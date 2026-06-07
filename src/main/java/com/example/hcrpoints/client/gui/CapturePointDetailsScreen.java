package com.example.hcrpoints.client.gui;

import com.example.hcrpoints.HCRPointsMod;
import com.example.hcrpoints.capturepoint.DisplayState;
import com.example.hcrpoints.capturepoint.CapturePoint;
import com.example.hcrpoints.capturepoint.CapturePointManager;
import java.util.ArrayList;
import java.util.Collection;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.List;

@OnlyIn(Dist.CLIENT)
public class CapturePointDetailsScreen extends Screen {
    private static final int BACKGROUND_WIDTH = 200;
    private static final int BACKGROUND_HEIGHT = 166;
    private static final int POINTS_PER_PAGE = 5; // 每页显示的据点数量

    private int leftPos;
    private int topPos;
    private Button prevPageButton;
    private Button nextPageButton;
    private int currentPage = 0;
    private List<CapturePoint> allPoints;

    public CapturePointDetailsScreen() {
        super(Component.literal("据点信息"));
        this.allPoints = new ArrayList<>(CapturePointManager.getInstance().getAllCapturePoints());
    }

    @Override
    protected void init() {
        this.leftPos = (this.width - BACKGROUND_WIDTH) / 2;
        this.topPos = (this.height - BACKGROUND_HEIGHT) / 2;
        
        // 添加翻页按钮
        this.prevPageButton = this.addRenderableWidget(
            Button.builder(Component.literal("<"), (button) -> {
                if (currentPage > 0) {
                    currentPage--;
                    updatePageButtons();
                }
            }).bounds(this.leftPos + 20, this.topPos + BACKGROUND_HEIGHT - 30, 20, 20).build()
        );
        
        this.nextPageButton = this.addRenderableWidget(
            Button.builder(Component.literal(">"), (button) -> {
                int maxPage = (allPoints.size() - 1) / POINTS_PER_PAGE;
                if (currentPage < maxPage) {
                    currentPage++;
                    updatePageButtons();
                }
            }).bounds(this.leftPos + BACKGROUND_WIDTH - 40, this.topPos + BACKGROUND_HEIGHT - 30, 20, 20).build()
        );
        

        
        updatePageButtons();
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(guiGraphics);
        super.render(guiGraphics, mouseX, mouseY, partialTick);
        
        // 实时更新据点列表
        this.allPoints = new ArrayList<>(CapturePointManager.getInstance().getAllCapturePoints());
        
        // 渲染背景（纯绘制，不使用贴图）
        renderBackgroundBox(guiGraphics);
        
        // 渲染标题
        guiGraphics.drawString(
            this.font,
            this.title,
            this.leftPos + (BACKGROUND_WIDTH / 2 - this.font.width(this.title) / 2),
            this.topPos + 6,
            0x404040,
            false
        );
        
        // 渲染据点信息
        renderCapturePoints(guiGraphics);
        
        // 渲染页码信息
        renderPageInfo(guiGraphics);
    }
    
    private void renderBackgroundBox(GuiGraphics guiGraphics) {
        // 绘制背景框（深色背景）
        guiGraphics.fill(this.leftPos, this.topPos, this.leftPos + BACKGROUND_WIDTH, this.topPos + BACKGROUND_HEIGHT, 0xFF202020);
        
        // 绘制边框
        guiGraphics.fill(this.leftPos, this.topPos, this.leftPos + BACKGROUND_WIDTH, this.topPos + 1, 0xFF000000); // 上边框
        guiGraphics.fill(this.leftPos, this.topPos, this.leftPos + 1, this.topPos + BACKGROUND_HEIGHT, 0xFF000000); // 左边框
        guiGraphics.fill(this.leftPos + BACKGROUND_WIDTH - 1, this.topPos, this.leftPos + BACKGROUND_WIDTH, this.topPos + BACKGROUND_HEIGHT, 0xFF000000); // 右边框
        guiGraphics.fill(this.leftPos, this.topPos + BACKGROUND_HEIGHT - 1, this.leftPos + BACKGROUND_WIDTH, this.topPos + BACKGROUND_HEIGHT, 0xFF000000); // 下边框
    }

    private void renderCapturePoints(GuiGraphics guiGraphics) {
        int startIndex = currentPage * POINTS_PER_PAGE;
        int endIndex = Math.min(startIndex + POINTS_PER_PAGE, allPoints.size());
        
        for (int i = startIndex; i < endIndex; i++) {
            CapturePoint point = allPoints.get(i);
            renderCapturePointInfo(guiGraphics, point, i - startIndex);
        }
    }

    private void renderCapturePointInfo(GuiGraphics guiGraphics, CapturePoint point, int index) {
        int yPos = this.topPos + 20 + index * 22;
        int xPos = this.leftPos + 10;
        
        // 显示据点名称
        guiGraphics.drawString(this.font, point.getName(), xPos, yPos, 0xFFFFFF, false);
        
        // 显示坐标
        String coordinates = String.format("(%d, %d, %d)", point.getPos1().getX(), point.getPos1().getY(), point.getPos1().getZ());
        guiGraphics.drawString(this.font, coordinates, xPos + 80, yPos, 0xAAAAAA, false);
        
        // 显示状态
        String statusText = getStatusText(point);
        int statusColor = getStatusColor(point);
        
        // 显示占领者信息
        String captorText = point.getCaptorName();
        if (!captorText.isEmpty()) {
            statusText += " - " + captorText;
        }
        
        guiGraphics.drawString(this.font, statusText, xPos, yPos + 10, statusColor, false);
        
        // 绘制进度条背景
        guiGraphics.fill(xPos + 80, yPos + 10, xPos + 180, yPos + 15, 0xFF555555);
        
        // 绘制进度条
        int progressWidth = (int) (point.getProgress() / 100.0 * 100); // 使用getProgress方法并转换为正确的比例
        guiGraphics.fill(xPos + 80, yPos + 10, xPos + 80 + progressWidth, yPos + 15, getStatusColor(point));
    }
    
    private void updatePageButtons() {
        if (prevPageButton != null && nextPageButton != null) {
            int maxPage = (allPoints.size() - 1) / POINTS_PER_PAGE;
            prevPageButton.active = currentPage > 0;
            nextPageButton.active = currentPage < maxPage;
        }
    }
    
    private void renderPageInfo(GuiGraphics guiGraphics) {
        if (allPoints.isEmpty()) {
            return;
        }
        
        int maxPage = (allPoints.size() - 1) / POINTS_PER_PAGE;
        String pageInfo = String.format("%d / %d", currentPage + 1, maxPage + 1);
        guiGraphics.drawString(
            this.font,
            pageInfo,
            this.leftPos + BACKGROUND_WIDTH / 2 - this.font.width(pageInfo) / 2,
            this.topPos + BACKGROUND_HEIGHT - 25,
            0xFFFFFF,
            false
        );
    }

    private String getStatusText(CapturePoint point) {
        // 注意：这里假设CapturePoint类有一个getDisplayState方法返回DisplayState枚举
        // 如果实际实现不同，需要相应调整
        DisplayState displayState = point.getDisplayState();
        switch (displayState) {
            case CAPTURED:
                return "已占领";
            case CAPTURING_FLAG_SINGLE:
            case CAPTURING_CONTESTED_MULTI:
            case CONTESTED_MULTI:
            case CAPTURING_DOWN:
                return "争夺中";
            case NEUTRAL:
            default:
                return "中立";
        }
    }

    private int getStatusColor(CapturePoint point) {
        DisplayState displayState = point.getDisplayState();
        switch (displayState) {
            case CAPTURED:
                // 检查占领是否为友方，根据情况显示不同颜色
                if (isFriendlyCapture(point)) {
                    return 0xFF55FF55; // 绿色 - 友方占领
                } else {
                    return 0xFFFF5555; // 红色 - 敌方占领
                }
            case CAPTURING_FLAG_SINGLE:
            case CAPTURING_CONTESTED_MULTI:
            case CONTESTED_MULTI:
            case CAPTURING_DOWN:
                return 0xFFFFAA00; // 橙色
            case NEUTRAL:
            default:
                return 0xFFAAAAAA; // 灰色
        }
    }
    
    /**
     * 检查占领是否为友方
     * @param point 据点对象
     * @return 如果是友方占领返回true，否则返回false
     */
    private boolean isFriendlyCapture(CapturePoint point) {
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        if (mc.player == null) {
            return false;
        }
        
        String captorName = point.getCaptorName();
        if (captorName.isEmpty()) {
            return false;
        }
        
        // 检查当前玩家的队伍
        net.minecraft.world.scores.Team playerTeam = mc.player.getTeam();
        
        // 如果占领者是玩家名称
        if (captorName.equals(mc.player.getName().getString())) {
            return true;
        }
        
        // 如果占领者是队伍名称，且当前玩家在该队伍中
        if (playerTeam != null && captorName.equals(playerTeam.getName())) {
            return true;
        }
        
        return false;
    }

    @Override
    public boolean isPauseScreen() {
        return false; // 不暂停游戏
    }
}