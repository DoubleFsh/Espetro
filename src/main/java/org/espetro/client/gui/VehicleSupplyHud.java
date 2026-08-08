package org.espetro.client.gui;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraftforge.client.event.RenderGuiEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.espetro.network.VehicleSupplySyncPacket;
import org.joml.Matrix4f;

/**
 * 载具 HUD 渲染器：
 * <ul>
 *   <li>容量条：轮盘打开时渲染在屏幕顶部</li>
 *   <li>准心提示：准心对准5格内己方载具时显示"按住 F 来交互"</li>
 * </ul>
 */
public final class VehicleSupplyHud {

    private static final int BAR_WIDTH = 200;
    private static final int BAR_HEIGHT = 6;
    private static final int PANEL_WIDTH = 220;
    private static final int PANEL_HEIGHT = 28;
    private static final int TOP_MARGIN = 4;
    private static final int ICON_SIZE = 10;

    private static final ResourceLocation ICON_AMMO =
        ResourceLocation.fromNamespaceAndPath("espetro", "textures/gui/squad/ammo_supply.png");
    private static final ResourceLocation ICON_CONSTR =
        ResourceLocation.fromNamespaceAndPath("espetro", "textures/gui/squad/construction_supply.png");

    private static final int COLOR_AMMO_FILL = 0xFFCC4444;
    private static final int COLOR_CONSTR_FILL = 0xFFCCAA00;
    private static final int COLOR_BAR_BG = 0xFF333333;
    private static final int COLOR_PANEL_BG = 0xDD111111;
    private static final int COLOR_PANEL_BORDER = 0xFF555555;
    private static final int COLOR_HINT = 0xFFDDBB66;
    private static final double HINT_RANGE = 5.0;
    private static final int HINT_Y_OFFSET = 20;
    private static final int PROGRESS_RING_RADIUS = 38;
    private static final int PROGRESS_RING_THICKNESS = 3;
    private static final int PROGRESS_SEGMENTS = 64;

    private static boolean registered;

    public static void register() {
        if (registered) return;
        registered = true;
        MinecraftForge.EVENT_BUS.register(VehicleSupplyHud.class);
    }

    @SubscribeEvent
    public static void onRenderOverlay(RenderGuiEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.player == null) return;

        GuiGraphics graphics = event.getGuiGraphics();

        if (VehicleWheelController.isWheelActive()) {
            VehicleSupplySyncPacket supply = VehicleWheelController.getCachedSupply();
            if (supply != null) drawCapacityBar(graphics, mc, supply);
            if (VehicleWheelController.isHolding()) drawProgressRing(event.getGuiGraphics().pose(), mc);
            return;
        }

        drawCrosshairHint(graphics, mc);
    }

    // ==================== 容量条（屏幕顶部居中面板） ====================

    private static void drawCapacityBar(GuiGraphics graphics, Minecraft mc, VehicleSupplySyncPacket supply) {
        int max = supply.getMaxCapacity();
        if (max <= 0) return;
        int ammo = supply.getAmmo();
        int constr = supply.canCarryConstruction() ? supply.getConstruction() : 0;

        int screenW = mc.getWindow().getGuiScaledWidth();
        int px = (screenW - PANEL_WIDTH) / 2;
        int py = TOP_MARGIN;

        // 面板背景
        graphics.fill(px, py, px + PANEL_WIDTH, py + PANEL_HEIGHT, COLOR_PANEL_BG);
        // 面板边框
        graphics.fill(px, py, px + PANEL_WIDTH, py + 1, COLOR_PANEL_BORDER);
        graphics.fill(px, py + PANEL_HEIGHT - 1, px + PANEL_WIDTH, py + PANEL_HEIGHT, COLOR_PANEL_BORDER);
        graphics.fill(px, py, px + 1, py + PANEL_HEIGHT, COLOR_PANEL_BORDER);
        graphics.fill(px + PANEL_WIDTH - 1, py, px + PANEL_WIDTH, py + PANEL_HEIGHT, COLOR_PANEL_BORDER);

        // 容量条背景
        int barX = px + 10;
        int barY = py + 4;
        int barW = PANEL_WIDTH - 20;
        int barH = BAR_HEIGHT;
        graphics.fill(barX, barY, barX + barW, barY + barH, COLOR_BAR_BG);

        // 弹药填充（红色，左侧优先）
        if (ammo > 0) {
            int ammoW = (int)((long)ammo * barW / max);
            graphics.fill(barX, barY, barX + ammoW, barY + barH, COLOR_AMMO_FILL);
        }

        // 建材填充（黄色，接在弹药右侧）
        if (constr > 0) {
            int ammoW = ammo > 0 ? (int)((long)ammo * barW / max) : 0;
            int constrW = (int)((long)constr * barW / max);
            int constrEnd = Math.min(barX + ammoW + constrW, barX + barW);
            graphics.fill(barX + ammoW, barY, constrEnd, barY + barH, COLOR_CONSTR_FILL);
        }

        // 图标 + 数值行（容量条下方）
        int iconRowY = barY + barH + 2;
        int iconTextY = iconRowY + (ICON_SIZE - mc.font.lineHeight) / 2;

        if (supply.canCarryConstruction()) {
            // 补给载具：弹药左，建材右
            int ammoIconX = px + 35;
            graphics.blit(ICON_AMMO, ammoIconX, iconRowY, 0, 0, ICON_SIZE, ICON_SIZE, ICON_SIZE, ICON_SIZE);
            graphics.drawString(mc.font, String.valueOf(ammo),
                ammoIconX + ICON_SIZE + 3, iconTextY, 0xFFFFFF);

            String constrText = String.valueOf(constr);
            int constrTextW = mc.font.width(constrText);
            int constrIconX = px + PANEL_WIDTH - 35 - ICON_SIZE - 3 - constrTextW;
            graphics.blit(ICON_CONSTR, constrIconX, iconRowY, 0, 0, ICON_SIZE, ICON_SIZE, ICON_SIZE, ICON_SIZE);
            graphics.drawString(mc.font, constrText,
                constrIconX + ICON_SIZE + 3, iconTextY, 0xFFFFFF);
        } else {
            // 战斗载具：弹药居中
            String ammoText = String.valueOf(ammo);
            int totalW = ICON_SIZE + 3 + mc.font.width(ammoText);
            int startX = px + (PANEL_WIDTH - totalW) / 2;
            graphics.blit(ICON_AMMO, startX, iconRowY, 0, 0, ICON_SIZE, ICON_SIZE, ICON_SIZE, ICON_SIZE);
            graphics.drawString(mc.font, ammoText,
                startX + ICON_SIZE + 3, iconTextY, 0xFFFFFF);
        }
    }

    // ==================== 准心提示 ====================

    private static void drawCrosshairHint(GuiGraphics graphics, Minecraft mc) {
        if (mc.hitResult instanceof EntityHitResult entityHit) {
            Entity target = entityHit.getEntity();
            if (isFriendlyVehicle(target, mc)) {
                int cx = mc.getWindow().getGuiScaledWidth() / 2;
                int cy = mc.getWindow().getGuiScaledHeight() / 2;
                String hint = "按住 F 来交互";
                graphics.drawString(mc.font, hint,
                    cx - mc.font.width(hint) / 2, cy + HINT_Y_OFFSET, COLOR_HINT);
            }
        }
    }

    private static boolean isFriendlyVehicle(Entity entity, Minecraft mc) {
        if (entity == null || mc.player == null) return false;
        if (entity.distanceTo(mc.player) > HINT_RANGE) return false;
        String playerTeam = ClientGameState.getPlayerTeam();
        if (playerTeam == null) return false;
        for (String tag : entity.getTags()) {
            if (tag.startsWith("espetro_team_")) {
                return tag.substring("espetro_team_".length()).equals(playerTeam);
            }
        }
        return false;
    }

    // ==================== 圆形进度条 ====================

    private static void drawProgressRing(PoseStack poseStack, Minecraft mc) {
        int progress = VehicleWheelController.getHoldProgress();  // 0..20
        float fill = Math.min(1.0f, (float)progress / 20.0f);
        int color = VehicleWheelController.getHoldColor();

        int cx = mc.getWindow().getGuiScaledWidth() / 2;
        int cy = mc.getWindow().getGuiScaledHeight() / 2;
        int r = PROGRESS_RING_RADIUS;
        int thick = PROGRESS_RING_THICKNESS;

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

        int segments = PROGRESS_SEGMENTS;
        int filledSegments = (int)(fill * segments);

        // 从顶部 (-90°) 顺时针绘制弧
        for (int i = 0; i <= filledSegments; i++) {
            float angle = (float)(-Math.PI / 2 + 2 * Math.PI * i / segments);
            float cos = (float)Math.cos(angle);
            float sin = (float)Math.sin(angle);
            float innerX = cx + (r - thick) * cos;
            float innerY = cy + (r - thick) * sin;
            float outerX = cx + (r + thick) * cos;
            float outerY = cy + (r + thick) * sin;

            builder.vertex(matrix, innerX, innerY, 0).color(red, green, blue, alpha).endVertex();
            builder.vertex(matrix, outerX, outerY, 0).color(red, green, blue, alpha).endVertex();
        }

        BufferUploader.drawWithShader(builder.end());
        RenderSystem.disableBlend();
    }
}
