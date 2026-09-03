package org.espetro.protection;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.entity.living.LivingAttackEvent;
import net.minecraftforge.event.entity.living.LivingDamageEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.espetro.Espetro;

/**
 * 在标准 Forge 伤害管线的三个阶段提供安全区保护。
 *
 * <p>绝大多数伤害会在 {@link LivingAttackEvent} 被提前取消；后两层用于兼容
 * 直接进入 hurt/damage 阶段的载具和武器模组。所有判定仅在实际发生伤害时运行。</p>
 *
 * <p>仅服务端注册：{@code MainBaseProtection} 引用服务端专用类，客户端不加载
 * 该处理器，避免左键攻击载具时客户端类加载失败崩溃。</p>
 */
@Mod.EventBusSubscriber(modid = Espetro.MOD_ID, value = Dist.DEDICATED_SERVER)
public final class MainBaseProtectionEventHandler {

    private MainBaseProtectionEventHandler() {
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onLivingAttack(LivingAttackEvent event) {
        if (MainBaseProtection.isProtected(event.getEntity())) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onLivingHurt(LivingHurtEvent event) {
        if (MainBaseProtection.isProtected(event.getEntity())) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onLivingDamage(LivingDamageEvent event) {
        if (MainBaseProtection.isProtected(event.getEntity())) {
            event.setCanceled(true);
        }
    }
}
