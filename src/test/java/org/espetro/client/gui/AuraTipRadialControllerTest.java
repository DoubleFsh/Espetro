package org.espetro.client.gui;

import org.espetro.network.FortificationCatalogPacket;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuraTipRadialControllerTest {

    @Test
    void buildWheelUsesCatalogRadioAndHabOnceAndKeepsRally() {
        List<FortificationCatalogPacket.Entry> catalog = List.of(
            entry("espetro:radio", "电台"),
            entry("espetro:hab", "兵站"),
            entry("espetro:ammo_crate", "弹药箱"),
            entry("espetro:vehicle_supply_station", "载具补给站"),
            entry("espetro:sandbag_wall", "沙袋掩体墙"));

        List<String> slotIds = AuraTipRadialController.buildMenuSlotIds(catalog);
        assertEquals("espetro.rally", slotIds.get(0));
        assertEquals(6, slotIds.size());
        assertFalse(slotIds.contains("espetro.radio"));
        assertFalse(slotIds.contains("espetro.hab"));
        assertTrue(slotIds.contains("espetro.fort.espetro:radio"));
        assertTrue(slotIds.contains("espetro.fort.espetro:hab"));
        assertEquals(slotIds.size(), slotIds.stream().collect(Collectors.toSet()).size());
    }

    private static FortificationCatalogPacket.Entry entry(String id, String name) {
        return new FortificationCatalogPacket.Entry(id, name, "espetro:textures/gui/squad/radio.png", 0, 0);
    }
}
