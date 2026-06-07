package com.example.hcrpoints.client;

import com.example.hcrpoints.config.ModConfig;
import com.example.hcrpoints.util.ModLogger;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.scores.Team;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.RenderLivingEvent;
import net.minecraftforge.client.event.RenderNameTagEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Matrix4f;

/**
 * 玩家队伍指示器
 * 基于team指令确认友军敌军，在玩家头上显示不同颜色的倒三角形/倒箭头
 * 仅在队伍机制启用时显示
 */
@OnlyIn(Dist.CLIENT)
@Mod.EventBusSubscriber(modid = com.example.hcrpoints.HCRPointsMod.MOD_ID, value = Dist.CLIENT)
public class PlayerTeamIndicator {
    // 渲染偏移量（玩家头顶上方）
    private static final float OFFSET_Y = 1.0f;
    // 三角形大小
    private static final float TRIANGLE_SIZE = 2.0f;
    
    /**
     * 隐藏所有玩家头上的名字标签
     */
    @SubscribeEvent
    public static void onRenderNameTag(RenderNameTagEvent event) {
        // 仅处理玩家实体
        if (event.getEntity() instanceof Player) {
            // RenderNameTagEvent 事件不可取消，不再尝试隐藏名字标签
            // 通过配置或其他方式处理名字标签隐藏
        }
    }
    
    /**
     * 在玩家头上渲染队伍指示器
     */
    @SubscribeEvent
    public static void onRenderPlayer(RenderLivingEvent.Post<?, ?> event) {
        // 已禁用敌我标识三角渲染
        /*
        // 仅处理玩家实体
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        
        // 仅在队伍机制启用时显示
        if (!ModConfig.enableTeams.get()) {
            return;
        }
        
        // 检查敌我标识功能是否启用
        if (!ModConfig.enableTeamIndicator.get()) {
            return;
        }
        
        // 跳过物品栏中的玩家渲染，只在世界中渲染
        if (isInventoryRender()) {
            return;
        }
        
        // 获取当前玩家
        Player localPlayer = Minecraft.getInstance().player;
        if (localPlayer == null) {
            return;
        }
        
        // 获取当前玩家的队伍
        Team localTeam = localPlayer.getTeam();
        Team targetTeam = player.getTeam();
        
        // 计算颜色
        int color = getTeamIndicatorColor(localTeam, targetTeam, player);
        
        // 检查目标玩家是否按下潜行按键，且本地玩家与目标玩家不是友军
        boolean isFriendly = isFriendlyPlayer(localTeam, targetTeam, player);
        boolean isTargetSneaking = player.isShiftKeyDown();
        
        // 如果目标玩家按下潜行按键且不是友军，则不渲染指示器
        if (isTargetSneaking && !isFriendly) {
            return;
        }
        
        // 获取渲染器
        EntityRenderDispatcher renderer = Minecraft.getInstance().getEntityRenderDispatcher();
        if (renderer == null) {
            return;
        }
        
        PoseStack poseStack = event.getPoseStack();
        MultiBufferSource buffer = event.getMultiBufferSource();
        if (buffer == null) {
            return;
        }
        
        // 保存当前矩阵
        poseStack.pushPose();
        
        try {
            // 定位到玩家头顶
            poseStack.translate(0.0D, player.getBbHeight() + OFFSET_Y, 0.0D);
            
            // 旋转到面向玩家视角
            poseStack.mulPose(renderer.cameraOrientation());
            
            // 缩放
            poseStack.scale(-0.075F, -0.075F, 0.075F);
            
            // 渲染指示器
            if (isFriendlyPlayer(localTeam, targetTeam, player)) {
                // 友军：绿色倒箭头
                renderInvertedArrow(poseStack, buffer, color);
            } else {
                // 敌军或观众：倒三角
                renderInvertedTriangle(poseStack, buffer, color);
            }
        } finally {
            // 恢复矩阵，确保无论发生什么都能恢复
            poseStack.popPose();
        }
        */
    }
    
    /**
     * 检查是否为物品栏中的玩家渲染
     * @return 是否为物品栏渲染
     */
    private static boolean isInventoryRender() {
        // 检查渲染上下文，判断是否在物品栏中渲染
        Minecraft mc = Minecraft.getInstance();
        // 检查是否有屏幕，并且是库存相关屏幕
        if (mc.screen != null) {
            // 库存屏幕包括普通物品栏和创造模式物品栏
            String screenName = mc.screen.getClass().getName();
            return screenName.contains("InventoryScreen") || screenName.contains("CreativeModeInventoryScreen");
        }
        return false;
    }
    
    /**
     * 判断是否为友军玩家
     */
    private static boolean isFriendlyPlayer(Team localTeam, Team targetTeam, Player targetPlayer) {
        // 当前玩家没有队伍，所有玩家都是敌军
        if (localTeam == null) {
            return false;
        }
        
        // 目标玩家没有队伍，是观众
        if (targetTeam == null) {
            return false;
        }
        
        // 同一队伍，友军
        return localTeam.equals(targetTeam);
    }
    
    /**
     * 获取队伍指示器颜色
     */
    private static int getTeamIndicatorColor(Team localTeam, Team targetTeam, Player targetPlayer) {
        if (isFriendlyPlayer(localTeam, targetTeam, targetPlayer)) {
            // 友军：绿色
            return 0xFF55FF55;
        } else if (targetTeam == null) {
            // 无队伍（观众）：灰色
            return 0xFFAAAAAA;
        } else {
            // 敌军：红色
            return 0xFFFF5555;
        }
    }
    
    /**
     * 渲染倒三角
     */
    private static void renderInvertedTriangle(PoseStack poseStack, MultiBufferSource buffer, int color) {
        Matrix4f matrix4f = poseStack.last().pose();
        // 使用标准线条渲染类型
        VertexConsumer vertexConsumer = buffer.getBuffer(net.minecraft.client.renderer.RenderType.lines());
        
        // 倒三角三个点坐标
        float centerX = 0.0f;
        float topY = -TRIANGLE_SIZE * 2;
        float bottomY = 0.0f;
        float sideX = TRIANGLE_SIZE;
        
        // 分解颜色为RGBA
        int r = (color >> 16) & 0xFF;
        int g = (color >> 8) & 0xFF;
        int b = color & 0xFF;
        int a = (color >> 24) & 0xFF;
        
        // 顶点1：顶部中心
        vertexConsumer.vertex(matrix4f, centerX, topY, 0.0f)
                .color(r, g, b, a)
                .uv(0.0f, 0.0f)
                .overlayCoords(OverlayTexture.NO_OVERLAY)
                .uv2(LightTexture.FULL_BRIGHT)
                .normal(0.0f, 0.0f, 1.0f)
                .endVertex();
        // 顶点2：左下角
        vertexConsumer.vertex(matrix4f, -sideX, bottomY, 0.0f)
                .color(r, g, b, a)
                .uv(0.0f, 0.0f)
                .overlayCoords(OverlayTexture.NO_OVERLAY)
                .uv2(LightTexture.FULL_BRIGHT)
                .normal(0.0f, 0.0f, 1.0f)
                .endVertex();
        // 顶点2到顶点3：左下角到右下角
        vertexConsumer.vertex(matrix4f, -sideX, bottomY, 0.0f)
                .color(r, g, b, a)
                .uv(0.0f, 0.0f)
                .overlayCoords(OverlayTexture.NO_OVERLAY)
                .uv2(LightTexture.FULL_BRIGHT)
                .normal(0.0f, 0.0f, 1.0f)
                .endVertex();
        // 顶点3：右下角
        vertexConsumer.vertex(matrix4f, sideX, bottomY, 0.0f)
                .color(r, g, b, a)
                .uv(0.0f, 0.0f)
                .overlayCoords(OverlayTexture.NO_OVERLAY)
                .uv2(LightTexture.FULL_BRIGHT)
                .normal(0.0f, 0.0f, 1.0f)
                .endVertex();
        // 顶点3到顶点1：右下角到顶部中心
        vertexConsumer.vertex(matrix4f, sideX, bottomY, 0.0f)
                .color(r, g, b, a)
                .uv(0.0f, 0.0f)
                .overlayCoords(OverlayTexture.NO_OVERLAY)
                .uv2(LightTexture.FULL_BRIGHT)
                .normal(0.0f, 0.0f, 1.0f)
                .endVertex();
        // 顶点1：顶部中心
        vertexConsumer.vertex(matrix4f, centerX, topY, 0.0f)
                .color(r, g, b, a)
                .uv(0.0f, 0.0f)
                .overlayCoords(OverlayTexture.NO_OVERLAY)
                .uv2(LightTexture.FULL_BRIGHT)
                .normal(0.0f, 0.0f, 1.0f)
                .endVertex();
    }
    
    /**
     * 渲染倒箭头
     */
    private static void renderInvertedArrow(PoseStack poseStack, MultiBufferSource buffer, int color) {
        Matrix4f matrix4f = poseStack.last().pose();
        // 使用标准线条渲染类型
        VertexConsumer vertexConsumer = buffer.getBuffer(net.minecraft.client.renderer.RenderType.lines());
        
        // 倒箭头坐标
        float centerX = 0.0f;
        float topY = -TRIANGLE_SIZE * 2;
        float bottomY = 0.0f;
        float sideX = TRIANGLE_SIZE;
        float arrowLength = TRIANGLE_SIZE * 3;
        
        // 分解颜色为RGBA
        int r = (color >> 16) & 0xFF;
        int g = (color >> 8) & 0xFF;
        int b = color & 0xFF;
        int a = (color >> 24) & 0xFF;
        
        // 箭头尖端（顶部中心）
        vertexConsumer.vertex(matrix4f, centerX, topY, 0.0f)
                .color(r, g, b, a)
                .uv(0.0f, 0.0f)
                .overlayCoords(OverlayTexture.NO_OVERLAY)
                .uv2(LightTexture.FULL_BRIGHT)
                .normal(0.0f, 0.0f, 1.0f)
                .endVertex();
        // 箭头左尾
        vertexConsumer.vertex(matrix4f, -sideX, bottomY, 0.0f)
                .color(r, g, b, a)
                .uv(0.0f, 0.0f)
                .overlayCoords(OverlayTexture.NO_OVERLAY)
                .uv2(LightTexture.FULL_BRIGHT)
                .normal(0.0f, 0.0f, 1.0f)
                .endVertex();
        // 箭头尖端到箭头右尾
        vertexConsumer.vertex(matrix4f, centerX, topY, 0.0f)
                .color(r, g, b, a)
                .uv(0.0f, 0.0f)
                .overlayCoords(OverlayTexture.NO_OVERLAY)
                .uv2(LightTexture.FULL_BRIGHT)
                .normal(0.0f, 0.0f, 1.0f)
                .endVertex();
        // 箭头右尾
        vertexConsumer.vertex(matrix4f, sideX, bottomY, 0.0f)
                .color(r, g, b, a)
                .uv(0.0f, 0.0f)
                .overlayCoords(OverlayTexture.NO_OVERLAY)
                .uv2(LightTexture.FULL_BRIGHT)
                .normal(0.0f, 0.0f, 1.0f)
                .endVertex();
        // 箭头左尾到箭头右尾
        vertexConsumer.vertex(matrix4f, -sideX, bottomY, 0.0f)
                .color(r, g, b, a)
                .uv(0.0f, 0.0f)
                .overlayCoords(OverlayTexture.NO_OVERLAY)
                .uv2(LightTexture.FULL_BRIGHT)
                .normal(0.0f, 0.0f, 1.0f)
                .endVertex();
        vertexConsumer.vertex(matrix4f, sideX, bottomY, 0.0f)
                .color(r, g, b, a)
                .uv(0.0f, 0.0f)
                .overlayCoords(OverlayTexture.NO_OVERLAY)
                .uv2(LightTexture.FULL_BRIGHT)
                .normal(0.0f, 0.0f, 1.0f)
                .endVertex();
        // 绘制箭头杆：中心到底部
        vertexConsumer.vertex(matrix4f, centerX, bottomY, 0.0f)
                .color(r, g, b, a)
                .uv(0.0f, 0.0f)
                .overlayCoords(OverlayTexture.NO_OVERLAY)
                .uv2(LightTexture.FULL_BRIGHT)
                .normal(0.0f, 0.0f, 1.0f)
                .endVertex();
        vertexConsumer.vertex(matrix4f, centerX, arrowLength, 0.0f)
                .color(r, g, b, a)
                .uv(0.0f, 0.0f)
                .overlayCoords(OverlayTexture.NO_OVERLAY)
                .uv2(LightTexture.FULL_BRIGHT)
                .normal(0.0f, 0.0f, 1.0f)
                .endVertex();
    }
}