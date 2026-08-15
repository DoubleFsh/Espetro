package org.espetro.client.gui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VanillaHudLayoutTest {

    @Test
    void selectedNameSitsToTheLeftOfTheMatchingSlot() {
        assertEquals(72, VanillaHudNameLayout.nameX(100, 22));
        assertEquals(27, VanillaHudNameLayout.nameY(20));
    }

    @Test
    void nameFadeFollowsVanillaTimerAndHotbarAlpha() {
        assertEquals(255, VanillaHudNameLayout.nameFade(40, 1.0F));
        assertEquals(0, VanillaHudNameLayout.nameFade(0, 1.0F));
        assertEquals(0, VanillaHudNameLayout.nameFade(40, 0.0F));
        int half = VanillaHudNameLayout.nameFade(40, 0.5F);
        assertTrue(half > 100 && half <= 128);
        assertEquals(192 << 24, VanillaHudNameLayout.nameBackgroundColor(255));
    }
}
