package org.espetro.api;

import net.minecraft.server.level.ServerPlayer;
import org.espetro.Espetro;
import org.espetro.team.CommanderSkillManager;
import org.espetro.team.SquadManager;
import org.espetro.team.VoteManager;

import java.util.List;
import java.util.Map;
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

    /**
     * ESPoints 在服务端将战术地图选点转换为世界坐标后调用。
     * Espetro 会重新校验指挥官权限、游戏阶段和技能冷却。
     */
    public static boolean submitArtillerySupportTarget(ServerPlayer commander, double x, double z) {
        return CommanderSkillManager.getInstance().submitArtillerySupportTarget(commander, x, z);
    }

    /**
     * ESPoints 在服务端将战术地图选点转换为世界坐标后调用。
     * 这是通用指挥官技能选点入口；旧的 submitArtillerySupportTarget 保留为兼容别名。
     */
    public static boolean submitCommanderSkillTarget(ServerPlayer commander, double x, double z) {
        return CommanderSkillManager.getInstance().submitArtillerySupportTarget(commander, x, z);
    }

    /**
     * 获取最近一次155火炮支援请求；不会从队列中移除。
     */
    public static CommanderSkillManager.ArtillerySupportRequest getLatestArtillerySupportRequest() {
        return CommanderSkillManager.getInstance().getLatestArtillerySupportRequest();
    }

    /**
     * 获取最近一次指挥官技能地图选点请求；不会从队列中移除。
     */
    public static CommanderSkillManager.ArtillerySupportRequest getLatestCommanderSkillTargetRequest() {
        return CommanderSkillManager.getInstance().getLatestCommanderSkillTargetRequest();
    }

    /**
     * 获取当前缓存的155火炮支援请求快照；不会从队列中移除。
     */
    public static List<CommanderSkillManager.ArtillerySupportRequest> getArtillerySupportRequests() {
        return CommanderSkillManager.getInstance().getArtillerySupportRequestsSnapshot();
    }

    /**
     * 获取当前缓存的指挥官技能地图选点请求快照；不会从队列中移除。
     */
    public static List<CommanderSkillManager.ArtillerySupportRequest> getCommanderSkillTargetRequests() {
        return CommanderSkillManager.getInstance().getCommanderSkillTargetRequestsSnapshot();
    }

    /**
     * 获取并清空155火炮支援请求兼容队列，可供 KubeJS 或其他服务端逻辑联动。
     */
    public static List<CommanderSkillManager.ArtillerySupportRequest> drainArtillerySupportRequests() {
        return CommanderSkillManager.getInstance().drainArtillerySupportRequests();
    }

    /**
     * 获取并清空指挥官技能地图选点请求兼容队列。
     */
    public static List<CommanderSkillManager.ArtillerySupportRequest> drainCommanderSkillTargetRequests() {
        return CommanderSkillManager.getInstance().drainCommanderSkillTargetRequests();
    }

    public static int getCommanderSkillCooldown(ServerPlayer commander, String skillId) {
        return commander == null ? 0
            : CommanderSkillManager.getInstance().getRemainingCooldownSeconds(commander.getUUID(), skillId);
    }

    public static Map<String, Integer> getCommanderSkillCooldowns(ServerPlayer commander) {
        return commander == null ? Map.of()
            : CommanderSkillManager.getInstance().getCooldownData(commander.getUUID());
    }

    public static boolean isCommanderSkillOnCooldown(ServerPlayer commander, String skillId) {
        return commander != null && CommanderSkillManager.getInstance().isOnCooldown(commander.getUUID(), skillId);
    }

    public static CommanderSkillManager.SkillStatus getCommanderSkillStatus(ServerPlayer commander, String skillId) {
        return CommanderSkillManager.getInstance().getSkillStatus(commander, skillId);
    }

    public static boolean canUseCommanderSkill(ServerPlayer commander, String skillId) {
        return getCommanderSkillStatus(commander, skillId).canUse();
    }

    private static String getPlayerTeamById(UUID playerId) {
        var server = Espetro.getServer();
        if (server == null) return null;
        var player = server.getPlayerList().getPlayer(playerId);
        return player != null ? Espetro.getPlayerTeam(player) : null;
    }
}
