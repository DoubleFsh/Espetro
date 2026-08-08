package org.espetro.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/** Vehicle deployment snapshot; background updates never force-open a screen. */
public final class VehicleDeployScreenPacket {

    private final boolean openScreen;
    private final List<VehicleInfo> vehicles;

    public VehicleDeployScreenPacket(boolean openScreen, List<VehicleInfo> vehicles) {
        this.openScreen = openScreen;
        this.vehicles = List.copyOf(vehicles);
    }

    public static VehicleDeployScreenPacket read(FriendlyByteBuf buf) {
        boolean open = buf.readBoolean();
        int size = Math.min(128, Math.max(0, buf.readVarInt()));
        List<VehicleInfo> vehicles = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            vehicles.add(new VehicleInfo(
                buf.readUtf(128), buf.readUtf(256), buf.readVarInt(), buf.readVarInt(),
                buf.readLong(), buf.readVarInt()));
        }
        return new VehicleDeployScreenPacket(open, vehicles);
    }

    public void write(FriendlyByteBuf buf) {
        buf.writeBoolean(openScreen);
        buf.writeVarInt(Math.min(vehicles.size(), 128));
        for (int i = 0; i < vehicles.size() && i < 128; i++) {
            VehicleInfo vehicle = vehicles.get(i);
            buf.writeUtf(vehicle.type, 128);
            buf.writeUtf(vehicle.displayName, 256);
            buf.writeVarInt(vehicle.max);
            buf.writeVarInt(vehicle.current);
            buf.writeLong(vehicle.readyAtEpochMs);
            buf.writeVarInt(vehicle.respawnMinutes);
        }
    }

    public void handle(Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context context = supplier.get();
        context.enqueueWork(() -> {
            try {
                Class.forName("org.espetro.client.ClientPacketHandlers")
                    .getMethod("handleVehicleDeployScreen", VehicleDeployScreenPacket.class)
                    .invoke(null, this);
            } catch (ReflectiveOperationException e) {
                org.espetro.Espetro.LOGGER.error("处理载具部署同步失败", e);
            }
        });
        context.setPacketHandled(true);
    }

    public boolean shouldOpenScreen() { return openScreen; }
    public List<VehicleInfo> getVehicles() { return vehicles; }

    public static final class VehicleInfo {
        public final String type;
        public final String displayName;
        public final int max;
        public final int current;
        public final long readyAtEpochMs;
        public final int respawnMinutes;

        public VehicleInfo(String type, String displayName, int max, int current,
                           long readyAtEpochMs, int respawnMinutes) {
            this.type = type;
            this.displayName = displayName;
            this.max = max;
            this.current = current;
            this.readyAtEpochMs = readyAtEpochMs;
            this.respawnMinutes = respawnMinutes;
        }
    }
}
