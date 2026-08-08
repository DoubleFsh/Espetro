package org.espetro.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;
import org.espetro.vehicle.VehicleManager;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * 载具补给状态同步包（服务端→客户端）
 * 在每次载具装载/卸载操作后发送，更新客户端显示的补给条。
 */
public class VehicleSupplySyncPacket {

    private final UUID vehicleId;
    private final int ammo;
    private final int construction;
    private final int maxCapacity;
    private final boolean canCarryConstruction;

    public VehicleSupplySyncPacket(UUID vehicleId, int ammo, int construction,
                                    int maxCapacity, boolean canCarryConstruction) {
        this.vehicleId = vehicleId;
        this.ammo = ammo;
        this.construction = construction;
        this.maxCapacity = maxCapacity;
        this.canCarryConstruction = canCarryConstruction;
    }

    public UUID getVehicleId() { return vehicleId; }
    public int getAmmo() { return ammo; }
    public int getConstruction() { return construction; }
    public int getMaxCapacity() { return maxCapacity; }
    public boolean canCarryConstruction() { return canCarryConstruction; }
    /** 用于判断是否为客户端请求（ammo < 0 表示客户端请求） */
    public boolean isRequest() { return ammo < 0; }

    public static VehicleSupplySyncPacket read(FriendlyByteBuf buf) {
        return new VehicleSupplySyncPacket(
            buf.readUUID(), buf.readVarInt(), buf.readVarInt(),
            buf.readVarInt(), buf.readBoolean());
    }

    public void write(FriendlyByteBuf buf) {
        buf.writeUUID(vehicleId);
        buf.writeVarInt(ammo);
        buf.writeVarInt(construction);
        buf.writeVarInt(maxCapacity);
        buf.writeBoolean(canCarryConstruction);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            var sender = ctx.get().getSender();
            if (sender != null) {
                // 服务端收到：客户端请求同步 → 返回当前补给状态
                handleServerRequest(sender);
            } else {
                // 客户端收到：更新 UI
                try {
                    Class.forName("org.espetro.client.ClientPacketHandlers")
                        .getMethod("handleVehicleSupplySync", VehicleSupplySyncPacket.class)
                        .invoke(null, this);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        });
        ctx.get().setPacketHandled(true);
    }

    private void handleServerRequest(net.minecraft.server.level.ServerPlayer player) {
        VehicleManager vm = VehicleManager.getInstance();
        String factionId = vm.getVehicleFactionId(vehicleId);
        String vehicleType = vm.getVehicleType(vehicleId);
        if (factionId == null || vehicleType == null) return;
        VehicleManager.VehicleSupplyState supply = vm.getOrCreateVehicleSupply(vehicleId, factionId, vehicleType);
        if (supply == null) return;
        VehicleSupplySyncPacket response = new VehicleSupplySyncPacket(vehicleId,
            supply.getAmmo(), supply.getConstruction(),
            supply.getMaxCapacity(), supply.canCarryConstruction());
        NetworkManager.NET.send(PacketDistributor.PLAYER.with(() -> player), response);
    }
}
