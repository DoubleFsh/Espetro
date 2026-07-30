package org.espetro.ping;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.event.entity.EntityMountEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.espetro.Espetro;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 缓存玩家在载具上的座位权限（上下车事件驱动，禁止 tick 扫描）。
 * <ul>
 *   <li>坦克类：座位 0/1/2</li>
 *   <li>步战/直升机：座位 0/1</li>
 *   <li>其它：否</li>
 * </ul>
 */
@Mod.EventBusSubscriber(modid = Espetro.MOD_ID)
public final class VehicleSeatPingCache {

    public enum Kind {
        TANK(Set.of(0, 1, 2)),
        IFV(Set.of(0, 1)),
        HELI(Set.of(0, 1)),
        NONE(Set.of());

        private final Set<Integer> seats;

        Kind(Set<Integer> seats) {
            this.seats = seats;
        }

        boolean allows(int seat) {
            return seats.contains(seat);
        }
    }

    private record SeatState(Kind kind, int seatIndex) {}

    private static final Map<UUID, SeatState> CACHE = new ConcurrentHashMap<>();

    private static final String SBW_VEHICLE_CLASS =
        "com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity";
    private static volatile VehicleAccess vehicleAccess;

    private VehicleSeatPingCache() {
    }

    public static boolean canPingFromVehicle(UUID playerId) {
        SeatState state = CACHE.get(playerId);
        return state != null && state.kind.allows(state.seatIndex);
    }

    /**
     * 标点请求的服务端最终校验。缓存只用于事件驱动提示，最终权限始终读取
     * 玩家当前 root vehicle，避免上下车事件顺序或换座造成短暂脏数据。
     */
    public static boolean canPingFromVehicle(ServerPlayer player) {
        SeatState state = resolveCurrentState(player);
        if (state == null) {
            clear(player != null ? player.getUUID() : null);
            return false;
        }
        CACHE.put(player.getUUID(), state);
        return state.kind.allows(state.seatIndex);
    }

    public static void clear(UUID playerId) {
        if (playerId != null) {
            CACHE.remove(playerId);
        }
    }

    @SubscribeEvent
    public static void onMount(EntityMountEvent event) {
        if (event.getLevel().isClientSide()) {
            return;
        }
        Entity entity = event.getEntityMounting();
        if (!(entity instanceof ServerPlayer player)) {
            return;
        }
        if (event.isDismounting()) {
            CACHE.remove(player.getUUID());
            return;
        }
        /*
         * EntityMountEvent 可能早于 passenger 列表落盘。延迟到服务端任务队列，
         * 只执行一次，不建立任何 tick 扫描。
         */
        player.server.execute(() -> canPingFromVehicle(player));
    }

    private static SeatState resolveCurrentState(ServerPlayer player) {
        if (player == null || !player.isPassenger()) {
            return null;
        }
        Entity vehicle = player.getRootVehicle();
        if (vehicle == player) {
            return null;
        }
        VehicleAccess access = access();
        if (access == null || !access.vehicleClass.isInstance(vehicle)) {
            return null;
        }
        try {
            Object rawType = access.getVehicleType.invoke(vehicle);
            String type = rawType instanceof Enum<?> value ? value.name() : String.valueOf(rawType);
            Kind kind = switch (type) {
                case "TANK" -> Kind.TANK;
                case "APC" -> Kind.IFV;
                case "HELICOPTER" -> Kind.HELI;
                default -> Kind.NONE;
            };
            Object rawSeat = access.getSeatIndex.invoke(vehicle, player);
            int seat = rawSeat instanceof Number number ? number.intValue() : -1;
            return kind == Kind.NONE || seat < 0 ? null : new SeatState(kind, seat);
        } catch (IllegalAccessException | InvocationTargetException ignored) {
            return null;
        }
    }

    private static VehicleAccess access() {
        VehicleAccess cached = vehicleAccess;
        if (cached != null) {
            return cached.available ? cached : null;
        }
        synchronized (VehicleSeatPingCache.class) {
            cached = vehicleAccess;
            if (cached == null) {
                try {
                    Class<?> vehicleClass = Class.forName(SBW_VEHICLE_CLASS);
                    cached = new VehicleAccess(
                        vehicleClass,
                        vehicleClass.getMethod("getVehicleType"),
                        vehicleClass.getMethod("getSeatIndex", Entity.class),
                        true);
                } catch (ReflectiveOperationException ignored) {
                    cached = VehicleAccess.UNAVAILABLE;
                }
                vehicleAccess = cached;
            }
        }
        return cached.available ? cached : null;
    }

    private record VehicleAccess(Class<?> vehicleClass, Method getVehicleType,
                                 Method getSeatIndex, boolean available) {
        private static final VehicleAccess UNAVAILABLE =
            new VehicleAccess(Object.class, null, null, false);
    }
}
