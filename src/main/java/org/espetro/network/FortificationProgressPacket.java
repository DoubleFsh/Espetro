package org.espetro.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** Event-driven progress response for the MUtil construction HUD. */
public record FortificationProgressPacket(String displayName, int progress, int required,
                                          boolean building) {
    public static FortificationProgressPacket read(FriendlyByteBuf buf) {
        return new FortificationProgressPacket(buf.readUtf(128), buf.readVarInt(),
            buf.readVarInt(), buf.readBoolean());
    }

    public void write(FriendlyByteBuf buf) {
        buf.writeUtf(displayName == null ? "" : displayName, 128);
        buf.writeVarInt(Math.max(0, progress));
        buf.writeVarInt(Math.max(1, required));
        buf.writeBoolean(building);
    }

    public void handle(Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context context = supplier.get();
        context.enqueueWork(() -> {
            try {
                Class.forName("org.espetro.client.ClientPacketHandlers")
                    .getMethod("handleFortificationProgress", FortificationProgressPacket.class)
                    .invoke(null, this);
            } catch (ReflectiveOperationException e) {
                org.espetro.Espetro.LOGGER.error("处理工事进度失败", e);
            }
        });
        context.setPacketHandled(true);
    }
}
