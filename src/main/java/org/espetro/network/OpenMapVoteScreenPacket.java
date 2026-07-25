package org.espetro.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** S→C force open map vote screen. */
public class OpenMapVoteScreenPacket {

    public OpenMapVoteScreenPacket() {
    }

    public static OpenMapVoteScreenPacket read(FriendlyByteBuf buf) {
        return new OpenMapVoteScreenPacket();
    }

    public void write(FriendlyByteBuf buf) {
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            try {
                Class.forName("org.espetro.client.ClientPacketHandlers")
                    .getMethod("handleOpenMapVoteScreen")
                    .invoke(null);
            } catch (Exception ignored) {
            }
        });
        ctx.get().setPacketHandled(true);
    }
}
