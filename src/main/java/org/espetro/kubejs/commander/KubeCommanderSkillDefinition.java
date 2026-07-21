package org.espetro.kubejs.commander;

public final class KubeCommanderSkillDefinition {
    private final String id;
    private final String displayName;
    private final String description;
    private final String stats;
    private final String icon;
    private final String trigger;
    private final int cooldownSeconds;
    private final boolean enabled;

    public KubeCommanderSkillDefinition(String id,
                                         String displayName,
                                         String description,
                                         String stats,
                                         String icon,
                                         String trigger,
                                         int cooldownSeconds,
                                         boolean enabled) {
        this.id = sanitizeId(id);
        this.displayName = displayName == null || displayName.isBlank() ? this.id : displayName;
        this.description = description == null ? "" : description;
        this.stats = stats == null ? "" : stats;
        this.icon = icon == null ? "" : icon.trim();
        this.trigger = trigger == null || trigger.isBlank() ? "activate" : trigger.trim().toLowerCase();
        this.cooldownSeconds = Math.max(0, cooldownSeconds);
        this.enabled = enabled;
    }

    public String id() {
        return id;
    }

    public String getId() {
        return id;
    }

    public String displayName() {
        return displayName;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String description() {
        return description;
    }

    public String getDescription() {
        return description;
    }

    public String stats() {
        return stats;
    }

    public String getStats() {
        return stats;
    }

    public String icon() {
        return icon;
    }

    public String getIcon() {
        return icon;
    }

    public String trigger() {
        return trigger;
    }

    public String getTrigger() {
        return trigger;
    }

    public int cooldownSeconds() {
        return cooldownSeconds;
    }

    public int getCooldownSeconds() {
        return cooldownSeconds;
    }

    public boolean enabled() {
        return enabled;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public boolean isTargetMapTrigger() {
        return "artillery_target".equals(trigger) || "target_map".equals(trigger);
    }

    public boolean isActivateTrigger() {
        return !isTargetMapTrigger();
    }

    private static String sanitizeId(String id) {
        return id == null ? "" : id.trim();
    }
}
