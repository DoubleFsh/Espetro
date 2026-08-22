package org.espetro.client.gui;

import org.espetro.network.RadioRadialPacket;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RadioRadialControllerTest {

    @Test
    void rootHasResupplyAndChangeClassOnly() {
        List<String> ids = RadioRadialController.rootSlotIds();
        assertEquals(List.of("espetro.radio.resupply", "espetro.radio.change_class"), ids);
        assertFalse(ids.contains("espetro.radio"));
        assertFalse(ids.contains("espetro.hab"));
    }

    @Test
    void classMenuKeepsBackSlotAndSanitizesIds() {
        List<RadioRadialPacket.ClassEntry> classes = List.of(
            new RadioRadialPacket.ClassEntry("rifleman", "步枪手", "rifleman"),
            new RadioRadialPacket.ClassEntry("lead crew", "组长", "leader"));
        List<String> ids = RadioRadialController.classSlotIds(classes);
        assertEquals("espetro.radio.back", ids.get(0));
        assertTrue(ids.contains("espetro.radio.class.rifleman"));
        assertTrue(ids.contains("espetro.radio.class.lead_crew"));
        assertEquals(3, ids.size());
    }

    @Test
    void emptyClassListShowsUnavailableSlot() {
        List<String> ids = RadioRadialController.classSlotIds(List.of());
        assertEquals(List.of("espetro.radio.back", "espetro.radio.no_class"), ids);
    }

    @Test
    void sanitizeRejectsPathSeparators() {
        assertEquals("unknown", RadioRadialController.sanitizeSlotId(""));
        assertEquals("a_b_c", RadioRadialController.sanitizeSlotId("a/b\\c"));
    }
}
