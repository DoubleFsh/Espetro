package org.espetro.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 将服务端权威体力状态同步到对应客户端。
 */
public class StaminaSyncPacket {

    private final boolean enabled;
    private final int stamina;
    private final int maxStamina;

    public StaminaSyncPacket(boolean enabled, int stamina, int maxStamina) {
        this.enabled = enabled;
        this.stamina = stamina;
        this.maxStamina = maxStamina;
    }

    public static StaminaSyncPacket read(FriendlyByteBuf buf) {
        return new StaminaSyncPacket(buf.readBoolean(), buf.readVarInt(), buf.readVarInt());
    }

    public void write(FriendlyByteBuf buf) {
        buf.writeBoolean(enabled);
        buf.writeVarInt(stamina);
        buf.writeVarInt(maxStamina);
    }

    public boolean isEnabled() {
        return enabled;
    }

    public int getStamina() {
        return stamina;
    }

    public int getMaxStamina() {
        return maxStamina;
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            try {
                Class.forName("org.espetro.client.ClientPacketHandlers")
                    .getMethod("handleStamina", StaminaSyncPacket.class)
                    .invoke(null, this);
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
        ctx.get().setPacketHandled(true);
    }
}
