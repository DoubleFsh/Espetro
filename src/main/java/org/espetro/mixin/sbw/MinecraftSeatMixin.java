package org.espetro.mixin.sbw;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import org.espetro.client.vehicle.SeatSwitchGate;
import org.espetro.vehicle.SbwVehicleSeatResolver;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Gate SBW seat switching (Shift+hotbar in their MinecraftMixin) by temporarily
 * clearing hotbar key-down until Espetro's seat channel is armed.
 */
@Mixin(Minecraft.class)
public abstract class MinecraftSeatMixin {

    @Shadow
    public LocalPlayer player;

    @Shadow
    public net.minecraft.client.Options options;

    @Unique
    private boolean[] espetro$suppressedHotbar;

    @Inject(method = "handleKeybinds", at = @At("HEAD"), require = 0)
    private void espetro$suppressSeatHotbar(CallbackInfo ci) {
        espetro$suppressedHotbar = null;
        if (player == null || options == null) {
            return;
        }
        if (!SbwVehicleSeatResolver.isSupportedVehicle(player.getVehicle())) {
            return;
        }
        if (SeatSwitchGate.isArmed()) {
            return;
        }
        if (!options.keyShift.isDown()) {
            return;
        }
        KeyMapping[] hotbar = options.keyHotbarSlots;
        boolean[] wasDown = new boolean[hotbar.length];
        boolean any = false;
        for (int i = 0; i < hotbar.length; i++) {
            if (hotbar[i] != null && hotbar[i].isDown()) {
                wasDown[i] = true;
                any = true;
                hotbar[i].setDown(false);
            }
        }
        if (any) {
            espetro$suppressedHotbar = wasDown;
        }
    }

    @Inject(method = "handleKeybinds", at = @At("RETURN"), require = 0)
    private void espetro$restoreSeatHotbar(CallbackInfo ci) {
        if (espetro$suppressedHotbar == null || options == null) {
            return;
        }
        KeyMapping[] hotbar = options.keyHotbarSlots;
        for (int i = 0; i < hotbar.length && i < espetro$suppressedHotbar.length; i++) {
            if (espetro$suppressedHotbar[i] && hotbar[i] != null) {
                hotbar[i].setDown(true);
            }
        }
        espetro$suppressedHotbar = null;
    }
}
