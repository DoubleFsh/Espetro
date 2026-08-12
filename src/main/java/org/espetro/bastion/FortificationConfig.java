package org.espetro.bastion;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.annotations.SerializedName;
import net.minecraftforge.fml.loading.FMLPaths;
import org.espetro.Espetro;

import javax.annotation.Nullable;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Global, server-owned fortification and vehicle service configuration.
 *
 * <p>The file is loaded once while the server starts.  Keeping this catalogue
 * frozen for the lifetime of a server prevents clients and the server from
 * disagreeing about radial-menu entries during an active match.</p>
 */
public final class FortificationConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Map<String, FortificationDef> DEFS = new LinkedHashMap<>();
    private static VehicleServiceSettings vehicleService = new VehicleServiceSettings();
    private static BuiltinConstructionSettings builtinConstruction =
        new BuiltinConstructionSettings();
    private static DamageSettings damageSettings = new DamageSettings();

    public static final int MAX_STRUCTURE_BLOCKS = 256;
    public static final int MAX_STRUCTURE_OFFSET = 32;

    public static float explosionDamageRatio() {
        return damageSettings.explosion;
    }

    public static float projectileHitDamageRatio() {
        return damageSettings.projectileHit;
    }

    private FortificationConfig() {
    }

    /** Load the global file, creating a documented default on first start. */
    public static void loadServerConfig() {
        Path configPath = FMLPaths.CONFIGDIR.get()
            .resolve("espetro").resolve("fortifications.json");
        try {
            Files.createDirectories(configPath.getParent());
            if (!Files.isRegularFile(configPath)) {
                Files.writeString(configPath, GSON.toJson(defaultRoot()), StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            Espetro.LOGGER.warn("无法创建工事配置 {}: {}", configPath, e.toString());
        }
        loadFromPath(configPath);
    }

    public static void loadDefaults() {
        DEFS.clear();
        vehicleService = new VehicleServiceSettings();
        builtinConstruction = new BuiltinConstructionSettings();
        damageSettings = new DamageSettings();
        register(defaultAmmoCrate());
        register(defaultVehicleSupplyStation());
        register(defaultSandbagWall());
    }

    public static void loadFromPath(@Nullable Path path) {
        loadDefaults();
        if (path == null || !Files.isRegularFile(path)) {
            return;
        }
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            JsonObject root = GSON.fromJson(reader, JsonObject.class);
            if (root == null) return;

            if (root.has("vehicle_service") && root.get("vehicle_service").isJsonObject()) {
                VehicleServiceSettings parsed = GSON.fromJson(
                    root.getAsJsonObject("vehicle_service"), VehicleServiceSettings.class);
                if (parsed != null) {
                    parsed.normalize();
                    vehicleService = parsed;
                }
            }

            if (root.has("builtin_construction")
                && root.get("builtin_construction").isJsonObject()) {
                BuiltinConstructionSettings parsed = GSON.fromJson(
                    root.getAsJsonObject("builtin_construction"),
                    BuiltinConstructionSettings.class);
                if (parsed != null) {
                    parsed.normalize();
                    builtinConstruction = parsed;
                }
            }

            if (root.has("damage") && root.get("damage").isJsonObject()) {
                DamageSettings parsed = GSON.fromJson(
                    root.getAsJsonObject("damage"), DamageSettings.class);
                if (parsed != null) {
                    parsed.normalize();
                    damageSettings = parsed;
                }
            }

            JsonArray arr = root.has("fortifications") && root.get("fortifications").isJsonArray()
                ? root.getAsJsonArray("fortifications") : null;
            if (arr != null) {
                DEFS.clear();
                for (JsonElement el : arr) {
                    if (!el.isJsonObject()) continue;
                    FortificationDef def = GSON.fromJson(el, FortificationDef.class);
                    String rejection = normalizeAndValidate(def);
                    if (rejection == null) {
                        register(def);
                    } else {
                        Espetro.LOGGER.warn("忽略无效工事配置: {}", rejection);
                    }
                }
            }
            if (DEFS.isEmpty()) {
                Espetro.LOGGER.warn("工事配置没有有效条目，恢复内置默认值");
                register(defaultAmmoCrate());
                register(defaultVehicleSupplyStation());
                register(defaultSandbagWall());
            }
            Espetro.LOGGER.info("已冻结工事配置: {} 条，主基地补给半径={}，补给站半径={}",
                DEFS.size(), vehicleService.mainBaseRadius, vehicleService.stationRadius);
        } catch (Exception e) {
            Espetro.LOGGER.warn("工事配置加载失败 {}: {}，继续使用内置默认值", path, e.toString());
        }
    }

    private static JsonObject defaultRoot() {
        JsonObject root = new JsonObject();
        root.add("vehicle_service", GSON.toJsonTree(new VehicleServiceSettings()));
        root.add("builtin_construction", GSON.toJsonTree(new BuiltinConstructionSettings()));
        root.add("damage", GSON.toJsonTree(new DamageSettings()));
        root.add("fortifications", GSON.toJsonTree(List.of(
            defaultAmmoCrate(), defaultVehicleSupplyStation(), defaultSandbagWall())));
        return root;
    }

    public static final class DamageSettings {
        @SerializedName("explosion_damage_ratio")
        public float explosion = 0.1f;
        @SerializedName("projectile_hit_damage_ratio")
        public float projectileHit = 0.1f;

        private void normalize() {
            explosion = Math.max(0.0f, Math.min(1.0f, explosion));
            projectileHit = Math.max(0.0f, Math.min(1.0f, projectileHit));
        }
    }

    @Nullable
    private static String normalizeAndValidate(@Nullable FortificationDef def) {
        if (def == null || def.id == null || def.id.isBlank()) {
            return "缺少 id";
        }
        def.id = def.id.trim().toLowerCase(Locale.ROOT);
        if (!def.id.matches("[a-z0-9_.-]{1,64}")) {
            return def.id + " 的 id 格式无效";
        }
        if (DEFS.containsKey(def.id)) {
            return def.id + " 重复";
        }
        if (def.displayName == null || def.displayName.isBlank()) {
            def.displayName = def.id;
        }
        if (def.icon == null || def.icon.isBlank()) {
            def.icon = "espetro:textures/gui/commander_skills/unavailable.png";
        }
        def.placeType = def.placeType == null
            ? "block" : def.placeType.trim().toLowerCase(Locale.ROOT);
        if (!"block".equals(def.placeType) && !"entity".equals(def.placeType)
            && !"structure".equals(def.placeType)) {
            return def.id + " 的 place_type 必须是 block、entity 或 structure";
        }
        if ("block".equals(def.placeType) && isBlank(def.blockId)) {
            return def.id + " 缺少 block_id";
        }
        if ("entity".equals(def.placeType) && isBlank(def.entityId) && isBlank(def.fallbackBlockId)) {
            return def.id + " 缺少 entity_id/fallback_block_id";
        }
        if ("structure".equals(def.placeType)) {
            if (def.blocks == null || def.blocks.isEmpty()) {
                return def.id + " 缺少 blocks";
            }
            if (def.blocks.size() > MAX_STRUCTURE_BLOCKS) {
                return def.id + " 的 blocks 超过 " + MAX_STRUCTURE_BLOCKS + " 个";
            }
            Set<String> offsets = new java.util.HashSet<>();
            for (StructureBlockDef block : def.blocks) {
                if (block == null || block.offset == null || block.offset.size() != 3
                    || isBlank(block.blockId)) {
                    return def.id + " 包含无效结构方块";
                }
                int x = block.offset.get(0);
                int y = block.offset.get(1);
                int z = block.offset.get(2);
                if (Math.abs(x) > MAX_STRUCTURE_OFFSET || Math.abs(y) > MAX_STRUCTURE_OFFSET
                    || Math.abs(z) > MAX_STRUCTURE_OFFSET) {
                    return def.id + " 包含超出范围的结构偏移";
                }
                if (!offsets.add(x + "," + y + "," + z)) {
                    return def.id + " 包含重复结构偏移 " + x + "," + y + "," + z;
                }
            }
        }
        def.constructionCost = Math.max(0, def.constructionCost);
        def.ammunitionCost = Math.max(0, def.ammunitionCost);
        def.normalizeProgress();
        if (def.usableBy == null || def.usableBy.isEmpty()) {
            def.usableBy = new ArrayList<>(List.of(
                "commander", "squad_leader", "fireteam_leader"));
        } else {
            Set<String> supported = Set.of("commander", "squad_leader", "fireteam_leader");
            List<String> roles = new ArrayList<>();
            for (String raw : def.usableBy) {
                String role = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
                if (!supported.contains(role)) {
                    return def.id + " 包含未知 usable_by: " + raw;
                }
                if (!roles.contains(role)) roles.add(role);
            }
            def.usableBy = roles;
        }
        return null;
    }

    private static boolean isBlank(@Nullable String value) {
        return value == null || value.isBlank();
    }

    private static void register(FortificationDef def) {
        DEFS.put(def.id.toLowerCase(Locale.ROOT), def);
    }

    public static VehicleServiceSettings vehicleService() {
        return vehicleService;
    }

    public static ConstructionProfile radioConstruction() {
        return builtinConstruction.radio.copy();
    }

    public static ConstructionProfile habConstruction() {
        return builtinConstruction.hab.copy();
    }

    public static Map<String, FortificationDef> all() {
        return Collections.unmodifiableMap(DEFS);
    }

    @Nullable
    public static FortificationDef get(String id) {
        if (id == null) return null;
        return DEFS.get(id.toLowerCase(Locale.ROOT));
    }

    public static List<FortificationDef> list() {
        return List.copyOf(DEFS.values());
    }

    private static FortificationDef defaultAmmoCrate() {
        FortificationDef d = new FortificationDef();
        d.id = "ammo_crate";
        d.displayName = "弹药箱";
        d.icon = "espetro:textures/gui/squad/ammo_crate.png";
        d.placeType = "block";
        d.blockId = "minecraft:shulker_box";
        d.constructionCost = 100;
        d.ammunitionCost = 0;
        d.requireRadioRange = true;
        d.usableBy = new ArrayList<>(List.of(
            "commander", "squad_leader", "fireteam_leader"));
        return d;
    }

    private static FortificationDef defaultVehicleSupplyStation() {
        FortificationDef d = new FortificationDef();
        d.id = "vehicle_supply_station";
        d.displayName = "载具补给站";
        d.icon = "espetro:textures/gui/commander_skills/vehicle_supply_station.png";
        d.placeType = "entity";
        d.entityId = "dragonrise_reforge:ammo_supply_station";
        d.fallbackBlockId = "minecraft:barrel";
        d.constructionCost = 200;
        d.ammunitionCost = 0;
        d.requireRadioRange = true;
        d.usableBy = new ArrayList<>(List.of(
            "commander", "squad_leader", "fireteam_leader"));
        return d;
    }

    private static FortificationDef defaultSandbagWall() {
        FortificationDef d = new FortificationDef();
        d.id = "sandbag_wall";
        d.displayName = "沙袋掩体墙";
        d.icon = "superbwarfare:textures/block/sandbag.png";
        d.placeType = "structure";
        d.constructionCost = 100;
        d.ammunitionCost = 0;
        d.requiredProgress = 100;
        d.buildPerHit = 5;
        d.removePerHit = 5;
        d.requireRadioRange = true;
        d.usableBy = new ArrayList<>(List.of(
            "commander", "squad_leader", "fireteam_leader"));
        d.blocks = new ArrayList<>();
        for (int y = 0; y < 2; y++) {
            for (int x = -1; x <= 1; x++) {
                d.blocks.add(new StructureBlockDef(
                    new ArrayList<>(List.of(x, y, 0)), "superbwarfare:sandbag"));
            }
        }
        return d;
    }

    public static final class BuiltinConstructionSettings {
        public ConstructionProfile radio = new ConstructionProfile(600, 30, 5);
        public ConstructionProfile hab = new ConstructionProfile(200, 5, 5);

        private void normalize() {
            if (radio == null) radio = new ConstructionProfile(600, 30, 5);
            if (hab == null) hab = new ConstructionProfile(200, 5, 5);
            radio.normalize(600, 30, 5);
            hab.normalize(200, 5, 5);
        }
    }

    public static final class ConstructionProfile {
        @SerializedName("required_progress")
        public int requiredProgress;
        @SerializedName("build_per_hit")
        public int buildPerHit;
        @SerializedName("remove_per_hit")
        public int removePerHit;

        public ConstructionProfile() {
        }

        public ConstructionProfile(int requiredProgress, int buildPerHit, int removePerHit) {
            this.requiredProgress = requiredProgress;
            this.buildPerHit = buildPerHit;
            this.removePerHit = removePerHit;
        }

        private void normalize(int fallbackRequired, int fallbackBuild, int fallbackRemove) {
            if (requiredProgress <= 0) requiredProgress = fallbackRequired;
            requiredProgress = Math.min(1_000_000, requiredProgress);
            if (buildPerHit <= 0) buildPerHit = fallbackBuild;
            if (removePerHit <= 0) removePerHit = fallbackRemove;
            buildPerHit = Math.min(requiredProgress, buildPerHit);
            removePerHit = Math.min(requiredProgress, removePerHit);
        }

        public ConstructionProfile copy() {
            return new ConstructionProfile(requiredProgress, buildPerHit, removePerHit);
        }
    }

    public static final class StructureBlockDef {
        public List<Integer> offset = new ArrayList<>();
        @SerializedName(value = "block_id", alternate = {"blockId"})
        public String blockId;

        public StructureBlockDef() {
        }

        public StructureBlockDef(List<Integer> offset, String blockId) {
            this.offset = offset;
            this.blockId = blockId;
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

        private void normalize() {
            if (!Double.isFinite(mainBaseRadius)) mainBaseRadius = 40.0;
            if (!Double.isFinite(stationRadius)) stationRadius = 20.0;
            mainBaseRadius = clamp(mainBaseRadius, 1.0, 256.0);
            stationRadius = clamp(stationRadius, 1.0, 128.0);
            transferAmount = Math.max(1, Math.min(100_000, transferAmount));
            transferIntervalTicks = Math.max(1, Math.min(200, transferIntervalTicks));
        }

        private static double clamp(double value, double min, double max) {
            return Math.max(min, Math.min(max, value));
        }
    }

    public static final class FortificationDef {
        public String id;
        @SerializedName(value = "display_name", alternate = {"displayName"})
        public String displayName;
        public String icon;
        @SerializedName(value = "place_type", alternate = {"placeType"})
        public String placeType = "block";
        @SerializedName(value = "block_id", alternate = {"blockId"})
        public String blockId;
        @SerializedName(value = "entity_id", alternate = {"entityId"})
        public String entityId;
        @SerializedName(value = "fallback_block_id", alternate = {"fallbackBlockId"})
        public String fallbackBlockId;
        @SerializedName(value = "construction_cost", alternate = {"constructionCost"})
        public int constructionCost;
        @SerializedName(value = "ammunition_cost", alternate = {"ammunitionCost"})
        public int ammunitionCost;
        @SerializedName(value = "required_progress", alternate = {"requiredProgress"})
        public int requiredProgress = 100;
        @SerializedName(value = "build_per_hit", alternate = {"buildPerHit"})
        public int buildPerHit = 5;
        @SerializedName(value = "remove_per_hit", alternate = {"removePerHit"})
        public int removePerHit = 5;
        public List<StructureBlockDef> blocks = new ArrayList<>();
        @SerializedName(value = "require_radio_range", alternate = {"requireRadioRange"})
        public boolean requireRadioRange = true;
        @SerializedName(value = "usable_by", alternate = {"usableBy"})
        public List<String> usableBy = new ArrayList<>();

        private void normalizeProgress() {
            ConstructionProfile profile = new ConstructionProfile(
                requiredProgress, buildPerHit, removePerHit);
            profile.normalize(100, 5, 5);
            requiredProgress = profile.requiredProgress;
            buildPerHit = profile.buildPerHit;
            removePerHit = profile.removePerHit;
        }
    }
}
