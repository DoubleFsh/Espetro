package org.espetro.mapconfig;

import com.google.gson.JsonObject;

/**
 * Player spawn points for ATTACK / DEFEND on one map.
 */
public final class SpawnPointsSnapshot {

    public record Point(double x, double y, double z, float yaw) {
    }

    public final Point attack;
    public final Point defend;
    public final boolean valid;
    public final String error;

    public SpawnPointsSnapshot(Point attack, Point defend, boolean valid, String error) {
        this.attack = attack;
        this.defend = defend;
        this.valid = valid;
        this.error = error;
    }

    public Point forTeam(String team) {
        return "ATTACK".equalsIgnoreCase(team) ? attack : defend;
    }

    public static SpawnPointsSnapshot parse(JsonObject root) {
        if (!root.has("spawnPoints") || !root.get("spawnPoints").isJsonObject()) {
            return new SpawnPointsSnapshot(null, null, false, "spawn_points.json 缺少 spawnPoints");
        }
        JsonObject sp = root.getAsJsonObject("spawnPoints");
        Point attack = parsePoint(sp, "ATTACK");
        Point defend = parsePoint(sp, "DEFEND");
        if (attack == null || defend == null) {
            return new SpawnPointsSnapshot(attack, defend, false, "spawn_points.json 缺少 ATTACK 或 DEFEND");
        }
        return new SpawnPointsSnapshot(attack, defend, true, null);
    }

    private static Point parsePoint(JsonObject parent, String team) {
        if (!parent.has(team) || !parent.get(team).isJsonObject()) {
            return null;
        }
        JsonObject p = parent.getAsJsonObject(team);
        try {
            return new Point(
                p.get("x").getAsDouble(),
                p.get("y").getAsDouble(),
                p.get("z").getAsDouble(),
                p.has("yaw") ? p.get("yaw").getAsFloat() : ("ATTACK".equals(team) ? 0f : 180f)
            );
        } catch (Exception e) {
            return null;
        }
    }
}
