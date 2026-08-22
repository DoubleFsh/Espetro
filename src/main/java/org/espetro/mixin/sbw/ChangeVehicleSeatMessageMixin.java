package org.espetro.mixin.sbw;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.network.NetworkEvent;
import org.espetro.vehicle.SeatSwitchServer;
import org.espetro.vehicle.VehicleSeatAccessPolicy;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.Supplier;

/** Server-side guard: seat-switch delay token, then crew-role policy. */
@Pseudo
@Mixin(targets = "com.atsuishio.superbwarfare.network.message.send.ChangeVehicleSeatMessage",
    remap = false)
public abstract class ChangeVehicleSeatMessageMixin {

    @Shadow(remap = false)
    public abstract int getIndex();

    @Inject(method = "handler", at = @At("HEAD"), cancellable = true,
        require = 1, remap = false)
    private void espetro$checkRequestedSeat(Supplier<NetworkEvent.Context> contextSupplier,
                                            CallbackInfo ci) {
        NetworkEvent.Context context = contextSupplier == null ? null : contextSupplier.get();
        ServerPlayer player = context == null ? null : context.getSender();
        if (player == null) return;
        if (!SeatSwitchServer.isReady(player)) {
            ci.cancel();
            return;
        }
        Entity vehicle = player.getVehicle();
        if (vehicle != null
            && !VehicleSeatAccessPolicy.checkSeatChange(player, vehicle, getIndex())) {
            ci.cancel();
        }
    }

    @Inject(method = "handler", at = @At("RETURN"), require = 0, remap = false)
    private void espetro$consumeSeatChannel(Supplier<NetworkEvent.Context> contextSupplier,
                                            CallbackInfo ci) {
        if (ci.isCancelled()) {
            return;
        }
        NetworkEvent.Context context = contextSupplier == null ? null : contextSupplier.get();
        ServerPlayer player = context == null ? null : context.getSender();
        if (player != null) {
            SeatSwitchServer.consumeReady(player);
        }
    }
}
