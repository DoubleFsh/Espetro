package org.espetro.vehicle;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VehicleSeatAccessPolicyTest {

    @ParameterizedTest
    @CsvSource({
        "TANK,0,true", "TANK,1,true", "TANK,2,true", "TANK,3,false",
        "IFV,0,true", "IFV,1,true", "IFV,2,false",
        "HELICOPTER,0,true", "HELICOPTER,1,false",
        "OTHER,0,false", "OTHER,1,false"
    })
    void restrictedSeatMatrixMatchesConfiguredVehicleRules(
            SbwVehicleSeatResolver.Kind kind, int seat, boolean restricted) {
        if (restricted) {
            assertTrue(VehicleSeatAccessPolicy.requiresVehicleCrew(kind, seat));
        } else {
            assertFalse(VehicleSeatAccessPolicy.requiresVehicleCrew(kind, seat));
        }
    }

    @Test
    void explicitCrewFlagOverridesLegacyIconFallback() {
        assertTrue(VehicleSeatAccessPolicy.resolvesVehicleCrew(true, "rifleman"));
        assertFalse(VehicleSeatAccessPolicy.resolvesVehicleCrew(false, "crewman"));
        assertTrue(VehicleSeatAccessPolicy.resolvesVehicleCrew(null, "crewman"));
        assertTrue(VehicleSeatAccessPolicy.resolvesVehicleCrew(null, " CREWMAN "));
        assertFalse(VehicleSeatAccessPolicy.resolvesVehicleCrew(null, "rifleman"));
        assertFalse(VehicleSeatAccessPolicy.resolvesVehicleCrew(null, null));
    }

    @Test
    void nonCrewMountUsesFirstEmptyUnrestrictedSeat() {
        Object occupied = new Object();

        assertEquals(3, VehicleSeatAccessPolicy.firstAvailableUnrestrictedSeat(
            SbwVehicleSeatResolver.Kind.TANK,
            Arrays.asList(null, null, null, null)));
        assertEquals(4, VehicleSeatAccessPolicy.firstAvailableUnrestrictedSeat(
            SbwVehicleSeatResolver.Kind.TANK,
            Arrays.asList(null, null, null, occupied, null)));
        assertEquals(2, VehicleSeatAccessPolicy.firstAvailableUnrestrictedSeat(
            SbwVehicleSeatResolver.Kind.IFV,
            Arrays.asList(null, null, null)));
        assertEquals(1, VehicleSeatAccessPolicy.firstAvailableUnrestrictedSeat(
            SbwVehicleSeatResolver.Kind.HELICOPTER,
            Arrays.asList(null, null)));
    }

    @Test
    void nonCrewMountIsRejectedWhenOnlyRestrictedOrOccupiedSeatsRemain() {
        Object occupied = new Object();

        assertEquals(-1, VehicleSeatAccessPolicy.firstAvailableUnrestrictedSeat(
            SbwVehicleSeatResolver.Kind.TANK,
            Arrays.asList(null, null, null)));
        assertEquals(-1, VehicleSeatAccessPolicy.firstAvailableUnrestrictedSeat(
            SbwVehicleSeatResolver.Kind.IFV,
            Arrays.asList(null, null, occupied)));
        assertEquals(-1, VehicleSeatAccessPolicy.firstAvailableUnrestrictedSeat(
            SbwVehicleSeatResolver.Kind.HELICOPTER,
            Arrays.asList(null, occupied)));
    }
}
