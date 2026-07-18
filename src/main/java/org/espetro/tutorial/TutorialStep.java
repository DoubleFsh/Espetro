package org.espetro.tutorial;

import org.espetro.team.GamePhase;

/**
 * 引导式教程步骤目录。文案使用 lang 键 {@code tutorial.step.<id>.title/body}。
 */
public enum TutorialStep {
    WELCOME("welcome", TeamFilter.ALL, null),
    TEAM_SELECT("team_select", TeamFilter.ALL, GamePhase.WAITING_FOR_PLAYERS),
    PHASE_OVERVIEW("phase_overview", TeamFilter.ALL, null),
    KEYS_KJY("keys_kjy", TeamFilter.ALL, null),
    COMMANDER_VOTE("commander_vote", TeamFilter.ALL, null),
    FACTION_SELECT("faction_select", TeamFilter.ALL, null),
    FACTION_REVEAL("faction_reveal", TeamFilter.ALL, GamePhase.FACTION_REVEAL),
    DEPLOY_OVERVIEW("deploy_overview", TeamFilter.ALL, GamePhase.DEPLOYING),
    DEPLOY_ATTACK_WAIT("deploy_attack_wait", TeamFilter.ATTACK, GamePhase.DEPLOYING),
    DEPLOY_DEFEND_BUILD("deploy_defend_build", TeamFilter.DEFEND, GamePhase.DEPLOYING),
    UNIFIED_DEPLOY("unified_deploy", TeamFilter.ALL, null),
    CLASS_SELECT("class_select", TeamFilter.ALL, null),
    SQUAD("squad", TeamFilter.ALL, null),
    BASTION("bastion", TeamFilter.COMMANDER, null),
    TEAM_PACK("team_pack", TeamFilter.ALL, null),
    OUTPOST("outpost", TeamFilter.DEFEND, GamePhase.DEPLOYING),
    VEHICLE("vehicle", TeamFilter.COMMANDER, null),
    COMMANDER_SKILLS("commander_skills", TeamFilter.COMMANDER, null),
    SKILL_DRONE("skill_drone", TeamFilter.COMMANDER, null),
    SKILL_SUPPLY("skill_supply", TeamFilter.COMMANDER, null),
    SKILL_ARTY("skill_arty", TeamFilter.COMMANDER, null),
    BATTLE_START("battle_start", TeamFilter.ALL, GamePhase.BATTLE),
    STAMINA("stamina", TeamFilter.ALL, null),
    TROOPS("troops", TeamFilter.ALL, null),
    NAMETAG("nametag", TeamFilter.ALL, null),
    RESUPPLY("resupply", TeamFilter.ALL, null),
    RESPAWN_FLOW("respawn_flow", TeamFilter.ALL, null),
    MID_JOIN("mid_join", TeamFilter.ALL, null);

    public enum TeamFilter {
        ALL,
        ATTACK,
        DEFEND,
        COMMANDER
    }

    private final String id;
    private final TeamFilter teamFilter;
    private final GamePhase phaseAffinity;

    TutorialStep(String id, TeamFilter teamFilter, GamePhase phaseAffinity) {
        this.id = id;
        this.teamFilter = teamFilter;
        this.phaseAffinity = phaseAffinity;
    }

    public String getId() {
        return id;
    }

    public TeamFilter getTeamFilter() {
        return teamFilter;
    }

    public GamePhase getPhaseAffinity() {
        return phaseAffinity;
    }

    public String titleKey() {
        return "tutorial.step." + id + ".title";
    }

    public String bodyKey() {
        return "tutorial.step." + id + ".body";
    }

    public int ordinalIndex() {
        return ordinal() + 1;
    }

    public static int totalCount() {
        return values().length;
    }

    public static TutorialStep byId(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        for (TutorialStep step : values()) {
            if (step.id.equalsIgnoreCase(id)) {
                return step;
            }
        }
        return null;
    }

    /**
     * 阶段切换时的主提示步骤（不含攻/守细分）。
     */
    public static TutorialStep primaryForPhase(GamePhase phase) {
        if (phase == null) {
            return null;
        }
        return switch (phase) {
            case WAITING_FOR_PLAYERS -> TEAM_SELECT;
            case DEFEND_COMMANDER_VOTE, ATTACK_COMMANDER_VOTE -> COMMANDER_VOTE;
            case DEFEND_FACTION_SELECT, ATTACK_FACTION_SELECT -> FACTION_SELECT;
            case FACTION_REVEAL -> FACTION_REVEAL;
            case DEPLOYING -> DEPLOY_OVERVIEW;
            case BATTLE -> BATTLE_START;
        };
    }
}
