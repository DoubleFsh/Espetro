package org.espetro.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 战局倒计时同步包 (S→C)。
 * 服务端每秒广播一次；客户端以收到的秒数为锚点在本地递减。
 */
public class BattleTimerPacket {

    private final int remainingSeconds;

    public BattleTimerPacket(int remainingSeconds) {
        this.remainingSeconds = remainingSeconds;
    }

    public static BattleTimerPacket read(FriendlyByteBuf buf) {
        return new BattleTimerPacket(buf.readVarInt());
    }

    public void write(FriendlyByteBuf buf) {
        buf.writeVarInt(remainingSeconds);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            try {
                Class.forName("org.espetro.client.ClientPacketHandlers")
                    .getMethod("handleBattleTimer", BattleTimerPacket.class)
                    .invoke(null, this);
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
        ctx.get().setPacketHandled(true);
    }

    public int getRemainingSeconds() {
        return remainingSeconds;
    }
}
