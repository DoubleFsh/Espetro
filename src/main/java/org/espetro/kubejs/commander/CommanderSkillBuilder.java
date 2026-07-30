package org.espetro.kubejs.commander;

import org.espetro.Espetro;

import java.util.LinkedHashSet;
import java.util.Set;

public final class CommanderSkillBuilder {
    private final String id;
    private String displayName;
    private String description = "";
    private String stats = "";
    private String icon = "";
    private String trigger = "activate";
    private int cooldownSeconds = 60;
    private boolean enabled = true;
    private final Set<SkillUserRole> allowedRoles = new LinkedHashSet<>();

    public CommanderSkillBuilder(String id) {
        this.id = id == null ? "" : id.trim();
        this.displayName = this.id;
    }

    public CommanderSkillBuilder displayName(String value) {
        this.displayName = value;
        return this;
    }

    public CommanderSkillBuilder name(String value) {
        return displayName(value);
    }

    public CommanderSkillBuilder description(String value) {
        this.description = value == null ? "" : value;
        return this;
    }

    public CommanderSkillBuilder stats(String value) {
        this.stats = value == null ? "" : value;
        return this;
    }

    /**
     * 设置技能图标的资源位置，如 "espetro:textures/gui/commander_skills/drone_detection.png"。
     * 资源包可覆盖同路径。
     */
    public CommanderSkillBuilder icon(String value) {
        this.icon = value == null ? "" : value.trim();
        return this;
    }

    public CommanderSkillBuilder trigger(String value) {
        this.trigger = value == null || value.isBlank() ? "activate" : value.trim().toLowerCase();
        return this;
    }

    public CommanderSkillBuilder activate() {
        return trigger("activate");
    }

    public CommanderSkillBuilder targetMap() {
        return trigger("target_map");
    }

    public CommanderSkillBuilder artilleryTarget() {
        return trigger("artillery_target");
    }

    public CommanderSkillBuilder cooldownSeconds(int value) {
        this.cooldownSeconds = Math.max(0, value);
        return this;
    }

    public CommanderSkillBuilder cooldown(int value) {
        return cooldownSeconds(value);
    }

    public CommanderSkillBuilder enabled(boolean value) {
        this.enabled = value;
        return this;
    }

    public CommanderSkillBuilder disabled() {
        this.enabled = false;
        return this;
    }

    /**
     * 声明可用对象。支持多参数或数组/列表，如：
     * <pre>
     *   .usableBy('commander', 'squad_leader')
     *   .usableBy(['commander', 'squad_leader'])
     * </pre>
     * 同义：commander/指挥官；squad_leader/leader/sl/队长。
     * 未声明或全部无效时注册为仅指挥官。
     */
    public CommanderSkillBuilder usableBy(Object... roles) {
        Set<SkillUserRole> parsed = SkillUserRole.parseMany(roles);
        if (parsed.isEmpty()) {
            Espetro.LOGGER.warn("技能 {} usableBy 无有效角色，将回退为仅指挥官", id);
            return this;
        }
        allowedRoles.clear();
        allowedRoles.addAll(parsed);
        return this;
    }

    /** 同 {@link #usableBy(Object...)} */
    public CommanderSkillBuilder roles(Object... roles) {
        return usableBy(roles);
    }

    public CommanderSkillBuilder allowCommander() {
        allowedRoles.add(SkillUserRole.COMMANDER);
        return this;
    }

    public CommanderSkillBuilder allowSquadLeader() {
        allowedRoles.add(SkillUserRole.SQUAD_LEADER);
        return this;
    }

    public KubeCommanderSkillDefinition build() {
        Set<SkillUserRole> roles = allowedRoles.isEmpty()
            ? SkillUserRole.defaultRoles()
            : new LinkedHashSet<>(allowedRoles);
        return new KubeCommanderSkillDefinition(
            id,
            displayName,
            description,
            stats,
            icon,
            trigger,
            cooldownSeconds,
            enabled,
            roles
        );
    }

    public KubeCommanderSkillDefinition register() {
        KubeCommanderSkillDefinition definition = build();
        EspetroCommanderSkills.register(definition);
        return definition;
    }
}
