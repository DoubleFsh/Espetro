package org.espetro.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** S→C round end result. */
public class RoundEndPacket {

    public final String winner; // ATTACK / DEFEND / DRAW / RESET
    public final int displaySeconds;

    public RoundEndPacket(String winner, int displaySeconds) {
        this.winner = winner == null ? "DRAW" : winner;
        this.displaySeconds = displaySeconds;
    }

    public static RoundEndPacket read(FriendlyByteBuf buf) {
        return new RoundEndPacket(buf.readUtf(), buf.readVarInt());
    }

    public void write(FriendlyByteBuf buf) {
        buf.writeUtf(winner);
        buf.writeVarInt(displaySeconds);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            try {
                Class.forName("org.espetro.client.ClientPacketHandlers")
                    .getMethod("handleRoundEnd", RoundEndPacket.class)
                    .invoke(null, this);
            } catch (Exception ignored) {
            }
        });
        ctx.get().setPacketHandled(true);
    }
}
