package org.espetro.team;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import com.google.gson.annotations.SerializedName;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import org.espetro.Espetro;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.*;

/**
 * 阵营数据包加载器
 * 从 data/espetro/factions/ 目录加载阵营和职业配置
 */
public class FactionDataLoader {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static FactionDataLoader INSTANCE;

    private Map<String, FactionData> factions = new LinkedHashMap<>();
    private Map<String, ClassKitData> classKits = new HashMap<>();
    /** factionId -> (vehicleType -> VehicleData) 来自编制JSON的载具配置 */
    private final Map<String, Map<String, VehicleData>> factionVehicles = new LinkedHashMap<>();
    private boolean loaded = false;

    public FactionDataLoader() {
        INSTANCE = this;
    }

    public static FactionDataLoader getInstance() {
        return INSTANCE;
    }

    /**
     * 从资源管理器加载所有配置
     */
    public void load(ResourceManager resourceManager) {
        this.factions.clear();
        this.classKits.clear();
        this.factionVehicles.clear();
        
        String namespace = Espetro.MOD_ID;
        String path = "factions";
        
        Map<ResourceLocation, Resource> resources = resourceManager.listResources(path, loc -> 
            loc.getNamespace().equals(namespace)
        );
        
        for (Map.Entry<ResourceLocation, Resource> entry : resources.entrySet()) {
            ResourceLocation id = entry.getKey();
            Resource resource = entry.getValue();
            
            if (!id.getPath().endsWith(".json")) continue;
            
            // 先读取原始文本用于出错时报错
            String rawJson = "";
            try (BufferedReader reader = resource.openAsReader()) {
                StringBuilder sb = new StringBuilder();
                char[] buf = new char[4096];
                int len;
                while ((len = reader.read(buf)) != -1) {
                    sb.append(buf, 0, len);
                }
                rawJson = sb.toString();
            } catch (IOException e) {
                Espetro.LOGGER.error("读取配置文件失败: {}", id, e);
                continue;
            }

            try {
                FactionJsonData data = GSON.fromJson(rawJson, FactionJsonData.class);
                if (data != null) {
                    String factionId = id.getPath().replace(".json", "").replace("factions/", "");
                    
                    // 处理阵营数据
                    if (data.faction != null) {
                        FactionData faction = data.faction;
                        faction.id = factionId;
                        this.factions.put(factionId, faction);
                    }
                    
                    // 处理职业数据（逐个解析，单个职业失败不影响其他职业和阵营头）
                    if (data.classes != null) {
                        for (Map.Entry<String, ClassKitData> classEntry : data.classes.entrySet()) {
                            String classId = classEntry.getKey();
                            ClassKitData kit = classEntry.getValue();
                            kit.id = classId;
                            kit.factionId = factionId;
                            
                            // 确保默认值生效（Gson不会使用类字段默认值）
                            if (kit.maxPlayers == 0) kit.maxPlayers = 5;
                            if (kit.troopValue == 0) kit.troopValue = 1;
                            
                            this.classKits.put(classId, kit);
                        }
                    }
                    
                    // 处理编制自定义载具配置
                    if (data.vehicles != null) {
                        Map<String, VehicleData> vMap = new LinkedHashMap<>();
                        for (Map.Entry<String, VehicleData> vEntry : data.vehicles.entrySet()) {
                            VehicleData vd = vEntry.getValue();
                            // 确保默认值
                            if (vd.respawnMinutes == 0) vd.respawnMinutes = VehicleData.DEFAULT_RESPAWN_MINUTES;
                            vMap.put(vEntry.getKey(), vd);
                        }
                        this.factionVehicles.put(factionId, vMap);
                    }
                    Espetro.LOGGER.info("加载阵营数据: {} ({})", id, 
                        data.faction != null ? data.faction.name : "无faction节点");
                }
            } catch (JsonSyntaxException e) {
                Espetro.LOGGER.error("==============================");
                Espetro.LOGGER.error("[!] 阵营JSON语法错误: {}", id);
                Espetro.LOGGER.error("[!] 该阵营将不会被加载！请检查以下JSON内容:");
                Espetro.LOGGER.error("[{}] 内容预览:\n{}", id, 
                    rawJson.length() > 500 ? rawJson.substring(0, 500) + "\n... (截断)" : rawJson);
                Espetro.LOGGER.error("[!] 错误详情: {}", e.getMessage());
                Espetro.LOGGER.error("==============================");
            } catch (Exception e) {
                Espetro.LOGGER.error("[!] 阵营加载异常: {} - {}", id, e.getMessage(), e);
            }
        }
        
        this.loaded = true;
        Espetro.LOGGER.info("已加载 {} 个阵营, {} 个职业配置", this.factions.size(), this.classKits.size());
    }

    public void ensureLoaded(ResourceManager resourceManager) {
        if (!loaded) {
            load(resourceManager);
        }
    }

    /**
     * 强制重新加载所有阵营/职业数据（数据包热重载）
     */
    public void reload(ResourceManager resourceManager) {
        this.factions.clear();
        this.classKits.clear();
        this.factionVehicles.clear();
        this.loaded = false;
        load(resourceManager);
        Espetro.LOGGER.info("阵营数据已热重载: {} 个阵营, {} 个职业", this.factions.size(), this.classKits.size());
    }

    // ==================== 阵营方法 ====================

    public FactionData getFaction(String factionId) {
        return factions.get(factionId);
    }

    public Collection<FactionData> getAllFactions() {
        return factions.values();
    }

    public String[] getAllFactionIds() {
        return factions.keySet().toArray(new String[0]);
    }

    public FactionData[] getFactionArray() {
        return factions.values().toArray(new FactionData[0]);
    }

    // ==================== 职业方法 ====================

    public ClassKitData getClassKit(String classId) {
        return classKits.get(classId);
    }

    public ClassKitData[] getClassesForFaction(String factionId) {
        return classKits.values().stream()
            .filter(kit -> factionId.equals(kit.factionId))
            .toArray(ClassKitData[]::new);
    }

    public String[] getClassIdsForFaction(String factionId) {
        return classKits.values().stream()
            .filter(kit -> factionId.equals(kit.factionId))
            .map(kit -> kit.id)
            .toArray(String[]::new);
    }

    // ==================== 载具方法（编制自定义） ====================

    /**
     * 获取编制自定义的载具配置
     * @return vehicleType -> VehicleData, 无配置返回空Map
     */
    public Map<String, VehicleData> getFactionVehicles(String factionId) {
        return factionVehicles.getOrDefault(factionId, Collections.emptyMap());
    }

    /**
     * 获取所有编制载具配置
     */
    public Map<String, Map<String, VehicleData>> getAllFactionVehicles() {
        return new LinkedHashMap<>(factionVehicles);
    }

    // ==================== 数据结构 ====================

    /**
     * JSON根对象
     */
    public static class FactionJsonData {
        public FactionData faction;
        public Map<String, ClassKitData> classes;
        /** 编制自定义载具配置: vehicleType -> VehicleData */
        public Map<String, VehicleData> vehicles;
    }

    /**
     * 阵营数据
     */
    public static class FactionData {
        public transient String id;
        
        public String name;
        public String description;
        public String icon;
        public String team;
        public String color = "FFFFFF";
    }

    /**
     * 职业数据包
     */
    public static class ClassKitData {
        public transient String id;
        public transient String factionId;

        public String name;
        public String description;
        public String role;

        /**
         * 装备分发命令数组 —— 每个元素是 /give 命令的参数部分（不含 /give 和玩家名）
         * <p>
         * 执行时自动拼接为: give &lt;玩家名&gt; &lt;此字符串&gt;
         * <p>
         * 示例：
         * <pre>{@code
         * "commands": [
         *   "minecraft:diamond_sword{Enchantments:[{id:\"minecraft:sharpness\",lvl:5s}]} 1",
         *   "minecraft:diamond_helmet 1",
         *   "minecraft:bread 64"
         * ]
         * }</pre>
         */
        public String[] commands;

        public int maxPlayers = 5;
        public int healthBonus = 0;
        public float speedBonus = 0f;
        public int troopValue = 1;
    }

    /**
     * 编制自定义载具数据（来自 faction JSON 的 vehicles 节）
     * 每个编制可自定义其载具种类、实体类型、显示名、上限和冷却时间
     */
    public static class VehicleData {
        /** 默认刷新冷却时间(分钟) */
        public static final int DEFAULT_RESPAWN_MINUTES = 5;
        /** 默认载具上限 */
        public static final int DEFAULT_MAX = 1;

        /** Minecraft实体注册名，如 "minecraft:cow" */
        @SerializedName("entity_type")
        public String entityTypeStr;
        /** 显示名，含颜色代码，如 "§6运输卡车" */
        @SerializedName("display_name")
        public String displayName;
        /** 该类型同时部署上限 */
        public int max = DEFAULT_MAX;
        /** 单辆刷新冷却时间(分钟) */
        @SerializedName("respawn_minutes")
        public int respawnMinutes = DEFAULT_RESPAWN_MINUTES;
    }
}
