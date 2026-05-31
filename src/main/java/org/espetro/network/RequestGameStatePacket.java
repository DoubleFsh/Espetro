package org.espetro.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;
import org.espetro.Espetro;
import org.espetro.team.ClassCountManager;
import org.espetro.team.ClassSelectManager;
import org.espetro.team.GamePhase;
import org.espetro.team.GameStateManager;
import org.espetro.team.VoteManager;

import java.util.function.Supplier;

/**
 * 客户端→服务端：请求当前游戏状态
 * 服务端返回 GameStateResponsePacket 给请求者
 */
public class RequestGameStatePacket {

    public RequestGameStatePacket() {
    }

    public static RequestGameStatePacket read(FriendlyByteBuf buf) {
        return new RequestGameStatePacket();
    }

    public void write(FriendlyByteBuf buf) {
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            if (ctx.get().getDirection().getReceptionSide().isServer()) {
                ServerPlayer player = ctx.get().getSender();
                if (player == null) return;

                GameStateManager gsm = GameStateManager.getInstance();
                GamePhase phase = gsm.getCurrentPhase();
                ClassCountManager ccm = ClassCountManager.getInstance();

                // 获取玩家队伍和编制
                String playerTeam = ccm.getPlayerTeam(player.getUUID());
                String playerFaction = ccm.getPlayerFaction(player.getUUID());

                // 计算当前阶段剩余时间
                int timeRemaining = 0;
                switch (phase) {
                    case DEFEND_COMMANDER_VOTE, ATTACK_COMMANDER_VOTE ->
                        timeRemaining = VoteManager.getInstance().getRemainingSeconds();
                    case DEFEND_FACTION_SELECT, ATTACK_FACTION_SELECT ->
                        timeRemaining = ClassSelectManager.getInstance().getRemainingSeconds();
                    case DEPLOYING -> {
                        int deployTimeout = org.espetro.config.GameConfig.getDeployTimeoutSeconds();
                        // GameStateManager 用 deployTickCounter 计数，这里近似计算
                        timeRemaining = Math.max(0, deployTimeout);
                    }
                    default -> timeRemaining = 0;
                }

                // 获取当前投票/选择阶段的活跃队伍（用于客户端判断是否需要显示界面）
                String activeTeam = phase.getActiveTeam();

                GameStateResponsePacket response = new GameStateResponsePacket(
                    phase.name(),
                    playerTeam,
                    playerFaction,
                    activeTeam,
                    timeRemaining
                );

                NetworkManager.NET.send(PacketDistributor.PLAYER.with(() -> player), response);
            }
        });
        ctx.get().setPacketHandled(true);
    }
}
