package org.espetro.mapconfig;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Immutable VehSpawn.json snapshot for one map.
 */
public final class VehSpawnSnapshot {

    public final List<String> vehicleTypes;
    /** type -> ordered spawn points */
    public final Map<String, List<SpawnPoint>> spawnPointsByType;
    public final List<String> errors;

    public record Pose(double x, double y, double z, float yaw) {
    }

    public record SpawnPoint(String id, Pose attack, Pose defend) {
    }

    public VehSpawnSnapshot(List<String> vehicleTypes,
                            Map<String, List<SpawnPoint>> spawnPointsByType,
                            List<String> errors) {
        this.vehicleTypes = List.copyOf(vehicleTypes);
        Map<String, List<SpawnPoint>> copy = new LinkedHashMap<>();
        for (Map.Entry<String, List<SpawnPoint>> e : spawnPointsByType.entrySet()) {
            copy.put(e.getKey(), List.copyOf(e.getValue()));
        }
        this.spawnPointsByType = Collections.unmodifiableMap(copy);
        this.errors = List.copyOf(errors);
    }

    public boolean isValid() {
        return errors.isEmpty() && !vehicleTypes.isEmpty();
    }

    public int pointCount(String type) {
        List<SpawnPoint> list = spawnPointsByType.get(type);
        return list == null ? 0 : list.size();
    }

    public static VehSpawnSnapshot parse(JsonObject root) {
        List<String> errors = new ArrayList<>();
        List<String> types = new ArrayList<>();
        Map<String, List<SpawnPoint>> points = new LinkedHashMap<>();

        int aliasCount = 0;
        JsonElement typesEl = null;
        if (root.has("VehTypes")) {
            aliasCount++;
            typesEl = root.get("VehTypes");
        }
        if (root.has("vehtypes")) {
            aliasCount++;
            typesEl = root.get("vehtypes");
        }
        if (root.has("vehicle_types")) {
            aliasCount++;
            typesEl = root.get("vehicle_types");
        }
        if (aliasCount > 1) {
            errors.add("VehSpawn.json 同时出现多个类型别名字段 (VehTypes/vehtypes/vehicle_types)");
            return new VehSpawnSnapshot(types, points, errors);
        }
        if (typesEl == null) {
            errors.add("VehSpawn.json 缺少 VehTypes 数组");
            return new VehSpawnSnapshot(types, points, errors);
        }
        if (!typesEl.isJsonArray()) {
            // legacy object form: treat keys as types if present under spawn_points only
            errors.add("VehTypes 必须是 JSON 数组");
            return new VehSpawnSnapshot(types, points, errors);
        }
        Set<String> seen = new LinkedHashSet<>();
        for (JsonElement el : typesEl.getAsJsonArray()) {
            if (!el.isJsonPrimitive()) {
                errors.add("VehTypes 含有非字符串元素");
                continue;
            }
            String t = el.getAsString().trim().toLowerCase(Locale.ROOT);
            if (t.isEmpty()) {
                errors.add("VehTypes 含有空类型名");
                continue;
            }
            if (!seen.add(t)) {
                errors.add("重复的载具类型: " + t);
                continue;
            }
            types.add(t);
        }

        if (!root.has("spawn_points") || !root.get("spawn_points").isJsonObject()) {
            errors.add("VehSpawn.json 缺少 spawn_points 对象");
            return new VehSpawnSnapshot(types, points, errors);
        }
        JsonObject spawnRoot = root.getAsJsonObject("spawn_points");
        for (String type : types) {
            if (!spawnRoot.has(type)) {
                errors.add("spawn_points 缺少类型: " + type);
                continue;
            }
            JsonElement typeEl = spawnRoot.get(type);
            List<SpawnPoint> list = new ArrayList<>();
            if (typeEl.isJsonArray()) {
                int idx = 0;
                for (JsonElement pointEl : typeEl.getAsJsonArray()) {
                    idx++;
                    if (!pointEl.isJsonObject()) {
                        errors.add(type + " 的第 " + idx + " 个出生点不是对象");
                        continue;
                    }
                    Optional<SpawnPoint> parsed = parsePoint(type, pointEl.getAsJsonObject(), idx, errors);
                    parsed.ifPresent(list::add);
                }
            } else if (typeEl.isJsonObject()) {
                // legacy object map: preserve insertion order of keys
                int idx = 0;
                for (Map.Entry<String, JsonElement> entry : typeEl.getAsJsonObject().entrySet()) {
                    idx++;
                    if (!entry.getValue().isJsonObject()) {
                        errors.add(type + "." + entry.getKey() + " 不是对象");
                        continue;
                    }
                    JsonObject obj = entry.getValue().getAsJsonObject();
                    if (!obj.has("id")) {
                        obj.addProperty("id", entry.getKey());
                    }
                    Optional<SpawnPoint> parsed = parsePoint(type, obj, idx, errors);
                    parsed.ifPresent(list::add);
                }
            } else {
                errors.add("spawn_points." + type + " 必须是数组或对象");
            }
            points.put(type, list);
        }
        return new VehSpawnSnapshot(types, points, errors);
    }

    private static Optional<SpawnPoint> parsePoint(String type, JsonObject obj, int idx, List<String> errors) {
        String id = obj.has("id") && obj.get("id").isJsonPrimitive()
            ? obj.get("id").getAsString()
            : type + "_" + idx;
        Optional<Pose> attack = parsePose(obj, "attack");
        Optional<Pose> defend = parsePose(obj, "defend");
        if (attack.isEmpty()) {
            errors.add(type + "/" + id + " 缺少有效 attack 坐标");
        }
        if (defend.isEmpty()) {
            errors.add(type + "/" + id + " 缺少有效 defend 坐标");
        }
        if (attack.isEmpty() || defend.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new SpawnPoint(id, attack.get(), defend.get()));
    }

    private static Optional<Pose> parsePose(JsonObject parent, String key) {
        if (!parent.has(key) || !parent.get(key).isJsonObject()) {
            return Optional.empty();
        }
        JsonObject p = parent.getAsJsonObject(key);
        if (!p.has("x") || !p.has("y") || !p.has("z")) {
            return Optional.empty();
        }
        try {
            double x = p.get("x").getAsDouble();
            double y = p.get("y").getAsDouble();
            double z = p.get("z").getAsDouble();
            float yaw = p.has("yaw") ? p.get("yaw").getAsFloat() : 0f;
            return Optional.of(new Pose(x, y, z, yaw));
        } catch (Exception e) {
            return Optional.empty();
        }
    }
}
