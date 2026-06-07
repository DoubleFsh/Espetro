package com.example.hcrpoints.network;

import com.example.hcrpoints.capturepoint.CapturePoint;
import com.example.hcrpoints.capturepoint.CapturePointManager;
import com.example.hcrpoints.hud.TacticalMapHUD;
import com.example.hcrpoints.util.ModLogger;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

import java.util.List;
import java.util.function.Supplier;

/**
 * 同步据点信息的消息类
 */
public class SyncCapturePointsMessage {
    private final List<CapturePoint.SerializableCapturePoint> capturePoints;
    
    public SyncCapturePointsMessage(List<CapturePoint.SerializableCapturePoint> capturePoints) {
        this.capturePoints = capturePoints;
    }
    
    /**
     * 编码消息
     * @param msg 消息对象
     * @param buf 数据缓冲区
     */
    public static void encode(SyncCapturePointsMessage msg, FriendlyByteBuf buf) {
        buf.writeInt(msg.capturePoints.size());
        for (CapturePoint.SerializableCapturePoint point : msg.capturePoints) {
            point.toNetwork(buf);
        }
    }
    
    /**
     * 解码消息
     * @param buf 数据缓冲区
     * @return 解码后的消息对象
     */
    public static SyncCapturePointsMessage decode(FriendlyByteBuf buf) {
        int size = buf.readInt();
        List<CapturePoint.SerializableCapturePoint> points = new java.util.ArrayList<>();
        for (int i = 0; i < size; i++) {
            try {
                points.add(CapturePoint.SerializableCapturePoint.fromNetwork(buf));
            } catch (Exception e) {
                ModLogger.decodeError("Failed to decode capture point: " + e.getMessage());
                throw e; // 重新抛出异常以触发客户端错误处理
            }
        }
        return new SyncCapturePointsMessage(points);
    }
    
    /**
     * 处理消息
     * @param msg 消息对象
     * @param ctx 网络上下文
     */
    public static void handle(SyncCapturePointsMessage msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            try {
                // 完全替换客户端据点数据，确保删除操作能正确同步
                CapturePointManager.getInstance().syncFromServer(msg.capturePoints);
                TacticalMapHUD.getInstance().syncVisibleCapturePointsFromServer(msg.capturePoints);
            } catch (Exception e) {
                ModLogger.syncError("Failed to handle sync message: " + e.getMessage());
                e.printStackTrace();
            }
        });
        ctx.get().setPacketHandled(true);
    }
    
    /**
     * 向指定玩家发送同步消息
     * @param player 目标玩家
     */
    public static void sendToPlayer(ServerPlayer player) {
        try {
            List<CapturePoint.SerializableCapturePoint> points = CapturePointManager.getInstance().getAllSerializablePoints();
            NetworkHandler.INSTANCE.send(PacketDistributor.PLAYER.with(() -> player), new SyncCapturePointsMessage(points));
        } catch (Exception e) {
            ModLogger.syncError("Failed to send sync message to player: " + e.getMessage());
        }
    }
    
    /**
     * 向指定玩家发送同步消息
     * @param player 目标玩家
     * @param points 序列化据点列表
     */
    public static void sendToPlayer(ServerPlayer player, List<CapturePoint.SerializableCapturePoint> points) {
        try {
            NetworkHandler.INSTANCE.send(PacketDistributor.PLAYER.with(() -> player), new SyncCapturePointsMessage(points));
        } catch (Exception e) {
            ModLogger.syncError("Failed to send sync message to player: " + e.getMessage());
        }
    }
    
    /**
     * 向所有玩家广播同步消息
     */
    public static void broadcastToAll() {
        try {
            List<CapturePoint.SerializableCapturePoint> points = CapturePointManager.getInstance().getAllSerializablePoints();
            NetworkHandler.INSTANCE.send(PacketDistributor.ALL.noArg(), new SyncCapturePointsMessage(points));
        } catch (Exception e) {
            ModLogger.syncError("Failed to broadcast sync message: " + e.getMessage());
        }
    }
    
    /**
     * 向所有玩家广播同步消息
     * @param points 序列化据点列表
     */
    public static void broadcastToAll(List<CapturePoint.SerializableCapturePoint> points) {
        try {
            NetworkHandler.INSTANCE.send(PacketDistributor.ALL.noArg(), new SyncCapturePointsMessage(points));
        } catch (Exception e) {
            ModLogger.syncError("Failed to broadcast sync message: " + e.getMessage());
        }
    }
}
