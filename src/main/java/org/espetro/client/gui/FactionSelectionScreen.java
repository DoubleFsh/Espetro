package org.espetro.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.espetro.team.FactionDataLoader;
import org.espetro.team.FactionDataProvider;

/**
 * 阵营选择界面
 * 玩家选择自己的阵营编制
 */
public class FactionSelectionScreen extends Screen {

    private FactionDataLoader.FactionData[] factions;

    public FactionSelectionScreen() {
        super(Component.literal("选择阵营"));
    }

    @Override
    protected void init() {
        super.init();

        // 加载阵营数据并过滤掉美军空降兵团
        FactionDataLoader loader = FactionDataProvider.getOrCreateLoader();
        // 客户端也需要加载数据（纯客户端连入时 ServerAboutToStart 不会触发）
        loader.ensureLoaded(net.minecraft.client.Minecraft.getInstance().getResourceManager());
        factions = java.util.Arrays.stream(loader.getFactionArray())
            .filter(f -> !f.name.contains("空降兵团"))
            .toArray(FactionDataLoader.FactionData[]::new);

        int centerX = this.width / 2;

        // 网格布局参数 - 更小的按钮
        int buttonWidth = 120;
        int buttonHeight = 40;
        int hSpacing = 8;
        int vSpacing = 6;
        int columns = Math.min(factions.length, 5);
        int startY = 85;

        // 计算网格起始X位置（居中）
        int totalWidth = columns * buttonWidth + (columns - 1) * hSpacing;
        int startX = centerX - totalWidth / 2;

        for (int i = 0; i < factions.length; i++) {
            FactionDataLoader.FactionData faction = factions[i];
            final int index = i;

            int col = i % columns;
            int row = i / columns;
            int x = startX + col * (buttonWidth + hSpacing);
            int y = startY + row * (buttonHeight + vSpacing);

            Component buttonText = Component.literal(faction.icon + " " + faction.name);

            Button.OnPress onPress = btn -> selectFaction(index);

            this.addRenderableWidget(
                Button.builder(buttonText, onPress)
                    .bounds(x, y, buttonWidth, buttonHeight)
                    .build()
            );
        }

        // 返回按钮
        Button.OnPress onBackPress = btn -> this.onClose();
        this.addRenderableWidget(
            Button.builder(Component.literal("§c返回菜单"), onBackPress)
                .bounds(centerX - 80, this.height - 40, 160, 30)
                .build()
        );
    }

    private void selectFaction(int index) {
        if (index >= 0 && index < factions.length) {
            FactionDataLoader.FactionData faction = factions[index];
            // 向服务端请求该阵营的职业数据，服务端回传后自动打开GUI
            org.espetro.network.NetworkManager.requestClassSelection(faction.id);
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(graphics);

        graphics.drawCenteredString(this.font, Component.literal("§6§l战术小队 - 选择阵营"),
            this.width / 2, 20, 0xFFFFFF);

        graphics.drawCenteredString(this.font, Component.literal("§7选择一个阵营编制加入战斗"),
            this.width / 2, 45, 0xAAAAAA);

        if (factions != null) {
            String hint = "§e当前可用阵营: " + factions.length + " 个";
            graphics.drawCenteredString(this.font, Component.literal(hint),
                this.width / 2, 60, 0x888888);
        }

        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
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
            // 向服务端请求完整职业数据，服务端会回传 OpenClassSelectionPacket 自动打开GUI
            org.espetro.network.NetworkManager.requestClassSelection(factionId);
        }
    }
}
