package org.espetro.mixin.sbw;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.network.NetworkEvent;
import org.espetro.vehicle.VehicleSeatAccessPolicy;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.Supplier;

/** Server-side guard at SBW's network entry point for normal seat changes. */
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
        Entity vehicle = player.getVehicle();
        if (vehicle != null
            && !VehicleSeatAccessPolicy.checkSeatChange(player, vehicle, getIndex())) {
            ci.cancel();
        }
    }
}
