package org.espetro.client.vehicle;

import net.minecraft.client.Minecraft;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.espetro.client.gui.VehicleWheelController;
import org.espetro.network.DismountRequestPacket;
import org.espetro.network.NetworkManager;
import org.espetro.vehicle.SbwVehicleSeatResolver;
import org.espetro.vehicle.VehicleInteractionConfig;

/** Hold SBW INTERACT while seated to dismount after a progress channel. */
public final class DismountGate {

    private static boolean registered;
    private static int ticks;
    private static boolean wasDown;
    /** 已发送过一次下车请求；等待 F 释放后才允许再次触发，防止按住 F 持续循环下车。 */
    private static boolean sent;

    private DismountGate() {
    }

    public static void register() {
        if (registered) {
            return;
        }
        registered = true;
        MinecraftForge.EVENT_BUS.register(DismountGate.class);
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.screen != null) {
            reset();
            wasDown = false;
            return;
        }
        if (mc.player.getVehicle() == null
            || !SbwVehicleSeatResolver.isSupportedVehicle(mc.player.getVehicle())) {
            reset();
            wasDown = false;
            return;
        }

        int delay = VehicleInteractionConfig.dismountDelayTicks();
        if (delay <= 0) {
            reset();
            wasDown = false;
            return;
        }

        boolean down = VehicleWheelController.isInteractHeld();
        if (!down) {
            if (wasDown) {
                reset();
            }
            wasDown = false;
            sent = false;
            return;
        }
        wasDown = true;
        if (sent) {
            // 已发送过下车请求：本次按住 F 期间不再重复发送，等释放后再触发。
            return;
        }

        ticks++;
        float progress = Math.min(1f, ticks / (float) delay);
        VehicleInteractionState.setDismount(progress);
        if (ticks >= delay) {
            NetworkManager.NET.sendToServer(new DismountRequestPacket());
            sent = true;
            reset();
        }
    }

    private static void reset() {
        ticks = 0;
        if (VehicleInteractionState.kind() == VehicleInteractionKind.DISMOUNT) {
            VehicleInteractionState.clear();
        }
    }
}
