package org.espetro.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.espetro.network.VehicleDeployScreenPacket;
import se.mickelus.mutil.gui.GuiElement;
import se.mickelus.mutil.gui.GuiRect;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 载具部署界面
 * 指挥官右键部署棍时打开，列出可用载具及状态
 */
public class VehicleDeployScreen extends Screen {

    private final List<VehicleDeployScreenPacket.VehicleInfo> vehicles;
    private GuiElement root;

    private static final Pattern FORMAT_CODE = Pattern.compile("(?i)\u00a7[0-9A-FK-OR]");

    private static final int PANEL_WIDTH = 520;
    private static final int BUTTON_WIDTH = 520;
    private static final int BUTTON_HEIGHT = 28;
    private static final int VERTICAL_SPACING = 6;
    private static final int TITLE_Y = 12;
    private static final int SUBTITLE_Y = 27;
    private static final int LIST_START_Y = 45;

    private static final int BTN_BG_NORMAL = 0xC05A5A5A;
    private static final int BTN_BG_HOVER = 0xE0707088;
    private static final int BTN_BG_DISABLED = 0xA0454545;
    private static final int BTN_BORDER = 0xFF000000;

    public VehicleDeployScreen(List<VehicleDeployScreenPacket.VehicleInfo> vehicles) {
        super(Component.literal("载具部署"));
        this.vehicles = vehicles != null ? vehicles : new ArrayList<>();
    }

    @Override
    protected void init() {
        super.init();
        rebuildGui();
    }

    private void rebuildGui() {
        this.root = new GuiElement(0, 0, this.width, this.height);

        int panelW = Math.min(PANEL_WIDTH, Math.max(260, this.width - 32));
        int buttonW = Math.min(BUTTON_WIDTH, panelW);
        int startX = (this.width - buttonW) / 2;
        int rowCount = Math.max(1, vehicles.size());
        int panelH = LIST_START_Y + rowCount * BUTTON_HEIGHT + Math.max(0, rowCount - 1) * VERTICAL_SPACING + 16;
        int panelX = (this.width - panelW) / 2;
        int panelY = 4;

        root.addChild(new GuiRect(panelX, panelY, panelW, panelH, 0x80252A35));
        root.addChild(new CenteredText(0, TITLE_Y, this.width, "\u00a76\u00a7l载具部署面板", 0xFFFFFF));
        root.addChild(new CenteredText(0, SUBTITLE_Y, this.width, "\u00a77载具将部署在原部署点附近", 0xAAAAAA));

        if (vehicles.isEmpty()) {
            DeployButton emptyButton = new DeployButton(
                startX,
                LIST_START_Y + 10,
                buttonW,
                BUTTON_HEIGHT,
                "\u00a7c当前编制无载具配置",
                null
            );
            emptyButton.setEnabled(false);
            root.addChild(emptyButton);
            return;
        }

        int y = LIST_START_Y;
        for (VehicleDeployScreenPacket.VehicleInfo v : vehicles) {
            String status;
            boolean enabled;
            if (v.cooldownRemaining > 0) {
                status = "\u00a7c冷却 " + v.cooldownRemaining + "秒";
                enabled = false;
            } else if (v.current >= v.max) {
                status = "\u00a76已满 " + v.current + "/" + v.max;
                enabled = false;
            } else {
                status = "\u00a7a就绪 " + v.current + "/" + v.max;
                enabled = true;
            }

            String label = v.displayName + "  " + status + "  \u00a77(" + v.respawnMinutes + "分钟刷新)";
            DeployButton button = new DeployButton(startX, y, buttonW, BUTTON_HEIGHT, label, () -> {
                if (Minecraft.getInstance().player != null) {
                    Minecraft.getInstance().player.connection.sendCommand("vehicle spawn " + v.type);
                }
            });
            button.setEnabled(enabled);

            root.addChild(button);
            y += BUTTON_HEIGHT + VERTICAL_SPACING;
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(graphics);
        if (root != null) {
            root.draw(graphics, 0, 0, this.width, this.height, mouseX, mouseY, partialTick);
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (root != null && root.onMouseClick((int) mouseX, (int) mouseY, button)) {
            return true;
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
    public boolean shouldCloseOnEsc() {
        return true;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private static String stripFormatting(String text) {
        return FORMAT_CODE.matcher(text).replaceAll("");
    }

    private static class CenteredText extends GuiElement {
        private final String text;
        private final int color;

        CenteredText(int x, int y, int width, String text, int color) {
            super(x, y, width, Minecraft.getInstance().font.lineHeight);
            this.text = text;
            this.color = color;
        }

        @Override
        public void draw(GuiGraphics graphics, int x, int y, int w, int h, int mouseX, int mouseY, float partialTick) {
            if (!isVisible()) {
                return;
            }

            int textWidth = Minecraft.getInstance().font.width(stripFormatting(text));
            graphics.drawString(
                Minecraft.getInstance().font,
                Component.literal(text),
                getX() + (getWidth() - textWidth) / 2,
                getY(),
                color,
                false
            );
        }
    }

    private static class DeployButton extends GuiElement {
        private final Runnable action;
        private final String label;
        private boolean enabled = true;
        private boolean hovered = false;

        DeployButton(int x, int y, int width, int height, String label, Runnable action) {
            super(x, y, width, height);
            this.label = label;
            this.action = action;
        }

        void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        @Override
        public boolean onMouseClick(int mouseX, int mouseY, int button) {
            if (!enabled || !isVisible()) {
                return false;
            }
            if (mouseX >= getX() && mouseX <= getX() + getWidth()
                && mouseY >= getY() && mouseY <= getY() + getHeight()) {
                if (action != null) {
                    action.run();
                }
                return true;
            }
            return false;
        }

        @Override
        public void updateFocusState(int mouseX, int mouseY, int width, int height) {
            hovered = enabled && isVisible()
                && mouseX >= getX() && mouseX <= getX() + getWidth()
                && mouseY >= getY() && mouseY <= getY() + getHeight();
        }

        @Override
        public void draw(GuiGraphics graphics, int x, int y, int width, int height, int mouseX, int mouseY, float partialTick) {
            if (!isVisible()) {
                return;
            }

            updateFocusState(mouseX, mouseY, width, height);

            int bx = getX();
            int by = getY();
            int bw = getWidth();
            int bh = getHeight();
            int bg = !enabled ? BTN_BG_DISABLED : (hovered ? BTN_BG_HOVER : BTN_BG_NORMAL);

            graphics.fill(bx, by, bx + bw, by + bh, bg);
            graphics.renderOutline(bx, by, bw, bh, BTN_BORDER);
            graphics.renderOutline(bx + 1, by + 1, bw - 2, bh - 2, enabled ? 0x706E6E6E : 0x40404040);

            int textColor = enabled ? 0xFFFFFF : 0x888888;
            int textWidth = Minecraft.getInstance().font.width(stripFormatting(label));
            graphics.drawString(
                Minecraft.getInstance().font,
                Component.literal(label),
                bx + (bw - textWidth) / 2,
                by + (bh - 8) / 2,
                textColor,
                false
            );
        }
    }
}
