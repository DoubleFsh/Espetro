package org.espetro.client.gui;

import net.minecraft.network.chat.Component;
import org.espetro.network.ClassSelectScreenPacket;
import org.espetro.network.NetworkManager;
import se.mickelus.mutil.gui.GuiElement;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 编制选择界面
 * 当前阵营全体玩家投票选择队伍编制。
 */
public class ClassSelectScreen extends MutilScreen {

    private String team;
    private boolean isCommander;
    private List<ClassSelectScreenPacket.FactionInfo> factions;
    private int timeRemaining;
    private String opponentTeamName;
    private String opponentFaction;
    private int opponentTimeRemaining;

    private String lastSelectedFaction = null;
    private int scrollOffset = 0;
    private int maxScrollOffset = 0;
    private final Map<String, EspetroMutilWidgets.ActionButton> factionButtons = new HashMap<>();

    public ClassSelectScreen(String team, boolean isCommander, List<ClassSelectScreenPacket.FactionInfo> factions,
                              int timeRemaining, String opponentTeamName, String opponentFaction,
                              int opponentTimeRemaining, String selectedFactionId) {
        super(Component.literal("编制选择"));
        this.team = team;
        this.isCommander = isCommander;
        this.factions = factions;
        this.timeRemaining = timeRemaining;
        this.opponentTeamName = opponentTeamName;
        this.opponentFaction = opponentFaction;
        this.opponentTimeRemaining = opponentTimeRemaining;
        this.lastSelectedFaction = selectedFactionId == null || selectedFactionId.isEmpty()
            ? null : selectedFactionId;
    }

    @Override
    protected void buildMutilRoot(GuiElement root) {
        factionButtons.clear();

        // 固定3列2行布局，共6个编制
        int columns = 3;
        int cardGap = 6;
        int cardH = 16;
        int cardW = 150;
        int count = factions == null ? 0 : factions.size();

        // 面板宽度：3列卡片 + 间距 + 边距
        int panelW = Math.min(this.width - 16, 24 + columns * cardW + (columns - 1) * cardGap);
        // 重新计算cardW以填满面板
        cardW = (panelW - 24 - (columns - 1) * cardGap) / columns;
        // 固定2行（加对手编制行+倒计时行空间）
        int rows = 2;
        int panelH = 90 + rows * cardH + (rows - 1) * cardGap + 18;
        int panelX = (this.width - panelW) / 2;
        int panelY = Math.max(13, (this.height - panelH) / 2);

        root.addChild(EspetroMutilWidgets.panel(panelX, panelY, panelW, panelH, 0x00000000, 0x00000000));

        String teamPrefix = EspetroMutilWidgets.teamPrefix(team);
        root.addChild(EspetroMutilWidgets.centeredText(panelX, panelY + 6, panelW,
            teamPrefix + "\u00a7l" + EspetroMutilWidgets.teamName(team) + " 编制投票 \u00a77[全员投票]",
            EspetroMutilWidgets.TEXT));

        boolean selectingOpen = timeRemaining > 0;
        String prompt = !selectingOpen
            ? "\u00a77本方编制已确定，等待对方选择编制"
            : "\u00a7e点击投票，可随时改票，票数实时同步";
        root.addChild(EspetroMutilWidgets.centeredText(panelX, panelY + 22, panelW,
            prompt, EspetroMutilWidgets.MUTED));

        int timeColor = timeRemaining <= 5 ? EspetroMutilWidgets.NEGATIVE : EspetroMutilWidgets.GOLD;
        String timeText = selectingOpen ? "剩余时间: " + timeRemaining + "秒" : "\u00a77本方选择已结束";
        root.addChild(EspetroMutilWidgets.centeredText(panelX, panelY + 36, panelW,
            timeText, selectingOpen ? timeColor : EspetroMutilWidgets.MUTED));

        // 对手编制信息 + 对手倒计时
        if (opponentTeamName != null && !opponentTeamName.isEmpty()) {
            String oppPrefix = "ATTACK".equals(team) ? "\u00a79" : "\u00a7c";
            if (opponentFaction != null && !opponentFaction.isEmpty()) {
                root.addChild(EspetroMutilWidgets.centeredText(panelX, panelY + 50, panelW,
                    oppPrefix + "\u00a7l" + opponentTeamName + " 编制: " + opponentFaction,
                    EspetroMutilWidgets.TEXT));
            } else {
                root.addChild(EspetroMutilWidgets.centeredText(panelX, panelY + 50, panelW,
                    "\u00a77" + opponentTeamName + " 编制尚未确定", EspetroMutilWidgets.MUTED));
            }
            // 对手倒计时
            if (opponentTimeRemaining >= 0) {
                int oppTimeColor = opponentTimeRemaining <= 5 ? EspetroMutilWidgets.NEGATIVE : EspetroMutilWidgets.GOLD;
                root.addChild(EspetroMutilWidgets.centeredText(panelX, panelY + 62, panelW,
                    opponentTeamName + "选择编制剩余: " + opponentTimeRemaining + "秒", oppTimeColor));
            }
        }
        root.addChild(EspetroMutilWidgets.rect(panelX + 12, panelY + 77, panelW - 24, 1, 0x25FFFFFF));

        int badgeW = 72;
        root.addChild(EspetroMutilWidgets.centeredText(panelX + panelW - badgeW - 10, panelY + 80, badgeW,
            teamPrefix + EspetroMutilWidgets.teamName(team), EspetroMutilWidgets.TEXT));

        if (count == 0) {
            root.addChild(EspetroMutilWidgets.centeredText(panelX, panelY + 90, panelW,
                "\u00a7c没有可选编制", EspetroMutilWidgets.NEGATIVE));
            return;
        }

        int startX = panelX + 12;
        int startY = panelY + 90;
        // 固定显示3列2行=6个，不需要滚动
        int visibleCount = Math.min(count, columns * rows);
        maxScrollOffset = 0;
        scrollOffset = 0;
        int maxVisible = Math.min(count, visibleCount);

        for (int i = 0; i < maxVisible; i++) {
            ClassSelectScreenPacket.FactionInfo faction = factions.get(i);
            int col = i % columns;
            int row = i / columns;
            int x = startX + col * (cardW + cardGap);
            int y = startY + row * (cardH + cardGap);

            boolean selected = faction.id != null && faction.id.equals(lastSelectedFaction);
            String prefix = selected ? "\u00a7a✓ " : "\u00a7f";
            String label = prefix + faction.name + " \u00a7e[" + faction.voteCount + "]";

            var button = EspetroMutilWidgets.button(x, y, cardW, cardH, label, () -> selectFaction(faction.id))
                .setEnabled(selectingOpen)
                .setSelected(selected)
                .setColors(0x00000000, 0x202C3544, 0x303B3020)
                .setBorderColor(0x00000000);

            if (!selectingOpen) {
                button.setTextColor(EspetroMutilWidgets.DIM);
            }

            root.addChild(button);
            if (faction.id != null) {
                factionButtons.put(faction.id, button);
            }
        }
    }

    private void selectFaction(String factionId) {
        if (timeRemaining <= 0 || factionId == null || factionId.isEmpty()) {
            return;
        }

        NetworkManager.sendClassSelect("", factionId);
        lastSelectedFaction = factionId;
        rebuildMutilRoot();
    }

    public void updateFromPacket(ClassSelectScreenPacket packet) {
        this.team = packet.getTeam();
        this.isCommander = packet.isCommander();
        this.factions = packet.getFactions();
        this.timeRemaining = packet.getTimeRemaining();
        this.opponentTeamName = packet.getOpponentTeamName();
        this.opponentFaction = packet.getOpponentFaction();
        this.opponentTimeRemaining = packet.getOpponentTimeRemaining();
        this.lastSelectedFaction = packet.getSelectedFactionId().isEmpty()
            ? null : packet.getSelectedFactionId();
        if (this.root != null) {
            rebuildMutilRoot();
        }
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
    public boolean isPauseScreen() {
        return false;
    }
}
