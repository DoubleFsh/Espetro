package org.espetro.bastion;

import java.util.Objects;

/** Pure placement geometry shared by Radio validation and tests. */
public final class RadioCoveragePolicy {

    private RadioCoveragePolicy() {
    }

    /**
     * The configured exclusion radius may be stricter, but it may never permit
     * two build-radius spheres to intersect.
     */
    public static double minimumCenterDistance(double buildRadius, double exclusionRadius) {
        double safeBuildRadius = Math.max(0.0, buildRadius);
        double safeExclusionRadius = Math.max(0.0, exclusionRadius);
        return Math.max(safeExclusionRadius, safeBuildRadius * 2.0);
    }

    /** Tangent ranges are allowed; only a strict intersection is rejected. */
    public static boolean overlaps(double distanceSquared, double minimumCenterDistance) {
        if (distanceSquared < 0.0 || minimumCenterDistance <= 0.0) {
            return false;
        }
        return distanceSquared < minimumCenterDistance * minimumCenterDistance;
    }

    /** Only a Radio belonging to the same faction may reserve placement space. */
    public static boolean blocksPlacement(String existingTeam, String placingTeam,
                                          double distanceSquared, double minimumCenterDistance) {
        return Objects.equals(existingTeam, placingTeam)
            && overlaps(distanceSquared, minimumCenterDistance);
    }
}
