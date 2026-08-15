package org.espetro.bastion;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.annotations.SerializedName;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.fml.loading.FMLPaths;
import org.espetro.Espetro;
import org.espetro.mapconfig.ActiveMapConfig;

import javax.annotation.Nullable;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Transactional, server-generation-scoped fortification JSON v2 registry.
 *
 * <p>The global file and every map override are parsed into temporary maps,
 * all referenced Structure NBT is compiled, and only then is a complete
 * registry set published.  A running server never mutates or reloads it.</p>
 */
public final class FortificationConfig {

    public static final int SCHEMA_VERSION = 2;
    public static final int HARD_MAX_TEMPLATE_BLOCKS = 16_384;
    public static final int HARD_MAX_TEMPLATE_ENTITIES = 128;
    public static final int HARD_MAX_TEMPLATE_AXIS = 128;
    public static final int HARD_MAX_TEMPLATE_NBT_BYTES = 8 * 1024 * 1024;
    public static final int HARD_MAX_PASSENGER_DEPTH = 8;
    private static final Set<String> REQUIRED = Set.of(
        "espetro:radio", "espetro:hab", "espetro:ammo_crate",
        "espetro:vehicle_supply_station", "espetro:sandbag_wall"
    );
    private static final Set<String> ROLES = Set.of(
        "commander", "squad_leader", "fireteam_leader"
    );
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static volatile RegistrySet registrySet = RegistrySet.empty();
    private static volatile ParsedRoot pending = defaults();
    private static volatile boolean frozen;
    private static volatile String failure;

    private FortificationConfig() {
    }

    /** Parse the administrator file, but do not publish a server registry yet. */
    public static synchronized void loadServerConfig() {
        if (frozen) {
            Espetro.LOGGER.info("忽略运行期工事配置重载；修改将在下次完整重启生效");
            return;
        }
        Path configPath = FMLPaths.CONFIGDIR.get().resolve("espetro/fortifications.json");
        try {
            Files.createDirectories(configPath.getParent());
            if (!Files.isRegularFile(configPath)) {
                Files.writeString(configPath, bundledDefaultJson(), StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            failure = "无法创建工事配置 " + configPath + ": " + e.getMessage();
            pending = ParsedRoot.invalid(failure);
            return;
        }
        parseIntoPending(configPath);
    }

    /** Unit-test/migration entry point.  Parsing is still all-or-nothing. */
    public static synchronized void loadFromPath(@Nullable Path path) {
        frozen = false;
        failure = null;
        if (path == null || !Files.isRegularFile(path)) {
            publishUncompiled(defaults());
            return;
        }
        parseIntoPending(path);
        if (pending.errors.isEmpty()) publishUncompiled(pending);
        else registrySet = RegistrySet.empty();
    }

    public static synchronized void loadDefaults() {
        frozen = false;
        failure = null;
        pending = defaults();
        publishUncompiled(pending);
    }

    /**
     * Compile base + per-map overridden registries after initial datapack load.
     * A failure leaves the registry gate closed and exposes no partial entries.
     */
    public static synchronized PreparationResult compileAndFreeze(
        MinecraftServer server, List<ActiveMapConfig> maps
    ) {
        if (frozen) {
            return failure == null
                ? PreparationResult.ok(registrySet.global.size(), registrySet.byDimension.size())
                : PreparationResult.fail(failure);
        }
        if (pending == null) loadServerConfig();
        List<String> errors = new ArrayList<>(pending.errors);
        if (!errors.isEmpty()) return fail(errors);

        try {
            Map<String, FortificationDef> globalDefs = copyDefinitions(pending.definitions);
            validateRequired(globalDefs, errors);
            compileDefinitions(server, globalDefs, pending.limits, errors, "global");

            Map<ResourceLocation, Map<String, FortificationDef>> perMap = new LinkedHashMap<>();
            Map<ResourceLocation, Map<String, String>> perMapAliases = new LinkedHashMap<>();
            if (maps != null) {
                for (ActiveMapConfig map : maps) {
                    if (map == null || !map.usable) continue;
                    Map<String, FortificationDef> overridden = applyOverrides(
                        globalDefs, map.logisticsJson, errors, map.mapFolder);
                    validateRequired(overridden, errors);
                    compileDefinitions(server, overridden, pending.limits, errors,
                        "map " + map.mapFolder);
                    perMap.put(map.dimensionId, Collections.unmodifiableMap(overridden));
                    perMapAliases.put(map.dimensionId,
                        Collections.unmodifiableMap(buildAliases(overridden, errors)));
                }
            }
            Map<String, String> aliases = buildAliases(globalDefs, errors);
            if (!errors.isEmpty()) return fail(errors);
            registrySet = new RegistrySet(
                Collections.unmodifiableMap(globalDefs),
                Collections.unmodifiableMap(aliases),
                Collections.unmodifiableMap(perMap),
                Collections.unmodifiableMap(perMapAliases),
                pending.vehicleService.copy(), pending.limits.copy());
            frozen = true;
            failure = null;
            Espetro.LOGGER.info("工事 JSON v2 已事务冻结: global={} maps={} aliases={}",
                globalDefs.size(), perMap.size(), aliases.size());
            return PreparationResult.ok(globalDefs.size(), perMap.size());
        } catch (Exception e) {
            errors.add(e.getMessage() == null ? e.toString() : e.getMessage());
            return fail(errors);
        }
    }

    public static synchronized void resetForNextServer() {
        frozen = false;
        failure = null;
        pending = defaults();
        registrySet = RegistrySet.empty();
    }

    public static boolean isFrozenReady() {
        return frozen && failure == null;
    }

    @Nullable
    public static String getFailure() {
        return failure;
    }

    public static Limits limits() {
        return registrySet.limits.copy();
    }

    public static VehicleServiceSettings vehicleService() {
        return registrySet.vehicleService;
    }

    public static Map<String, FortificationDef> all() {
        return registrySet.global;
    }

    public static List<FortificationDef> list() {
        return List.copyOf(registrySet.global.values());
    }

    public static List<FortificationDef> list(ResourceLocation dimension) {
        return List.copyOf(registrySet.byDimension.getOrDefault(dimension,
            registrySet.global).values());
    }

    @Nullable
    public static FortificationDef get(String id) {
        return resolve(registrySet.global, registrySet.aliases, id);
    }

    @Nullable
    public static FortificationDef get(ResourceLocation dimension, String id) {
        Map<String, FortificationDef> definitions = registrySet.byDimension.getOrDefault(
            dimension, registrySet.global);
        Map<String, String> aliases = registrySet.aliasesByDimension.getOrDefault(
            dimension, registrySet.aliases);
        return resolve(definitions, aliases, id);
    }

    /** Compatibility accessors; damage policy now belongs to each JSON definition. */
    public static float explosionDamageRatio() {
        return 0.1F;
    }

    public static float projectileHitDamageRatio() {
        return 0.1F;
    }

    /** Compatibility accessor for old callers while Radio is now a normal definition. */
    public static ConstructionProfile radioConstruction() {
        FortificationDef def = get("espetro:radio");
        return def == null ? new ConstructionProfile(600, 30, 5)
            : new ConstructionProfile(def.construction.requiredProgress,
                def.construction.buildPerHit, def.construction.removePerHit);
    }

    /** Compatibility accessor for old callers while HAB is now a normal definition. */
    public static ConstructionProfile habConstruction() {
        FortificationDef def = get("espetro:hab");
        return def == null ? new ConstructionProfile(200, 5, 5)
            : new ConstructionProfile(def.construction.requiredProgress,
                def.construction.buildPerHit, def.construction.removePerHit);
    }

    private static void parseIntoPending(Path path) {
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            pending = parse(JsonParser.parseReader(reader), path.toString());
            if (!pending.errors.isEmpty()) {
                failure = String.join("; ", pending.errors);
                Espetro.LOGGER.error("工事配置事务解析失败（未发布任何条目）: {}", failure);
            } else {
                failure = null;
            }
        } catch (Exception e) {
            failure = path + ": " + e.getMessage();
            pending = ParsedRoot.invalid(failure);
        }
    }

    private static ParsedRoot parse(JsonElement input, String source) {
        List<String> errors = new ArrayList<>();
        if (input == null || !input.isJsonObject()) {
            return ParsedRoot.invalid(source + ": 根必须是对象");
        }
        JsonObject root = input.getAsJsonObject();
        int version = root.has("schema_version") ? integer(root, "schema_version", -1) : 1;
        if (version == 1) {
            root = migrateV1(root, errors, source);
            version = SCHEMA_VERSION;
        }
        if (version != SCHEMA_VERSION) {
            errors.add(source + ".schema_version: 仅支持 2（读取到 " + version + ")");
        }
        Limits limits = root.has("limits") && root.get("limits").isJsonObject()
            ? GSON.fromJson(root.get("limits"), Limits.class) : new Limits();
        limits.normalize(errors, source + ".limits");
        VehicleServiceSettings service = root.has("vehicle_service")
            && root.get("vehicle_service").isJsonObject()
            ? GSON.fromJson(root.get("vehicle_service"), VehicleServiceSettings.class)
            : new VehicleServiceSettings();
        service.normalize(errors, source + ".vehicle_service");
        Map<String, FortificationDef> definitions = new LinkedHashMap<>();
        JsonArray entries = root.has("fortifications") && root.get("fortifications").isJsonArray()
            ? root.getAsJsonArray("fortifications") : null;
        if (entries == null) {
            errors.add(source + ".fortifications: 缺少数组");
        } else if (entries.size() > 256) {
            errors.add(source + ".fortifications: 超过 256 条硬上限");
        } else {
            for (int i = 0; i < entries.size(); i++) {
                String path = source + ".fortifications[" + i + "]";
                if (!entries.get(i).isJsonObject()) {
                    errors.add(path + ": 必须是对象");
                    continue;
                }
                try {
                    FortificationDef def = GSON.fromJson(entries.get(i), FortificationDef.class);
                    normalize(def, limits, errors, path);
                    if (def != null && def.id != null && definitions.putIfAbsent(def.id, def) != null) {
                        errors.add(path + ".id: 重复 " + def.id);
                    }
                } catch (Exception e) {
                    errors.add(path + ": " + e.getMessage());
                }
            }
        }
        return new ParsedRoot(definitions, service, limits, List.copyOf(errors));
    }

    private static JsonObject migrateV1(JsonObject legacy, List<String> errors, String source) {
        Espetro.LOGGER.warn("{}: 读取到旧工事格式；仅进行一次性兼容迁移，请保存为 schema_version=2", source);
        JsonObject migrated = new JsonObject();
        migrated.addProperty("schema_version", SCHEMA_VERSION);
        migrated.add("limits", GSON.toJsonTree(new Limits()));
        migrated.add("vehicle_service", legacy.has("vehicle_service")
            ? legacy.get("vehicle_service").deepCopy()
            : GSON.toJsonTree(new VehicleServiceSettings()));
        JsonArray output = new JsonArray();

        int radioRequired = 600, radioBuild = 30, radioRemove = 5;
        int habRequired = 200, habBuild = 5, habRemove = 5;
        if (legacy.has("builtin_construction") && legacy.get("builtin_construction").isJsonObject()) {
            JsonObject builtins = legacy.getAsJsonObject("builtin_construction");
            int[] radio = legacyProfile(builtins.get("radio"), radioRequired, radioBuild, radioRemove);
            int[] hab = legacyProfile(builtins.get("hab"), habRequired, habBuild, habRemove);
            radioRequired = radio[0]; radioBuild = radio[1]; radioRemove = radio[2];
            habRequired = hab[0]; habBuild = hab[1]; habRemove = hab[2];
        }
        output.add(v2Structure("espetro:radio", List.of("builtin_radio"), "电台", "radio",
            "espetro:fortifications/radio", null, new int[]{0, 0, 0},
            0, radioRequired, radioBuild, radioRemove, radioRequired, false));
        output.add(v2Structure("espetro:hab", List.of("builtin_hab"), "兵站", "hab",
            "espetro:fortifications/hab_attack", Map.of(
                "attack", "espetro:fortifications/hab_attack",
                "defend", "espetro:fortifications/hab_defend"), new int[]{3, 0, 1},
            500, habRequired, habBuild, habRemove, habRequired, true));

        if (legacy.has("fortifications") && legacy.get("fortifications").isJsonArray()) {
            for (JsonElement element : legacy.getAsJsonArray("fortifications")) {
                if (!element.isJsonObject()) continue;
                JsonObject old = element.getAsJsonObject();
                String rawId = string(old, "id", "");
                String id = canonicalId(rawId);
                JsonObject converted = switch (id) {
                    case "espetro:ammo_crate" -> v2Structure(id, List.of("ammo_crate"),
                        string(old, "display_name", "弹药箱"), "ammo_crate",
                        "espetro:fortifications/ammo_crate", null, new int[]{0, 0, 0},
                        integer(old, "construction_cost", 100), integer(old, "required_progress", 100),
                        integer(old, "build_per_hit", 5), integer(old, "remove_per_hit", 5),
                        integer(old, "required_progress", 100), true);
                    case "espetro:sandbag_wall" -> v2Structure(id, List.of("sandbag_wall"),
                        string(old, "display_name", "沙袋掩体墙"), "generic",
                        "espetro:fortifications/sandbag_wall", null, new int[]{1, 0, 0},
                        integer(old, "construction_cost", 100), integer(old, "required_progress", 100),
                        integer(old, "build_per_hit", 5), integer(old, "remove_per_hit", 5),
                        integer(old, "required_progress", 100), true);
                    case "espetro:vehicle_supply_station" -> v2Entity(old);
                    default -> null;
                };
                if (converted == null) {
                    errors.add(source + ": 旧条目 " + rawId
                        + " 无对应 Structure NBT，拒绝以 inline blocks 继续运行");
                } else {
                    if (old.has("icon")) converted.add("icon", legacyIcon(old.get("icon")));
                    output.add(converted);
                }
            }
        }
        migrated.add("fortifications", output);
        return migrated;
    }

    private static JsonObject legacyIcon(JsonElement icon) {
        if (icon != null && icon.isJsonObject()) return icon.getAsJsonObject().deepCopy();
        JsonObject result = new JsonObject();
        result.addProperty("texture", icon == null ? "" : icon.getAsString());
        return result;
    }

    private static JsonObject v2Entity(JsonObject old) {
        JsonObject result = commonV2("espetro:vehicle_supply_station",
            List.of("vehicle_supply_station"), string(old, "display_name", "载具补给站"),
            "vehicle_supply_station", integer(old, "construction_cost", 200),
            integer(old, "required_progress", 100), integer(old, "build_per_hit", 5),
            integer(old, "remove_per_hit", 5), integer(old, "required_progress", 100), true);
        JsonObject placement = new JsonObject();
        placement.addProperty("type", "entity");
        String entityType = firstNonBlank(string(old, "entity_type", ""),
            string(old, "entity_id", "dragonrise_reforge:ammo_supply_station"));
        placement.addProperty("entity_type", entityType);
        placement.addProperty("fallback_template",
            "espetro:fortifications/vehicle_supply_station_fallback");
        placement.add("spawn_offset", GSON.toJsonTree(new double[]{0.5, 0.0, 0.5}));
        placement.addProperty("yaw", "player_facing");
        placement.addProperty("virtual_damageable_part", true);
        placement.add("entity_nbt", new JsonObject());
        result.add("placement", placement);
        return result;
    }

    private static JsonObject v2Structure(String id, List<String> aliases, String name,
                                           String behavior, String template,
                                           @Nullable Map<String, String> byTeam, int[] pivot,
                                           int cost, int required, int build, int remove,
                                           int structural, boolean radioRange) {
        JsonObject result = commonV2(id, aliases, name, behavior, cost, required, build,
            remove, structural, radioRange);
        JsonObject placement = new JsonObject();
        placement.addProperty("type", "structure");
        placement.addProperty("template", template);
        if (byTeam != null) placement.add("template_by_team", GSON.toJsonTree(byTeam));
        placement.add("origin_offset", GSON.toJsonTree(new int[]{0, 0, 0}));
        placement.add("pivot", GSON.toJsonTree(pivot));
        placement.addProperty("rotation", "player_facing");
        placement.addProperty("mirror", "none");
        placement.addProperty("air_policy", "reject_non_replaceable");
        placement.addProperty("include_entities", true);
        placement.addProperty("palette_index", 0);
        result.add("placement", placement);
        return result;
    }

    private static JsonObject commonV2(String id, List<String> aliases, String name,
                                        String behavior, int cost, int required, int build,
                                        int remove, int structural, boolean radioRange) {
        JsonObject result = new JsonObject();
        result.addProperty("id", id);
        result.add("legacy_ids", GSON.toJsonTree(aliases));
        result.addProperty("display_name", name);
        JsonObject icon = new JsonObject();
        icon.addProperty("item", "minecraft:barrier");
        result.add("icon", icon);
        result.addProperty("behavior", behavior);
        JsonObject costJson = new JsonObject();
        costJson.addProperty("construction", Math.max(0, cost));
        costJson.addProperty("ammunition", 0);
        result.add("cost", costJson);
        JsonObject construction = new JsonObject();
        construction.addProperty("required_progress", required);
        construction.addProperty("build_per_hit", build);
        construction.addProperty("remove_per_hit", remove);
        result.add("construction", construction);
        JsonObject durability = new JsonObject();
        durability.addProperty("structural_value", structural);
        durability.addProperty("repair_per_hit", build);
        durability.add("damageable_structure_entities", new JsonArray());
        JsonObject reduction = new JsonObject();
        reduction.addProperty("explosion", 0.9);
        reduction.addProperty("projectile", 0.9);
        reduction.addProperty("direct_break", 0.0);
        durability.add("damage_reduction", reduction);
        result.add("durability", durability);
        JsonObject requirements = new JsonObject();
        requirements.addProperty("require_radio_range", radioRange);
        requirements.add("usable_by", GSON.toJsonTree(List.of(
            "commander", "squad_leader", "fireteam_leader")));
        result.add("requirements", requirements);
        return result;
    }

    private static int[] legacyProfile(JsonElement element, int required, int build, int remove) {
        if (element == null || !element.isJsonObject()) return new int[]{required, build, remove};
        JsonObject object = element.getAsJsonObject();
        return new int[]{integer(object, "required_progress", required),
            integer(object, "build_per_hit", build), integer(object, "remove_per_hit", remove)};
    }

    private static void normalize(FortificationDef def, Limits limits, List<String> errors,
                                  String path) {
        if (def == null) {
            errors.add(path + ": null definition");
            return;
        }
        def.id = canonicalId(def.id);
        if (def.id == null || !ResourceLocation.isValidResourceLocation(def.id)) {
            errors.add(path + ".id: 非法 namespaced id");
            return;
        }
        if (def.id.length() > 128) errors.add(path + ".id: 超过 128 字符");
        if ("espetro:rally".equals(def.id)) errors.add(path + ".id: Rally 不属于工事结构系统");
        if (def.displayName == null || def.displayName.isBlank()) def.displayName = def.id;
        if (def.displayName.length() > 128) errors.add(path + ".display_name: 超过 128 字符");
        if (def.iconData == null) def.iconData = new Icon();
        def.icon = def.iconData.texture != null && !def.iconData.texture.isBlank()
            ? def.iconData.texture : def.iconData.item;
        if (def.icon == null || def.icon.isBlank()) def.icon = "minecraft:barrier";
        try {
            def.behaviorType = Behavior.valueOf(def.behavior == null ? ""
                : def.behavior.trim().toUpperCase(Locale.ROOT));
        } catch (Exception e) {
            errors.add(path + ".behavior: 未知行为 " + def.behavior);
        }
        if (def.placement == null) {
            errors.add(path + ".placement: 缺失");
        } else {
            def.placement.normalize(errors, path + ".placement");
        }
        if (def.cost == null) def.cost = new Cost();
        def.cost.normalize(errors, path + ".cost");
        if (def.construction == null) def.construction = new Construction();
        def.construction.normalize(errors, path + ".construction");
        if (def.durability == null) def.durability = new Durability();
        def.durability.normalize(errors, path + ".durability");
        if (def.requirements == null) def.requirements = new Requirements();
        def.requirements.normalize(errors, path + ".requirements");
        def.legacyIds = def.legacyIds == null ? new ArrayList<>()
            : new ArrayList<>(new LinkedHashSet<>(def.legacyIds));
        def.legacyIds.removeIf(value -> value == null || value.isBlank());
        for (String alias : def.legacyIds) {
            if (alias.length() > 128) errors.add(path + ".legacy_ids: alias too long");
        }
        // Derived compatibility fields are never JSON authorities.
        def.placeType = def.placement == null ? null : def.placement.type;
        def.entityId = def.placement == null ? null : def.placement.entityId;
        def.fallbackBlockId = null;
        def.constructionCost = def.cost.construction;
        def.ammunitionCost = def.cost.ammunition;
        def.requiredProgress = def.construction.requiredProgress;
        def.buildPerHit = def.construction.buildPerHit;
        def.removePerHit = def.construction.removePerHit;
        def.requireRadioRange = def.requirements.requireRadioRange;
        def.usableBy = def.requirements.usableBy;
    }

    private static void compileDefinitions(MinecraftServer server,
                                           Map<String, FortificationDef> definitions,
                                           Limits limits, List<String> errors, String scope) {
        for (FortificationDef def : definitions.values()) {
            try {
                def.compiled = FortificationTemplateCompiler.compile(server, def, limits);
            } catch (Exception e) {
                errors.add(scope + "/" + def.id + "/" +
                    (def.placement == null ? "placement" : def.placement.describeTemplates())
                    + ": " + e.getMessage());
            }
        }
    }

    private static void validateRequired(Map<String, FortificationDef> definitions,
                                         List<String> errors) {
        for (String required : REQUIRED) {
            if (!definitions.containsKey(required)) errors.add("缺少必要工事定义 " + required);
        }
        checkBehavior(definitions, "espetro:radio", Behavior.RADIO, errors);
        checkBehavior(definitions, "espetro:hab", Behavior.HAB, errors);
        checkBehavior(definitions, "espetro:ammo_crate", Behavior.AMMO_CRATE, errors);
        checkBehavior(definitions, "espetro:vehicle_supply_station",
            Behavior.VEHICLE_SUPPLY_STATION, errors);
    }

    private static void checkBehavior(Map<String, FortificationDef> definitions, String id,
                                      Behavior expected, List<String> errors) {
        FortificationDef def = definitions.get(id);
        if (def != null && def.behaviorType != expected) {
            errors.add(id + " 必须使用 behavior=" + expected.name().toLowerCase(Locale.ROOT));
        }
    }

    private static Map<String, FortificationDef> applyOverrides(
        Map<String, FortificationDef> base, String logisticsJson, List<String> errors, String map
    ) {
        Map<String, FortificationDef> result = copyDefinitions(base);
        if (logisticsJson == null || logisticsJson.isBlank()) return result;
        try {
            JsonObject root = JsonParser.parseString(logisticsJson).getAsJsonObject();
            JsonObject logistics = root.has("logistics") && root.get("logistics").isJsonObject()
                ? root.getAsJsonObject("logistics") : null;
            if (logistics != null) {
                applyLegacyCostOverride(result, logistics, "hab_construction_cost",
                    "espetro:hab", map, errors);
                applyLegacyCostOverride(result, logistics, "ammo_crate_construction_cost",
                    "espetro:ammo_crate", map, errors);
            }
            JsonObject overrides = logistics != null && logistics.has("fortification_overrides")
                && logistics.get("fortification_overrides").isJsonObject()
                ? logistics.getAsJsonObject("fortification_overrides") : null;
            if (overrides == null) return result;
            for (Map.Entry<String, JsonElement> entry : overrides.entrySet()) {
                String id = canonicalId(entry.getKey());
                FortificationDef original = result.get(id);
                if (original == null) {
                    errors.add(map + ".logistics.fortification_overrides: 未知 id " + entry.getKey());
                    continue;
                }
                if (!entry.getValue().isJsonObject()) {
                    errors.add(map + ".logistics.fortification_overrides." + entry.getKey()
                        + ": 必须是对象");
                    continue;
                }
                JsonObject override = entry.getValue().getAsJsonObject();
                if (override.has("id") || override.has("legacy_ids")) {
                    errors.add(map + ".logistics.fortification_overrides." + entry.getKey()
                        + ": 不允许覆盖 id/legacy_ids");
                    continue;
                }
                JsonObject merged = GSON.toJsonTree(original).getAsJsonObject();
                deepMerge(merged, override);
                FortificationDef changed = GSON.fromJson(merged, FortificationDef.class);
                normalize(changed, pending.limits, errors,
                    map + ".logistics.fortification_overrides." + entry.getKey());
                result.put(id, changed);
            }
        } catch (Exception e) {
            errors.add(map + ".logistics.json fortification_overrides: " + e.getMessage());
        }
        return result;
    }

    private static void applyLegacyCostOverride(Map<String, FortificationDef> result,
                                                JsonObject logistics, String field, String id,
                                                String map, List<String> errors) {
        if (!logistics.has(field)) return;
        try {
            int value = logistics.get(field).getAsInt();
            FortificationDef def = result.get(id);
            if (def != null) {
                def.cost.construction = boundedNonNegative(value, 1_000_000,
                    map + ".logistics." + field, errors);
                def.constructionCost = def.cost.construction;
                Espetro.LOGGER.warn("{}: {} 仅作为一次性迁移输入；请改写到 fortification_overrides.{}.cost.construction",
                    map, field, id);
            }
        } catch (Exception e) {
            errors.add(map + ".logistics." + field + ": 必须是整数");
        }
    }

    private static void deepMerge(JsonObject target, JsonObject patch) {
        for (Map.Entry<String, JsonElement> entry : patch.entrySet()) {
            JsonElement current = target.get(entry.getKey());
            if (current != null && current.isJsonObject() && entry.getValue().isJsonObject()) {
                deepMerge(current.getAsJsonObject(), entry.getValue().getAsJsonObject());
            } else {
                target.add(entry.getKey(), entry.getValue().deepCopy());
            }
        }
    }

    private static Map<String, FortificationDef> copyDefinitions(
        Map<String, FortificationDef> definitions
    ) {
        Map<String, FortificationDef> result = new LinkedHashMap<>();
        for (Map.Entry<String, FortificationDef> entry : definitions.entrySet()) {
            FortificationDef copy = GSON.fromJson(GSON.toJsonTree(entry.getValue()),
                FortificationDef.class);
            List<String> errors = new ArrayList<>();
            normalize(copy, pending.limits, errors, "copy." + entry.getKey());
            if (!errors.isEmpty()) throw new IllegalStateException(String.join("; ", errors));
            result.put(entry.getKey(), copy);
        }
        return result;
    }

    private static Map<String, String> buildAliases(Map<String, FortificationDef> definitions,
                                                     List<String> errors) {
        Map<String, String> result = new LinkedHashMap<>();
        for (FortificationDef def : definitions.values()) {
            result.put(def.id, def.id);
            for (String raw : def.legacyIds) {
                String canonical = canonicalLookup(raw);
                String previous = result.putIfAbsent(canonical, def.id);
                if (previous != null && !previous.equals(def.id)) {
                    errors.add("legacy alias " + raw + " 同时指向 " + previous + " 和 " + def.id);
                }
            }
        }
        return result;
    }

    @Nullable
    private static FortificationDef resolve(Map<String, FortificationDef> definitions,
                                             Map<String, String> aliases, String raw) {
        if (raw == null) return null;
        String key = canonicalLookup(raw);
        return definitions.get(aliases.getOrDefault(key, key));
    }

    private static String canonicalLookup(String raw) {
        String value = raw.trim().toLowerCase(Locale.ROOT);
        return value.indexOf(':') >= 0 ? value : "espetro:" + value;
    }

    @Nullable
    private static String canonicalId(@Nullable String raw) {
        if (raw == null || raw.isBlank()) return null;
        return canonicalLookup(raw);
    }

    private static int integer(JsonObject object, String key, int fallback) {
        try {
            return object.has(key) ? object.get(key).getAsInt() : fallback;
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static String string(JsonObject object, String key, String fallback) {
        try {
            return object.has(key) ? object.get(key).getAsString() : fallback;
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static boolean hasText(@Nullable String value) {
        return value != null && !value.isBlank();
    }

    private static String firstNonBlank(String first, String second) {
        return hasText(first) ? first : second;
    }

    private static PreparationResult fail(List<String> errors) {
        failure = String.join("; ", errors);
        registrySet = RegistrySet.empty();
        frozen = false;
        Espetro.LOGGER.error("工事 registry 冻结失败，战场 gate 必须保持关闭: {}", failure);
        return PreparationResult.fail(failure);
    }

    private static void publishUncompiled(ParsedRoot root) {
        List<String> errors = new ArrayList<>();
        Map<String, String> aliases = buildAliases(root.definitions, errors);
        if (!errors.isEmpty()) {
            failure = String.join("; ", errors);
            registrySet = RegistrySet.empty();
            return;
        }
        registrySet = new RegistrySet(Collections.unmodifiableMap(root.definitions),
            Collections.unmodifiableMap(aliases), Map.of(), Map.of(),
            root.vehicleService.copy(), root.limits.copy());
        pending = root;
    }

    private static ParsedRoot defaults() {
        try {
            return parse(JsonParser.parseString(bundledDefaultJson()), "bundled-default");
        } catch (Exception e) {
            return ParsedRoot.invalid("内置工事配置不可读: " + e.getMessage());
        }
    }

    private static String bundledDefaultJson() throws Exception {
        try (InputStream stream = FortificationConfig.class.getResourceAsStream(
            "/data/espetro/config/fortifications.json")) {
            if (stream == null) throw new IllegalStateException("缺少内置 fortifications.json");
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    public enum Behavior {
        RADIO, HAB, AMMO_CRATE, VEHICLE_SUPPLY_STATION, GENERIC
    }

    public record PreparationResult(boolean success, int definitionCount, int mapCount,
                                    @Nullable String error) {
        static PreparationResult ok(int definitions, int maps) {
            return new PreparationResult(true, definitions, maps, null);
        }

        static PreparationResult fail(String error) {
            return new PreparationResult(false, 0, 0, error);
        }
    }

    private record ParsedRoot(Map<String, FortificationDef> definitions,
                              VehicleServiceSettings vehicleService, Limits limits,
                              List<String> errors) {
        static ParsedRoot invalid(String error) {
            return new ParsedRoot(new LinkedHashMap<>(), new VehicleServiceSettings(),
                new Limits(), List.of(error));
        }
    }

    private record RegistrySet(Map<String, FortificationDef> global,
                               Map<String, String> aliases,
                               Map<ResourceLocation, Map<String, FortificationDef>> byDimension,
                               Map<ResourceLocation, Map<String, String>> aliasesByDimension,
                               VehicleServiceSettings vehicleService, Limits limits) {
        static RegistrySet empty() {
            return new RegistrySet(Map.of(), Map.of(), Map.of(), Map.of(),
                new VehicleServiceSettings(), new Limits());
        }
    }

    public static final class Limits {
        @SerializedName("max_template_blocks")
        public int maxTemplateBlocks = 4096;
        @SerializedName("max_template_entities")
        public int maxTemplateEntities = 32;
        @SerializedName("max_template_axis")
        public int maxTemplateAxis = 64;
        @SerializedName("max_template_nbt_bytes")
        public int maxTemplateNbtBytes = 2_097_152;
        @SerializedName("max_passenger_depth")
        public int maxPassengerDepth = 4;

        void normalize(List<String> errors, String path) {
            maxTemplateBlocks = boundedPositive(maxTemplateBlocks, HARD_MAX_TEMPLATE_BLOCKS,
                path + ".max_template_blocks", errors);
            maxTemplateEntities = boundedPositive(maxTemplateEntities, HARD_MAX_TEMPLATE_ENTITIES,
                path + ".max_template_entities", errors);
            maxTemplateAxis = boundedPositive(maxTemplateAxis, HARD_MAX_TEMPLATE_AXIS,
                path + ".max_template_axis", errors);
            maxTemplateNbtBytes = boundedPositive(maxTemplateNbtBytes,
                HARD_MAX_TEMPLATE_NBT_BYTES, path + ".max_template_nbt_bytes", errors);
            maxPassengerDepth = boundedPositive(maxPassengerDepth, HARD_MAX_PASSENGER_DEPTH,
                path + ".max_passenger_depth", errors);
        }

        Limits copy() {
            Limits copy = new Limits();
            copy.maxTemplateBlocks = maxTemplateBlocks;
            copy.maxTemplateEntities = maxTemplateEntities;
            copy.maxTemplateAxis = maxTemplateAxis;
            copy.maxTemplateNbtBytes = maxTemplateNbtBytes;
            copy.maxPassengerDepth = maxPassengerDepth;
            return copy;
        }
    }

    public static final class VehicleServiceSettings {
        @SerializedName("main_base_radius")
        public double mainBaseRadius = 40.0;
        @SerializedName("station_radius")
        public double stationRadius = 20.0;
        @SerializedName("transfer_amount")
        public int transferAmount = 100;
        @SerializedName("transfer_interval_ticks")
        public int transferIntervalTicks = 20;

        void normalize(List<String> errors, String path) {
            if (!Double.isFinite(mainBaseRadius)) errors.add(path + ".main_base_radius: 非有限数");
            if (!Double.isFinite(stationRadius)) errors.add(path + ".station_radius: 非有限数");
            mainBaseRadius = Math.max(1.0, Math.min(256.0, mainBaseRadius));
            stationRadius = Math.max(1.0, Math.min(128.0, stationRadius));
            transferAmount = Math.max(1, Math.min(100_000, transferAmount));
            transferIntervalTicks = Math.max(1, Math.min(200, transferIntervalTicks));
        }

        VehicleServiceSettings copy() {
            VehicleServiceSettings copy = new VehicleServiceSettings();
            copy.mainBaseRadius = mainBaseRadius;
            copy.stationRadius = stationRadius;
            copy.transferAmount = transferAmount;
            copy.transferIntervalTicks = transferIntervalTicks;
            return copy;
        }
    }

    public static final class FortificationDef {
        public String id;
        @SerializedName("legacy_ids")
        public List<String> legacyIds = new ArrayList<>();
        @SerializedName("display_name")
        public String displayName;
        @SerializedName("icon")
        public Icon iconData = new Icon();
        public String behavior;
        public Placement placement;
        public Cost cost = new Cost();
        public Construction construction = new Construction();
        public Durability durability = new Durability();
        public Requirements requirements = new Requirements();

        public transient Behavior behaviorType;
        public transient Map<String, FortificationTemplateCompiler.CompiledTemplate> compiled = Map.of();
        // Source-compatible derived fields used by existing network/menu code.
        public transient String icon;
        public transient String placeType;
        public transient String entityId;
        public transient String fallbackBlockId;
        public transient int constructionCost;
        public transient int ammunitionCost;
        public transient int requiredProgress;
        public transient int buildPerHit;
        public transient int removePerHit;
        public transient boolean requireRadioRange;
        public transient List<String> usableBy = List.of();

        @Nullable
        public FortificationTemplateCompiler.CompiledTemplate templateFor(String team) {
            String normalized = team == null ? "default" : team.toLowerCase(Locale.ROOT);
            return compiled.getOrDefault(normalized, compiled.get("default"));
        }
    }

    public static final class Icon {
        public String item;
        public String texture;
    }

    public static final class Placement {
        public String type;
        public String template;
        @SerializedName("template_by_team")
        public Map<String, String> templateByTeam = new LinkedHashMap<>();
        @SerializedName("origin_offset")
        public int[] originOffset = new int[]{0, 0, 0};
        public int[] pivot = new int[]{0, 0, 0};
        public String rotation = "player_facing";
        public String mirror = "none";
        @SerializedName("air_policy")
        public String airPolicy = "reject_non_replaceable";
        @SerializedName("include_entities")
        public boolean includeEntities;
        @SerializedName("palette_index")
        public int paletteIndex;
        @SerializedName(value = "entity_id", alternate = {"entity_type"})
        public String entityId;
        @SerializedName("entity_nbt")
        public JsonObject entityNbt;
        @SerializedName("fallback_template")
        public String fallbackTemplate;
        @SerializedName("spawn_offset")
        public double[] spawnOffset;
        public String yaw;
        @SerializedName("virtual_damageable_part")
        public boolean virtualDamageablePart;
        public transient net.minecraft.nbt.CompoundTag sanitizedEntityNbt;

        void normalize(List<String> errors, String path) {
            type = type == null ? "" : type.trim().toLowerCase(Locale.ROOT);
            if (!Set.of("structure", "entity").contains(type)) {
                errors.add(path + ".type: 必须是 structure 或 entity");
                return;
            }
            if ("structure".equals(type)) {
                if (!"player_facing".equals(rotation)) errors.add(path + ".rotation: 仅支持 player_facing");
                if (!"none".equals(mirror)) errors.add(path + ".mirror: v2 仅支持 none");
                if (!"reject_non_replaceable".equals(airPolicy)) {
                    errors.add(path + ".air_policy: 仅支持 reject_non_replaceable");
                }
                if (originOffset == null || originOffset.length != 3) {
                    errors.add(path + ".origin_offset: 必须有3项");
                }
                if (pivot == null || pivot.length != 3) errors.add(path + ".pivot: 必须有3项");
                paletteIndex = Math.max(0, paletteIndex);
                if (ResourceLocation.tryParse(template) == null) errors.add(path + ".template: 非法或缺失");
                if (hasText(entityId) || entityNbt != null || fallbackTemplate != null
                    || spawnOffset != null || hasText(yaw)) {
                    errors.add(path + ": structure 不得混用 entity 字段");
                }
                if (templateByTeam == null) templateByTeam = new LinkedHashMap<>();
                for (Map.Entry<String, String> entry : templateByTeam.entrySet()) {
                    String side = entry.getKey().toLowerCase(Locale.ROOT);
                    if (!Set.of("attack", "defend", "default").contains(side)
                        || ResourceLocation.tryParse(entry.getValue()) == null) {
                        errors.add(path + ".template_by_team." + entry.getKey() + ": 非法映射");
                    }
                }
            } else {
                if (ResourceLocation.tryParse(entityId) == null) {
                    errors.add(path + ".entity_type: 非法或缺失（entity_id 可作为同义字段）");
                }
                if (!virtualDamageablePart) errors.add(path + ".virtual_damageable_part: entity 必须为 true");
                if (template != null || (templateByTeam != null && !templateByTeam.isEmpty())) {
                    errors.add(path + ": entity 不得混用 structure template 字段");
                }
                if (fallbackTemplate != null && ResourceLocation.tryParse(fallbackTemplate) == null) {
                    errors.add(path + ".fallback_template: 非法");
                }
                if (spawnOffset == null || spawnOffset.length != 3
                    || !Double.isFinite(spawnOffset[0]) || !Double.isFinite(spawnOffset[1])
                    || !Double.isFinite(spawnOffset[2])) {
                    spawnOffset = new double[]{0.5, 0.0, 0.5};
                }
                if (yaw == null || yaw.isBlank()) yaw = "player_facing";
                if (!"player_facing".equals(yaw)) errors.add(path + ".yaw: 仅支持 player_facing");
            }
        }

        String describeTemplates() {
            if ("entity".equals(type)) return entityId + " fallback=" + fallbackTemplate;
            return template + " team=" + templateByTeam;
        }
    }

    public static final class Cost {
        public int construction;
        public int ammunition;

        void normalize(List<String> errors, String path) {
            construction = boundedNonNegative(construction, 1_000_000, path + ".construction", errors);
            ammunition = boundedNonNegative(ammunition, 1_000_000, path + ".ammunition", errors);
        }
    }

    public static final class Construction {
        @SerializedName("required_progress")
        public int requiredProgress = 100;
        @SerializedName("build_per_hit")
        public int buildPerHit = 5;
        @SerializedName("remove_per_hit")
        public int removePerHit = 5;

        void normalize(List<String> errors, String path) {
            requiredProgress = boundedPositive(requiredProgress, 1_000_000,
                path + ".required_progress", errors);
            buildPerHit = boundedPositive(buildPerHit, requiredProgress,
                path + ".build_per_hit", errors);
            removePerHit = boundedPositive(removePerHit, requiredProgress,
                path + ".remove_per_hit", errors);
        }
    }

    public static final class Durability {
        @SerializedName("structural_value")
        public int structuralValue = 100;
        @SerializedName("repair_per_hit")
        public int repairPerHit = 5;
        @SerializedName("damageable_structure_entities")
        public List<Integer> damageableStructureEntities = new ArrayList<>();
        @SerializedName("damage_reduction")
        public DamageReduction damageReduction = new DamageReduction();

        void normalize(List<String> errors, String path) {
            structuralValue = boundedPositive(structuralValue, 1_000_000,
                path + ".structural_value", errors);
            repairPerHit = boundedPositive(repairPerHit, structuralValue,
                path + ".repair_per_hit", errors);
            if (damageReduction == null) damageReduction = new DamageReduction();
            damageReduction.normalize(errors, path + ".damage_reduction");
            if (damageableStructureEntities == null) damageableStructureEntities = new ArrayList<>();
            Set<Integer> unique = new LinkedHashSet<>();
            for (Integer index : damageableStructureEntities) {
                if (index == null || index < 0) errors.add(path + ".damageable_structure_entities: 非法索引");
                else unique.add(index);
            }
            damageableStructureEntities = new ArrayList<>(unique);
        }
    }

    public static final class DamageReduction {
        public double explosion = 0.9;
        public double projectile = 0.9;
        @SerializedName("direct_break")
        public double directBreak;

        void normalize(List<String> errors, String path) {
            explosion = reduction(explosion, path + ".explosion", errors);
            projectile = reduction(projectile, path + ".projectile", errors);
            directBreak = reduction(directBreak, path + ".direct_break", errors);
        }

        public double forKind(FortificationManager.DamageKind kind) {
            return switch (kind) {
                case EXPLOSION -> explosion;
                case PROJECTILE -> projectile;
                case DIRECT_BREAK -> directBreak;
            };
        }
    }

    public static final class Requirements {
        @SerializedName("require_radio_range")
        public boolean requireRadioRange = true;
        @SerializedName("usable_by")
        public List<String> usableBy = new ArrayList<>(List.of(
            "commander", "squad_leader", "fireteam_leader"));

        void normalize(List<String> errors, String path) {
            if (usableBy == null || usableBy.isEmpty()) {
                errors.add(path + ".usable_by: 不得为空");
                return;
            }
            List<String> normalized = new ArrayList<>();
            for (String raw : usableBy) {
                String role = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
                if (!ROLES.contains(role)) errors.add(path + ".usable_by: 未知角色 " + raw);
                else if (!normalized.contains(role)) normalized.add(role);
            }
            usableBy = normalized;
        }
    }

    public static final class ConstructionProfile {
        public int requiredProgress;
        public int buildPerHit;
        public int removePerHit;

        public ConstructionProfile(int requiredProgress, int buildPerHit, int removePerHit) {
            this.requiredProgress = requiredProgress;
            this.buildPerHit = buildPerHit;
            this.removePerHit = removePerHit;
        }

        public ConstructionProfile copy() {
            return new ConstructionProfile(requiredProgress, buildPerHit, removePerHit);
        }
    }

    private static int boundedPositive(int value, int max, String path, List<String> errors) {
        if (value < 1 || value > max) errors.add(path + ": 必须在 [1," + max + "]");
        return Math.max(1, Math.min(max, value));
    }

    private static int boundedNonNegative(int value, int max, String path, List<String> errors) {
        if (value < 0 || value > max) errors.add(path + ": 必须在 [0," + max + "]");
        return Math.max(0, Math.min(max, value));
    }

    private static double reduction(double value, String path, List<String> errors) {
        if (!Double.isFinite(value) || value < 0.0 || value > 1.0) errors.add(path + ": 必须在 [0,1]");
        return Double.isFinite(value) ? Math.max(0.0, Math.min(1.0, value)) : 0.0;
    }
}
