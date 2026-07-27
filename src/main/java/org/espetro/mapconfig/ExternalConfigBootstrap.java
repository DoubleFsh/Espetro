package org.espetro.mapconfig;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.fml.loading.FMLPaths;
import org.espetro.Espetro;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Startup-only loader for EsDimensions.json, EsWorld templates and EsFactions.
 * Results are frozen for the process lifetime; /reload does not re-read them.
 */
public final class ExternalConfigBootstrap {

    public static final String DIMENSIONS_FILE = "EsDimensions.json";
    public static final String WORLD_DIR = "EsWorld";
    public static final String FACTIONS_DIR = "EsFactions";

    private static volatile boolean bootstrapped = false;
    private static List<ActiveMapConfig> allMaps = List.of();
    private static List<ActiveMapConfig> usableMaps = List.of();
    private static Map<String, Path> factionFiles = Map.of();
    private static int mapVoteSeconds = 30;
    private static final List<String> bootstrapErrors = new ArrayList<>();
    private static final List<String> bootstrapWarnings = new ArrayList<>();

    private ExternalConfigBootstrap() {
    }

    public static synchronized void bootstrapIfNeeded() {
        if (bootstrapped) {
            return;
        }
        Path gameDir = FMLPaths.GAMEDIR.get();
        bootstrap(gameDir);
        bootstrapped = true;
    }

    /** Package-visible for tests. */
    static synchronized void bootstrap(Path gameDir) {
        bootstrapErrors.clear();
        bootstrapWarnings.clear();
        List<ActiveMapConfig> maps = new ArrayList<>();
        List<ActiveMapConfig> usable = new ArrayList<>();
        Map<String, Path> factions = new LinkedHashMap<>();
        int voteSeconds = 30;

        ExampleContentInstaller.Result exampleInstall =
            ExampleContentInstaller.installMissing(gameDir);
        bootstrapErrors.addAll(exampleInstall.errors());

        try {
            ensureDirectoryLayout(gameDir);
        } catch (IOException e) {
            bootstrapErrors.add("创建外部目录失败: " + e.getMessage());
            allMaps = List.of();
            usableMaps = List.of();
            factionFiles = Map.of();
            mapVoteSeconds = voteSeconds;
            return;
        }

        Path dimensionsPath = gameDir.resolve(DIMENSIONS_FILE);
        Path worldRoot = gameDir.resolve(WORLD_DIR);
        Path factionsRoot = gameDir.resolve(FACTIONS_DIR);

        try {
            if (!Files.isRegularFile(dimensionsPath)) {
                writeDefaultDimensions(dimensionsPath);
                bootstrapWarnings.add("已创建默认 " + DIMENSIONS_FILE + "（无地图条目）");
            }
            String text = Files.readString(dimensionsPath, StandardCharsets.UTF_8);
            JsonObject root = JsonParser.parseString(text).getAsJsonObject();
            if (root.has("map_vote_seconds") && root.get("map_vote_seconds").isJsonPrimitive()) {
                voteSeconds = Math.max(5, root.get("map_vote_seconds").getAsInt());
            }
            Set<String> usedIds = new LinkedHashSet<>();
            Set<String> usedMapFolders = new LinkedHashSet<>();
            if (root.has("dimensions") && root.get("dimensions").isJsonArray()) {
                JsonArray arr = root.getAsJsonArray("dimensions");
                for (JsonElement el : arr) {
                    if (!el.isJsonObject()) {
                        bootstrapErrors.add("dimensions 元素必须是对象");
                        continue;
                    }
                    JsonObject o = el.getAsJsonObject();
                    String name = o.has("name") ? o.get("name").getAsString() : null;
                    String map = o.has("map") ? o.get("map").getAsString() : null;
                    String manualId = o.has("dimension_id") ? o.get("dimension_id").getAsString() : null;
                    if (name == null || name.isBlank()) {
                        bootstrapErrors.add("维度条目缺少 name");
                        continue;
                    }
                    Optional<String> mapErr = PathSafety.validateMapFolderName(map);
                    if (mapErr.isPresent()) {
                        bootstrapErrors.add(name + ": " + mapErr.get());
                        maps.add(ActiveMapConfig.rejected(name, map == null ? "" : map,
                            DimensionIdUtil.generate(map == null ? name : map), mapErr.get()));
                        continue;
                    }
                    if (!usedMapFolders.add(map)) {
                        String message = "map 文件夹重复注册: " + map;
                        bootstrapErrors.add(name + ": " + message);
                        maps.add(ActiveMapConfig.rejected(
                            name, map, DimensionIdUtil.generate(map), message));
                        continue;
                    }
                    ResourceLocation dimId;
                    if (manualId != null && !manualId.isBlank()) {
                        Optional<String> idErr = DimensionIdUtil.validateManualId(manualId, usedIds);
                        if (idErr.isPresent()) {
                            bootstrapErrors.add(name + ": " + idErr.get());
                            maps.add(ActiveMapConfig.rejected(name, map, DimensionIdUtil.generate(map), idErr.get()));
                            continue;
                        }
                        dimId = DimensionIdUtil.parseOrNull(manualId);
                    } else {
                        dimId = DimensionIdUtil.generate(map);
                        if (usedIds.contains(dimId.toString())) {
                            String msg = "自动生成的 dimension_id 冲突: " + dimId;
                            bootstrapErrors.add(name + ": " + msg);
                            maps.add(ActiveMapConfig.rejected(name, map, dimId, msg));
                            continue;
                        }
                    }
                    usedIds.add(dimId.toString());

                    Path template;
                    try {
                        template = PathSafety.resolveChildDir(worldRoot, map);
                    } catch (IOException e) {
                        bootstrapErrors.add(name + ": " + e.getMessage());
                        maps.add(ActiveMapConfig.rejected(name, map, dimId, e.getMessage()));
                        continue;
                    }
                    if ("_template".equals(map)) {
                        String msg = "不能使用 _template 作为作战地图";
                        bootstrapErrors.add(name + ": " + msg);
                        maps.add(ActiveMapConfig.rejected(name, map, dimId, msg));
                        continue;
                    }
                    ActiveMapConfig cfg = ActiveMapConfig.loadFromTemplate(name, map, dimId, template);
                    maps.add(cfg);
                    cfg.deprecationWarnings().ifPresent(bootstrapWarnings::add);
                    if (cfg.usable) {
                        usable.add(cfg);
                        Espetro.LOGGER.info("已注册地图: {} -> {} ({})", name, dimId, map);
                    } else {
                        bootstrapErrors.add(name + " 拒绝注册: " + String.join("; ", cfg.rejectionReasons));
                        Espetro.LOGGER.error("地图拒绝注册: {} — {}", name, cfg.rejectionReasons);
                    }
                }
            } else {
                bootstrapWarnings.add(DIMENSIONS_FILE + " 无 dimensions 数组");
            }
        } catch (Exception e) {
            bootstrapErrors.add("解析 " + DIMENSIONS_FILE + " 失败: " + e.getMessage());
            Espetro.LOGGER.error("解析 EsDimensions.json 失败", e);
        }

        // Load EsFactions file index (content parsed later by FactionExternalLoader)
        try {
            if (Files.isDirectory(factionsRoot)) {
                try (var stream = Files.list(factionsRoot)) {
                    stream.filter(p -> p.getFileName().toString().endsWith(".json"))
                        .sorted()
                        .forEach(p -> {
                            String id = p.getFileName().toString().replace(".json", "");
                            factions.put(id, p);
                        });
                }
            }
        } catch (IOException e) {
            bootstrapErrors.add("扫描 EsFactions 失败: " + e.getMessage());
        }

        allMaps = List.copyOf(maps);
        usableMaps = List.copyOf(usable);
        factionFiles = Collections.unmodifiableMap(new LinkedHashMap<>(factions));
        mapVoteSeconds = voteSeconds;
        Espetro.LOGGER.info("外部配置冻结: 地图总数={}, 可用={}, 编制文件={}, 投票秒={}",
            allMaps.size(), usableMaps.size(), factionFiles.size(), mapVoteSeconds);
        for (String err : bootstrapErrors) {
            Espetro.LOGGER.error("[EsDimensions] {}", err);
        }
        for (String w : bootstrapWarnings) {
            Espetro.LOGGER.warn("[EsDimensions] {}", w);
        }
    }

    private static void ensureDirectoryLayout(Path gameDir) throws IOException {
        Path worldRoot = gameDir.resolve(WORLD_DIR);
        Path factionsRoot = gameDir.resolve(FACTIONS_DIR);
        Path template = worldRoot.resolve("_template").resolve("EsConfig");
        Files.createDirectories(template);
        Files.createDirectories(factionsRoot);

        Path dimensionsPath = gameDir.resolve(DIMENSIONS_FILE);
        if (!Files.exists(dimensionsPath)) {
            writeDefaultDimensions(dimensionsPath);
        }

        // Default EsConfig templates (only if missing)
        writeIfMissing(template.resolve("game.json"), DEFAULT_GAME_JSON);
        writeIfMissing(template.resolve("spawn_points.json"), DEFAULT_SPAWN_JSON);
        writeIfMissing(template.resolve("outposts.json"), DEFAULT_OUTPOSTS_JSON);
        writeIfMissing(template.resolve("bastion.json"), DEFAULT_BASTION_JSON);
        writeIfMissing(template.resolve("logistics.json"), DEFAULT_LOGISTICS_JSON);
        writeIfMissing(template.resolve("team_pack.json"), DEFAULT_TEAM_PACK_JSON);
        writeIfMissing(template.resolve("VehSpawn.json"), DEFAULT_VEH_SPAWN_JSON);
        writeIfMissing(template.resolve("SquadTypes.json"), DEFAULT_SQUAD_TYPES_JSON);
        writeIfMissing(template.resolve(ESPointsMapSnapshot.TACTICAL_MAP_FILE),
            DEFAULT_TACTICAL_MAP_JSON);
        writeIfMissing(template.resolve(ESPointsMapSnapshot.CAPTURE_POINTS_FILE),
            DEFAULT_CAPTURE_POINTS_JSON);

        // EsFactions：只创建目录，不写入任何预设/示例编制（由运营自行放置 JSON）。
    }

    private static void writeDefaultDimensions(Path path) throws IOException {
        String json = """
            {
              "_comment": "dimension_id 建议省略，由系统自动生成；修改后必须重启游戏或服务端。",
              "map_vote_seconds": 30,
              "dimensions": []
            }
            """;
        Files.writeString(path, json, StandardCharsets.UTF_8);
    }

    private static void writeIfMissing(Path path, String content) throws IOException {
        if (!Files.exists(path)) {
            Files.createDirectories(path.getParent());
            Files.writeString(path, content, StandardCharsets.UTF_8);
        }
    }


    public static boolean isBootstrapped() {
        return bootstrapped;
    }

    public static List<ActiveMapConfig> getAllMaps() {
        bootstrapIfNeeded();
        return allMaps;
    }

    public static List<ActiveMapConfig> getUsableMaps() {
        bootstrapIfNeeded();
        return usableMaps;
    }

    public static Optional<ActiveMapConfig> findByDimensionId(ResourceLocation id) {
        bootstrapIfNeeded();
        for (ActiveMapConfig m : allMaps) {
            if (m.dimensionId.equals(id)) {
                return Optional.of(m);
            }
        }
        return Optional.empty();
    }

    public static Optional<ActiveMapConfig> findByMapFolder(String map) {
        bootstrapIfNeeded();
        for (ActiveMapConfig m : usableMaps) {
            if (m.mapFolder.equals(map)) {
                return Optional.of(m);
            }
        }
        return Optional.empty();
    }

    public static Map<String, Path> getFactionFiles() {
        bootstrapIfNeeded();
        return factionFiles;
    }

    public static int getMapVoteSeconds() {
        bootstrapIfNeeded();
        return mapVoteSeconds;
    }

    public static List<String> getBootstrapErrors() {
        bootstrapIfNeeded();
        return List.copyOf(bootstrapErrors);
    }

    public static List<String> getBootstrapWarnings() {
        bootstrapIfNeeded();
        return List.copyOf(bootstrapWarnings);
    }

    /** Test-only reset. */
    static synchronized void resetForTests() {
        bootstrapped = false;
        allMaps = List.of();
        usableMaps = List.of();
        factionFiles = Map.of();
        mapVoteSeconds = 30;
        bootstrapErrors.clear();
        bootstrapWarnings.clear();
    }

    private static final String DEFAULT_GAME_JSON = """
        {
          "game": {
            "team_select_seconds": 60,
            "deploy_timeout_seconds": 240,
            "deploy_warning_seconds": 30,
            "defend_commander_vote_seconds": 20,
            "attack_commander_vote_seconds": 20,
            "defend_faction_select_seconds": 30,
            "attack_faction_select_seconds": 30,
            "faction_pool_size": 6,
            "faction_reveal_seconds": 5,
            "round_end_seconds": 10,
            "respawn_invincibility_ticks": 60,
            "class_switch_cooldown_seconds": 60,
            "teammate_name_tag_distance": 10.0,
            "waiting_y": 200.0
          },
          "troops": {
            "initial_attack": 280,
            "initial_defend": 1200,
            "commander_death_penalty": 2
          },
          "stamina": {
            "player_stamina": 100,
            "sprint_cost_per_second": 5,
            "jump_cost": 15,
            "regen_delay_seconds": 2,
            "regen_per_second": 2,
            "full_recovery_seconds": 12
          },
          "governance": {
            "impeachment_vote_seconds": 60,
            "impeachment_cooldown_seconds": 600,
            "commander_vacancy_seconds": 180
          }
        }
        """;

    private static final String DEFAULT_SPAWN_JSON = """
        {
          "spawnPoints": {
            "ATTACK": { "x": 100.5, "y": 65, "z": 0.5, "yaw": 0 },
            "DEFEND": { "x": -100.5, "y": 65, "z": 0.5, "yaw": 180 }
          }
        }
        """;

    private static final String DEFAULT_OUTPOSTS_JSON = """
        {
          "redeploy_cooldown_seconds": 60,
          "outposts": []
        }
        """;

    private static final String DEFAULT_BASTION_JSON = """
        {
          "bastion": {
            "cooldown_seconds": 800,
            "required_planks": 0,
            "armor_stand_health": 5,
            "destroy_troop_penalty": 20
          }
        }
        """;

    private static final String DEFAULT_LOGISTICS_JSON = """
        {
          "logistics": {
            "max_construction": 20000,
            "max_ammunition": 20000,
            "pickup_cooldown_seconds": 5,
            "deposit_radius": 8.0,
            "radio_build_radius": 150.0,
            "radio_exclusion_radius": 400.0,
            "radio_teammate_count": 0,
            "radio_teammate_radius": 30.0,
            "require_teammate": false,
            "radio": {
              "allowed_phases": ["BATTLE"],
              "require_commander": false,
              "allow_squad_leader": true,
              "cooldown_seconds": -1,
              "required_planks": 0,
              "creative_bypasses_planks": true,
              "max_active_per_team": -1,
              "build_radius": 150.0,
              "require_target_block": false,
              "exclusion_radius": 400.0,
              "teammate_count": 0,
              "teammate_radius": 30.0
            },
            "hab_construction_cost": 500,
            "ammo_crate_construction_cost": 100,
            "default_resupply_ammo_cost": 50,
            "hab_activation_seconds": 30,
            "hab_reactivation_seconds": 30,
            "hab_disable_radio_health": 75,
            "sources": []
          }
        }
        """;

    private static final String DEFAULT_TEAM_PACK_JSON = """
        {
          "team_pack": {
            "cooldown_seconds": 120,
            "durability": 1,
            "break_speed_multiplier": 8.0,
            "wave_seconds": 60,
            "minimum_respawn_seconds": 20
          }
        }
        """;

    private static final String DEFAULT_VEH_SPAWN_JSON = """
        {
          "VehTypes": ["tank", "apc"],
          "spawn_points": {
            "tank": [
              {
                "id": "tank_1",
                "attack": {"x": 12, "y": 64, "z": 12, "yaw": 0},
                "defend": {"x": 120, "y": 64, "z": 120, "yaw": 180}
              }
            ],
            "apc": [
              {
                "id": "apc_1",
                "attack": {"x": 18, "y": 64, "z": 12, "yaw": 0},
                "defend": {"x": 114, "y": 64, "z": 120, "yaw": 180}
              }
            ]
          }
        }
        """;

    private static final String DEFAULT_SQUAD_TYPES_JSON = """
        {
          "types": [
            {"id": "infantry", "display_name": "步兵队"},
            {"id": "support", "display_name": "支援队"},
            {"id": "vehicle", "display_name": "载具队"},
            {"id": "recon", "display_name": "侦查队"}
          ]
        }
        """;

    private static final String DEFAULT_TACTICAL_MAP_JSON = """
        {
          "topLeftX": -512,
          "topLeftZ": -512,
          "bottomRightX": 512,
          "bottomRightZ": 512,
          "initialRange": 512,
          "minimumRange": 64,
          "backgroundImage": "",
          "backgroundImageWidth": 0,
          "backgroundImageHeight": 0,
          "showGrid": true,
          "showLabels": true,
          "tacticalMarkerDurationSeconds": 120,
          "tacticalMarkerFadeSeconds": 120
        }
        """;

    private static final String DEFAULT_CAPTURE_POINTS_JSON = """
        {
          "totalBatches": 1,
          "endBehavior": "terminate",
          "teamReinforcements": {
            "ATTACK": 280,
            "DEFEND": 1200
          },
          "plannedPoints": [
            {
              "name": "A",
              "batch": 1,
              "pos1": {"x": -24, "y": 60, "z": -24},
              "pos2": {"x": 24, "y": 72, "z": 24}
            }
          ]
        }
        """;

}
