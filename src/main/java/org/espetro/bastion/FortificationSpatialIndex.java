package org.espetro.bastion;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Dimension + chunk broad-phase index for completed and pending fortifications. */
final class FortificationSpatialIndex {

    private final Map<Key, Set<UUID>> chunks = new HashMap<>();
    private final Map<UUID, Entry> entries = new HashMap<>();

    void clear() {
        chunks.clear();
        entries.clear();
    }

    void put(UUID id, String dimension, AABB box) {
        remove(id);
        Entry entry = new Entry(dimension, box, coveredChunks(box));
        entries.put(id, entry);
        for (long chunk : entry.chunks) {
            chunks.computeIfAbsent(new Key(dimension, chunk), ignored -> new HashSet<>()).add(id);
        }
    }

    void remove(UUID id) {
        Entry previous = entries.remove(id);
        if (previous == null) return;
        for (long chunk : previous.chunks) {
            Key key = new Key(previous.dimension, chunk);
            Set<UUID> bucket = chunks.get(key);
            if (bucket == null) continue;
            bucket.remove(id);
            if (bucket.isEmpty()) chunks.remove(key);
        }
    }

    List<UUID> query(String dimension, AABB bounds) {
        Set<UUID> result = new HashSet<>();
        for (long chunk : coveredChunks(bounds)) {
            Set<UUID> bucket = chunks.get(new Key(dimension, chunk));
            if (bucket == null) continue;
            for (UUID id : bucket) {
                Entry entry = entries.get(id);
                if (entry != null && entry.box.intersects(bounds)) result.add(id);
            }
        }
        return List.copyOf(result);
    }

    int size() {
        return entries.size();
    }

    private static List<Long> coveredChunks(AABB box) {
        int minX = new ChunkPos(BlockPos.containing(box.minX, box.minY, box.minZ)).x;
        int maxX = new ChunkPos(BlockPos.containing(Math.nextDown(box.maxX), box.maxY,
            box.maxZ)).x;
        int minZ = new ChunkPos(BlockPos.containing(box.minX, box.minY, box.minZ)).z;
        int maxZ = new ChunkPos(BlockPos.containing(box.maxX, box.maxY,
            Math.nextDown(box.maxZ))).z;
        List<Long> result = new ArrayList<>((maxX - minX + 1) * (maxZ - minZ + 1));
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) result.add(ChunkPos.asLong(x, z));
        }
        return result;
    }

    private record Key(String dimension, long chunk) {
    }

    private record Entry(String dimension, AABB box, List<Long> chunks) {
    }
}
