package org.espetro.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import org.espetro.team.FactionDataLoader;
import org.espetro.team.FactionDataProvider;
import se.mickelus.mutil.gui.GuiElement;

import java.util.Arrays;

/**
 * 阵营选择界面
 * 玩家选择自己的阵营编制
 */
public class FactionSelectionScreen extends MutilScreen {

    private FactionDataLoader.FactionData[] factions = new FactionDataLoader.FactionData[0];
    private int scrollOffset = 0;
    private int maxScrollOffset = 0;

    public FactionSelectionScreen() {
        super(Component.literal("选择阵营"));
    }

    @Override
    protected void init() {
        loadFactions();
        super.init();
    }

    private void loadFactions() {
        FactionDataLoader loader = FactionDataProvider.getOrCreateLoader();
        loader.ensureLoaded(Minecraft.getInstance().getResourceManager());
        factions = Arrays.stream(loader.getFactionArray())
            .filter(f -> f != null && f.name != null && !f.name.contains("空降兵团"))
            .toArray(FactionDataLoader.FactionData[]::new);
    }

    @Override
    protected void renderBeforeMutil(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        EspetroMutilWidgets.drawScreenShade(graphics, this.width, this.height);
    }

    @Override
    protected void buildMutilRoot(GuiElement root) {
        int panelW = Math.min(520, Math.max(240, this.width - 28));
        int columns = this.width < 420 ? 1 : this.width < 680 ? 2 : 3;
        int gap = 4;
        int cardW = (panelW - 24 - (columns - 1) * gap) / columns;
        int cardH = 13;
        int rows = Math.max(1, (factions.length + columns - 1) / columns);
        int panelH = Math.min(this.height - 28, 58 + rows * cardH + Math.max(0, rows - 1) * gap + 24);
        int panelX = (this.width - panelW) / 2;
        int panelY = Math.max(14, (this.height - panelH) / 2);

        root.addChild(EspetroMutilWidgets.panel(panelX, panelY, panelW, panelH, 0x00000000, 0x00000000));
        root.addChild(EspetroMutilWidgets.centeredText(panelX, panelY + 6, panelW,
            "\u00a76\u00a7l战术小队 - 选择阵营", EspetroMutilWidgets.TEXT));
        root.addChild(EspetroMutilWidgets.centeredText(panelX, panelY + 22, panelW,
            "\u00a77选择一个阵营编制加入战斗", EspetroMutilWidgets.MUTED));
        root.addChild(EspetroMutilWidgets.centeredText(panelX, panelY + 36, panelW,
            "\u00a7e当前可用阵营: " + factions.length + " 个", EspetroMutilWidgets.GOLD));
        root.addChild(EspetroMutilWidgets.rect(panelX + 12, panelY + 51, panelW - 24, 1, 0x25FFFFFF));

        int startX = panelX + 12;
        int startY = panelY + 60;
        int visibleRows = Math.max(1, (panelH - 86) / (cardH + gap));
        int visibleCount = visibleRows * columns;
        maxScrollOffset = Math.max(0, factions.length - visibleCount);
        scrollOffset = Math.min(scrollOffset, maxScrollOffset);
        int maxVisible = Math.min(factions.length, scrollOffset + visibleCount);

        for (int i = scrollOffset; i < maxVisible; i++) {
            FactionDataLoader.FactionData faction = factions[i];
            int localIndex = i - scrollOffset;
            int col = localIndex % columns;
            int row = localIndex / columns;
            int x = startX + col * (cardW + gap);
            int y = startY + row * (cardH + gap);

            String icon = faction.icon == null ? "" : faction.icon + " ";
            String name = faction.name == null ? faction.id : faction.name;
            String label = "\u00a7f" + icon + name;
            var button = EspetroMutilWidgets.button(x, y, cardW, cardH, label, () -> selectFaction(faction.id))
                .setColors(0x00000000, 0x202C3544, 0x303A3020)
                .setBorderColor(0x00000000);
            root.addChild(button);
        }

        if (maxScrollOffset > 0) {
            root.addChild(EspetroMutilWidgets.centeredText(panelX, panelY + panelH - 19, panelW,
                "\u00a78鼠标滚轮切换列表  " + (scrollOffset + 1) + "-" + maxVisible + "/" + factions.length,
                EspetroMutilWidgets.DIM));
        }

        int backW = EspetroMutilWidgets.textButtonWidth("\u00a7c返回");
        root.addChild(EspetroMutilWidgets.button(panelX + panelW / 2 - backW / 2, panelY + panelH - 16, backW, 13,
            "\u00a7c返回", this::onClose)
            .setColors(0x00000000, 0x20251515, 0x30251515)
            .setBorderColor(0x00000000));
    }

    private void selectFaction(String factionId) {
        if (factionId != null && !factionId.isEmpty()) {
            org.espetro.network.NetworkManager.requestClassSelection(factionId);
        }
    }

    @Override
    public boolean isPauseScreen() {
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
        if (shouldCloseOnEsc()) {
            super.onClose();
        }
    }

    @Override
    public boolean shouldCloseOnEsc() {
        org.espetro.team.GamePhase phase = ClientGameState.getCurrentPhase();
        return !phase.isMatchActive() || phase == org.espetro.team.GamePhase.BATTLE;
    }

    public static void open() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen == null) {
            mc.setScreen(new FactionSelectionScreen());
        }
    }

    /**
     * 直接打开指定阵营的职业选择界面（通过服务端获取数据）
     */
    public static void openWithFaction(String factionId) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen == null) {
            org.espetro.network.NetworkManager.requestClassSelection(factionId);
        }
    }
}
