package org.espetro.vehicle;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.event.entity.EntityLeaveLevelEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.espetro.Espetro;
import org.espetro.team.ClassCountManager;
import org.espetro.team.CommanderSkillManager;

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

        if (CommanderSkillManager.isVehicleSupplyStationEntity(entity)) {
            CommanderSkillManager.getInstance().onVehicleSupplyStationDestroyed(entity);
            Espetro.LOGGER.debug("载具补给站实体 {} 已死亡，清理补给站", entity.getUUID());
        }
    }

    @SubscribeEvent
    public static void onVehicleLeaveLevel(EntityLeaveLevelEvent event) {
        if (event.getLevel().isClientSide()) return;

        Entity entity = event.getEntity();
        if (entity.getTags().contains("espetro_vehicle")) {
            if (entity.getRemovalReason() == Entity.RemovalReason.KILLED) {
                VehicleManager.getInstance().onVehicleDeath(entity.getUUID());
                Espetro.LOGGER.debug("载具 {} 已被杀毁，移除追踪并处理兵力扣除", entity.getUUID());
            } else {
                VehicleManager.getInstance().onVehicleRemoved(entity.getUUID());
                Espetro.LOGGER.debug("载具 {} 已离开世界，移除追踪", entity.getUUID());
            }
        }

        if (CommanderSkillManager.isVehicleSupplyStationEntity(entity)
            && entity.getRemovalReason() == Entity.RemovalReason.KILLED) {
            CommanderSkillManager.getInstance().onVehicleSupplyStationDestroyed(entity);
            Espetro.LOGGER.debug("载具补给站实体 {} 已被杀毁，清理补给站", entity.getUUID());
        }
    }

    /**
     * 指挥官右键载具部署木棍时发送部署面板消息
     */
    @SubscribeEvent
    public static void onRightClickDeployStick(PlayerInteractEvent.RightClickItem event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (event.getItemStack().getItem() != VehicleItems.VEHICLE_DEPLOY_STICK) return;

        event.setCanceled(true);

        String factionId = ClassCountManager.getInstance().getPlayerFaction(player.getUUID());
        if (factionId == null) {
            player.sendSystemMessage(net.minecraft.network.chat.Component.literal("§c你还没有选择编制！"));
            return;
        }

        org.espetro.network.NetworkManager.sendVehicleDeployScreen(player, factionId);
    }
}
