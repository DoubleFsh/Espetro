package com.example.hcrpoints.network;

import com.example.hcrpoints.config.ModConfig;
import com.example.hcrpoints.util.ModLogger;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

import java.util.function.Supplier;

/**
 * 配置同步消息类
 * 用于服务端向客户端同步配置
 */
public class SyncConfigMessage {
    private final boolean enableTeams;
    private final boolean enableOperationMode;
    private final boolean enableFriendlyFirePenalty;
    private final int friendlyFirePenalty;
    private final double lowReinforcementThreshold;
    
    /**
     * 构造函数
     */
    public SyncConfigMessage() {
        this.enableTeams = ModConfig.enableTeams.get();
        this.enableOperationMode = ModConfig.enableOperationMode.get();
        this.enableFriendlyFirePenalty = ModConfig.enableFriendlyFirePenalty.get();
        this.friendlyFirePenalty = ModConfig.friendlyFirePenalty.get();
        this.lowReinforcementThreshold = ModConfig.lowReinforcementThreshold.get();
    }
    
    /**
     * 从网络数据包读取配置
     * @param buf 网络数据包缓冲区
     */
    public SyncConfigMessage(FriendlyByteBuf buf) {
        this.enableTeams = buf.readBoolean();
        this.enableOperationMode = buf.readBoolean();
        this.enableFriendlyFirePenalty = buf.readBoolean();
        this.friendlyFirePenalty = buf.readInt();
        this.lowReinforcementThreshold = buf.readDouble();
    }
    
    /**
     * 将配置写入网络数据包
     * @param msg 消息对象
     * @param buf 网络数据包缓冲区
     */
    public static void encode(SyncConfigMessage msg, FriendlyByteBuf buf) {
        buf.writeBoolean(msg.enableTeams);
        buf.writeBoolean(msg.enableOperationMode);
        buf.writeBoolean(msg.enableFriendlyFirePenalty);
        buf.writeInt(msg.friendlyFirePenalty);
        buf.writeDouble(msg.lowReinforcementThreshold);
    }
    
    /**
     * 从网络数据包解码配置
     * @param buf 网络数据包缓冲区
     * @return 解码后的消息对象
     */
    public static SyncConfigMessage decode(FriendlyByteBuf buf) {
        return new SyncConfigMessage(buf);
    }
    
    /**
     * 处理配置同步消息
     * @param msg 消息对象
     * @param ctx 网络事件上下文
     */
    public static void handle(SyncConfigMessage msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            // 在客户端更新配置
            msg.updateConfig(msg.enableTeams, msg.enableOperationMode, msg.enableFriendlyFirePenalty, msg.friendlyFirePenalty, msg.lowReinforcementThreshold);
            
            ModLogger.info("客户端配置已同步：enableTeams=" + msg.enableTeams + ", enableOperationMode=" + msg.enableOperationMode);
        });
        ctx.get().setPacketHandled(true);
    }
    
    /**
     * 更新配置
     */
    private void updateConfig(boolean enableTeams, boolean enableOperationMode, boolean enableFriendlyFirePenalty, int friendlyFirePenalty, double lowReinforcementThreshold) {
        try {
            // 使用ForgeConfigSpec提供的set()方法修改配置值，这是正确的API
            ModConfig.enableTeams.set(enableTeams);
            ModConfig.enableOperationMode.set(enableOperationMode);
            ModConfig.enableFriendlyFirePenalty.set(enableFriendlyFirePenalty);
            ModConfig.friendlyFirePenalty.set(friendlyFirePenalty);
            ModConfig.lowReinforcementThreshold.set(lowReinforcementThreshold);
        } catch (Exception e) {
            ModLogger.error("更新配置时发生异常: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * 向指定玩家发送配置同步消息
     * @param player 目标玩家
     */
    public static void sendToPlayer(ServerPlayer player) {
        try {
            NetworkHandler.INSTANCE.send(
                PacketDistributor.PLAYER.with(() -> player),
                new SyncConfigMessage()
            );
            ModLogger.info("已向玩家 " + player.getName().getString() + " 发送配置同步消息");
        } catch (Exception e) {
            ModLogger.error("向玩家发送配置同步消息失败: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * 向所有玩家广播配置同步消息
     */
    public static void broadcastToAll() {
        try {
            NetworkHandler.INSTANCE.send(
                PacketDistributor.ALL.noArg(),
                new SyncConfigMessage()
            );
            ModLogger.info("已向所有玩家广播配置同步消息");
        } catch (Exception e) {
            ModLogger.error("广播配置同步消息失败: " + e.getMessage());
            e.printStackTrace();
        }
    }
}