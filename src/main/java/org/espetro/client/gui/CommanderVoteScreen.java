package org.espetro.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.espetro.network.NetworkManager;

import java.util.*;

/**
 * 指挥官投票界面
 * 仅显示玩家所在队伍的成员，不允许关闭，限时30秒
 * 5×6网格布局
 */
public class CommanderVoteScreen extends Screen {

    private final String team; // "ATTACK" 或 "DEFEND"
    private final List<String> players;
    private int timeRemaining;
    
    private Map<String, Integer> voteCounts = new HashMap<>();
    private String currentVote = null;

    // 网格布局参数
    private static final int COLUMNS = 5;
    private static final int ROWS = 6;
    private static final int BUTTON_WIDTH = 70;
    private static final int BUTTON_HEIGHT = 18;
    private static final int H_SPACING = 6;
    private static final int V_SPACING = 4;

    public CommanderVoteScreen(String team, List<String> players, int timeRemaining) {
        super(Component.literal("指挥官投票"));
        this.team = team;
        this.players = players;
        this.timeRemaining = timeRemaining;
    }

    public static void open(String team, List<String> players, int timeRemaining) {
        Minecraft mc = Minecraft.getInstance();
        mc.setScreen(new CommanderVoteScreen(team, players, timeRemaining));
    }

    public static void updateVoteData(Map<String, Integer> voteCounts, int timeRemaining) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof CommanderVoteScreen screen) {
            screen.voteCounts = voteCounts;
            screen.timeRemaining = timeRemaining;
        }
    }

    @Override
    protected void init() {
        super.init();
        createPlayerButtons();
    }

    private void createPlayerButtons() {
        this.clearWidgets();
        
        // 计算网格总宽度，居中
        int totalWidth = COLUMNS * BUTTON_WIDTH + (COLUMNS - 1) * H_SPACING;
        int startX = (this.width - totalWidth) / 2;
        int startY = 72;

        for (int i = 0; i < players.size(); i++) {
            String playerName = players.get(i);
            int votes = voteCounts.getOrDefault(playerName, 0);
            boolean isVoted = playerName.equals(currentVote);

            int col = i % COLUMNS;
            int row = i / COLUMNS;
            int x = startX + col * (BUTTON_WIDTH + H_SPACING);
            int y = startY + row * (BUTTON_HEIGHT + V_SPACING);

            // 按钮文字：名字 + 票数
            String voteText = votes > 0 ? " §e" + votes : "";
            String prefix = isVoted ? "§a✓ " : "§f";
            Component buttonText = Component.literal(prefix + playerName + voteText);

            this.addRenderableWidget(new TransparentTextButton(x, y, BUTTON_WIDTH, BUTTON_HEIGHT, buttonText, () -> {
                if (!playerName.equals(Minecraft.getInstance().player.getName().getString())) {
                    if (!playerName.equals(currentVote)) {
                        currentVote = playerName;
                        NetworkManager.sendCastVote(playerName);
                        createPlayerButtons();
                    }
                }
            }));
        }
        
        // 当前投票提示
        if (currentVote != null) {
            String teamColor = "ATTACK".equals(team) ? "§c" : "§9";
            int centerX = this.width / 2;
            this.addRenderableWidget(Button.builder(
                Component.literal(teamColor + "当前投票: §a" + currentVote),
                btn -> {}
            ).bounds(centerX - 90, this.height - 35, 180, 22).build());
        }
    }

    /**
     * 透明背景的文字按钮
     */
    private static class TransparentTextButton extends AbstractWidget {
        private final Runnable onClick;

        public TransparentTextButton(int x, int y, int width, int height, Component message, Runnable onClick) {
            super(x, y, width, height, message);
            this.onClick = onClick;
        }

        @Override
        protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            boolean hovered = mouseX >= this.getX() && mouseX <= this.getX() + this.getWidth()
                && mouseY >= this.getY() && mouseY <= this.getY() + this.getHeight();

            if (hovered) {
                // hover 时显示半透明背景
                graphics.fill(this.getX(), this.getY(),
                    this.getX() + this.getWidth(), this.getY() + this.getHeight(),
                    0x40FFFFFF);
            }

            graphics.drawCenteredString(Minecraft.getInstance().font, getMessage(),
                this.getX() + this.getWidth() / 2,
                this.getY() + (this.getHeight() - 8) / 2,
                hovered ? 0xFFFFAA : 0xFFFFFF);
        }

        @Override
        public void onClick(double mouseX, double mouseY) {
            this.onClick.run();
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {}
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(graphics);
        
        int centerX = this.width / 2;
        
        // 标题
        String teamName = "ATTACK".equals(team) ? "§c§l进攻方" : "§9§l防守方";
        graphics.drawCenteredString(this.font, 
            Component.literal("§6§l指挥官投票 §7| " + teamName), centerX, 12, 0xFFFFFF);
        
        // 倒计时
        String timeColor = timeRemaining <= 10 ? "§c" : "§e";
        graphics.drawCenteredString(this.font, 
            Component.literal(timeColor + "剩余时间: " + timeRemaining + "秒"), centerX, 30, 0xFFFFFF);
        
        // 提示
        graphics.drawCenteredString(this.font, 
            Component.literal("§7点击玩家名字投票（不可投给自己）"), centerX, 50, 0x888888);
        
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
    
    @Override
    public void onClose() {
        // 不允许关闭 - 重新打开界面
        Minecraft mc = Minecraft.getInstance();
        if (mc != null) {
            mc.setScreen(new CommanderVoteScreen(team, players, timeRemaining));
        }
    }
}
