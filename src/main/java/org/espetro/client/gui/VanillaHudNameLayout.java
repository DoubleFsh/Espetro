package org.espetro.client.gui;

/** Screen-space placement for the selected hotbar item name. No Minecraft types. */
final class VanillaHudNameLayout {
    private VanillaHudNameLayout() {
    }

    static int nameX(int slotX, int textWidth) {
        return slotX - 6 - textWidth;
    }

    static int nameY(int slotY) {
        return slotY + 7;
    }

    static int nameFade(int timer, float hotbarAlpha) {
        int fade = (int) (timer * 256.0F / 10.0F);
        if (fade > 255) {
            fade = 255;
        }
        if (fade <= 0) {
            return 0;
        }
        fade = (int) (fade * hotbarAlpha);
        return Math.max(0, fade);
    }

    static int nameBackgroundColor(int fade) {
        return ((int) (192.0F * fade / 255.0F)) << 24;
    }
}
