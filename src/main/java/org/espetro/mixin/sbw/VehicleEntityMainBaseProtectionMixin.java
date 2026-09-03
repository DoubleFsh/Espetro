package org.espetro.mixin.sbw;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.minecraft.world.damagesource.DamageSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Superb Warfare 载具伤害入口兜底。
 *
 * <p>DragonRise 与 FCP 载具均继承 {@link VehicleEntity}；Forge Living 伤害
 * 事件是主路径，本注入覆盖载具模组直接调用/覆写 hurt 管线的情况。</p>
 *
 * <p>注意：本 mixin 位于通用列表（客户端同样注入）。客户端没有基地保护逻辑，
 * 且 {@code MainBaseProtection} 引用服务端专用类（{@code ServerLevel} /
 * {@code ServerPlayer}），在客户端加载会抛 {@link NoClassDefFoundError}，
 * 因此客户端侧必须短路返回，服务端调用也做 LinkageError 防御。</p>
 */
@Mixin(VehicleEntity.class)
public abstract class VehicleEntityMainBaseProtectionMixin {

    @Inject(method = "hurt", at = @At("HEAD"), cancellable = true, require = 0)
    private void espetro$protectVehicleInMainBase(DamageSource source, float amount,
                                                   CallbackInfoReturnable<Boolean> cir) {
        VehicleEntity vehicle = (VehicleEntity) (Object) this;
        // 客户端无基地保护逻辑；同时避免加载引用服务端专用类的 MainBaseProtection
        if (vehicle.level() == null || vehicle.level().isClientSide) {
            return;
        }
        try {
            if (org.espetro.protection.MainBaseProtection.isProtected(vehicle)) {
                cir.setReturnValue(false);
            }
        } catch (LinkageError ignored) {
            // 服务端专用类在异常环境不可加载时静默跳过保护判定
        }
    }
}
