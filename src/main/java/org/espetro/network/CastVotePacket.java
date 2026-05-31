package org.espetro.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;
import org.espetro.team.VoteManager;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * 投票数据包
 * 玩家投票时发送到服务器
 */
public class CastVotePacket {

    private final String targetPlayerName;

    public CastVotePacket(String targetPlayerName) {
        this.targetPlayerName = targetPlayerName;
    }

    public static CastVotePacket read(FriendlyByteBuf buf) {
        String targetPlayerName = buf.readUtf();
        return new CastVotePacket(targetPlayerName);
    }

    public void write(FriendlyByteBuf buf) {
        buf.writeUtf(targetPlayerName);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null) return;

            // 查找目标玩家
            UUID targetUUID = null;
            var server = player.getServer();
            if (server != null) {
                var targetPlayer = server.getPlayerList().getPlayerByName(targetPlayerName);
                if (targetPlayer != null) {
                    targetUUID = targetPlayer.getUUID();
                }
            }

            if (targetUUID != null) {
                VoteManager.getInstance().castVote(player, targetUUID);
                
                // 广播更新后的投票数据给所有玩家
                broadcastVoteUpdate();
            }
        });
        ctx.get().setPacketHandled(true);
    }

    private void broadcastVoteUpdate() {
        VoteManager voteManager = VoteManager.getInstance();
        var server = org.espetro.Espetro.getServer();
        if (server == null) return;

        // 构建投票统计
        java.util.Map<String, Integer> voteCounts = new java.util.HashMap<>();
        
        // 攻方
        for (UUID uuid : voteManager.getAttackPlayers()) {
            ServerPlayer p = server.getPlayerList().getPlayer(uuid);
            if (p != null) {
                int count = voteManager.getVoteCount(uuid);
                voteCounts.put(p.getName().getString(), count);
            }
        }
        
        // 守方
        for (UUID uuid : voteManager.getDefendPlayers()) {
            ServerPlayer p = server.getPlayerList().getPlayer(uuid);
            if (p != null) {
                int count = voteManager.getVoteCount(uuid);
                voteCounts.put(p.getName().getString(), count);
            }
        }

        VoteDataPacket packet = new VoteDataPacket(voteCounts, voteManager.getRemainingSeconds());
        
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            NetworkManager.NET.send(PacketDistributor.PLAYER.with(() -> p), packet);
        }
    }
}
