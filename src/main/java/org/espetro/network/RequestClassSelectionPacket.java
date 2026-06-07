package org.espetro.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 客户端→服务端：请求打开职业选择界面
 * 客户端每次想打开职业选择GUI时先发此包，服务端返回完整数据
 * factionId 可为 null/空，此时服务端根据玩家队伍自动查找对应阵营
 */
public class RequestClassSelectionPacket {

    private final String factionId;

    public RequestClassSelectionPacket(String factionId) {
        this.factionId = factionId;
    }

    public static RequestClassSelectionPacket read(FriendlyByteBuf buf) {
        return new RequestClassSelectionPacket(buf.readUtf());
    }

    public void write(FriendlyByteBuf buf) {
        buf.writeUtf(factionId != null ? factionId : "");
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            if (ctx.get().getDirection().getReceptionSide().isServer()) {
                ServerPlayer player = ctx.get().getSender();
                if (player != null) {
                    // 发送统一部署主界面（集成职业选择、复活点、载具、地图）
                    NetworkManager.sendUnifiedDeployScreen(player, -1);
                }
            }
        });
        ctx.get().setPacketHandled(true);
    }
}
