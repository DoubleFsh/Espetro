package org.espetro.api;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TacticalMapStateSnapshotTest {
    @Test
    void unchangedContentReusesImmutableSnapshotAndRevision() {
        TacticalMapStateSnapshot first =
            EspetroAPI.getTacticalMapStateSnapshot();
        TacticalMapStateSnapshot second =
            EspetroAPI.getTacticalMapStateSnapshot();

        assertSame(first, second);
        assertTrue(first.revision() > 0L);
        assertThrows(UnsupportedOperationException.class,
            () -> first.structures().clear());
        assertThrows(UnsupportedOperationException.class,
            () -> first.vehicleSupplyStations().clear());
    }

    @Test
    void dirtyMarkRebuildsOnceAndAdvancesRevision() {
        TacticalMapStateSnapshot before =
            EspetroAPI.getTacticalMapStateSnapshot();

        EspetroAPI.markTacticalMapStateDirty();

        TacticalMapStateSnapshot rebuilt =
            EspetroAPI.getTacticalMapStateSnapshot();
        TacticalMapStateSnapshot unchanged =
            EspetroAPI.getTacticalMapStateSnapshot();

        assertNotSame(before, rebuilt);
        assertTrue(rebuilt.revision() > before.revision());
        assertSame(rebuilt, unchanged);
    }
}
