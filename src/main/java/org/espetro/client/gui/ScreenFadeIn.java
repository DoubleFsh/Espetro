package org.espetro.client.gui;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.gui.GuiGraphics;

/**
 * GUI 从下往上的渐入动画辅助类（从透明到不透明 + 向上位移）。
 * 参考 tetra/mutil 的 KeyframeAnimation + Applier.Opacity/TranslateY 思路，
 * 但使用 RenderSystem 全局 alpha，兼容所有自定义 widget。
 */
public final class ScreenFadeIn {

    /** 动画时长（毫秒） */
    private static final int DURATION_MS = 300;
    /** 起始 Y 偏移（像素，从下方上移） */
    private static final int START_OFFSET_Y = 24;

    private final long startTime;
    private float progress = 0f;

    public ScreenFadeIn() {
        this.startTime = System.currentTimeMillis();
    }

    /**
     * 在绘制前调用，应用渐入动画的全局状态（alpha + Y 偏移）。
     * 返回当前 Y 偏移量（像素），调用方应将绘制内容向下平移此值。
     */
    public float preRender(GuiGraphics graphics) {
        long elapsed = System.currentTimeMillis() - startTime;
        progress = Math.min(1f, elapsed / (float) DURATION_MS);

        // 缓动函数：easeOutCubic
        float eased = 1f - (1f - progress) * (1f - progress) * (1f - progress);

        // alpha 从 0 到 1
        float alpha = eased;
        // Y 偏移从 START_OFFSET_Y 到 0
        float offsetY = START_OFFSET_Y * (1f - eased);

        // 应用全局 alpha（影响所有后续绘制）
        RenderSystem.enableBlend();
        RenderSystem.setShaderColor(1f, 1f, 1f, alpha);

        return offsetY;
    }

    /**
     * 在绘制后调用，恢复 RenderSystem 状态。
     */
    public void postRender() {
        RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
        RenderSystem.disableBlend();
    }

    /**
     * 动画是否已完成。
     */
    public boolean isFinished() {
        return progress >= 1f;
    }

    /**
     * 平移绘制内容（Y 方向），用于在渐入时从下方上移。
     * 通过 GuiGraphics 的 pose stack 实现。
     */
    public static void translateY(GuiGraphics graphics, float y) {
        graphics.pose().translate(0, y, 0);
    }
}
