package org.espetro.api;

import net.minecraft.server.level.ServerPlayer;
import org.espetro.Espetro;
import org.espetro.team.SquadManager;
import org.espetro.team.VoteManager;

import java.util.UUID;

/**
 * Espetro 对外提供的 API
 * 供其他模组查询玩家的阵营、小队、指挥官信息
 */
public class EspetroAPI {

    /**
     * 获取玩家所在阵营（ATTACK 或 DEFEND）
     */
    public static String getPlayerTeam(ServerPlayer player) {
        return Espetro.getPlayerTeam(player);
    }

    /**
     * 获取玩家所在小队ID
     */
    public static int getPlayerSquadId(UUID playerId) {
        return SquadManager.getInstance().getPlayerSquadId(playerId);
    }

    /**
     * 检查玩家是否是小队队长
     */
    public static boolean isSquadLeader(UUID playerId) {
        String team = getPlayerTeamById(playerId);
        if (team == null) return false;

        int squadId = getPlayerSquadId(playerId);
        if (squadId == -1) return false;

        for (SquadManager.SquadSnapshot squad : SquadManager.getInstance().getSquadSnapshots(team)) {
            if (squad.id == squadId) {
                return squad.members.stream().anyMatch(m -> m.uuid.equals(playerId) && m.leader);
            }
        }
        return false;
    }

    /**
     * 检查玩家是否是指挥官
     */
    public static boolean isCommander(UUID playerId) {
        return VoteManager.getInstance().isCommander(playerId);
    }

    private static String getPlayerTeamById(UUID playerId) {
        var server = Espetro.getServer();
        if (server == null) return null;
        var player = server.getPlayerList().getPlayer(playerId);
        return player != null ? Espetro.getPlayerTeam(player) : null;
    }
}
