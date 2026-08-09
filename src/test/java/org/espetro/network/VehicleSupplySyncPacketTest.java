package org.espetro.network;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VehicleSupplySyncPacketTest {

    @Test
    void supplyVehicleKeepsClassChangeWheelAvailableOutsideTransferZone() {
        VehicleSupplySyncPacket packet = VehicleSupplySyncPacket.state(
            UUID.randomUUID(), 500, 500, 3000,
            true, false, false, false, 20);

        assertTrue(packet.hasAnyAction());
    }

    @Test
    void combatVehicleOutsideTransferZoneStillHasInfantryResupply() {
        VehicleSupplySyncPacket packet = VehicleSupplySyncPacket.state(
            UUID.randomUUID(), 0, 0, 500,
            false, true, false, false, 20);

        assertTrue(packet.hasAnyAction());
        assertTrue(packet.canResupplyInfantry());
    }

    @Test
    void ordinaryVehicleStillHasInfantryResupply() {
        VehicleSupplySyncPacket packet = VehicleSupplySyncPacket.state(
            UUID.randomUUID(), 100, 0, 300,
            false, false, false, false, 20);

        assertTrue(packet.hasAnyAction());
        assertTrue(packet.canResupplyInfantry());
        assertFalse(packet.canTransferAmmo());
    }
}
