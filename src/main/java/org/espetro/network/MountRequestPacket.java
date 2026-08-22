package org.espetro.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import org.espetro.vehicle.VehicleMountServer;

import java.util.UUID;
import java.util.function.Supplier;

/** Client asks to begin / cancel / complete a mount channel on a vehicle. */
public final class MountRequestPacket {

    public enum Action {
        BEGIN, CANCEL, COMPLETE
    }

    private final Action action;
    private final UUID vehicleId;

    public MountRequestPacket(Action action, UUID vehicleId) {
        this.action = action;
        this.vehicleId = vehicleId;
    }

    public static MountRequestPacket read(FriendlyByteBuf buf) {
        Action action = buf.readEnum(Action.class);
        UUID id = buf.readBoolean() ? buf.readUUID() : null;
        return new MountRequestPacket(action, id);
    }

    public void write(FriendlyByteBuf buf) {
        buf.writeEnum(action);
        buf.writeBoolean(vehicleId != null);
        if (vehicleId != null) {
            buf.writeUUID(vehicleId);
        }
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ServerPlayer sender = ctx.get().getSender();
        ctx.get().enqueueWork(() -> {
            if (sender == null) {
                return;
            }
            switch (action) {
                case BEGIN -> VehicleMountServer.begin(sender, vehicleId);
                case CANCEL -> VehicleMountServer.cancel(sender);
                case COMPLETE -> VehicleMountServer.complete(sender, vehicleId);
            }
        });
        ctx.get().setPacketHandled(true);
    }
}
