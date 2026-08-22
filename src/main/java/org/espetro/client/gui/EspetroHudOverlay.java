package org.espetro.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import org.espetro.client.aui.GuiElement;

/**
 * Persistent HUD root for Espetro HUD elements.
 *
 * <p>体力条已迁至 EsICO。本层保留 Radio 范围补给等状态。</p>
 */
public final class EspetroHudOverlay {

    private static GuiElement root;
    private static int width = -1;
    private static int height = -1;

    private EspetroHudOverlay() {
    }

    public static void render(GuiGraphics graphics, Minecraft minecraft, float partialTick) {
        if (minecraft == null || minecraft.level == null || minecraft.options.hideGui) {
            return;
        }
        int nextWidth = graphics.guiWidth();
        int nextHeight = graphics.guiHeight();
        if (root == null || width != nextWidth || height != nextHeight) {
            width = nextWidth;
            height = nextHeight;
            root = new GuiElement(0, 0, width, height);
            root.addChild(FobSupplyHud.createElement());
            root.addChild(OutpostSupplyHud.createElement());
            root.addChild(FortificationProgressHud.createElement());
        }
        root.draw(graphics, 0, 0, width, height, -1, -1, partialTick);
    }
}
