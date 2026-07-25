package org.espetro.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 编制选择阶段轻量同步：仅倒计时与当前选中编制，避免每秒全量 faction 列表。
 */
public class ClassSelectTimerPacket {

    private final int timeRemaining;
    private final int opponentTimeRemaining;
    private final String selectedFactionId;
    private final boolean isCommander;

    public ClassSelectTimerPacket(int timeRemaining, int opponentTimeRemaining,
                                  String selectedFactionId, boolean isCommander) {
        this.timeRemaining = timeRemaining;
        this.opponentTimeRemaining = opponentTimeRemaining;
        this.selectedFactionId = selectedFactionId == null ? "" : selectedFactionId;
        this.isCommander = isCommander;
    }

    public static ClassSelectTimerPacket read(FriendlyByteBuf buf) {
        return new ClassSelectTimerPacket(
            buf.readVarInt(),
            buf.readVarInt(),
            buf.readUtf(),
            buf.readBoolean());
    }

    public void write(FriendlyByteBuf buf) {
        buf.writeVarInt(timeRemaining);
        buf.writeVarInt(opponentTimeRemaining);
        buf.writeUtf(selectedFactionId);
        buf.writeBoolean(isCommander);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            try {
                Class.forName("org.espetro.client.ClientPacketHandlers")
                    .getMethod("handleClassSelectTimer", ClassSelectTimerPacket.class)
                    .invoke(null, this);
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
        ctx.get().setPacketHandled(true);
    }

    public int getTimeRemaining() {
        return timeRemaining;
    }

    public int getOpponentTimeRemaining() {
        return opponentTimeRemaining;
    }

    public String getSelectedFactionId() {
        return selectedFactionId;
    }

    public boolean isCommander() {
        return isCommander;
    }
}
