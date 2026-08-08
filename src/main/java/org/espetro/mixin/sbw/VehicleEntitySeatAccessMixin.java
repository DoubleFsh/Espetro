package org.espetro.mixin.sbw;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import org.espetro.vehicle.VehicleSeatAccessPolicy;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Prevents SBW's direct changeSeat packet from bypassing Espetro role checks. */
@Pseudo
@Mixin(targets = "com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity",
    remap = false)
public abstract class VehicleEntitySeatAccessMixin {

    @Inject(method = "changeSeat", at = @At("HEAD"), cancellable = true,
        require = 1, remap = false)
    private void espetro$checkSeatRole(Entity passenger, int seatIndex,
                                       CallbackInfoReturnable<Boolean> cir) {
        if (passenger instanceof ServerPlayer player
            && !VehicleSeatAccessPolicy.checkSeatChange(
                player, (Entity) (Object) this, seatIndex)) {
            cir.setReturnValue(false);
        }
    }
}
