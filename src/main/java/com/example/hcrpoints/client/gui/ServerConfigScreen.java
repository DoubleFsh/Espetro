package com.example.hcrpoints.client.gui;

import com.example.hcrpoints.config.ModConfig;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraftforge.common.ForgeConfigSpec;

/**
 * 服务端配置界面
 */
public class ServerConfigScreen extends Screen {
    private static final Component TITLE = Component.literal("服务端配置");
    private final Screen parentScreen;
    
    // 滚动处理
    private static final int SCROLL_SPEED = 10;
    private static final int CONTENT_HEIGHT = 600;
    private static final int WIDGET_HEIGHT = 20;
    private static final int LINE_SPACING = 25;
    private static final int LABEL_TOP_PADDING = 5;
    
    private int scrollOffset = 0;
    private boolean isScrolling = false;
    
    // 配置项组件
    // HUD配置
    private CycleButton<Boolean> enableHUDButton;
    private CycleButton<Boolean> enableCarouselButton;
    
    // 队伍配置
    private CycleButton<Boolean> enableTeamsButton;
    
    // 性能配置
    private EditBox checkIntervalInput;
    
    // 奖励配置
    private EditBox pointRewardIntervalInput;
    private EditBox pointRewardAmountInput;
    private EditBox killRewardAmountInput;
    private EditBox captureRewardAmountInput;
    private EditBox capturedRewardIntervalInput;
    private EditBox capturedRewardAmountInput;
    private EditBox capturedRewardDelayInput;
    private EditBox friendlyFirePenaltyInput;
    private CycleButton<Boolean> enableFriendlyFirePenaltyButton;
    
    // 行动攻防机制配置
    private CycleButton<Boolean> enableOperationModeButton;
    private EditBox lowReinforcementThresholdInput;
    
    // 标签组件
    private Component hudSettingsLabel;
    private Component teamSettingsLabel;
    private Component performanceSettingsLabel;
    private Component rewardSettingsLabel;
    private Component operationSettingsLabel;
    
    private Component enableHUDLabel;
    private Component enableCarouselLabel;
    private Component enableTeamsLabel;
    private Component checkIntervalLabel;
    private Component pointRewardIntervalLabel;
    private Component pointRewardAmountLabel;
    private Component killRewardAmountLabel;
    private Component captureRewardAmountLabel;
    private Component capturedRewardIntervalLabel;
    private Component capturedRewardAmountLabel;
    private Component capturedRewardDelayLabel;
    private Component friendlyFirePenaltyLabel;
    private Component enableFriendlyFirePenaltyLabel;
    private Component enableOperationModeLabel;
    private Component lowReinforcementThresholdLabel;
    
    public ServerConfigScreen(Screen parentScreen) {
        super(TITLE);
        this.parentScreen = parentScreen;
        
        // 初始化标签
        this.hudSettingsLabel = Component.literal("HUD配置");
        this.teamSettingsLabel = Component.literal("队伍配置");
        this.performanceSettingsLabel = Component.literal("性能配置");
        this.rewardSettingsLabel = Component.literal("奖励配置");
        this.operationSettingsLabel = Component.literal("行动攻防机制配置");
        
        this.enableHUDLabel = Component.literal("启用HUD显示");
        this.enableCarouselLabel = Component.literal("启用据点信息轮播");
        this.enableTeamsLabel = Component.literal("启用队伍机制");
        this.checkIntervalLabel = Component.literal("据点检查间隔（tick）");
        this.pointRewardIntervalLabel = Component.literal("据点内获得点数的时间间隔（秒）");
        this.pointRewardAmountLabel = Component.literal("据点内每次获得的点数");
        this.killRewardAmountLabel = Component.literal("击杀玩家获得的点数");
        this.captureRewardAmountLabel = Component.literal("占领据点获得的点数");
        this.capturedRewardIntervalLabel = Component.literal("占领后持续奖励间隔（秒）");
        this.capturedRewardAmountLabel = Component.literal("占领后每次奖励点数");
        this.capturedRewardDelayLabel = Component.literal("占领后奖励延迟（秒）");
        this.friendlyFirePenaltyLabel = Component.literal("友军击杀扣除点数");
        this.enableFriendlyFirePenaltyLabel = Component.literal("启用友军击杀惩罚");
        this.enableOperationModeLabel = Component.literal("启用行动攻防机制");
        this.lowReinforcementThresholdLabel = Component.literal("兵力不足阈值（%）");
    }
    
    @Override
    protected void init() {
        super.init();
        this.clearWidgets();
        
        int centerX = this.width / 2;
        int leftColumnX = centerX - 200;
        int rightColumnX = centerX + 50;
        int startY = 40;
        
        // 计算带滚动偏移的当前Y位置
        int currentY = startY - scrollOffset;
        
        // HUD配置部分
        addSectionTitle(currentY, hudSettingsLabel);
        currentY += LINE_SPACING;
        
        // 启用HUD显示
        addLabel(currentY, enableHUDLabel);
        this.enableHUDButton = createBooleanCycleButton(rightColumnX, currentY, ModConfig.enableHUD);
        currentY += LINE_SPACING;
        
        // 启用据点信息轮播
        addLabel(currentY, enableCarouselLabel);
        this.enableCarouselButton = createBooleanCycleButton(rightColumnX, currentY, ModConfig.enableCarousel);
        currentY += LINE_SPACING * 2;
        
        // 队伍配置部分
        addSectionTitle(currentY, teamSettingsLabel);
        currentY += LINE_SPACING;
        
        // 启用队伍机制
        addLabel(currentY, enableTeamsLabel);
        this.enableTeamsButton = createBooleanCycleButton(rightColumnX, currentY, ModConfig.enableTeams);
        currentY += LINE_SPACING * 2;
        
        // 性能配置部分
        addSectionTitle(currentY, performanceSettingsLabel);
        currentY += LINE_SPACING;
        
        // 据点检查间隔
        addLabel(currentY, checkIntervalLabel);
        this.checkIntervalInput = createIntegerEditBox(rightColumnX, currentY, ModConfig.checkInterval.get());
        this.checkIntervalInput.setResponder(s -> {
            if (!s.isEmpty()) {
                try {
                    int value = Integer.parseInt(s);
                    if (value >= 1 && value <= 100) {
                        ModConfig.checkInterval.set(value);
                        saveConfig();
                    }
                } catch (NumberFormatException e) {
                    // 忽略无效输入
                }
            }
        });
        currentY += LINE_SPACING * 2;
        
        // 奖励配置部分
        addSectionTitle(currentY, rewardSettingsLabel);
        currentY += LINE_SPACING;
        
        // 据点内获得点数的时间间隔
        addLabel(currentY, pointRewardIntervalLabel);
        this.pointRewardIntervalInput = createIntegerEditBox(rightColumnX, currentY, ModConfig.pointRewardInterval.get());
        this.pointRewardIntervalInput.setResponder(s -> {
            if (!s.isEmpty()) {
                try {
                    int value = Integer.parseInt(s);
                    if (value >= 1 && value <= 3600) {
                        ModConfig.pointRewardInterval.set(value);
                        saveConfig();
                    }
                } catch (NumberFormatException e) {
                    // 忽略无效输入
                }
            }
        });
        currentY += LINE_SPACING;
        
        // 据点内每次获得的点数
        addLabel(currentY, pointRewardAmountLabel);
        this.pointRewardAmountInput = createIntegerEditBox(rightColumnX, currentY, ModConfig.pointRewardAmount.get());
        this.pointRewardAmountInput.setResponder(s -> {
            if (!s.isEmpty()) {
                try {
                    int value = Integer.parseInt(s);
                    if (value >= 1 && value <= 1000) {
                        ModConfig.pointRewardAmount.set(value);
                        saveConfig();
                    }
                } catch (NumberFormatException e) {
                    // 忽略无效输入
                }
            }
        });
        currentY += LINE_SPACING;
        
        // 击杀玩家获得的点数
        addLabel(currentY, killRewardAmountLabel);
        this.killRewardAmountInput = createIntegerEditBox(rightColumnX, currentY, ModConfig.killRewardAmount.get());
        this.killRewardAmountInput.setResponder(s -> {
            if (!s.isEmpty()) {
                try {
                    int value = Integer.parseInt(s);
                    if (value >= 1 && value <= 1000) {
                        ModConfig.killRewardAmount.set(value);
                        saveConfig();
                    }
                } catch (NumberFormatException e) {
                    // 忽略无效输入
                }
            }
        });
        currentY += LINE_SPACING;
        
        // 占领据点获得的点数
        addLabel(currentY, captureRewardAmountLabel);
        this.captureRewardAmountInput = createIntegerEditBox(rightColumnX, currentY, ModConfig.captureRewardAmount.get());
        this.captureRewardAmountInput.setResponder(s -> {
            if (!s.isEmpty()) {
                try {
                    int value = Integer.parseInt(s);
                    if (value >= 1 && value <= 1000) {
                        ModConfig.captureRewardAmount.set(value);
                        saveConfig();
                    }
                } catch (NumberFormatException e) {
                    // 忽略无效输入
                }
            }
        });
        currentY += LINE_SPACING;
        
        // 占领后持续奖励间隔
        addLabel(currentY, capturedRewardIntervalLabel);
        this.capturedRewardIntervalInput = createIntegerEditBox(rightColumnX, currentY, ModConfig.capturedRewardInterval.get());
        this.capturedRewardIntervalInput.setResponder(s -> {
            if (!s.isEmpty()) {
                try {
                    int value = Integer.parseInt(s);
                    if (value >= 1 && value <= 3600) {
                        ModConfig.capturedRewardInterval.set(value);
                        saveConfig();
                    }
                } catch (NumberFormatException e) {
                    // 忽略无效输入
                }
            }
        });
        currentY += LINE_SPACING;
        
        // 占领后每次奖励点数
        addLabel(currentY, capturedRewardAmountLabel);
        this.capturedRewardAmountInput = createIntegerEditBox(rightColumnX, currentY, ModConfig.capturedRewardAmount.get());
        this.capturedRewardAmountInput.setResponder(s -> {
            if (!s.isEmpty()) {
                try {
                    int value = Integer.parseInt(s);
                    if (value >= 1 && value <= 1000) {
                        ModConfig.capturedRewardAmount.set(value);
                        saveConfig();
                    }
                } catch (NumberFormatException e) {
                    // 忽略无效输入
                }
            }
        });
        currentY += LINE_SPACING;
        
        // 占领后奖励延迟
        addLabel(currentY, capturedRewardDelayLabel);
        this.capturedRewardDelayInput = createIntegerEditBox(rightColumnX, currentY, ModConfig.capturedRewardDelay.get());
        this.capturedRewardDelayInput.setResponder(s -> {
            if (!s.isEmpty()) {
                try {
                    int value = Integer.parseInt(s);
                    if (value >= 1 && value <= 3600) {
                        ModConfig.capturedRewardDelay.set(value);
                        saveConfig();
                    }
                } catch (NumberFormatException e) {
                    // 忽略无效输入
                }
            }
        });
        currentY += LINE_SPACING;
        
        // 启用友军击杀惩罚
        addLabel(currentY, enableFriendlyFirePenaltyLabel);
        this.enableFriendlyFirePenaltyButton = createBooleanCycleButton(rightColumnX, currentY, ModConfig.enableFriendlyFirePenalty);
        currentY += LINE_SPACING;
        
        // 友军击杀扣除点数
        addLabel(currentY, friendlyFirePenaltyLabel);
        this.friendlyFirePenaltyInput = createIntegerEditBox(rightColumnX, currentY, ModConfig.friendlyFirePenalty.get());
        this.friendlyFirePenaltyInput.setResponder(s -> {
            if (!s.isEmpty()) {
                try {
                    int value = Integer.parseInt(s);
                    if (value >= 1 && value <= 10000) {
                        ModConfig.friendlyFirePenalty.set(value);
                        saveConfig();
                    }
                } catch (NumberFormatException e) {
                    // 忽略无效输入
                }
            }
        });
        currentY += LINE_SPACING * 2;
        
        // 行动攻防机制配置部分
        addSectionTitle(currentY, operationSettingsLabel);
        currentY += LINE_SPACING;
        
        // 启用行动攻防机制
        addLabel(currentY, enableOperationModeLabel);
        this.enableOperationModeButton = createBooleanCycleButton(rightColumnX, currentY, ModConfig.enableOperationMode);
        currentY += LINE_SPACING;
        
        // 兵力不足阈值
        addLabel(currentY, lowReinforcementThresholdLabel);
        this.lowReinforcementThresholdInput = createDoubleEditBox(rightColumnX, currentY, ModConfig.lowReinforcementThreshold.get());
        this.lowReinforcementThresholdInput.setResponder(s -> {
            if (!s.isEmpty()) {
                try {
                    double value = Double.parseDouble(s);
                    if (value >= 0.0 && value <= 100.0) {
                        ModConfig.lowReinforcementThreshold.set(value);
                        saveConfig();
                    }
                } catch (NumberFormatException e) {
                    // 忽略无效输入
                }
            }
        });
        currentY += LINE_SPACING * 2;
        
        // 完成按钮
        this.addRenderableWidget(Button.builder(
                CommonComponents.GUI_DONE, 
                (button) -> this.onClose())
                .bounds(centerX - 100, currentY, 200, WIDGET_HEIGHT)
                .build());
    }
    
    private void addSectionTitle(int y, Component title) {
        // 部分标题在render方法中绘制
    }
    
    private void addLabel(int y, Component label) {
        // 标签在render方法中绘制
    }
    
    private CycleButton<Boolean> createBooleanCycleButton(int x, int y, ForgeConfigSpec.BooleanValue configValue) {
        CycleButton<Boolean> button = CycleButton.<Boolean>builder(value -> Component.literal(value ? "是" : "否"))
                .withValues(true, false)
                .withInitialValue(configValue.get())
                .create(x, y, 100, WIDGET_HEIGHT, Component.empty(), (button1, value) -> {
                    configValue.set(value);
                    saveConfig();
                });
        this.addRenderableWidget(button);
        return button;
    }
    
    private EditBox createIntegerEditBox(int x, int y, int value) {
        EditBox editBox = new EditBox(this.font, x, y, 100, WIDGET_HEIGHT, Component.empty());
        editBox.setValue(String.valueOf(value));
        editBox.setFilter(s -> s.matches("\\d*"));
        this.addRenderableWidget(editBox);
        return editBox;
    }
    
    private EditBox createDoubleEditBox(int x, int y, double value) {
        EditBox editBox = new EditBox(this.font, x, y, 100, WIDGET_HEIGHT, Component.empty());
        editBox.setValue(String.valueOf(value));
        editBox.setFilter(s -> s.matches("\\d*(\\.\\d*)?"));
        this.addRenderableWidget(editBox);
        return editBox;
    }
    
    private void saveConfig() {
        ModConfig.SPEC.save();
    }
    
    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(guiGraphics);
        super.render(guiGraphics, mouseX, mouseY, partialTick);
        
        int centerX = this.width / 2;
        int leftColumnX = centerX - 200;
        int rightColumnX = centerX + 50;
        int startY = 40;
        int currentY = startY - scrollOffset;
        
        // 绘制标题
        guiGraphics.drawCenteredString(this.font, TITLE, centerX, 15, 0xFFFFFF);
        
        // HUD配置部分
        guiGraphics.drawCenteredString(this.font, hudSettingsLabel, centerX, currentY + LABEL_TOP_PADDING, 0xFFFFFF);
        currentY += LINE_SPACING;
        
        // 启用HUD显示
        guiGraphics.drawString(this.font, enableHUDLabel, leftColumnX, currentY + LABEL_TOP_PADDING, 0xCCCCCC);
        currentY += LINE_SPACING;
        
        // 启用据点信息轮播
        guiGraphics.drawString(this.font, enableCarouselLabel, leftColumnX, currentY + LABEL_TOP_PADDING, 0xCCCCCC);
        currentY += LINE_SPACING * 2;
        
        // 队伍配置部分
        guiGraphics.drawCenteredString(this.font, teamSettingsLabel, centerX, currentY + LABEL_TOP_PADDING, 0xFFFFFF);
        currentY += LINE_SPACING;
        
        // 启用队伍机制
        guiGraphics.drawString(this.font, enableTeamsLabel, leftColumnX, currentY + LABEL_TOP_PADDING, 0xCCCCCC);
        currentY += LINE_SPACING * 2;
        
        // 性能配置部分
        guiGraphics.drawCenteredString(this.font, performanceSettingsLabel, centerX, currentY + LABEL_TOP_PADDING, 0xFFFFFF);
        currentY += LINE_SPACING;
        
        // 据点检查间隔
        guiGraphics.drawString(this.font, checkIntervalLabel, leftColumnX, currentY + LABEL_TOP_PADDING, 0xCCCCCC);
        currentY += LINE_SPACING * 2;
        
        // 奖励配置部分
        guiGraphics.drawCenteredString(this.font, rewardSettingsLabel, centerX, currentY + LABEL_TOP_PADDING, 0xFFFFFF);
        currentY += LINE_SPACING;
        
        // 据点内获得点数的时间间隔
        guiGraphics.drawString(this.font, pointRewardIntervalLabel, leftColumnX, currentY + LABEL_TOP_PADDING, 0xCCCCCC);
        currentY += LINE_SPACING;
        
        // 据点内每次获得的点数
        guiGraphics.drawString(this.font, pointRewardAmountLabel, leftColumnX, currentY + LABEL_TOP_PADDING, 0xCCCCCC);
        currentY += LINE_SPACING;
        
        // 击杀玩家获得的点数
        guiGraphics.drawString(this.font, killRewardAmountLabel, leftColumnX, currentY + LABEL_TOP_PADDING, 0xCCCCCC);
        currentY += LINE_SPACING;
        
        // 占领据点获得的点数
        guiGraphics.drawString(this.font, captureRewardAmountLabel, leftColumnX, currentY + LABEL_TOP_PADDING, 0xCCCCCC);
        currentY += LINE_SPACING;
        
        // 占领后持续奖励间隔
        guiGraphics.drawString(this.font, capturedRewardIntervalLabel, leftColumnX, currentY + LABEL_TOP_PADDING, 0xCCCCCC);
        currentY += LINE_SPACING;
        
        // 占领后每次奖励点数
        guiGraphics.drawString(this.font, capturedRewardAmountLabel, leftColumnX, currentY + LABEL_TOP_PADDING, 0xCCCCCC);
        currentY += LINE_SPACING;
        
        // 占领后奖励延迟
        guiGraphics.drawString(this.font, capturedRewardDelayLabel, leftColumnX, currentY + LABEL_TOP_PADDING, 0xCCCCCC);
        currentY += LINE_SPACING;
        
        // 启用友军击杀惩罚
        guiGraphics.drawString(this.font, enableFriendlyFirePenaltyLabel, leftColumnX, currentY + LABEL_TOP_PADDING, 0xCCCCCC);
        currentY += LINE_SPACING;
        
        // 友军击杀扣除点数
        guiGraphics.drawString(this.font, friendlyFirePenaltyLabel, leftColumnX, currentY + LABEL_TOP_PADDING, 0xCCCCCC);
        currentY += LINE_SPACING * 2;
        
        // 行动攻防机制配置部分
        guiGraphics.drawCenteredString(this.font, operationSettingsLabel, centerX, currentY + LABEL_TOP_PADDING, 0xFFFFFF);
        currentY += LINE_SPACING;
        
        // 启用行动攻防机制
        guiGraphics.drawString(this.font, enableOperationModeLabel, leftColumnX, currentY + LABEL_TOP_PADDING, 0xCCCCCC);
        currentY += LINE_SPACING;
        
        // 兵力不足阈值
        guiGraphics.drawString(this.font, lowReinforcementThresholdLabel, leftColumnX, currentY + LABEL_TOP_PADDING, 0xCCCCCC);
        currentY += LINE_SPACING * 2;
        
        // 绘制滚动条
        int screenHeight = this.height;
        int contentHeight = startY + CONTENT_HEIGHT;
        if (contentHeight > screenHeight) {
            int scrollbarWidth = 6;
            int scrollbarX = this.width - 20;
            int scrollableHeight = screenHeight - 30;
            int scrollbarHeight = Math.max(20, (int) ((double) scrollableHeight / contentHeight * scrollableHeight));
            int scrollbarY = 30 + (int) ((double) scrollOffset / (contentHeight - scrollableHeight) * (scrollableHeight - scrollbarHeight));
            
            // 滚动条背景
            guiGraphics.fill(scrollbarX, 30, scrollbarX + scrollbarWidth, screenHeight - 30, 0x44444444);
            // 滚动条滑块
            guiGraphics.fill(scrollbarX, scrollbarY, scrollbarX + scrollbarWidth, scrollbarY + scrollbarHeight, 0x88888888);
        }
    }
    
    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        int maxScroll = Math.max(0, CONTENT_HEIGHT - (this.height - 80));
        scrollOffset = Math.max(0, Math.min(maxScroll, scrollOffset - (int) (delta * SCROLL_SPEED)));
        this.clearWidgets();
        this.init();
        return true;
    }
    
    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        int scrollbarWidth = 6;
        int scrollbarX = this.width - 20;
        if (mouseX >= scrollbarX && mouseX <= scrollbarX + scrollbarWidth && 
            mouseY >= 30 && mouseY <= this.height - 30) {
            isScrolling = true;
            updateScrollFromMouse((int) mouseY);
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }
    
    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        isScrolling = false;
        return super.mouseReleased(mouseX, mouseY, button);
    }
    
    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (isScrolling) {
            updateScrollFromMouse((int) mouseY);
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }
    
    private void updateScrollFromMouse(int mouseY) {
        int scrollableHeight = this.height - 30;
        int contentHeight = CONTENT_HEIGHT;
        int scrollbarHeight = Math.max(20, (int) ((double) scrollableHeight / contentHeight * scrollableHeight));
        double scrollPosition = (mouseY - 30 - scrollbarHeight / 2) / (scrollableHeight - scrollbarHeight);
        int maxScroll = Math.max(0, contentHeight - scrollableHeight);
        scrollOffset = (int) (scrollPosition * maxScroll);
        scrollOffset = Math.max(0, Math.min(maxScroll, scrollOffset));
        this.clearWidgets();
        this.init();
    }
    
    @Override
    public void onClose() {
        this.minecraft.setScreen(this.parentScreen);
    }
    
    @Override
    public boolean isPauseScreen() {
        return false;
    }
}