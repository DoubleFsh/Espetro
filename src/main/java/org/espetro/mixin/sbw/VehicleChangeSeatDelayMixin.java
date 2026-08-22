package org.espetro.mixin.sbw;

import net.minecraft.world.entity.Entity;
import org.espetro.client.vehicle.SeatSwitchGate;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Client-side: block SBW predictive {@code changeSeat} until the seat-switch
 * channel is armed. Server role checks stay in {@link VehicleEntitySeatAccessMixin}.
 */
@Pseudo
@Mixin(targets = "com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity",
    remap = false)
public abstract class VehicleChangeSeatDelayMixin {

    @Inject(method = "changeSeat", at = @At("HEAD"), cancellable = true, require = 0, remap = false)
    private void espetro$gateClientSeat(Entity entity, int index,
                                        CallbackInfoReturnable<Boolean> cir) {
        if (entity == null || entity.level() == null || !entity.level().isClientSide) {
            return;
        }
        if (SeatSwitchGate.isArmed()) {
            SeatSwitchGate.consumeArmed();
            return;
        }
        cir.setReturnValue(false);
    }
}
