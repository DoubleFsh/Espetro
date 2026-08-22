package org.espetro.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import org.espetro.client.aui.GuiElement;

/**
 * Persistent MUtil root for Espetro HUD elements.
 *
 * <p>兵力已迁至 {@link UnifiedDeployScreen} 右上角时间下方；本层保留体力条与
 * Radio 范围补给状态。</p>
 */
public final class MutilHudOverlay {

    private static GuiElement root;
    private static int width = -1;
    private static int height = -1;

    private MutilHudOverlay() {
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
            root.addChild(new StaminaElement());
            root.addChild(FobSupplyHud.createElement());
            root.addChild(OutpostSupplyHud.createElement());
            root.addChild(FortificationProgressHud.createElement());
        }
        // HUD widgets have no time-based MUtil animations. State changes are pushed by
        // their sync events, so the render path must not poll/update the tree per frame.
        root.draw(graphics, 0, 0, width, height, -1, -1, partialTick);
    }

    private static final class StaminaElement extends GuiElement {
        private StaminaElement() {
            super(0, 0, 1, 1);
        }

        @Override
        public void draw(GuiGraphics graphics, int x, int y, int drawWidth, int drawHeight,
                         int mouseX, int mouseY, float partialTick) {
            StaminaOverlay.drawElement(graphics, Minecraft.getInstance());
            super.draw(graphics, x, y, drawWidth, drawHeight, mouseX, mouseY, partialTick);
        }
    }
}
