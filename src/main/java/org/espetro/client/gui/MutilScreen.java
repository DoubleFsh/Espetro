package org.espetro.client.gui;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import se.mickelus.mutil.gui.GuiElement;

abstract class MutilScreen extends Screen {

    protected GuiElement root;

    protected MutilScreen(Component title) {
        super(title);
    }

    @Override
    protected void init() {
        super.init();
        rebuildMutilRoot();
    }

    protected final void rebuildMutilRoot() {
        GuiElement newRoot = new GuiElement(0, 0, this.width, this.height);
        buildMutilRoot(newRoot);
        this.root = newRoot;
    }

    protected abstract void buildMutilRoot(GuiElement root);

    protected void renderBeforeMutil(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        EspetroMutilWidgets.drawScreenShade(graphics, this.width, this.height);
    }

    protected void renderAfterMutil(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBeforeMutil(graphics, mouseX, mouseY, partialTick);
        if (root != null) {
            root.updateFocusState(0, 0, mouseX, mouseY);
            root.draw(graphics, 0, 0, this.width, this.height, mouseX, mouseY, partialTick);

            var tooltip = root.getTooltipLines();
            if (tooltip != null && !tooltip.isEmpty()) {
                graphics.renderComponentTooltip(this.font, tooltip, mouseX, mouseY);
            }
        }
        renderAfterMutil(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (root != null) {
            root.updateFocusState(0, 0, (int) mouseX, (int) mouseY);
            if (root.onMouseClick((int) mouseX, (int) mouseY, button)) {
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (root != null) {
            root.onMouseRelease((int) mouseX, (int) mouseY, button);
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (root != null && root.onMouseScroll(mouseX, mouseY, delta)) {
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (root != null && root.onKeyPress(keyCode, scanCode, modifiers)) {
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean keyReleased(int keyCode, int scanCode, int modifiers) {
        if (root != null && root.onKeyRelease(keyCode, scanCode, modifiers)) {
            return true;
        }
        return super.keyReleased(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        if (root != null && root.onCharType(codePoint, modifiers)) {
            return true;
        }
        return super.charTyped(codePoint, modifiers);
    }
}
