package org.espetro.client.gui;

import org.espetro.team.GamePhase;

/**
 * 客户端游戏状态
 * 保存从服务端同步的游戏阶段和玩家信息
 */
public class ClientGameState {

    private static GamePhase currentPhase = GamePhase.WAITING_FOR_PLAYERS;
    // 玩家当前选择的编制/阵营ID（用于职业选择）
    private static String playerFactionId = null;
    // 玩家当前选择的攻守方：ATTACK 或 DEFEND
    private static String playerTeam = null;

    public static void setCurrentPhase(GamePhase phase) {
        currentPhase = phase;
    }

    public static GamePhase getCurrentPhase() {
        return currentPhase;
    }

    public static void setPlayerFactionId(String factionId) {
        playerFactionId = factionId;
    }

    public static String getPlayerFactionId() {
        return playerFactionId;
    }

    public static void setPlayerTeam(String team) {
        playerTeam = team;
    }

    public static String getPlayerTeam() {
        return playerTeam;
    }

    /**
     * 检查是否允许打开阵营选择界面（K键）
     * 在等待阶段或战局中加入时允许
     */
    public static boolean canOpenTeamSelection() {
        return currentPhase == GamePhase.WAITING_FOR_PLAYERS
            || currentPhase == GamePhase.DEPLOYING
            || currentPhase == GamePhase.BATTLE;
    }

    /**
     * 检查是否允许打开职业选择界面（J键）
     * 在部署阶段和战斗阶段允许
     */
    public static boolean canOpenClassSelection() {
        return currentPhase == GamePhase.DEPLOYING || currentPhase == GamePhase.BATTLE;
    }
}
