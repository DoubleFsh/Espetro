package org.espetro.team;

/** Pure validation/math for class attribute bonuses. */
public final class ClassAttributeBonusPolicy {
    private static final double MAX_HEALTH_BONUS = 1024.0D;
    private static final double MAX_SPEED_BONUS = 4.0D;

    private ClassAttributeBonusPolicy() {
    }

    public static double healthAmount(int configuredBonus) {
        return Math.max(-MAX_HEALTH_BONUS, Math.min(MAX_HEALTH_BONUS, configuredBonus));
    }

    public static double speedMultiplier(float configuredBonus) {
        if (!Float.isFinite(configuredBonus)) {
            return 0.0D;
        }
        return Math.max(-0.95D, Math.min(MAX_SPEED_BONUS, configuredBonus));
    }

    public static float clampCurrentHealth(float currentHealth, float newMaximum) {
        if (!Float.isFinite(currentHealth) || !Float.isFinite(newMaximum) || newMaximum <= 0.0F) {
            return 0.0F;
        }
        return Math.max(0.0F, Math.min(currentHealth, newMaximum));
    }
}
