package com.example.hcrpoints.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

import java.util.function.Supplier;

/**
 * 背水一战音频播放消息类
 * 用于服务端通知客户端播放背水一战的背景音乐
 */
public class PlayLowReinforcementAudioMessage {
    private final boolean playAudio; // true表示播放，false表示停止
    
    /**
     * 构造函数
     * @param playAudio 是否播放音频
     */
    public PlayLowReinforcementAudioMessage(boolean playAudio) {
        this.playAudio = playAudio;
    }
    
    /**
     * 从网络数据包读取
     * @param buf 网络数据包缓冲区
     */
    public PlayLowReinforcementAudioMessage(FriendlyByteBuf buf) {
        this.playAudio = buf.readBoolean();
    }
    
    /**
     * 将消息写入网络数据包
     * @param msg 消息对象
     * @param buf 网络数据包缓冲区
     */
    public static void encode(PlayLowReinforcementAudioMessage msg, FriendlyByteBuf buf) {
        buf.writeBoolean(msg.playAudio);
    }
    
    /**
     * 从网络数据包解码消息
     * @param buf 网络数据包缓冲区
     * @return 解码后的消息对象
     */
    public static PlayLowReinforcementAudioMessage decode(FriendlyByteBuf buf) {
        return new PlayLowReinforcementAudioMessage(buf);
    }
    
    /**
     * 处理音频播放消息
     * @param msg 消息对象
     * @param ctx 网络事件上下文
     */
    public static void handle(PlayLowReinforcementAudioMessage msg, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> {
            // 只在客户端处理
            if (context.getDirection().getReceptionSide().isClient()) {
                // 调用音频播放管理器处理音频播放
                com.example.hcrpoints.client.AudioManager.getInstance().handleLowReinforcementAudio(msg.playAudio);
            }
        });
        context.setPacketHandled(true);
    }
    
    /**
     * 广播消息给所有玩家
     * @param playAudio 是否播放音频
     */
    public static void broadcastToAll(boolean playAudio) {
        NetworkHandler.INSTANCE.send(
            PacketDistributor.ALL.noArg(),
            new PlayLowReinforcementAudioMessage(playAudio)
        );
    }
}
