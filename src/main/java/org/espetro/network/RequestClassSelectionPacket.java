package org.espetro.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import org.espetro.Espetro;
import org.espetro.team.FactionDataLoader;
import org.espetro.team.FactionDataProvider;

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
                    // 确定目标factionId：用客户端提供的，或从玩家队伍自动查找
                    String resolvedFaction = resolveFactionId(player, factionId);
                    if (resolvedFaction != null) {
                        FactionDataLoader loader = FactionDataProvider.getOrCreateLoader();
                        OpenClassSelectionPacket response = new OpenClassSelectionPacket(resolvedFaction, loader);
                        NetworkManager.sendToPlayer(player, response);
                    }
                }
            }
        });
        ctx.get().setPacketHandled(true);
    }

    /**
     * 解析玩家所属的阵营ID
     */
    private static String resolveFactionId(ServerPlayer player, String requestedFaction) {
        // 客户端明确指定了阵营
        if (requestedFaction != null && !requestedFaction.isEmpty()) {
            return requestedFaction;
        }
        // 根据玩家队伍查找对应阵营
        String team = Espetro.getPlayerTeam(player);
        if (team == null) return null;
        // 从 ClassSelectManager 获取该队伍对应的最终编制 factionId
        org.espetro.team.ClassSelectManager csm = org.espetro.team.ClassSelectManager.getInstance();
        if ("ATTACK".equals(team)) {
            return csm.getFinalAttackClass();
        } else if ("DEFEND".equals(team)) {
            return csm.getFinalDefendClass();
        }
        return null;
    }
}
