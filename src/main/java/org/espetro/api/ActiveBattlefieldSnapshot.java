package org.espetro.api;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/**
 * Immutable public view of the active disposable battlefield and its
 * per-map extension configuration.
 */
public final class ActiveBattlefieldSnapshot {
    private final String mapId;
    private final String displayName;
    private final ResourceKey<Level> dimension;
    private final String tacticalMapJson;
    private final String capturePointsJson;
    private final String backgroundImage;
    private final byte[] backgroundBytes;
    private final String backgroundSha256;
    private final int backgroundWidth;
    private final int backgroundHeight;
    private final String objectiveMode;
    private final String objectiveLane;
    private final long objectiveSeed;

    public ActiveBattlefieldSnapshot(String mapId, String displayName,
                                     ResourceKey<Level> dimension,
                                     String tacticalMapJson, String capturePointsJson,
                                     String backgroundImage, byte[] backgroundBytes) {
        this(mapId, displayName, dimension, tacticalMapJson, capturePointsJson,
            backgroundImage, backgroundBytes, "", 0, 0, "", "", 0L);
    }

    public ActiveBattlefieldSnapshot(String mapId, String displayName,
                                     ResourceKey<Level> dimension,
                                     String tacticalMapJson, String capturePointsJson,
                                     String backgroundImage, byte[] backgroundBytes,
                                     String backgroundSha256,
                                     int backgroundWidth, int backgroundHeight) {
        this(mapId, displayName, dimension, tacticalMapJson, capturePointsJson,
            backgroundImage, backgroundBytes, backgroundSha256,
            backgroundWidth, backgroundHeight, "", "", 0L);
    }

    public ActiveBattlefieldSnapshot(String mapId, String displayName,
                                     ResourceKey<Level> dimension,
                                     String tacticalMapJson, String capturePointsJson,
                                     String backgroundImage, byte[] backgroundBytes,
                                     String backgroundSha256,
                                     int backgroundWidth, int backgroundHeight,
                                     String objectiveMode, String objectiveLane,
                                     long objectiveSeed) {
        this.mapId = mapId;
        this.displayName = displayName;
        this.dimension = dimension;
        this.tacticalMapJson = tacticalMapJson;
        this.capturePointsJson = capturePointsJson;
        this.backgroundImage = backgroundImage == null ? "" : backgroundImage;
        this.backgroundBytes = backgroundBytes == null ? new byte[0] : backgroundBytes.clone();
        this.backgroundSha256 = backgroundSha256 == null ? "" : backgroundSha256;
        this.backgroundWidth = Math.max(0, backgroundWidth);
        this.backgroundHeight = Math.max(0, backgroundHeight);
        this.objectiveMode = objectiveMode == null ? "" : objectiveMode;
        this.objectiveLane = objectiveLane == null ? "" : objectiveLane;
        this.objectiveSeed = objectiveSeed;
    }

    public String mapId() {
        return mapId;
    }

    public String displayName() {
        return displayName;
    }

    public ResourceKey<Level> dimension() {
        return dimension;
    }

    public String tacticalMapJson() {
        return tacticalMapJson;
    }

    public String capturePointsJson() {
        return capturePointsJson;
    }

    public String backgroundImage() {
        return backgroundImage;
    }

    public byte[] backgroundBytes() {
        return backgroundBytes.clone();
    }

    public String backgroundSha256() {
        return backgroundSha256;
    }

    public int backgroundWidth() {
        return backgroundWidth;
    }

    public int backgroundHeight() {
        return backgroundHeight;
    }

    public String objectiveMode() {
        return objectiveMode;
    }

    public String objectiveLane() {
        return objectiveLane;
    }

    public long objectiveSeed() {
        return objectiveSeed;
    }
}
