package org.espetro.mapconfig;

import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import org.espetro.Espetro;
import net.minecraftforge.common.MinecraftForge;
import org.espetro.api.ActiveBattlefieldSnapshot;
import org.espetro.api.EspetroAPI;
import org.espetro.api.event.BattlefieldLifecycleEvent;

import javax.annotation.Nullable;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Runtime holder for the currently active battlefield map configuration.
 * Only one map is active per match.
 */
public final class BattlefieldContext {

    private static final AtomicReference<ActiveMapConfig> ACTIVE = new AtomicReference<>(null);
    private static final AtomicLong SESSION_ID = new AtomicLong(0L);
    private static volatile String lastRoundWinner = null;
    /** Filled by ESPoints after it resolves AAS/RAAS from EsConfig. */
    private static volatile String resolvedObjectiveMode = "";
    private static volatile String resolvedObjectiveLane = "";

    private BattlefieldContext() {
    }

    public static void activate(ActiveMapConfig config) {
        if (config != null) {
            ActiveMapConfig roundConfig = config.forRound(
                ThreadLocalRandom.current().nextLong());
            // Push into legacy static GameConfig / SpawnPointConfig for gradual migration.
            // Apply first so a malformed runtime bridge can never expose a
            // half-activated battlefield through the public context.
            GameConfigBridge.apply(roundConfig);
            ACTIVE.set(roundConfig);
            SESSION_ID.incrementAndGet();
            Espetro.LOGGER.info("激活战场配置: {} ({})，目标模式={}，路线={}",
                roundConfig.displayName,
                roundConfig.dimensionId,
                roundConfig.esPoints.objectiveMode,
                roundConfig.esPoints.objectiveLane.isEmpty()
                    ? "固定路线"
                    : roundConfig.esPoints.objectiveLane);
            EspetroAPI.getActiveBattlefieldSnapshot().ifPresent(snapshot ->
                MinecraftForge.EVENT_BUS.post(new BattlefieldLifecycleEvent.Activated(snapshot)));
        } else {
            clear();
        }
    }

    public static void clear() {
        SESSION_ID.incrementAndGet();
        ActiveMapConfig previous = ACTIVE.getAndSet(null);
        if (previous != null) {
            String esConfigPath = previous.esConfigDir != null
                ? previous.esConfigDir.toAbsolutePath().normalize().toString()
                : "";
            ActiveBattlefieldSnapshot snapshot = new ActiveBattlefieldSnapshot(
                previous.mapFolder,
                previous.displayName,
                previous.dimensionKey,
                esConfigPath,
                previous.esPoints != null ? previous.esPoints.tacticalMapJson : "",
                previous.esPoints != null ? previous.esPoints.capturePointsJson : "",
                previous.esPoints != null ? previous.esPoints.backgroundImage : "",
                previous.esPoints != null ? previous.esPoints.backgroundBytes() : new byte[0],
                previous.esPoints != null ? previous.esPoints.backgroundSha256 : "",
                previous.esPoints != null ? previous.esPoints.backgroundWidth : 0,
                previous.esPoints != null ? previous.esPoints.backgroundHeight : 0,
                previous.esPoints != null ? previous.esPoints.objectiveMode : "",
                previous.esPoints != null ? previous.esPoints.objectiveLane : "",
                previous.esPoints != null ? previous.esPoints.objectiveSeed : 0L
            );
            MinecraftForge.EVENT_BUS.post(new BattlefieldLifecycleEvent.Cleared(snapshot));
            resolvedObjectiveMode = "";
            resolvedObjectiveLane = "";
            Espetro.LOGGER.info("已清除活动战场配置");
        }
    }

    public static Optional<ActiveMapConfig> get() {
        return Optional.ofNullable(ACTIVE.get());
    }

    @Nullable
    public static ActiveMapConfig getOrNull() {
        return ACTIVE.get();
    }

    public static boolean isActive() {
        return ACTIVE.get() != null;
    }

    public static String getObjectiveMode() {
        if (resolvedObjectiveMode != null && !resolvedObjectiveMode.isBlank()) {
            return resolvedObjectiveMode;
        }
        ActiveMapConfig config = ACTIVE.get();
        return config == null || config.esPoints == null
            ? ""
            : config.esPoints.objectiveMode;
    }

    public static void setResolvedObjective(String mode, String laneId) {
        resolvedObjectiveMode = mode == null ? "" : mode.trim();
        resolvedObjectiveLane = laneId == null ? "" : laneId.trim();
    }

    public static String getObjectiveLane() {
        return resolvedObjectiveLane == null ? "" : resolvedObjectiveLane;
    }

    /**
     * Monotonically increasing token used by delayed effects. A token captured
     * in an earlier battlefield copy can never become valid in a later round,
     * even when both rounds use the same map and dimension id.
     */
    public static long getSessionId() {
        return SESSION_ID.get();
    }

    public static Optional<ResourceKey<Level>> getActiveDimensionKey() {
        ActiveMapConfig cfg = ACTIVE.get();
        return cfg == null ? Optional.empty() : Optional.of(cfg.dimensionKey);
    }

    public static boolean isActiveBattlefield(@Nullable ServerLevel level) {
        if (level == null) {
            return false;
        }
        ActiveMapConfig cfg = ACTIVE.get();
        return cfg != null && cfg.dimensionKey.equals(level.dimension());
    }

    public static boolean isActiveBattlefield(ResourceKey<Level> key) {
        ActiveMapConfig cfg = ACTIVE.get();
        return cfg != null && cfg.dimensionKey.equals(key);
    }

    /**
     * Prefer active battlefield; fall back to overworld only when no map is active
     * (should not happen during DEPLOYING/BATTLE).
     */
    public static ServerLevel requireBattlefield(net.minecraft.server.MinecraftServer server) {
        ActiveMapConfig cfg = ACTIVE.get();
        if (cfg != null) {
            ServerLevel level = server.getLevel(cfg.dimensionKey);
            if (level != null) {
                return level;
            }
            Espetro.LOGGER.error("活动战场维度未加载: {}", cfg.dimensionId);
        }
        return server.overworld();
    }

    public static void setLastRoundWinner(String winner) {
        lastRoundWinner = winner;
    }

    public static String getLastRoundWinner() {
        return lastRoundWinner;
    }
}
