package org.espetro.mixin.tacz;

import net.minecraftforge.event.OnDatapackSyncEvent;
import org.espetro.compat.tacz.TaczGunPackSyncCompat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Replaces TaCZ 1.1.8's monolithic common-assets packet with bounded Espetro chunks. */
@Pseudo
@Mixin(targets = "com.tacz.guns.resource.CommonAssetsManager", remap = false)
public abstract class CommonAssetsManagerMixin {

    @Inject(method = "OnDatapackSync", at = @At("HEAD"), cancellable = true,
        require = 0, remap = false)
    private static void espetro$sendChunkedGunPackCache(OnDatapackSyncEvent event,
                                                        CallbackInfo callback) {
        if (TaczGunPackSyncCompat.sendChunked(event)) {
            callback.cancel();
        }
    }
}
