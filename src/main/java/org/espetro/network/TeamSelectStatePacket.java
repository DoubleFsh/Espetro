package org.espetro.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** S→C team select counts + timer. */
public class TeamSelectStatePacket {

    public final int attackCount;
    public final int defendCount;
    public final int remainingSeconds;
    public final long endGameTime;
    public final boolean active;
    public final String myTeam;

    public TeamSelectStatePacket(int attackCount, int defendCount, int remainingSeconds,
                                 long endGameTime, boolean active, String myTeam) {
        this.attackCount = attackCount;
        this.defendCount = defendCount;
        this.remainingSeconds = remainingSeconds;
        this.endGameTime = endGameTime;
        this.active = active;
        this.myTeam = myTeam;
    }

    public static TeamSelectStatePacket read(FriendlyByteBuf buf) {
        return new TeamSelectStatePacket(
            buf.readVarInt(), buf.readVarInt(), buf.readVarInt(),
            buf.readLong(), buf.readBoolean(),
            buf.readBoolean() ? buf.readUtf() : null
        );
    }

    public void write(FriendlyByteBuf buf) {
        buf.writeVarInt(attackCount);
        buf.writeVarInt(defendCount);
        buf.writeVarInt(remainingSeconds);
        buf.writeLong(endGameTime);
        buf.writeBoolean(active);
        buf.writeBoolean(myTeam != null);
        if (myTeam != null) buf.writeUtf(myTeam);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            try {
                Class.forName("org.espetro.client.ClientPacketHandlers")
                    .getMethod("handleTeamSelectState", TeamSelectStatePacket.class)
                    .invoke(null, this);
            } catch (Exception ignored) {
            }
        });
        ctx.get().setPacketHandled(true);
    }
}
