package org.espetro.vehicle;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.event.entity.EntityLeaveLevelEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.EntityMountEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.espetro.Espetro;
import org.espetro.team.SquadManager;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 载具事件处理器
 */
@Mod.EventBusSubscriber(modid = Espetro.MOD_ID)
public class VehicleEventHandler {

    /** 小队员载具认领申请：key = 小队 ID，同一时间每小队最多一条待处理申请。 */
    static final Map<Integer, PendingClaim> PENDING_CLAIMS = new ConcurrentHashMap<>();
    private static final long CLAIM_TIMEOUT_MS = 60_000L;

    record PendingClaim(UUID memberUuid, UUID vehicleUuid, long expiryMs) {}

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
            org.espetro.bastion.FortificationManager.getInstance()
                .removeEntity(entity.getUUID());
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
     * 新生成的 ammo_supply_station（非读档、尚未标记）→ 绑定放置者队伍，
     * 使 ESPoints SyncBastions / 战术地图显示该载具补给站。
     */
    @SubscribeEvent
    public static void onSupplyStationJoinLevel(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide()) return;
        if (!(event.getLevel() instanceof ServerLevel)) return;

        Entity entity = event.getEntity();
        if (!VehicleManager.isAmmoSupplyStationEntity(entity)) return;
        // 仅 Espetro“建造工事”产生、带持久映射标签的实体可进入战术地图。
        if (VehicleManager.isMappedSupplyStation(entity)) {
            VehicleManager.getInstance().registerMappedSupplyStation(entity);
        }
    }

    // 载具部署木棍已移除；载具部署入口在 Alt 轮盘（DEPLOY_VEHICLE）。

    // ======================== 载具小队归属 ========================

    /** 检查实体是否为 SBW VehicleEntity（通过类名判断，不直接依赖 superbwarfare 模组）。 */
    private static boolean isSbwVehicle(Entity entity) {
        Class<?> clazz = entity.getClass();
        while (clazz != null) {
            if ("com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity".equals(clazz.getName())) {
                return true;
            }
            clazz = clazz.getSuperclass();
        }
        return false;
    }

    private static String getVehicleDisplayName(Entity vehicle) {
        Component custom = vehicle.getCustomName();
        return custom != null ? custom.getString() : vehicle.getType().getDescription().getString();
    }

    /**
     * 玩家右键载具时检查小队归属规则。
     */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onVehicleEntityInteract(PlayerInteractEvent.EntityInteract event) {
        if (event.getLevel().isClientSide()) return;
        if (!(event.getEntity() instanceof ServerPlayer player)) return;

        Entity target = event.getTarget();
        if (!isSbwVehicle(target)) return;

        String team = Espetro.getPlayerTeam(player);
        if (team == null) return; // 不在阵营中的玩家不受限制

        team = team.toUpperCase();
        SquadManager sm = SquadManager.getInstance();
        int playerSquad = sm.getPlayerSquadId(player.getUUID());
        boolean isLeader = sm.isSquadLeader(player.getUUID());

        int vehicleSquad = VehicleSquadOwnership.getSquadId(target);
        boolean vehicleOwned = vehicleSquad != -1;
        boolean vehicleHasPassengers = !target.getPassengers().isEmpty();

        // 1) 载具上有人 → 任何人可登
        if (vehicleHasPassengers) {
            return;
        }

        // 2) 载具无人 → 按归属规则判断
        if (!vehicleOwned) {
            // 无归属：只有小队长可登（自动归属），小队员触发申请
            if (isLeader) {
                return; // 允许交互，挂载时自动归属
            }
            if (playerSquad != SquadManager.NO_SQUAD) {
                submitClaim(player, target, playerSquad, team, sm);
            }
            event.setCanceled(true);
            return;
        }

        // 已归属且无人
        if (playerSquad == vehicleSquad) {
            // 本队队员可直接登（包含队长）
            return;
        }

        if (isLeader) {
            // 别队队长登空载具 → 允许交互，挂载时重新归属
            return;
        }

        if (playerSquad != SquadManager.NO_SQUAD) {
            // 别队小队员 → 触发申请
            submitClaim(player, target, playerSquad, team, sm);
        }
        event.setCanceled(true);
    }

    private static void submitClaim(ServerPlayer member, Entity vehicle, int squadId,
                                      String team, SquadManager sm) {
        // 新申请作废旧申请
        PENDING_CLAIMS.remove(squadId);
        PENDING_CLAIMS.put(squadId, new PendingClaim(
            member.getUUID(), vehicle.getUUID(),
            System.currentTimeMillis() + CLAIM_TIMEOUT_MS));

        String vehicleName = getVehicleDisplayName(vehicle);
        member.sendSystemMessage(Component.literal("§a已向队长申请认领该载具"));

        UUID leaderUuid = sm.getSquadLeaderUuid(team, squadId);
        if (leaderUuid != null) {
            ServerPlayer leader = member.serverLevel().getServer().getPlayerList()
                .getPlayer(leaderUuid);
            if (leader != null) {
                leader.sendSystemMessage(Component.literal(
                    "§a队员申请使用" + vehicleName + "，输入/veh pass以通过，输入/veh passno以否决"));
            }
        }
    }

    /**
     * 小队长登上载具时自动归属 / 重新归属。
     */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onVehicleMount(EntityMountEvent event) {
        if (event.getLevel().isClientSide()) return;
        if (!event.isMounting()) return;
        if (!(event.getEntity() instanceof ServerPlayer player)) return;

        Entity vehicle = event.getEntityBeingMounted();
        if (!isSbwVehicle(vehicle)) return;

        SquadManager sm = SquadManager.getInstance();
        if (!sm.isSquadLeader(player.getUUID())) return;

        int playerSquad = sm.getPlayerSquadId(player.getUUID());
        if (playerSquad == SquadManager.NO_SQUAD) return;

        String team = Espetro.getPlayerTeam(player);
        if (team == null) return;
        team = team.toUpperCase();

        int vehicleSquad = VehicleSquadOwnership.getSquadId(vehicle);
        String vehicleSquadTeam = VehicleSquadOwnership.getSquadTeam(vehicle);
        if (vehicleSquad == -1) {
            // 无归属 → 自动归属
            VehicleSquadOwnership.setOwner(vehicle, playerSquad, team);
            return;
        }

        // 已归属但属于其他小队 → 重新归属（仅当载具无人时才会走到这里，
        // 因为有人时的交互已在 onVehicleEntityInteract 中放行且不触发重归属）
        if (vehicleSquad != playerSquad) {
            // 通知原队长
            if (vehicleSquadTeam != null) {
                UUID oldLeaderUuid = sm.getSquadLeaderUuid(vehicleSquadTeam, vehicleSquad);
                if (oldLeaderUuid != null) {
                    ServerPlayer oldLeader = player.serverLevel().getServer().getPlayerList()
                        .getPlayer(oldLeaderUuid);
                    if (oldLeader != null) {
                        String vehicleName = getVehicleDisplayName(vehicle);
                        String newSquadName = sm.getSquadName(team, playerSquad);
                        if (newSquadName == null) newSquadName = "未知小队";
                        oldLeader.sendSystemMessage(Component.literal(
                            "§c您空闲的载具" + vehicleName + "已被" + newSquadName + "认领"));
                    }
                }
            }
            VehicleSquadOwnership.setOwner(vehicle, playerSquad, team);
        }
    }
}
