package org.espetro.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkEvent;
import org.espetro.Espetro;
import org.espetro.compat.tacz.TaczGunPackSyncPayload;

import java.io.IOException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.zip.CRC32;

/** One bounded fragment of TaCZ's common gun-pack cache. */
public final class TaczGunPackSyncChunkPacket {
    public static final int MAX_CHUNK_BYTES = 700_000;
    private static final int MAX_TOTAL_BYTES = 64 * 1024 * 1024;
    private static final int MAX_CHUNKS = 1024;
    private static final long ASSEMBLY_TIMEOUT_MILLIS = 60_000L;
    private static final Map<UUID, Assembly> INCOMING = new HashMap<>();

    private final UUID transferId;
    private final int chunkIndex;
    private final int chunkCount;
    private final int totalBytes;
    private final long checksum;
    private final byte[] chunk;

    public TaczGunPackSyncChunkPacket(UUID transferId, int chunkIndex, int chunkCount,
                                      int totalBytes, long checksum, byte[] chunk) {
        this.transferId = transferId;
        this.chunkIndex = chunkIndex;
        this.chunkCount = chunkCount;
        this.totalBytes = totalBytes;
        this.checksum = checksum;
        this.chunk = chunk == null ? new byte[0] : Arrays.copyOf(chunk, chunk.length);
    }

    public void write(FriendlyByteBuf buffer) {
        buffer.writeUUID(transferId);
        buffer.writeVarInt(chunkIndex);
        buffer.writeVarInt(chunkCount);
        buffer.writeVarInt(totalBytes);
        buffer.writeLong(checksum);
        buffer.writeByteArray(chunk);
    }

    public static TaczGunPackSyncChunkPacket read(FriendlyByteBuf buffer) {
        UUID transferId = buffer.readUUID();
        int chunkIndex = buffer.readVarInt();
        int chunkCount = buffer.readVarInt();
        int totalBytes = buffer.readVarInt();
        long checksum = buffer.readLong();
        byte[] chunk = buffer.readByteArray(MAX_CHUNK_BYTES);
        return new TaczGunPackSyncChunkPacket(
            transferId, chunkIndex, chunkCount, totalBytes, checksum, chunk);
    }

    public void handle(Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        if (!context.getDirection().getReceptionSide().isClient()) {
            context.setPacketHandled(true);
            return;
        }
        boolean memoryConnection = context.getNetworkManager() != null
            && context.getNetworkManager().isMemoryConnection();
        context.enqueueWork(() -> accept(memoryConnection));
        context.setPacketHandled(true);
    }

    private void accept(boolean memoryConnection) {
        try {
            validateMetadata();
            discardExpiredAssemblies();
            Assembly assembly = INCOMING.computeIfAbsent(transferId,
                ignored -> new Assembly(chunkCount, totalBytes, checksum));
            if (!assembly.matches(chunkCount, totalBytes, checksum)) {
                INCOMING.remove(transferId);
                throw new IOException("Conflicting TaCZ sync chunk metadata");
            }
            assembly.add(chunkIndex, chunk);
            if (!assembly.complete()) {
                return;
            }
            INCOMING.remove(transferId);
            byte[] payload = TaczGunPackSyncPayload.join(assembly.orderedChunks(), totalBytes);
            CRC32 crc = new CRC32();
            crc.update(payload);
            if (crc.getValue() != checksum) {
                throw new IOException("TaCZ sync payload checksum mismatch");
            }
            Map<String, Map<String, String>> cache = TaczGunPackSyncPayload.decode(payload);
            applyToTacz(cache, memoryConnection);
            Espetro.LOGGER.info("Applied chunked TaCZ gun-pack cache: {} bytes in {} chunk(s)",
                totalBytes, chunkCount);
        } catch (ReflectiveOperationException | IOException | RuntimeException e) {
            INCOMING.remove(transferId);
            Espetro.LOGGER.error("Failed to apply chunked TaCZ gun-pack synchronization", e);
        }
    }

    private void validateMetadata() throws IOException {
        if (transferId == null) {
            throw new IOException("Missing TaCZ sync transfer id");
        }
        if (chunkCount <= 0 || chunkCount > MAX_CHUNKS) {
            throw new IOException("Invalid TaCZ sync chunk count: " + chunkCount);
        }
        if (chunkIndex < 0 || chunkIndex >= chunkCount) {
            throw new IOException("Invalid TaCZ sync chunk index: " + chunkIndex);
        }
        if (totalBytes < 0 || totalBytes > MAX_TOTAL_BYTES) {
            throw new IOException("Invalid TaCZ sync payload size: " + totalBytes);
        }
        if (chunk.length > MAX_CHUNK_BYTES) {
            throw new IOException("TaCZ sync chunk exceeds safe packet size: " + chunk.length);
        }
    }

    private static void discardExpiredAssemblies() {
        long cutoff = System.currentTimeMillis() - ASSEMBLY_TIMEOUT_MILLIS;
        INCOMING.entrySet().removeIf(entry -> entry.getValue().createdAtMillis < cutoff);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void applyToTacz(Map<String, Map<String, String>> normalized,
                                    boolean memoryConnection)
        throws ReflectiveOperationException {
        Class<?> dataTypeClass = Class.forName("com.tacz.guns.resource.network.DataType");
        Map<Object, Map<ResourceLocation, String>> cache = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, String>> typeEntry : normalized.entrySet()) {
            Object dataType = Enum.valueOf((Class<? extends Enum>) dataTypeClass, typeEntry.getKey());
            Map<ResourceLocation, String> entries = new LinkedHashMap<>();
            for (Map.Entry<String, String> entry : typeEntry.getValue().entrySet()) {
                ResourceLocation id = ResourceLocation.tryParse(entry.getKey());
                if (id == null) {
                    throw new IllegalArgumentException("Invalid TaCZ resource id: " + entry.getKey());
                }
                entries.put(id, entry.getValue());
            }
            cache.put(dataType, entries);
        }

        Class<?> commonAssetsClass = Class.forName("com.tacz.guns.resource.CommonAssetsManager");
        if (!memoryConnection) {
            commonAssetsClass.getMethod("clearInstance").invoke(null);
        }
        Class<?> networkCacheClass = Class.forName(
            "com.tacz.guns.resource.network.CommonNetworkCache");
        Object networkCache = networkCacheClass.getField("INSTANCE").get(null);
        Method fromNetwork = networkCacheClass.getMethod("fromNetwork", Map.class);
        fromNetwork.invoke(networkCache, cache);

        Class<?> clientIndexClass = Class.forName("com.tacz.guns.client.resource.ClientIndexManager");
        clientIndexClass.getMethod("reload").invoke(null);
    }

    private static final class Assembly {
        private final int totalBytes;
        private final long checksum;
        private final byte[][] chunks;
        private final long createdAtMillis = System.currentTimeMillis();
        private int received;

        private Assembly(int chunkCount, int totalBytes, long checksum) {
            this.totalBytes = totalBytes;
            this.checksum = checksum;
            this.chunks = new byte[chunkCount][];
        }

        private boolean matches(int chunkCount, int candidateTotalBytes, long candidateChecksum) {
            return chunks.length == chunkCount
                && totalBytes == candidateTotalBytes
                && checksum == candidateChecksum;
        }

        private void add(int index, byte[] value) {
            if (chunks[index] == null) {
                chunks[index] = Arrays.copyOf(value, value.length);
                received++;
            }
        }

        private boolean complete() {
            return received == chunks.length;
        }

        private List<byte[]> orderedChunks() {
            return new ArrayList<>(Arrays.asList(chunks));
        }
    }
}
