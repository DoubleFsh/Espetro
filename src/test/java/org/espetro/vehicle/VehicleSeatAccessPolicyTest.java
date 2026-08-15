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
        "0,0,false", "1,0,true", "1,1,false",
        "2,0,true", "2,1,true", "2,2,false",
        "3,0,true", "3,2,true", "3,3,false"
    })
    void configuredCountRestrictsThatManyLeadingSeats(
            int requiredSeatCount, int seat, boolean restricted) {
        if (restricted) {
            assertTrue(VehicleSeatAccessPolicy.requiresVehicleCrew(requiredSeatCount, seat));
        } else {
            assertFalse(VehicleSeatAccessPolicy.requiresVehicleCrew(requiredSeatCount, seat));
        }
    }

    @Test
    void missingFieldKeepsLegacyVehicleTypeDefaults() {
        assertEquals(3, VehicleSeatAccessPolicy.legacyVehicleCrewSeatCount(
            SbwVehicleSeatResolver.Kind.TANK));
        assertEquals(2, VehicleSeatAccessPolicy.legacyVehicleCrewSeatCount(
            SbwVehicleSeatResolver.Kind.IFV));
        assertEquals(1, VehicleSeatAccessPolicy.legacyVehicleCrewSeatCount(
            SbwVehicleSeatResolver.Kind.HELICOPTER));
        assertEquals(0, VehicleSeatAccessPolicy.legacyVehicleCrewSeatCount(
            SbwVehicleSeatResolver.Kind.OTHER));
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
            3,
            Arrays.asList(null, null, null, null)));
        assertEquals(4, VehicleSeatAccessPolicy.firstAvailableUnrestrictedSeat(
            3,
            Arrays.asList(null, null, null, occupied, null)));
        assertEquals(2, VehicleSeatAccessPolicy.firstAvailableUnrestrictedSeat(
            2,
            Arrays.asList(null, null, null)));
        assertEquals(1, VehicleSeatAccessPolicy.firstAvailableUnrestrictedSeat(
            1,
            Arrays.asList(null, null)));
    }

    @Test
    void nonCrewMountIsRejectedWhenOnlyRestrictedOrOccupiedSeatsRemain() {
        Object occupied = new Object();

        assertEquals(-1, VehicleSeatAccessPolicy.firstAvailableUnrestrictedSeat(
            3,
            Arrays.asList(null, null, null)));
        assertEquals(-1, VehicleSeatAccessPolicy.firstAvailableUnrestrictedSeat(
            2,
            Arrays.asList(null, null, occupied)));
        assertEquals(-1, VehicleSeatAccessPolicy.firstAvailableUnrestrictedSeat(
            1,
            Arrays.asList(null, occupied)));
    }
}
