package org.espetro.client.aui;

import com.sighs.apricityui.init.Document;
import com.sighs.apricityui.init.Element;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * AUI-backed replacement for MUtil {@code GuiElement}.
 * HTML nodes handle hit-testing; subclasses may still paint with {@link GuiGraphics}.
 */
public class GuiElement {
    protected final Document document;
    protected final Element node;
    protected boolean hasFocus;
    private final List<GuiElement> children = new ArrayList<>();
    private int x;
    private int y;
    private int width;
    private int height;
    private boolean visible = true;
    private List<Component> tooltipLines = List.of();

    public GuiElement(int x, int y, int width, int height) {
        this(x, y, width, height, "aui-node");
    }

    public GuiElement(int x, int y, int width, int height, String cssClass) {
        this.document = null;
        this.x = x;
        this.y = y;
        this.width = Math.max(0, width);
        this.height = Math.max(0, height);
        if (document == null) {
            this.node = null;
            return;
        }
        this.node = document.createHTML(
            "<div class=\"" + cssClass + "\" style=\"" + inlineStyle() + "\"></div>");
    }

    GuiElement(Document document, Element existing, int x, int y, int width, int height) {
        this.document = document;
        this.node = existing;
        this.x = x;
        this.y = y;
        this.width = Math.max(0, width);
        this.height = Math.max(0, height);
        syncStyle();
    }

    public final Element node() {
        return node;
    }

    public int getX() {
        return x;
    }

    public int getY() {
        return y;
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    public void setX(int x) {
        this.x = x;
        syncStyle();
    }

    public void setY(int y) {
        this.y = y;
        syncStyle();
    }

    public void setWidth(int width) {
        this.width = Math.max(0, width);
        syncStyle();
    }

    public void setHeight(int height) {
        this.height = Math.max(0, height);
        syncStyle();
    }

    public boolean isVisible() {
        return visible;
    }

    public void setVisible(boolean visible) {
        this.visible = visible;
        if (node == null) {
            return;
        }
        if (visible) {
            node.removeAttribute("hidden");
            String cls = node.getClassName();
            if (cls != null && cls.contains("hidden")) {
                node.setClassName(cls.replace("hidden", "").trim());
            }
        } else {
            node.setAttribute("hidden", "true");
            String cls = node.getClassName();
            if (cls == null || !cls.contains("hidden")) {
                node.setClassName((cls == null ? "aui-node" : cls) + " hidden");
            }
        }
    }

    public boolean hasFocus() {
        return hasFocus;
    }

    public void addChild(GuiElement child) {
        if (child == null) {
            return;
        }
        children.add(child);
        if (node != null && child.node != null) {
            node.appendChild(child.node);
        }
    }

    public void clearChildren() {
        for (GuiElement child : new ArrayList<>(children)) {
            if (child.node != null) {
                child.node.remove();
            }
        }
        children.clear();
    }

    public List<GuiElement> getChildren() {
        return Collections.unmodifiableList(children);
    }

    public void setTooltip(List<Component> lines) {
        tooltipLines = lines == null ? List.of() : List.copyOf(lines);
    }

    public List<Component> getTooltipLines() {
        if (!tooltipLines.isEmpty() && hasFocus && visible) {
            return tooltipLines;
        }
        for (GuiElement child : children) {
            List<Component> nested = child.getTooltipLines();
            if (nested != null && !nested.isEmpty()) {
                return nested;
            }
        }
        return List.of();
    }

    public void updateAnimations() {
        for (GuiElement child : children) {
            child.updateAnimations();
        }
    }

    public void updateFocusState(int refX, int refY, int mouseX, int mouseY) {
        boolean next = visible
            && mouseX >= refX + x && mouseX < refX + x + width
            && mouseY >= refY + y && mouseY < refY + y + height;
        if (next != hasFocus) {
            hasFocus = next;
            if (hasFocus) {
                onFocus();
            } else {
                onBlur();
            }
        }
        for (GuiElement child : children) {
            if (child.isVisible()) {
                child.updateFocusState(refX + x, refY + y, mouseX, mouseY);
            }
        }
    }

    public boolean onMouseClick(int mouseX, int mouseY, int button) {
        for (int i = children.size() - 1; i >= 0; i--) {
            GuiElement child = children.get(i);
            if (child.isVisible() && child.onMouseClick(mouseX, mouseY, button)) {
                return true;
            }
        }
        return false;
    }

    public void onMouseRelease(int mouseX, int mouseY, int button) {
        for (GuiElement child : children) {
            child.onMouseRelease(mouseX, mouseY, button);
        }
    }

    public boolean onMouseScroll(double mouseX, double mouseY, double delta) {
        for (int i = children.size() - 1; i >= 0; i--) {
            GuiElement child = children.get(i);
            if (child.isVisible() && child.onMouseScroll(mouseX, mouseY, delta)) {
                return true;
            }
        }
        return false;
    }

    public boolean onKeyPress(int keyCode, int scanCode, int modifiers) {
        for (GuiElement child : children) {
            if (child.onKeyPress(keyCode, scanCode, modifiers)) {
                return true;
            }
        }
        return false;
    }

    public boolean onKeyRelease(int keyCode, int scanCode, int modifiers) {
        for (GuiElement child : children) {
            if (child.onKeyRelease(keyCode, scanCode, modifiers)) {
                return true;
            }
        }
        return false;
    }

    public boolean onCharType(char codePoint, int modifiers) {
        for (GuiElement child : children) {
            if (child.onCharType(codePoint, modifiers)) {
                return true;
            }
        }
        return false;
    }

    public void draw(GuiGraphics graphics, int x, int y, int width, int height,
                     int mouseX, int mouseY, float partialTick) {
        if (!visible) {
            return;
        }
        drawChildren(graphics, x + this.x, y + this.y, width, height, mouseX, mouseY, partialTick);
    }

    protected void drawChildren(GuiGraphics graphics, int refX, int refY, int screenWidth,
                                int screenHeight, int mouseX, int mouseY, float opacity) {
        for (GuiElement child : children) {
            if (child.isVisible()) {
                child.draw(graphics, refX, refY, screenWidth, screenHeight, mouseX, mouseY, opacity);
            }
        }
    }

    protected void onFocus() {
    }

    protected void onBlur() {
    }

    protected final void syncStyle() {
        if (node == null) {
            return;
        }
        node.setAttribute("style", inlineStyle());
    }

    private String inlineStyle() {
        return "left:" + x + "px;top:" + y + "px;width:" + width + "px;height:" + height + "px;";
    }
}
