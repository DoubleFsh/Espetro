package org.espetro.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** Server → client mount progress for the vehicle wheel center. */
public final class MountProgressPacket {

    private final boolean active;
    private final float progress;
    private final int delayTicks;

    public MountProgressPacket(boolean active, float progress, int delayTicks) {
        this.active = active;
        this.progress = progress;
        this.delayTicks = delayTicks;
    }

    public boolean active() {
        return active;
    }

    public float progress() {
        return progress;
    }

    public int delayTicks() {
        return delayTicks;
    }

    public static MountProgressPacket read(FriendlyByteBuf buf) {
        return new MountProgressPacket(buf.readBoolean(), buf.readFloat(), buf.readVarInt());
    }

    public void write(FriendlyByteBuf buf) {
        buf.writeBoolean(active);
        buf.writeFloat(progress);
        buf.writeVarInt(delayTicks);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            try {
                Class.forName("org.espetro.client.ClientPacketHandlers")
                    .getMethod("handleMountProgress", MountProgressPacket.class)
                    .invoke(null, this);
            } catch (ReflectiveOperationException e) {
                org.espetro.Espetro.LOGGER.error("处理上车进度同步失败", e);
            }
        });
        ctx.get().setPacketHandled(true);
    }
}
