package org.espetro.mixin;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class EspetroMixinPluginTest {

    @Test
    void optionalSeatGuardsAreNotSkippedByEarlyForgeModDiscovery() {
        EspetroMixinPlugin plugin = new EspetroMixinPlugin();

        assertTrue(plugin.shouldApplyMixin(
            "com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity",
            "org.espetro.mixin.sbw.VehicleEntitySeatAccessMixin"));
        assertTrue(plugin.shouldApplyMixin(
            "com.atsuishio.superbwarfare.network.message.send.ChangeVehicleSeatMessage",
            "org.espetro.mixin.sbw.ChangeVehicleSeatMessageMixin"));
    }
}
