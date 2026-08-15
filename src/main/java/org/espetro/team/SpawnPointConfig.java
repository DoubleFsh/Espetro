package org.espetro.team;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import org.espetro.Espetro;
import org.espetro.api.EspetroAPI;

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
    /** @deprecated 不从 datapack 加载；由 GameConfigBridge 设置。 */
    @Deprecated
    public static void loadConfig(MinecraftServer server) {
        // 有意留空
    }


    /**
     * 重置为默认复活点
     */
    private static void applyDefaults() {
        replaceSpawnPoints(defaultSpawnPoints());
    }

    /**
     * 解析并应用配置
     */
    private static void parseAndApplyConfig(String json) {
        JsonObject root = GSON.fromJson(json, JsonObject.class);
        
        if (root.has("spawnPoints")) {
            JsonObject spawnPoints = root.getAsJsonObject("spawnPoints");

            // 先重置为默认值，再覆盖（防止重载时部分字段残留旧值）
            Map<String, SpawnPoint> updatedSpawnPoints = defaultSpawnPoints();

            for (String team : new String[]{"ATTACK", "DEFEND"}) {
                if (spawnPoints.has(team)) {
                    JsonObject point = spawnPoints.getAsJsonObject(team);
                    SpawnPoint spawn = new SpawnPoint();
                    spawn.x = getDouble(point, "x", 0);
                    spawn.y = getDouble(point, "y", 65);
                    spawn.z = getDouble(point, "z", 0);
                    spawn.yaw = (float) getDouble(point, "yaw", team.equals("ATTACK") ? 0 : 180);
                    
                    updatedSpawnPoints.put(team, spawn);
                    Espetro.LOGGER.info("加载 {} 复活点: ({}, {}, {}), yaw: {}", 
                        team, spawn.x, spawn.y, spawn.z, spawn.yaw);
                }
            }
            replaceSpawnPoints(updatedSpawnPoints);
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
        SpawnPoint updated = new SpawnPoint(x, y, z, yaw);
        if (sameSpawnPoint(SPAWN_POINTS.get(team), updated)) {
            return;
        }
        SPAWN_POINTS.put(team, updated);
        EspetroAPI.markTacticalMapStateDirty();
        Espetro.LOGGER.info("动态设置 {} 复活点: ({}, {}, {}), yaw: {}", team, x, y, z, yaw);
    }

    /**
     * 获取所有复活点配置（用于命令显示）
     */
    public static Map<String, SpawnPoint> getAllSpawnPoints() {
        return new HashMap<>(SPAWN_POINTS);
    }

    private static Map<String, SpawnPoint> defaultSpawnPoints() {
        Map<String, SpawnPoint> defaults = new HashMap<>();
        defaults.put("ATTACK", new SpawnPoint(100.5, 65, 0.5, 0));
        defaults.put("DEFEND", new SpawnPoint(-100.5, 65, 0.5, 180));
        return defaults;
    }

    private static void replaceSpawnPoints(Map<String, SpawnPoint> updatedSpawnPoints) {
        if (sameSpawnPoints(SPAWN_POINTS, updatedSpawnPoints)) {
            return;
        }
        SPAWN_POINTS.clear();
        SPAWN_POINTS.putAll(updatedSpawnPoints);
        EspetroAPI.markTacticalMapStateDirty();
    }

    private static boolean sameSpawnPoints(Map<String, SpawnPoint> first,
                                           Map<String, SpawnPoint> second) {
        if (!first.keySet().equals(second.keySet())) {
            return false;
        }
        for (Map.Entry<String, SpawnPoint> entry : first.entrySet()) {
            if (!sameSpawnPoint(entry.getValue(), second.get(entry.getKey()))) {
                return false;
            }
        }
        return true;
    }

    private static boolean sameSpawnPoint(SpawnPoint first, SpawnPoint second) {
        if (first == second) {
            return true;
        }
        return first != null && second != null
            && Double.compare(first.x, second.x) == 0
            && Double.compare(first.y, second.y) == 0
            && Double.compare(first.z, second.z) == 0
            && Float.compare(first.yaw, second.yaw) == 0;
    }
}
