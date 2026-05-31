package org.espetro.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 服务端→客户端：回复当前游戏状态
 * 包含阶段、玩家队伍、当前活跃队伍、剩余时间
 */
public class GameStateResponsePacket {

    private final String phaseName;
    private final String playerTeam;
    private final String playerFaction;
    private final String activeTeam;
    private final int timeRemaining;

    public GameStateResponsePacket(String phaseName, String playerTeam, String playerFaction,
                                    String activeTeam, int timeRemaining) {
        this.phaseName = phaseName;
        this.playerTeam = playerTeam;
        this.playerFaction = playerFaction;
        this.activeTeam = activeTeam;
        this.timeRemaining = timeRemaining;
    }

    public static GameStateResponsePacket read(FriendlyByteBuf buf) {
        return new GameStateResponsePacket(
            buf.readUtf(),
            buf.readUtf(),
            buf.readUtf(),
            buf.readUtf(),
            buf.readVarInt()
        );
    }

    public void write(FriendlyByteBuf buf) {
        buf.writeUtf(phaseName);
        buf.writeUtf(playerTeam != null ? playerTeam : "");
        buf.writeUtf(playerFaction != null ? playerFaction : "");
        buf.writeUtf(activeTeam != null ? activeTeam : "");
        buf.writeVarInt(timeRemaining);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            try {
                Class.forName("org.espetro.client.ClientPacketHandlers")
                    .getMethod("handleGameStateResponse", GameStateResponsePacket.class)
                    .invoke(null, this);
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
        ctx.get().setPacketHandled(true);
    }

    public String getPhaseName() { return phaseName; }
    public String getPlayerTeam() { return playerTeam; }
    public String getPlayerFaction() { return playerFaction; }
    public String getActiveTeam() { return activeTeam; }
    public int getTimeRemaining() { return timeRemaining; }
}
