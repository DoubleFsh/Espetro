package org.espetro.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 双方编制揭示界面数据包。
 */
public class FactionRevealPacket {

    private final String attackFactionName;
    private final String defendFactionName;
    private final int durationSeconds;

    public FactionRevealPacket(String attackFactionName, String defendFactionName, int durationSeconds) {
        this.attackFactionName = attackFactionName == null ? "" : attackFactionName;
        this.defendFactionName = defendFactionName == null ? "" : defendFactionName;
        this.durationSeconds = durationSeconds;
    }

    public static FactionRevealPacket read(FriendlyByteBuf buf) {
        String attackFactionName = buf.readUtf();
        String defendFactionName = buf.readUtf();
        int durationSeconds = buf.readVarInt();
        return new FactionRevealPacket(attackFactionName, defendFactionName, durationSeconds);
    }

    public void write(FriendlyByteBuf buf) {
        buf.writeUtf(attackFactionName);
        buf.writeUtf(defendFactionName);
        buf.writeVarInt(durationSeconds);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            try {
                Class.forName("org.espetro.client.ClientPacketHandlers")
                    .getMethod("handleFactionReveal", FactionRevealPacket.class)
                    .invoke(null, this);
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
        ctx.get().setPacketHandled(true);
    }

    public String getAttackFactionName() {
        return attackFactionName;
    }

    public String getDefendFactionName() {
        return defendFactionName;
    }

    public int getDurationSeconds() {
        return durationSeconds;
    }
}
