package org.espetro.client.aui;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.Objects;

/**
 * Vanilla {@link Screen} host for Espetro / EsPoints menus.
 * Clicks go through {@code mouseClicked} like the old MUtil screens.
 * Does not create an AUI Document — a full-screen intercept overlay
 * would swallow vanilla and in-game clicks.
 */
public abstract class AuiScreen extends Screen {
    public static final String HOST_PATH = "screens/host.html";

    protected GuiElement root;
    private boolean rootRebuildPending;
    private boolean rebuildingRoot;
    private Object structureSignature;
    private long lastThrottleEpochSec = -1;

    protected AuiScreen(Component title) {
        super(title);
    }

    public static void runWithDocument(Object ignored, Runnable action) {
        if (action != null) {
            action.run();
        }
    }

    protected boolean shadeWorld() {
        return true;
    }

    protected final boolean updateStructure(Object newSignature) {
        if (Objects.equals(structureSignature, newSignature)) {
            return false;
        }
        structureSignature = newSignature;
        rebuildMenuRoot();
        return true;
    }

    protected final Object getStructureSignature() {
        return structureSignature;
    }

    protected final boolean onceEverySecond() {
        long epochSec = System.currentTimeMillis() / 1000L;
        if (epochSec == lastThrottleEpochSec) {
            return false;
        }
        lastThrottleEpochSec = epochSec;
        return true;
    }

    protected final void rebuildMenuRoot() {
        rootRebuildPending = true;
    }

    @Override
    protected void init() {
        super.init();
        rebuildMenuRootNow();
    }

    private void rebuildMenuRootNow() {
        if (rebuildingRoot) {
            return;
        }
        rebuildingRoot = true;
        try {
            GuiElement next = new GuiElement(0, 0, this.width, this.height);
            buildMenuRoot(next);
            this.root = next;
        } finally {
            rebuildingRoot = false;
        }
    }

    @Override
    public void tick() {
        super.tick();
        if (rootRebuildPending) {
            rootRebuildPending = false;
            rebuildMenuRootNow();
        }
        if (root != null) {
            root.updateAnimations();
        }
    }

    protected abstract void buildMenuRoot(GuiElement root);

    protected void renderBeforeMenu(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
    }

    protected void renderAfterMenu(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.flush();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        graphics.pose().pushPose();
        try {
            renderBeforeMenu(graphics, mouseX, mouseY, partialTick);
            if (root != null) {
                root.updateFocusState(0, 0, mouseX, mouseY);
                root.draw(graphics, 0, 0, this.width, this.height, mouseX, mouseY, partialTick);
                var tooltip = root.getTooltipLines();
                if (tooltip != null && !tooltip.isEmpty()) {
                    graphics.renderComponentTooltip(this.font, tooltip, mouseX, mouseY);
                }
            }
            renderAfterMenu(graphics, mouseX, mouseY, partialTick);
            graphics.flush();
        } finally {
            graphics.flush();
            graphics.pose().popPose();
            graphics.setColor(1f, 1f, 1f, 1f);
            RenderSystem.disableBlend();
        }
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

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
