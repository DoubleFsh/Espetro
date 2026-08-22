package org.espetro.vehicle;

import net.minecraft.server.level.ServerPlayer;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class DismountServer {

    private static final Map<UUID, Long> READY_UNTIL = new HashMap<>();

    private DismountServer() {
    }

    public static void markReady(ServerPlayer player) {
        if (player == null) {
            return;
        }
        READY_UNTIL.put(player.getUUID(), player.serverLevel().getGameTime() + 40L);
    }

    public static boolean consumeReady(ServerPlayer player) {
        if (player == null) {
            return VehicleInteractionConfig.dismountDelayTicks() <= 0;
        }
        if (VehicleInteractionConfig.dismountDelayTicks() <= 0) {
            return true;
        }
        Long until = READY_UNTIL.remove(player.getUUID());
        if (until == null) {
            return false;
        }
        return player.serverLevel().getGameTime() <= until;
    }

    public static void clearAll() {
        READY_UNTIL.clear();
    }
}
