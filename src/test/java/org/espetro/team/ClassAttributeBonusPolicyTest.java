package org.espetro.team;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ClassAttributeBonusPolicyTest {
    @Test
    void healthBonusUsesMinecraftHealthPoints() {
        assertEquals(4.0D, ClassAttributeBonusPolicy.healthAmount(4));
    }

    @Test
    void speedBonusIsAProportionalMultiplier() {
        assertEquals(0.1D, ClassAttributeBonusPolicy.speedMultiplier(0.1F), 0.000001D);
    }

    @Test
    void removingBonusOnlyClampsAndNeverHeals() {
        assertEquals(12.0F, ClassAttributeBonusPolicy.clampCurrentHealth(12.0F, 20.0F));
        assertEquals(20.0F, ClassAttributeBonusPolicy.clampCurrentHealth(24.0F, 20.0F));
    }
}
