package org.espetro.vehicle;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.entity.EntityLeaveLevelEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.espetro.Espetro;

import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 载具事件处理器
 */
@Mod.EventBusSubscriber(modid = Espetro.MOD_ID)
public class VehicleEventHandler {

    /**
     * 指挥官右键放置补给站后、实体加入世界前，缓存放置者队伍（数 tick 内有效）。
     * key = 玩家 UUID
     */
    private static final Map<UUID, PendingSupplyPlace> PENDING_SUPPLY_PLACES = new ConcurrentHashMap<>();
    private static final int PENDING_PLACE_TTL_TICKS = 40;
    private static final double PLACE_MATCH_DISTANCE_SQ = 12.0 * 12.0;

    private record PendingSupplyPlace(String team, BlockPos near, long gameTime) {}

    /**
     * 载具死亡时从追踪中移除
     */
    @SubscribeEvent
    public static void onVehicleDeath(LivingDeathEvent event) {
        Entity entity = event.getEntity();

        if (entity.getTags().contains("espetro_vehicle")) {
            VehicleManager.getInstance().onVehicleDeath(entity.getUUID());
            Espetro.LOGGER.debug("载具 {} 已死亡，移除追踪", entity.getUUID());
        }

    }

    @SubscribeEvent
    public static void onVehicleLeaveLevel(EntityLeaveLevelEvent event) {
        if (event.getLevel().isClientSide()) return;

        Entity entity = event.getEntity();
        if (VehicleManager.isMappedSupplyStation(entity)
                && (entity.getRemovalReason() == Entity.RemovalReason.KILLED
                    || entity.getRemovalReason() == Entity.RemovalReason.DISCARDED)) {
            VehicleManager.getInstance().unregisterMappedSupplyStation(entity.getUUID());
        }
        if (entity.getTags().contains("espetro_vehicle")) {
            VehicleManager.getInstance().updateVehicleLocation(entity);
            if (entity.getRemovalReason() == Entity.RemovalReason.KILLED) {
                VehicleManager.getInstance().onVehicleDeath(entity.getUUID());
                Espetro.LOGGER.debug("载具 {} 已被杀毁，移除追踪并处理兵力扣除", entity.getUUID());
            } else if (entity.getRemovalReason() == Entity.RemovalReason.DISCARDED) {
                VehicleManager.getInstance().onVehicleRemoved(entity.getUUID());
                Espetro.LOGGER.debug("载具 {} 已被主动移除，清除追踪", entity.getUUID());
            } else {
                Espetro.LOGGER.debug("载具 {} 暂时离开已加载世界，保留停服清理追踪", entity.getUUID());
            }
        }

    }

    @SubscribeEvent
    public static void onVehicleJoinLevel(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide()) return;
        Entity entity = event.getEntity();
        if (entity.getTags().contains("espetro_vehicle")) {
            VehicleManager.getInstance().updateVehicleLocation(entity);
        }
    }

    /**
     * 指挥官（或任意持有部署器的玩家）右键准备放置 Dragonrise 补给站时，记录队伍，
     * 供随后 {@link #onSupplyStationJoinLevel} 打上 ESPoints 战术地图所需标签。
     */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onSupplyStationPlaceAttempt(PlayerInteractEvent.RightClickBlock event) {
        if (event.getLevel().isClientSide()) return;
        if (!(event.getEntity() instanceof ServerPlayer player)) return;

        ItemStack stack = event.getItemStack();
        if (!VehicleManager.isSupplyStationDeployerItem(stack)) return;

        String team = Espetro.getPlayerTeam(player);
        if (team == null || team.isBlank()) return;

        long gameTime = player.serverLevel().getGameTime();
        PENDING_SUPPLY_PLACES.put(
            player.getUUID(),
            new PendingSupplyPlace(team.trim().toUpperCase(), event.getPos().immutable(), gameTime)
        );
        prunePendingPlaces(gameTime);
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onSupplyStationPlaceAttemptItem(PlayerInteractEvent.RightClickItem event) {
        if (event.getLevel().isClientSide()) return;
        if (!(event.getEntity() instanceof ServerPlayer player)) return;

        ItemStack stack = event.getItemStack();
        if (!VehicleManager.isSupplyStationDeployerItem(stack)) return;

        String team = Espetro.getPlayerTeam(player);
        if (team == null || team.isBlank()) return;

        long gameTime = player.serverLevel().getGameTime();
        PENDING_SUPPLY_PLACES.put(
            player.getUUID(),
            new PendingSupplyPlace(team.trim().toUpperCase(), player.blockPosition().immutable(), gameTime)
        );
        prunePendingPlaces(gameTime);
    }

    /**
     * 新生成的 ammo_supply_station（非读档、尚未标记）→ 绑定放置者队伍，
     * 使 ESPoints SyncBastions / 战术地图显示该载具补给站。
     */
    @SubscribeEvent
    public static void onSupplyStationJoinLevel(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide()) return;
        if (!(event.getLevel() instanceof ServerLevel level)) return;

        Entity entity = event.getEntity();
        if (!VehicleManager.isAmmoSupplyStationEntity(entity)) return;
        // 读盘恢复或坑位预放的实体已经有持久标签，只需重新加入轻量索引。
        if (VehicleManager.isMappedSupplyStation(entity)) {
            VehicleManager.getInstance().registerMappedSupplyStation(entity);
            return;
        }
        if (event.loadedFromDisk()) return;

        long gameTime = level.getGameTime();
        prunePendingPlaces(gameTime);

        String team = resolveTeamForNewStation(entity, gameTime);
        VehicleManager.tagCommanderSupplyStation(entity, team);
        VehicleManager.getInstance().registerMappedSupplyStation(entity);
    }

    private static String resolveTeamForNewStation(Entity entity, long gameTime) {
        // 1) 匹配最近的放置意图（玩家右键缓存）
        String fromPending = null;
        double bestDist = PLACE_MATCH_DISTANCE_SQ;
        UUID bestPlayer = null;
        for (Map.Entry<UUID, PendingSupplyPlace> entry : PENDING_SUPPLY_PLACES.entrySet()) {
            PendingSupplyPlace pending = entry.getValue();
            if (gameTime - pending.gameTime() > PENDING_PLACE_TTL_TICKS) {
                continue;
            }
            double dist = entity.distanceToSqr(
                pending.near().getX() + 0.5,
                pending.near().getY() + 0.5,
                pending.near().getZ() + 0.5
            );
            // 也允许按实体自身位置与放置点比较（部署器会抬高 Y）
            double distEntity = entity.distanceToSqr(
                pending.near().getX() + 0.5,
                entity.getY(),
                pending.near().getZ() + 0.5
            );
            double d = Math.min(dist, distEntity);
            if (d <= bestDist) {
                bestDist = d;
                fromPending = pending.team();
                bestPlayer = entry.getKey();
            }
        }
        if (fromPending != null && bestPlayer != null) {
            PENDING_SUPPLY_PLACES.remove(bestPlayer);
            return fromPending;
        }

        // 2) 回退：附近 10 格内有队伍的玩家（放置者通常站在旁边）
        if (entity.level() instanceof ServerLevel serverLevel) {
            ServerPlayer nearest = null;
            double nearestDist = 10.0 * 10.0;
            for (ServerPlayer player : serverLevel.players()) {
                if (player.isSpectator()) continue;
                double d = player.distanceToSqr(entity);
                if (d > nearestDist) continue;
                String team = Espetro.getPlayerTeam(player);
                if (team == null || team.isBlank()) continue;
                nearestDist = d;
                nearest = player;
            }
            if (nearest != null) {
                return Espetro.getPlayerTeam(nearest);
            }
        }
        return null;
    }

    private static void prunePendingPlaces(long gameTime) {
        Iterator<Map.Entry<UUID, PendingSupplyPlace>> it = PENDING_SUPPLY_PLACES.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, PendingSupplyPlace> entry = it.next();
            if (gameTime - entry.getValue().gameTime() > PENDING_PLACE_TTL_TICKS) {
                it.remove();
            }
        }
    }

    // 载具部署木棍已移除；载具部署入口在 Alt 轮盘（DEPLOY_VEHICLE）。
}
