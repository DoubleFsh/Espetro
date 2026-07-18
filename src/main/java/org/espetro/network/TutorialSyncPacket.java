package org.espetro.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 新手教程同步包（S→C）：展示或清除当前步骤卡片。
 */
public class TutorialSyncPacket {

    public static final byte ACTION_SHOW = 0;
    public static final byte ACTION_CLEAR = 1;

    private final byte action;
    private final String stepId;
    private final int index;
    private final int total;
    private final boolean allowSkip;

    public TutorialSyncPacket(byte action, String stepId, int index, int total, boolean allowSkip) {
        this.action = action;
        this.stepId = stepId == null ? "" : stepId;
        this.index = index;
        this.total = total;
        this.allowSkip = allowSkip;
    }

    public static TutorialSyncPacket show(String stepId, int index, int total, boolean allowSkip) {
        return new TutorialSyncPacket(ACTION_SHOW, stepId, index, total, allowSkip);
    }

    public static TutorialSyncPacket clear() {
        return new TutorialSyncPacket(ACTION_CLEAR, "", 0, 0, false);
    }

    public static TutorialSyncPacket read(FriendlyByteBuf buf) {
        byte action = buf.readByte();
        String stepId = buf.readUtf();
        int index = buf.readVarInt();
        int total = buf.readVarInt();
        boolean allowSkip = buf.readBoolean();
        return new TutorialSyncPacket(action, stepId, index, total, allowSkip);
    }

    public void write(FriendlyByteBuf buf) {
        buf.writeByte(action);
        buf.writeUtf(stepId);
        buf.writeVarInt(index);
        buf.writeVarInt(total);
        buf.writeBoolean(allowSkip);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            try {
                Class.forName("org.espetro.client.ClientPacketHandlers")
                    .getMethod("handleTutorialSync", byte.class, String.class, int.class, int.class, boolean.class)
                    .invoke(null, action, stepId, index, total, allowSkip);
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
        ctx.get().setPacketHandled(true);
    }

    public byte getAction() {
        return action;
    }

    public String getStepId() {
        return stepId;
    }

    public int getIndex() {
        return index;
    }

    public int getTotal() {
        return total;
    }

    public boolean isAllowSkip() {
        return allowSkip;
    }
}
