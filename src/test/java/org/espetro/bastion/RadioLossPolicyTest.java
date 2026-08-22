package org.espetro.bastion;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RadioLossPolicyTest {

    @Test
    void explosionOnRadioCoreAlwaysScoresLossEvenWhenIndexedAsFort() {
        assertTrue(RadioLossPolicy.explosionScoresRadioLoss(true, true));
        assertTrue(RadioLossPolicy.explosionScoresRadioLoss(true, false));
        assertFalse(RadioLossPolicy.explosionScoresRadioLoss(false, true));
        assertFalse(RadioLossPolicy.explosionScoresRadioLoss(false, false));
    }

    @Test
    void onlyFriendlyShovelAndMatchEndSkipRadioManpower() {
        assertTrue(RadioLossPolicy.deductManpower(true, false, false));
        assertFalse(RadioLossPolicy.deductManpower(true, true, false));
        assertFalse(RadioLossPolicy.deductManpower(true, false, true));
        assertFalse(RadioLossPolicy.deductManpower(true, true, true));
        assertFalse(RadioLossPolicy.deductManpower(false, false, false));
    }
}
