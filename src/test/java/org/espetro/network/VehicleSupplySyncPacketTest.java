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
    void combatVehicleWithoutTransferAccessHasNoWheelAction() {
        VehicleSupplySyncPacket packet = VehicleSupplySyncPacket.state(
            UUID.randomUUID(), 0, 0, 500,
            false, true, false, false, 20);

        assertFalse(packet.hasAnyAction());
    }
}
