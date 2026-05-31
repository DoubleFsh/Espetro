package org.espetro.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.espetro.team.TeamManager;

/**
 * 攻防方选择界面
 */
public class TeamSelectionScreen extends Screen {

    public TeamSelectionScreen() {
        super(Component.literal("选择队伍"));
    }

    @Override
    protected void init() {
        super.init();

        int centerX = this.width / 2;
        int centerY = this.height / 2;
        int buttonWidth = 220;
        int buttonHeight = 70;

        // 进攻队伍按钮
        this.addRenderableWidget(Button.builder(
            Component.literal("§c⚔ 进攻方 ⚔")
                .append("\n§7点击加入进攻方"),
            (btn) -> TeamSelectionGui.selectTeam("ATTACK")
        ).bounds(centerX - buttonWidth / 2, centerY - 100, buttonWidth, buttonHeight)
        .build());

        // 防守队伍按钮
        this.addRenderableWidget(Button.builder(
            Component.literal("§9🛡 防守方 🛡")
                .append("\n§7点击加入防守方"),
            (btn) -> TeamSelectionGui.selectTeam("DEFEND")
        ).bounds(centerX - buttonWidth / 2, centerY + 30, buttonWidth, buttonHeight)
        .build());
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(graphics);
        
        // 标题
        graphics.drawCenteredString(this.font, Component.literal("§6§l选择你的阵营"), 
            this.width / 2, 30, 0xFFFFFF);
        
        // 副标题
        graphics.drawCenteredString(this.font, Component.literal("§7选择加入 §c进攻方 §7或 §9防守方"), 
            this.width / 2, 50, 0xAAAAAA);

        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
    
    public static void open() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen == null) {
            mc.setScreen(new TeamSelectionScreen());
        }
    }
}
