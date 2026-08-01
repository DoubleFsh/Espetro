package org.espetro.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import se.mickelus.mutil.gui.GuiElement;

/**
 * Persistent MUtil root for Espetro HUD elements.
 *
 * <p>The tree is rebuilt only when GUI scale changes. Packet updates mutate the
 * backing HUD state, so ordinary render ticks never replace the element tree.</p>
 */
public final class MutilHudOverlay {

    private static GuiElement root;
    private static int width = -1;
    private static int height = -1;
    private static int tickCounter;
    /** 每 N 帧渲染一次；跳过中间帧，降低 HUD 渲染开销。设为 1 即每帧渲染。 */
    private static final int HUD_RENDER_INTERVAL = 10;

    private MutilHudOverlay() {
    }

    public static void render(GuiGraphics graphics, Minecraft minecraft, float partialTick) {
        if (minecraft == null || minecraft.level == null || minecraft.options.hideGui) {
            return;
        }
        tickCounter++;
        if (tickCounter % HUD_RENDER_INTERVAL != 0) {
            return;
        }
        int nextWidth = graphics.guiWidth();
        int nextHeight = graphics.guiHeight();
        if (root == null || width != nextWidth || height != nextHeight) {
            width = nextWidth;
            height = nextHeight;
            root = new GuiElement(0, 0, width, height);
            root.addChild(new TroopElement());
            root.addChild(new StaminaElement());
        }
        root.updateAnimations();
        root.draw(graphics, 0, 0, width, height, -1, -1, partialTick);
    }

    private static final class TroopElement extends GuiElement {
        private TroopElement() {
            super(0, 0, 230, 24);
        }

        @Override
        public void draw(GuiGraphics graphics, int x, int y, int drawWidth, int drawHeight,
                         int mouseX, int mouseY, float partialTick) {
            TroopCountOverlay.drawElement(graphics, Minecraft.getInstance());
            super.draw(graphics, x, y, drawWidth, drawHeight, mouseX, mouseY, partialTick);
        }
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
