package org.espetro.ping;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.event.entity.EntityMountEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.espetro.Espetro;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.espetro.vehicle.SbwVehicleSeatResolver;

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

    private static final Map<UUID, SbwVehicleSeatResolver.SeatState> CACHE =
        new ConcurrentHashMap<>();

    private VehicleSeatPingCache() {
    }

    public static boolean canPingFromVehicle(UUID playerId) {
        SbwVehicleSeatResolver.SeatState state = CACHE.get(playerId);
        return state != null && allowsPing(state.kind(), state.seatIndex());
    }

    /**
     * 标点请求的服务端最终校验。缓存只用于事件驱动提示，最终权限始终读取
     * 玩家当前 root vehicle，避免上下车事件顺序或换座造成短暂脏数据。
     */
    public static boolean canPingFromVehicle(ServerPlayer player) {
        SbwVehicleSeatResolver.SeatState state = SbwVehicleSeatResolver.resolveCurrent(player);
        if (state == null) {
            clear(player != null ? player.getUUID() : null);
            return false;
        }
        CACHE.put(player.getUUID(), state);
        return allowsPing(state.kind(), state.seatIndex());
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

    private static boolean allowsPing(SbwVehicleSeatResolver.Kind kind, int seat) {
        return switch (kind) {
            case TANK -> seat >= 0 && seat <= 2;
            case IFV, HELICOPTER -> seat >= 0 && seat <= 1;
            case OTHER -> false;
        };
    }
}
