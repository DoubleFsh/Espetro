package org.espetro.network;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaczGunPackSyncChunkPacketTest {

    @Test
    void maximumChunkRoundTripsBelowVanillaPayloadLimit() {
        byte[] chunk = new byte[TaczGunPackSyncChunkPacket.MAX_CHUNK_BYTES];
        Arrays.fill(chunk, (byte) 0x5a);
        TaczGunPackSyncChunkPacket original = new TaczGunPackSyncChunkPacket(
            UUID.fromString("2c8a3a7b-f890-4c5a-b621-d6d694f068a7"),
            1, 3, 1_750_000, 0x1020304050607080L, chunk);
        FriendlyByteBuf first = new FriendlyByteBuf(Unpooled.buffer());
        FriendlyByteBuf second = new FriendlyByteBuf(Unpooled.buffer());
        try {
            original.write(first);
            assertTrue(first.readableBytes() < 1_048_576);

            TaczGunPackSyncChunkPacket decoded = TaczGunPackSyncChunkPacket.read(first);
            decoded.write(second);

            byte[] expected = new byte[second.readableBytes()];
            second.getBytes(second.readerIndex(), expected);
            byte[] actual = new byte[first.writerIndex()];
            first.getBytes(0, actual);
            assertArrayEquals(actual, expected);
        } finally {
            first.release();
            second.release();
        }
    }
}
