package org.espetro.dimension;

import net.minecraft.Util;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.progress.ChunkProgressListener;
import net.minecraft.server.level.progress.LoggerChunkProgressListener;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.border.BorderChangeListener;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.storage.DerivedLevelData;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.storage.ServerLevelData;
import net.minecraft.world.level.storage.WorldData;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.level.LevelEvent;
import org.espetro.Espetro;
import org.espetro.mapconfig.ActiveMapConfig;
import org.espetro.mapconfig.BattlefieldContext;
import org.espetro.mapconfig.ExternalConfigBootstrap;

import javax.annotation.Nullable;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Maintains disposable per-save battlefield copies backed by read-only
 * EsWorld templates.
 */
public final class BattlefieldWorldManager {

    public enum ImportState {
        IDLE,
        UNLOADING,
        COPYING,
        LOADING,
        READY,
        FAILED,
        CLEANING
    }

    private static final BattlefieldWorldManager INSTANCE = new BattlefieldWorldManager();
    private static final ExecutorService IO = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "Espetro-Battlefield-IO");
        thread.setDaemon(true);
        return thread;
    });

    private final AtomicBoolean busy = new AtomicBoolean(false);
    /** Templates that passed startup copying and remain eligible for voting. */
    private final Set<ResourceLocation> availableDimensions = new LinkedHashSet<>();
    /** Startup-created levels that have not yet been used by a match. */
    private final Set<ResourceLocation> freshDimensions = new LinkedHashSet<>();
    private volatile ImportState state = ImportState.IDLE;
    private volatile String lastError = null;
    private volatile ActiveMapConfig lastLoaded = null;
    private final Object pendingCleanupLock = new Object();
    private boolean pendingCleanupRequested;
    private ActiveMapConfig pendingCleanupMap;
    private final List<Consumer<Result>> pendingCleanupCallbacks = new ArrayList<>();

    private BattlefieldWorldManager() {
    }

    public static BattlefieldWorldManager getInstance() {
        return INSTANCE;
    }

    public ImportState getState() {
        return state;
    }

    public String getLastError() {
        return lastError;
    }

    public boolean isBusy() {
        return busy.get();
    }

    /**
     * Copies all accepted templates before vanilla creates its initial
     * ServerLevels. Once a map has been used, cleanup removes this disposable
     * copy and later rounds import only the selected template again.
     */
    public int prepareAllAtStartup(MinecraftServer server) {
        if (!busy.compareAndSet(false, true)) {
            Espetro.LOGGER.error("战场地图启动准备重复执行");
            return 0;
        }
        availableDimensions.clear();
        freshDimensions.clear();
        lastError = null;
        lastLoaded = null;
        state = ImportState.COPYING;
        try {
            ExternalConfigBootstrap.bootstrapIfNeeded();
            for (ActiveMapConfig map : ExternalConfigBootstrap.getUsableMaps()) {
                Path dimPath = dimensionDirectory(server, map.dimensionKey);
                Path tempPath = dimPath.resolveSibling(
                    dimPath.getFileName().toString() + ".importing");
                long startedNanos = System.nanoTime();
                Result result = copyTemplate(map, dimPath, tempPath);
                if (result.success()) {
                    availableDimensions.add(map.dimensionId);
                    freshDimensions.add(map.dimensionId);
                    long millis = (System.nanoTime() - startedNanos) / 1_000_000L;
                    Espetro.LOGGER.info("启动时已准备战场地图: {} -> {} ({} ms)",
                        map.displayName, dimPath, millis);
                } else {
                    lastError = result.error();
                    Espetro.LOGGER.error("地图 {} 启动准备失败: {}",
                        map.displayName, result.error());
                }
            }
            return availableDimensions.size();
        } finally {
            state = ImportState.IDLE;
            busy.set(false);
        }
    }

    public boolean isPrepared(ActiveMapConfig map) {
        return map != null && availableDimensions.contains(map.dimensionId);
    }

    /**
     * Activates a pristine startup level or imports the selected EsWorld
     * template and mounts a new ServerLevel. Callback runs on the server thread.
     */
    public void importAndLoad(MinecraftServer server, ActiveMapConfig map, Consumer<Result> onComplete) {
        if (!busy.compareAndSet(false, true)) {
            onComplete.accept(Result.fail("战场正在切换中"));
            return;
        }
        lastError = null;
        state = ImportState.LOADING;
        server.execute(() -> {
            try {
                if (!isPrepared(map)) {
                    fail(server, "地图模板在启动阶段准备失败: " + map.displayName, onComplete);
                    return;
                }
                ServerLevel level = server.getLevel(map.dimensionKey);
                if (level != null && freshDimensions.remove(map.dimensionId)) {
                    activate(server, map, onComplete);
                    return;
                }
                rebuildSelectedMap(server, map, level, onComplete);
            } catch (Exception e) {
                Espetro.LOGGER.error("战场导入启动失败", e);
                fail(server, e.getMessage(), onComplete);
            }
        });
    }

    /**
     * Unmounts the disposable ServerLevel without saving match damage, closes
     * its storage handles off-thread, and deletes the whole save-side dimension
     * directory. EsWorld is never written.
     */
    public void cleanupBattlefield(MinecraftServer server, @Nullable ActiveMapConfig map, Runnable onDone) {
        cleanupBattlefield(server, map, ignored -> {
            if (onDone != null) {
                onDone.run();
            }
        });
    }

    public void cleanupBattlefield(
        MinecraftServer server,
        @Nullable ActiveMapConfig map,
        Consumer<Result> onComplete
    ) {
        if (!busy.compareAndSet(false, true)) {
            synchronized (pendingCleanupLock) {
                pendingCleanupRequested = true;
                if (map != null) {
                    pendingCleanupMap = map;
                }
                if (onComplete != null) {
                    pendingCleanupCallbacks.add(onComplete);
                }
            }
            Espetro.LOGGER.warn("战场正忙，清理请求已排队");
            return;
        }
        state = ImportState.CLEANING;
        server.execute(() -> {
            ActiveMapConfig target = map != null ? map
                : lastLoaded != null ? lastLoaded : BattlefieldContext.getOrNull();
            try {
                BattlefieldContext.clear();
                lastLoaded = null;
                if (target == null) {
                    finishCleanup(server, Result.ok(), onComplete);
                    return;
                }

                freshDimensions.remove(target.dimensionId);
                ServerLevel discarded = detachForDiscard(server, target.dimensionKey);
                Path dimPath = dimensionDirectory(server, target.dimensionKey);
                Path tempPath = importingPath(dimPath);
                CompletableFuture
                    .supplyAsync(() -> discardAndDelete(discarded, dimPath, tempPath), IO)
                    .whenComplete((result, error) -> server.execute(() -> {
                        Result completed = error == null
                            ? result
                            : Result.fail("删除战场存档副本失败: " + error.getMessage());
                        finishCleanup(server, completed, onComplete);
                    }));
            } catch (Exception e) {
                Espetro.LOGGER.error("战场清理失败", e);
                finishCleanup(server, Result.fail(e.getMessage()), onComplete);
            }
        });
    }

    private void rebuildSelectedMap(
        MinecraftServer server,
        ActiveMapConfig map,
        @Nullable ServerLevel existing,
        Consumer<Result> onComplete
    ) {
        state = existing == null ? ImportState.COPYING : ImportState.UNLOADING;
        ServerLevel discarded = existing == null ? null : detachForDiscard(server, map.dimensionKey);
        freshDimensions.remove(map.dimensionId);
        Path dimPath = dimensionDirectory(server, map.dimensionKey);
        Path tempPath = importingPath(dimPath);

        CompletableFuture
            .supplyAsync(() -> {
                Result discardedResult = closeDiscardedLevel(discarded);
                if (!discardedResult.success()) {
                    return discardedResult;
                }
                state = ImportState.COPYING;
                return copyTemplate(map, dimPath, tempPath);
            }, IO)
            .whenComplete((result, error) -> server.execute(() -> {
                if (error != null) {
                    fail(server, "复制地图失败: " + error.getMessage(), onComplete);
                    return;
                }
                if (result == null || !result.success()) {
                    fail(server, result == null ? "复制地图失败" : result.error(), onComplete);
                    return;
                }
                state = ImportState.LOADING;
                ServerLevel created = createServerLevel(server, map.dimensionKey);
                if (created == null) {
                    fail(server, "创建战场维度失败: " + map.dimensionId, onComplete);
                    return;
                }
                activate(server, map, onComplete);
            }));
    }

    private void activate(
        MinecraftServer server,
        ActiveMapConfig map,
        Consumer<Result> onComplete
    ) {
        ServerLevel level = server.getLevel(map.dimensionKey);
        if (level == null) {
            fail(server, "战场维度未挂载: " + map.dimensionId, onComplete);
            return;
        }
        BattlefieldContext.activate(map);
        lastLoaded = map;
        state = ImportState.READY;
        busy.set(false);
        Espetro.LOGGER.info("战场地图已从只读模板副本就绪: {}", map.dimensionId);
        safeAccept(onComplete, Result.ok());
        drainPendingCleanup(server);
    }

    private void finishCleanup(
        MinecraftServer server,
        Result result,
        Consumer<Result> onComplete
    ) {
        if (result.success()) {
            state = ImportState.IDLE;
            lastError = null;
            Espetro.LOGGER.info("战场存档维度副本已删除，等待下一局重新导入");
        } else {
            state = ImportState.FAILED;
            lastError = result.error();
            Espetro.LOGGER.error("战场存档维度副本删除失败: {}", result.error());
        }
        busy.set(false);
        safeAccept(onComplete, result);
        drainPendingCleanup(server);
    }

    private void fail(MinecraftServer server, String error, Consumer<Result> onComplete) {
        lastError = error;
        state = ImportState.FAILED;
        BattlefieldContext.clear();
        busy.set(false);
        safeAccept(onComplete, Result.fail(error));
        drainPendingCleanup(server);
    }

    private static void safeAccept(Consumer<Result> callback, Result result) {
        if (callback == null) {
            return;
        }
        try {
            callback.accept(result);
        } catch (Exception callbackError) {
            Espetro.LOGGER.error("战场切换回调执行失败", callbackError);
        }
    }

    private void drainPendingCleanup(MinecraftServer server) {
        ActiveMapConfig map;
        List<Consumer<Result>> callbacks;
        synchronized (pendingCleanupLock) {
            if (!pendingCleanupRequested) {
                return;
            }
            pendingCleanupRequested = false;
            map = pendingCleanupMap;
            pendingCleanupMap = null;
            callbacks = new ArrayList<>(pendingCleanupCallbacks);
            pendingCleanupCallbacks.clear();
        }
        cleanupBattlefield(server, map, result -> {
            for (Consumer<Result> callback : callbacks) {
                try {
                    callback.accept(result);
                } catch (Exception e) {
                    Espetro.LOGGER.error("排队的战场清理回调执行失败", e);
                }
            }
        });
    }

    public void resetAfterServerStop() {
        busy.set(false);
        availableDimensions.clear();
        freshDimensions.clear();
        state = ImportState.IDLE;
        lastError = null;
        lastLoaded = null;
        BattlefieldContext.clear();
        synchronized (pendingCleanupLock) {
            pendingCleanupRequested = false;
            pendingCleanupMap = null;
            pendingCleanupCallbacks.clear();
        }
    }

    private static Path importingPath(Path dimPath) {
        return dimPath.resolveSibling(dimPath.getFileName().toString() + ".importing");
    }

    /**
     * Stops the level from participating in subsequent server ticks. The
     * caller has already returned normal players to the hub, but this guard
     * also handles direct API calls.
     */
    @Nullable
    private ServerLevel detachForDiscard(MinecraftServer server, ResourceKey<Level> key) {
        ServerLevel level = server.getLevel(key);
        if (level == null) {
            return null;
        }

        BlockPos hubSpawn = server.overworld().getSharedSpawnPos();
        for (var player : new ArrayList<>(level.players())) {
            player.teleportTo(server.overworld(),
                hubSpawn.getX() + 0.5, hubSpawn.getY(), hubSpawn.getZ() + 0.5, 0f, 0f);
        }

        MinecraftForge.EVENT_BUS.post(new LevelEvent.Unload(level));
        Map<ResourceKey<Level>, ServerLevel> levels = server.forgeGetWorldMap();
        if (levels.get(key) == level) {
            levels.remove(key);
            server.markWorldsDirty();
        }
        removeBorderDelegate(server, level);
        level.noSave = true;
        level.invalidateCaps();
        Espetro.LOGGER.info("已从服务器 Tick 列表摘除可丢弃战场维度: {}", key.location());
        return level;
    }

    private static void removeBorderDelegate(MinecraftServer server, ServerLevel level) {
        var mainBorder = server.overworld().getWorldBorder();
        var discardedBorder = level.getWorldBorder();
        mainBorder.listeners.removeIf(listener ->
            listener instanceof BorderChangeListener.DelegateBorderChangeListener delegate
                && delegate.worldBorder == discardedBorder);
    }

    /**
     * Releases region/entity/POI handles without saving loaded match chunks.
     * Never call ServerLevel.close() here: it invokes save(true) first.
     */
    private Result closeDiscardedLevel(@Nullable ServerLevel level) {
        if (level == null) {
            return Result.ok();
        }

        Exception failure = null;
        try {
            level.entityManager.permanentStorage.close();
        } catch (Exception e) {
            failure = appendFailure(failure, e);
        }

        ServerChunkCache chunks = level.getChunkSource();
        try {
            chunks.getLightEngine().close();
        } catch (Exception e) {
            failure = appendFailure(failure, e);
        }
        try {
            chunks.chunkMap.close();
        } catch (Exception e) {
            failure = appendFailure(failure, e);
        }

        return failure == null
            ? Result.ok()
            : Result.fail("关闭旧战场存储失败: " + failure.getMessage());
    }

    private Result discardAndDelete(
        @Nullable ServerLevel discarded,
        Path dimPath,
        Path tempPath
    ) {
        Result closed = closeDiscardedLevel(discarded);
        Exception failure = closed.success() ? null : new IOException(closed.error());
        try {
            deleteRecursively(tempPath);
            deleteRecursively(dimPath);
        } catch (Exception e) {
            failure = appendFailure(failure, e);
        }
        return failure == null
            ? Result.ok()
            : Result.fail("删除存档战场维度失败: " + failure.getMessage());
    }

    private static Exception appendFailure(@Nullable Exception current, Exception next) {
        if (current == null) {
            return next;
        }
        current.addSuppressed(next);
        return current;
    }

    @Nullable
    private ServerLevel createServerLevel(MinecraftServer server, ResourceKey<Level> key) {
        ServerLevel level = null;
        try {
            WorldData worldData = server.getWorldData();
            ServerLevelData overworldData = worldData.overworldData();
            ResourceKey<LevelStem> stemKey =
                ResourceKey.create(Registries.LEVEL_STEM, key.location());
            LevelStem stem = server.registryAccess()
                .registryOrThrow(Registries.LEVEL_STEM)
                .get(stemKey);
            if (stem == null) {
                Espetro.LOGGER.error("无法解析战场 LevelStem: {}", key.location());
                return null;
            }

            DerivedLevelData derived = new DerivedLevelData(worldData, overworldData);
            ChunkProgressListener progress = new LoggerChunkProgressListener(11);
            level = new ServerLevel(
                server,
                // Match MinecraftServer#createLevels. Passing the server
                // (main-thread executor) here deadlocks ChunkMap's sorter
                // while its constructor waits for mailbox initialization.
                Util.backgroundExecutor(),
                server.storageSource,
                derived,
                key,
                stem,
                progress,
                worldData.isDebugWorld(),
                BiomeManager.obfuscateSeed(worldData.worldGenOptions().seed()),
                List.of(),
                false,
                server.overworld().getRandomSequences()
            );
            level.setDefaultSpawnPos(new BlockPos(0, 64, 0), 0f);
            level.getChunkSource().setSpawnSettings(
                server.isSpawningMonsters(), server.isSpawningAnimals());

            BorderChangeListener delegate =
                new BorderChangeListener.DelegateBorderChangeListener(level.getWorldBorder());
            server.overworld().getWorldBorder().addListener(delegate);
            server.forgeGetWorldMap().put(key, level);
            server.markWorldsDirty();
            MinecraftForge.EVENT_BUS.post(new LevelEvent.Load(level));
            Espetro.LOGGER.info("已挂载从 EsWorld 重新复制的战场维度: {}", key.location());
            return level;
        } catch (Exception e) {
            Espetro.LOGGER.error("创建战场 ServerLevel 失败: {}", key.location(), e);
            if (level != null) {
                ServerLevel failedLevel = level;
                server.forgeGetWorldMap().remove(key, level);
                server.markWorldsDirty();
                removeBorderDelegate(server, failedLevel);
                IO.execute(() -> closeDiscardedLevel(failedLevel));
            }
            return null;
        }
    }

    private Result copyTemplate(ActiveMapConfig map, Path dimPath, Path tempPath) {
        return replaceSaveCopy(map.templateWorldDir, dimPath, tempPath);
    }

    /**
     * Replaces a disposable save-side dimension with a pristine template copy.
     * Package-private so the exact filesystem transaction can be regression
     * tested without booting a Minecraft server.
     */
    static Result replaceSaveCopy(Path template, Path dimPath, Path tempPath) {
        try {
            deleteRecursively(tempPath);
            Files.createDirectories(tempPath);
            // Copy only region, entities, poi, data, EsConfig — never level.dat / playerdata / etc.
            for (String child : List.of("region", "entities", "poi", "data", "EsConfig")) {
                Path src = template.resolve(child);
                if (Files.exists(src)) {
                    copyDirectory(src, tempPath.resolve(child));
                }
            }
            // Atomic replace: remove target then move temp
            deleteRecursively(dimPath);
            Files.createDirectories(dimPath.getParent());
            try {
                Files.move(tempPath, dimPath, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
                Files.move(tempPath, dimPath);
            }
            return Result.ok();
        } catch (Exception e) {
            try {
                deleteRecursively(tempPath);
            } catch (Exception ignored) {
            }
            return Result.fail("复制模板失败: " + e.getMessage());
        }
    }

    private Path dimensionDirectory(MinecraftServer server, ResourceKey<Level> key) {
        // getWorldDir() is the saves container (for example run/saves), not the
        // currently opened save. LevelResource.ROOT resolves the selected save.
        Path worldRoot = server.storageSource.getLevelPath(LevelResource.ROOT);
        return validateDimensionDirectory(worldRoot, server.storageSource.getDimensionPath(key));
    }

    static Path validateDimensionDirectory(Path worldRoot, Path dimensionPath) {
        Path normalizedWorldRoot = worldRoot.toAbsolutePath().normalize();
        Path normalizedDimension = dimensionPath.toAbsolutePath().normalize();
        Path allowedRoot = normalizedWorldRoot.resolve("dimensions").normalize();
        if (!normalizedDimension.startsWith(allowedRoot)
            || normalizedDimension.equals(allowedRoot)) {
            throw rejectedDimensionPath(normalizedDimension);
        }

        // The lexical containment check above blocks "..". Resolve every
        // existing ancestor as well so a dimensions/ or namespace symlink
        // cannot redirect copy/delete operations outside the selected save.
        try {
            Path realAllowedRoot = resolveExistingAncestors(allowedRoot);
            Path realDimension = resolveExistingAncestors(normalizedDimension);
            if (!realDimension.startsWith(realAllowedRoot)
                || realDimension.equals(realAllowedRoot)) {
                throw rejectedDimensionPath(normalizedDimension);
            }
        } catch (IOException e) {
            throw new IllegalStateException(
                "无法安全校验存档维度目录: " + normalizedDimension, e);
        }
        return normalizedDimension;
    }

    private static Path resolveExistingAncestors(Path path) throws IOException {
        Path normalized = path.toAbsolutePath().normalize();
        Path existing = normalized;
        List<Path> missingSegments = new ArrayList<>();
        while (existing != null
            && !Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) {
            Path name = existing.getFileName();
            if (name != null) {
                missingSegments.add(name);
            }
            existing = existing.getParent();
        }
        if (existing == null) {
            throw new IOException("路径没有可解析的现存父目录: " + normalized);
        }

        Path resolved = existing.toRealPath();
        for (int i = missingSegments.size() - 1; i >= 0; i--) {
            resolved = resolved.resolve(missingSegments.get(i));
        }
        return resolved.normalize();
    }

    private static IllegalStateException rejectedDimensionPath(Path dimensionPath) {
        return new IllegalStateException(
            "拒绝访问存档维度目录之外的路径: " + dimensionPath);
    }

    private static void copyDirectory(Path source, Path target) throws IOException {
        Path sourceRoot = source.toRealPath();
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                if (Files.isSymbolicLink(dir) || !dir.toRealPath().startsWith(sourceRoot)) {
                    throw new IOException("地图模板包含越界符号链接目录: " + dir);
                }
                Path rel = source.relativize(dir);
                Path dest = target.resolve(rel.toString());
                Files.createDirectories(dest);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (Files.isSymbolicLink(file) || !file.toRealPath().startsWith(sourceRoot)) {
                    throw new IOException("地图模板包含符号链接或越界文件: " + file);
                }
                Path rel = source.relativize(file);
                Path dest = target.resolve(rel.toString());
                Files.createDirectories(dest.getParent());
                Files.copy(file, dest, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void deleteRecursively(Path path) throws IOException {
        if (path == null || !Files.exists(path)) {
            return;
        }
        Files.walkFileTree(path, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.deleteIfExists(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                Files.deleteIfExists(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    public record Result(boolean success, @Nullable String error) {
        public static Result ok() {
            return new Result(true, null);
        }

        public static Result fail(String error) {
            return new Result(false, error);
        }
    }
}
