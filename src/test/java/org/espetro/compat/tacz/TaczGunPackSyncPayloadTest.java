package org.espetro.compat.tacz;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TaczGunPackSyncPayloadTest {

    @Test
    void roundTripsUnicodeResourceCache() throws IOException {
        Map<String, Map<String, String>> cache = new LinkedHashMap<>();
        cache.put("GUN_INDEX", new LinkedHashMap<>(Map.of(
            "murasamet:ak_74m", "{\"name\":\"测试步枪\"}",
            "tacz:m4a1", "{\"type\":\"rifle\"}"
        )));
        cache.put("AMMO_INDEX", new LinkedHashMap<>(Map.of(
            "tacz:556x45", "{\"damage\":7}"
        )));

        byte[] encoded = TaczGunPackSyncPayload.encode(cache);

        assertEquals(cache, TaczGunPackSyncPayload.decode(encoded));
    }

    @Test
    void splitsAndReassemblesAtExactByteBoundaries() throws IOException {
        byte[] payload = "0123456789中文abcdefghij".getBytes(StandardCharsets.UTF_8);
        List<byte[]> chunks = TaczGunPackSyncPayload.split(payload, 7);

        assertEquals((payload.length + 6) / 7, chunks.size());
        assertArrayEquals(payload, TaczGunPackSyncPayload.join(chunks, payload.length));
    }

    @Test
    void rejectsIncompleteTransfer() {
        List<byte[]> incomplete = java.util.Arrays.asList(new byte[] {1, 2}, null);

        assertThrows(IOException.class,
            () -> TaczGunPackSyncPayload.join(incomplete, 4));
    }
}
