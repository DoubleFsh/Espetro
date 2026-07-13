package org.espetro.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;

/**
 * 客户端体力状态与 HUD。
 */
public final class StaminaOverlay {

    private static final int BAR_WIDTH = 40;
    private static final int BAR_HEIGHT = 2;

    private static boolean enabled;
    private static int stamina;
    private static int maxStamina;

    private StaminaOverlay() {
    }

    public static void update(boolean newEnabled, int newStamina, int newMaxStamina, int newJumpStaminaCost) {
        enabled = newEnabled;
        maxStamina = Math.max(0, newMaxStamina);
        stamina = Math.max(0, Math.min(newStamina, maxStamina));
    }

    public static boolean isExhausted() {
        return enabled && stamina <= 0;
    }

    public static boolean isEnabled() {
        return enabled;
    }

    public static void render(GuiGraphics graphics, Minecraft mc) {
        if (!enabled || maxStamina <= 0 || stamina >= maxStamina
                || mc.options.hideGui || mc.player == null) {
            return;
        }

        int filledWidth = Math.round(BAR_WIDTH * (stamina / (float) maxStamina));
        if (stamina > 0) {
            filledWidth = Math.max(1, filledWidth);
        }

        int x = (graphics.guiWidth() - BAR_WIDTH) / 2;
        int y = graphics.guiHeight() / 2 + 12;
        graphics.fill(x, y, x + filledWidth, y + BAR_HEIGHT, 0xE6FFFFFF);
    }
}
