package org.espetro.mixin.sbw;

import com.atsuishio.superbwarfare.entity.vehicle.TurretWreckEntity;
import org.espetro.Espetro;
import org.espetro.vehicle.WreckDecayService;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 加速 SBW 炮塔残骸消失（约 5 秒）。
 * {@code baseTick} 在生产环境重映射为 {@code m_6075_}。
 */
@Mixin(TurretWreckEntity.class)
public abstract class TurretWreckEntityMixin {

    @Inject(method = "baseTick", at = @At("TAIL"), require = 0)
    private void espetro$decayTurretWreck(CallbackInfo ci) {
        try {
            WreckDecayService.tickTurretWreck((TurretWreckEntity) (Object) this);
        } catch (Throwable t) {
            Espetro.LOGGER.error("Espetro 炮塔残骸加速消失失败", t);
        }
    }
}
