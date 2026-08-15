package org.espetro.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import org.espetro.logistics.resupply.ResupplySessionManager;
import org.espetro.logistics.resupply.ResupplySourceRef;

import java.util.function.Supplier;

/** C2S request to open one server-authoritative per-item catalogue. */
public record RequestResupplyCatalogPacket(ResupplySourceRef source) {
    public static RequestResupplyCatalogPacket read(FriendlyByteBuf buf) {
        return new RequestResupplyCatalogPacket(ResupplySourceRef.read(buf));
    }

    public void write(FriendlyByteBuf buf) {
        source.write(buf);
    }

    public void handle(Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context context = supplier.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player != null) ResupplySessionManager.open(player, source);
        });
        context.setPacketHandled(true);
    }
}
