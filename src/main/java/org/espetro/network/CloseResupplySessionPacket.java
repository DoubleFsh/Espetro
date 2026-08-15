package org.espetro.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import org.espetro.logistics.resupply.ResupplySessionManager;

import java.util.UUID;
import java.util.function.Supplier;

/** Explicit C2S close; logout/dimension/reset paths also clear sessions. */
public record CloseResupplySessionPacket(UUID token) {
    public static CloseResupplySessionPacket read(FriendlyByteBuf buf) {
        return new CloseResupplySessionPacket(buf.readUUID());
    }

    public void write(FriendlyByteBuf buf) {
        buf.writeUUID(token);
    }

    public void handle(Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context context = supplier.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player != null) ResupplySessionManager.close(player.getUUID(), token);
        });
        context.setPacketHandled(true);
    }
}
