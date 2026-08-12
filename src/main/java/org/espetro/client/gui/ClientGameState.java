package org.espetro.client.gui;

import org.espetro.team.GamePhase;

/**
 * 客户端游戏状态
 * 保存从服务端同步的游戏阶段和玩家信息
 */
public class ClientGameState {

    private static GamePhase currentPhase = GamePhase.LOBBY;
    // 玩家当前选择的编制/阵营ID（用于职业选择）
    private static String playerFactionId = null;
    // 玩家当前选择的攻守方：ATTACK 或 DEFEND
    private static String playerTeam = null;
    // 战局倒计时剩余秒数，-1 表示无倒计时
    private static int battleTimerAnchorSeconds = -1;
    private static long battleTimerAnchorMs;
    // 服务端当前选中/正在运行的地图目录名，用于读取客户端 EsWorld/<name>.png。
    private static String currentMapFolder = null;

    public static void setCurrentPhase(GamePhase phase) {
        currentPhase = phase;
    }

    public static GamePhase getCurrentPhase() {
        return currentPhase;
    }

    public static void setCurrentMapFolder(String mapFolder) {
        currentMapFolder = mapFolder == null || mapFolder.isBlank() ? null : mapFolder.trim();
    }

    public static String getCurrentMapFolder() {
        return currentMapFolder;
    }

    public static void setPlayerFactionId(String factionId) {
        playerFactionId = factionId;
    }

    public static String getPlayerFactionId() {
        return playerFactionId;
    }

    public static void setPlayerTeam(String team) {
        playerTeam = team;
        // 事件驱动：阵营变化时刷新兵力 HUD 缓存（不查服、不扫记分板）
        TroopCountOverlay.onTeamChanged(team);
    }

    public static String getPlayerTeam() {
        return playerTeam;
    }

    public static void setBattleTimeRemaining(int seconds) {
        battleTimerAnchorSeconds = seconds < 0 ? -1 : seconds;
        battleTimerAnchorMs = System.currentTimeMillis();
    }

    public static int getBattleTimeRemaining() {
        return calculateAnchoredRemaining(
            battleTimerAnchorSeconds, battleTimerAnchorMs, System.currentTimeMillis());
    }

    static int calculateAnchoredRemaining(int anchorSeconds, long anchorMs, long nowMs) {
        if (anchorSeconds < 0) return -1;
        long elapsedMs = Math.max(0L, nowMs - anchorMs);
        long elapsedSeconds = elapsedMs / 1000L;
        return (int) Math.max(0L, anchorSeconds - elapsedSeconds);
    }

    /**
     * 检查是否允许打开阵营选择界面（K键）
     * 在等待阶段或战局中加入时允许
     */
    public static boolean canOpenTeamSelection() {
        return currentPhase == GamePhase.TEAM_SELECT
            || currentPhase == GamePhase.DEPLOYING
            || currentPhase == GamePhase.BATTLE
            || currentPhase.isCommanderVotePhase()
            || currentPhase.isFactionSelectPhase()
            || currentPhase == GamePhase.FACTION_REVEAL;
    }

    /**
     * 检查是否允许打开职业选择界面（J键）
     * 在部署阶段和战斗阶段允许
     */
    public static boolean canOpenClassSelection() {
        return currentPhase == GamePhase.DEPLOYING || currentPhase == GamePhase.BATTLE;
    }

    /**
     * 检查是否允许打开指挥官技能面板（Y键）
     * 在部署阶段和战斗阶段允许
     */
    public static boolean canOpenCommanderSkill() {
        return currentPhase == GamePhase.DEPLOYING || currentPhase == GamePhase.BATTLE;
    }

    /**
     * J键统一入口：主城/等待阶段打开组队面板，对战中请求职业选择。
     */
    public static void tryOpenJKeyScreen() {
        if (canOpenClassSelection()) {
            String playerTeam = getPlayerTeam();
            if (playerTeam == null) {
                org.espetro.network.NetworkManager.requestGameState();
            } else {
                String factionId = getPlayerFactionId();
                org.espetro.network.NetworkManager.requestClassSelection(factionId);
            }
        } else if (currentPhase.isLobbyLike()) {
            org.espetro.network.NetworkManager.requestPartyList();
            net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
            if (mc.player != null && !(mc.screen instanceof PartyScreen)) {
                mc.setScreen(new PartyScreen());
            }
        }
    }
}
