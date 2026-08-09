package org.espetro.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import org.espetro.bastion.FortificationManager;

import java.util.function.Supplier;

/** 客户端请求放置工事（C→S）。 */
public class BuildFortificationPacket {

    private final String fortId;

    public BuildFortificationPacket(String fortId) {
        this.fortId = fortId == null ? "" : fortId;
    }

    public static BuildFortificationPacket read(FriendlyByteBuf buf) {
        return new BuildFortificationPacket(buf.readUtf(128));
    }

    public void write(FriendlyByteBuf buf) {
        buf.writeUtf(fortId, 128);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null) return;
            String err = FortificationManager.getInstance().place(player, fortId);
            if (err != null) {
                player.sendSystemMessage(Component.literal(err));
            } else {
                player.sendSystemMessage(Component.literal(
                    "§e已进入工事预览：左键确认，右键取消。"));
            }
        });
        ctx.get().setPacketHandled(true);
    }
}
