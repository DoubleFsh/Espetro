package org.espetro.compat.tacz;

import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.OnDatapackSyncEvent;
import net.minecraftforge.network.PacketDistributor;
import org.espetro.Espetro;
import org.espetro.network.NetworkManager;
import org.espetro.network.TaczGunPackSyncChunkPacket;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.CRC32;

/** Optional TaCZ bridge. All TaCZ references stay reflective so the dependency remains soft. */
public final class TaczGunPackSyncCompat {
    private static final String COMMON_ASSETS_MANAGER = "com.tacz.guns.resource.CommonAssetsManager";
    private static final int MAX_CHUNK_BYTES = 700_000;

    private TaczGunPackSyncCompat() {
    }

    /**
     * Replaces TaCZ's single over-sized login packet. Returning {@code false} lets TaCZ use its
     * original implementation when this compatibility layer cannot inspect the cache.
     */
    public static boolean sendChunked(OnDatapackSyncEvent event) {
        PreparedSync prepared;
        try {
            prepared = prepare();
        } catch (ReflectiveOperationException | RuntimeException | java.io.IOException e) {
            Espetro.LOGGER.error("Unable to prepare chunked TaCZ gun-pack synchronization", e);
            return false;
        }

        List<ServerPlayer> recipients;
        if (event.getPlayer() != null) {
            recipients = Collections.singletonList(event.getPlayer());
        } else {
            recipients = new ArrayList<>(event.getPlayerList().getPlayers());
        }

        try {
            for (ServerPlayer player : recipients) {
                sendToPlayer(player, prepared);
            }
        } catch (RuntimeException e) {
            // Do not fall back after part of a transfer has been sent: doing so would recreate
            // the original >1 MiB login failure. The client discards an incomplete transfer.
            Espetro.LOGGER.error("Failed while sending chunked TaCZ gun-pack synchronization", e);
        }
        return true;
    }

    private static PreparedSync prepare()
        throws ReflectiveOperationException, java.io.IOException {
        Class<?> managerClass = Class.forName(COMMON_ASSETS_MANAGER);
        Method getInstance = managerClass.getMethod("getInstance");
        Object manager = getInstance.invoke(null);
        if (manager == null) {
            throw new IllegalStateException("TaCZ CommonAssetsManager is not initialized");
        }
        Method getNetworkCache = managerClass.getMethod("getNetworkCache");
        Object rawCache = getNetworkCache.invoke(manager);
        Map<String, Map<String, String>> cache = normalizeCache(rawCache);
        byte[] payload = TaczGunPackSyncPayload.encode(cache);
        List<byte[]> chunks = TaczGunPackSyncPayload.split(payload, MAX_CHUNK_BYTES);
        CRC32 crc = new CRC32();
        crc.update(payload);
        Espetro.LOGGER.info("TaCZ gun-pack cache prepared: {} bytes in {} chunk(s)",
            payload.length, chunks.size());
        return new PreparedSync(payload.length, crc.getValue(), chunks);
    }

    private static void sendToPlayer(ServerPlayer player, PreparedSync prepared) {
        UUID transferId = UUID.randomUUID();
        for (int index = 0; index < prepared.chunks().size(); index++) {
            TaczGunPackSyncChunkPacket packet = new TaczGunPackSyncChunkPacket(
                transferId,
                index,
                prepared.chunks().size(),
                prepared.payloadLength(),
                prepared.checksum(),
                prepared.chunks().get(index)
            );
            NetworkManager.NET.send(PacketDistributor.PLAYER.with(() -> player), packet);
        }
    }

    private static Map<String, Map<String, String>> normalizeCache(Object rawCache) {
        if (!(rawCache instanceof Map<?, ?> outer)) {
            throw new IllegalArgumentException("Unexpected TaCZ network cache type");
        }
        Map<String, Map<String, String>> normalized = new LinkedHashMap<>();
        for (Map.Entry<?, ?> typeEntry : outer.entrySet()) {
            Object typeKey = typeEntry.getKey();
            String typeName = typeKey instanceof Enum<?> enumKey
                ? enumKey.name()
                : String.valueOf(typeKey);
            if (!(typeEntry.getValue() instanceof Map<?, ?> inner)) {
                throw new IllegalArgumentException("Unexpected TaCZ cache entries for " + typeName);
            }
            Map<String, String> entries = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : inner.entrySet()) {
                entries.put(String.valueOf(entry.getKey()), String.valueOf(entry.getValue()));
            }
            normalized.put(typeName, entries);
        }
        return normalized;
    }

    private record PreparedSync(int payloadLength, long checksum, List<byte[]> chunks) {
    }
}
