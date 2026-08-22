package org.espetro.client.aui;

import net.minecraft.client.gui.GuiGraphics;

/** Solid rectangle painted in the Java pass. */
public class GuiRect extends GuiElement {
    private int color;

    public GuiRect(int x, int y, int width, int height, int color) {
        super(x, y, width, height);
        this.color = color;
    }

    public void setColor(int color) {
        this.color = color;
    }

    @Override
    public void draw(GuiGraphics graphics, int x, int y, int width, int height,
                     int mouseX, int mouseY, float partialTick) {
        if (!isVisible()) {
            return;
        }
        if ((color & 0xFF000000) != 0) {
            int left = x + getX();
            int top = y + getY();
            graphics.fill(left, top, left + getWidth(), top + getHeight(), color);
        }
        super.draw(graphics, x, y, width, height, mouseX, mouseY, partialTick);
    }
}
