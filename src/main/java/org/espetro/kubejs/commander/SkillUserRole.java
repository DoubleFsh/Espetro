package org.espetro.kubejs.commander;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * KubeJS 技能可用对象：指挥官和/或小队长。
 */
public enum SkillUserRole {
    COMMANDER,
    SQUAD_LEADER;

    public static Set<SkillUserRole> defaultRoles() {
        Set<SkillUserRole> roles = new LinkedHashSet<>();
        roles.add(COMMANDER);
        return roles;
    }

    /**
     * 解析脚本字符串；未知 token 忽略。若全部无效返回空集（由调用方决定回退）。
     */
    public static SkillUserRole parseOne(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String key = raw.trim().toLowerCase(Locale.ROOT)
            .replace('-', '_')
            .replace(' ', '_');
        return switch (key) {
            case "commander", "cmd", "command", "指挥官" -> COMMANDER;
            case "squad_leader", "squadleader", "leader", "sl", "队长", "小队长" -> SQUAD_LEADER;
            default -> null;
        };
    }

    public static Set<SkillUserRole> parseMany(Object... tokens) {
        Set<SkillUserRole> roles = new LinkedHashSet<>();
        if (tokens == null) {
            return roles;
        }
        for (Object token : tokens) {
            if (token == null) {
                continue;
            }
            if (token instanceof Iterable<?> it) {
                for (Object nested : it) {
                    SkillUserRole role = parseOne(String.valueOf(nested));
                    if (role != null) {
                        roles.add(role);
                    }
                }
                continue;
            }
            if (token.getClass().isArray()) {
                int len = java.lang.reflect.Array.getLength(token);
                for (int i = 0; i < len; i++) {
                    Object nested = java.lang.reflect.Array.get(token, i);
                    SkillUserRole role = parseOne(String.valueOf(nested));
                    if (role != null) {
                        roles.add(role);
                    }
                }
                continue;
            }
            SkillUserRole role = parseOne(String.valueOf(token));
            if (role != null) {
                roles.add(role);
            }
        }
        return roles;
    }

    public String wireName() {
        return name().toLowerCase(Locale.ROOT);
    }
}
