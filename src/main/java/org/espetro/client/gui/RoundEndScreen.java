package org.espetro.client.gui;

import net.minecraft.network.chat.Component;
import se.mickelus.mutil.gui.GuiElement;

/** Short result screen shown before battlefield cleanup. */
public final class RoundEndScreen extends MutilScreen {
    private final String winner;
    private final long closesAt;
    private EspetroMutilWidgets.Text countdownText;

    public RoundEndScreen(String winner, int seconds) {
        super(Component.literal("回合结束"));
        this.winner = winner;
        this.closesAt = System.currentTimeMillis() + Math.max(1, seconds) * 1000L;
    }

    @Override
    protected void buildMutilRoot(GuiElement root) {
        String result = "ATTACK".equals(winner) ? "§c进攻方胜利"
            : "DEFEND".equals(winner) ? "§9防守方胜利" : "§e平局";
        int panelW = Math.min(260, width - 24);
        int x = (width - panelW) / 2;
        int y = height / 2 - 45;
        root.addChild(EspetroMutilWidgets.panel(x, y, panelW, 90));
        root.addChild(EspetroMutilWidgets.centeredText(x + 8, y + 13, panelW - 16,
            "§6§l回合结束", EspetroMutilWidgets.GOLD));
        root.addChild(EspetroMutilWidgets.centeredText(x + 8, y + 36, panelW - 16,
            result, EspetroMutilWidgets.TEXT));
        countdownText = EspetroMutilWidgets.centeredText(x + 8, y + 59, panelW - 16,
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
    public boolean isPauseScreen() {
        return false;
    }
}
