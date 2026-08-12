package org.espetro.client.gui;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.espetro.network.MapVoteStatePacket;
import org.espetro.network.NetworkManager;
import se.mickelus.mutil.gui.GuiElement;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Forced global map-vote screen with up to six candidates in a 2×3 grid. */
public final class MapVoteScreen extends MutilScreen {
    private static final int COLUMNS = 3;
    private static final int MAX_ROWS = 2;
    private static final int MAX_CANDIDATES = COLUMNS * MAX_ROWS;
    /** 与编制选择 ClassSelectScreen 同级的卡片间距。 */
    private static final int GAP = 4;
    private static final int CARD_PAD = 4;
    /** 最底部仅保留名称/票数一行。 */
    private static final int FOOTER_H = 14;
    private static final int SIDE_PAD = 8;
    private static final int BOTTOM_PAD = 8;
    private static final int MIN_CARD_W = 120;
    private static final int MIN_CARD_H = 90;

    private static MapVoteStatePacket latest = new MapVoteStatePacket(
        false, 0, 0L, List.of(), java.util.Map.of(), null, null, null);

    /** 缓存已从客户端 EsWorld 目录解码的地图预览纹理：mapFolder → 纹理与源尺寸。 */
    private static final Map<String, PreviewTexture> previewTextureCache = new LinkedHashMap<>();

    private record PreviewTexture(ResourceLocation location, int texW, int texH) {
    }

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
        preloadPreviewTextures();
    }

    /** 从客户端游戏根目录 EsWorld/&lt;mapFolder&gt;.png 解码并注册纹理。 */
    private static void preloadPreviewTextures() {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) return;

        for (MapVoteStatePacket.Candidate c : latest.candidates) {
            String key = c.mapFolder;
            if (previewTextureCache.containsKey(key)) continue;
            Path previewPath = MapVotePreviewResolver.resolve(mc.gameDirectory.toPath(), key);
            if (previewPath == null) continue;

            try (InputStream in = Files.newInputStream(previewPath)) {
                NativeImage image = NativeImage.read(in);
                int texW = image.getWidth();
                int texH = image.getHeight();
                DynamicTexture texture = new DynamicTexture(image);
                ResourceLocation rl = ResourceLocation.fromNamespaceAndPath(
                    "espetro", "map_preview/" + Integer.toHexString(key.hashCode()));
                mc.getTextureManager().register(rl, texture);
                previewTextureCache.put(key, new PreviewTexture(rl, texW, texH));
            } catch (IOException e) {
                // 解码失败视为无预览图
            }
        }
    }

    public static void update(MapVoteStatePacket packet) {
        latest = packet;
        preloadPreviewTextures();
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
        CurrentMapBackgroundRenderer.render(
            graphics, this.width, this.height, resolveBackgroundMapFolder());
    }

    private static String resolveBackgroundMapFolder() {
        if (latest.myVoteMapFolder != null && !latest.myVoteMapFolder.isBlank()) {
            return latest.myVoteMapFolder;
        }
        if (latest.winnerMapFolder != null && !latest.winnerMapFolder.isBlank()) {
            return latest.winnerMapFolder;
        }
        String current = ClientGameState.getCurrentMapFolder();
        if (current != null && !current.isBlank()) {
            return current;
        }
        return latest.candidates.isEmpty() ? null : latest.candidates.get(0).mapFolder;
    }

    /**
     * 与编制选择一致：3 列 × 2 行铺满标题下方整块可用区域。
     * 候选不足 6 个时仍用满格卡片尺寸，从左上起填格（不把单卡拉成整行）。
     */
    private LayoutMetrics computeLayout() {
        int headerH = EspetroMutilWidgets.PHASE_HEADER_HEIGHT;
        int startY = headerH + 8;
        // 几乎占满屏：左右/底边少量边距，卡片均分剩余宽高
        int contentW = Math.max(MIN_CARD_W * COLUMNS, this.width - SIDE_PAD * 2);
        int contentH = Math.max(MIN_CARD_H * MAX_ROWS,
            this.height - startY - BOTTOM_PAD);

        int cardW = Math.max(MIN_CARD_W, (contentW - (COLUMNS - 1) * GAP) / COLUMNS);
        int cardH = Math.max(MIN_CARD_H, (contentH - (MAX_ROWS - 1) * GAP) / MAX_ROWS);

        // 预览区：固定 16:9 横向画框，宽度优先；高度不足时按高度反推宽度
        int imgW = Math.max(1, cardW - CARD_PAD * 2);
        int maxImgH = Math.max(1, cardH - FOOTER_H - 4);
        int imgH = Math.max(1, imgW * 9 / 16);
        if (imgH > maxImgH) {
            imgH = Math.max(1, maxImgH);
            imgW = Math.max(1, imgH * 16 / 9);
        }

        int panelW = COLUMNS * cardW + (COLUMNS - 1) * GAP;
        int panelH = MAX_ROWS * cardH + (MAX_ROWS - 1) * GAP;
        int startX = (this.width - panelW) / 2;
        // 垂直：紧贴标题下，若仍有余量则略微居中
        int gridStartY = startY + Math.max(0, (contentH - panelH) / 2);
        return new LayoutMetrics(cardW, cardH, imgW, imgH, startX, gridStartY);
    }

    private record LayoutMetrics(int cardW, int cardH, int imgW, int imgH,
                                 int startX, int startY) {
    }

    @Override
    protected void buildMutilRoot(GuiElement root) {
        mapButtons.clear();
        phaseHeader = EspetroMutilWidgets.addMutablePhaseHeader(root, width,
            "§6§l全局地图投票", buildResultText(computeDisplaySeconds()),
            "§7全服统一计票，票数最高的地图胜出",
            EspetroMutilWidgets.GOLD);

        int count = Math.min(latest.candidates.size(), MAX_CANDIDATES);
        LayoutMetrics layout = computeLayout();

        for (int i = 0; i < count; i++) {
            MapVoteStatePacket.Candidate candidate = latest.candidates.get(i);
            int col = i % COLUMNS;
            int row = i / COLUMNS;
            var button = new MapCardButton(
                layout.startX + col * (layout.cardW + GAP),
                layout.startY + row * (layout.cardH + GAP),
                layout.cardW, layout.cardH,
                layout.imgW, layout.imgH,
                candidate,
                () -> {
                    if (tutorialPreviewMode) {
                        return;
                    }
                    NetworkManager.sendMapVoteCast(candidate.mapFolder);
                });
            mapButtons.add(button);
            root.addChild(button);
        }
        refreshLabels();
    }

    private void refreshLabels() {
        for (int i = 0; i < mapButtons.size() && i < latest.candidates.size(); i++) {
            var candidate = latest.candidates.get(i);
            int votes = latest.tally.getOrDefault(candidate.mapFolder, 0);
            boolean selected = candidate.mapFolder.equals(latest.myVoteMapFolder);
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

    /** 16:9 预览图卡片，显示地图截图或占位文本；尺寸由外层布局计算。 */
    private static final class MapCardButton extends GuiElement {
        private MapVoteStatePacket.Candidate candidate;
        private int votes;
        private boolean selected;
        private boolean enabled;
        private final int imgW;
        private final int imgH;
        private final Runnable action;

        private MapCardButton(int x, int y, int width, int height,
                              int imgW, int imgH,
                              MapVoteStatePacket.Candidate candidate, Runnable action) {
            super(x, y, width, height);
            this.imgW = Math.max(1, imgW);
            this.imgH = Math.max(1, imgH);
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

            // 透明卡片：仅细边框 + 选中/hover 描边
            int border = selected ? 0xFFE8B85C
                : hasFocus() && enabled ? 0xFFC2C8D5 : 0x805B6260;
            if (selected) {
                graphics.fill(bx, by, bx + bw, by + bh, 0x302E3529);
            } else if (hasFocus() && enabled) {
                graphics.fill(bx, by, bx + bw, by + bh, 0x20273038);
            }
            graphics.renderOutline(bx, by, bw, bh, border);

            // 预览区：贴卡片顶部水平居中，固定 16:9 横向，尽量放大
            int maxImgW = Math.max(1, bw - CARD_PAD * 2);
            int maxImgH = Math.max(1, bh - FOOTER_H - 4);
            int imgAreaW = Math.max(1, Math.min(imgW, maxImgW));
            int imgAreaH = Math.max(1, imgAreaW * 9 / 16);
            if (imgAreaH > maxImgH) {
                imgAreaH = maxImgH;
                imgAreaW = Math.max(1, imgAreaH * 16 / 9);
            }
            int imgX = bx + (bw - imgAreaW) / 2;
            int imgY = by + 4;

            PreviewTexture preview = previewTextureCache.get(candidate.mapFolder);
            if (preview != null) {
                graphics.blit(preview.location(), imgX, imgY, imgAreaW, imgAreaH,
                    0f, 0f, preview.texW(), preview.texH(),
                    preview.texW(), preview.texH());
            } else {
                graphics.fill(imgX, imgY, imgX + imgAreaW, imgY + imgAreaH, 0x40303030);
                graphics.renderOutline(imgX, imgY, imgAreaW, imgAreaH, 0x605B6260);
                String placeholder = "§8暂未添加图片";
                graphics.drawCenteredString(Minecraft.getInstance().font,
                    Component.literal(placeholder),
                    bx + bw / 2, imgY + imgAreaH / 2 - 5,
                    EspetroMutilWidgets.DIM);
            }

            // 票数：外框右下角，只显示数字
            String voteText = String.valueOf(votes);
            int voteW = Minecraft.getInstance().font.width(voteText);
            int voteX = bx + bw - 4 - voteW;
            int voteY = by + bh - 10;
            graphics.drawString(Minecraft.getInstance().font,
                Component.literal(voteText), voteX, voteY,
                enabled ? 0xFFE8B85C : EspetroMutilWidgets.DIM, false);

            // 名称：固定放在外框最底下一行，与右下角票数同一行
            int nameMaxW = Math.max(8, bw - 8 - voteW);
            String mapName = EspetroMutilWidgets.trimToWidth(
                (selected ? "§a✔ " : "§f") + candidate.displayName, nameMaxW);
            int nameX = bx + 4;
            int nameY = by + bh - 10;
            graphics.drawString(Minecraft.getInstance().font,
                Component.literal(mapName), nameX, nameY,
                EspetroMutilWidgets.TEXT, false);

            super.draw(graphics, x, y, width, height, mouseX, mouseY, partialTick);
        }
    }
}
