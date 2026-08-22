package org.espetro.client.vehicle;

import net.minecraft.client.Minecraft;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.espetro.network.NetworkManager;
import org.espetro.network.SeatSwitchReadyPacket;
import org.espetro.vehicle.SbwVehicleSeatResolver;
import org.espetro.vehicle.VehicleInteractionConfig;

/**
 * Seat-switch channel for SBW (Shift + hotbar).
 * Progress fills while Shift is held. Only after the channel completes
 * ({@link #isArmed()}) may the client send {@code ChangeVehicleSeatMessage}.
 */
public final class SeatSwitchGate {

    private static int switchTicks;
    private static boolean armed;
    private static boolean registered;
    private static long armedUntilClientTick;

    private SeatSwitchGate() {
    }

    public static void register() {
        if (registered) {
            return;
        }
        registered = true;
        MinecraftForge.EVENT_BUS.register(SeatSwitchGate.class);
    }

    /** True for a short window after the channel completes. */
    public static boolean isArmed() {
        if (!armed) {
            return VehicleInteractionConfig.seatSwitchDelayTicks() <= 0;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return false;
        }
        return mc.level.getGameTime() <= armedUntilClientTick;
    }

    /** Consume the one-shot arm after a seat change is dispatched. */
    public static void consumeArmed() {
        armed = false;
        switchTicks = 0;
        armedUntilClientTick = 0L;
        if (VehicleInteractionState.kind() == VehicleInteractionKind.SEAT_SWITCH) {
            VehicleInteractionState.clear();
        }
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.player == null || mc.screen != null) {
            reset();
            return;
        }
        if (mc.player.getVehicle() == null
            || !SbwVehicleSeatResolver.isSupportedVehicle(mc.player.getVehicle())) {
            reset();
            return;
        }

        int delay = VehicleInteractionConfig.seatSwitchDelayTicks();
        if (delay <= 0) {
            armed = true;
            armedUntilClientTick = Long.MAX_VALUE / 4;
            return;
        }

        boolean shiftDown = mc.options.keyShift.isDown();
        if (!shiftDown) {
            if (armed && mc.level != null && mc.level.getGameTime() > armedUntilClientTick) {
                reset();
            } else if (!armed) {
                reset();
            }
            return;
        }

        if (armed) {
            VehicleInteractionState.setSeatSwitch(1f);
            return;
        }

        switchTicks++;
        float progress = Math.min(1f, switchTicks / (float) delay);
        VehicleInteractionState.setSeatSwitch(progress);
        if (switchTicks >= delay) {
            armed = true;
            armedUntilClientTick = mc.level.getGameTime() + 40L;
            NetworkManager.NET.sendToServer(new SeatSwitchReadyPacket());
            VehicleInteractionState.setSeatSwitch(1f);
        }
    }

    private static void reset() {
        if ((switchTicks > 0 || armed)
            && VehicleInteractionState.kind() == VehicleInteractionKind.SEAT_SWITCH) {
            VehicleInteractionState.clear();
        }
        switchTicks = 0;
        armed = false;
        armedUntilClientTick = 0L;
    }
}
