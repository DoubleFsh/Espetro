package org.espetro.audio;

import java.util.Locale;

/** Pure policy helpers for team mapping and the per-round easter-egg roll. */
public final class AudioCuePolicy {
    public static final double EASTER_EGG_CHANCE = 0.10D;

    private AudioCuePolicy() {
    }

    public static String normalizeTeam(String team) {
        if (team == null) return null;
        return switch (team.trim().toUpperCase(Locale.ROOT)) {
            case "ATTACK", "ESPETRO_ATTACK" -> "ATTACK";
            case "DEFEND", "ESPETRO_DEFEND" -> "DEFEND";
            default -> null;
        };
    }

    public static String opposingTeam(String team) {
        String normalized = normalizeTeam(team);
        if ("ATTACK".equals(normalized)) return "DEFEND";
        if ("DEFEND".equals(normalized)) return "ATTACK";
        return null;
    }

    /**
     * Resolve the side raising a newly neutralized point. When the point is
     * empty on the exact transition tick, fall back to the original owner's
     * opposing side because Espetro battles always have two sides.
     */
    public static String resolveNeutralizingTeam(String originalOwnerTeam,
                                                  String activeAttackingTeam) {
        String owner = normalizeTeam(originalOwnerTeam);
        if (owner == null) return null;
        String attacker = normalizeTeam(activeAttackingTeam);
        return attacker != null && !attacker.equals(owner)
            ? attacker : opposingTeam(owner);
    }

    public static boolean useEasterEgg(double roll) {
        return Double.isFinite(roll) && roll >= 0.0D && roll < EASTER_EGG_CHANCE;
    }
}
