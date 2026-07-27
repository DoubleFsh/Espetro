package org.espetro.stamina;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StaminaRecoveryPolicyTest {

    @Test
    void depletedBarsOfDifferentSizesRecoverWithinTwelveSeconds() {
        int delay = StaminaRecoveryPolicy.effectiveDelaySeconds(2, 12);
        int events = 12 - delay + 1;
        int restore100 = StaminaRecoveryPolicy.restorePerSecond(100, 2, 2, 12);
        int restore500 = StaminaRecoveryPolicy.restorePerSecond(500, 2, 2, 12);

        assertEquals(2, delay);
        assertEquals(10, restore100);
        assertEquals(46, restore500);
        assertTrue(restore100 * events >= 100);
        assertTrue(restore500 * events >= 500);
    }

    @Test
    void configuredRateRemainsTheMinimumAndZeroTargetKeepsLegacyBehavior() {
        assertEquals(80,
            StaminaRecoveryPolicy.restorePerSecond(100, 80, 2, 12));
        assertEquals(2,
            StaminaRecoveryPolicy.restorePerSecond(500, 2, 2, 0));
        assertEquals(20,
            StaminaRecoveryPolicy.effectiveDelaySeconds(20, 0));
    }

    @Test
    void targetShortensAnImpossibleDelay() {
        assertEquals(11,
            StaminaRecoveryPolicy.effectiveDelaySeconds(20, 12));
        assertEquals(250,
            StaminaRecoveryPolicy.restorePerSecond(500, 2, 20, 12));
    }
}
