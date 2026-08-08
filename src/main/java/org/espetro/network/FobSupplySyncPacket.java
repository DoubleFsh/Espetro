package org.espetro.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 己方 Radio 覆盖范围内的建材/弹药库存同步（S→C）。
 * construction &lt; 0 表示离开覆盖范围，客户端隐藏 HUD。
 */
public class FobSupplySyncPacket {

    private final int construction;
    private final int ammunition;
    private final int maxConstruction;
    private final int maxAmmunition;
    private final boolean inRange;

    public FobSupplySyncPacket(boolean inRange, int construction, int ammunition,
                               int maxConstruction, int maxAmmunition) {
        this.inRange = inRange;
        this.construction = construction;
        this.ammunition = ammunition;
        this.maxConstruction = maxConstruction;
        this.maxAmmunition = maxAmmunition;
    }

    public static FobSupplySyncPacket outOfRange() {
        return new FobSupplySyncPacket(false, 0, 0, 0, 0);
    }

    public static FobSupplySyncPacket read(FriendlyByteBuf buf) {
        boolean inRange = buf.readBoolean();
        if (!inRange) {
            return outOfRange();
        }
        return new FobSupplySyncPacket(true, buf.readVarInt(), buf.readVarInt(),
            buf.readVarInt(), buf.readVarInt());
    }

    public void write(FriendlyByteBuf buf) {
        buf.writeBoolean(inRange);
        if (inRange) {
            buf.writeVarInt(construction);
            buf.writeVarInt(ammunition);
            buf.writeVarInt(maxConstruction);
            buf.writeVarInt(maxAmmunition);
        }
    }

    public boolean isInRange() { return inRange; }
    public int getConstruction() { return construction; }
    public int getAmmunition() { return ammunition; }
    public int getMaxConstruction() { return maxConstruction; }
    public int getMaxAmmunition() { return maxAmmunition; }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            try {
                Class.forName("org.espetro.client.ClientPacketHandlers")
                    .getMethod("handleFobSupplySync", FobSupplySyncPacket.class)
                    .invoke(null, this);
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
        ctx.get().setPacketHandled(true);
    }
}
