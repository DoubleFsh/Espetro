package org.espetro.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import org.espetro.network.NetworkManager;
import org.espetro.network.TutorialActionPacket;

import java.util.ArrayList;
import java.util.List;

/**
 * 客户端教程提示卡片：半透明底部面板，不抢全屏焦点。
 * 在 HUD 与任意 Screen 之上绘制；按钮点击通过 Screen 事件或 HUD 鼠标拦截处理。
 */
public final class TutorialOverlay {

    private static final int CARD_WIDTH = 320;
    private static final int PADDING = 8;
    private static final int BTN_H = 14;
    private static final int BTN_GAP = 6;

    private static boolean visible;
    private static String stepId = "";
    private static int index;
    private static int total;
    private static boolean allowSkip = true;

    private static int cardX;
    private static int cardY;
    private static int cardW;
    private static int cardH;
    private static int nextBtnX, dismissBtnX, skipBtnX;
    private static int btnY, btnW;

    private TutorialOverlay() {
    }

    public static void show(String newStepId, int newIndex, int newTotal, boolean newAllowSkip) {
        visible = true;
        stepId = newStepId == null ? "" : newStepId;
        index = newIndex;
        total = newTotal;
        allowSkip = newAllowSkip;
    }

    public static void clear() {
        visible = false;
        stepId = "";
    }

    public static boolean isVisible() {
        return visible;
    }

    public static String getStepId() {
        return stepId;
    }

    public static void render(GuiGraphics graphics, Minecraft mc) {
        if (!visible || mc == null || mc.font == null || mc.options.hideGui) {
            return;
        }

        Font font = mc.font;
        int screenW = graphics.guiWidth();
        int screenH = graphics.guiHeight();

        Component title = Component.translatable("tutorial.step." + stepId + ".title");
        Component body = Component.translatable("tutorial.step." + stepId + ".body");
        Component progress = Component.literal(index + " / " + total);

        int textWidth = CARD_WIDTH - PADDING * 2;
        List<FormattedCharSequence> bodyLines = font.split(body, textWidth);
        if (bodyLines.size() > 8) {
            bodyLines = new ArrayList<>(bodyLines.subList(0, 8));
        }

        int contentH = font.lineHeight
            + 2
            + bodyLines.size() * font.lineHeight
            + 6
            + BTN_H;
        cardW = CARD_WIDTH;
        cardH = contentH + PADDING * 2;
        cardX = (screenW - cardW) / 2;
        cardY = screenH - cardH - 28;

        graphics.fill(cardX, cardY, cardX + cardW, cardY + cardH, 0xC0101018);
        graphics.fill(cardX, cardY, cardX + cardW, cardY + 1, 0xFFE8B84A);
        graphics.fill(cardX, cardY + cardH - 1, cardX + cardW, cardY + cardH, 0x66E8B84A);

        int textX = cardX + PADDING;
        int y = cardY + PADDING;
        graphics.drawString(font, title, textX, y, 0xFFE8B84A, false);
        int progW = font.width(progress);
        graphics.drawString(font, progress, cardX + cardW - PADDING - progW, y, 0xFFAAAAAA, false);
        y += font.lineHeight + 2;

        for (FormattedCharSequence line : bodyLines) {
            graphics.drawString(font, line, textX, y, 0xFFE0E0E0, false);
            y += font.lineHeight;
        }
        y += 6;

        int btnCount = allowSkip ? 3 : 2;
        btnW = (cardW - PADDING * 2 - BTN_GAP * (btnCount - 1)) / btnCount;
        btnY = y;
        nextBtnX = cardX + PADDING;
        dismissBtnX = nextBtnX + btnW + BTN_GAP;
        skipBtnX = dismissBtnX + btnW + BTN_GAP;

        drawButton(graphics, font, nextBtnX, btnY, btnW, Component.translatable("tutorial.btn.next"), 0xFF2E7D32);
        drawButton(graphics, font, dismissBtnX, btnY, btnW, Component.translatable("tutorial.btn.dismiss"), 0xFF455A64);
        if (allowSkip) {
            drawButton(graphics, font, skipBtnX, btnY, btnW, Component.translatable("tutorial.btn.skip"), 0xFFB71C1C);
        }
    }

    private static void drawButton(GuiGraphics graphics, Font font, int x, int y, int w, Component label, int color) {
        graphics.fill(x, y, x + w, y + BTN_H, color);
        graphics.fill(x, y, x + w, y + 1, 0x66FFFFFF);
        int labelW = font.width(label);
        graphics.drawString(font, label, x + (w - labelW) / 2, y + (BTN_H - font.lineHeight) / 2, 0xFFFFFFFF, false);
    }

    /**
     * @return true 若点击落在教程按钮上并已处理
     */
    public static boolean handleClick(double mouseX, double mouseY) {
        if (!visible) {
            return false;
        }
        int mx = (int) mouseX;
        int my = (int) mouseY;
        if (my < btnY || my > btnY + BTN_H) {
            return false;
        }
        if (hit(mx, nextBtnX, btnW)) {
            NetworkManager.NET.sendToServer(TutorialActionPacket.next(stepId));
            return true;
        }
        if (hit(mx, dismissBtnX, btnW)) {
            NetworkManager.NET.sendToServer(TutorialActionPacket.dismiss(stepId));
            return true;
        }
        if (allowSkip && hit(mx, skipBtnX, btnW)) {
            NetworkManager.NET.sendToServer(TutorialActionPacket.skipAll());
            return true;
        }
        return false;
    }

    private static boolean hit(int mx, int x, int w) {
        return mx >= x && mx <= x + w;
    }
}
