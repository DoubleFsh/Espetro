package org.espetro.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import org.espetro.stamina.StaminaManager;

import java.util.function.Supplier;

/**
 * 客户端跳跃动作通知。服务端仍根据自身体力状态决定是否扣除或拦截。
 */
public class StaminaJumpPacket {

    public static StaminaJumpPacket read(FriendlyByteBuf buf) {
        return new StaminaJumpPacket();
    }

    public void write(FriendlyByteBuf buf) {
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ServerPlayer sender = ctx.get().getSender();
        ctx.get().enqueueWork(() -> {
            if (sender != null) {
                StaminaManager.onPlayerJump(sender);
            }
        });
        ctx.get().setPacketHandled(true);
    }
}
