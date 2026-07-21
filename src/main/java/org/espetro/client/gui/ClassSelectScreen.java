package org.espetro.client.gui;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.espetro.network.ClassSelectScreenPacket;
import org.espetro.network.NetworkManager;
import se.mickelus.mutil.gui.GuiElement;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

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
    private int scrollStep = 1;
    private EspetroMutilWidgets.PhaseHeader phaseHeader;
    private final Map<String, FactionCardButton> factionCards = new HashMap<>();

    public ClassSelectScreen(String team, boolean isCommander, List<ClassSelectScreenPacket.FactionInfo> factions,
                              int timeRemaining, String opponentTeamName, String opponentFaction,
                              int opponentTimeRemaining, String selectedFactionId) {
        super(Component.literal("编制选择"));
        this.team = team;
        this.isCommander = isCommander;
        this.factions = factions == null ? new ArrayList<>() : new ArrayList<>(factions);
        this.timeRemaining = timeRemaining;
        this.opponentTeamName = opponentTeamName;
        this.opponentFaction = opponentFaction;
        this.opponentTimeRemaining = opponentTimeRemaining;
        this.lastSelectedFaction = selectedFactionId == null || selectedFactionId.isEmpty()
            ? null : selectedFactionId;
    }

    @Override
    protected void buildMutilRoot(GuiElement root) {
        factionCards.clear();
        int count = factions == null ? 0 : factions.size();
        // 编制候选池固定为六个：始终按 Squad 风格的 3 列 × 2 行展示。
        int columns = 3;
        int visibleRows = 2;
        int cardGap = 6;
        int panelW = Math.min(this.width - 16,
            24 + columns * 240 + (columns - 1) * cardGap);
        int cardW = (panelW - 24 - (columns - 1) * cardGap) / columns;
        int startY = EspetroMutilWidgets.PHASE_HEADER_HEIGHT + 8;
        int availableH = Math.max(2 * 67 + cardGap, this.height - startY - 18);
        int maxCardH = Math.max(67, (availableH - cardGap) / visibleRows);
        int imageH = Math.max(36, Math.min(
            Math.min(140, Math.round(cardW * 270.0f / 512.0f)), maxCardH - 31));
        int cardH = imageH + 31;
        int panelX = (this.width - panelW) / 2;

        boolean selectingOpen = timeRemaining > 0;
        String teamPrefix = EspetroMutilWidgets.teamPrefix(team);
        phaseHeader = EspetroMutilWidgets.addMutablePhaseHeader(root, this.width,
            "\u00a76\u00a7l编制投票 \u00a77| " + teamPrefix + "\u00a7l"
                + EspetroMutilWidgets.teamName(team) + " \u00a77[全员投票]",
            buildTimeText(), buildOpponentText(), EspetroMutilWidgets.teamColor(team));
        int headerH = EspetroMutilWidgets.PHASE_HEADER_HEIGHT;

        if (count == 0) {
            root.addChild(EspetroMutilWidgets.centeredText(panelX, headerH + 18, panelW,
                "\u00a7c没有可选编制", EspetroMutilWidgets.NEGATIVE));
            return;
        }

        int startX = panelX + 12;
        int visibleCount = Math.max(columns, visibleRows * columns);
        maxScrollOffset = Math.max(0,
            ((count - visibleCount + columns - 1) / columns) * columns);
        scrollOffset = Math.min(scrollOffset, maxScrollOffset);
        scrollOffset -= scrollOffset % columns;
        scrollStep = columns;
        int maxVisible = Math.min(count, scrollOffset + visibleCount);

        for (int i = scrollOffset; i < maxVisible; i++) {
            ClassSelectScreenPacket.FactionInfo faction = factions.get(i);
            int localIndex = i - scrollOffset;
            int col = localIndex % columns;
            int row = localIndex / columns;
            int x = startX + col * (cardW + cardGap);
            int y = startY + row * (cardH + cardGap);

            boolean selected = faction.id != null && faction.id.equals(lastSelectedFaction);
            FactionCardButton card = new FactionCardButton(x, y, cardW, cardH, imageH, faction,
                selectingOpen, selected, () -> selectFaction(faction.id));
            root.addChild(card);
            factionCards.put(faction.id, card);
        }

        if (maxScrollOffset > 0) {
            root.addChild(EspetroMutilWidgets.centeredText(panelX,
                Math.max(headerH, this.height - 10), panelW,
                "\u00a78滚轮浏览  " + (scrollOffset + 1) + "-" + maxVisible + "/" + count,
                EspetroMutilWidgets.DIM));
        }
    }

    private static class FactionCardButton extends GuiElement {
        private ClassSelectScreenPacket.FactionInfo faction;
        private final int imageHeight;
        private boolean enabled;
        private boolean selected;
        private final Runnable action;
        private ResourceLocation texture;

        FactionCardButton(int x, int y, int width, int height, int imageHeight,
                          ClassSelectScreenPacket.FactionInfo faction,
                          boolean enabled, boolean selected, Runnable action) {
            super(x, y, width, height);
            this.faction = faction;
            this.imageHeight = imageHeight;
            this.enabled = enabled;
            this.selected = selected;
            this.action = action;
            this.texture = resolveTexture(faction.selectionImage);
        }

        void update(ClassSelectScreenPacket.FactionInfo faction, boolean enabled, boolean selected) {
            if (!Objects.equals(this.faction.selectionImage, faction.selectionImage)) {
                this.texture = resolveTexture(faction.selectionImage);
            }
            this.faction = faction;
            this.enabled = enabled;
            this.selected = selected;
        }

        @Override
        public boolean onMouseClick(int mouseX, int mouseY, int button) {
            if (button != 0 || !enabled || !isVisible() || !hasFocus()) return false;
            if (action != null) action.run();
            return true;
        }

        @Override
        public void draw(GuiGraphics graphics, int x, int y, int width, int height,
                         int mouseX, int mouseY, float partialTick) {
            if (!isVisible()) return;
            int bx = x + getX();
            int by = y + getY();
            int bw = getWidth();
            int bh = getHeight();

            int fill = !enabled ? 0xA0121517 : selected ? 0xE02E3529
                : hasFocus() ? 0xE0273038 : 0xD0191D20;
            int border = selected ? 0xFFE8B85C
                : hasFocus() && enabled ? 0xFFC2C8D5 : 0x805B6260;
            graphics.fill(bx, by, bx + bw, by + bh, fill);
            graphics.renderOutline(bx, by, bw, bh, border);

            int imageX = bx + 3;
            int imageY = by + 3;
            int imageW = Math.max(1, bw - 6);
            int imageH = Math.max(1, imageHeight - 6);
            graphics.fill(imageX, imageY, imageX + imageW, imageY + imageH, 0xD0101214);

            if (texture != null) {
                RenderSystem.enableBlend();
                RenderSystem.setShaderColor(1f, 1f, 1f, enabled ? 1f : 0.55f);
                // UV 宽高与虚拟纹理宽高相同，始终完整采样整张纹理；
                // 源文件分辨率无需预先获知，并统一拉伸/缩放到固定卡片图片区。
                graphics.blit(texture, imageX, imageY, imageW, imageH,
                    0f, 0f, imageW, imageH, imageW, imageH);
                RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
                RenderSystem.disableBlend();
            }

            String prefix = selected ? "\u00a7a✓ " : enabled ? "\u00a7f" : "\u00a78";
            String label = prefix + faction.name + " \u00a7e[" + faction.voteCount + "]";
            String drawn = EspetroMutilWidgets.trimToWidth(label, Math.max(8, bw - 8));
            int labelW = Minecraft.getInstance().font.width(
                EspetroMutilWidgets.stripFormatting(drawn));
            graphics.drawString(Minecraft.getInstance().font, Component.literal(drawn),
                bx + Math.max(4, (bw - labelW) / 2), by + imageHeight + 2,
                enabled ? EspetroMutilWidgets.TEXT : EspetroMutilWidgets.DIM, false);

            if (texture == null) {
                String missing = "\u00a77还没配置图片喵";
                int missingW = Minecraft.getInstance().font.width(
                    EspetroMutilWidgets.stripFormatting(missing));
                graphics.drawString(Minecraft.getInstance().font, Component.literal(missing),
                    bx + Math.max(4, (bw - missingW) / 2), by + imageHeight + 14,
                    EspetroMutilWidgets.DIM, false);
            }
            super.draw(graphics, x, y, width, height, mouseX, mouseY, partialTick);
        }

        private static ResourceLocation resolveTexture(String value) {
            if (value == null || value.isBlank()) return null;
            ResourceLocation location = ResourceLocation.tryParse(value);
            if (location == null) return null;
            return Minecraft.getInstance().getResourceManager().getResource(location).isPresent()
                ? location
                : null;
        }
    }

    private void selectFaction(String factionId) {
        if (timeRemaining <= 0 || factionId == null || factionId.isEmpty()) {
            return;
        }

        NetworkManager.sendClassSelect("", factionId);
        lastSelectedFaction = factionId;
        refreshDynamicElements();
    }

    private boolean isWaitingForOwnSelection() {
        // 当前流程固定守方先选编制、攻方后选编制；攻方收到对方倒计时表示本方尚未开始。
        return "ATTACK".equals(team) && timeRemaining <= 0 && opponentTimeRemaining > 0;
    }

    public void updateFromPacket(ClassSelectScreenPacket packet) {
        List<ClassSelectScreenPacket.FactionInfo> nextFactions = packet.getFactions() == null
            ? new ArrayList<>() : new ArrayList<>(packet.getFactions());
        boolean layoutChanged = !Objects.equals(this.team, packet.getTeam())
            || !hasSameFactionLayout(this.factions, nextFactions);
        this.team = packet.getTeam();
        this.isCommander = packet.isCommander();
        this.factions = nextFactions;
        this.timeRemaining = packet.getTimeRemaining();
        this.opponentTeamName = packet.getOpponentTeamName();
        this.opponentFaction = packet.getOpponentFaction();
        this.opponentTimeRemaining = packet.getOpponentTimeRemaining();
        this.lastSelectedFaction = packet.getSelectedFactionId().isEmpty()
            ? null : packet.getSelectedFactionId();
        if (this.root != null) {
            if (layoutChanged) {
                rebuildMutilRoot();
            } else {
                refreshDynamicElements();
            }
        }
    }

    private void refreshDynamicElements() {
        if (phaseHeader != null) {
            phaseHeader.setTitle("\u00a76\u00a7l编制投票 \u00a77| "
                + EspetroMutilWidgets.teamPrefix(team) + "\u00a7l"
                + EspetroMutilWidgets.teamName(team) + " \u00a77[全员投票]");
            phaseHeader.setStatus(buildTimeText());
            phaseHeader.setDetail(buildOpponentText());
        }
        boolean enabled = timeRemaining > 0;
        for (ClassSelectScreenPacket.FactionInfo faction : factions) {
            FactionCardButton card = factionCards.get(faction.id);
            if (card != null) {
                card.update(faction, enabled, Objects.equals(faction.id, lastSelectedFaction));
            }
        }
    }

    private String buildTimeText() {
        if (timeRemaining > 0) {
            return (timeRemaining <= 5 ? "\u00a7c" : "\u00a76")
                + "剩余时间: " + timeRemaining + "秒";
        }
        return isWaitingForOwnSelection()
            ? "\u00a77本方编制投票尚未开始"
            : "\u00a77本方编制已确定，等待对方选择编制";
    }

    private String buildOpponentText() {
        if (opponentTeamName == null || opponentTeamName.isEmpty()) {
            return "";
        }
        String text;
        if (opponentFaction != null && !opponentFaction.isEmpty()) {
            text = ("ATTACK".equals(team) ? "\u00a79" : "\u00a7c")
                + opponentTeamName + " 编制: " + opponentFaction;
        } else {
            text = "\u00a77" + opponentTeamName + " 编制尚未确定";
        }
        if (opponentTimeRemaining >= 0) {
            text += (opponentTimeRemaining <= 5 ? " \u00a7c· " : " \u00a76· ")
                + "剩余 " + opponentTimeRemaining + "秒";
        }
        return text;
    }

    private static boolean hasSameFactionLayout(List<ClassSelectScreenPacket.FactionInfo> current,
                                                List<ClassSelectScreenPacket.FactionInfo> updated) {
        if (current == null || current.size() != updated.size()) {
            return false;
        }
        for (int i = 0; i < current.size(); i++) {
            ClassSelectScreenPacket.FactionInfo left = current.get(i);
            ClassSelectScreenPacket.FactionInfo right = updated.get(i);
            if (!Objects.equals(left.id, right.id)
                    || !Objects.equals(left.name, right.name)
                    || !Objects.equals(left.selectionImage, right.selectionImage)) {
                return false;
            }
        }
        return true;
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (maxScrollOffset > 0) {
            int nextOffset = scrollOffset + (delta < 0 ? scrollStep : -scrollStep);
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
