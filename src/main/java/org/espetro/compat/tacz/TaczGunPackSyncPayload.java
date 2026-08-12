package org.espetro.compat.tacz;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Binary representation used to move TaCZ's common resource cache in bounded chunks. */
public final class TaczGunPackSyncPayload {
    private static final int FORMAT_VERSION = 1;
    private static final int MAX_TYPES = 64;
    private static final int MAX_ENTRIES_PER_TYPE = 1_000_000;
    private static final int MAX_STRING_BYTES = 16 * 1024 * 1024;

    private TaczGunPackSyncPayload() {
    }

    public static byte[] encode(Map<String, ? extends Map<String, String>> cache) throws IOException {
        Map<String, ? extends Map<String, String>> safeCache = cache == null
            ? Collections.emptyMap()
            : cache;
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeInt(FORMAT_VERSION);
            output.writeInt(safeCache.size());
            for (Map.Entry<String, ? extends Map<String, String>> typeEntry : safeCache.entrySet()) {
                writeString(output, typeEntry.getKey());
                Map<String, String> entries = typeEntry.getValue() == null
                    ? Collections.emptyMap()
                    : typeEntry.getValue();
                output.writeInt(entries.size());
                for (Map.Entry<String, String> entry : entries.entrySet()) {
                    writeString(output, entry.getKey());
                    writeString(output, entry.getValue());
                }
            }
        }
        return bytes.toByteArray();
    }

    public static Map<String, Map<String, String>> decode(byte[] payload) throws IOException {
        if (payload == null) {
            throw new IOException("Missing TaCZ sync payload");
        }
        Map<String, Map<String, String>> cache = new LinkedHashMap<>();
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(payload))) {
            int version = input.readInt();
            if (version != FORMAT_VERSION) {
                throw new IOException("Unsupported TaCZ sync payload version: " + version);
            }
            int typeCount = readBoundedCount(input, MAX_TYPES, "data type");
            for (int typeIndex = 0; typeIndex < typeCount; typeIndex++) {
                String type = readString(input);
                int entryCount = readBoundedCount(input, MAX_ENTRIES_PER_TYPE, "cache entry");
                Map<String, String> entries = new LinkedHashMap<>();
                for (int entryIndex = 0; entryIndex < entryCount; entryIndex++) {
                    entries.put(readString(input), readString(input));
                }
                cache.put(type, entries);
            }
            if (input.read() != -1) {
                throw new IOException("Trailing bytes in TaCZ sync payload");
            }
        }
        return cache;
    }

    public static List<byte[]> split(byte[] payload, int maxChunkBytes) {
        if (payload == null) {
            throw new IllegalArgumentException("payload must not be null");
        }
        if (maxChunkBytes <= 0) {
            throw new IllegalArgumentException("maxChunkBytes must be positive");
        }
        int chunkCount = Math.max(1, (payload.length + maxChunkBytes - 1) / maxChunkBytes);
        List<byte[]> chunks = new ArrayList<>(chunkCount);
        for (int offset = 0; offset < payload.length; offset += maxChunkBytes) {
            int length = Math.min(maxChunkBytes, payload.length - offset);
            byte[] chunk = new byte[length];
            System.arraycopy(payload, offset, chunk, 0, length);
            chunks.add(chunk);
        }
        if (payload.length == 0) {
            chunks.add(new byte[0]);
        }
        return chunks;
    }

    public static byte[] join(List<byte[]> chunks, int expectedLength) throws IOException {
        if (chunks == null || chunks.isEmpty()) {
            throw new IOException("Missing TaCZ sync chunks");
        }
        if (expectedLength < 0) {
            throw new IOException("Invalid TaCZ sync payload length: " + expectedLength);
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream(expectedLength);
        for (byte[] chunk : chunks) {
            if (chunk == null) {
                throw new IOException("Incomplete TaCZ sync payload");
            }
            output.write(chunk);
        }
        byte[] payload = output.toByteArray();
        if (payload.length != expectedLength) {
            throw new IOException("TaCZ sync payload length mismatch: expected "
                + expectedLength + ", received " + payload.length);
        }
        return payload;
    }

    private static void writeString(DataOutputStream output, String value) throws IOException {
        byte[] bytes = (value == null ? "" : value).getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_STRING_BYTES) {
            throw new IOException("TaCZ sync string is too large: " + bytes.length + " bytes");
        }
        output.writeInt(bytes.length);
        output.write(bytes);
    }

    private static String readString(DataInputStream input) throws IOException {
        int length = input.readInt();
        if (length < 0 || length > MAX_STRING_BYTES) {
            throw new IOException("Invalid TaCZ sync string length: " + length);
        }
        byte[] bytes = new byte[length];
        input.readFully(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static int readBoundedCount(DataInputStream input, int maximum, String label) throws IOException {
        int count;
        try {
            count = input.readInt();
        } catch (EOFException e) {
            throw new IOException("Truncated TaCZ sync payload", e);
        }
        if (count < 0 || count > maximum) {
            throw new IOException("Invalid TaCZ " + label + " count: " + count);
        }
        return count;
    }
}
