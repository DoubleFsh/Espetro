package org.espetro.tutorial;

import org.espetro.team.GamePhase;

/**
 * 新手教程步骤目录（TutorialHudOverlay + 只读阶段 GUI 预览）。
 * 文案键：{@code tutorial.step.<id>.title/body}。
 * 默认仅手动 {@link TutorialManager#reopen} 按枚举顺序完整播放。
 */
public enum TutorialStep {
    WELCOME("welcome"),
    HUB("hub"),
    MAP_VOTE("map_vote"),
    MAP_LOADING("map_loading"),
    TEAM_SELECT("team_select"),
    COMMANDER_VOTE("commander_vote"),
    FACTION_SELECT("faction_select"),
    FACTION_REVEAL("faction_reveal"),
    DEPLOY_PANEL("deploy_panel"),
    SQUAD("squad"),
    CLASS_SELECT("class_select"),
    DEPLOY_POINT("deploy_point"),
    KEYS_RADIAL("keys_radial"),
    RADIO_RALLY("radio_rally"),
    LOGISTICS_FOB("logistics_fob"),
    COMMANDER_SKILLS("commander_skills"),
    OUTPOST("outpost"),
    BATTLE("battle"),
    RESPAWN("respawn"),
    SCORE_ROUND("score_round"),
    MID_JOIN("mid_join");

    private final String id;

    TutorialStep(String id) {
        this.id = id;
    }

    public String getId() {
        return id;
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

    /** 阶段亲和步骤（自动推送已关闭；保留参考）。 */
    public static TutorialStep primaryForPhase(GamePhase phase) {
        if (phase == null) {
            return null;
        }
        return switch (phase) {
            case LOBBY, WAITING_FOR_PLAYERS -> HUB;
            case MAP_VOTE -> MAP_VOTE;
            case MAP_LOADING -> MAP_LOADING;
            case TEAM_SELECT -> TEAM_SELECT;
            case DEFEND_COMMANDER_VOTE, ATTACK_COMMANDER_VOTE -> COMMANDER_VOTE;
            case DEFEND_FACTION_SELECT, ATTACK_FACTION_SELECT -> FACTION_SELECT;
            case FACTION_REVEAL -> FACTION_REVEAL;
            case DEPLOYING -> DEPLOY_PANEL;
            case BATTLE -> BATTLE;
            case ROUND_END -> SCORE_ROUND;
            case CLEANUP -> null;
        };
    }
}
