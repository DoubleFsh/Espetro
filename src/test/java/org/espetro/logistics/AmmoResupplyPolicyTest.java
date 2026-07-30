package org.espetro.logistics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AmmoResupplyPolicyTest {

    @Test
    void fullInventoryHasNoResupplyGap() {
        assertEquals(0, AmmoResupplyPolicy.grantCount(6, 6, 2));
        assertEquals(0, AmmoResupplyPolicy.grantCount(7, 6, 2));
    }

    @Test
    void onlyMissingAmountCanBeGranted() {
        assertEquals(1, AmmoResupplyPolicy.grantCount(5, 6, 4));
        assertEquals(4, AmmoResupplyPolicy.grantCount(0, 6, 4));
    }

    @Test
    void radioMustAffordTheWholeConfiguredCost() {
        assertFalse(AmmoResupplyPolicy.canAfford(49, 50));
        assertTrue(AmmoResupplyPolicy.canAfford(50, 50));
        assertTrue(AmmoResupplyPolicy.canAfford(0, 0));
    }
}
