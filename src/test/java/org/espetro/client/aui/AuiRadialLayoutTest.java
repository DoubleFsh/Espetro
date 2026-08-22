package org.espetro.client.aui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AuiRadialLayoutTest {

    @Test
    void topOfRingSelectsFirstSlotAndHoleIgnoresPointer() {
        assertEquals(0, AuiRadialLayout.hitIndex(100, 100 - 70, 100, 100, 4));
        assertEquals(1, AuiRadialLayout.hitIndex(100 + 70, 100, 100, 100, 4));
        assertEquals(-1, AuiRadialLayout.hitIndex(100, 100, 100, 100, 4));
        assertEquals(-1, AuiRadialLayout.hitIndex(100, -50, 100, 100, 4));
    }

    @Test
    void slotCoordinatesSitOnTheRingMidline() {
        assertEquals(100.0D, AuiRadialLayout.slotX(100, 0, 4), 0.01D);
        assertEquals(100.0D - AuiRadialLayout.slotRadius(),
            AuiRadialLayout.slotY(100, 0, 4), 0.01D);
    }
}
