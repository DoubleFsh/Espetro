package org.espetro.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import se.mickelus.mutil.gui.GuiElement;
import net.minecraft.client.gui.GuiGraphics;
import org.espetro.network.TeamSelectStatePacket;

/**
 * 攻防方选择界面
 * 展示攻击方/防守方图片，图片下方显示名称标签
 */
public class TeamSelectionScreen extends MutilScreen {
    private static int attackCount;
    private static int defendCount;
    private static int remainingSeconds = 60;
    private static long receivedAtMs;
    private static boolean selectionActive;
    /** 本机当前已选队伍："ATTACK" / "DEFEND" / null。 */
    private static String myTeam;

    private static final ResourceLocation ATTACK_TEXTURE =
        ResourceLocation.fromNamespaceAndPath("espetro", "textures/gui/attack_faction.png");
    private static final ResourceLocation DEFEND_TEXTURE =
        ResourceLocation.fromNamespaceAndPath("espetro", "textures/gui/defend_faction.png");

    // 图片最大显示尺寸（等比例缩放，不超过此尺寸）
    private static final int IMG_MAX = 128;
    // 图片间距
    private static final int IMG_GAP = 48;
    // 纹理原始尺寸（128x128 含透明留白，避免边缘黑边）
    private static final int ATK_TEX_W = 128, ATK_TEX_H = 128;
    private static final int DEF_TEX_W = 128, DEF_TEX_H = 128;

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

    private EspetroMutilWidgets.ImageButton attackButton;
    private EspetroMutilWidgets.ImageButton defendButton;

    public TeamSelectionScreen() {
        super(Component.literal("选择队伍"));
    }

    public static void updateTeamState(TeamSelectStatePacket packet) {
        attackCount = packet.attackCount;
        defendCount = packet.defendCount;
        remainingSeconds = packet.remainingSeconds;
        receivedAtMs = System.currentTimeMillis();
        selectionActive = packet.active;
        myTeam = packet.myTeam;
        // 同步到 ClientGameState，避免本地与权威状态漂移。
        if (packet.myTeam != null && !packet.myTeam.isBlank()) {
            ClientGameState.setPlayerTeam(packet.myTeam);
        }

        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof TeamSelectionScreen screen) {
            // 只刷新选中边框，绝不 rebuild 整棵 MUtil 树（否则每秒广播会高度闪烁）。
            screen.refreshSelectionBorders();
        }
    }

    @Override
    public void onClose() {
        // 由服务端在选定队伍或阶段推进后替换界面。中途加入者处于
        // BATTLE 阶段，不能仅以全局 TEAM_SELECT 阶段判断是否允许退出。
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }

    @Override
    protected void renderBeforeMutil(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // 静态不透明纯黑底（与部署/其他阶段 GUI 一致），不依赖失明层。
        EspetroMutilWidgets.drawScreenShade(graphics, this.width, this.height);
    }

    @Override
    protected void buildMutilRoot(GuiElement root) {
        // 全屏黑底由 renderBeforeMutil 绘制；此处仅文字 + 图标，无半透明色块。
        // 计数/倒计时在 renderAfterMutil 绘制，避免每秒重建。
        root.addChild(EspetroMutilWidgets.centeredText(
            6, HEADER_TITLE_Y, Math.max(1, this.width - 12),
            "§6§l队伍选择", EspetroMutilWidgets.TEXT));
        root.addChild(EspetroMutilWidgets.centeredText(
            6, HEADER_STATUS_Y, Math.max(1, this.width - 12),
            "§f请选择进攻方或防守方加入战斗", EspetroMutilWidgets.MUTED));
        root.addChild(EspetroMutilWidgets.centeredText(
            6, HEADER_DETAIL_Y, Math.max(1, this.width - 12),
            "§8选择后将进入编制投票阶段", EspetroMutilWidgets.DIM));

        // 两个图片 + 间距 + 两侧留白
        int contentW = IMG_MAX * 2 + IMG_GAP;
        int panelW = Math.min(500, Math.max(contentW + 40, this.width - 36));

        // 图片区 + 文字标签区
        int panelH = IMG_MAX + 26;
        int panelX = (this.width - panelW) / 2;
        int panelY = HEADER_H + Math.max(8, (this.height - HEADER_H - panelH) / 2);

        // 两张图片居中排列
        int totalImgW = IMG_MAX * 2 + IMG_GAP;
        int imgsStartX = panelX + (panelW - totalImgW) / 2;
        int imgY = panelY + 2;

        // 攻击方图片（左侧，等比例缩放）
        int attackImgX = imgsStartX;
        attackButton = new EspetroMutilWidgets.ImageButton(attackImgX, imgY, IMG_MAX, IMG_MAX,
            ATK_TEX_W, ATK_TEX_H, ATTACK_TEXTURE,
            () -> TeamSelectionGui.selectTeam("ATTACK"))
            .setHoverBorderColor(ATTACK_HOVER);
        root.addChild(attackButton);

        // 防守方图片（右侧，等比例缩放）
        int defendImgX = imgsStartX + IMG_MAX + IMG_GAP;
        defendButton = new EspetroMutilWidgets.ImageButton(defendImgX, imgY, IMG_MAX, IMG_MAX,
            DEF_TEX_W, DEF_TEX_H, DEFEND_TEXTURE,
            () -> TeamSelectionGui.selectTeam("DEFEND"))
            .setHoverBorderColor(DEFEND_HOVER);
        root.addChild(defendButton);

        // 图片下方的文字标签
        int labelY = imgY + IMG_MAX + 6;
        root.addChild(EspetroMutilWidgets.centeredText(attackImgX, labelY, IMG_MAX,
            "§c§l攻击方", EspetroMutilWidgets.ATTACK));
        root.addChild(EspetroMutilWidgets.centeredText(defendImgX, labelY, IMG_MAX,
            "§9§l防守方", EspetroMutilWidgets.DEFEND));

        refreshSelectionBorders();
    }

    /** 按当前 myTeam 刷新两侧图片的常驻选中边框，不重建布局。 */
    private void refreshSelectionBorders() {
        String team = resolveMyTeam();
        if (attackButton != null) {
            attackButton.setSelectedBorderColor("ATTACK".equals(team) ? ATTACK_BORDER : 0x00000000);
            attackButton.setBorderColor(0x00000000);
        }
        if (defendButton != null) {
            defendButton.setSelectedBorderColor("DEFEND".equals(team) ? DEFEND_BORDER : 0x00000000);
            defendButton.setBorderColor(0x00000000);
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
    protected void renderAfterMutil(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        int elapsed = (int) ((System.currentTimeMillis() - receivedAtMs) / 1000L);
        int left = Math.max(0, remainingSeconds - elapsed);
        graphics.drawCenteredString(this.font,
            "§c进攻方 §f" + attackCount + "   §7|   §9防守方 §f" + defendCount,
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
}
