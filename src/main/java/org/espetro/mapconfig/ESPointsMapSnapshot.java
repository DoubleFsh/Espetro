package org.espetro.mapconfig;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import javax.annotation.Nullable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Frozen ESPoints extension data belonging to one Espetro map.
 *
 * <p>The source files are read exactly once during external-map bootstrap.
 * Consumers receive defensive copies and never get a path they could use to
 * mutate the read-only EsWorld template.</p>
 */
public final class ESPointsMapSnapshot {
    public static final String TACTICAL_MAP_FILE = "TacticalMap.json";
    public static final String CAPTURE_POINTS_FILE = "CapturePoints.json";
    public static final int MAX_BACKGROUND_BYTES = 16 * 1024 * 1024;
    public static final int MAX_POINTS_PER_BATCH = 7;

    public final String tacticalMapJson;
    public final String capturePointsJson;
    public final String backgroundImage;
    private final byte[] backgroundBytes;

    private ESPointsMapSnapshot(String tacticalMapJson, String capturePointsJson,
                                String backgroundImage, byte[] backgroundBytes) {
        this.tacticalMapJson = tacticalMapJson;
        this.capturePointsJson = capturePointsJson;
        this.backgroundImage = backgroundImage == null ? "" : backgroundImage;
        this.backgroundBytes = backgroundBytes == null ? new byte[0] : backgroundBytes.clone();
    }

    public static ESPointsMapSnapshot load(Path esConfigDir) throws IOException {
        String tacticalJson = Files.readString(
            esConfigDir.resolve(TACTICAL_MAP_FILE), StandardCharsets.UTF_8);
        String captureJson = Files.readString(
            esConfigDir.resolve(CAPTURE_POINTS_FILE), StandardCharsets.UTF_8);

        JsonObject tactical = requireObject(tacticalJson, TACTICAL_MAP_FILE);
        validateTacticalMap(tactical);
        validateCapturePoints(requireObject(captureJson, CAPTURE_POINTS_FILE));

        String image = optionalString(tactical, "backgroundImage", "").trim();
        byte[] imageBytes = image.isEmpty() ? new byte[0] : readBackground(esConfigDir, image);
        return new ESPointsMapSnapshot(tacticalJson, captureJson, image, imageBytes);
    }

    public byte[] backgroundBytes() {
        return backgroundBytes.clone();
    }

    public boolean hasBackground() {
        return backgroundBytes.length > 0;
    }

    private static void validateTacticalMap(JsonObject root) {
        int topLeftX = requiredInt(root, "topLeftX", TACTICAL_MAP_FILE);
        int topLeftZ = requiredInt(root, "topLeftZ", TACTICAL_MAP_FILE);
        int bottomRightX = requiredInt(root, "bottomRightX", TACTICAL_MAP_FILE);
        int bottomRightZ = requiredInt(root, "bottomRightZ", TACTICAL_MAP_FILE);
        if (bottomRightX <= topLeftX || bottomRightZ <= topLeftZ) {
            throw new IllegalArgumentException(
                TACTICAL_MAP_FILE + " 地图右下角必须位于左上角的东南方向");
        }

        int initialRange = requiredInt(root, "initialRange", TACTICAL_MAP_FILE);
        int minimumRange = requiredInt(root, "minimumRange", TACTICAL_MAP_FILE);
        if (initialRange <= 0 || minimumRange <= 0 || minimumRange > initialRange) {
            throw new IllegalArgumentException(
                TACTICAL_MAP_FILE + " initialRange/minimumRange 必须为正数，且最小范围不能大于初始范围");
        }
        positiveOptionalInt(root, "tacticalMarkerDurationSeconds", 120, TACTICAL_MAP_FILE);
        positiveOptionalInt(root, "tacticalMarkerFadeSeconds", 120, TACTICAL_MAP_FILE);
        nonNegativeOptionalInt(root, "backgroundImageWidth", 0, TACTICAL_MAP_FILE);
        nonNegativeOptionalInt(root, "backgroundImageHeight", 0, TACTICAL_MAP_FILE);
        optionalBoolean(root, "showGrid", true, TACTICAL_MAP_FILE);
        optionalBoolean(root, "showLabels", true, TACTICAL_MAP_FILE);
    }

    private static void validateCapturePoints(JsonObject root) {
        JsonElement pointsElement = firstPresent(root, "plannedPoints", "points", "capturePoints");
        if (pointsElement == null || !pointsElement.isJsonArray()) {
            throw new IllegalArgumentException(
                CAPTURE_POINTS_FILE + " 必须包含 plannedPoints 数组");
        }

        JsonArray points = pointsElement.getAsJsonArray();
        int totalBatches = positiveOptionalInt(root, "totalBatches", 1, CAPTURE_POINTS_FILE);
        String endBehavior = optionalString(root, "endBehavior", "terminate")
            .toLowerCase(Locale.ROOT);
        if (!"terminate".equals(endBehavior) && !"loop".equals(endBehavior)) {
            throw new IllegalArgumentException(
                CAPTURE_POINTS_FILE + " endBehavior 只能是 terminate 或 loop");
        }

        JsonElement reinforcements = root.get("teamReinforcements");
        if (reinforcements == null || !reinforcements.isJsonObject()) {
            throw new IllegalArgumentException(
                CAPTURE_POINTS_FILE + " 必须包含 teamReinforcements 对象");
        }
        positiveTeamValue(reinforcements.getAsJsonObject(), "ATTACK");
        positiveTeamValue(reinforcements.getAsJsonObject(), "DEFEND");

        Set<String> names = new HashSet<>();
        Map<Integer, Integer> pointsPerBatch = new HashMap<>();
        int index = 0;
        for (JsonElement element : points) {
            index++;
            if (!element.isJsonObject()) {
                throw new IllegalArgumentException(
                    CAPTURE_POINTS_FILE + " 第 " + index + " 个据点必须是对象");
            }
            JsonObject point = element.getAsJsonObject();
            String name = optionalString(point, "name", "").trim().toUpperCase(Locale.ROOT);
            if (name.length() != 1 || name.charAt(0) < 'A' || name.charAt(0) > 'Z') {
                throw new IllegalArgumentException(
                    CAPTURE_POINTS_FILE + " 第 " + index + " 个据点名称必须是 A-Z");
            }
            if (!names.add(name)) {
                throw new IllegalArgumentException(
                    CAPTURE_POINTS_FILE + " 存在重复据点名称: " + name);
            }
            int batch = requiredInt(point, "batch", CAPTURE_POINTS_FILE);
            if (batch < 1 || batch > totalBatches) {
                throw new IllegalArgumentException(
                    CAPTURE_POINTS_FILE + " 据点 " + name + " 的批次超出 1-" + totalBatches);
            }
            int count = pointsPerBatch.merge(batch, 1, Integer::sum);
            if (count > MAX_POINTS_PER_BATCH) {
                throw new IllegalArgumentException(
                    CAPTURE_POINTS_FILE + " 批次 " + batch + " 的据点不能超过 "
                        + MAX_POINTS_PER_BATCH + " 个");
            }
            int[] pos1 = requirePosition(firstPresent(point, "pos1", "from"), name + ".pos1");
            int[] pos2 = requirePosition(firstPresent(point, "pos2", "to"), name + ".pos2");
            if (pos1[0] == pos2[0] && pos1[1] == pos2[1] && pos1[2] == pos2[2]) {
                throw new IllegalArgumentException(
                    CAPTURE_POINTS_FILE + " 据点 " + name + " 的两个角点不能相同");
            }
        }
    }

    private static byte[] readBackground(Path esConfigDir, String relativeName) throws IOException {
        if (relativeName.contains("..") || relativeName.contains(":")
            || relativeName.startsWith("/") || relativeName.startsWith("\\")
            || Path.of(relativeName).isAbsolute()) {
            throw new IOException("战术地图底图必须是 EsConfig 内的安全相对路径: " + relativeName);
        }
        if (!relativeName.toLowerCase(Locale.ROOT).endsWith(".png")) {
            throw new IOException("战术地图底图必须使用 PNG 格式: " + relativeName);
        }

        Path root = esConfigDir.toAbsolutePath().normalize().toRealPath();
        Path candidate = root.resolve(relativeName).normalize();
        if (!candidate.startsWith(root)
            || !Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("战术地图底图不存在或路径越界: " + relativeName);
        }
        Path real = candidate.toRealPath();
        if (!real.startsWith(root)) {
            throw new IOException("战术地图底图符号链接越界: " + relativeName);
        }
        long size = Files.size(real);
        if (size <= 0 || size > MAX_BACKGROUND_BYTES) {
            throw new IOException("战术地图底图大小必须在 1-" + MAX_BACKGROUND_BYTES + " 字节之间");
        }
        byte[] bytes = Files.readAllBytes(real);
        if (bytes.length < 8
            || bytes[0] != (byte) 0x89 || bytes[1] != 0x50 || bytes[2] != 0x4E
            || bytes[3] != 0x47 || bytes[4] != 0x0D || bytes[5] != 0x0A
            || bytes[6] != 0x1A || bytes[7] != 0x0A) {
            throw new IOException("战术地图底图不是有效的 PNG 文件: " + relativeName);
        }
        return bytes;
    }

    private static JsonObject requireObject(String json, String fileName) {
        try {
            JsonElement parsed = JsonParser.parseString(json);
            if (!parsed.isJsonObject()) {
                throw new IllegalArgumentException(fileName + " 根节点必须是对象");
            }
            return parsed.getAsJsonObject();
        } catch (RuntimeException e) {
            throw new IllegalArgumentException(fileName + " 无法解析: " + e.getMessage(), e);
        }
    }

    private static int[] requirePosition(@Nullable JsonElement value, String path) {
        if (value == null || value.isJsonNull()) {
            throw new IllegalArgumentException(CAPTURE_POINTS_FILE + " 缺少 " + path);
        }
        if (value.isJsonArray()) {
            JsonArray array = value.getAsJsonArray();
            if (array.size() != 3) {
                throw new IllegalArgumentException(CAPTURE_POINTS_FILE + " " + path + " 必须有三个坐标");
            }
            return new int[]{array.get(0).getAsInt(), array.get(1).getAsInt(), array.get(2).getAsInt()};
        }
        if (value.isJsonObject()) {
            JsonObject object = value.getAsJsonObject();
            return new int[]{
                requiredInt(object, "x", path),
                requiredInt(object, "y", path),
                requiredInt(object, "z", path)
            };
        }
        throw new IllegalArgumentException(CAPTURE_POINTS_FILE + " " + path + " 必须是坐标对象或数组");
    }

    private static void positiveTeamValue(JsonObject object, String team) {
        if (!object.has(team)
            || !object.get(team).isJsonPrimitive()
            || !object.getAsJsonPrimitive(team).isNumber()
            || object.get(team).getAsInt() <= 0) {
            throw new IllegalArgumentException(
                CAPTURE_POINTS_FILE + " teamReinforcements." + team + " 必须大于 0");
        }
    }

    private static int requiredInt(JsonObject object, String key, String source) {
        if (!object.has(key) || !object.get(key).isJsonPrimitive()
            || !object.getAsJsonPrimitive(key).isNumber()) {
            throw new IllegalArgumentException(source + " 缺少数值字段 " + key);
        }
        return object.get(key).getAsInt();
    }

    private static int positiveOptionalInt(JsonObject object, String key, int fallback, String source) {
        int value = object.has(key) ? requiredInt(object, key, source) : fallback;
        if (value <= 0) {
            throw new IllegalArgumentException(source + " " + key + " 必须大于 0");
        }
        return value;
    }

    private static int nonNegativeOptionalInt(JsonObject object, String key, int fallback, String source) {
        int value = object.has(key) ? requiredInt(object, key, source) : fallback;
        if (value < 0) {
            throw new IllegalArgumentException(source + " " + key + " 不能小于 0");
        }
        return value;
    }

    private static boolean optionalBoolean(JsonObject object, String key, boolean fallback, String source) {
        if (!object.has(key)) {
            return fallback;
        }
        if (!object.get(key).isJsonPrimitive()
            || !object.getAsJsonPrimitive(key).isBoolean()) {
            throw new IllegalArgumentException(source + " " + key + " 必须是布尔值");
        }
        return object.get(key).getAsBoolean();
    }

    private static String optionalString(JsonObject object, String key, String fallback) {
        if (!object.has(key) || object.get(key).isJsonNull()) {
            return fallback;
        }
        if (!object.get(key).isJsonPrimitive()
            || !object.getAsJsonPrimitive(key).isString()) {
            throw new IllegalArgumentException(key + " 必须是字符串");
        }
        return object.get(key).getAsString();
    }

    @Nullable
    private static JsonElement firstPresent(JsonObject object, String... keys) {
        for (String key : keys) {
            if (object.has(key)) {
                return object.get(key);
            }
        }
        return null;
    }
}
