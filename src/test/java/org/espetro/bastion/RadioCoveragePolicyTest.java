package org.espetro.bastion;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RadioCoveragePolicyTest {

    @Test
    void separationNeverAllowsBuildRangesToOverlap() {
        assertEquals(300.0, RadioCoveragePolicy.minimumCenterDistance(150.0, 0.0));
        assertEquals(400.0, RadioCoveragePolicy.minimumCenterDistance(150.0, 400.0));
    }

    @Test
    void touchingRangesAreAllowedButIntersectionIsRejected() {
        assertTrue(RadioCoveragePolicy.overlaps(299.0 * 299.0, 300.0));
        assertFalse(RadioCoveragePolicy.overlaps(300.0 * 300.0, 300.0));
        assertFalse(RadioCoveragePolicy.overlaps(301.0 * 301.0, 300.0));
    }

    @Test
    void enemyRadioNeverBlocksFriendlyRadioPlacement() {
        double overlappingDistance = 100.0 * 100.0;

        assertTrue(RadioCoveragePolicy.blocksPlacement(
            "ATTACK", "ATTACK", overlappingDistance, 300.0));
        assertFalse(RadioCoveragePolicy.blocksPlacement(
            "DEFEND", "ATTACK", overlappingDistance, 300.0));
    }
}
