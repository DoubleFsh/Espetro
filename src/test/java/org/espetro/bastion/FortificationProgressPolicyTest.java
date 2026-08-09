package org.espetro.bastion;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FortificationProgressPolicyTest {
    @Test
    void stageRevealsNumericLayersMonotonically() {
        assertEquals(0, FortificationProgressPolicy.stage(0, 100));
        assertEquals(1, FortificationProgressPolicy.stage(1, 100));
        assertEquals(1, FortificationProgressPolicy.stage(16, 100));
        assertEquals(2, FortificationProgressPolicy.stage(17, 100));
        assertEquals(6, FortificationProgressPolicy.stage(100, 100));
        assertEquals(6, FortificationProgressPolicy.stage(999, 100));
    }

    @Test
    void proportionalDamageDestroysAWholeStructureWithoutRoundingImmortality() {
        int progress = 100;
        int damage = FortificationProgressPolicy.damagePerPart(100, 6);
        for (int i = 0; i < 6; i++) progress = Math.max(0, progress - damage);
        assertEquals(0, progress);
    }

    @Test
    void repairRestoresOnlyTheNumberOfPartsSupportedByProgress() {
        assertEquals(0, FortificationProgressPolicy.desiredPresentParts(0, 100, 6));
        assertEquals(1, FortificationProgressPolicy.desiredPresentParts(1, 100, 6));
        assertEquals(5, FortificationProgressPolicy.desiredPresentParts(83, 100, 6));
        assertEquals(6, FortificationProgressPolicy.desiredPresentParts(84, 100, 6));
    }
}
