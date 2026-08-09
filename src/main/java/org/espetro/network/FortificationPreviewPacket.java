package org.espetro.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/** Server-approved blueprint for the local placement outline. */
public record FortificationPreviewPacket(UUID token, String fortId, String displayName,
                                         List<Offset> occupiedOffsets) {
    private static final int MAX_BLOCKS = 256;

    public FortificationPreviewPacket {
        fortId = fortId == null ? "" : fortId;
        displayName = displayName == null ? fortId : displayName;
        occupiedOffsets = occupiedOffsets == null ? List.of() : List.copyOf(occupiedOffsets);
    }

    public static FortificationPreviewPacket read(FriendlyByteBuf buf) {
        UUID token = buf.readUUID();
        String id = buf.readUtf(64);
        String name = buf.readUtf(128);
        int size = Math.min(MAX_BLOCKS, Math.max(0, buf.readVarInt()));
        List<Offset> offsets = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            offsets.add(new Offset(buf.readByte(), buf.readByte(), buf.readByte()));
        }
        return new FortificationPreviewPacket(token, id, name, offsets);
    }

    public void write(FriendlyByteBuf buf) {
        buf.writeUUID(token);
        buf.writeUtf(fortId, 64);
        buf.writeUtf(displayName, 128);
        int size = Math.min(MAX_BLOCKS, occupiedOffsets.size());
        buf.writeVarInt(size);
        for (int i = 0; i < size; i++) {
            Offset offset = occupiedOffsets.get(i);
            buf.writeByte(offset.x());
            buf.writeByte(offset.y());
            buf.writeByte(offset.z());
        }
    }

    public void handle(Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context context = supplier.get();
        context.enqueueWork(() -> {
            try {
                Class.forName("org.espetro.client.ClientPacketHandlers")
                    .getMethod("handleFortificationPreview", FortificationPreviewPacket.class)
                    .invoke(null, this);
            } catch (ReflectiveOperationException e) {
                org.espetro.Espetro.LOGGER.error("处理工事预览失败", e);
            }
        });
        context.setPacketHandled(true);
    }

    public record Offset(int x, int y, int z) {
    }
}
