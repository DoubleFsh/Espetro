package org.espetro.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.espetro.client.aui.GuiElement;
import net.minecraft.client.gui.GuiGraphics;
import org.espetro.network.TeamSelectStatePacket;

/**
 * 攻守方选择界面
 * 展示攻击方/防守方编制图片，图片下方显示名称标签。
 * 中途加入时若服务端已发送编制选择图则优先使用；否则回退到默认攻/防图片。
 */
public class TeamSelectionScreen extends EspetroMenuScreen {
    private static int attackCount;
    private static int defendCount;
    private static int remainingSeconds = 60;
    private static long receivedAtMs;
    private static boolean selectionActive;
    /** 本机当前已选队伍："ATTACK" / "DEFEND" / null。 */
    private static String myTeam;
    /** 因平衡被锁定的队伍："ATTACK" / "DEFEND" / null。 */
    private static String lockedTeam;
    /** 攻击方编制选择图 ResourceLocation 字符串（服务端下发）。 */
    private static String attackFactionImage;
    /** 防守方编制选择图 ResourceLocation 字符串（服务端下发）。 */
    private static String defendFactionImage;

    private static final ResourceLocation DEFAULT_ATTACK =
        ResourceLocation.fromNamespaceAndPath("espetro", "textures/gui/attack_faction.png");
    private static final ResourceLocation DEFAULT_DEFEND =
        ResourceLocation.fromNamespaceAndPath("espetro", "textures/gui/defend_faction.png");

    // 16:9 横向图片显示尺寸
    private static final int IMG_W = 192;
    private static final int IMG_H = 108;
    // 图片间距
    private static final int IMG_GAP = 48;
    // 纹理原始尺寸（128x128 含透明留白，避免边缘黑边）
    private static final int TEX_W = 128, TEX_H = 128;

    /** 选中态边框：进攻红 / 防守蓝（不透明，始终可见）。 */
    private static final int ATTACK_BORDER = 0xFFFF5E56;
    private static final int DEFEND_BORDER = 0xFF5F8DFF;
    private static final int ATTACK_HOVER = 0xFFFF8A84;
    private static final int DEFEND_HOVER = 0xFF8AADFF;

    /**
     * 固定顶栏几何：避免依赖 this.font.lineHeight（缩放/字库变化时会改 headerH，
     * 导致整页按钮纵坐标每秒抖动）。
     */
    private static final int HEADER_TITLE_Y = 6;
    private static final int HEADER_STATUS_Y = 18;
    private static final int HEADER_DETAIL_Y = 30;
    private static final int HEADER_COUNT_Y = 42;
    private static final int HEADER_TIMER_Y = 54;
    private static final int HEADER_H = 66;

    private TeamFactionImageButton attackButton;
    private TeamFactionImageButton defendButton;

    public TeamSelectionScreen() {
        super(Component.literal("选择队伍"));
    }

    public static void updateTeamState(TeamSelectStatePacket packet) {
        int oldAtk = attackCount;
        int oldDef = defendCount;
        String oldAtkImg = attackFactionImage;
        String oldDefImg = defendFactionImage;

        attackCount = packet.attackCount;
        defendCount = packet.defendCount;
        remainingSeconds = packet.remainingSeconds;
        receivedAtMs = System.currentTimeMillis();
        selectionActive = packet.active;
        myTeam = packet.myTeam;
        lockedTeam = packet.lockedTeam;
        attackFactionImage = packet.attackFactionImage;
        defendFactionImage = packet.defendFactionImage;
        // 同步到 ClientGameState，避免本地与权威状态漂移。
        if (packet.myTeam != null && !packet.myTeam.isBlank()) {
            ClientGameState.setPlayerTeam(packet.myTeam);
        }

        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof TeamSelectionScreen screen) {
            // 编制图片变了 → 重建按钮
            if (!eq(oldAtkImg, attackFactionImage) || !eq(oldDefImg, defendFactionImage)) {
                screen.rebuildMenuRoot();
            } else {
                // 只刷新选中边框
                screen.refreshSelectionBorders();
            }
        }
    }

    private static boolean eq(String a, String b) {
        if (a == null) return b == null;
        return a.equals(b);
    }

    @Override
    public void onClose() {
        // 由服务端在选定队伍或阶段推进后替换界面。
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }

    @Override
    protected void renderBeforeMenu(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        EspetroAuiWidgets.drawScreenShade(graphics, this.width, this.height);
    }

    @Override
    protected void buildMenuRoot(GuiElement root) {
        // 全屏黑底由 renderBeforeMenu 绘制；此处仅文字 + 图标，无半透明色块。
        // 计数/倒计时在 renderAfterMenu 绘制，避免每秒重建。
        root.addChild(EspetroAuiWidgets.centeredText(
            6, HEADER_TITLE_Y, Math.max(1, this.width - 12),
            "§6§l队伍选择", EspetroAuiWidgets.TEXT));
        String attackName = EspetroAuiWidgets.teamName("ATTACK");
        String defendName = EspetroAuiWidgets.teamName("DEFEND");
        root.addChild(EspetroAuiWidgets.centeredText(
            6, HEADER_STATUS_Y, Math.max(1, this.width - 12),
            "§f请选择" + attackName + "或" + defendName + "加入战斗", EspetroAuiWidgets.MUTED));
        root.addChild(EspetroAuiWidgets.centeredText(
            6, HEADER_DETAIL_Y, Math.max(1, this.width - 12),
            "§8选择后将进入编制投票阶段", EspetroAuiWidgets.DIM));

        // 两个图片 + 间距 + 两侧留白
        int contentW = IMG_W * 2 + IMG_GAP;
        int panelW = Math.min(540, Math.max(contentW + 40, this.width - 36));

        int panelH = IMG_H + 26;
        int panelX = (this.width - panelW) / 2;
        int panelY = HEADER_H + Math.max(8, (this.height - HEADER_H - panelH) / 2);

        int totalImgW = IMG_W * 2 + IMG_GAP;
        int imgsStartX = panelX + (panelW - totalImgW) / 2;
        int imgY = panelY + 2;

        ResourceLocation atkTex = resolveTexture(attackFactionImage, DEFAULT_ATTACK);
        ResourceLocation defTex = resolveTexture(defendFactionImage, DEFAULT_DEFEND);

        // 攻击方图片（左侧，16:9 横向）
        int attackImgX = imgsStartX;
        attackButton = new TeamFactionImageButton(attackImgX, imgY, IMG_W, IMG_H, TEX_W, TEX_H, atkTex,
            () -> { if (!"ATTACK".equals(lockedTeam)) TeamSelectionGui.selectTeam("ATTACK"); },
            ATTACK_BORDER, ATTACK_HOVER);
        root.addChild(attackButton);

        // 防守方图片（右侧，16:9 横向）
        int defendImgX = imgsStartX + IMG_W + IMG_GAP;
        defendButton = new TeamFactionImageButton(defendImgX, imgY, IMG_W, IMG_H, TEX_W, TEX_H, defTex,
            () -> { if (!"DEFEND".equals(lockedTeam)) TeamSelectionGui.selectTeam("DEFEND"); },
            DEFEND_BORDER, DEFEND_HOVER);
        root.addChild(defendButton);

        // 图片下方的文字标签
        int labelY = imgY + IMG_H + 6;
        root.addChild(EspetroAuiWidgets.centeredText(attackImgX, labelY, IMG_W,
            EspetroAuiWidgets.teamPrefix("ATTACK") + "§l" + attackName, EspetroAuiWidgets.ATTACK));
        root.addChild(EspetroAuiWidgets.centeredText(defendImgX, labelY, IMG_W,
            EspetroAuiWidgets.teamPrefix("DEFEND") + "§l" + defendName, EspetroAuiWidgets.DEFEND));

        refreshSelectionBorders();
    }

    private static ResourceLocation resolveTexture(String fullPath, ResourceLocation fallback) {
        if (fullPath == null || fullPath.isEmpty()) return fallback;
        String trimmed = fullPath.trim();
        // 1. 尝试 ResourceLocation 格式（espetro:textures/...）且资源确实存在，
        //    避免客户端资源缺失时直接 blit 显示紫黑块。
        ResourceLocation rl = ResourceLocation.tryParse(trimmed);
        if (rl != null) {
            try {
                if (Minecraft.getInstance().getResourceManager()
                        .getResource(rl).isPresent()) {
                    return rl;
                }
            } catch (Exception ignored) {
                // fall through
            }
        }
        // 2. 尝试客户端本地 EsFactions/ 目录下的图片文件（单机/局域网备用）。
        Minecraft mc = Minecraft.getInstance();
        if (mc != null) {
            java.nio.file.Path imagePath =
                FactionSelectionImageResolver.resolveClientFile(mc.gameDirectory.toPath(), trimmed);
            if (imagePath != null) {
                ResourceLocation dynamic = tryRegisterLocalImage(imagePath, trimmed);
                if (dynamic != null) return dynamic;
            }
        }
        // 3. 回退默认图。
        return fallback;
    }

    /** 读取客户端本地图片并注册动态纹理；失败返回 null。 */
    private static ResourceLocation tryRegisterLocalImage(java.nio.file.Path imagePath, String key) {
        try (java.io.InputStream in = java.nio.file.Files.newInputStream(imagePath)) {
            com.mojang.blaze3d.platform.NativeImage image =
                com.mojang.blaze3d.platform.NativeImage.read(in);
            net.minecraft.client.renderer.texture.DynamicTexture texture =
                new net.minecraft.client.renderer.texture.DynamicTexture(image);
            ResourceLocation location = ResourceLocation.fromNamespaceAndPath(
                "espetro", "dynamic/team_select_" + Integer.toHexString(key.hashCode()));
            Minecraft.getInstance().getTextureManager().register(location, texture);
            return location;
        } catch (Exception ignored) {
            return null;
        }
    }

    /** 按当前 myTeam 和 lockedTeam 刷新两侧图片的边框。 */
    private void refreshSelectionBorders() {
        String team = resolveMyTeam();
        if (attackButton != null) {
            attackButton.setSelectedBorderColor("ATTACK".equals(team) ? ATTACK_BORDER : 0x00000000);
            if ("ATTACK".equals(lockedTeam)) {
                attackButton.setBorderColor(ATTACK_BORDER);
                attackButton.setHoverBorderColor(ATTACK_BORDER);
            } else {
                attackButton.setBorderColor(0x00000000);
                attackButton.setHoverBorderColor(ATTACK_HOVER);
            }
        }
        if (defendButton != null) {
            defendButton.setSelectedBorderColor("DEFEND".equals(team) ? DEFEND_BORDER : 0x00000000);
            if ("DEFEND".equals(lockedTeam)) {
                defendButton.setBorderColor(DEFEND_BORDER);
                defendButton.setHoverBorderColor(DEFEND_BORDER);
            } else {
                defendButton.setBorderColor(0x00000000);
                defendButton.setHoverBorderColor(DEFEND_HOVER);
            }
        }
    }

    private static String resolveMyTeam() {
        if (myTeam != null && !myTeam.isBlank()) {
            return myTeam;
        }
        String local = ClientGameState.getPlayerTeam();
        return local == null || local.isBlank() ? null : local;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    protected void renderAfterMenu(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        int elapsed = (int) ((System.currentTimeMillis() - receivedAtMs) / 1000L);
        int left = Math.max(0, remainingSeconds - elapsed);
        // 队伍人数计数
        String attackName = EspetroAuiWidgets.teamName("ATTACK");
        String defendName = EspetroAuiWidgets.teamName("DEFEND");
        String countLine = EspetroAuiWidgets.teamPrefix("ATTACK") + attackName
            + " §f" + attackCount + "   §7|   "
            + EspetroAuiWidgets.teamPrefix("DEFEND") + defendName
            + " §f" + defendCount;
        if (lockedTeam != null) {
            countLine += "   §c\uD83D\uDD12 " + EspetroAuiWidgets.teamName(lockedTeam) + "已锁定";
        }
        graphics.drawCenteredString(this.font, countLine,
            this.width / 2, HEADER_COUNT_Y, 0xFFFFFF);
        if (selectionActive) {
            graphics.drawCenteredString(this.font, "§e剩余 " + left + " 秒，可重新选择",
                this.width / 2, HEADER_TIMER_Y, 0xFFFFFF);
        }
    }

    public static void open() {
        Minecraft mc = Minecraft.getInstance();
        if (!(mc.screen instanceof TeamSelectionScreen)) {
            mc.setScreen(new TeamSelectionScreen());
        }
    }

    /** 点击后立即写入本地选中态（服务端广播到达前）。 */
    static void markLocalSelection(String team) {
        myTeam = team;
    }

    void refreshSelectionBordersPublic() {
        refreshSelectionBorders();
    }

    /**
     * 可点击的阵营图片按钮。
     * 渲染时从纹理中裁剪中心 16:9 区域填满 IMG_W×IMG_H，支持选中/悬浮边框。
     */
    private static final class TeamFactionImageButton extends GuiElement {
        private final ResourceLocation texture;
        private final int texW, texH;
        private final int dispW, dispH;
        private final Runnable action;
        private int borderColor;
        private int hoverBorderColor;
        private int selectedBorderColor;

        TeamFactionImageButton(int x, int y, int dispW, int dispH,
                               int texW, int texH, ResourceLocation texture,
                               Runnable action, int borderColor, int hoverBorderColor) {
            super(x, y, dispW, dispH);
            this.dispW = dispW;
            this.dispH = dispH;
            this.texW = texW;
            this.texH = texH;
            this.texture = texture;
            this.action = action;
            this.borderColor = borderColor;
            this.hoverBorderColor = hoverBorderColor;
            this.selectedBorderColor = 0x00000000;
        }

        void setBorderColor(int c) { this.borderColor = c; }
        void setHoverBorderColor(int c) { this.hoverBorderColor = c; }
        void setSelectedBorderColor(int c) { this.selectedBorderColor = c; }

        @Override
        public boolean onMouseClick(int mouseX, int mouseY, int button) {
            if (button == 0 && hasFocus() && action != null) {
                action.run();
                return true;
            }
            return false;
        }

        @Override
        public void draw(GuiGraphics graphics, int refX, int refY, int screenWidth,
                         int screenHeight, int mouseX, int mouseY, float opacity) {
            if (!isVisible() || texture == null) return;
            int bx = refX + getX();
            int by = refY + getY();

            graphics.blit(texture, bx, by, dispW, dispH, 0, 0, texW, texH, texW, texH);

            // 边框
            int outColor = selectedBorderColor != 0 ? selectedBorderColor
                : hasFocus() ? hoverBorderColor : borderColor;
            if (outColor != 0) {
                graphics.renderOutline(bx, by, dispW, dispH, outColor);
            }
        }
    }
}
