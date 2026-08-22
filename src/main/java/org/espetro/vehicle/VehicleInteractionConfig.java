package org.espetro.vehicle;

import net.minecraftforge.common.ForgeConfigSpec;

/** Hold-to-mount / dismount / seat-switch timings (common config). */
public final class VehicleInteractionConfig {

    public static final ForgeConfigSpec SPEC;

    public static final ForgeConfigSpec.IntValue MOUNT_DELAY_TICKS;
    public static final ForgeConfigSpec.IntValue DISMOUNT_DELAY_TICKS;
    public static final ForgeConfigSpec.IntValue SEAT_SWITCH_DELAY_TICKS;
    public static final ForgeConfigSpec.DoubleValue MOUNT_MAX_DISTANCE;

    static {
        ForgeConfigSpec.Builder b = new ForgeConfigSpec.Builder();
        b.push("vehicle");
        MOUNT_DELAY_TICKS = b.comment("Ticks holding INTERACT on wheel center to mount (0 = instant)")
            .defineInRange("mountDelayTicks", 60, 0, 20 * 60);
        DISMOUNT_DELAY_TICKS = b.comment("Ticks holding INTERACT to dismount (0 = native SBW)")
            .defineInRange("dismountDelayTicks", 60, 0, 20 * 60);
        SEAT_SWITCH_DELAY_TICKS = b.comment("Ticks holding Shift before a seat change is allowed")
            .defineInRange("seatSwitchDelayTicks", 100, 0, 20 * 60);
        MOUNT_MAX_DISTANCE = b.comment("Max distance from vehicle while mounting")
            .defineInRange("mountMaxDistance", 5.0, 1.0, 16.0);
        b.pop();
        SPEC = b.build();
    }

    private VehicleInteractionConfig() {
    }

    public static int mountDelayTicks() {
        return MOUNT_DELAY_TICKS.get();
    }

    public static int dismountDelayTicks() {
        return DISMOUNT_DELAY_TICKS.get();
    }

    public static int seatSwitchDelayTicks() {
        return SEAT_SWITCH_DELAY_TICKS.get();
    }

    public static double mountMaxDistance() {
        return MOUNT_MAX_DISTANCE.get();
    }
}
