package org.espetro.vehicle;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.event.entity.EntityLeaveLevelEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.espetro.Espetro;
import org.espetro.team.ClassCountManager;

/**
 * 载具事件处理器
 */
@Mod.EventBusSubscriber(modid = Espetro.MOD_ID)
public class VehicleEventHandler {

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

    // 载具部署木棍已移除；载具部署入口在 Alt 轮盘（DEPLOY_VEHICLE）。
}
