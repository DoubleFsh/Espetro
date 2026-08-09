package org.espetro.team;

/**
 * 游戏阶段枚举（多维度战局流程）。
 *
 * LOBBY → MAP_VOTE → MAP_LOADING → TEAM_SELECT
 * → ATTACK_COMMANDER_VOTE → DEFEND_COMMANDER_VOTE
 * → ATTACK_FACTION_SELECT → DEFEND_FACTION_SELECT
 * → FACTION_REVEAL → DEPLOYING → BATTLE
 * → ROUND_END → CLEANUP → LOBBY
 */
public enum GamePhase {
    /** @deprecated use LOBBY */
    @Deprecated
    WAITING_FOR_PLAYERS("等待玩家集结"),
    LOBBY("主城等待"),
    MAP_VOTE("地图投票"),
    MAP_LOADING("地图加载"),
    TEAM_SELECT("攻守方选择"),
    DEFEND_COMMANDER_VOTE("守方指挥官投票"),
    ATTACK_COMMANDER_VOTE("攻方指挥官投票"),
    DEFEND_FACTION_SELECT("守方编制选择"),
    ATTACK_FACTION_SELECT("攻方编制选择"),
    FACTION_REVEAL("双方编制揭示"),
    DEPLOYING("部署阶段"),
    BATTLE("对战开始"),
    ROUND_END("回合结算"),
    CLEANUP("战场清理");

    private final String displayName;

    GamePhase(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }

    public boolean isCommanderVotePhase() {
        return this == DEFEND_COMMANDER_VOTE || this == ATTACK_COMMANDER_VOTE;
    }

    public boolean isFactionSelectPhase() {
        return this == DEFEND_FACTION_SELECT || this == ATTACK_FACTION_SELECT;
    }

    public boolean isLobbyLike() {
        return this == LOBBY || this == WAITING_FOR_PLAYERS;
    }

    public boolean isMatchActive() {
        return this != LOBBY && this != WAITING_FOR_PLAYERS && this != CLEANUP;
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
