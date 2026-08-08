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
    /** 攻击方编制选择图 ResourceLocation 字符串 (null 时用默认图)。 */
    private final String attackFactionImage;
    /** 防守方编制选择图 ResourceLocation 字符串 (null 时用默认图)。 */
    private final String defendFactionImage;
    private final int durationSeconds;

    public FactionRevealPacket(String attackFactionName, String defendFactionName,
                               String attackFactionImage, String defendFactionImage,
                               int durationSeconds) {
        this.attackFactionName = attackFactionName == null ? "" : attackFactionName;
        this.defendFactionName = defendFactionName == null ? "" : defendFactionName;
        this.attackFactionImage = attackFactionImage;
        this.defendFactionImage = defendFactionImage;
        this.durationSeconds = durationSeconds;
    }

    public static FactionRevealPacket read(FriendlyByteBuf buf) {
        String attackFactionName = buf.readUtf();
        String defendFactionName = buf.readUtf();
        String attackFactionImage = buf.readBoolean() ? buf.readUtf() : null;
        String defendFactionImage = buf.readBoolean() ? buf.readUtf() : null;
        int durationSeconds = buf.readVarInt();
        return new FactionRevealPacket(attackFactionName, defendFactionName,
            attackFactionImage, defendFactionImage, durationSeconds);
    }

    public void write(FriendlyByteBuf buf) {
        buf.writeUtf(attackFactionName);
        buf.writeUtf(defendFactionName);
        buf.writeBoolean(attackFactionImage != null);
        if (attackFactionImage != null) buf.writeUtf(attackFactionImage);
        buf.writeBoolean(defendFactionImage != null);
        if (defendFactionImage != null) buf.writeUtf(defendFactionImage);
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

    public String getAttackFactionImage() {
        return attackFactionImage;
    }

    public String getDefendFactionImage() {
        return defendFactionImage;
    }

    public int getDurationSeconds() {
        return durationSeconds;
    }
}
