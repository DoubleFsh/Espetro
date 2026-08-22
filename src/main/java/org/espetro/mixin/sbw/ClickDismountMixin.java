package org.espetro.mixin.sbw;

import net.minecraft.world.entity.player.Player;
import org.espetro.vehicle.VehicleInteractionConfig;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Block SBW native DISMOUNT double-tap. Dismount is driven by INTERACT hold.
 */
@Pseudo
@Mixin(targets = "com.atsuishio.superbwarfare.event.ClickEventHandler", remap = false)
public abstract class ClickDismountMixin {

    @Inject(method = "handleDismountPress", at = @At("HEAD"), cancellable = true,
        require = 0, remap = false)
    private void espetro$blockNativeDismount(Player player, CallbackInfo ci) {
        if (VehicleInteractionConfig.dismountDelayTicks() <= 0) {
            return;
        }
        ci.cancel();
    }
}
