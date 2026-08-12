package org.espetro.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;
import org.espetro.audio.AudioPackId;

import java.util.function.Supplier;

/** Server-to-client cue for client-local formation audio. No audio bytes cross the network. */
public final class AudioCuePacket {
    public enum Cue {
        STOP(0),
        ENTRY_ATTACK(1),
        ENTRY_DEFEND(2),
        CAPTURED(3),
        LOST(4),
        VICTORY(5),
        VICTORY_EASTER_EGG(6),
        DEFEAT(7),
        /** The opposing side has neutralized a point and is beginning to raise it. */
        CAPTURING_POINT(8),
        /** This side's point has just been neutralized. */
        LOSING_POINT(9);

        private final int id;

        Cue(int id) {
            this.id = id;
        }

        private static Cue fromId(int id) {
            for (Cue cue : values()) {
                if (cue.id == id) return cue;
            }
            return STOP;
        }
    }

    private final Cue cue;
    private final String audioPack;

    public AudioCuePacket(Cue cue, String audioPack) {
        this.cue = cue == null ? Cue.STOP : cue;
        String normalized = AudioPackId.normalize(audioPack);
        this.audioPack = normalized == null ? "" : normalized;
    }

    public Cue getCue() {
        return cue;
    }

    public String getAudioPack() {
        return audioPack;
    }

    public static AudioCuePacket read(FriendlyByteBuf buf) {
        return new AudioCuePacket(Cue.fromId(buf.readUnsignedByte()),
            buf.readUtf(AudioPackId.MAX_LENGTH));
    }

    public void write(FriendlyByteBuf buf) {
        buf.writeByte(cue.id);
        buf.writeUtf(audioPack, AudioPackId.MAX_LENGTH);
    }

    public void handle(Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> {
            try {
                Class.forName("org.espetro.client.ClientPacketHandlers")
                    .getMethod("handleAudioCue", AudioCuePacket.class)
                    .invoke(null, this);
            } catch (ReflectiveOperationException ignored) {
            }
        });
        context.setPacketHandled(true);
    }
}
