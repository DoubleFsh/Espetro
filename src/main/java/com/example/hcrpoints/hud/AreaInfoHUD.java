package com.example.hcrpoints.hud;

import com.example.hcrpoints.capturepoint.CapturePointManager;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraftforge.client.gui.overlay.ForgeGui;
import net.minecraftforge.client.gui.overlay.IGuiOverlay;

import java.lang.reflect.Method;
import java.util.Optional;

/**
 * 区域信息HUD渲染类
 * 负责在游戏界面上渲染玩家当前所在的区域信息
 */
public class AreaInfoHUD implements IGuiOverlay {
    private static final int HUD_MARGIN = 10;     // HUD边距
    private static final int TEXT_SIZE = 12;       // 文本大小
    private static final int TEXT_COLOR = 0xFFFFFFFF; // 文本颜色
    private static final int BACKGROUND_COLOR = 0x80000000; // 背景颜色（半透明黑色）

    /**
     * 渲染HUD界面
     * @param gui Forge GUI对象
     * @param guiGraphics GUI图形对象
     * @param partialTick 部分刻度
     * @param screenWidth 屏幕宽度
     * @param screenHeight 屏幕高度
     */
    @Override
    public void render(ForgeGui gui, GuiGraphics guiGraphics, float partialTick, int screenWidth, int screenHeight) {
        // 获取Minecraft实例
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return;
        }

        // 获取玩家当前所在的区域信息
        String areaInfo = getCurrentAreaInfo(mc.player);
        if (areaInfo == null) {
            return; // 如果没有区域信息，不渲染
        }

        // 计算HUD起始位置（屏幕中间，CapturePointHUD上方）
        int textWidth = mc.font.width(areaInfo);
        int hudStartX = (screenWidth - textWidth) / 2;
        int hudStartY = HUD_MARGIN; // 屏幕顶部，位于CapturePointHUD上方

        // 绘制文本（去掉背景）
        guiGraphics.drawString(
                mc.font,
                areaInfo,
                hudStartX,
                hudStartY,
                TEXT_COLOR,
                false
        );
    }

    /**
     * 获取玩家当前所在的区域信息
     * @param player 玩家实例
     * @return 区域信息字符串
     */
    private String getCurrentAreaInfo(net.minecraft.world.entity.player.Player player) {
        try {
            // 使用反射调用HCR BatUI API，避免编译依赖
            Class<?> apiClass = Class.forName("hcr.batui.api.HCRBATUIApi");
            Method getPlayerAreaMethod = apiClass.getMethod("getPlayerArea", net.minecraft.world.entity.player.Player.class);
            Object playerArea = getPlayerAreaMethod.invoke(null, player);

            // 检查Optional是否有值
            Class<?> optionalClass = Class.forName("java.util.Optional");
            Method isPresentMethod = optionalClass.getMethod("isPresent");
            boolean isPresent = (Boolean) isPresentMethod.invoke(playerArea);

            if (isPresent) {
                // 获取Area对象
                Method getMethod = optionalClass.getMethod("get");
                Object area = getMethod.invoke(playerArea);

                // 获取区域名称和类型
                Class<?> areaClass = Class.forName("hcr.batui.data.Area");
                Method getNameMethod = areaClass.getMethod("getName");
                String areaName = (String) getNameMethod.invoke(area);

                Method getTypeMethod = areaClass.getMethod("getType");
                Object areaType = getTypeMethod.invoke(area);

                return String.format("当前区域: %s (%s)", areaName, areaType);
            } else {
                return "当前区域: 无";
            }
        } catch (ClassNotFoundException e) {
            // 如果HCR BatUI API不可用，返回null
            return null;
        } catch (Exception e) {
            // 处理其他异常
            e.printStackTrace();
            return null;
        }
    }
}