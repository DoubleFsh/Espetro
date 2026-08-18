package org.espetro.mapconfig;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Frozen objective layout from CapturePoints.json.
 *
 * <p>The legacy plannedPoints array remains the AAS route. RAAS adds a point
 * pool and named lanes; a lane contains one or more point choices per stage.
 * At round start the layout is reduced to the legacy ESPoints format.</p>
 */
final class ObjectiveLayout {

    private static final Gson GSON = new Gson();
    private static final int MIN_RAAS_STAGES = 3;
    private static final int MAX_RAAS_STAGES = 26;
    private static final Pattern ID_PATTERN = Pattern.compile("[a-z0-9_.-]{1,64}");

    enum Mode {
        AAS,
        RAAS,
        RANDOM
    }

    record Selection(String mode, String laneId, long seed, String capturePointsJson) {
    }

    private record Lane(String id, List<List<String>> stages) {
    }

    private final JsonObject source;
    private final Mode mode;
    private final Map<String, JsonObject> points;
    private final List<Lane> lanes;

    private ObjectiveLayout(JsonObject source, Mode mode,
                            Map<String, JsonObject> points, List<Lane> lanes) {
        this.source = source.deepCopy();
        this.mode = mode;
        this.points = Map.copyOf(points);
        this.lanes = List.copyOf(lanes);
    }

    static ObjectiveLayout parse(JsonObject source) {
        if (source == null) {
            throw new IllegalArgumentException("CapturePoints.json 根节点不能为空");
        }

        Mode mode = readMode(source);
        if (mode != Mode.RAAS) {
            validateAas(source);
        }

        Map<String, JsonObject> points = new LinkedHashMap<>();
        List<Lane> lanes = new ArrayList<>();
        if (mode != Mode.AAS) {
            JsonObject raas = requireObject(source, "raas");
            readPointPool(raas, points);
            readLanes(raas, points.keySet(), lanes);
        }
        return new ObjectiveLayout(source, mode, points, lanes);
    }

    Selection select(long seed) {
        Random random = new Random(seed);
        Mode selectedMode = mode == Mode.RANDOM
            ? (random.nextBoolean() ? Mode.RAAS : Mode.AAS)
            : mode;

        if (selectedMode == Mode.AAS) {
            JsonObject round = source.deepCopy();
            stripRouteConfig(round);
            return new Selection("AAS", "", seed, GSON.toJson(round));
        }

        Lane lane = lanes.get(random.nextInt(lanes.size()));
        JsonArray planned = new JsonArray();
        int batch = 1;
        for (List<String> stage : lane.stages) {
            String pointId = stage.get(random.nextInt(stage.size()));
            JsonObject point = points.get(pointId).deepCopy();
            point.remove("id");
            point.addProperty("name", String.valueOf((char) ('A' + batch - 1)));
            point.addProperty("batch", batch++);
            planned.add(point);
        }

        JsonObject round = source.deepCopy();
        stripRouteConfig(round);
        round.addProperty("totalBatches", planned.size());
        round.add("plannedPoints", planned);
        return new Selection("RAAS", lane.id, seed, GSON.toJson(round));
    }

    private static Mode readMode(JsonObject source) {
        if (!source.has("objectiveMode")) {
            return Mode.AAS;
        }
        try {
            return Mode.valueOf(source.get("objectiveMode").getAsString()
                .trim().toUpperCase(Locale.ROOT));
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException(
                "objectiveMode 只能是 AAS、RAAS 或 RANDOM", exception);
        }
    }

    private static void validateAas(JsonObject source) {
        JsonArray planned = requireArray(source, "plannedPoints");
        if (planned.isEmpty()) {
            throw new IllegalArgumentException("plannedPoints 不能为空");
        }
        Set<String> names = new LinkedHashSet<>();
        for (int i = 0; i < planned.size(); i++) {
            JsonObject point = requireObject(planned.get(i), "plannedPoints[" + i + "]");
            String name = requireString(point, "name", "plannedPoints[" + i + "]");
            if (!names.add(name)) {
                throw new IllegalArgumentException("CapturePoints.json 包含重复据点名称: " + name);
            }
            validateArea(point, "plannedPoints[" + i + "]");
        }
    }

    private static void readPointPool(JsonObject raas, Map<String, JsonObject> points) {
        JsonArray array = requireArray(raas, "points");
        for (int i = 0; i < array.size(); i++) {
            JsonObject point = requireObject(array.get(i), "raas.points[" + i + "]");
            String id = requireString(point, "id", "raas.points[" + i + "]");
            if (!ID_PATTERN.matcher(id).matches()) {
                throw new IllegalArgumentException("非法 RAAS 据点 id: " + id);
            }
            if (points.putIfAbsent(id, point.deepCopy()) != null) {
                throw new IllegalArgumentException("重复 RAAS 据点 id: " + id);
            }
            validateArea(point, "raas.points[" + i + "]");
        }
        if (points.isEmpty()) {
            throw new IllegalArgumentException("raas.points 不能为空");
        }
    }

    private static void readLanes(JsonObject raas, Set<String> pointIds, List<Lane> lanes) {
        JsonArray array = requireArray(raas, "lanes");
        Set<String> laneIds = new LinkedHashSet<>();
        for (int i = 0; i < array.size(); i++) {
            JsonObject object = requireObject(array.get(i), "raas.lanes[" + i + "]");
            String id = requireString(object, "id", "raas.lanes[" + i + "]");
            if (!ID_PATTERN.matcher(id).matches()) {
                throw new IllegalArgumentException("非法 RAAS 路线 id: " + id);
            }
            if (!laneIds.add(id)) {
                throw new IllegalArgumentException("重复 RAAS 路线 id: " + id);
            }

            JsonArray stageArray = requireArray(object, "stages");
            if (stageArray.size() < MIN_RAAS_STAGES || stageArray.size() > MAX_RAAS_STAGES) {
                throw new IllegalArgumentException("RAAS 路线 " + id + " 必须包含 "
                    + MIN_RAAS_STAGES + " 到 " + MAX_RAAS_STAGES + " 个阶段");
            }

            List<List<String>> stages = new ArrayList<>();
            Set<String> used = new LinkedHashSet<>();
            for (int stageIndex = 0; stageIndex < stageArray.size(); stageIndex++) {
                JsonElement stageElement = stageArray.get(stageIndex);
                if (!stageElement.isJsonArray() || stageElement.getAsJsonArray().isEmpty()) {
                    throw new IllegalArgumentException("RAAS 路线 " + id + " 的阶段 "
                        + (stageIndex + 1) + " 不能为空");
                }
                List<String> choices = new ArrayList<>();
                for (JsonElement choice : stageElement.getAsJsonArray()) {
                    String pointId = choice.getAsString();
                    if (!pointIds.contains(pointId)) {
                        throw new IllegalArgumentException("RAAS 路线 " + id
                            + " 引用了不存在的据点: " + pointId);
                    }
                    if (!used.add(pointId)) {
                        throw new IllegalArgumentException("RAAS 路线 " + id
                            + " 重复引用据点: " + pointId);
                    }
                    choices.add(pointId);
                }
                stages.add(List.copyOf(choices));
            }
            lanes.add(new Lane(id, List.copyOf(stages)));
        }
        if (lanes.isEmpty()) {
            throw new IllegalArgumentException("raas.lanes 不能为空");
        }
    }

    private static void validateArea(JsonObject point, String path) {
        validatePosition(point.get("pos1"), path + ".pos1");
        validatePosition(point.get("pos2"), path + ".pos2");
    }

    private static void validatePosition(JsonElement element, String path) {
        if (element != null && element.isJsonArray()) {
            JsonArray position = element.getAsJsonArray();
            if (position.size() != 3) {
                throw new IllegalArgumentException(path + " 必须包含 x、y、z 三个坐标");
            }
            for (JsonElement coordinate : position) {
                if (!coordinate.isJsonPrimitive()
                    || !coordinate.getAsJsonPrimitive().isNumber()) {
                    throw new IllegalArgumentException(path + " 坐标必须是数字");
                }
            }
            return;
        }
        if (element == null || !element.isJsonObject()) {
            throw new IllegalArgumentException(path + " 必须是坐标对象或 [x,y,z] 数组");
        }
        JsonObject position = element.getAsJsonObject();
        for (String axis : List.of("x", "y", "z")) {
            if (!position.has(axis) || !position.get(axis).isJsonPrimitive()
                || !position.getAsJsonPrimitive(axis).isNumber()) {
                throw new IllegalArgumentException(path + "." + axis + " 必须是数字");
            }
        }
    }

    private static JsonObject requireObject(JsonObject parent, String key) {
        if (!parent.has(key) || !parent.get(key).isJsonObject()) {
            throw new IllegalArgumentException(key + " 必须是对象");
        }
        return parent.getAsJsonObject(key);
    }

    private static JsonObject requireObject(JsonElement element, String path) {
        if (element == null || !element.isJsonObject()) {
            throw new IllegalArgumentException(path + " 必须是对象");
        }
        return element.getAsJsonObject();
    }

    private static JsonArray requireArray(JsonObject parent, String key) {
        if (!parent.has(key) || !parent.get(key).isJsonArray()) {
            throw new IllegalArgumentException(key + " 必须是数组");
        }
        return parent.getAsJsonArray(key);
    }

    private static String requireString(JsonObject object, String key, String path) {
        if (!object.has(key) || !object.get(key).isJsonPrimitive()
            || !object.getAsJsonPrimitive(key).isString()) {
            throw new IllegalArgumentException(path + "." + key + " 必须是字符串");
        }
        String value = object.get(key).getAsString().trim();
        if (value.isEmpty()) {
            throw new IllegalArgumentException(path + "." + key + " 不能为空");
        }
        return value;
    }

    private static void stripRouteConfig(JsonObject root) {
        root.remove("objectiveMode");
        root.remove("raas");
    }
}
