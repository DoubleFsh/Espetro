package org.espetro.vehicle;

import net.minecraft.server.level.ServerPlayer;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Players who finished the client seat-switch channel may change seats once.
 * Token is one-shot and expires quickly.
 */
public final class SeatSwitchServer {

    private static final Map<UUID, Long> READY_UNTIL_TICK = new HashMap<>();

    private SeatSwitchServer() {
    }

    public static void markReady(ServerPlayer player) {
        if (player == null) {
            return;
        }
        long now = player.serverLevel().getGameTime();
        READY_UNTIL_TICK.put(player.getUUID(), now + 40L);
    }

    public static boolean isReady(ServerPlayer player) {
        if (player == null) {
            return VehicleInteractionConfig.seatSwitchDelayTicks() <= 0;
        }
        if (VehicleInteractionConfig.seatSwitchDelayTicks() <= 0) {
            return true;
        }
        Long until = READY_UNTIL_TICK.get(player.getUUID());
        if (until == null) {
            return false;
        }
        long now = player.serverLevel().getGameTime();
        if (now > until) {
            READY_UNTIL_TICK.remove(player.getUUID());
            return false;
        }
        return true;
    }

    public static void consumeReady(ServerPlayer player) {
        if (player != null) {
            READY_UNTIL_TICK.remove(player.getUUID());
        }
    }

    public static void clearAll() {
        READY_UNTIL_TICK.clear();
    }
}
