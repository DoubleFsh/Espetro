package org.espetro.client.gui;

import org.espetro.network.FobSupplySyncPacket;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FobSupplyHudTest {

    @Test
    void barWidthUsesIndependentCapacityAndClampsInvalidValues() {
        assertEquals(0, FobSupplyHud.scaledBarWidth(0, 20_000, 48));
        assertEquals(24, FobSupplyHud.scaledBarWidth(10_000, 20_000, 48));
        assertEquals(48, FobSupplyHud.scaledBarWidth(20_000, 20_000, 48));
        assertEquals(48, FobSupplyHud.scaledBarWidth(30_000, 20_000, 48));
        assertEquals(1, FobSupplyHud.scaledBarWidth(1, 20_000, 48));
        assertEquals(0, FobSupplyHud.scaledBarWidth(1_000, 0, 48));
    }

    @Test
    void redrawRevisionChangesOnlyWhenVisibleSupplyStateChanges() {
        FobSupplyHud.resetStateForTest();
        FobSupplySyncPacket initial = new FobSupplySyncPacket(
            true, 1_000, 2_000, 20_000, 20_000, 100, 100);
        FobSupplyHud.update(initial);
        assertEquals(1, FobSupplyHud.stateRevisionForTest());

        FobSupplyHud.update(initial);
        assertEquals(1, FobSupplyHud.stateRevisionForTest());

        FobSupplyHud.update(new FobSupplySyncPacket(
            true, 1_000, 2_100, 20_000, 20_000, 100, 100));
        assertEquals(2, FobSupplyHud.stateRevisionForTest());

        FobSupplyHud.update(new FobSupplySyncPacket(
            true, 1_000, 2_100, 20_000, 20_000, 50, 100));
        assertEquals(3, FobSupplyHud.stateRevisionForTest());

        FobSupplyHud.clear();
        assertEquals(4, FobSupplyHud.stateRevisionForTest());
        FobSupplyHud.clear();
        assertEquals(4, FobSupplyHud.stateRevisionForTest());
    }

}
