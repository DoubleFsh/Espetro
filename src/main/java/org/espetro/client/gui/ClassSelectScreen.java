package org.espetro.client.gui;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.espetro.network.ClassSelectScreenPacket;
import org.espetro.network.NetworkManager;
import org.espetro.client.aui.GuiElement;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
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

    /** 最底部一行同时放名称与票数，图片区尽量放大。 */
    private static final int CARD_FOOTER_PAD = 2;
    private static final int CARD_NAME_LINE = 11;
    private static final int CARD_FOOTER_H = CARD_FOOTER_PAD + CARD_NAME_LINE;
    /** 约 12 逻辑像素观感的小字（相对默认字号缩小）。 */
    private static final float CARD_NAME_SCALE = 0.85f;

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
    protected void renderBeforeMutil(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        CurrentMapBackgroundRenderer.render(
            graphics, this.width, this.height, ClientGameState.getCurrentMapFolder());
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
            24 + columns * 360 + (columns - 1) * cardGap);
        int cardW = (panelW - 24 - (columns - 1) * cardGap) / columns;
        int startY = EspetroMutilWidgets.PHASE_HEADER_HEIGHT + 8;
        int availableH = Math.max(2 * 67 + cardGap, this.height - startY - 18);
        int maxCardH = Math.max(67, (availableH - cardGap) / visibleRows);
        // 图片画框固定 16:9 横向：先按卡片宽计算高度，放不下时再按高度反推宽度
        int imageW = Math.max(1, cardW - 6);
        int imageH = Math.max(1, imageW * 9 / 16);
        int maxImageSlotH = Math.max(36, maxCardH - CARD_FOOTER_H);
        int imageSlotH = imageH + 6;
        if (imageSlotH > maxImageSlotH) {
            imageSlotH = maxImageSlotH;
            imageH = Math.max(1, imageSlotH - 6);
            imageW = Math.max(1, imageH * 16 / 9);
        }
        int cardH = imageSlotH + CARD_FOOTER_H;
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
            FactionCardButton card = new FactionCardButton(x, y, cardW, cardH, imageSlotH, faction,
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
        private record FactionTexture(ResourceLocation location, int width, int height) {
        }

        private ClassSelectScreenPacket.FactionInfo faction;
        private final int imageHeight;
        private boolean enabled;
        private boolean selected;
        private final Runnable action;
        private FactionTexture texture;

        FactionCardButton(int x, int y, int width, int height, int imageHeight,
                          ClassSelectScreenPacket.FactionInfo faction,
                          boolean enabled, boolean selected, Runnable action) {
            super(x, y, width, height);
            this.faction = faction;
            this.imageHeight = imageHeight;
            this.enabled = enabled;
            this.selected = selected;
            this.action = action;
            this.texture = resolveTexture(faction.selectionImage, faction.imageData);
        }

        void update(ClassSelectScreenPacket.FactionInfo faction, boolean enabled, boolean selected) {
            if (!Objects.equals(this.faction.selectionImage, faction.selectionImage)
                    || !java.util.Arrays.equals(this.faction.imageData, faction.imageData)) {
                this.texture = resolveTexture(faction.selectionImage, faction.imageData);
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

            // 透明卡片：选中/hover 用细边框与极低 alpha，无大面积面板底。
            int border = selected ? 0xFFE8B85C
                : hasFocus() && enabled ? 0xFFC2C8D5 : 0x805B6260;
            if (selected) {
                graphics.fill(bx, by, bx + bw, by + bh, 0x302E3529);
            } else if (hasFocus() && enabled) {
                graphics.fill(bx, by, bx + bw, by + bh, 0x20273038);
            }
            graphics.renderOutline(bx, by, bw, bh, border);

            int imageBoxY = by + 3;
            int imageBoxW = Math.max(1, bw - 6);
            int imageSlotH = Math.max(1, imageHeight - 6);
            AspectFit.Size fitted = texture == null
                ? AspectFit.within(16, 9, imageBoxW, imageSlotH)
                : AspectFit.within(texture.width(), texture.height(), imageBoxW, imageSlotH);
            int imageX = bx + Math.max(0, (bw - fitted.width()) / 2);
            int imageY = imageBoxY + Math.max(0, (imageSlotH - fitted.height()) / 2);

            if (texture != null) {
                graphics.setColor(1f, 1f, 1f, enabled ? 1f : 0.55f);
                graphics.blit(texture.location(), imageX, imageY, fitted.width(), fitted.height(),
                    0f, 0f, texture.width(), texture.height(), texture.width(), texture.height());
                graphics.setColor(1f, 1f, 1f, 1f);
            } else {
                String missing = "§7还没配置图片喵";
                int missingW = Minecraft.getInstance().font.width(
                    EspetroMutilWidgets.stripFormatting(missing));
                graphics.drawString(Minecraft.getInstance().font, Component.literal(missing),
                    bx + Math.max(4, (bw - missingW) / 2),
                    imageBoxY + Math.max(8, imageSlotH / 2),
                    EspetroMutilWidgets.DIM, false);
            }

            // 票数：外框右下角，只显示数字
            String voteText = String.valueOf(faction.voteCount);
            int voteW = Minecraft.getInstance().font.width(voteText);
            int voteX = bx + bw - 4 - voteW;
            int voteY = by + bh - 10;
            graphics.drawString(Minecraft.getInstance().font, Component.literal(voteText),
                voteX, voteY, enabled ? 0xFFE8B85C : EspetroMutilWidgets.DIM, false);

            // 名称：固定放在外框最底下一行，与右下角票数同一行
            String nameOnly = faction.name == null ? "" : faction.name;
            int nameMaxW = Math.max(8,
                (int) ((bw - 8 - voteW) / CARD_NAME_SCALE));
            String drawnName = EspetroMutilWidgets.trimToWidth(
                (selected ? "§a✔ " : "") + nameOnly, nameMaxW);
            int nameColor = enabled ? EspetroMutilWidgets.TEXT : EspetroMutilWidgets.DIM;
            int nameX = bx + 4;
            int nameY = by + bh - 10;
            EspetroMutilWidgets.drawScaledString(graphics, drawnName,
                nameX, nameY, nameColor, CARD_NAME_SCALE);

            super.draw(graphics, x, y, width, height, mouseX, mouseY, partialTick);
        }

        private static final Map<String, FactionTexture> DISK_TEXTURE_CACHE = new HashMap<>();
        private static final Map<String, Boolean> DISK_TEXTURE_FAILED = new HashMap<>();
        /** 缓存服务端发来的图片字节创建的纹理，key 为 selectionImage */
        private static final Map<String, FactionTexture> SERVER_IMAGE_CACHE = new HashMap<>();
        /** 资源包纹理的尺寸只读取一次，避免界面重建时重复解码 PNG。 */
        private static final Map<ResourceLocation, FactionTexture> RESOURCE_TEXTURE_CACHE = new HashMap<>();

        private static FactionTexture resolveTexture(String value, byte[] imageData) {
            if (value == null || value.isBlank()) return null;
            // 1. 服务端发来了图片字节 → 直接动态注册
            if (imageData != null && imageData.length > 0) {
                return resolveServerImage(value, imageData);
            }
            // 2. 尝试 ResourceLocation 格式 (espetro:textures/gui/factions/xxx.png)
            ResourceLocation location = ResourceLocation.tryParse(value);
            if (location != null && Minecraft.getInstance().getResourceManager()
                    .getResource(location).isPresent()) {
                return resolveResourceTexture(location);
            }
            // 3. 尝试从本地 EsFactions/ 目录加载（单机/局域网备用）
            return resolveEsFactionsTexture(value);
        }

        private static FactionTexture resolveServerImage(String key, byte[] imageData) {
            FactionTexture cached = SERVER_IMAGE_CACHE.get(key);
            if (cached != null) return cached;
            try {
                NativeImage image = NativeImage.read(
                    new java.io.ByteArrayInputStream(imageData));
                int width = image.getWidth();
                int height = image.getHeight();
                DynamicTexture texture = new DynamicTexture(image);
                String safe = Integer.toHexString(key.hashCode());
                ResourceLocation id = ResourceLocation.fromNamespaceAndPath(
                    "espetro", "dynamic/srv_faction_" + safe);
                Minecraft.getInstance().getTextureManager().register(id, texture);
                FactionTexture resolved = new FactionTexture(id, width, height);
                SERVER_IMAGE_CACHE.put(key, resolved);
                return resolved;
            } catch (Exception e) {
                return null;
            }
        }

        private static FactionTexture resolveResourceTexture(ResourceLocation location) {
            FactionTexture cached = RESOURCE_TEXTURE_CACHE.get(location);
            if (cached != null) return cached;
            try {
                var resource = Minecraft.getInstance().getResourceManager().getResource(location);
                if (resource.isEmpty()) return null;
                try (InputStream in = resource.get().open(); NativeImage image = NativeImage.read(in)) {
                    FactionTexture resolved = new FactionTexture(
                        location, image.getWidth(), image.getHeight());
                    RESOURCE_TEXTURE_CACHE.put(location, resolved);
                    return resolved;
                }
            } catch (Exception e) {
                return null;
            }
        }

        private static FactionTexture resolveEsFactionsTexture(String fileName) {
            String key = fileName.trim();
            if (DISK_TEXTURE_FAILED.containsKey(key)) return null;
            FactionTexture cached = DISK_TEXTURE_CACHE.get(key);
            if (cached != null) return cached;
            // 按服务端规则读取本地 EsFactions/ 目录；尝试游戏目录、当前工作目录、
            // 开发 run 目录和上一级目录，保证与服务端存放位置一致。
            Path gameDir = Minecraft.getInstance().gameDirectory.toPath();
            List<Path> candidateRoots = new ArrayList<>();
            candidateRoots.add(gameDir.resolve("EsFactions").normalize());
            candidateRoots.add(Path.of("EsFactions").toAbsolutePath().normalize());
            candidateRoots.add(Path.of("run", "EsFactions").toAbsolutePath().normalize());
            candidateRoots.add(Path.of("..", "EsFactions").toAbsolutePath().normalize());
            for (Path esFactionsDir : candidateRoots) {
                try {
                    Path imagePath = esFactionsDir.resolve(key).normalize();
                    // 安全检查：确保路径仍在对应 EsFactions 目录下
                    if (!imagePath.startsWith(esFactionsDir.normalize())) {
                        continue;
                    }
                    if (!Files.isRegularFile(imagePath)) {
                        continue;
                    }
                    try (InputStream in = Files.newInputStream(imagePath)) {
                        NativeImage image = NativeImage.read(in);
                        int width = image.getWidth();
                        int height = image.getHeight();
                        DynamicTexture texture = new DynamicTexture(image);
                        String safe = Integer.toHexString(key.hashCode());
                        ResourceLocation id = ResourceLocation.fromNamespaceAndPath(
                            "espetro", "dynamic/faction_" + safe);
                        Minecraft.getInstance().getTextureManager().register(id, texture);
                        FactionTexture resolved = new FactionTexture(id, width, height);
                        DISK_TEXTURE_CACHE.put(key, resolved);
                        return resolved;
                    }
                } catch (Exception ignored) {
                    // 尝试下一个候选目录
                }
            }
            DISK_TEXTURE_FAILED.put(key, true);
            return null;
        }
    }

    private void selectFaction(String factionId) {
        if (tutorialPreviewMode || timeRemaining <= 0 || factionId == null || factionId.isEmpty()) {
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

    /** 轻量倒计时包：只改时间/选中态，绝不 rebuild。 */
    public void updateTimer(int timeRemaining, int opponentTimeRemaining,
                            String selectedFactionId, boolean isCommander) {
        this.timeRemaining = timeRemaining;
        this.opponentTimeRemaining = opponentTimeRemaining;
        this.isCommander = isCommander;
        this.lastSelectedFaction = selectedFactionId == null || selectedFactionId.isEmpty()
            ? null : selectedFactionId;
        if (root != null) {
            refreshDynamicElements();
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
    public void onClose() {
        // 编制界面由服务端阶段推进后替换，玩家不能手动跳过选择流程。
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
