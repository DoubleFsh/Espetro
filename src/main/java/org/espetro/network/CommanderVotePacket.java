package org.espetro.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * 指挥官投票数据包
 * 通知客户端打开投票界面（仅显示玩家所在队伍）
 */
public class CommanderVotePacket {

    // 玩家所在队伍 "ATTACK" 或 "DEFEND"
    private final String team;
    // 同队伍玩家列表
    private final List<String> players;
    // 投票时限
    private final int timeRemaining;

    public CommanderVotePacket(String team, List<String> players, int timeRemaining) {
        this.team = team;
        this.players = players;
        this.timeRemaining = timeRemaining;
    }

    public static CommanderVotePacket read(FriendlyByteBuf buf) {
        String team = buf.readUtf();
        int size = buf.readInt();
        List<String> players = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            players.add(buf.readUtf());
        }
        int timeRemaining = buf.readInt();
        return new CommanderVotePacket(team, players, timeRemaining);
    }

    public void write(FriendlyByteBuf buf) {
        buf.writeUtf(team);
        buf.writeInt(players.size());
        for (String name : players) {
            buf.writeUtf(name);
        }
        buf.writeInt(timeRemaining);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            try {
                Class.forName("org.espetro.client.ClientPacketHandlers")
                    .getMethod("handleCommanderVote", CommanderVotePacket.class)
                    .invoke(null, this);
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
        ctx.get().setPacketHandled(true);
    }

    public String getTeam() {
        return team;
    }

    public List<String> getPlayers() {
        return players;
    }

    public int getTimeRemaining() {
        return timeRemaining;
    }
}
