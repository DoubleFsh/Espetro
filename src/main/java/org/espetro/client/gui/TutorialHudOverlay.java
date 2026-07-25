package org.espetro.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

/**
 * 自绘教程 HUD：说明卡片 + 左下角「退出教程」。
 * 不依赖 AuraTip 关卡片才能下一步。
 */
public final class TutorialHudOverlay {

    private static final int EXIT_W = 88;
    private static final int EXIT_H = 16;
    private static final int EXIT_MARGIN = 8;
    private static final int CARD_MARGIN = 10;
    private static final int CARD_MAX_W = 320;

    private TutorialHudOverlay() {
    }

    public static void onStepChanged() {
        // 预留：可在此重置动画
    }

    public static void clear() {
        // no state
    }

    public static void render(GuiGraphics graphics, Minecraft mc, float partialTick) {
        if (!TutorialClientController.isActive() || mc == null || mc.font == null) {
            return;
        }
        Font font = mc.font;
        int sw = mc.getWindow().getGuiScaledWidth();
        int sh = mc.getWindow().getGuiScaledHeight();

        String stepId = TutorialClientController.getStepId();
        Component title = Component.translatable("tutorial.step." + stepId + ".title")
            .append(Component.literal("  "
                + TutorialClientController.getIndex() + " / "
                + TutorialClientController.getTotal()));
        Component body = Component.translatable("tutorial.step." + stepId + ".body");
        String footer = "Enter = 下一步"
            + (TutorialClientController.isAllowSkip() ? "  ·  左下角可完全退出" : "");

        int cardW = Math.min(CARD_MAX_W, Math.max(180, sw - CARD_MARGIN * 2));
        String bodyText = body.getString();
        int bodyH = font.wordWrapHeight(bodyText, cardW - 16);
        int cardH = 12 + font.lineHeight + 6 + bodyH + 8 + font.lineHeight + 10;
        int cardX = (sw - cardW) / 2;
        int cardY = sh - cardH - EXIT_MARGIN - EXIT_H - 6;

        // 半透明说明卡（底部居中，不挡左下退出）
        graphics.fill(cardX, cardY, cardX + cardW, cardY + cardH, 0xCC111416);
        graphics.renderOutline(cardX, cardY, cardW, cardH, 0x90E0B85A);
        graphics.drawString(font, title, cardX + 8, cardY + 6, 0xFFE0B85A, false);
        graphics.drawWordWrap(font, Component.literal(bodyText),
            cardX + 8, cardY + 6 + font.lineHeight + 6, cardW - 16, 0xFFE8E8E8);
        graphics.drawString(font, footer,
            cardX + 8, cardY + cardH - font.lineHeight - 6, 0xFFAEB4BF, false);

        // 左下「退出教程」
        int exitX = EXIT_MARGIN;
        int exitY = sh - EXIT_MARGIN - EXIT_H;
        boolean hover = isOverExit(mc.mouseHandler.xpos(), mc.mouseHandler.ypos(), mc);
        graphics.fill(exitX, exitY, exitX + EXIT_W, exitY + EXIT_H,
            hover ? 0xC0403030 : 0xA0181818);
        graphics.renderOutline(exitX, exitY, EXIT_W, EXIT_H, hover ? 0xFFFF6666 : 0x80FF6666);
        String exitLabel = "退出教程";
        int tw = font.width(exitLabel);
        graphics.drawString(font, exitLabel,
            exitX + (EXIT_W - tw) / 2, exitY + (EXIT_H - font.lineHeight) / 2,
            0xFFFF8888, false);
    }

    public static boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (!TutorialClientController.isActive() || button != 0) {
            return false;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) {
            return false;
        }
        int sh = mc.getWindow().getGuiScaledHeight();
        int exitX = EXIT_MARGIN;
        int exitY = sh - EXIT_MARGIN - EXIT_H;
        if (mouseX >= exitX && mouseX < exitX + EXIT_W
            && mouseY >= exitY && mouseY < exitY + EXIT_H) {
            TutorialClientController.requestSkipAll();
            return true;
        }
        return false;
    }

    private static boolean isOverExit(double windowMouseX, double windowMouseY, Minecraft mc) {
        // window coords → scaled GUI coords
        double scale = mc.getWindow().getGuiScale();
        double mx = windowMouseX / scale;
        double my = windowMouseY / scale;
        int sh = mc.getWindow().getGuiScaledHeight();
        int exitX = EXIT_MARGIN;
        int exitY = sh - EXIT_MARGIN - EXIT_H;
        return mx >= exitX && mx < exitX + EXIT_W && my >= exitY && my < exitY + EXIT_H;
    }
}
