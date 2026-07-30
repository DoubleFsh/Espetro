package org.espetro.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import org.espetro.network.MapVoteStatePacket;
import org.espetro.network.NetworkManager;
import se.mickelus.mutil.gui.GuiElement;

import java.util.ArrayList;
import java.util.List;

/** Forced global map-vote screen with up to six candidates. */
public final class MapVoteScreen extends MutilScreen {
    private static MapVoteStatePacket latest = new MapVoteStatePacket(
        false, 0, 0L, List.of(), java.util.Map.of(), null, null, null);

    private final List<MapCardButton> mapButtons = new ArrayList<>();
    private long receivedAtMs;
    private int receivedRemaining;
    private EspetroMutilWidgets.PhaseHeader phaseHeader;
    /** 上次写入顶栏的显示秒，避免每 tick 改字符串加重闪烁感。 */
    private int lastStatusSecond = Integer.MIN_VALUE;

    public MapVoteScreen() {
        super(Component.literal("地图投票"));
        receivedAtMs = System.currentTimeMillis();
        receivedRemaining = latest.remainingSeconds;
    }

    public static void update(MapVoteStatePacket packet) {
        latest = packet;
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof MapVoteScreen screen) {
            boolean structureChanged = screen.mapButtons.size() != packet.candidates.size();
            screen.receivedAtMs = System.currentTimeMillis();
            screen.receivedRemaining = packet.remainingSeconds;
            if (structureChanged) screen.rebuildMutilRoot();
            else screen.refreshLabels();
        }
    }

    @Override
    protected void renderBeforeMutil(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        EspetroMutilWidgets.drawScreenShade(graphics, this.width, this.height);
    }

    @Override
    protected void buildMutilRoot(GuiElement root) {
        mapButtons.clear();
        phaseHeader = EspetroMutilWidgets.addMutablePhaseHeader(root, width,
            "§6§l全局地图投票", buildResultText(computeDisplaySeconds()),
            "§7全服统一计票，票数最高的地图胜出",
            EspetroMutilWidgets.GOLD);
        int columns = 3;
        int visibleRows = 2;
        int gap = 6;
        int panelW = Math.min(width - 16, 24 + columns * 240 + (columns - 1) * gap);
        int cardW = (panelW - 24 - (columns - 1) * gap) / columns;
        int availableH = Math.max(96, height - 70);
        int cardH = Math.max(46, Math.min(96, (availableH - gap) / visibleRows));
        int startX = (width - panelW) / 2 + 12;
        int startY = 52;
        for (int i = 0; i < latest.candidates.size(); i++) {
            MapVoteStatePacket.Candidate candidate = latest.candidates.get(i);
            int col = i % columns;
            int row = i / columns;
            var button = new MapCardButton(
                startX + col * (cardW + gap), startY + row * (cardH + gap),
                cardW, cardH, candidate,
                () -> {
                    if (tutorialPreviewMode) {
                        return;
                    }
                    NetworkManager.sendMapVoteCast(candidate.mapFolder());
                });
            mapButtons.add(button);
            root.addChild(button);
        }
        refreshLabels();
    }

    private void refreshLabels() {
        for (int i = 0; i < mapButtons.size() && i < latest.candidates.size(); i++) {
            var candidate = latest.candidates.get(i);
            int votes = latest.tally.getOrDefault(candidate.mapFolder(), 0);
            boolean selected = candidate.mapFolder().equals(latest.myVoteMapFolder);
            mapButtons.get(i).update(candidate, votes, selected, latest.active);
        }
        updateStatusHeader(true);
    }

    private int computeDisplaySeconds() {
        int elapsed = (int) ((System.currentTimeMillis() - receivedAtMs) / 1000L);
        return Math.max(0, receivedRemaining - elapsed);
    }

    private String buildResultText(int remaining) {
        return latest.active ? "§e剩余 " + remaining + " 秒"
            : "§a胜出地图：" + (latest.winnerDisplayName == null ? "随机" : latest.winnerDisplayName);
    }

    private void updateStatusHeader(boolean force) {
        if (phaseHeader == null) return;
        int remaining = computeDisplaySeconds();
        if (!force && remaining == lastStatusSecond) return;
        lastStatusSecond = remaining;
        phaseHeader.setStatus(buildResultText(remaining));
    }

    @Override
    public void tick() {
        super.tick();
        updateStatusHeader(false);
    }

    @Override
    public void onClose() {
        if (!latest.active) super.onClose();
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return !latest.active;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /** Same three-by-two card language as formation voting, without requiring an image asset. */
    private static final class MapCardButton extends GuiElement {
        private MapVoteStatePacket.Candidate candidate;
        private int votes;
        private boolean selected;
        private boolean enabled;
        private final Runnable action;

        private MapCardButton(int x, int y, int width, int height,
                              MapVoteStatePacket.Candidate candidate, Runnable action) {
            super(x, y, width, height);
            this.candidate = candidate;
            this.action = action;
        }

        private void update(MapVoteStatePacket.Candidate candidate, int votes,
                            boolean selected, boolean enabled) {
            this.candidate = candidate;
            this.votes = votes;
            this.selected = selected;
            this.enabled = enabled;
        }

        @Override
        public boolean onMouseClick(int mouseX, int mouseY, int button) {
            if (button != 0 || !enabled || !isVisible() || !hasFocus()) {
                return false;
            }
            if (action != null) {
                action.run();
            }
            return true;
        }

        @Override
        public void draw(GuiGraphics graphics, int x, int y, int width, int height,
                         int mouseX, int mouseY, float partialTick) {
            if (!isVisible()) {
                return;
            }
            int bx = x + getX();
            int by = y + getY();
            int bw = getWidth();
            int bh = getHeight();
            // 透明卡片：仅细边框 + 选中/hover 描边，无大面积暗底与网格装饰。
            int border = selected ? 0xFFE8B85C
                : hasFocus() && enabled ? 0xFFC2C8D5 : 0x805B6260;
            if (selected) {
                graphics.fill(bx, by, bx + bw, by + bh, 0x302E3529);
            } else if (hasFocus() && enabled) {
                graphics.fill(bx, by, bx + bw, by + bh, 0x20273038);
            }
            graphics.renderOutline(bx, by, bw, bh, border);

            int previewBottom = by + Math.max(24, bh - 26);
            String mapName = EspetroMutilWidgets.trimToWidth(
                (selected ? "§a✔ " : "§f") + candidate.displayName(), Math.max(20, bw - 10));
            graphics.drawCenteredString(Minecraft.getInstance().font,
                Component.literal(mapName), bx + bw / 2, by + Math.max(8, (previewBottom - by) / 2 - 4),
                EspetroMutilWidgets.TEXT);
            String footer = "§7票数 §e" + votes;
            graphics.drawCenteredString(Minecraft.getInstance().font,
                Component.literal(footer), bx + bw / 2, by + bh - 17,
                enabled ? EspetroMutilWidgets.TEXT : EspetroMutilWidgets.DIM);
            super.draw(graphics, x, y, width, height, mouseX, mouseY, partialTick);
        }
    }
}
