package org.espetro.network;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MapVoteStatePacketTest {

    @Test
    void roundTripsCandidateMetadataWithoutPreviewPayload() {
        MapVoteStatePacket original = new MapVoteStatePacket(
            true,
            25,
            500L,
            List.of(new MapVoteStatePacket.Candidate(
                "test_flat", "Test Flat", "espetro:test_flat")),
            Map.of("test_flat", 2),
            "test_flat",
            null,
            null
        );
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            original.write(buffer);
            MapVoteStatePacket decoded = MapVoteStatePacket.read(buffer);

            assertTrue(decoded.active);
            assertEquals(25, decoded.remainingSeconds);
            assertEquals(500L, decoded.endGameTime);
            assertEquals(1, decoded.candidates.size());
            assertEquals("test_flat", decoded.candidates.get(0).mapFolder);
            assertEquals("Test Flat", decoded.candidates.get(0).displayName);
            assertEquals("espetro:test_flat", decoded.candidates.get(0).dimensionId);
            assertEquals(2, decoded.tally.get("test_flat"));
            assertEquals("test_flat", decoded.myVoteMapFolder);
            assertNull(decoded.winnerMapFolder);
            assertNull(decoded.winnerDisplayName);
        } finally {
            buffer.release();
        }
    }
}
