package org.espetro.vehicle;

import com.atsuishio.superbwarfare.entity.vehicle.TurretWreckEntity;
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;

/**
 * 加速 SBW（及继承其 {@link VehicleEntity} 的 DragonRise / FCP）载具残骸消失。
 * <p>
 * 参考 EsVehHP：通过每 tick 扣除生命值，触发 SBW 原版阈值：
 * <ul>
 *   <li>车体残骸：{@code health <= -maxHealth} 时 discard</li>
 *   <li>炮塔残骸：{@code health <= 0} 时消失</li>
 * </ul>
 * 默认寿命 5 秒（100 tick）。
 */
public final class WreckDecayService {

    /** 残骸从生成到消失的目标时长（秒）。 */
    public static final double LIFETIME_SECONDS = 5.0;

    /** SBW {@link TurretWreckEntity} 生成时默认生命。 */
    public static final float TURRET_WRECK_START_HP = 100.0f;

    private WreckDecayService() {
    }

    public static double drainFractionPerTick() {
        double ticks = LIFETIME_SECONDS * 20.0;
        if (!(ticks > 0)) {
            return 0;
        }
        return 1.0 / ticks;
    }

    public static double drainPerTick(double healthSpan) {
        if (!(healthSpan > 0)) {
            return 0;
        }
        return healthSpan * drainFractionPerTick();
    }

    public static void tickVehicleWreck(VehicleEntity vehicle) {
        if (vehicle == null || vehicle.level().isClientSide || vehicle.isRemoved()) {
            return;
        }
        if (!vehicle.isWreck()) {
            return;
        }
        float max = vehicle.getMaxHealth();
        if (!(max > 0)) {
            return;
        }
        float drain = (float) drainPerTick(max);
        if (!(drain > 0)) {
            return;
        }
        // 从 ~0 降到 -maxHealth，约 LIFETIME_SECONDS 秒后被 SBW 清除。
        vehicle.setHealth(vehicle.getHealth() - drain);
    }

    public static void tickTurretWreck(TurretWreckEntity wreck) {
        if (wreck == null || wreck.level().isClientSide || wreck.isRemoved()) {
            return;
        }
        float drain = (float) drainPerTick(TURRET_WRECK_START_HP);
        if (!(drain > 0)) {
            return;
        }
        wreck.setHealth(wreck.getHealth() - drain);
    }
}
