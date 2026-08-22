package org.espetro.client.gui;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResupplyRadialControllerTest {

    @Test
    void pageCountRoundsUpAndNeverZero() {
        assertEquals(1, ResupplyRadialController.pageCount(0, 5));
        assertEquals(1, ResupplyRadialController.pageCount(5, 5));
        assertEquals(2, ResupplyRadialController.pageCount(6, 5));
        assertEquals(3, ResupplyRadialController.pageCount(11, 5));
    }

    @Test
    void firstPageHasEntriesBackAndNext() {
        List<String> ids = ResupplyRadialController.pageSlotIds(8, 0, 40);
        assertEquals("espetro.resupply.entry.0", ids.get(0));
        assertEquals("espetro.resupply.entry.4", ids.get(4));
        assertTrue(ids.contains("espetro.resupply.back"));
        assertTrue(ids.contains("espetro.resupply.next"));
        assertFalse(ids.contains("espetro.resupply.previous"));
    }

    @Test
    void lastPageHasPreviousAndNoNext() {
        List<String> ids = ResupplyRadialController.pageSlotIds(8, 1, 40);
        assertTrue(ids.contains("espetro.resupply.entry.5"));
        assertTrue(ids.contains("espetro.resupply.entry.7"));
        assertTrue(ids.contains("espetro.resupply.previous"));
        assertFalse(ids.contains("espetro.resupply.next"));
    }
}
