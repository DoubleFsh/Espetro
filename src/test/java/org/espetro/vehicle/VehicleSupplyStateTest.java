package org.espetro.vehicle;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link VehicleManager.VehicleSupplyState} 纯逻辑单测：共享容量池与装卸载边界。
 */
class VehicleSupplyStateTest {

    @Test
    void newlyDeployedCombatAndSupplyVehiclesStartEmpty() {
        var combat = new VehicleManager.VehicleSupplyState(500, false);
        var supply = new VehicleManager.VehicleSupplyState(5000, true);
        assertEquals(0, combat.getAmmo());
        assertEquals(500, combat.getFreeSpace());
        assertEquals(0, supply.getAmmo());
        assertEquals(0, supply.getConstruction());
        assertEquals(5000, supply.getFreeSpace());
    }

    @Test
    void sharedCapacityLimitsAmmoAndConstruction() {
        var supply = new VehicleManager.VehicleSupplyState(100, true);
        assertEquals(80, supply.addAmmo(80));
        assertEquals(20, supply.addConstruction(50));
        assertEquals(0, supply.getFreeSpace());
        assertEquals(0, supply.addAmmo(10));
        assertEquals(80, supply.getAmmo());
        assertEquals(20, supply.getConstruction());
    }

    @Test
    void cannotCarryConstructionWhenDisabled() {
        var supply = new VehicleManager.VehicleSupplyState(100, false);
        assertEquals(0, supply.addConstruction(50));
        assertEquals(0, supply.getConstruction());
        assertEquals(100, supply.addAmmo(100));
    }

    @Test
    void fillAmmoClearsConstruction() {
        var supply = new VehicleManager.VehicleSupplyState(100, true);
        supply.addConstruction(30);
        supply.fillAmmo();
        assertEquals(100, supply.getAmmo());
        assertEquals(0, supply.getConstruction());
        assertEquals(0, supply.getFreeSpace());
    }

    @Test
    void fillHalfSplitsCapacityForSupplyVehicle() {
        var supply = new VehicleManager.VehicleSupplyState(100, true);
        supply.fillHalf();
        assertEquals(50, supply.getAmmo());
        assertEquals(50, supply.getConstruction());
        assertEquals(0, supply.getFreeSpace());
    }

    @Test
    void fillHalfWithoutConstructionOnlyFillsAmmoHalf() {
        var supply = new VehicleManager.VehicleSupplyState(100, false);
        supply.fillHalf();
        assertEquals(50, supply.getAmmo());
        assertEquals(0, supply.getConstruction());
        assertEquals(50, supply.getFreeSpace());
    }

    @Test
    void removeAndAffordAmmo() {
        var supply = new VehicleManager.VehicleSupplyState(200, false);
        supply.fillAmmo();
        assertTrue(supply.canAffordAmmo(50));
        assertEquals(50, supply.removeAmmo(50));
        assertEquals(150, supply.getAmmo());
        assertFalse(supply.canAffordAmmo(151));
        assertEquals(150, supply.removeAmmo(999));
        assertEquals(0, supply.getAmmo());
    }

    @Test
    void loadRespectsFreeSpaceBeforeTransfer() {
        // 模拟装卸事务：want = min(chunk, freeSpace)
        var supply = new VehicleManager.VehicleSupplyState(100, false);
        supply.addAmmo(90);
        int want = Math.min(100, supply.getFreeSpace());
        assertEquals(10, want);
        assertEquals(10, supply.addAmmo(want));
        assertEquals(100, supply.getAmmo());
    }

    @Test
    void onlySupplyVehiclesActAsMobileClassChangeSources() {
        var supplyVehicle = new VehicleConfig.VehicleTypeConfig(1, 10);
        supplyVehicle.supplyVeh = true;
        assertTrue(supplyVehicle.canChangeClass());

        var combatVehicle = new VehicleConfig.VehicleTypeConfig(1, 10);
        combatVehicle.fightVeh = true;
        assertFalse(combatVehicle.canChangeClass());
    }
}
