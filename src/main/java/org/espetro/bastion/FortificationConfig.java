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
        register(defaultAmmoCrate());
        register(defaultVehicleSupplyStation());
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
        root.add("fortifications", GSON.toJsonTree(List.of(
            defaultAmmoCrate(), defaultVehicleSupplyStation())));
        return root;
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
        if (!"block".equals(def.placeType) && !"entity".equals(def.placeType)) {
            return def.id + " 的 place_type 必须是 block 或 entity";
        }
        if ("block".equals(def.placeType) && isBlank(def.blockId)) {
            return def.id + " 缺少 block_id";
        }
        if ("entity".equals(def.placeType) && isBlank(def.entityId) && isBlank(def.fallbackBlockId)) {
            return def.id + " 缺少 entity_id/fallback_block_id";
        }
        def.constructionCost = Math.max(0, def.constructionCost);
        def.ammunitionCost = Math.max(0, def.ammunitionCost);
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
        @SerializedName(value = "require_radio_range", alternate = {"requireRadioRange"})
        public boolean requireRadioRange = true;
        @SerializedName(value = "usable_by", alternate = {"usableBy"})
        public List<String> usableBy = new ArrayList<>();
    }
}
