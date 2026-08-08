package org.espetro.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

import java.util.UUID;
import java.util.function.Supplier;

/** Server-authoritative vehicle inventory and radial capability snapshot. */
public final class VehicleSupplySyncPacket {

    private final boolean request;
    private final UUID vehicleId;
    private final int ammo;
    private final int construction;
    private final int maxCapacity;
    private final boolean supplyVehicle;
    private final boolean fightVehicle;
    private final boolean transferAmmo;
    private final boolean transferConstruction;
    private final int transferIntervalTicks;

    private VehicleSupplySyncPacket(boolean request, UUID vehicleId, int ammo, int construction,
                                    int maxCapacity, boolean supplyVehicle, boolean fightVehicle,
                                    boolean transferAmmo, boolean transferConstruction,
                                    int transferIntervalTicks) {
        this.request = request;
        this.vehicleId = vehicleId;
        this.ammo = ammo;
        this.construction = construction;
        this.maxCapacity = maxCapacity;
        this.supplyVehicle = supplyVehicle;
        this.fightVehicle = fightVehicle;
        this.transferAmmo = transferAmmo;
        this.transferConstruction = transferConstruction;
        this.transferIntervalTicks = Math.max(1, transferIntervalTicks);
    }

    public static VehicleSupplySyncPacket request(UUID vehicleId) {
        return new VehicleSupplySyncPacket(true, vehicleId, 0, 0, 0,
            false, false, false, false, 20);
    }

    public static VehicleSupplySyncPacket state(UUID vehicleId, int ammo, int construction,
                                                int maxCapacity, boolean supplyVehicle,
                                                boolean fightVehicle, boolean transferAmmo,
                                                boolean transferConstruction,
                                                int transferIntervalTicks) {
        return new VehicleSupplySyncPacket(false, vehicleId, ammo, construction, maxCapacity,
            supplyVehicle, fightVehicle, transferAmmo, transferConstruction,
            transferIntervalTicks);
    }

    public UUID getVehicleId() { return vehicleId; }
    public int getAmmo() { return ammo; }
    public int getConstruction() { return construction; }
    public int getMaxCapacity() { return maxCapacity; }
    public boolean isSupplyVehicle() { return supplyVehicle; }
    public boolean isFightVehicle() { return fightVehicle; }
    public boolean canTransferAmmo() { return transferAmmo; }
    public boolean canTransferConstruction() { return transferConstruction; }
    public int getTransferIntervalTicks() { return transferIntervalTicks; }
    public boolean canCarryConstruction() { return supplyVehicle; }
    public boolean isRequest() { return request; }
    public boolean hasAnyAction() {
        return transferAmmo || transferConstruction || supplyVehicle;
    }

    public static VehicleSupplySyncPacket read(FriendlyByteBuf buf) {
        boolean request = buf.readBoolean();
        UUID id = buf.readUUID();
        if (request) return request(id);
        return state(id, buf.readVarInt(), buf.readVarInt(), buf.readVarInt(),
            buf.readBoolean(), buf.readBoolean(), buf.readBoolean(), buf.readBoolean(),
            buf.readVarInt());
    }

    public void write(FriendlyByteBuf buf) {
        buf.writeBoolean(request);
        buf.writeUUID(vehicleId);
        if (request) return;
        buf.writeVarInt(ammo);
        buf.writeVarInt(construction);
        buf.writeVarInt(maxCapacity);
        buf.writeBoolean(supplyVehicle);
        buf.writeBoolean(fightVehicle);
        buf.writeBoolean(transferAmmo);
        buf.writeBoolean(transferConstruction);
        buf.writeVarInt(transferIntervalTicks);
    }

    public void handle(Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context context = supplier.get();
        context.enqueueWork(() -> {
            ServerPlayer sender = context.getSender();
            if (sender != null) {
                VehicleSupplySyncPacket response =
                    VehicleSupplyActionPacket.createSyncResponse(sender, vehicleId);
                if (response != null) {
                    NetworkManager.NET.send(PacketDistributor.PLAYER.with(() -> sender), response);
                }
            } else {
                try {
                    Class.forName("org.espetro.client.ClientPacketHandlers")
                        .getMethod("handleVehicleSupplySync", VehicleSupplySyncPacket.class)
                        .invoke(null, this);
                } catch (ReflectiveOperationException e) {
                    org.espetro.Espetro.LOGGER.error("处理载具补给同步失败", e);
                }
            }
        });
        context.setPacketHandled(true);
    }
}
