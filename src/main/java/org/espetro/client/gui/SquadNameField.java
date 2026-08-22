package org.espetro.client.gui;

import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import org.lwjgl.glfw.GLFW;
import org.espetro.client.aui.GuiElement;

/**
 * 小队名称输入框（顶层类，避免嵌套类在部分运行时模块加载器下出现
 * {@code NoClassDefFoundError: SquadScreen$NameField}）。
 */
final class SquadNameField extends GuiElement {
    private static final int MAX_LENGTH = 18;
    private static final float TEXT_SCALE = 0.72f;

    private final String placeholder;
    private final Runnable submit;
    private String value = "";
    private boolean active = false;

    SquadNameField(int x, int y, int width, int height, String placeholder, Runnable submit) {
        super(x, y, width, height);
        this.placeholder = placeholder == null ? "" : placeholder;
        this.submit = submit;
    }

    String getValue() {
        return value.trim();
    }

    void clear() {
        value = "";
    }

    @Override
    public boolean onMouseClick(int mouseX, int mouseY, int button) {
        if (button != 0 || !isVisible()) {
            return false;
        }
        active = hasFocus();
        return active;
    }

    @Override
    public boolean onKeyPress(int keyCode, int scanCode, int modifiers) {
        if (!active) {
            return false;
        }

        if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
            if (submit != null) {
                submit.run();
            }
            return true;
        }

        if (keyCode == GLFW.GLFW_KEY_BACKSPACE && !value.isEmpty()) {
            int cut = value.offsetByCodePoints(value.length(), -1);
            value = value.substring(0, cut);
            return true;
        }

        return false;
    }

    @Override
    public boolean onCharType(char codePoint, int modifiers) {
        if (!active || value.length() >= MAX_LENGTH || !SharedConstants.isAllowedChatCharacter(codePoint)) {
            return false;
        }
        value += codePoint;
        return true;
    }

    @Override
    public void draw(GuiGraphics graphics, int x, int y, int width, int height,
                     int mouseX, int mouseY, float partialTick) {
        if (!isVisible()) {
            return;
        }

        int bx = x + getX();
        int by = y + getY();
        // 接近不透明，避免输入框在半透明 UI 上叠影闪烁。
        graphics.fill(bx, by, bx + getWidth(), by + getHeight(), active ? 0xF0504834 : 0xE0404040);
        graphics.renderOutline(bx, by, getWidth(), getHeight(),
            active ? EspetroAuiWidgets.BORDER_ACTIVE : EspetroAuiWidgets.BORDER);

        String drawn = value.isEmpty() ? placeholder : value;
        int color = value.isEmpty() ? EspetroAuiWidgets.DIM : EspetroAuiWidgets.TEXT;
        int logicalTextWidth = Math.max(8, (int) ((getWidth() - 6) / TEXT_SCALE));
        String trimmed = Minecraft.getInstance().font.plainSubstrByWidth(drawn, logicalTextWidth);
        int textHeight = Math.max(1,
            Math.round(Minecraft.getInstance().font.lineHeight * TEXT_SCALE));
        EspetroAuiWidgets.drawScaledString(graphics, trimmed,
            bx + 3, by + Math.max(1, (getHeight() - textHeight) / 2),
            color, TEXT_SCALE);

        if (active && !value.isEmpty() && (System.currentTimeMillis() / 500) % 2 == 0) {
            int textW = Math.round(Minecraft.getInstance().font.width(trimmed) * TEXT_SCALE);
            int cursorX = Math.min(bx + getWidth() - 3, bx + 3 + textW + 1);
            graphics.fill(cursorX, by + 2, cursorX + 1, by + getHeight() - 2,
                EspetroAuiWidgets.TEXT);
        }
    }
}
