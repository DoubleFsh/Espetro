package org.espetro.bastion;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Items;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.entity.player.AttackEntityEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.event.level.ExplosionEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.fml.common.Mod;
import org.espetro.Espetro;
import org.espetro.api.event.BastionLifecycleEvent;

/** Keeps the fortification indexes in sync with block destruction. */
@Mod.EventBusSubscriber(modid = Espetro.MOD_ID)
public final class FortificationEventHandler {

    private FortificationEventHandler() {
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onBlockBroken(BlockEvent.BreakEvent event) {
        if (event.isCanceled() || !(event.getLevel() instanceof ServerLevel level)) return;
        FortificationManager manager = FortificationManager.getInstance();
        if (!manager.contains(level, event.getPos())) return;
        if (event.getPlayer().getMainHandItem().getItem() == Items.IRON_SHOVEL) {
            event.setCanceled(true);
            return;
        }
        manager.damageAt(level, event.getPos(), event.getPlayer());
    }

    @SubscribeEvent
    public static void onExplosion(ExplosionEvent.Detonate event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        for (var pos : java.util.List.copyOf(event.getAffectedBlocks())) {
            FortificationManager.getInstance().damageAt(level, pos, event.getExplosion().getIndirectSourceEntity());
        }
    }

    @SubscribeEvent
    public static void onLeftClick(PlayerInteractEvent.LeftClickBlock event) {
        guardShovelWork(event, true);
    }

    @SubscribeEvent
    public static void onRightClick(PlayerInteractEvent.RightClickBlock event) {
        guardShovelWork(event, false);
    }

    private static void guardShovelWork(PlayerInteractEvent event, boolean build) {
        if (event.getLevel().isClientSide() || !(event.getLevel() instanceof ServerLevel level)
            || event.getEntity().getMainHandItem().getItem() != Items.IRON_SHOVEL
            || !FortificationManager.getInstance().contains(level, event.getPos())) return;
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.SUCCESS);
    }

    /** Reserved construction cells cannot be overwritten by ordinary block placement. */
    @SubscribeEvent
    public static void onBlockPlaced(BlockEvent.EntityPlaceEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level)
            || !FortificationManager.getInstance().contains(level, event.getPos())) return;
        event.setCanceled(true);
        if (event.getEntity() instanceof net.minecraft.server.level.ServerPlayer player) {
            player.displayClientMessage(net.minecraft.network.chat.Component.literal(
                "§c该位置已被工事施工范围占用。"), true);
        }
    }

    @SubscribeEvent
    public static void onAttackEntity(AttackEntityEvent event) {
        if (event.getEntity().getMainHandItem().getItem() == Items.IRON_SHOVEL
            && FortificationManager.getInstance().containsEntity(event.getTarget().getUUID())) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onUseEntity(PlayerInteractEvent.EntityInteract event) {
        if (event.getEntity().getMainHandItem().getItem() == Items.IRON_SHOVEL
            && FortificationManager.getInstance().containsEntity(event.getTarget().getUUID())) {
            event.setCanceled(true);
            event.setCancellationResult(InteractionResult.SUCCESS);
        }
    }

    @SubscribeEvent
    public static void onBastionDestroyed(BastionLifecycleEvent.Destroyed event) {
        FortificationManager.getInstance().onBastionDestroyed(
            event.bastionId(), event.level(), event.attacker());
    }
}
