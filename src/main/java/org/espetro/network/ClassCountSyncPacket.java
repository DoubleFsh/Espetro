package org.espetro.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;
import org.espetro.team.ClassCountManager;
import org.espetro.team.FactionDataLoader;
import org.espetro.team.FactionDataProvider;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 职业人数同步包
 * 客户端请求人数 -> 服务器响应人数
 * 或服务器发送错误消息 -> 客户端显示
 */
public class ClassCountSyncPacket {

    private String factionId;
    private Map<String, Integer> classCounts;
    private boolean isError;
    private String errorMessage;

    // 客户端请求构造函数
    public ClassCountSyncPacket(String factionId) {
        this.factionId = factionId;
        this.classCounts = new HashMap<>();
        this.isError = false;
        this.errorMessage = "";
    }

    // 服务器响应构造函数
    public ClassCountSyncPacket(Map<String, Integer> counts, String factionId) {
        this.factionId = factionId;
        this.classCounts = counts;
        this.isError = false;
        this.errorMessage = "";
    }

    // 错误消息构造函数
    public ClassCountSyncPacket(String message, boolean isError) {
        this.factionId = "";
        this.classCounts = new HashMap<>();
        this.isError = true;
        this.errorMessage = message;
    }

    public static ClassCountSyncPacket read(FriendlyByteBuf buf) {
        String factionId = buf.readUtf();
        boolean isError = buf.readBoolean();

        if (isError) {
            String message = buf.readUtf();
            return new ClassCountSyncPacket(message, true);
        }

        Map<String, Integer> counts = new HashMap<>();
        int size = buf.readInt();
        for (int i = 0; i < size; i++) {
            String classId = buf.readUtf();
            int count = buf.readInt();
            counts.put(classId, count);
        }

        return new ClassCountSyncPacket(counts, factionId);
    }

    public void write(FriendlyByteBuf buf) {
        buf.writeUtf(factionId);
        buf.writeBoolean(isError);

        if (isError) {
            buf.writeUtf(errorMessage);
        } else {
            buf.writeInt(classCounts.size());
            for (Map.Entry<String, Integer> entry : classCounts.entrySet()) {
                buf.writeUtf(entry.getKey());
                buf.writeInt(entry.getValue());
            }
        }
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();

            if (player != null) {
                // 服务端处理：收到请求，返回人数数据
                ClassCountManager countManager = ClassCountManager.getInstance();
                String team = countManager.getEffectivePlayerTeam(player.getUUID());
                Map<String, Integer> counts = countManager.getCountsForFaction(team, factionId);
                ClassCountSyncPacket response = new ClassCountSyncPacket(counts, factionId);
                NetworkManager.NET.send(PacketDistributor.PLAYER.with(() -> player), response);
            } else {
                // 客户端处理：通过反射调用客户端handler（避免服务端类加载）
                try {
                    Class.forName("org.espetro.client.ClientPacketHandlers")
                        .getMethod("handleClassCountSync", ClassCountSyncPacket.class)
                        .invoke(null, this);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        });
        ctx.get().setPacketHandled(true);
    }

    public boolean isError() { return isError; }
    public String getErrorMessage() { return errorMessage; }
    public Map<String, Integer> getClassCounts() { return classCounts; }
}
