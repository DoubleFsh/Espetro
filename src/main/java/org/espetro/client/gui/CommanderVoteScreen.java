package org.espetro.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.espetro.network.NetworkManager;
import se.mickelus.mutil.gui.GuiElement;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 指挥官投票界面
 */
public class CommanderVoteScreen extends MutilScreen {

    private final String team;
    private final List<String> players;
    private int timeRemaining;
    private String opponentTeamName;
    private String opponentFaction;
    private int opponentTimeRemaining;

    private Map<String, Integer> voteCounts = new HashMap<>();
    private String currentVote = null;
    private int scrollOffset = 0;
    private int maxScrollOffset = 0;

    public CommanderVoteScreen(String team, List<String> players, int timeRemaining,
                                String opponentTeamName, String opponentFaction,
                                int opponentTimeRemaining) {
        super(Component.literal("指挥官投票"));
        this.team = team;
        this.players = players;
        this.timeRemaining = timeRemaining;
        this.opponentTeamName = opponentTeamName;
        this.opponentFaction = opponentFaction;
        this.opponentTimeRemaining = opponentTimeRemaining;
    }

    public static void open(String team, List<String> players, int timeRemaining,
                            String opponentTeamName, String opponentFaction,
                            int opponentTimeRemaining) {
        Minecraft mc = Minecraft.getInstance();
        mc.setScreen(new CommanderVoteScreen(team, players, timeRemaining,
            opponentTeamName, opponentFaction, opponentTimeRemaining));
    }

    public static void updateVoteData(Map<String, Integer> voteCounts, int timeRemaining, int opponentTimeRemaining) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof CommanderVoteScreen screen) {
            screen.voteCounts = voteCounts == null ? new HashMap<>() : new HashMap<>(voteCounts);
            screen.timeRemaining = timeRemaining;
            screen.opponentTimeRemaining = opponentTimeRemaining;
            screen.rebuildMutilRoot();
        }
    }

    /** 固定显示60个候选人名字 */
    private static final int VISIBLE_NAME_COUNT = 60;

    @Override
    protected void buildMutilRoot(GuiElement root) {
        int playerCount = players == null ? 0 : players.size();

        int columns = 4;
        int gap = this.height < 320 ? 2 : 4;
        int cardH = this.height < 320 ? 12 : 14;
        int targetRows = VISIBLE_NAME_COUNT / columns;
        int headerH = 70;
        int footerH = 24;

        // 面板宽度：每列最小150像素，确保名字和票数有足够空间
        int minCardW = 150;
        int minPanelW = 24 + columns * minCardW + (columns - 1) * gap;
        int panelW = Math.min(this.width - 16, Math.max(minPanelW, this.width - 28));
        int cardW = (panelW - 24 - (columns - 1) * gap) / columns;

        int requestedPanelH = headerH + targetRows * cardH + Math.max(0, targetRows - 1) * gap + footerH;
        int panelH = Math.min(this.height - 12, requestedPanelH);
        int panelX = (this.width - panelW) / 2;
        int panelY = Math.max(6, (this.height - panelH) / 2);

        root.addChild(EspetroMutilWidgets.panel(panelX, panelY, panelW, panelH, 0x00000000, 0x00000000));

        String teamPrefix = EspetroMutilWidgets.teamPrefix(team);
        root.addChild(EspetroMutilWidgets.centeredText(panelX, panelY + 6, panelW,
            "\u00a76\u00a7l指挥官投票 \u00a77| " + teamPrefix + "\u00a7l" + EspetroMutilWidgets.teamName(team),
            EspetroMutilWidgets.TEXT));

        boolean votingOpen = timeRemaining > 0;
        boolean waitingForOwnVote = isWaitingForOwnVote();
        int timeColor = timeRemaining <= 10 ? EspetroMutilWidgets.NEGATIVE : EspetroMutilWidgets.GOLD;
        String timeText = votingOpen
            ? "剩余时间: " + timeRemaining + "秒"
            : waitingForOwnVote
            ? "\u00a77本方指挥官投票尚未开始"
            : "\u00a77本方指挥官投票已结束";
        root.addChild(EspetroMutilWidgets.centeredText(panelX, panelY + 22, panelW,
            timeText, votingOpen ? timeColor : EspetroMutilWidgets.MUTED));

        // 对手投票倒计时 + 已知编制信息
        int infoY = 36;
        if (opponentTeamName != null && !opponentTeamName.isEmpty() && opponentTimeRemaining >= 0) {
            String oppPrefix = "ATTACK".equals(team) ? "\u00a79" : "\u00a7c";
            int oppTimeColor = opponentTimeRemaining <= 5 ? EspetroMutilWidgets.NEGATIVE : EspetroMutilWidgets.GOLD;
            root.addChild(EspetroMutilWidgets.centeredText(panelX, panelY + infoY, panelW,
                oppPrefix + "\u00a7l" + opponentTeamName + " 指挥官投票剩余: " + opponentTimeRemaining + "秒",
                oppTimeColor));
            infoY += 12;
        }

        if (opponentTeamName != null && !opponentTeamName.isEmpty()
            && opponentFaction != null && !opponentFaction.isEmpty()) {
            String oppPrefix = "ATTACK".equals(team) ? "\u00a79" : "\u00a7c";
            root.addChild(EspetroMutilWidgets.centeredText(panelX, panelY + infoY, panelW,
                oppPrefix + "\u00a7l" + opponentTeamName + " 编制: " + opponentFaction,
                EspetroMutilWidgets.TEXT));
        }
        root.addChild(EspetroMutilWidgets.rect(panelX + 12, panelY + 63, panelW - 24, 1, 0x25FFFFFF));

        if (playerCount == 0) {
            root.addChild(EspetroMutilWidgets.centeredText(panelX, panelY + 76, panelW,
                "\u00a7c当前队伍没有可投票玩家", EspetroMutilWidgets.NEGATIVE));
            return;
        }

        String selfName = Minecraft.getInstance().player == null ? "" : Minecraft.getInstance().player.getName().getString();
        int startX = panelX + 12;
        int startY = panelY + headerH;

        // 计算可见范围：优先固定显示4列×15行=60人，小屏幕再自动减少行数并滚动。
        int listH = Math.max(cardH, panelH - headerH - footerH);
        int usableRows = Math.max(1, Math.min(targetRows, (listH + gap) / (cardH + gap)));
        int visibleCount = Math.min(VISIBLE_NAME_COUNT, usableRows * columns);
        maxScrollOffset = Math.max(0, playerCount - visibleCount);
        scrollOffset = Math.min(scrollOffset, maxScrollOffset);
        int maxVisible = Math.min(playerCount, scrollOffset + visibleCount);

        for (int i = scrollOffset; i < maxVisible; i++) {
            String playerName = players.get(i);
            int votes = voteCounts.getOrDefault(playerName, 0);
            boolean isSelf = playerName.equals(selfName);
            boolean isSelected = playerName.equals(currentVote);

            int localIndex = i - scrollOffset;
            int col = localIndex % columns;
            int row = localIndex / columns;
            int x = startX + col * (cardW + gap);
            int y = startY + row * (cardH + gap);

            String prefix = isSelected ? "\u00a7a\u2713 " : isSelf ? "\u00a78" : "\u00a7f";
            // 名字旁边显示实时投票数：票数>0黄色，=0灰色
            String label = prefix + playerName + " \u00a7e[" + votes + "]";

            var button = EspetroMutilWidgets.button(x, y, cardW, cardH, label, () -> voteFor(playerName))
                .setEnabled(votingOpen && !isSelf)
                .setSelected(isSelected)
                .setColors(0x00000000, 0x202D3444, 0x30243A27)
                .setBorderColor(0x00000000);
            if (isSelf) {
                button.setTextColor(EspetroMutilWidgets.DIM);
            }
            root.addChild(button);
        }

        if (maxScrollOffset > 0) {
            root.addChild(EspetroMutilWidgets.centeredText(panelX, panelY + panelH - 26, panelW,
                "\u00a78鼠标滚轮切换列表  " + (scrollOffset + 1) + "-" + maxVisible + "/" + playerCount,
                EspetroMutilWidgets.DIM));
        }

        String voteText = waitingForOwnVote
            ? "\u00a78等待本方指挥官投票开始"
            : !votingOpen
            ? "\u00a78等待对方完成指挥官投票"
            : currentVote == null
            ? "\u00a78尚未投票"
            : teamPrefix + "当前投票: \u00a7a" + currentVote;
        root.addChild(EspetroMutilWidgets.centeredText(panelX, panelY + panelH - 13, panelW,
            voteText, EspetroMutilWidgets.TEXT));
    }

    private void voteFor(String playerName) {
        if (playerName == null || timeRemaining <= 0) {
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null && playerName.equals(mc.player.getName().getString())) {
            return;
        }

        if (!playerName.equals(currentVote)) {
            currentVote = playerName;
            NetworkManager.sendCastVote(playerName);
            rebuildMutilRoot();
        }
    }

    private boolean isWaitingForOwnVote() {
        // 当前流程固定守方先投票、攻方后投票；攻方收到对方倒计时表示本方尚未开始。
        return "ATTACK".equals(team) && timeRemaining <= 0 && opponentTimeRemaining > 0;
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (maxScrollOffset > 0) {
            int nextOffset = scrollOffset + (delta < 0 ? 1 : -1);
            nextOffset = Math.max(0, Math.min(maxScrollOffset, nextOffset));
            if (nextOffset != scrollOffset) {
                scrollOffset = nextOffset;
                rebuildMutilRoot();
                return true;
            }
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }

    @Override
    public void onClose() {
        Minecraft mc = Minecraft.getInstance();
        if (mc != null) {
            mc.setScreen(new CommanderVoteScreen(team, players, timeRemaining,
                opponentTeamName, opponentFaction, opponentTimeRemaining));
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
