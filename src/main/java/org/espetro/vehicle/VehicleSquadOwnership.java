package org.espetro.vehicle;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.Entity;

/**
 * 载具小队归属管理，使用实体持久数据标签存储。
 */
public final class VehicleSquadOwnership {

    private static final String SQUAD_ID_KEY = "espetro_vehicle_squad_id";
    private static final String SQUAD_TEAM_KEY = "espetro_vehicle_squad_team";

    private VehicleSquadOwnership() {}

    /** 返回载具归属的小队 ID，无归属时返回 -1。 */
    public static int getSquadId(Entity vehicle) {
        CompoundTag data = vehicle.getPersistentData();
        if (data.contains(SQUAD_ID_KEY, CompoundTag.TAG_INT)) {
            return data.getInt(SQUAD_ID_KEY);
        }
        return -1;
    }

    /** 返回载具归属的小队阵营（ATTACK / DEFEND），无归属时返回 null。 */
    public static String getSquadTeam(Entity vehicle) {
        CompoundTag data = vehicle.getPersistentData();
        if (data.contains(SQUAD_TEAM_KEY, CompoundTag.TAG_STRING)) {
            return data.getString(SQUAD_TEAM_KEY);
        }
        return null;
    }

    /** 设置载具归属于指定小队。 */
    public static void setOwner(Entity vehicle, int squadId, String team) {
        vehicle.getPersistentData().putInt(SQUAD_ID_KEY, squadId);
        vehicle.getPersistentData().putString(SQUAD_TEAM_KEY, team);
    }

    /** 清除载具的小队归属。 */
    public static void clearOwner(Entity vehicle) {
        vehicle.getPersistentData().remove(SQUAD_ID_KEY);
        vehicle.getPersistentData().remove(SQUAD_TEAM_KEY);
    }

    /** 返回载具当前是否有小队归属。 */
    public static boolean isOwned(Entity vehicle) {
        return getSquadId(vehicle) != -1;
    }
}
