package org.espetro.client.gui;

import cc.sighs.auratip.client.render.TipOverlay;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.fml.ModList;
import org.espetro.Espetro;

/**
 * AuraTip 默认挂在 {@code RenderGuiEvent}（HUD 层），而主界面 Screen 在之后绘制，
 * 部署面板等全屏 GUI 会完全挡住错误/拒绝提示。
 * <p>
 * 在 {@link ScreenEvent.Render.Post} 再渲染一次活动中的 TipOverlay（同一实例、同一停靠位
 * 与动画状态），保证提示叠在当前主 GUI 之上，且仍从右侧滑入、停在 {@code RIGHT_CENTER}。
 */
@OnlyIn(Dist.CLIENT)
public final class AuraTipAboveScreen {

    private AuraTipAboveScreen() {
    }

    public static void onScreenRenderPost(ScreenEvent.Render.Post event) {
        if (!ModList.get().isLoaded("auratip")) {
            return;
        }
        try {
            if (!TipOverlay.INSTANCE.isActive()) {
                return;
            }
            Minecraft mc = Minecraft.getInstance();
            if (mc == null || mc.getWindow() == null || event.getScreen() == null) {
                return;
            }

            int width = mc.getWindow().getGuiScaledWidth();
            int height = mc.getWindow().getGuiScaledHeight();
            double mouseX = mc.mouseHandler.xpos()
                * (double) width / (double) mc.getWindow().getScreenWidth();
            double mouseY = mc.mouseHandler.ypos()
                * (double) height / (double) mc.getWindow().getScreenHeight();

            GuiGraphics graphics = event.getGuiGraphics();
            var pose = graphics.pose();
            pose.pushPose();
            // 抬到 Screen 控件之上；不改 tip 的 panelX/Y 与动画，仅提高绘制层级
            pose.translate(0, 0, 1200);
            TipOverlay.INSTANCE.render(
                graphics,
                event.getPartialTick(),
                (int) mouseX,
                (int) mouseY,
                width,
                height
            );
            pose.popPose();
        } catch (Throwable t) {
            Espetro.LOGGER.debug("Screen 上叠加 AuraTip 失败: {}", t.toString());
        }
    }
}
