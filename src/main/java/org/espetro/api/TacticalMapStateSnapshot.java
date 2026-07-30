package org.espetro.api;

import java.util.List;
import java.util.UUID;

/**
 * Immutable, level-reference-free tactical state. The revision changes only
 * when content changes (or a new battlefield session starts).
 */
public record TacticalMapStateSnapshot(
    long revision,
    long battlefieldSession,
    List<EspetroAPI.FobSnapshot> structures,
    List<RallySnapshot> rallies,
    List<TeamBaseSnapshot> teamBases,
    List<PlayerDeployPointSnapshot> playerDeployPoints,
    List<VehicleSupplyStationSnapshot> vehicleSupplyStations
) {
    public TacticalMapStateSnapshot {
        structures = List.copyOf(structures);
        rallies = List.copyOf(rallies);
        teamBases = List.copyOf(teamBases);
        playerDeployPoints = List.copyOf(playerDeployPoints);
        vehicleSupplyStations = List.copyOf(vehicleSupplyStations);
    }

    public record RallySnapshot(UUID id, String team, int squadId, String dimension,
                                int x, int y, int z, long nextWaveAtMillis) {
        public long nextWaveSeconds() {
            return Math.max(0L,
                (nextWaveAtMillis - System.currentTimeMillis() + 999L) / 1000L);
        }
    }

    public record TeamBaseSnapshot(String team, String name, String dimension,
                                   int x, int y, int z, float yaw) {
    }

    public record PlayerDeployPointSnapshot(UUID playerId, String dimension,
                                            int x, int y, int z) {
    }

    public record VehicleSupplyStationSnapshot(UUID id, String name, String team,
                                               String dimension, int x, int y, int z) {
    }
}
