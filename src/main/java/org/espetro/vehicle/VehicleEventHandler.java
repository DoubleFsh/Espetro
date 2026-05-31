package org.espetro.vehicle;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
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
