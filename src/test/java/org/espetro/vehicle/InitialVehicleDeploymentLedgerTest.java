package org.espetro.vehicle;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InitialVehicleDeploymentLedgerTest {

    @Test
    void claimsEveryConfiguredSlotOnceAcrossRepeatedPhaseHooks() {
        InitialVehicleDeploymentLedger ledger = new InitialVehicleDeploymentLedger();

        var first = new InitialVehicleDeploymentLedger.SlotKey(
            "pla_112th", "tank", 0, "attack");
        var second = new InitialVehicleDeploymentLedger.SlotKey(
            "pla_112th", "tank", 1, "ATTACK");
        var defender = new InitialVehicleDeploymentLedger.SlotKey(
            "us_redone", "tank", 0, "DEFEND");

        assertTrue(ledger.claim(first));
        assertFalse(ledger.claim(new InitialVehicleDeploymentLedger.SlotKey(
            "pla_112th", "tank", 0, "ATTACK")));
        assertTrue(ledger.claim(second));
        assertTrue(ledger.claim(defender));
        assertEquals(3, ledger.size());
    }

    @Test
    void roundResetAllowsTheNextMatchToDeployItsFirstWave() {
        InitialVehicleDeploymentLedger ledger = new InitialVehicleDeploymentLedger();
        var slot = new InitialVehicleDeploymentLedger.SlotKey(
            "us_redone", "ifv", 0, "DEFEND");

        assertTrue(ledger.claim(slot));
        ledger.clear();

        assertEquals(0, ledger.size());
        assertTrue(ledger.claim(slot));
    }

    @Test
    void initialDelayUsesDeployPhaseStartAndZeroIsImmediatelyDue() {
        assertEquals(10_000L, VehicleManager.computeInitialReadyAt(10_000L, 0));
        assertEquals(25_000L, VehicleManager.computeInitialReadyAt(10_000L, 15));
    }

    @Test
    void initialDelayCalculationCannotOverflowEpochMillis() {
        assertEquals(Long.MAX_VALUE,
            VehicleManager.computeInitialReadyAt(Long.MAX_VALUE - 500L, 1));
    }
}
