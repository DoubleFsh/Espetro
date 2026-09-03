package org.espetro.vehicle;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.LogicalSide;
import net.minecraftforge.fml.common.Mod;
import org.espetro.Espetro;
import org.espetro.team.GameStateManager;

@Mod.EventBusSubscriber(modid = Espetro.MOD_ID)
public final class VehicleMountEvents {

    private VehicleMountEvents() {
    }

    /**
     * Block instant SBW/vanilla mounts. Actual mount starts only after the
     * client completes the SBW INTERACT hold channel.
     */
    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
        if (event.getSide() != LogicalSide.SERVER) {
            return;
        }
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        Entity target = event.getTarget();
        if (!SbwVehicleSeatResolver.isSupportedVehicle(target)) {
            return;
        }
        if (player.getVehicle() != null) {
            return;
        }
        // 主城阶段：跳过读条，放行原版 SBW 交互上车。
        if (GameStateManager.getInstance().getCurrentPhase().isLobbyLike()) {
            return;
        }
        if (VehicleInteractionConfig.mountDelayTicks() <= 0) {
            return;
        }
        event.setCanceled(true);
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.side != LogicalSide.SERVER || event.phase != TickEvent.Phase.END) {
            return;
        }
        var server = net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            return;
        }
        VehicleMountServer.tickAll(server.getPlayerList().getPlayers());
    }

    @SubscribeEvent
    public static void onStop(ServerStoppingEvent event) {
        VehicleMountServer.clear();
        SeatSwitchServer.clearAll();
        DismountServer.clearAll();
    }
}
