package org.espetro.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import org.espetro.vehicle.SeatSwitchServer;

import java.util.function.Supplier;

/** Client finished seat-switch channel; allow one SBW seat-change. */
public final class SeatSwitchReadyPacket {

    public static SeatSwitchReadyPacket read(FriendlyByteBuf buf) {
        return new SeatSwitchReadyPacket();
    }

    public void write(FriendlyByteBuf buf) {
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ServerPlayer sender = ctx.get().getSender();
        ctx.get().enqueueWork(() -> {
            if (sender != null) {
                SeatSwitchServer.markReady(sender);
            }
        });
        ctx.get().setPacketHandled(true);
    }
}
