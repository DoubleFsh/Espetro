package org.espetro.vehicle;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Round-scoped idempotency ledger for the first vehicle wave.
 *
 * <p>A configured entity occupies one ordered map slot. Re-entering a phase
 * hook or preparing the same faction twice must never create a second vehicle
 * in that slot.</p>
 */
final class InitialVehicleDeploymentLedger {

    record SlotKey(String factionId, String vehicleType, int slotIndex, String team) {
        SlotKey {
            factionId = factionId == null ? "" : factionId;
            vehicleType = vehicleType == null ? "" : vehicleType;
            team = team == null ? "" : team.trim().toUpperCase(Locale.ROOT);
        }
    }

    private final Set<SlotKey> scheduled = new HashSet<>();

    boolean claim(SlotKey key) {
        return key != null && scheduled.add(key);
    }

    int size() {
        return scheduled.size();
    }

    void clear() {
        scheduled.clear();
    }
}
