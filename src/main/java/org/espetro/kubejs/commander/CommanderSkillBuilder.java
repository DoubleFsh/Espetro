package org.espetro.kubejs.commander;

public final class CommanderSkillBuilder {
    private final String id;
    private String displayName;
    private String description = "";
    private String stats = "";
    private String trigger = "activate";
    private int cooldownSeconds = 60;
    private boolean enabled = true;

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

    public KubeCommanderSkillDefinition build() {
        return new KubeCommanderSkillDefinition(
            id,
            displayName,
            description,
            stats,
            trigger,
            cooldownSeconds,
            enabled
        );
    }

    public KubeCommanderSkillDefinition register() {
        KubeCommanderSkillDefinition definition = build();
        EspetroCommanderSkills.register(definition);
        return definition;
    }
}
