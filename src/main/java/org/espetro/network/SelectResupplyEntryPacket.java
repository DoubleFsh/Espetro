package org.espetro.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import org.espetro.logistics.resupply.ResupplySessionManager;
import org.espetro.logistics.resupply.ResupplySourceRef;

import java.util.UUID;
import java.util.function.Supplier;

/** C2S action.  Price, max, NBT and ItemStack are intentionally absent. */
public record SelectResupplyEntryPacket(UUID token, long catalogRevision, long actionSeq,
                                        int entryIndex, ResupplySourceRef source) {
    public static SelectResupplyEntryPacket read(FriendlyByteBuf buf) {
        return new SelectResupplyEntryPacket(buf.readUUID(), buf.readLong(), buf.readLong(),
            buf.readVarInt(), ResupplySourceRef.read(buf));
    }

    public void write(FriendlyByteBuf buf) {
        buf.writeUUID(token);
        buf.writeLong(catalogRevision);
        buf.writeLong(actionSeq);
        buf.writeVarInt(entryIndex);
        source.write(buf);
    }

    public void handle(Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context context = supplier.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player != null) ResupplySessionManager.select(player, this);
        });
        context.setPacketHandled(true);
    }
}
