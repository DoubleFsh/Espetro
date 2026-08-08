package org.espetro.vehicle;

import net.minecraft.server.TickTask;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

import javax.annotation.Nullable;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.function.Function;

/**
 * Optional SBW seat API bridge shared by vehicle permissions and tactical pings.
 *
 * <p>The class contains no direct SBW references, so Espetro remains loadable when
 * Superb Warfare and DragonRise are absent. DragonRise vehicles inherit SBW's
 * VehicleEntity and are resolved by the same cached accessors.</p>
 */
public final class SbwVehicleSeatResolver {

    public enum Kind {
        TANK,
        IFV,
        HELICOPTER,
        OTHER
    }

    public record SeatState(Kind kind, int seatIndex, Entity vehicle) {
    }

    private static final String VEHICLE_CLASS_NAME =
        "com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity";
    private static volatile VehicleAccess vehicleAccess;

    private SbwVehicleSeatResolver() {
    }

    @Nullable
    public static SeatState resolveCurrent(@Nullable ServerPlayer player) {
        if (player == null || !player.isPassenger()) return null;
        Entity vehicle = player.getRootVehicle();
        if (vehicle == player) return null;
        int seat = getSeatIndex(vehicle, player);
        if (seat < 0) return null;
        return new SeatState(getKind(vehicle), seat, vehicle);
    }

    public static boolean isSupportedVehicle(@Nullable Entity vehicle) {
        VehicleAccess access = access();
        return vehicle != null && access != null && access.vehicleClass.isInstance(vehicle);
    }

    public static Kind getKind(@Nullable Entity vehicle) {
        VehicleAccess access = access();
        if (vehicle == null || access == null || !access.vehicleClass.isInstance(vehicle)) {
            return Kind.OTHER;
        }
        try {
            Object rawType = access.getVehicleType.invoke(vehicle);
            String type = rawType instanceof Enum<?> value ? value.name() : String.valueOf(rawType);
            return switch (type) {
                case "TANK" -> Kind.TANK;
                case "APC" -> Kind.IFV;
                case "HELICOPTER" -> Kind.HELICOPTER;
                default -> Kind.OTHER;
            };
        } catch (IllegalAccessException | InvocationTargetException ignored) {
            return Kind.OTHER;
        }
    }

    public static int getSeatIndex(@Nullable Entity vehicle, @Nullable Entity passenger) {
        VehicleAccess access = access();
        if (vehicle == null || passenger == null || access == null
            || !access.vehicleClass.isInstance(vehicle)) {
            return -1;
        }
        try {
            Object result = access.getSeatIndex.invoke(vehicle, passenger);
            return result instanceof Number number ? number.intValue() : -1;
        } catch (IllegalAccessException | InvocationTargetException ignored) {
            return -1;
        }
    }

    /**
     * Returns SBW's live, fixed-index seat list. Empty positions are represented by
     * {@code null}. The list is inspected synchronously on the server thread only.
     */
    @Nullable
    public static List<?> getOrderedSeatOccupants(@Nullable Entity vehicle) {
        VehicleAccess access = access();
        if (vehicle == null || access == null || !access.vehicleClass.isInstance(vehicle)) {
            return null;
        }
        try {
            Object rawPassengers = access.getOrderedPassengers.invoke(vehicle);
            return rawPassengers instanceof List<?> passengers ? passengers : null;
        } catch (IllegalAccessException | InvocationTargetException ignored) {
            return null;
        }
    }

    /**
     * Installs a one-shot SBW seat override for the current mount operation.
     *
     * <p>{@link net.minecraftforge.event.entity.EntityMountEvent} runs immediately
     * before SBW's {@code addPassenger}. The wrapper restores the vehicle's original
     * override as soon as SBW consumes it. A queued cleanup also restores it if
     * another mod aborts the mount before {@code addPassenger} is reached.</p>
     */
    public static boolean overrideNextMountSeat(@Nullable Entity vehicle,
                                                @Nullable ServerPlayer passenger,
                                                int seatIndex) {
        VehicleAccess access = access();
        if (vehicle == null || passenger == null || seatIndex < 0 || access == null
            || !access.vehicleClass.isInstance(vehicle)) {
            return false;
        }
        try {
            Object current = access.getEntityIndexOverride.invoke(vehicle);
            @SuppressWarnings("unchecked")
            Function<Entity, ?> original = current instanceof Function<?, ?> function
                ? (Function<Entity, ?>) function : null;
            OneShotSeatOverride override = new OneShotSeatOverride(
                access, vehicle, passenger, seatIndex, original);
            access.setEntityIndexOverride.invoke(vehicle, override);
            // MinecraftServer.execute() runs immediately when called from the server
            // thread. tell(TickTask) always queues, so cleanup cannot race ahead of
            // the synchronous startRiding -> addPassenger call that consumes it.
            passenger.server.tell(new TickTask(
                passenger.server.getTickCount(), override::restoreIfCurrent));
            return true;
        } catch (IllegalAccessException | InvocationTargetException | RuntimeException ignored) {
            return false;
        }
    }

    /**
     * Mirrors SBW's addPassenger seat selection before EntityMountEvent completes.
     * Returns -1 when no seat can be resolved, in which case callers fail open.
     */
    public static int predictMountSeat(@Nullable Entity vehicle, @Nullable Entity passenger) {
        VehicleAccess access = access();
        if (vehicle == null || passenger == null || access == null
            || !access.vehicleClass.isInstance(vehicle)) {
            return -1;
        }
        try {
            Object override = access.getEntityIndexOverride.invoke(vehicle);
            if (override instanceof Function<?, ?> rawFunction) {
                @SuppressWarnings("unchecked")
                Function<Entity, ?> function = (Function<Entity, ?>) rawFunction;
                Object result = function.apply(passenger);
                if (result instanceof Number number && number.intValue() != -1) {
                    return number.intValue();
                }
            }
            Object rawPassengers = access.getOrderedPassengers.invoke(vehicle);
            if (!(rawPassengers instanceof List<?> passengers)) return -1;
            for (int index = 0; index < passengers.size(); index++) {
                if (passengers.get(index) == null) return index;
            }
        } catch (IllegalAccessException | InvocationTargetException | RuntimeException ignored) {
            return -1;
        }
        return -1;
    }

    @Nullable
    private static VehicleAccess access() {
        VehicleAccess cached = vehicleAccess;
        if (cached != null) return cached.available ? cached : null;
        synchronized (SbwVehicleSeatResolver.class) {
            cached = vehicleAccess;
            if (cached == null) {
                try {
                    Class<?> vehicleClass = Class.forName(
                        VEHICLE_CLASS_NAME, false, SbwVehicleSeatResolver.class.getClassLoader());
                    cached = new VehicleAccess(
                        vehicleClass,
                        vehicleClass.getMethod("getVehicleType"),
                        vehicleClass.getMethod("getSeatIndex", Entity.class),
                        vehicleClass.getMethod("getOrderedPassengers"),
                        vehicleClass.getMethod("getEntityIndexOverride"),
                        vehicleClass.getMethod("setEntityIndexOverride", Function.class),
                        true);
                } catch (ReflectiveOperationException | LinkageError ignored) {
                    cached = VehicleAccess.UNAVAILABLE;
                }
                vehicleAccess = cached;
            }
        }
        return cached.available ? cached : null;
    }

    private record VehicleAccess(Class<?> vehicleClass, Method getVehicleType,
                                 Method getSeatIndex, Method getOrderedPassengers,
                                 Method getEntityIndexOverride, Method setEntityIndexOverride,
                                 boolean available) {
        private static final VehicleAccess UNAVAILABLE = new VehicleAccess(
            Object.class, null, null, null, null, null, false);
    }

    /** Server-thread-only function consumed by SBW's addPassenger implementation. */
    private static final class OneShotSeatOverride implements Function<Entity, Integer> {
        private final VehicleAccess access;
        private final Entity vehicle;
        private final Entity passenger;
        private final int seatIndex;
        private final Function<Entity, ?> original;

        private OneShotSeatOverride(VehicleAccess access, Entity vehicle, Entity passenger,
                                    int seatIndex, @Nullable Function<Entity, ?> original) {
            this.access = access;
            this.vehicle = vehicle;
            this.passenger = passenger;
            this.seatIndex = seatIndex;
            this.original = original;
        }

        @Override
        public Integer apply(Entity candidate) {
            restoreIfCurrent();
            if (candidate == passenger) return seatIndex;
            if (original == null) return -1;
            try {
                Object result = original.apply(candidate);
                return result instanceof Number number ? number.intValue() : -1;
            } catch (RuntimeException ignored) {
                return -1;
            }
        }

        private void restoreIfCurrent() {
            try {
                Object current = access.getEntityIndexOverride.invoke(vehicle);
                if (current == this) {
                    access.setEntityIndexOverride.invoke(vehicle, original);
                }
            } catch (IllegalAccessException | InvocationTargetException | RuntimeException ignored) {
                // A failed cleanup must not make an otherwise valid mount fatal.
            }
        }
    }
}
