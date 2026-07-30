package org.espetro.team;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import org.espetro.network.EquipZoneSyncPacket;

import java.util.ArrayList;
import java.util.List;

/**
 * 职业换装合法区域：本方原部署点，半径与判定共用。
 * <p>
 * 等待选择部署点由服务端状态单独放行；Radio 轮盘由点击位置单独校验。
 */
public final class ClassEquipmentZones {

    /** 与 {@code BlockPos.closerThan(center, RANGE)} 一致。 */
    public static final double RANGE = 6.0;

    private ClassEquipmentZones() {
    }

    public static boolean isPlayerNearOriginalSpawn(ServerPlayer player) {
        if (player == null) {
            return false;
        }
        BlockPos playerPos = player.blockPosition();
        ClassCountManager counts = ClassCountManager.getInstance();
        String team = counts.getEffectivePlayerTeam(player.getUUID());
        if (team != null) {
            SpawnPointConfig.SpawnPoint spawn = SpawnPointConfig.getSpawnPoint(team);
            if (spawn != null) {
                BlockPos teamSpawn = new BlockPos((int) spawn.x, (int) spawn.y, (int) spawn.z);
                return playerPos.closerThan(teamSpawn, RANGE);
            }
        }
        return false;
    }

    /** 为本队玩家构建客户端黄框同步列表（不含敌方）。 */
    public static List<EquipZoneSyncPacket.Zone> collectForPlayer(ServerPlayer player) {
        List<EquipZoneSyncPacket.Zone> zones = new ArrayList<>();
        if (player == null) {
            return zones;
        }
        String team = ClassCountManager.getInstance().getEffectivePlayerTeam(player.getUUID());
        if (team == null) {
            return zones;
        }

        SpawnPointConfig.SpawnPoint spawn = SpawnPointConfig.getSpawnPoint(team);
        if (spawn != null) {
            BlockPos teamSpawn = new BlockPos((int) spawn.x, (int) spawn.y, (int) spawn.z);
            zones.add(zone("spawn", teamSpawn));
        }
        return zones;
    }

    private static EquipZoneSyncPacket.Zone zone(String type, BlockPos pos) {
        return new EquipZoneSyncPacket.Zone(
            type, pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, RANGE);
    }
}
