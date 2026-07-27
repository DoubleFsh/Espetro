package org.espetro.stamina;

/**
 * Pure stamina recovery calculations shared by the server runtime and tests.
 */
final class StaminaRecoveryPolicy {

    private StaminaRecoveryPolicy() {
    }

    /**
     * A positive full-recovery target is authoritative, so an excessively long
     * configured delay is shortened enough to leave at least one recovery interval.
     */
    static int effectiveDelaySeconds(int configuredDelaySeconds,
                                     int fullRecoverySeconds) {
        int configured = Math.max(0, configuredDelaySeconds);
        if (fullRecoverySeconds <= 0) {
            return configured;
        }
        return Math.min(configured, Math.max(0, fullRecoverySeconds - 1));
    }

    /**
     * Keeps the configured per-second value as a minimum while scaling recovery
     * for different stamina maxima so a fully depleted bar meets the target.
     */
    static int restorePerSecond(int maxStamina,
                                int configuredRestorePerSecond,
                                int configuredDelaySeconds,
                                int fullRecoverySeconds) {
        int configured = Math.max(0, configuredRestorePerSecond);
        if (maxStamina <= 0 || fullRecoverySeconds <= 0) {
            return configured;
        }
        int delay = effectiveDelaySeconds(
            configuredDelaySeconds, fullRecoverySeconds);
        int recoveryEvents = Math.max(1, fullRecoverySeconds - delay + 1);
        int required = (int) Math.min(Integer.MAX_VALUE,
            (maxStamina + (long) recoveryEvents - 1L) / recoveryEvents);
        return Math.max(configured, required);
    }
}
