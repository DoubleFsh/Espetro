package org.espetro.client.vehicle;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.LogicalSide;
import org.espetro.client.gui.ClientGameState;
import org.espetro.client.gui.VehicleWheelController;
import org.espetro.network.MountRequestPacket;
import org.espetro.network.NetworkManager;
import org.espetro.vehicle.SbwVehicleSeatResolver;
import org.espetro.vehicle.VehicleInteractionConfig;

import java.util.UUID;

/**
 * Mount via vehicle-wheel center hover + SBW INTERACT.
 * Instant entity-interact mounts are cancelled when delay &gt; 0.
 */
public final class VehicleMountClientGate {

    private static boolean registered;
    private static UUID mountingVehicleId;
    private static int mountTicks;

    private VehicleMountClientGate() {
    }

    public static void register() {
        if (registered) {
            return;
        }
        registered = true;
        MinecraftForge.EVENT_BUS.register(VehicleMountClientGate.class);
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onInteract(PlayerInteractEvent.EntityInteract event) {
        if (event.getSide() != LogicalSide.CLIENT && event.getSide() != LogicalSide.SERVER) {
            return;
        }
        if (!SbwVehicleSeatResolver.isSupportedVehicle(event.getTarget())) {
            return;
        }
        // 主城阶段：跳过读条，放行原版 SBW 交互上车（不使用本 mod 的上车读条/拦截）。
        if (ClientGameState.getCurrentPhase().isLobbyLike()) {
            return;
        }
        if (VehicleInteractionConfig.mountDelayTicks() <= 0) {
            return;
        }
        event.setCanceled(true);
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.screen != null) {
            resetMount(true);
            return;
        }

        if (mc.player.getVehicle() != null) {
            resetMount(false);
            return;
        }

        // 主城阶段：跳过上车读条，完全走原版 SBW 交互上车。
        if (ClientGameState.getCurrentPhase().isLobbyLike()) {
            resetMount(true);
            return;
        }

        int delay = VehicleInteractionConfig.mountDelayTicks();
        if (delay <= 0) {
            resetMount(false);
            return;
        }

        boolean interactDown = VehicleWheelController.isInteractHeld();
        boolean wheelActive = VehicleWheelController.isWheelActive();
        boolean centerHovered = VehicleWheelController.isCenterHovered();

        if (!interactDown || !wheelActive) {
            resetMount(true);
            return;
        }

        Entity target = lookVehicle(mc);
        if (target == null) {
            if (mountingVehicleId == null) {
                resetMount(false);
                return;
            }
        } else if (mountingVehicleId == null
            || !mountingVehicleId.equals(target.getUUID())) {
            if (!centerHovered) {
                resetMount(false);
                return;
            }
            begin(target.getUUID());
        }

        if (!centerHovered) {
            if (mountTicks > 0 || VehicleInteractionState.kind() == VehicleInteractionKind.MOUNT) {
                mountTicks = 0;
                VehicleInteractionState.clear();
                NetworkManager.NET.sendToServer(new MountRequestPacket(
                    MountRequestPacket.Action.CANCEL, mountingVehicleId));
            }
            return;
        }

        if (mountingVehicleId == null) {
            return;
        }

        if (mountTicks == 0) {
            NetworkManager.NET.sendToServer(new MountRequestPacket(
                MountRequestPacket.Action.BEGIN, mountingVehicleId));
        }
        mountTicks++;
        float progress = Math.min(1f, mountTicks / (float) delay);
        VehicleInteractionState.setMount(progress);
        if (mountTicks >= delay) {
            NetworkManager.NET.sendToServer(new MountRequestPacket(
                MountRequestPacket.Action.COMPLETE, mountingVehicleId));
            VehicleInteractionState.setMount(1f);
        }
    }

    private static void begin(UUID vehicleId) {
        mountingVehicleId = vehicleId;
        mountTicks = 0;
        VehicleInteractionState.setMount(0f);
        NetworkManager.NET.sendToServer(new MountRequestPacket(
            MountRequestPacket.Action.BEGIN, vehicleId));
    }

    private static void resetMount(boolean notifyServer) {
        if (mountingVehicleId == null && mountTicks == 0) {
            if (VehicleInteractionState.kind() == VehicleInteractionKind.MOUNT) {
                VehicleInteractionState.clear();
            }
            return;
        }
        UUID id = mountingVehicleId;
        mountingVehicleId = null;
        mountTicks = 0;
        if (VehicleInteractionState.kind() == VehicleInteractionKind.MOUNT) {
            VehicleInteractionState.clear();
        }
        if (notifyServer && id != null) {
            NetworkManager.NET.sendToServer(new MountRequestPacket(
                MountRequestPacket.Action.CANCEL, id));
        }
    }

    private static Entity lookVehicle(Minecraft mc) {
        if (mc.hitResult instanceof EntityHitResult hit
            && SbwVehicleSeatResolver.isSupportedVehicle(hit.getEntity().getRootVehicle())) {
            return hit.getEntity().getRootVehicle();
        }
        return null;
    }
}
