package org.espetro.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 兵力统计同步数据包
 * 服务端同步双方兵力到客户端用于HUD显示
 */
public class TroopCountSyncPacket {

    private final int attackTroops;
    private final int defendTroops;

    public TroopCountSyncPacket(int attackTroops, int defendTroops) {
        this.attackTroops = attackTroops;
        this.defendTroops = defendTroops;
    }

    public static TroopCountSyncPacket read(FriendlyByteBuf buf) {
        int attack = buf.readInt();
        int defend = buf.readInt();
        return new TroopCountSyncPacket(attack, defend);
    }

    public void write(FriendlyByteBuf buf) {
        buf.writeInt(attackTroops);
        buf.writeInt(defendTroops);
    }

    public int getAttackTroops() { return attackTroops; }
    public int getDefendTroops() { return defendTroops; }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            try {
                Class.forName("org.espetro.client.ClientPacketHandlers")
                    .getMethod("handleTroopCount", TroopCountSyncPacket.class)
                    .invoke(null, this);
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
        ctx.get().setPacketHandled(true);
    }
}
