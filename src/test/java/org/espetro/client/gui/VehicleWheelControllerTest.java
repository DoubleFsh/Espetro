package org.espetro.client.gui;

import org.espetro.network.VehicleSupplySyncPacket;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VehicleWheelControllerTest {

    @Test
    void transferActionsAreHoldRepeatOnly() {
        assertTrue(VehicleWheelController.isTransferAction("LOAD_AMMO"));
        assertTrue(VehicleWheelController.isTransferAction("UNLOAD_CONSTRUCTION"));
        assertFalse(VehicleWheelController.isTransferAction("RESUPPLY_INFANTRY"));
        assertFalse(VehicleWheelController.isTransferAction("CHANGE_CLASS"));
    }

    @Test
    void layoutSignatureTracksVisibleFamilies() {
        UUID id = UUID.fromString("00000000-0000-0000-0000-000000000001");
        VehicleSupplySyncPacket supply = VehicleSupplySyncPacket.state(
            id, 100, 50, 200, true, false, true, true, 10);
        assertEquals("ACRS", VehicleWheelController.layoutSignature(supply));
        assertEquals("", VehicleWheelController.layoutSignature(null));
    }

    @Test
    void visibleActionsFollowSnapshotFlags() {
        UUID id = UUID.fromString("00000000-0000-0000-0000-000000000002");
        VehicleSupplySyncPacket fight = VehicleSupplySyncPacket.state(
            id, 20, 0, 20, false, true, false, false, 20);
        List<String> actions = VehicleWheelController.visibleActions(fight);
        assertEquals(List.of("RESUPPLY_INFANTRY", "CHANGE_CLASS"), actions);

        VehicleSupplySyncPacket logistics = VehicleSupplySyncPacket.state(
            id, 80, 40, 200, true, false, true, true, 8);
        assertEquals(
            List.of("LOAD_AMMO", "UNLOAD_AMMO", "LOAD_CONSTRUCTION",
                "UNLOAD_CONSTRUCTION", "RESUPPLY_INFANTRY", "CHANGE_CLASS"),
            VehicleWheelController.visibleActions(logistics));
    }
}
