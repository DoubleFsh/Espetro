package org.espetro.client.vehicle;

import net.minecraft.resources.ResourceLocation;

/** Client-side vehicle interaction progress for the AuraTip wheel center. */
public final class VehicleInteractionState {

    private static final ResourceLocation ICON =
        ResourceLocation.fromNamespaceAndPath("espetro", "textures/gui/vehicle/mount.png");

    private static VehicleInteractionKind kind = VehicleInteractionKind.NONE;
    private static float progress = -1f;

    private VehicleInteractionState() {
    }

    public static void set(VehicleInteractionKind newKind, float p) {
        if (newKind == null || newKind == VehicleInteractionKind.NONE) {
            clear();
            return;
        }
        kind = newKind;
        progress = clamp(p);
    }

    public static void setMount(float p) {
        set(VehicleInteractionKind.MOUNT, p);
    }

    public static void setSeatSwitch(float p) {
        set(VehicleInteractionKind.SEAT_SWITCH, p);
    }

    public static void setDismount(float p) {
        set(VehicleInteractionKind.DISMOUNT, p);
    }

    public static void clear() {
        kind = VehicleInteractionKind.NONE;
        progress = -1f;
    }

    public static VehicleInteractionKind kind() {
        return kind;
    }

    public static float progress() {
        return kind == VehicleInteractionKind.NONE ? -1f : progress;
    }

    public static ResourceLocation icon() {
        return ICON;
    }

    public static String label() {
        return switch (kind) {
            case MOUNT -> "上车";
            case SEAT_SWITCH -> "换座";
            case DISMOUNT -> "下车";
            default -> "";
        };
    }

    public static int color() {
        if (progress < 0f) {
            return 0xFF888888;
        }
        if (progress > 0.75f) {
            return 0xFF44CC44;
        }
        if (progress > 0.5f) {
            return 0xFFCCAA00;
        }
        return 0xFFCC4444;
    }

    private static float clamp(float p) {
        if (p < 0f) {
            return 0f;
        }
        return Math.min(1f, p);
    }
}
