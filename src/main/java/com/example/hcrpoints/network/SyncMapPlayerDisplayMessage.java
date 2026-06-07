package com.example.hcrpoints.network;

import com.example.hcrpoints.config.MapPlayerDisplayConfig;
import com.example.hcrpoints.util.ModLogger;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

import java.util.function.Supplier;

/**
 * 同步地图玩家显示配置消息类
 * 用于服务端向客户端同步地图显示玩家位置的状态
 */
public class SyncMapPlayerDisplayMessage {
    private final boolean showPlayerLocations;
    
    /**
     * 构造函数
     */
    public SyncMapPlayerDisplayMessage(boolean showPlayerLocations) {
        this.showPlayerLocations = showPlayerLocations;
    }
    
    /**
     * 从网络数据包读取配置
     * @param buf 网络数据包缓冲区
     */
    public SyncMapPlayerDisplayMessage(FriendlyByteBuf buf) {
        this.showPlayerLocations = buf.readBoolean();
    }
    
    /**
     * 将配置写入网络数据包
     * @param msg 消息对象
     * @param buf 网络数据包缓冲区
     */
    public static void encode(SyncMapPlayerDisplayMessage msg, FriendlyByteBuf buf) {
        buf.writeBoolean(msg.showPlayerLocations);
    }
    
    /**
     * 从网络数据包解码配置
     * @param buf 网络数据包缓冲区
     * @return 解码后的消息对象
     */
    public static SyncMapPlayerDisplayMessage decode(FriendlyByteBuf buf) {
        return new SyncMapPlayerDisplayMessage(buf);
    }
    
    /**
     * 处理配置同步消息
     * @param msg 消息对象
     * @param ctx 网络事件上下文
     */
    public static void handle(SyncMapPlayerDisplayMessage msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            // 更新客户端的配置状态
            MapPlayerDisplayConfig.getInstance().setShowPlayerLocations(msg.showPlayerLocations);
            ModLogger.info("客户端地图玩家显示配置已同步: showPlayerLocations = " + msg.showPlayerLocations);
        });
        ctx.get().setPacketHandled(true);
    }
    
    /**
     * 向指定玩家发送配置同步消息
     * @param player 目标玩家
     */
    public static void sendToPlayer(ServerPlayer player) {
        try {
            NetworkHandler.INSTANCE.send(
                PacketDistributor.PLAYER.with(() -> player),
                new SyncMapPlayerDisplayMessage(MapPlayerDisplayConfig.getInstance().isShowPlayerLocations())
            );
        } catch (Exception e) {
            ModLogger.error("向玩家发送地图玩家显示配置同步消息失败: " + e.getMessage());
        }
    }
    
    /**
     * 向所有玩家广播配置同步消息
     */
    public static void broadcastToAll() {
        try {
            NetworkHandler.INSTANCE.send(
                PacketDistributor.ALL.noArg(),
                new SyncMapPlayerDisplayMessage(MapPlayerDisplayConfig.getInstance().isShowPlayerLocations())
            );
            ModLogger.info("已向所有玩家广播地图玩家显示配置同步消息");
        } catch (Exception e) {
            ModLogger.error("广播地图玩家显示配置同步消息失败: " + e.getMessage());
        }
    }
}