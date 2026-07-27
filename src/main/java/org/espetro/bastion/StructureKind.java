package org.espetro.bastion;

/**
 * Radio 为补给/建造范围锚点；HAB 为可复活兵站。
 */
public enum StructureKind {
    RADIO,
    HAB;

    public static StructureKind fromStorage(@javax.annotation.Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            return RADIO;
        }
        try {
            return StructureKind.valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return RADIO;
        }
    }

    public String networkType() {
        return name();
    }
}
