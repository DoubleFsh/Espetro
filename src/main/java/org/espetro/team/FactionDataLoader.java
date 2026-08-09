package org.espetro.team;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.annotations.SerializedName;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import org.espetro.Espetro;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import org.espetro.mapconfig.ActiveMapConfig;

/**
 * 阵营/编制加载器。权威源为游戏根目录 {@code EsFactions/*.json}
 *（{@link #loadExternalFrozen}）。不再从 datapack 加载运行时编制。
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
    /** factionId -> ordered vehicle type declarations from VehTypes. */
    private final Map<String, List<String>> factionVehicleTypes = new LinkedHashMap<>();
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
     * @deprecated 不再从 datapack 加载编制。请使用 {@link #loadExternalFrozen}。
     * 空实现，避免 ensureLoaded/reload 清空 EsFactions 冻结数据。
     */
    @Deprecated
    public void load(ResourceManager resourceManager) {
        Espetro.LOGGER.debug("忽略 datapack FactionDataLoader.load；编制仅来自 EsFactions");
    }

    /**
     * Startup-only external formation load. EsFactions is deliberately not
     * connected to the datapack reload listener.
     */
    public void loadExternalFrozen(Map<String, Path> files) {
        this.factions.clear();
        this.classKits.clear();
        this.factionVehicles.clear();
        this.factionVehicleTypes.clear();
        this.classesByFaction.clear();
        this.classIdsByFaction.clear();
        this.factionIdArray = EMPTY_STRING_ARRAY;
        this.factionArray = EMPTY_FACTION_ARRAY;

        for (Map.Entry<String, Path> entry : files.entrySet()) {
            String factionId = entry.getKey();
            Path file = entry.getValue();
            ResourceLocation id = ResourceLocation.tryBuild(
                Espetro.MOD_ID, "external_factions/" + factionId + ".json");
            if (id == null) {
                Espetro.LOGGER.error("[编制拒载] {}: 文件名只能使用小写英文字母、数字、_、-、.", file);
                continue;
            }
            try {
                String rawJson = Files.readString(file, StandardCharsets.UTF_8);
                JsonObject root = JsonParser.parseString(rawJson).getAsJsonObject();
                int aliasCount = 0;
                if (root.has("VehTypes")) aliasCount++;
                if (root.has("vehtypes")) aliasCount++;
                if (root.has("vehicle_types")) aliasCount++;
                if (aliasCount > 1) {
                    warnRejected(id, "同时出现 VehTypes/vehtypes/vehicle_types 多个别名");
                    continue;
                }
                FactionJsonData data = GSON.fromJson(root, FactionJsonData.class);
                if (data == null || !prepareAndValidateFaction(id, factionId, data)) {
                    continue;
                }
                if (!validateVehicleDeclaration(id, data)) {
                    continue;
                }
                commitFaction(factionId, data);
                Espetro.LOGGER.info("加载外部编制: {} ({})", file,
                    data.faction != null ? data.faction.name : factionId);
            } catch (Exception e) {
                Espetro.LOGGER.error("[编制拒载] {}: {}", file, e.getMessage(), e);
            }
        }
        rebuildLookupCaches();
        this.loaded = true;
        ClassLoadoutPreviewResolver.clearCache();
        Espetro.LOGGER.info("EsFactions 已冻结: {} 个编制, {} 个职业", factions.size(), classKits.size());
    }

    private void commitFaction(String factionId, FactionJsonData data) {
        this.factions.put(factionId, data.faction);
        if (data.classes != null) {
            for (Map.Entry<String, ClassKitData> classEntry : data.classes.entrySet()) {
                this.classKits.put(classEntry.getKey(), classEntry.getValue());
            }
        }
        if (data.vehicles != null) {
            this.factionVehicles.put(factionId, new LinkedHashMap<>(data.vehicles));
        }
        this.factionVehicleTypes.put(factionId,
            data.vehicleTypes == null ? List.of() : List.copyOf(data.vehicleTypes));
    }

    private boolean validateVehicleDeclaration(ResourceLocation id, FactionJsonData data) {
        if (data.vehicleTypes == null) {
            warnRejected(id, "缺少 VehTypes 数组");
            return false;
        }
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        List<String> normalizedTypes = new ArrayList<>();
        for (String rawType : data.vehicleTypes) {
            String type = rawType == null ? "" : rawType.trim().toLowerCase(Locale.ROOT);
            if (type.isBlank() || !seen.add(type)) {
                warnRejected(id, "VehTypes 含空值或重复类型");
                return false;
            }
            normalizedTypes.add(type);
        }
        data.vehicleTypes = normalizedTypes;
        if (data.vehicles == null) {
            data.vehicles = new LinkedHashMap<>();
        }
        Map<String, VehicleData> normalizedVehicles = new LinkedHashMap<>();
        for (Map.Entry<String, VehicleData> entry : data.vehicles.entrySet()) {
            String type = entry.getKey() == null
                ? "" : entry.getKey().trim().toLowerCase(Locale.ROOT);
            if (type.isBlank() || normalizedVehicles.containsKey(type)) {
                warnRejected(id, "vehicles 含空类型或大小写归一化后重复的类型");
                return false;
            }
            if (!seen.contains(type)) {
                warnRejected(id, "vehicles." + entry.getKey() + " 未在 VehTypes 中声明");
                return false;
            }
            VehicleData vehicle = entry.getValue();
            if (vehicle == null) {
                warnRejected(id, "vehicles." + entry.getKey() + " 为空");
                return false;
            }
            if ((vehicle.entities == null || vehicle.entities.isEmpty())
                && vehicle.entityTypeStr != null && !vehicle.entityTypeStr.isBlank()) {
                vehicle.entities = new ArrayList<>(List.of(vehicle.entityTypeStr));
            }
            if (vehicle.entities == null || vehicle.entities.isEmpty()) {
                warnRejected(id, "vehicles." + entry.getKey() + ".entity 必须是非空数组");
                return false;
            }
            List<String> normalizedEntities = new ArrayList<>();
            for (String entity : vehicle.entities) {
                if (entity == null || entity.isBlank()) {
                    warnRejected(id, "vehicles." + entry.getKey() + ".entity 含空实体 ID");
                    return false;
                }
                normalizedEntities.add(entity.trim());
            }
            vehicle.entities = normalizedEntities;
            vehicle.perMaxCount = Math.max(1, vehicle.perMaxCount);
            if (vehicle.nbt != null) {
                String trimmedNbt = vehicle.nbt.trim();
                vehicle.nbt = trimmedNbt.isEmpty() ? null : trimmedNbt;
            }
            normalizedVehicles.put(type, vehicle);
        }
        data.vehicles = normalizedVehicles;
        return true;
    }

    /** Whether one external formation can run on the selected map. */
    public boolean isCompatibleWithMap(String factionId, ActiveMapConfig map) {
        if (map == null) {
            Espetro.LOGGER.warn("[编制兼容] {} → 失败: map 为空", factionId);
            return false;
        }
        if (!map.usable) {
            Espetro.LOGGER.warn("[编制兼容] {} → 失败: map.usable=false", factionId);
            return false;
        }
        if (!factions.containsKey(factionId)) {
            Espetro.LOGGER.warn("[编制兼容] {} → 失败: factions 中未找到", factionId);
            return false;
        }
        List<String> declared = factionVehicleTypes.get(factionId);
        if (declared == null) {
            Espetro.LOGGER.warn("[编制兼容] {} → 失败: factionVehicleTypes 中无此编制", factionId);
            return false;
        }
        for (String type : declared) {
            if (!map.vehSpawn.vehicleTypes.contains(type)) {
                Espetro.LOGGER.warn("[编制兼容] {} → 失败: VehTypes 中 '{}' 不在地图 VehSpawn ({}) 中",
                    factionId, type, map.vehSpawn.vehicleTypes);
                return false;
            }
        }
        Map<String, VehicleData> vehicles = factionVehicles.getOrDefault(factionId, Map.of());
        for (Map.Entry<String, VehicleData> entry : vehicles.entrySet()) {
            String type = entry.getKey();
            VehicleData data = entry.getValue();
            List<org.espetro.mapconfig.VehSpawnSnapshot.SpawnPoint> points =
                map.vehSpawn.spawnPointsByType.get(type);
            if (points == null) {
                Espetro.LOGGER.warn("[编制兼容] {} → 失败: 载具类型 '{}' 在地图 spawnPointsByType 中无出生点",
                    factionId, type);
                return false;
            }
            if (data.entities == null) {
                Espetro.LOGGER.warn("[编制兼容] {} → 失败: 载具类型 '{}' 的 entities 为 null",
                    factionId, type);
                return false;
            }
            if (data.entities.size() > points.size()) {
                Espetro.LOGGER.warn("[编制兼容] {} → 失败: 载具类型 '{}' entities({}) > spawn点数({})",
                    factionId, type, data.entities.size(), points.size());
                return false;
            }
            for (String entityId : data.entities) {
                ResourceLocation rl = ResourceLocation.tryParse(entityId);
                if (rl == null) {
                    Espetro.LOGGER.warn("[编制兼容] {} → 失败: 载具实体 '{}' ResourceLocation 解析失败",
                        factionId, entityId);
                    return false;
                }
                if (!net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.containsKey(rl)) {
                    Espetro.LOGGER.warn("[编制兼容] {} → 失败: 载具实体 '{}' 不在 ENTITY_TYPE 注册表中",
                        factionId, entityId);
                    return false;
                }
            }
        }
        return true;
    }

    public boolean isMapPlayable(ActiveMapConfig map) {
        Set<String> affiliations = new HashSet<>();
        int compatible = 0;
        for (FactionData faction : factionArray) {
            if (faction == null || faction.id == null || faction.factionId == null) {
                continue;
            }
            if (getClassesForFaction(faction.id).length == 0) {
                continue;
            }
            if (!isCompatibleWithMap(faction.id, map)) {
                continue;
            }
            compatible++;
            affiliations.add(faction.factionId);
        }
        boolean ok = affiliations.size() >= 2;
        if (!ok && map != null) {
            Espetro.LOGGER.warn(
                "地图 {} 编制兼容性不足: 兼容编制 {} 个, 不同 faction_id {} 个 {}（需要至少 2 个不同 faction_id）",
                map.displayName, compatible, affiliations.size(), affiliations);
        }
        return ok;
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
            // team_count：maxPlayers 为每小队上限；max_per_squad 无意义。
            if (kit.teamCount) {
                if (kit.maxPerSquad > 0) {
                    Espetro.LOGGER.warn("编制 {} 的职业 {} 已启用 team_count，忽略 max_per_squad={}",
                        resourceId, classId, kit.maxPerSquad);
                    kit.maxPerSquad = 0;
                }
            } else if (kit.maxPerSquad > 0 && kit.maxPerSquad > kit.maxPlayers) {
                warnRejected(resourceId, "职业 " + classId + " 的 max_per_squad ("
                    + kit.maxPerSquad + ") 必须 ≤ maxPlayers (" + kit.maxPlayers + ")");
                return false;
            } else if (kit.maxPerSquad < 0) {
                kit.maxPerSquad = 0;
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

            if (kit.strictCount && variantLimitSum != kit.maxPlayers) {
                warnRejected(resourceId, "职业 " + classId + " (strict_count=true) 的变体上限总和 "
                    + variantLimitSum + " 不等于职业上限 " + kit.maxPlayers);
                return false;
            }
        }
        return true;
    }

    private void warnRejected(ResourceLocation resourceId, String reason) {
        Espetro.LOGGER.warn("[编制拒载] {}: {}。该编制不会载入", resourceId, reason);
    }

    public void ensureLoaded(ResourceManager resourceManager) {
        // 编制仅由 loadExternalFrozen 在启动时加载；此处不得再扫 datapack。
        if (!loaded) {
            Espetro.LOGGER.debug("FactionDataLoader 尚未外部冻结加载（ensureLoaded 忽略 datapack）");
        }
    }

    /**
     * @deprecated 不支持热重载 EsFactions；请重启服务端。
     */
    @Deprecated
    public void reload(ResourceManager resourceManager) {
        Espetro.LOGGER.warn("编制不支持热重载；请重启以重新读取 EsFactions/");
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

        java.util.Map<String, Integer> factionClassCounts = new java.util.LinkedHashMap<>();
        for (ClassKitData kit : classKits.values()) {
            if (kit == null || kit.factionId == null) {
                Espetro.LOGGER.warn("[重建缓存诊断] 跳过一个 null 或 factionId=null 的职业: kit={}", kit);
                continue;
            }
            groupedClasses.computeIfAbsent(kit.factionId, ignored -> new ArrayList<>()).add(kit);
            factionClassCounts.merge(kit.factionId, 1, Integer::sum);
        }
        Espetro.LOGGER.info("[重建缓存诊断] classKits 中各类 factionId 的职业数: {}", factionClassCounts);

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
        Espetro.LOGGER.info("[重建缓存诊断] classesByFaction 的 factionId 集合: {}, factions.keySet: {}",
            classesByFaction.keySet(), factions.keySet());
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
        @SerializedName(value = "VehTypes", alternate = {"vehtypes", "vehicle_types"})
        public List<String> vehicleTypes;
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
        /** 胜利结算时展示的阵营名称（未配置则回退到 name）。 */
        @SerializedName(value = "show_name", alternate = {"showName"})
        public String showName;
        /** 单个 Radio 作用范围内最多可放置的 HAB 兵站数（默认 2）。 */
        @SerializedName(value = "max_habs_per_radio", alternate = {"maxHabsPerRadio"})
        public int maxHabsPerRadio = 2;
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
         * 是否属于载具组员职业。null 表示旧配置，回退到 icon=crewman；
         * 显式 false 可覆盖该兼容规则。
         */
        @SerializedName(value = "vehicle_crew", alternate = {"vehicleCrew"})
        public Boolean vehicleCrew;
        /**
         * 完整文件系统路径的职业图标（优先于 {@link #icon}）。
         * 例：{@code /home/shu/图片/Icon/rifleman.png}，不是 jar 内 ResourceLocation。
         */
        @com.google.gson.annotations.SerializedName(value = "IconImage", alternate = {"icon_image", "iconImage"})
        public String iconImage;

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

        /**
         * 变体计数模式。true 时每个变体独立统计人数并校验上限总和；
         * false 时变体仅代表不同配装，不拥有独立人数名额。
         * 缺失时默认 true，确保旧数据包兼容。
         */
        @SerializedName(value = "strict_count", alternate = {"strictCount"})
        public boolean strictCount = true;

        /**
         * true：职业人数在班组小队内统计；未入小队不可选；maxPlayers 为每小队上限。
         * false/缺省：maxPlayers 为编制/队伍总上限；可用 max_per_squad 限制每小队。
         */
        @SerializedName(value = "team_count", alternate = {"teamCount"})
        public boolean teamCount = false;

        /**
         * 仅 team_count=false 时生效：每个班组小队内该职业上限；0 表示不限制每小队。
         * 必须满足 0 或 1..maxPlayers。
         */
        @SerializedName(value = "max_per_squad", alternate = {"maxPerSquad"})
        public int maxPerSquad = 0;

        /**
         * 选择该职业时，小队至少需要的人数（含自己）。0 = 不限制。
         */
        @SerializedName(value = "teammates_need", alternate = {"teammatesNeed"})
        public int teammatesNeed = 0;

        /** 步兵职业显示行号（1-5），0/缺省 = 不参与 5 行网格（如载具兵不显示在步兵区）。 */
        @SerializedName(value = "row", alternate = {"grid_row", "gridRow"})
        public int row = 0;

        /** 每 N 个小队成员解锁 1 个该职业名额（例：2 = 每 2 人 1 个名额）。0 = 不限制。 */
        @SerializedName(value = "unlock_per_n", alternate = {"unlockPerN"})
        public int unlockPerN = 0;

        /** 小队达到此人数后解锁该职业。优先级高于 unlock_per_n。0 = 不限制。 */
        @SerializedName(value = "unlock_min_squad", alternate = {"unlockMinSquad"})
        public int unlockMinSquad = 0;

        /** 仅小队长可选；非队长不显示，后续职业向前补位。 */
        @SerializedName(value = "leader_only", alternate = {"leaderOnly"})
        public boolean leaderOnly = false;

        public int maxPlayers = 5;
        public int healthBonus = 0;
        public float speedBonus = 0f;
        public int troopValue = 1;

        public boolean isVehicleCrew() {
            return org.espetro.vehicle.VehicleSeatAccessPolicy.resolvesVehicleCrew(
                vehicleCrew, icon);
        }

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
        /** Ordered entity slots; slot N uses VehSpawn point N. */
        @SerializedName("entity")
        public List<String> entities;
        /** 显示名，含颜色代码，如 "§6运输卡车" */
        @SerializedName("display_name")
        public String displayName;
        /** 该类型同时部署上限 */
        public int max = 0;
        /** Simultaneous maximum for each individual entity slot. */
        @SerializedName(value = "per_max_count", alternate = {"perMaxCount"})
        public int perMaxCount = 1;
        /** 单辆刷新冷却时间(分钟) */
        @SerializedName("respawn_minutes")
        public int respawnMinutes = 0;
        /** 载具死亡/被摧毁时扣除的兵力值。 */
        @SerializedName(value = "troop_value", alternate = {"troopValue"})
        public int troopValue = 0;
        /** 生成实体时附加的通用 scoreboard tags。 */
        @SerializedName(value = "entity_tags", alternate = {"entityTags"})
        public String[] entityTags;
        /**
         * 部署时合并到实体的 SNBT（如 "{Energy:2147483647}"）。
         * 由 VehicleManager 在 Entity#load 前应用；不依赖载具模组 API。
         */
        @SerializedName(value = "nbt", alternate = {"entity_nbt", "entityNbt"})
        public String nbt;
        /** 单类载具的固定部署坐标配置。 */
        public VehicleDeploymentData deployment;
        /** 补给载具：可装载弹药和建材，默认容量 3000 */
        @SerializedName(value = "supplyveh", alternate = {"supply_veh", "supplyVeh"})
        public Boolean supplyVeh;
        /** 步兵战斗载具：仅弹药，默认容量 500，可用于更换职业 */
        @SerializedName(value = "fightveh", alternate = {"fight_veh", "fightVeh"})
        public Boolean fightVeh;
        /** 载具补给总容量（覆盖默认值） */
        public Integer capacity;
        /**
         * 该车型首次可部署前的等待秒数（开战时钟起算），攻防分开。
         * 例: "initial_deploy_delay_seconds": { "attack": 180, "defend": 60 }
         */
        @SerializedName(value = "initial_deploy_delay_seconds",
            alternate = {"initialDeployDelaySeconds"})
        public InitialDeployDelayData initialDeployDelay;
    }

    public static class InitialDeployDelayData {
        @SerializedName(value = "attack", alternate = {"ATTACK"})
        public int attack;
        @SerializedName(value = "defend", alternate = {"DEFEND"})
        public int defend;
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
