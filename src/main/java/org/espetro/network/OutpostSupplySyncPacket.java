package org.espetro.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** 靠近前哨基地时的 Radio 血量、弹药/建材存量与兵站启用状态同步（S→C）。 */
public final class OutpostSupplySyncPacket {

    private final boolean inRange;
    private final int radioHealth;
    private final int radioMaxHealth;
    private final int ammunition;
    private final int construction;
    private final boolean habEnabled;

    public OutpostSupplySyncPacket(boolean inRange, int radioHealth, int radioMaxHealth,
                                   int ammunition, int construction, boolean habEnabled) {
        this.inRange = inRange;
        this.radioHealth = Math.max(0, radioHealth);
        this.radioMaxHealth = Math.max(1, radioMaxHealth);
        this.ammunition = Math.max(0, ammunition);
        this.construction = Math.max(0, construction);
        this.habEnabled = habEnabled;
    }

    public static OutpostSupplySyncPacket outOfRange() {
        return new OutpostSupplySyncPacket(false, 0, 1, 0, 0, false);
    }

    public static OutpostSupplySyncPacket read(FriendlyByteBuf buf) {
        boolean inRange = buf.readBoolean();
        if (!inRange) {
            return outOfRange();
        }
        return new OutpostSupplySyncPacket(true,
            buf.readVarInt(), buf.readVarInt(),
            buf.readVarInt(), buf.readVarInt(), buf.readBoolean());
    }

    public void write(FriendlyByteBuf buf) {
        buf.writeBoolean(inRange);
        if (!inRange) {
            return;
        }
        buf.writeVarInt(radioHealth);
        buf.writeVarInt(radioMaxHealth);
        buf.writeVarInt(ammunition);
        buf.writeVarInt(construction);
        buf.writeBoolean(habEnabled);
    }

    public boolean isInRange() { return inRange; }
    public int getRadioHealth() { return radioHealth; }
    public int getRadioMaxHealth() { return radioMaxHealth; }
    public int getAmmunition() { return ammunition; }
    public int getConstruction() { return construction; }
    public boolean isHabEnabled() { return habEnabled; }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            try {
                Class.forName("org.espetro.client.ClientPacketHandlers")
                    .getMethod("handleOutpostSupplySync", OutpostSupplySyncPacket.class)
                    .invoke(null, this);
            } catch (Exception e) {
                org.espetro.Espetro.LOGGER.error("处理前哨补给同步失败", e);
            }
        });
        ctx.get().setPacketHandled(true);
    }
}
