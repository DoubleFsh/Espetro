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
 * 从各编制 JSON 的 vehicles 节加载载具配置（无全局配置文件）
 */
public class VehicleConfig {

    // factionId -> (vehicleType -> VehicleTypeConfig)
    private static final Map<String, Map<String, VehicleTypeConfig>> VEHICLE_CONFIGS = new LinkedHashMap<>();

    // 部署半径（全局常量）
    public static final int SPAWN_RADIUS = 6;

    /**
     * 载具类型配置
     */
    public static class VehicleTypeConfig {
        public int max;
        public int respawnMinutes;
        /** 实体类型注册名，如 "minecraft:cow" */
        @Nullable
        public String entityTypeStr;
        /** 显示名，含颜色代码，如 "§6运输卡车" */
        @Nullable
        public String displayName;

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
            ResourceLocation rl = ResourceLocation.parse(entityTypeStr);
            return BuiltInRegistries.ENTITY_TYPE.get(rl);
        }
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

                VehicleTypeConfig vtc = new VehicleTypeConfig(vd.max, vd.respawnMinutes);
                vtc.entityTypeStr = vd.entityTypeStr;
                vtc.displayName = vd.displayName;

                typeMap.put(vehicleType, vtc);
            }

            VEHICLE_CONFIGS.put(factionId, typeMap);
        }

        Espetro.LOGGER.info("载具配置已加载: {} 个编制自定义了载具", VEHICLE_CONFIGS.size());
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
