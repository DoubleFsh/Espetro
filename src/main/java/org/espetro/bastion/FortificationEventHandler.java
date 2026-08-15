package org.espetro.bastion;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraftforge.event.entity.ProjectileImpactEvent;
import net.minecraftforge.event.entity.EntityLeaveLevelEvent;
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
        var explosion = event.getExplosion();
        var affected = java.util.List.copyOf(event.getAffectedBlocks());
        double radius = 6.0D;
        if (!affected.isEmpty()) {
            radius = 0.0D;
            for (var pos : affected) {
                radius = Math.max(radius,
                    Math.sqrt(pos.distToCenterSqr(
                        explosion.getPosition().x, explosion.getPosition().y,
                        explosion.getPosition().z)));
            }
            radius += 1.5D;
        }
        FortificationManager.getInstance().damageExplosion(level,
            explosion.getPosition(), (float) radius, affected,
            explosion.getIndirectSourceEntity());
    }

    /** 炮弹/导弹等投射物直接命中工事方块或工事实体时扣除完整度。 */
    @SubscribeEvent
    public static void onProjectileImpact(ProjectileImpactEvent event) {
        if (event.getEntity().level().isClientSide()
            || !(event.getEntity().level() instanceof ServerLevel level)) return;
        // 只处理 superb_warfare 小口径炮弹及其派生（不含 TaCZ/步枪/机枪弹）
        String projectileClass = event.getEntity().getClass().getName();
        boolean smallCaliberShell =
            "com.atsuishio.superbwarfare.entity.projectile.SmallCannonShellEntity".equals(projectileClass)
                || "com.redabysslucia.dragonrise_reforge.entities.projectile.AAshellEntity".equals(projectileClass);
        if (!smallCaliberShell) return;
        HitResult ray = event.getRayTraceResult();
        net.minecraft.world.entity.Entity projectile = event.getEntity();
        net.minecraft.world.entity.Entity attacker = projectile instanceof net.minecraft.world.entity.projectile.Projectile p
            ? p.getOwner() : null;
        if (ray instanceof BlockHitResult blockHit) {
            FortificationManager.getInstance().damageAt(level, blockHit.getBlockPos(), attacker,
                FortificationManager.DamageKind.PROJECTILE);
        } else if (ray instanceof EntityHitResult entityHit) {
            FortificationManager.getInstance().damageEntity(
                level, entityHit.getEntity().getUUID(), attacker);
        }
    }

    /** Missing/dead virtual entity parts settle once; manager-side removal is idempotent. */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onEntityLeave(EntityLeaveLevelEvent event) {
        if (!event.getLevel().isClientSide()) {
            FortificationManager.getInstance().removeEntity(event.getEntity().getUUID());
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
    public static void onBastionBuilt(BastionLifecycleEvent.Built event) {
        org.espetro.network.NetworkManager.refreshDeployPointsForTeam(event.team());
    }

    @SubscribeEvent
    public static void onBastionDestroyed(BastionLifecycleEvent.Destroyed event) {
        FortificationManager.getInstance().onBastionDestroyed(
            event.bastionId(), event.level(), event.attacker());
        org.espetro.network.NetworkManager.refreshDeployPointsForTeam(event.team());
    }
}
