package com.example.hcrpoints.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 同步玩家位置的网络消息
 */
public class SyncPlayerPositionsMessage {
    private final Map<UUID, PlayerPosition> playerPositions;

    public SyncPlayerPositionsMessage(Map<UUID, PlayerPosition> playerPositions) {
        this.playerPositions = new HashMap<>(playerPositions);
    }

    /**
     * 玩家位置类
     */
    public static class PlayerPosition {
        private final double x;
        private final double y;
        private final double z;
        private final String name;
        private final String teamName;

        public PlayerPosition(double x, double y, double z, String name) {
            this(x, y, z, name, "");
        }

        public PlayerPosition(double x, double y, double z, String name, String teamName) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.name = name;
            this.teamName = teamName;
        }

        public double getX() { return x; }
        public double getY() { return y; }
        public double getZ() { return z; }
        public String getName() { return name; }
        public String getTeamName() { return teamName; }
    }

    /**
     * 编码消息
     */
    public static void encode(SyncPlayerPositionsMessage msg, FriendlyByteBuf buf) {
        buf.writeInt(msg.playerPositions.size());
        for (Map.Entry<UUID, PlayerPosition> entry : msg.playerPositions.entrySet()) {
            UUID uuid = entry.getKey();
            PlayerPosition pos = entry.getValue();
            buf.writeUUID(uuid);
            buf.writeDouble(pos.getX());
            buf.writeDouble(pos.getY());
            buf.writeDouble(pos.getZ());
            buf.writeUtf(pos.getName());
            buf.writeUtf(pos.getTeamName());
        }
    }

    /**
     * 解码消息
     */
    public static SyncPlayerPositionsMessage decode(FriendlyByteBuf buf) {
        int size = buf.readInt();
        Map<UUID, PlayerPosition> positions = new HashMap<>(size);
        for (int i = 0; i < size; i++) {
            UUID uuid = buf.readUUID();
            double x = buf.readDouble();
            double y = buf.readDouble();
            double z = buf.readDouble();
            String name = buf.readUtf();
            String teamName = buf.readUtf();
            positions.put(uuid, new PlayerPosition(x, y, z, name, teamName));
        }
        return new SyncPlayerPositionsMessage(positions);
    }

    /**
     * 处理消息
     */
    public static void handle(SyncPlayerPositionsMessage msg, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> {
            // 只在客户端处理
            if (context.getDirection().getReceptionSide().isClient()) {
                // 更新客户端的玩家位置数据
                com.example.hcrpoints.hud.TacticalMapHUD.getInstance().syncPlayerPositionsFromServer(msg.playerPositions);
            }
        });
        context.setPacketHandled(true);
    }

    /**
     * 广播消息给所有玩家
     */
    public static void broadcastToAll(Map<UUID, PlayerPosition> playerPositions) {
        NetworkHandler.INSTANCE.send(
            PacketDistributor.ALL.noArg(),
            new SyncPlayerPositionsMessage(playerPositions)
        );
    }
}
