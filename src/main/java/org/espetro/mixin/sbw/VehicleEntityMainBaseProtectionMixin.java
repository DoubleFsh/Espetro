package org.espetro.mixin.sbw;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.minecraft.world.damagesource.DamageSource;
import org.espetro.protection.MainBaseProtection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Superb Warfare 载具伤害入口兜底。
 *
 * <p>DragonRise 与 FCP 载具均继承 {@link VehicleEntity}；Forge Living 伤害
 * 事件是主路径，本注入覆盖载具模组直接调用/覆写 hurt 管线的情况。</p>
 */
@Mixin(VehicleEntity.class)
public abstract class VehicleEntityMainBaseProtectionMixin {

    @Inject(method = "hurt", at = @At("HEAD"), cancellable = true, require = 0)
    private void espetro$protectVehicleInMainBase(DamageSource source, float amount,
                                                   CallbackInfoReturnable<Boolean> cir) {
        VehicleEntity vehicle = (VehicleEntity) (Object) this;
        if (MainBaseProtection.isProtected(vehicle)) {
            cir.setReturnValue(false);
        }
    }
}
