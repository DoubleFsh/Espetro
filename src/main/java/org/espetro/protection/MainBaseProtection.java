package org.espetro.protection;

import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import org.espetro.Espetro;
import org.espetro.config.GameConfig;
import org.espetro.mapconfig.BattlefieldContext;
import org.espetro.team.SpawnPointConfig;
import org.espetro.vehicle.VehicleManager;

import javax.annotation.Nullable;
import java.util.Locale;

/**
 * 本方原部署点无敌区的统一、事件驱动判定。
 *
 * <p>范围采用 X/Z 水平距离，保证高低差较大的基地、直升机和高架载具
 * 仍使用同一张基地安全区。这里只做常量时间查询，不扫描世界或逐 tick 遍历实体。</p>
 */
public final class MainBaseProtection {

    private static final String TEAM_TAG_PREFIX = "espetro_team_";

    private MainBaseProtection() {
    }

    /**
     * 玩家按自己的阵营判定；载具优先使用 Espetro 部署时记录的所属阵营，
     * 再回退到持久标签和当前驾驶员阵营。
     */
    public static boolean isProtected(@Nullable Entity entity) {
        if (entity == null || entity.level().isClientSide
            || !(entity.level() instanceof ServerLevel level)
            || !BattlefieldContext.isActiveBattlefield(level)) {
            return false;
        }

        double radius = GameConfig.getMainBaseInvulnerabilityRadius();
        if (radius <= 0.0) {
            return false;
        }

        String team = entity instanceof ServerPlayer player
            ? normalizeTeam(Espetro.getPlayerTeam(player))
            : resolveVehicleTeam(entity);
        if (team == null) {
            return false;
        }

        SpawnPointConfig.SpawnPoint spawn = SpawnPointConfig.getSpawnPoint(team);
        if (spawn == null) {
            return false;
        }
        return isWithinHorizontalRadius(entity.getX(), entity.getZ(), spawn.x, spawn.z, radius);
    }

    static boolean isWithinHorizontalRadius(double x, double z, double centerX, double centerZ,
                                            double radius) {
        if (radius <= 0.0) {
            return false;
        }
        double deltaX = x - centerX;
        double deltaZ = z - centerZ;
        return deltaX * deltaX + deltaZ * deltaZ <= radius * radius;
    }

    @Nullable
    private static String resolveVehicleTeam(Entity entity) {
        VehicleManager vehicles = VehicleManager.getInstance();
        String tracked = normalizeTeam(vehicles.getTrackedVehicleTeam(entity.getUUID()));
        if (tracked != null) {
            return tracked;
        }

        String persistent = null;
        if (entity.getPersistentData().contains(VehicleManager.VEHICLE_TEAM_KEY, Tag.TAG_STRING)) {
            persistent = normalizeTeam(
                entity.getPersistentData().getString(VehicleManager.VEHICLE_TEAM_KEY));
        }
        if (persistent != null) {
            return persistent;
        }

        for (String tag : entity.getTags()) {
            if (tag.startsWith(TEAM_TAG_PREFIX)) {
                String fromTag = normalizeTeam(tag.substring(TEAM_TAG_PREFIX.length()));
                if (fromTag != null) {
                    return fromTag;
                }
            }
        }

        Entity controller = entity.getControllingPassenger();
        if (controller instanceof ServerPlayer player) {
            String controllerTeam = normalizeTeam(Espetro.getPlayerTeam(player));
            if (controllerTeam != null) {
                return controllerTeam;
            }
        }
        return resolvePassengerTeam(entity);
    }

    @Nullable
    private static String resolvePassengerTeam(Entity vehicle) {
        for (Entity passenger : vehicle.getPassengers()) {
            if (passenger instanceof ServerPlayer player) {
                String team = normalizeTeam(Espetro.getPlayerTeam(player));
                if (team != null) {
                    return team;
                }
            }
            String nested = resolvePassengerTeam(passenger);
            if (nested != null) {
                return nested;
            }
        }
        return null;
    }

    @Nullable
    private static String normalizeTeam(@Nullable String team) {
        if (team == null || team.isBlank()) {
            return null;
        }
        String normalized = team.trim().toUpperCase(Locale.ROOT);
        return "ATTACK".equals(normalized) || "DEFEND".equals(normalized)
            ? normalized
            : null;
    }
}
