package org.espetro.mixin.sbw;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import org.espetro.Espetro;
import org.espetro.vehicle.WreckDecayService;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 注入 SBW {@link VehicleEntity#baseTick}：残骸态加速掉血，约 5 秒消失。
 * <p>
 * DragonRise / FCP 载具均继承本类，因此一并生效。
 * {@code baseTick} 在生产环境重映射为 {@code m_6075_}，须默认 remap=true。
 */
@Mixin(VehicleEntity.class)
public abstract class VehicleEntityWreckMixin {

    @Inject(method = "baseTick", at = @At("TAIL"), require = 0)
    private void espetro$decayVehicleWreck(CallbackInfo ci) {
        try {
            VehicleEntity vehicle = (VehicleEntity) (Object) this;
            if (vehicle.isRemoved()) {
                return;
            }
            if (vehicle.isWreck()) {
                WreckDecayService.tickVehicleWreck(vehicle);
            }
        } catch (Throwable t) {
            Espetro.LOGGER.error("Espetro 载具残骸加速消失失败", t);
        }
    }
}
