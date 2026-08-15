package org.espetro.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.UUID;
import java.util.function.Supplier;

/** S2C result/delta for one action sequence. */
public record ResupplyEntryDeltaPacket(UUID token, long actionSeq, long stateRevision,
                                       int balance, boolean success, boolean close,
                                       String message, ResupplyCatalogPacket.Entry entry) {
    private static final int MAX_MESSAGE = 512;

    public ResupplyEntryDeltaPacket {
        message = message == null ? "" : message;
        if (message.length() > MAX_MESSAGE) message = message.substring(0, MAX_MESSAGE);
    }

    public static ResupplyEntryDeltaPacket read(FriendlyByteBuf buf) {
        UUID token = buf.readUUID();
        long action = buf.readLong();
        long state = buf.readLong();
        int balance = buf.readVarInt();
        boolean success = buf.readBoolean();
        boolean close = buf.readBoolean();
        String message = buf.readUtf(MAX_MESSAGE);
        ResupplyCatalogPacket.Entry entry = buf.readBoolean()
            ? ResupplyCatalogPacket.Entry.read(buf) : null;
        return new ResupplyEntryDeltaPacket(token, action, state, balance, success, close,
            message, entry);
    }

    public void write(FriendlyByteBuf buf) {
        buf.writeUUID(token);
        buf.writeLong(actionSeq);
        buf.writeLong(stateRevision);
        buf.writeVarInt(Math.max(0, balance));
        buf.writeBoolean(success);
        buf.writeBoolean(close);
        buf.writeUtf(message, MAX_MESSAGE);
        buf.writeBoolean(entry != null);
        if (entry != null) entry.write(buf);
    }

    public void handle(Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context context = supplier.get();
        context.enqueueWork(() -> {
            try {
                Class.forName("org.espetro.client.ClientPacketHandlers")
                    .getMethod("handleResupplyDelta", ResupplyEntryDeltaPacket.class)
                    .invoke(null, this);
            } catch (ReflectiveOperationException error) {
                org.espetro.Espetro.LOGGER.error("处理补给增量失败", error);
            }
        });
        context.setPacketHandled(true);
    }
}
