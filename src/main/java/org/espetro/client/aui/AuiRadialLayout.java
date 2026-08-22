package org.espetro.client.aui;

/** Pure polar hit-test for the AUI tactical radial. No Minecraft types. */
public final class AuiRadialLayout {
    public static final double INNER_RADIUS = 44.0D;
    public static final double OUTER_RADIUS = 96.0D;

    private AuiRadialLayout() {
    }

    /**
     * Slot index under the pointer, or {@code -1} if the pointer is in the
     * hole or outside the ring. Slots start at the top and go clockwise.
     */
    public static int hitIndex(double mouseX, double mouseY,
                               double centerX, double centerY, int slotCount) {
        if (slotCount <= 0) {
            return -1;
        }
        double dx = mouseX - centerX;
        double dy = mouseY - centerY;
        double distance = Math.hypot(dx, dy);
        if (distance < INNER_RADIUS || distance > OUTER_RADIUS + 12.0D) {
            return -1;
        }
        double angle = Math.atan2(dx, -dy);
        if (angle < 0.0D) {
            angle += Math.PI * 2.0D;
        }
        double step = (Math.PI * 2.0D) / slotCount;
        int index = (int) Math.floor((angle + step / 2.0D) / step);
        return Math.floorMod(index, slotCount);
    }

    public static double slotX(double centerX, int index, int slotCount) {
        return centerX + Math.sin(slotAngle(index, slotCount)) * slotRadius();
    }

    public static double slotY(double centerY, int index, int slotCount) {
        return centerY - Math.cos(slotAngle(index, slotCount)) * slotRadius();
    }

    public static double slotRadius() {
        return (INNER_RADIUS + OUTER_RADIUS) * 0.5D;
    }

    public static double slotAngle(int index, int slotCount) {
        if (slotCount <= 0) {
            return 0.0D;
        }
        return index * (Math.PI * 2.0D) / slotCount;
    }
}
