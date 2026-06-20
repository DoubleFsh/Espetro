package org.espetro.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import se.mickelus.mutil.gui.GuiElement;

/**
 * 攻防方选择界面
 * 展示攻击方/防守方图片，图片下方显示名称标签
 */
public class TeamSelectionScreen extends MutilScreen {

    private static final ResourceLocation ATTACK_TEXTURE =
        ResourceLocation.fromNamespaceAndPath("espetro", "textures/gui/attack_faction.png");
    private static final ResourceLocation DEFEND_TEXTURE =
        ResourceLocation.fromNamespaceAndPath("espetro", "textures/gui/defend_faction.png");

    // 图片最大显示尺寸（等比例缩放，不超过此尺寸）
    private static final int IMG_MAX = 128;
    // 图片间距
    private static final int IMG_GAP = 48;
    // 纹理原始尺寸
    private static final int ATK_TEX_W = 105, ATK_TEX_H = 81;
    private static final int DEF_TEX_W = 102, DEF_TEX_H = 79;

    public TeamSelectionScreen() {
        super(Component.literal("选择队伍"));
    }

    @Override
    protected void buildMutilRoot(GuiElement root) {
        // 两个图片 + 间距 + 两侧留白
        int contentW = IMG_MAX * 2 + IMG_GAP;
        int panelW = Math.min(500, Math.max(contentW + 40, this.width - 36));

        // 标题区 + 图片区 + 文字标签区
        int panelH = 180;
        int panelX = (this.width - panelW) / 2;
        int panelY = Math.max(10, (this.height - panelH) / 2);

        // 面板背景（无边框）
        root.addChild(EspetroMutilWidgets.panel(panelX, panelY, panelW, panelH, 0x00000000, 0x00000000));

        // 标题
        root.addChild(EspetroMutilWidgets.centeredText(panelX, panelY + 10, panelW,
            "\u00a76\u00a7l选择你的阵营", EspetroMutilWidgets.TEXT));

        // 两张图片居中排列
        int totalImgW = IMG_MAX * 2 + IMG_GAP;
        int imgsStartX = panelX + (panelW - totalImgW) / 2;
        int imgY = panelY + 32;

        // 攻击方图片（左侧，等比例缩放）
        int attackImgX = imgsStartX;
        root.addChild(new EspetroMutilWidgets.ImageButton(attackImgX, imgY, IMG_MAX, IMG_MAX,
            ATK_TEX_W, ATK_TEX_H, ATTACK_TEXTURE,
            () -> TeamSelectionGui.selectTeam("ATTACK"))
            .setHoverBorderColor(0xFFFF5E56));

        // 防守方图片（右侧，等比例缩放）
        int defendImgX = imgsStartX + IMG_MAX + IMG_GAP;
        root.addChild(new EspetroMutilWidgets.ImageButton(defendImgX, imgY, IMG_MAX, IMG_MAX,
            DEF_TEX_W, DEF_TEX_H, DEFEND_TEXTURE,
            () -> TeamSelectionGui.selectTeam("DEFEND"))
            .setHoverBorderColor(0xFF5F8DFF));

        // 图片下方的文字标签
        int labelY = imgY + IMG_MAX + 6;
        root.addChild(EspetroMutilWidgets.centeredText(attackImgX, labelY, IMG_MAX,
            "\u00a7c\u00a7l攻击方", EspetroMutilWidgets.ATTACK));
        root.addChild(EspetroMutilWidgets.centeredText(defendImgX, labelY, IMG_MAX,
            "\u00a79\u00a7l防守方", EspetroMutilWidgets.DEFEND));
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    public static void open() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen == null) {
            mc.setScreen(new TeamSelectionScreen());
        }
    }
}
