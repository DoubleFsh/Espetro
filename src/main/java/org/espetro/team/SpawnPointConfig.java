package org.espetro.team;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import org.espetro.Espetro;

import java.util.HashMap;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * 复活点配置加载器
 * 从 data/espetro/config/spawn_points.json 加载复活点坐标
 */
public class SpawnPointConfig {

    private static final Gson GSON = new Gson();
    private static final String CONFIG_PATH = "espetro/config/spawn_points.json";

    // 队伍复活点配置
    private static final Map<String, SpawnPoint> SPAWN_POINTS = new HashMap<>();

    // 默认复活点（如果配置文件不存在或读取失败）
    static {
        SPAWN_POINTS.put("ATTACK", new SpawnPoint(100.5, 65, 0.5, 0));
        SPAWN_POINTS.put("DEFEND", new SpawnPoint(-100.5, 65, 0.5, 180));
    }

    /**
     * 复活点数据类
     */
    public static class SpawnPoint {
        public double x;
        public double y;
        public double z;
        public float yaw;

        public SpawnPoint() {}

        public SpawnPoint(double x, double y, double z, float yaw) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.yaw = yaw;
        }

        public float getPitch() {
            return 0f;
        }
    }

    /**
     * 加载复活点配置
     */
    public static void loadConfig(MinecraftServer server) {
        try {
            ResourceManager resourceManager = server.getResourceManager();
            
            // 先尝试从数据包加载
            ResourceLocation configLocation = ResourceLocation.fromNamespaceAndPath("espetro", "config/spawn_points.json");
            
            var resourceOptional = resourceManager.getResource(configLocation);
            if (resourceOptional.isPresent()) {
                try (var inputStream = resourceOptional.get().open()) {
                    String json = new String(inputStream.readAllBytes());
                    parseAndApplyConfig(json);
                    Espetro.LOGGER.info("成功从数据包加载复活点配置");
                    return;
                }
            }
            
            // 文件不存在，重置为默认值
            applyDefaults();
            Espetro.LOGGER.warn("未找到复活点配置文件，使用默认配置");
        } catch (Exception e) {
            applyDefaults();
            Espetro.LOGGER.error("加载复活点配置失败，使用默认配置", e);
        }
    }

    /**
     * 重置为默认复活点
     */
    private static void applyDefaults() {
        SPAWN_POINTS.clear();
        SPAWN_POINTS.put("ATTACK", new SpawnPoint(100.5, 65, 0.5, 0));
        SPAWN_POINTS.put("DEFEND", new SpawnPoint(-100.5, 65, 0.5, 180));
    }

    /**
     * 解析并应用配置
     */
    private static void parseAndApplyConfig(String json) {
        JsonObject root = GSON.fromJson(json, JsonObject.class);
        
        if (root.has("spawnPoints")) {
            JsonObject spawnPoints = root.getAsJsonObject("spawnPoints");
            
            // 先重置为默认值，再覆盖（防止重载时部分字段残留旧值）
            applyDefaults();
            
            for (String team : new String[]{"ATTACK", "DEFEND"}) {
                if (spawnPoints.has(team)) {
                    JsonObject point = spawnPoints.getAsJsonObject(team);
                    SpawnPoint spawn = new SpawnPoint();
                    spawn.x = getDouble(point, "x", 0);
                    spawn.y = getDouble(point, "y", 65);
                    spawn.z = getDouble(point, "z", 0);
                    spawn.yaw = (float) getDouble(point, "yaw", team.equals("ATTACK") ? 0 : 180);
                    
                    SPAWN_POINTS.put(team, spawn);
                    Espetro.LOGGER.info("加载 {} 复活点: ({}, {}, {}), yaw: {}", 
                        team, spawn.x, spawn.y, spawn.z, spawn.yaw);
                }
            }
        }
    }

    private static double getDouble(JsonObject obj, String key, double defaultValue) {
        if (obj.has(key)) {
            return obj.get(key).getAsDouble();
        }
        return defaultValue;
    }

    /**
     * 获取指定队伍的复活点
     */
    public static SpawnPoint getSpawnPoint(String team) {
        SpawnPoint point = SPAWN_POINTS.get(team);
        if (point == null) {
            point = SPAWN_POINTS.get("DEFEND"); // 默认
        }
        return point;
    }

    /**
     * 动态设置复活点
     */
    public static void setSpawnPoint(String team, double x, double y, double z, float yaw) {
        SPAWN_POINTS.put(team, new SpawnPoint(x, y, z, yaw));
        Espetro.LOGGER.info("动态设置 {} 复活点: ({}, {}, {}), yaw: {}", team, x, y, z, yaw);
    }

    /**
     * 获取所有复活点配置（用于命令显示）
     */
    public static Map<String, SpawnPoint> getAllSpawnPoints() {
        return new HashMap<>(SPAWN_POINTS);
    }
}
