package org.espetro.mapconfig;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Immutable frozen configuration for one registered map / dimension.
 * Activated only when that map wins the vote for a match.
 */
public final class ActiveMapConfig {

    public static final List<String> REQUIRED_ES_CONFIG_FILES = List.of(
        "game.json",
        "spawn_points.json",
        "outposts.json",
        "bastion.json",
        "logistics.json",
        "team_pack.json",
        "VehSpawn.json",
        "SquadTypes.json",
        ESPointsMapSnapshot.TACTICAL_MAP_FILE,
        ESPointsMapSnapshot.CAPTURE_POINTS_FILE
    );

    public final String displayName;
    public final String mapFolder;
    public final ResourceLocation dimensionId;
    public final ResourceKey<Level> dimensionKey;
    public final Path templateWorldDir;
    public final Path esConfigDir;
    /** Complete level-stem JSON derived from this template's level.dat. */
    public final String dimensionJson;

    public final GameSettingsSnapshot game;
    public final SpawnPointsSnapshot spawnPoints;
    public final VehSpawnSnapshot vehSpawn;
    public final SquadTypesSnapshot squadTypes;
    public final String outpostsJson;
    public final String bastionJson;
    public final String logisticsJson;
    public final String teamPackJson;
    public final ESPointsMapSnapshot esPoints;

    /** 地图预览图 PNG 字节数据（服务端读取，通过网络发送给客户端）。null 表示无预览图。 */
    public final byte[] previewImageBytes;

    /**
     * 获取 CapturePoints.json 原始内容，供 HCRPoints 运行时使用
     */
    public String capturePointsJson() {
        return esPoints != null ? esPoints.capturePointsJson : null;
    }

    public final List<String> rejectionReasons;
    public final boolean usable;

    private ActiveMapConfig(
        String displayName,
        String mapFolder,
        ResourceLocation dimensionId,
        Path templateWorldDir,
        Path esConfigDir,
        String dimensionJson,
        GameSettingsSnapshot game,
        SpawnPointsSnapshot spawnPoints,
        VehSpawnSnapshot vehSpawn,
        SquadTypesSnapshot squadTypes,
        String outpostsJson,
        String bastionJson,
        String logisticsJson,
        String teamPackJson,
        ESPointsMapSnapshot esPoints,
        byte[] previewImageBytes,
        List<String> rejectionReasons
    ) {
        this.displayName = displayName;
        this.mapFolder = mapFolder;
        this.dimensionId = dimensionId;
        this.dimensionKey = ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION, dimensionId);
        this.templateWorldDir = templateWorldDir;
        this.esConfigDir = esConfigDir;
        this.dimensionJson = dimensionJson;
        this.game = game;
        this.spawnPoints = spawnPoints;
        this.vehSpawn = vehSpawn;
        this.squadTypes = squadTypes;
        this.outpostsJson = outpostsJson;
        this.bastionJson = bastionJson;
        this.logisticsJson = logisticsJson;
        this.teamPackJson = teamPackJson;
        this.esPoints = esPoints;
        this.previewImageBytes = previewImageBytes;
        this.rejectionReasons = List.copyOf(rejectionReasons);
        this.usable = rejectionReasons.isEmpty();
    }

    public static ActiveMapConfig rejected(String displayName, String mapFolder, ResourceLocation dimensionId, String reason) {
        return new ActiveMapConfig(
            displayName, mapFolder, dimensionId, null, null, null,
            GameSettingsSnapshot.defaults(),
            new SpawnPointsSnapshot(null, null, false, reason),
            new VehSpawnSnapshot(List.of(), java.util.Map.of(), List.of(reason)),
            SquadTypesSnapshot.defaults(),
            null, null, null, null, null, null,
            List.of(reason)
        );
    }

    public static ActiveMapConfig loadFromTemplate(
        String displayName,
        String mapFolder,
        ResourceLocation dimensionId,
        Path templateWorldDir
    ) {
        List<String> errors = new ArrayList<>();
        if (templateWorldDir == null || !Files.isDirectory(templateWorldDir)) {
            return rejected(displayName, mapFolder, dimensionId, "地图目录不存在: " + mapFolder);
        }
        if (!Files.isRegularFile(templateWorldDir.resolve("level.dat"))) {
            errors.add("地图缺少 level.dat（必须提供完整原版世界存档）: " + mapFolder);
        }
        Path region = templateWorldDir.resolve("region");
        if (!Files.isDirectory(region)) {
            errors.add("地图缺少 region/ 目录（不是完整世界）: " + mapFolder);
        }
        Path esConfig = templateWorldDir.resolve("EsConfig");
        if (!Files.isDirectory(esConfig)) {
            errors.add("地图缺少 EsConfig/ 目录: " + mapFolder);
            return rejected(displayName, mapFolder, dimensionId, String.join("; ", errors));
        }

        for (String required : REQUIRED_ES_CONFIG_FILES) {
            Path f = esConfig.resolve(required);
            if (!Files.isRegularFile(f)) {
                errors.add("缺少必需配置: EsConfig/" + required);
            }
        }
        if (!errors.isEmpty()) {
            return rejected(displayName, mapFolder, dimensionId, String.join("; ", errors));
        }

        try {
            Gson gson = new Gson();
            String dimensionJson = gson.toJson(
                LevelDatDimensionReader.readDimensionJson(templateWorldDir.resolve("level.dat")));
            GameSettingsSnapshot game = GameSettingsSnapshot.parse(readJson(esConfig.resolve("game.json")));
            if (game.deprecatedRequiredPlayersPresent) {
                // warning only — not a rejection
            }
            SpawnPointsSnapshot spawnPoints = SpawnPointsSnapshot.parse(readJson(esConfig.resolve("spawn_points.json")));
            if (!spawnPoints.valid) {
                errors.add(spawnPoints.error);
            }
            VehSpawnSnapshot vehSpawn = VehSpawnSnapshot.parse(readJson(esConfig.resolve("VehSpawn.json")));
            errors.addAll(vehSpawn.errors);
            SquadTypesSnapshot squadTypes = SquadTypesSnapshot.parse(readJson(esConfig.resolve("SquadTypes.json")));
            errors.addAll(squadTypes.errors);

            String outpostsJson = Files.readString(esConfig.resolve("outposts.json"), StandardCharsets.UTF_8);
            String bastionJson = Files.readString(esConfig.resolve("bastion.json"), StandardCharsets.UTF_8);
            String logisticsJson = Files.readString(esConfig.resolve("logistics.json"), StandardCharsets.UTF_8);
            String teamPackJson = Files.readString(esConfig.resolve("team_pack.json"), StandardCharsets.UTF_8);
            ESPointsMapSnapshot esPoints = ESPointsMapSnapshot.load(esConfig);

            // 读取 EsWorld/{map}/map_preview.png 字节数据
            byte[] previewImageBytes = null;
            Path previewPath = templateWorldDir.resolve("map_preview.png");
            if (Files.isRegularFile(previewPath)) {
                try {
                    previewImageBytes = Files.readAllBytes(previewPath);
                } catch (IOException e) {
                    // 读取失败视为无预览图
                }
            }

            // Validate JSON syntax of remaining configs
            validateOutposts(outpostsJson, errors);
            validateObjectSection(bastionJson, "bastion.json", "bastion", errors);
            validateObjectSection(logisticsJson, "logistics.json", "logistics", errors);
            validateObjectSection(teamPackJson, "team_pack.json", "team_pack", errors);

            if (!errors.isEmpty()) {
                return rejected(displayName, mapFolder, dimensionId, String.join("; ", errors));
            }

            return new ActiveMapConfig(
                displayName, mapFolder, dimensionId, templateWorldDir, esConfig, dimensionJson,
                game, spawnPoints, vehSpawn, squadTypes,
                outpostsJson, bastionJson, logisticsJson, teamPackJson, esPoints,
                previewImageBytes,
                List.of()
            );
        } catch (Exception e) {
            return rejected(displayName, mapFolder, dimensionId, "加载 EsConfig 失败: " + e.getMessage());
        }
    }

    private static JsonObject readJson(Path path) throws IOException {
        String text = Files.readString(path, StandardCharsets.UTF_8);
        return JsonParser.parseString(text).getAsJsonObject();
    }

    private static JsonObject parseObjectOrError(String json, String name, List<String> errors) {
        try {
            var parsed = JsonParser.parseString(json);
            if (!parsed.isJsonObject()) {
                errors.add(name + " 根节点必须是 JSON 对象");
                return null;
            }
            return parsed.getAsJsonObject();
        } catch (Exception e) {
            errors.add(name + " JSON 语法错误: " + e.getMessage());
            return null;
        }
    }

    private static void validateObjectSection(
        String json, String fileName, String section, List<String> errors
    ) {
        JsonObject root = parseObjectOrError(json, fileName, errors);
        if (root == null) {
            return;
        }
        if (!root.has(section) || !root.get(section).isJsonObject()) {
            errors.add(fileName + " 缺少对象节点 " + section);
        }
    }

    private static void validateOutposts(String json, List<String> errors) {
        JsonObject root = parseObjectOrError(json, "outposts.json", errors);
        if (root == null) {
            return;
        }
        if (!root.has("outposts") || !root.get("outposts").isJsonArray()) {
            errors.add("outposts.json 缺少 outposts 数组");
            return;
        }
        int index = 0;
        for (var element : root.getAsJsonArray("outposts")) {
            index++;
            if (!element.isJsonObject()) {
                errors.add("outposts.json 第 " + index + " 个前哨必须是对象");
                continue;
            }
            JsonObject outpost = element.getAsJsonObject();
            for (String coordinate : List.of("x", "y", "z")) {
                if (!outpost.has(coordinate)
                    || !outpost.get(coordinate).isJsonPrimitive()
                    || !outpost.getAsJsonPrimitive(coordinate).isNumber()) {
                    errors.add("outposts.json 第 " + index + " 个前哨缺少数值 " + coordinate);
                }
            }
        }
    }

    public Optional<String> deprecationWarnings() {
        List<String> w = new ArrayList<>();
        if (game.deprecatedRequiredPlayersPresent) {
            w.add(mapFolder + ": game.required_players 已废弃，忽略");
        }
        if (game.deprecatedTutorialPresent) {
            w.add(mapFolder + ": tutorial 段已废弃，忽略");
        }
        return w.isEmpty() ? Optional.empty() : Optional.of(String.join("; ", w));
    }
}
