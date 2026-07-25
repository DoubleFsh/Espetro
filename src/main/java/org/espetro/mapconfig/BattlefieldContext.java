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
import java.util.concurrent.atomic.AtomicReference;

/**
 * Runtime holder for the currently active battlefield map configuration.
 * Only one map is active per match.
 */
public final class BattlefieldContext {

    private static final AtomicReference<ActiveMapConfig> ACTIVE = new AtomicReference<>(null);
    private static volatile String lastRoundWinner = null;

    private BattlefieldContext() {
    }

    public static void activate(ActiveMapConfig config) {
        if (config != null) {
            // Push into legacy static GameConfig / SpawnPointConfig for gradual migration.
            // Apply first so a malformed runtime bridge can never expose a
            // half-activated battlefield through the public context.
            GameConfigBridge.apply(config);
            ACTIVE.set(config);
            Espetro.LOGGER.info("激活战场配置: {} ({})", config.displayName, config.dimensionId);
            EspetroAPI.getActiveBattlefieldSnapshot().ifPresent(snapshot ->
                MinecraftForge.EVENT_BUS.post(new BattlefieldLifecycleEvent.Activated(snapshot)));
        } else {
            clear();
        }
    }

    public static void clear() {
        ActiveMapConfig previous = ACTIVE.getAndSet(null);
        if (previous != null) {
            ActiveBattlefieldSnapshot snapshot = new ActiveBattlefieldSnapshot(
                previous.mapFolder,
                previous.displayName,
                previous.dimensionKey,
                previous.esPoints.tacticalMapJson,
                previous.esPoints.capturePointsJson,
                previous.esPoints.backgroundImage,
                previous.esPoints.backgroundBytes()
            );
            MinecraftForge.EVENT_BUS.post(new BattlefieldLifecycleEvent.Cleared(snapshot));
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
