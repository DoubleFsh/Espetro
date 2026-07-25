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

    public ActiveBattlefieldSnapshot(String mapId, String displayName,
                                     ResourceKey<Level> dimension,
                                     String tacticalMapJson, String capturePointsJson,
                                     String backgroundImage, byte[] backgroundBytes) {
        this.mapId = mapId;
        this.displayName = displayName;
        this.dimension = dimension;
        this.tacticalMapJson = tacticalMapJson;
        this.capturePointsJson = capturePointsJson;
        this.backgroundImage = backgroundImage == null ? "" : backgroundImage;
        this.backgroundBytes = backgroundBytes == null ? new byte[0] : backgroundBytes.clone();
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
}
