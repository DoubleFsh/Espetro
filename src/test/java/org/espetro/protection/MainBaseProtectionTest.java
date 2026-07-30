package org.espetro.protection;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MainBaseProtectionTest {

    @Test
    void horizontalRadiusIncludesBoundaryAndIgnoresHeight() {
        assertTrue(MainBaseProtection.isWithinHorizontalRadius(
            150.0, 0.0, 0.0, 0.0, 150.0));
        assertTrue(MainBaseProtection.isWithinHorizontalRadius(
            90.0, 120.0, 0.0, 0.0, 150.0));
        assertFalse(MainBaseProtection.isWithinHorizontalRadius(
            150.01, 0.0, 0.0, 0.0, 150.0));
    }

    @Test
    void zeroRadiusDisablesProtection() {
        assertFalse(MainBaseProtection.isWithinHorizontalRadius(
            0.0, 0.0, 0.0, 0.0, 0.0));
    }
}
