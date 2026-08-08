package org.espetro.client.gui;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraftforge.client.event.RenderGuiEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.joml.Matrix4f;

/**
 * 载具换座延迟处理器。
 * <p>按下 Shift 换座时需等待 5 秒，期间显示倒计时环。</p>
 */
public final class SeatSwitchHandler {

    private static final int SWITCH_DELAY_TICKS = 100; // 5秒
    private static final int RING_RADIUS = 28;
    private static final int RING_THICKNESS = 3;
    private static final int RING_SEGMENTS = 64;

    private static int switchTicks;    // 已等待tick数，0=空闲
    private static boolean blocking;   // true=正在阻挡shift等待倒计时

    private static boolean registered;

    public static void register() {
        if (registered) return;
        registered = true;
        MinecraftForge.EVENT_BUS.register(SeatSwitchHandler.class);
    }

    @SubscribeEvent
    public static void onClientTickPre(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.START) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.player == null || mc.screen != null) {
            switchTicks = 0;
            blocking = false;
            return;
        }

        // 不在载具上 → 重置
        if (mc.player.getVehicle() == null || !isEspetroVehicle(mc.player.getVehicle())) {
            switchTicks = 0;
            blocking = false;
            return;
        }

        boolean shiftDown = mc.options.keyShift.isDown();

        // Shift未按下 → 取消倒计时
        if (!shiftDown) {
            switchTicks = 0;
            blocking = false;
            return;
        }

        // 换座已完成（倒计时结束且未阻挡）→ 等待玩家松手重置
        if (switchTicks >= SWITCH_DELAY_TICKS && !blocking) {
            return;
        }

        // 正在阻挡中 → 倒计时中，继续阻挡
        if (blocking) {
            mc.player.input.shiftKeyDown = false;
            switchTicks++;
            if (switchTicks >= SWITCH_DELAY_TICKS) {
                // 倒计时结束：停止阻挡，让shift自然通过触发实际换座
                blocking = false;
            }
            return;
        }

        // 首次按下 → 开始倒计时
        switchTicks = 1;
        blocking = true;
        mc.player.input.shiftKeyDown = false;
    }

    @SubscribeEvent
    public static void onRenderOverlay(RenderGuiEvent.Post event) {
        if (switchTicks <= 0 || switchTicks > SWITCH_DELAY_TICKS) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc == null) return;

        GuiGraphics graphics = event.getGuiGraphics();

        // 倒计时文字
        float remainSec = (float)(SWITCH_DELAY_TICKS - switchTicks) / 20f;
        String text = String.format("§e换座倒计时 §f%.1fs", Math.max(0, remainSec));
        int cx = mc.getWindow().getGuiScaledWidth() / 2;
        int cy = mc.getWindow().getGuiScaledHeight() / 2;
        graphics.drawString(mc.font, text,
            cx - mc.font.width(text) / 2, cy + 30, 0xFFFFFF);

        // 圆形进度环
        drawProgressRing(event.getGuiGraphics().pose(), mc, cx, cy,
            (float)switchTicks / SWITCH_DELAY_TICKS);
    }

    private static void drawProgressRing(PoseStack poseStack, Minecraft mc,
                                          int cx, int cy, float progress) {
        int r = RING_RADIUS;
        int thick = RING_THICKNESS;
        // 进度环颜色：白→黄渐变
        int color = progress > 0.75f ? 0xFF44CC44
            : progress > 0.5f ? 0xFFCCAA00 : 0xFFCC4444;

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);

        Matrix4f matrix = poseStack.last().pose();
        BufferBuilder builder = Tesselator.getInstance().getBuilder();
        builder.begin(VertexFormat.Mode.TRIANGLE_STRIP, DefaultVertexFormat.POSITION_COLOR);

        float a = (color >> 24) & 0xFF;
        float red = ((color >> 16) & 0xFF) / 255f;
        float green = ((color >> 8) & 0xFF) / 255f;
        float blue = (color & 0xFF) / 255f;
        float alpha = a / 255f;

        int filled = (int)(progress * RING_SEGMENTS);

        for (int i = 0; i <= filled; i++) {
            float angle = (float)(-Math.PI / 2 + 2 * Math.PI * i / RING_SEGMENTS);
            float cos = (float)Math.cos(angle);
            float sin = (float)Math.sin(angle);
            float ix = cx + (r - thick) * cos;
            float iy = cy + (r - thick) * sin;
            float ox = cx + (r + thick) * cos;
            float oy = cy + (r + thick) * sin;
            builder.vertex(matrix, ix, iy, 0).color(red, green, blue, alpha).endVertex();
            builder.vertex(matrix, ox, oy, 0).color(red, green, blue, alpha).endVertex();
        }

        BufferUploader.drawWithShader(builder.end());
        RenderSystem.disableBlend();
    }

    private static boolean isEspetroVehicle(net.minecraft.world.entity.Entity vehicle) {
        for (String tag : vehicle.getTags()) {
            if (tag.startsWith("espetro_team_")) return true;
        }
        return false;
    }
}
