package org.espetro.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import org.espetro.team.ClassCountManager;

import java.util.function.Supplier;

/** 客户端请求打开本队载具信息面板（C→S）。 */
public final class RequestVehicleInfoPacket {

    public RequestVehicleInfoPacket() {
    }

    public static RequestVehicleInfoPacket read(FriendlyByteBuf buf) {
        return new RequestVehicleInfoPacket();
    }

    public void write(FriendlyByteBuf buf) {
    }

    public void handle(Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context context = supplier.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player == null) return;
            String factionId = ClassCountManager.getInstance().getPlayerFaction(player.getUUID());
            if (factionId == null) {
                player.sendSystemMessage(Component.literal("§c你还没有选择编制！"));
                return;
            }
            NetworkManager.sendVehicleDeployScreen(player, factionId);
        });
        context.setPacketHandled(true);
    }
}
