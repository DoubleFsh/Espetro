package org.espetro.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * 载具部署界面包（S→C）
 * 指挥官右键部署棍时推送，替代聊天可点击消息
 */
public class VehicleDeployScreenPacket {

    private final List<VehicleInfo> vehicles;

    public VehicleDeployScreenPacket(List<VehicleInfo> vehicles) {
        this.vehicles = vehicles;
    }

    public VehicleDeployScreenPacket(FriendlyByteBuf buf) {
        int size = buf.readVarInt();
        this.vehicles = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            vehicles.add(new VehicleInfo(
                buf.readUtf(),    // type
                buf.readUtf(),    // displayName
                buf.readVarInt(), // max
                buf.readVarInt(), // current
                buf.readVarInt(), // cooldownRemaining (seconds, fits in varint for our use)
                buf.readVarInt()  // respawnMinutes
            ));
        }
    }

    public static VehicleDeployScreenPacket read(FriendlyByteBuf buf) {
        return new VehicleDeployScreenPacket(buf);
    }

    public void write(FriendlyByteBuf buf) {
        buf.writeVarInt(vehicles.size());
        for (VehicleInfo v : vehicles) {
            buf.writeUtf(v.type);
            buf.writeUtf(v.displayName);
            buf.writeVarInt(v.max);
            buf.writeVarInt(v.current);
            buf.writeVarInt(v.cooldownRemaining);
            buf.writeVarInt(v.respawnMinutes);
        }
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            try {
                Class.forName("org.espetro.client.ClientPacketHandlers")
                    .getMethod("handleVehicleDeployScreen", VehicleDeployScreenPacket.class)
                    .invoke(null, this);
            } catch (Exception e) {
                org.espetro.Espetro.LOGGER.error("Failed to handle VehicleDeployScreenPacket", e);
            }
        });
        ctx.get().setPacketHandled(true);
    }

    public List<VehicleInfo> getVehicles() {
        return vehicles;
    }

    /**
     * 单个载具信息
     */
    public static class VehicleInfo {
        public final String type;
        public final String displayName;
        public final int max;
        public final int current;
        public final int cooldownRemaining; // 秒
        public final int respawnMinutes;

        public VehicleInfo(String type, String displayName, int max, int current,
                           int cooldownRemaining, int respawnMinutes) {
            this.type = type;
            this.displayName = displayName;
            this.max = max;
            this.current = current;
            this.cooldownRemaining = cooldownRemaining;
            this.respawnMinutes = respawnMinutes;
        }
    }
}
