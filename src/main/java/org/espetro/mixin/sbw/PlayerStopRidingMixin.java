package org.espetro.mixin.sbw;

import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import org.espetro.vehicle.DismountServer;
import org.espetro.vehicle.VehicleInteractionConfig;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.Supplier;

/** Reject SBW PlayerStopRiding until Espetro dismount channel completed. */
@Pseudo
@Mixin(targets = "com.atsuishio.superbwarfare.network.message.send.PlayerStopRidingMessage",
    remap = false)
public abstract class PlayerStopRidingMixin {

    @Inject(method = "handler", at = @At("HEAD"), cancellable = true, require = 0, remap = false)
    private void espetro$requireDismountChannel(Supplier<NetworkEvent.Context> contextSupplier,
                                                CallbackInfo ci) {
        if (VehicleInteractionConfig.dismountDelayTicks() <= 0) {
            return;
        }
        NetworkEvent.Context context = contextSupplier == null ? null : contextSupplier.get();
        ServerPlayer player = context == null ? null : context.getSender();
        if (player == null) {
            return;
        }
        if (!DismountServer.consumeReady(player)) {
            ci.cancel();
        }
    }
}
