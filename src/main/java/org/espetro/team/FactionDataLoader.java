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
    private static final String[] EMPTY_STRING_ARRAY = new String[0];
    private static final FactionData[] EMPTY_FACTION_ARRAY = new FactionData[0];
    private static final ClassKitData[] EMPTY_CLASS_KIT_ARRAY = new ClassKitData[0];
    private static FactionDataLoader INSTANCE;

    private Map<String, FactionData> factions = new LinkedHashMap<>();
    private Map<String, ClassKitData> classKits = new HashMap<>();
    /** factionId -> (vehicleType -> VehicleData) 来自编制JSON的载具配置 */
    private final Map<String, Map<String, VehicleData>> factionVehicles = new LinkedHashMap<>();
    private final Map<String, ClassKitData[]> classesByFaction = new HashMap<>();
    private final Map<String, String[]> classIdsByFaction = new HashMap<>();
    private String[] factionIdArray = EMPTY_STRING_ARRAY;
    private FactionData[] factionArray = EMPTY_FACTION_ARRAY;
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
        this.classesByFaction.clear();
        this.classIdsByFaction.clear();
        this.factionIdArray = EMPTY_STRING_ARRAY;
        this.factionArray = EMPTY_FACTION_ARRAY;
        
        String namespace = Espetro.MOD_ID;
        String path = "factions";

        // 优先使用存档内 datapacks 数据包，再回退模组内置
        Map<ResourceLocation, Resource> resources = org.espetro.data.EspetroDataResources.listPreferred(
            resourceManager, path, loc -> loc.getNamespace().equals(namespace)
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

                    // 必须先校验整份文件；任一职业变体无效时，编制头、职业和载具都不提交。
                    if (!prepareAndValidateFaction(id, factionId, data)) {
                        continue;
                    }

                    this.factions.put(factionId, data.faction);
                    if (data.classes != null) {
                        for (Map.Entry<String, ClassKitData> classEntry : data.classes.entrySet()) {
                            this.classKits.put(classEntry.getKey(), classEntry.getValue());
                        }
                    }
                    if (data.vehicles != null) {
                        this.factionVehicles.put(factionId, new LinkedHashMap<>(data.vehicles));
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

        rebuildLookupCaches();
        this.loaded = true;
        Espetro.LOGGER.info("已加载 {} 个阵营, {} 个职业配置", this.factions.size(), this.classKits.size());
    }

    private boolean prepareAndValidateFaction(ResourceLocation resourceId, String factionId, FactionJsonData data) {
        if (data.faction == null) {
            warnRejected(resourceId, "缺少 faction 节点");
            return false;
        }
        if (data.faction.factionId == null || data.faction.factionId.isBlank()) {
            warnRejected(resourceId, "faction.faction_id 缺失或为空");
            return false;
        }
        data.faction.id = factionId;

        if (data.classes == null) {
            return true;
        }

        for (Map.Entry<String, ClassKitData> classEntry : data.classes.entrySet()) {
            String classId = classEntry.getKey();
            ClassKitData kit = classEntry.getValue();
            if (classId == null || classId.isBlank() || kit == null) {
                warnRejected(resourceId, "存在空职业 ID 或空职业配置");
                return false;
            }

            kit.id = classId;
            kit.factionId = factionId;
            if (kit.troopValue == 0) kit.troopValue = 1;
            if (kit.maxPlayers < 1) {
                warnRejected(resourceId, "职业 " + classId + " 的 maxPlayers 必须大于 0");
                return false;
            }

            if (kit.variants == null) {
                kit.variants = new LinkedHashMap<>();
                ClassVariantData fallback = ClassVariantData.fromLegacy(kit);
                fallback.id = "default";
                fallback.classId = classId;
                fallback.factionId = factionId;
                kit.variants.put(fallback.id, fallback);
                kit.legacyImplicitVariant = true;
                continue;
            }
            if (kit.variants.isEmpty()) {
                warnRejected(resourceId, "职业 " + classId + " 的 variants 不可为空；"
                    + "旧格式兼容需要完全省略 variants 字段");
                return false;
            }

            if (kit.hasLegacyLoadoutFields()) {
                Espetro.LOGGER.warn("编制 {} 的职业 {} 已配置 variants；职业级 commands/equipment/resupply 将被忽略",
                    resourceId, classId);
            }

            long variantLimitSum = 0L;
            for (Map.Entry<String, ClassVariantData> variantEntry : kit.variants.entrySet()) {
                String variantId = variantEntry.getKey();
                ClassVariantData variant = variantEntry.getValue();
                if (variantId == null || variantId.isBlank() || variant == null) {
                    warnRejected(resourceId, "职业 " + classId + " 存在空变体 ID 或空变体配置");
                    return false;
                }
                if (variant.maxPlayers < 1) {
                    warnRejected(resourceId, "职业 " + classId + " 的变体 " + variantId
                        + " maxPlayers 必须大于 0");
                    return false;
                }
                variant.id = variantId;
                variant.classId = classId;
                variant.factionId = factionId;
                if (variant.name == null || variant.name.isBlank()) {
                    variant.name = variantId;
                }
                variantLimitSum += variant.maxPlayers;
            }

            if (variantLimitSum != kit.maxPlayers) {
                warnRejected(resourceId, "职业 " + classId + " 的变体上限总和 " + variantLimitSum
                    + " 不等于职业上限 " + kit.maxPlayers);
                return false;
            }
        }
        return true;
    }

    private void warnRejected(ResourceLocation resourceId, String reason) {
        Espetro.LOGGER.warn("[编制拒载] {}: {}。该编制不会载入", resourceId, reason);
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
        return factionIdArray;
    }

    public FactionData[] getFactionArray() {
        return factionArray;
    }

    // ==================== 职业方法 ====================

    public ClassKitData getClassKit(String classId) {
        return classKits.get(classId);
    }

    public ClassVariantData getClassVariant(String classId, String variantId) {
        ClassKitData kit = getClassKit(classId);
        return kit != null ? kit.getVariant(variantId) : null;
    }

    public ClassKitData[] getClassesForFaction(String factionId) {
        return classesByFaction.getOrDefault(factionId, EMPTY_CLASS_KIT_ARRAY);
    }

    public String[] getClassIdsForFaction(String factionId) {
        return classIdsByFaction.getOrDefault(factionId, EMPTY_STRING_ARRAY);
    }

    private void rebuildLookupCaches() {
        factionIdArray = factions.keySet().toArray(EMPTY_STRING_ARRAY);
        factionArray = factions.values().toArray(EMPTY_FACTION_ARRAY);

        Map<String, List<ClassKitData>> groupedClasses = new HashMap<>();
        for (String factionId : factions.keySet()) {
            groupedClasses.put(factionId, new ArrayList<>());
        }

        for (ClassKitData kit : classKits.values()) {
            if (kit == null || kit.factionId == null) {
                continue;
            }
            groupedClasses.computeIfAbsent(kit.factionId, ignored -> new ArrayList<>()).add(kit);
        }

        for (Map.Entry<String, List<ClassKitData>> entry : groupedClasses.entrySet()) {
            List<ClassKitData> kits = entry.getValue();
            ClassKitData[] kitArray = kits.toArray(EMPTY_CLASS_KIT_ARRAY);
            String[] classIds = new String[kitArray.length];
            for (int i = 0; i < kitArray.length; i++) {
                classIds[i] = kitArray[i].id;
            }
            classesByFaction.put(entry.getKey(), kitArray);
            classIdsByFaction.put(entry.getKey(), classIds);
        }
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
        /** 编制投票卡片使用的完整 Minecraft 纹理资源位置。 */
        @SerializedName(value = "selection_image", alternate = {"selectionImage"})
        public String selectionImage;
        /** 编制所属真实阵营 ID；无需注册，使用精确字符串比较。 */
        @SerializedName("faction_id")
        public String factionId;
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
        /** 职业选择界面使用的图标资源短名（assets/espetro/textures/gui/roles）。 */
        public String icon;

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

        /**
         * 直接装备到玩家装备栏的物品配置，值沿用 /give 的参数格式。
         * <p>
         * 支持槽位键：
         * head/helmet/armor.head, chest/chestplate/armor.chest,
         * legs/leggings/armor.legs, feet/boots/armor.feet,
         * mainhand/weapon.mainhand, offhand/weapon.offhand。
         * <p>
         * 示例：
         * <pre>{@code
         * "equipment": {
         *   "head": "minecraft:diamond_helmet 1",
         *   "chest": "minecraft:diamond_chestplate{display:{Name:'{\"text\":\"重甲\"}'}} 1"
         * }
         * }</pre>
         */
        @SerializedName(value = "equipment", alternate = {"equipment_slots", "equipmentSlots"})
        public Map<String, String> equipment;

        /** equipment 的语义别名，便于只配置可穿戴装备。 */
        @SerializedName(value = "wearable_equipment", alternate = {"wearableEquipment"})
        public Map<String, String> wearableEquipment;

        /** 是否自动把 commands 发到背包里的可穿戴物品穿上，默认开启。 */
        @SerializedName(value = "auto_equip_wearables", alternate = {"autoEquipWearables"})
        public Boolean autoEquipWearables;

        /** 弹药补给配置（可选） */
        public ResupplyData resupply;

        /** 装备变体 ID -> 完整装备配置，按 JSON 顺序显示。 */
        public Map<String, ClassVariantData> variants;

        /** 旧格式在加载时合成的 default 变体。 */
        public transient boolean legacyImplicitVariant;

        public int maxPlayers = 5;
        public int healthBonus = 0;
        public float speedBonus = 0f;
        public int troopValue = 1;

        public ClassVariantData getVariant(String variantId) {
            if (variants == null || variants.isEmpty()) return null;
            if (variantId == null || variantId.isBlank()) {
                return variants.size() == 1 ? variants.values().iterator().next() : null;
            }
            return variants.get(variantId);
        }

        public boolean hasLegacyLoadoutFields() {
            return commands != null && commands.length > 0
                || equipment != null && !equipment.isEmpty()
                || wearableEquipment != null && !wearableEquipment.isEmpty()
                || autoEquipWearables != null
                || resupply != null;
        }
    }

    /** 同一职业下的一套完整装备变体。 */
    public static class ClassVariantData {
        public transient String id;
        public transient String classId;
        public transient String factionId;

        public String name;
        public String description;
        public int maxPlayers;
        public String[] commands;
        @SerializedName(value = "equipment", alternate = {"equipment_slots", "equipmentSlots"})
        public Map<String, String> equipment;
        @SerializedName(value = "wearable_equipment", alternate = {"wearableEquipment"})
        public Map<String, String> wearableEquipment;
        @SerializedName(value = "auto_equip_wearables", alternate = {"autoEquipWearables"})
        public Boolean autoEquipWearables;
        public ResupplyData resupply;

        private static ClassVariantData fromLegacy(ClassKitData kit) {
            ClassVariantData variant = new ClassVariantData();
            variant.name = "默认装备";
            variant.description = kit.description;
            variant.maxPlayers = kit.maxPlayers;
            variant.commands = kit.commands;
            variant.equipment = kit.equipment;
            variant.wearableEquipment = kit.wearableEquipment;
            variant.autoEquipWearables = kit.autoEquipWearables;
            variant.resupply = kit.resupply;
            return variant;
        }
    }

    /**
     * 弹药补给数据
     */
    public static class ResupplyData {
        /** 补给物品列表 */
        public ResupplyItem[] items;
        /** 从 FOB 共享弹药库存扣除的点数；未配置时使用 logistics.json 默认值。 */
        @SerializedName(value = "ammo_cost", alternate = {"ammoCost"})
        public Integer ammoCost;
    }

    /**
     * 单个补给物品配置
     */
    public static class ResupplyItem {
        /** 物品注册名，如 "minecraft:arrow" */
        public String id;
        /** 可选 SNBT，如 "{display:{Name:'{\"text\":\"弹药\"}'}}" */
        public String nbt;
        /** 每次补给数量 */
        public int count = 16;
        /** 背包中该物品数量上限 */
        public int max = 64;
    }

    /**
     * 编制自定义载具数据（来自 faction JSON 的 vehicles 节）
     * 每个编制可自定义其载具种类、实体类型、部署位置、显示名、上限和冷却时间
     */
    public static class VehicleData {
        /** 默认刷新冷却时间(分钟) */
        public static final int DEFAULT_RESPAWN_MINUTES = 5;
        /** 默认载具上限 */
        public static final int DEFAULT_MAX = 1;

        /** Minecraft实体注册名，如 "minecraft:minecart" 或任意模组实体ID。 */
        @SerializedName("entity_type")
        public String entityTypeStr;
        /** 显示名，含颜色代码，如 "§6运输卡车" */
        @SerializedName("display_name")
        public String displayName;
        /** 该类型同时部署上限 */
        public int max = 0;
        /** 单辆刷新冷却时间(分钟) */
        @SerializedName("respawn_minutes")
        public int respawnMinutes = 0;
        /** 载具死亡/被摧毁时扣除的兵力值。 */
        @SerializedName(value = "troop_value", alternate = {"troopValue"})
        public int troopValue = 0;
        /** 生成实体时附加的通用 scoreboard tags。 */
        @SerializedName(value = "entity_tags", alternate = {"entityTags"})
        public String[] entityTags;
        /** 单类载具的固定部署坐标配置。 */
        public VehicleDeploymentData deployment;
    }

    /**
     * 单类载具部署位置配置。坐标必须由编制 JSON 直接指定。
     */
    public static class VehicleDeploymentData {
        /** 攻方选中该编制时使用的固定坐标。 */
        @SerializedName(value = "ATTACK", alternate = {"attack"})
        public VehicleDeploymentPointData attack;
        /** 守方选中该编制时使用的固定坐标。 */
        @SerializedName(value = "DEFEND", alternate = {"defend"})
        public VehicleDeploymentPointData defend;
    }

    /**
     * 单队伍载具部署坐标。
     */
    public static class VehicleDeploymentPointData {
        /** 固定坐标 [x, y, z]。 */
        public int[] position;
        /** 朝向角度。 */
        public Float yaw;
    }
}
