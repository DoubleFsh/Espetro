package org.espetro.vehicle;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.event.entity.EntityMountEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.espetro.Espetro;
import org.espetro.mapconfig.BattlefieldContext;
import org.espetro.team.ClassCountManager;
import org.espetro.team.FactionDataLoader;
import org.espetro.team.FactionDataProvider;
import org.espetro.team.GamePhase;
import org.espetro.team.GameStateManager;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Event-driven, server-authoritative access policy for restricted SBW seats. */
@Mod.EventBusSubscriber(modid = Espetro.MOD_ID)
public final class VehicleSeatAccessPolicy {

    private static final long DENIAL_MESSAGE_INTERVAL_MS = 1_000L;
    private static final Map<UUID, Long> LAST_DENIAL_MESSAGE = new ConcurrentHashMap<>();

    private VehicleSeatAccessPolicy() {
    }

    static int legacyVehicleCrewSeatCount(SbwVehicleSeatResolver.Kind kind) {
        if (kind == null) return 0;
        return switch (kind) {
            case TANK -> 3;
            case IFV -> 2;
            case HELICOPTER -> 1;
            case OTHER -> 0;
        };
    }

    static boolean requiresVehicleCrew(int requiredSeatCount, int seatIndex) {
        return seatIndex >= 0 && seatIndex < Math.max(0, requiredSeatCount);
    }

    static int firstAvailableUnrestrictedSeat(int requiredSeatCount,
                                              List<?> seatOccupants) {
        if (seatOccupants == null) return -1;
        for (int seatIndex = 0; seatIndex < seatOccupants.size(); seatIndex++) {
            if (seatOccupants.get(seatIndex) == null
                && !requiresVehicleCrew(requiredSeatCount, seatIndex)) {
                return seatIndex;
            }
        }
        return -1;
    }

    public static boolean resolvesVehicleCrew(Boolean configured, String icon) {
        if (configured != null) return configured;
        return icon != null && "crewman".equalsIgnoreCase(icon.trim());
    }

    public static boolean isVehicleCrew(ServerPlayer player) {
        ClassCountManager counts = ClassCountManager.getInstance();
        if (player == null || counts == null) return false;
        String classId = counts.getPlayerClass(player.getUUID());
        if (classId == null || classId.isBlank()) return false;
        FactionDataLoader.ClassKitData kit =
            FactionDataProvider.getOrCreateLoader().getClassKit(classId);
        return kit != null && kit.isVehicleCrew();
    }

    public static boolean mayUseSeat(ServerPlayer player, Entity vehicle, int seatIndex) {
        if (!isRestrictionActive(player)) return true;
        int requiredSeatCount = getRequiredVehicleCrewSeatCount(vehicle);
        return !requiresVehicleCrew(requiredSeatCount, seatIndex) || isVehicleCrew(player);
    }

    /** Used by the optional SBW changeSeat mixin. */
    public static boolean checkSeatChange(ServerPlayer player, Entity vehicle, int seatIndex) {
        boolean allowed = mayUseSeat(player, vehicle, seatIndex);
        if (!allowed) notifyDenied(player);
        return allowed;
    }

    /** Re-check after a successful class mutation; no polling is required. */
    public static void revalidateCurrentSeat(ServerPlayer player) {
        SbwVehicleSeatResolver.SeatState state =
            SbwVehicleSeatResolver.resolveCurrent(player);
        if (state == null || mayUseSeat(player, state.vehicle(), state.seatIndex())) return;
        player.stopRiding();
        notifyDenied(player);
    }

    public static void clear(UUID playerId) {
        if (playerId != null) LAST_DENIAL_MESSAGE.remove(playerId);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onMount(EntityMountEvent event) {
        if (event.getLevel().isClientSide() || !event.isMounting()
            || !(event.getEntityMounting() instanceof ServerPlayer player)) {
            return;
        }
        Entity vehicle = event.getEntityBeingMounted();
        if (!SbwVehicleSeatResolver.isSupportedVehicle(vehicle)) return;

        int requiredSeatCount = getRequiredVehicleCrewSeatCount(vehicle);
        if (!isRestrictionActive(player) || isVehicleCrew(player) || requiredSeatCount <= 0) {
            return;
        }

        // Non-crew players do not occupy an empty driver/gunner seat merely because
        // it appears first in SBW's seat list. Route this one mount operation to the
        // first empty unrestricted passenger seat instead.
        List<?> seats = SbwVehicleSeatResolver.getOrderedSeatOccupants(vehicle);
        int targetSeat = firstAvailableUnrestrictedSeat(requiredSeatCount, seats);
        if (targetSeat < 0
            || !SbwVehicleSeatResolver.overrideNextMountSeat(vehicle, player, targetSeat)) {
            event.setCanceled(true);
            notifyNoVacancy(player);
        }
    }

    static int getRequiredVehicleCrewSeatCount(Entity vehicle) {
        if (vehicle == null) return 0;
        VehicleManager manager = VehicleManager.getInstance();
        String factionId = manager.getVehicleFactionId(vehicle.getUUID());
        String vehicleType = manager.getVehicleType(vehicle.getUUID());
        if (factionId != null && vehicleType != null) {
            VehicleConfig.VehicleTypeConfig config =
                VehicleConfig.getVehicleConfig(factionId, vehicleType);
            if (config != null && config.vehicleCrewSeats != null) {
                return Math.max(0, config.vehicleCrewSeats);
            }
        }
        return legacyVehicleCrewSeatCount(SbwVehicleSeatResolver.getKind(vehicle));
    }

    private static boolean isRestrictionActive(ServerPlayer player) {
        if (player == null || !BattlefieldContext.isActiveBattlefield(player.serverLevel())) {
            return false;
        }
        GamePhase phase = GameStateManager.getInstance().getCurrentPhase();
        return phase == GamePhase.DEPLOYING || phase == GamePhase.BATTLE;
    }

    private static void notifyDenied(ServerPlayer player) {
        notify(player, "§c该座位仅限载具组员。");
    }

    private static void notifyNoVacancy(ServerPlayer player) {
        notify(player, "§c载具上没有空余位置了。");
    }

    private static void notify(ServerPlayer player, String message) {
        long now = System.currentTimeMillis();
        Long previous = LAST_DENIAL_MESSAGE.put(player.getUUID(), now);
        if (previous != null && now - previous < DENIAL_MESSAGE_INTERVAL_MS) return;
        player.displayClientMessage(Component.literal(message), true);
    }
}
