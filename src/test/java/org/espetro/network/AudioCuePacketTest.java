package org.espetro.network;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AudioCuePacketTest {

    @Test
    void roundTripsCueAndClientLocalPackIndex() {
        AudioCuePacket original = new AudioCuePacket(
            AudioCuePacket.Cue.VICTORY_EASTER_EGG, "modern_russia");
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            original.write(buffer);
            AudioCuePacket decoded = AudioCuePacket.read(buffer);

            assertEquals(AudioCuePacket.Cue.VICTORY_EASTER_EGG, decoded.getCue());
            assertEquals("modern_russia", decoded.getAudioPack());
        } finally {
            buffer.release();
        }
    }

    @Test
    void roundTripsBothNeutralizationCues() {
        assertRoundTrip(AudioCuePacket.Cue.CAPTURING_POINT);
        assertRoundTrip(AudioCuePacket.Cue.LOSING_POINT);
    }

    private static void assertRoundTrip(AudioCuePacket.Cue cue) {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            new AudioCuePacket(cue, "neutral_test").write(buffer);
            AudioCuePacket decoded = AudioCuePacket.read(buffer);
            assertEquals(cue, decoded.getCue());
            assertEquals("neutral_test", decoded.getAudioPack());
        } finally {
            buffer.release();
        }
    }
}
