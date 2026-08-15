package org.espetro.bastion;

import net.minecraft.world.phys.AABB;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FortificationSpatialIndexTest {

    @Test
    void returnsAllIntersectingLargeStructuresWithoutScanningOtherDimensions() {
        FortificationSpatialIndex index = new FortificationSpatialIndex();
        UUID large = UUID.randomUUID();
        UUID adjacent = UUID.randomUUID();
        UUID otherDimension = UUID.randomUUID();
        index.put(large, "espetro:test", new AABB(0, 60, 0, 48, 70, 48));
        index.put(adjacent, "espetro:test", new AABB(47, 60, 47, 50, 64, 50));
        index.put(otherDimension, "minecraft:overworld", new AABB(0, 60, 0, 48, 70, 48));

        var hits = index.query("espetro:test", new AABB(46, 59, 46, 49, 71, 49));
        assertEquals(2, hits.size());
        assertTrue(hits.contains(large));
        assertTrue(hits.contains(adjacent));
        index.remove(large);
        // The adjacent entry and the deliberately isolated other-dimension
        // entry remain registered; size() is global, while query is scoped.
        assertEquals(2, index.size());
    }
}
