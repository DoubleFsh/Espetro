package org.espetro.vehicle;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.EntityType;
import org.espetro.Espetro;
import org.espetro.team.FactionDataLoader;
import org.espetro.team.FactionDataProvider;

import javax.annotation.Nullable;
import java.util.*;

/**
 * 载具配置加载器
 * 完全从各编制 JSON 的 vehicles 节加载可部署载具。
 */
public class VehicleConfig {
    // factionId -> (vehicleType -> VehicleTypeConfig)
    private static final Map<String, Map<String, VehicleTypeConfig>> VEHICLE_CONFIGS = new LinkedHashMap<>();

    /**
     * 载具类型配置
     */
    public static class VehicleTypeConfig {
        public int max;
        public int respawnMinutes;
        /** 实体类型注册名，如 "minecraft:minecart" 或任意模组实体ID。 */
        @Nullable
        public String entityTypeStr;
        /** 显示名，含颜色代码，如 "§6运输卡车" */
        @Nullable
        public String displayName;
        public int troopValue;
        public DeploymentConfig deployment = new DeploymentConfig();

        public VehicleTypeConfig(int max, int respawnMinutes) {
            this.max = max;
            this.respawnMinutes = respawnMinutes;
        }

        /** 刷新时间（毫秒） */
        public long respawnMillis() {
            return respawnMinutes * 60_000L;
        }

        /**
         * 从注册名解析 EntityType，失败返回 null
         */
        @Nullable
        public EntityType<?> getEntityType() {
            if (entityTypeStr == null || entityTypeStr.isEmpty()) return null;
            ResourceLocation rl = ResourceLocation.tryParse(entityTypeStr);
            if (rl == null) return null;
            if (!BuiltInRegistries.ENTITY_TYPE.containsKey(rl)) return null;
            return BuiltInRegistries.ENTITY_TYPE.get(rl);
        }
    }

    public static class DeploymentConfig {
        @Nullable
        public DeploymentPointConfig attack;
        @Nullable
        public DeploymentPointConfig defend;

        @Nullable
        public DeploymentPointConfig forTeam(@Nullable String team) {
            if ("ATTACK".equalsIgnoreCase(team)) return attack;
            if ("DEFEND".equalsIgnoreCase(team)) return defend;
            return null;
        }
    }

    public static class DeploymentPointConfig {
        public int[] position;
        public float yaw = 0f;
    }

    /**
     * 加载载具配置（完全从各编制 JSON 读取）
     */
    public static void loadConfig(MinecraftServer server) {
        VEHICLE_CONFIGS.clear();

        // 确保编制数据已加载（含 vehicles 节）
        FactionDataLoader loader = FactionDataProvider.getOrCreateLoader();
        loader.ensureLoaded(server.getResourceManager());

        Map<String, Map<String, FactionDataLoader.VehicleData>> factionVehicles = loader.getAllFactionVehicles();

        for (Map.Entry<String, Map<String, FactionDataLoader.VehicleData>> entry : factionVehicles.entrySet()) {
            String factionId = entry.getKey();
            Map<String, VehicleTypeConfig> typeMap = new LinkedHashMap<>();

            for (Map.Entry<String, FactionDataLoader.VehicleData> vEntry : entry.getValue().entrySet()) {
                String vehicleType = vEntry.getKey();
                FactionDataLoader.VehicleData vd = vEntry.getValue();

                VehicleTypeConfig vtc = buildVehicleConfig(vehicleType, vd);

                typeMap.put(vehicleType, vtc);
            }

            VEHICLE_CONFIGS.put(factionId, typeMap);
        }

        Espetro.LOGGER.info("载具配置已加载: {} 个编制自定义了载具", VEHICLE_CONFIGS.size());
    }

    private static VehicleTypeConfig buildVehicleConfig(String vehicleType, FactionDataLoader.VehicleData vd) {
        int max = vd.max > 0 ? vd.max : FactionDataLoader.VehicleData.DEFAULT_MAX;
        int respawn = vd.respawnMinutes > 0
            ? vd.respawnMinutes
            : FactionDataLoader.VehicleData.DEFAULT_RESPAWN_MINUTES;

        VehicleTypeConfig cfg = new VehicleTypeConfig(max, respawn);
        cfg.entityTypeStr = vd.entityTypeStr;
        cfg.displayName = firstNonBlank(vd.displayName, vehicleType);
        cfg.troopValue = Math.max(0, vd.troopValue);
        cfg.deployment = buildDeploymentConfig(vehicleType, vd);
        if (cfg.entityTypeStr == null || cfg.entityTypeStr.isBlank()) {
            Espetro.LOGGER.warn("载具 {} 未配置 entity_type；请在对应编制 JSON 的 vehicles 节中配置", vehicleType);
        }
        return cfg;
    }

    private static DeploymentConfig buildDeploymentConfig(String vehicleType, FactionDataLoader.VehicleData vd) {
        DeploymentConfig cfg = new DeploymentConfig();

        FactionDataLoader.VehicleDeploymentData raw = vd.deployment;
        if (raw != null) {
            cfg.attack = buildDeploymentPoint(raw.attack);
            cfg.defend = buildDeploymentPoint(raw.defend);
        }

        if (cfg.attack == null) {
            Espetro.LOGGER.warn("载具 {} 未配置有效 deployment.ATTACK.position 坐标；必须在编制 JSON 中直接指定攻方坐标", vehicleType);
        }
        if (cfg.defend == null) {
            Espetro.LOGGER.warn("载具 {} 未配置有效 deployment.DEFEND.position 坐标；必须在编制 JSON 中直接指定守方坐标", vehicleType);
        }
        return cfg;
    }

    @Nullable
    private static DeploymentPointConfig buildDeploymentPoint(@Nullable FactionDataLoader.VehicleDeploymentPointData raw) {
        if (raw == null || !validVector(raw.position)) {
            return null;
        }

        DeploymentPointConfig point = new DeploymentPointConfig();
        point.position = raw.position;
        if (raw.yaw != null) {
            point.yaw = raw.yaw;
        }
        return point;
    }

    private static boolean validVector(@Nullable int[] vector) {
        return vector != null && vector.length >= 3;
    }

    @Nullable
    private static String firstNonBlank(@Nullable String first, @Nullable String fallback) {
        return first != null && !first.isBlank() ? first : fallback;
    }

    /**
     * 获取指定编制的载具配置
     */
    public static Map<String, VehicleTypeConfig> getFactionVehicles(String factionId) {
        return VEHICLE_CONFIGS.getOrDefault(factionId, Collections.emptyMap());
    }

    /**
     * 获取某个编制的某个载具类型配置
     */
    @Nullable
    public static VehicleTypeConfig getVehicleConfig(String factionId, String vehicleType) {
        Map<String, VehicleTypeConfig> map = VEHICLE_CONFIGS.get(factionId);
        if (map == null) return null;
        return map.get(vehicleType);
    }

    /**
     * 获取所有编制配置
     */
    public static Map<String, Map<String, VehicleTypeConfig>> getAllConfigs() {
        return new LinkedHashMap<>(VEHICLE_CONFIGS);
    }

    /**
     * 获取所有载具类型 key（用于命令补全）
     */
    public static Set<String> getAllVehicleTypeKeys() {
        Set<String> keys = new LinkedHashSet<>();
        for (Map<String, VehicleTypeConfig> typeMap : VEHICLE_CONFIGS.values()) {
            keys.addAll(typeMap.keySet());
        }
        return keys;
    }
}
