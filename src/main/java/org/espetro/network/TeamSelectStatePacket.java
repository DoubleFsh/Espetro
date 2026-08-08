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
    /** 因人数平衡被锁定的队伍："ATTACK" / "DEFEND" / null（均未锁定）。 */
    public final String lockedTeam;
    /** 攻击方编制选择图 ResourceLocation 字符串（null 时用默认图）。 */
    public final String attackFactionImage;
    /** 防守方编制选择图 ResourceLocation 字符串（null 时用默认图）。 */
    public final String defendFactionImage;

    public TeamSelectStatePacket(int attackCount, int defendCount, int remainingSeconds,
                                 long endGameTime, boolean active, String myTeam,
                                 String lockedTeam,
                                 String attackFactionImage, String defendFactionImage) {
        this.attackCount = attackCount;
        this.defendCount = defendCount;
        this.remainingSeconds = remainingSeconds;
        this.endGameTime = endGameTime;
        this.active = active;
        this.myTeam = myTeam;
        this.lockedTeam = lockedTeam;
        this.attackFactionImage = attackFactionImage;
        this.defendFactionImage = defendFactionImage;
    }

    public static TeamSelectStatePacket read(FriendlyByteBuf buf) {
        return new TeamSelectStatePacket(
            buf.readVarInt(), buf.readVarInt(), buf.readVarInt(),
            buf.readLong(), buf.readBoolean(),
            buf.readBoolean() ? buf.readUtf() : null,
            buf.readBoolean() ? buf.readUtf() : null,
            buf.readBoolean() ? buf.readUtf() : null,
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
        buf.writeBoolean(lockedTeam != null);
        if (lockedTeam != null) buf.writeUtf(lockedTeam);
        buf.writeBoolean(attackFactionImage != null);
        if (attackFactionImage != null) buf.writeUtf(attackFactionImage);
        buf.writeBoolean(defendFactionImage != null);
        if (defendFactionImage != null) buf.writeUtf(defendFactionImage);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            try {
                Class.forName("org.espetro.client.ClientPacketHandlers")
                    .getMethod("handleTeamSelectState", TeamSelectStatePacket.class)
                    .invoke(null, this);
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
        ctx.get().setPacketHandled(true);
    }
}
