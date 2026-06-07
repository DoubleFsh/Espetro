package com.example.hcrpoints.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;
import com.example.hcrpoints.hud.MessagePopup;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * 显示消息弹窗的网络消息
 */
public class ShowMessagePopupMessage {
    private final UUID playerUUID;
    private final String message;
    private final long duration;
    private final int width;
    private final int height;
    private final int backgroundColor;
    private final int textColor;
    private final int borderColor;
    private final int borderWidth;

    public ShowMessagePopupMessage(UUID playerUUID, String message, long duration, int width, int height,
                                  int backgroundColor, int textColor, int borderColor, int borderWidth) {
        this.playerUUID = playerUUID;
        this.message = message;
        this.duration = duration;
        this.width = width;
        this.height = height;
        this.backgroundColor = backgroundColor;
        this.textColor = textColor;
        this.borderColor = borderColor;
        this.borderWidth = borderWidth;
    }

    /**
     * 编码消息
     */
    public static void encode(ShowMessagePopupMessage msg, FriendlyByteBuf buf) {
        buf.writeUUID(msg.playerUUID);
        buf.writeUtf(msg.message);
        buf.writeLong(msg.duration);
        buf.writeInt(msg.width);
        buf.writeInt(msg.height);
        buf.writeInt(msg.backgroundColor);
        buf.writeInt(msg.textColor);
        buf.writeInt(msg.borderColor);
        buf.writeInt(msg.borderWidth);
    }

    /**
     * 解码消息
     */
    public static ShowMessagePopupMessage decode(FriendlyByteBuf buf) {
        return new ShowMessagePopupMessage(
            buf.readUUID(),
            buf.readUtf(),
            buf.readLong(),
            buf.readInt(),
            buf.readInt(),
            buf.readInt(),
            buf.readInt(),
            buf.readInt(),
            buf.readInt()
        );
    }

    /**
     * 处理消息
     */
    public static void handle(ShowMessagePopupMessage msg, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> {
            // 只在客户端处理
            if (context.getDirection() == NetworkDirection.PLAY_TO_CLIENT) {
                // 调用MessagePopup显示消息
                MessagePopup.getInstance().showMessage(
                    msg.playerUUID,
                    msg.message,
                    msg.duration,
                    msg.width,
                    msg.height,
                    msg.backgroundColor,
                    msg.textColor,
                    msg.borderColor,
                    msg.borderWidth
                );
            }
        });
        context.setPacketHandled(true);
    }

    /**
     * 发送消息给指定玩家
     */
    public static void sendToPlayer(ServerPlayer player, UUID playerUUID, String message, long duration,
                                   int width, int height, int backgroundColor, int textColor,
                                   int borderColor, int borderWidth) {
        NetworkHandler.INSTANCE.send(
            PacketDistributor.PLAYER.with(() -> player),
            new ShowMessagePopupMessage(playerUUID, message, duration, width, height,
                                       backgroundColor, textColor, borderColor, borderWidth)
        );
    }

    /**
     * 广播消息给所有玩家
     */
    public static void broadcastToAll(UUID playerUUID, String message, long duration,
                                     int width, int height, int backgroundColor, int textColor,
                                     int borderColor, int borderWidth) {
        NetworkHandler.INSTANCE.send(
            PacketDistributor.ALL.noArg(),
            new ShowMessagePopupMessage(playerUUID, message, duration, width, height,
                                       backgroundColor, textColor, borderColor, borderWidth)
        );
    }
}