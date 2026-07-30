package org.espetro.kubejs.commander;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

public final class KubeCommanderSkillDefinition {
    private final String id;
    private final String displayName;
    private final String description;
    private final String stats;
    private final String icon;
    private final String trigger;
    private final int cooldownSeconds;
    private final boolean enabled;
    private final Set<SkillUserRole> allowedRoles;

    public KubeCommanderSkillDefinition(String id,
                                         String displayName,
                                         String description,
                                         String stats,
                                         String icon,
                                         String trigger,
                                         int cooldownSeconds,
                                         boolean enabled) {
        this(id, displayName, description, stats, icon, trigger, cooldownSeconds, enabled,
            SkillUserRole.defaultRoles());
    }

    public KubeCommanderSkillDefinition(String id,
                                         String displayName,
                                         String description,
                                         String stats,
                                         String icon,
                                         String trigger,
                                         int cooldownSeconds,
                                         boolean enabled,
                                         Set<SkillUserRole> allowedRoles) {
        this.id = sanitizeId(id);
        this.displayName = displayName == null || displayName.isBlank() ? this.id : displayName;
        this.description = description == null ? "" : description;
        this.stats = stats == null ? "" : stats;
        this.icon = icon == null ? "" : icon.trim();
        this.trigger = trigger == null || trigger.isBlank() ? "activate" : trigger.trim().toLowerCase(Locale.ROOT);
        this.cooldownSeconds = Math.max(0, cooldownSeconds);
        this.enabled = enabled;
        Set<SkillUserRole> roles = allowedRoles == null || allowedRoles.isEmpty()
            ? SkillUserRole.defaultRoles()
            : new LinkedHashSet<>(allowedRoles);
        this.allowedRoles = Collections.unmodifiableSet(roles);
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

    public Set<SkillUserRole> allowedRoles() {
        return allowedRoles;
    }

    public Set<SkillUserRole> getAllowedRoles() {
        return allowedRoles;
    }

    public boolean allows(SkillUserRole role) {
        return role != null && allowedRoles.contains(role);
    }

    public boolean allowsCommander() {
        return allows(SkillUserRole.COMMANDER);
    }

    public boolean allowsSquadLeader() {
        return allows(SkillUserRole.SQUAD_LEADER);
    }

    /** 供日志/UI：commander,squad_leader */
    public String allowedRolesWire() {
        return allowedRoles.stream()
            .map(SkillUserRole::wireName)
            .collect(Collectors.joining(","));
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
