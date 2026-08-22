package org.espetro.client.gui;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import org.espetro.client.aui.GuiElement;

/**
 * 战局结算界面。
 * <p>
 * 上方：超大结算等级文字 + 小字 "XXX赢得了胜利"<br>
 * 下方：左/右双方阵营名 + 剩余票数
 */
public final class RoundEndScreen extends MutilScreen {

    // ── 结算等级文案 ──
    private static final String[] WIN_LABELS  = {"平局", "惨烈胜利", "险胜", "决定性胜利", "重大胜利", "完胜"};
    private static final String[] LOSE_LABELS = {"平局", "功亏一篑", "险败", "决定性战败", "重大战败", "完败"};
    private static final int[] LEVEL_COLORS = {
        0xFFFFD700, // 0 平局 金
        0xFFFFAA00, // 1 惨烈 橙
        0xFFFF8800, // 2 险    橙红
        0xFFFF6600, // 3 决定性 深橙
        0xFFFF3300, // 4 重大 红
        0xFFFF0000  // 5 完胜 纯红
    };

    private final String winner;       // "ATTACK" / "DEFEND" / "DRAW"
    private final long closesAt;
    /** 赢方 show_name，平局时为 null */
    private final String winnerShowName;
    /** 输方 show_name，平局时为 null */
    private final String loserShowName;
    private final int attackTickets;
    private final int defendTickets;
    private final int resultLevel;     // 5→0
    private final boolean attackerTimeout;
    private EspetroMutilWidgets.Text countdownText;

    public RoundEndScreen(String winner, int displaySeconds,
                          String winnerShowName, String loserShowName,
                          int attackTickets, int defendTickets,
                          int resultLevel, boolean attackerTimeout) {
        super(Component.literal("回合结束"));
        this.winner = winner == null ? "DRAW" : winner;
        this.closesAt = System.currentTimeMillis() + Math.max(1, displaySeconds) * 1000L;
        this.winnerShowName = winnerShowName;
        this.loserShowName = loserShowName;
        this.attackTickets = attackTickets;
        this.defendTickets = defendTickets;
        this.resultLevel = Math.max(0, Math.min(5, resultLevel));
        this.attackerTimeout = attackerTimeout;
    }

    @Override
    protected void buildMutilRoot(GuiElement root) {
        // 倒计时文字
        countdownText = EspetroMutilWidgets.centeredText(
            0, height - 28, width,
            countdownLabel(), EspetroMutilWidgets.MUTED);
        root.addChild(countdownText);
    }

    private String countdownLabel() {
        long seconds = Math.max(0, (closesAt - System.currentTimeMillis() + 999) / 1000);
        return "§7" + seconds + " 秒后返回主城";
    }

    @Override
    public void tick() {
        super.tick();
        if (countdownText != null) countdownText.setText(countdownLabel());
    }

    @Override
    public boolean isPauseScreen() { return false; }

    @Override
    public boolean shouldCloseOnEsc() { return false; }

    @Override
    protected void renderBeforeMutil(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        CurrentMapBackgroundRenderer.render(
            graphics, width, height, ClientGameState.getCurrentMapFolder());
    }

    @Override
    protected void renderAfterMutil(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        Minecraft mc = Minecraft.getInstance();
        String myTeam = ClientGameState.getPlayerTeam();
        boolean isDraw = "DRAW".equals(winner);
        boolean iWon = !isDraw && winner.equals(myTeam);
        int level = resultLevel;

        // ── 1. 超大等级文字（居中上方）──
        String levelText = isDraw ? "平局" : (iWon ? WIN_LABELS[level] : LOSE_LABELS[level]);
        int levelColor = isDraw ? LEVEL_COLORS[0] : (iWon ? LEVEL_COLORS[level] : 0xFFFF3333);
        float levelScale = 3.5f;
        PoseStack pose = g.pose();
        pose.pushPose();
        pose.translate(width / 2f, height * 0.22f, 0);
        pose.scale(levelScale, levelScale, 1f);
        int lw = mc.font.width(levelText);
        g.drawString(mc.font, Component.literal(levelText), -lw / 2, 0, levelColor);
        pose.popPose();

        // ── 2. "XXX赢得了胜利"（小字）──
        if (!isDraw) {
            String victorName = winnerShowName != null ? winnerShowName : (iWon ? "我方" : "敌方");
            String subText = victorName + "赢得了胜利";
            float subScale = 1.2f;
            pose.pushPose();
            pose.translate(width / 2f, height * 0.32f, 0);
            pose.scale(subScale, subScale, 1f);
            int sw = mc.font.width(subText);
            g.drawString(mc.font, Component.literal(subText), -sw / 2, 0,
                iWon ? 0xFFFFFFAA : 0xFFFFAAAA);
            pose.popPose();
        }

        // ── 3. 下半区：双方阵营名 + 票数 ──
        // 确定玩家视角下的左/右双方
        boolean isAttacker = "ATTACK".equals(myTeam);
        boolean isDefender = "DEFEND".equals(myTeam);

        String leftFaction, rightFaction;
        int leftTickets, rightTickets;

        if (!isDraw && myTeam != null) {
            if (iWon) {
                // 我赢了：左=赢得方，右=输方
                leftFaction = winnerShowName != null ? winnerShowName : "我方";
                rightFaction = loserShowName != null ? loserShowName : "敌方";
                if (isAttacker) { leftTickets = attackTickets; rightTickets = defendTickets; }
                else            { leftTickets = defendTickets; rightTickets = attackTickets; }
            } else {
                // 我输了：左=我方，右=敌方（敌方是赢方）
                leftFaction = loserShowName != null ? loserShowName : "我方";
                rightFaction = winnerShowName != null ? winnerShowName : "敌方";
                if (isAttacker) { leftTickets = attackTickets; rightTickets = defendTickets; }
                else            { leftTickets = defendTickets; rightTickets = attackTickets; }
            }
        } else {
            // 平局或无队伍：按攻/守固定展示
            leftFaction = winnerShowName != null ? winnerShowName : "进攻方";
            rightFaction = loserShowName != null ? loserShowName : "防守方";
            leftTickets = attackTickets;
            rightTickets = defendTickets;
        }

        // 下半区 Y 起始
        int bottomY = (int)(height * 0.48f);
        int leftX = width / 4;
        int rightX = width * 3 / 4;

        // 阵营名（中号 1.8x）
        float nameScale = 1.8f;
        drawScaledCentered(g, mc.font, leftFaction, leftX, bottomY, nameScale, 0xFFFFFFFF);
        drawScaledCentered(g, mc.font, rightFaction, rightX, bottomY, nameScale, 0xFFFFFFFF);

        // 票数（中号 2.5x，在阵营名下方）
        int numberY = bottomY + 32;
        float numScale = 2.5f;
        drawScaledCentered(g, mc.font, String.valueOf(leftTickets), leftX, numberY, numScale, 0xFFFFFF66);
        drawScaledCentered(g, mc.font, String.valueOf(rightTickets), rightX, numberY, numScale, 0xFFFFFF66);
    }

    private static void drawScaledCentered(GuiGraphics g, net.minecraft.client.gui.Font font,
                                           String text, int centerX, int centerY,
                                           float scale, int color) {
        PoseStack pose = g.pose();
        pose.pushPose();
        pose.translate(centerX, centerY, 0);
        pose.scale(scale, scale, 1f);
        int w = font.width(text);
        g.drawString(font, Component.literal(text), -w / 2, 0, color);
        pose.popPose();
    }
}
