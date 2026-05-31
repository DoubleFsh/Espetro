package org.espetro.team;

/**
 * 游戏阶段枚举
 * 按流程图顺序：等待→守方指挥官投票→攻方指挥官投票→守方编制选择→攻方编制选择→部署→对战
 */
public enum GamePhase {
    WAITING_FOR_PLAYERS("等待玩家集结"),
    DEFEND_COMMANDER_VOTE("守方指挥官投票"),
    ATTACK_COMMANDER_VOTE("攻方指挥官投票"),
    DEFEND_FACTION_SELECT("守方编制选择"),
    ATTACK_FACTION_SELECT("攻方编制选择"),
    DEPLOYING("部署阶段"),
    BATTLE("对战开始");

    private final String displayName;

    GamePhase(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }

    /**
     * 是否属于指挥官投票阶段
     */
    public boolean isCommanderVotePhase() {
        return this == DEFEND_COMMANDER_VOTE || this == ATTACK_COMMANDER_VOTE;
    }

    /**
     * 是否属于编制选择阶段
     */
    public boolean isFactionSelectPhase() {
        return this == DEFEND_FACTION_SELECT || this == ATTACK_FACTION_SELECT;
    }

    /**
     * 获取当前阶段对应的队伍（用于投票/编制选择）
     */
    public String getActiveTeam() {
        return switch (this) {
            case DEFEND_COMMANDER_VOTE, DEFEND_FACTION_SELECT -> "DEFEND";
            case ATTACK_COMMANDER_VOTE, ATTACK_FACTION_SELECT -> "ATTACK";
            default -> null;
        };
    }
}
