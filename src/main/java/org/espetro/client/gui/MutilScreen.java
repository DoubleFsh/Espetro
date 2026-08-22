package org.espetro.client.gui;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import org.espetro.client.aui.AuiScreen;
import org.espetro.client.aui.GuiElement;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

abstract class MutilScreen extends AuiScreen {

    protected boolean tutorialPreviewMode;
    private long lastBindingEpochSec = -1;
    private final List<DynamicBinding> dynamicBindings = new ArrayList<>();

    private record DynamicBinding(EspetroMutilWidgets.Text widget, Supplier<String> supplier) {
    }

    protected MutilScreen(Component title) {
        super(title);
    }

    public final void setTutorialPreviewMode(boolean tutorialPreviewMode) {
        this.tutorialPreviewMode = tutorialPreviewMode;
    }

    public final boolean isTutorialPreviewMode() {
        return tutorialPreviewMode;
    }

    protected final EspetroMutilWidgets.Text bindDynamic(
            EspetroMutilWidgets.Text widget, Supplier<String> supplier) {
        dynamicBindings.add(new DynamicBinding(widget, supplier));
        return widget;
    }

    @Override
    public void tick() {
        super.tick();
        if (!dynamicBindings.isEmpty()) {
            long epochSec = System.currentTimeMillis() / 1000L;
            if (epochSec != lastBindingEpochSec) {
                lastBindingEpochSec = epochSec;
                for (DynamicBinding binding : dynamicBindings) {
                    String next = binding.supplier.get();
                    if (next != null) {
                        binding.widget.setText(next);
                    }
                }
            }
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (tutorialPreviewMode) {
            return TutorialHudOverlay.mouseClicked(mouseX, mouseY, button) || true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (tutorialPreviewMode) {
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (tutorialPreviewMode) {
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (TutorialClientController.handleKeyPressed(keyCode, scanCode, modifiers)) {
            return true;
        }
        if (tutorialPreviewMode) {
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean keyReleased(int keyCode, int scanCode, int modifiers) {
        if (tutorialPreviewMode) {
            return true;
        }
        return super.keyReleased(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        if (tutorialPreviewMode) {
            return true;
        }
        return super.charTyped(codePoint, modifiers);
    }

    @Override
    protected void renderBeforeMutil(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        if (shadeWorld()) {
            EspetroMutilWidgets.drawScreenShade(graphics, this.width, this.height);
        }
    }
}
