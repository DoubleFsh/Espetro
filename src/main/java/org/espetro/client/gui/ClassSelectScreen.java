package org.espetro.client.gui;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.espetro.network.ClassSelectScreenPacket;
import org.espetro.network.NetworkManager;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 编制选择界面
 * 指挥官选择队伍编制（如 PLA重型合成旅、美国空降部队），非指挥官只能观看
 * 不可关闭，星标只标记最后选择的编制
 */
public class ClassSelectScreen extends Screen {

    private final String team; // "ATTACK" 或 "DEFEND"
    private final boolean isCommander;
    private final List<ClassSelectScreenPacket.FactionInfo> factions;
    private int timeRemaining;

    // 最后选择的编制（星标只标记这一个）
    private String lastSelectedFaction = null;

    // 存储编制按钮及其原始名称，用于动态更新星标
    private final Map<String, Button> factionButtons = new HashMap<>();
    private final Map<String, String> factionNames = new HashMap<>();

    // 按钮布局
    private final int buttonWidth = 180;
    private final int buttonHeight = 35;
    private final int hSpacing = 12;
    private final int vSpacing = 10;
    private final int columns = 2;
    private int startX;
    private int startY = 80;

    public ClassSelectScreen(String team, boolean isCommander, List<ClassSelectScreenPacket.FactionInfo> factions, int timeRemaining) {
        super(Component.literal("编制选择"));
        this.team = team;
        this.isCommander = isCommander;
        this.factions = factions;
        this.timeRemaining = timeRemaining;
    }

    @Override
    protected void init() {
        super.init();

        // 计算居中位置
        int totalWidth = columns * buttonWidth + (columns - 1) * hSpacing;
        startX = (this.width - totalWidth) / 2;

        createButtons();
    }

    private void createButtons() {
        if (factions == null || factions.isEmpty()) return;

        factionButtons.clear();
        factionNames.clear();

        // 创建编制按钮
        for (int i = 0; i < factions.size(); i++) {
            ClassSelectScreenPacket.FactionInfo faction = factions.get(i);
            final String factionId = faction.id;

            int col = i % columns;
            int row = i / columns;
            int x = startX + col * (buttonWidth + hSpacing);
            int y = startY + row * (buttonHeight + vSpacing);

            factionNames.put(factionId, faction.name);

            if (isCommander) {
                Button button = Button.builder(Component.literal(faction.name), btn -> selectFaction(factionId))
                    .bounds(x, y, buttonWidth, buttonHeight)
                    .build();
                factionButtons.put(factionId, button);
                this.addRenderableWidget(button);
            } else {
                this.addRenderableWidget(
                    Button.builder(Component.literal("§7" + faction.name), btn -> {})
                        .bounds(x, y, buttonWidth, buttonHeight)
                        .build()
                );
            }
        }
    }

    private void selectFaction(String factionId) {
        if (!isCommander) return;

        // 发送编制选择请求到服务端
        NetworkManager.sendClassSelect("", factionId);

        // 移除旧按钮的星标
        if (lastSelectedFaction != null) {
            Button oldBtn = factionButtons.get(lastSelectedFaction);
            String oldName = factionNames.get(lastSelectedFaction);
            if (oldBtn != null && oldName != null) {
                oldBtn.setMessage(Component.literal(oldName));
            }
        }

        // 本地更新显示 — 只记录最后一次选择
        lastSelectedFaction = factionId;

        // 给新选中的按钮添加星标
        Button newBtn = factionButtons.get(factionId);
        String newName = factionNames.get(factionId);
        if (newBtn != null && newName != null) {
            newBtn.setMessage(Component.literal(newName + " §6★"));
        }
    }

    public void updateTimeRemaining(int timeRemaining) {
        this.timeRemaining = timeRemaining;
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(graphics);

        // 标题
        String teamName = "ATTACK".equals(team) ? "§c进攻方" : "§9防守方";
        String roleText = isCommander ? "§a指挥官" : "§7队员";
        graphics.drawCenteredString(this.font, Component.literal("§6§l" + teamName + " 编制选择 §7[" + roleText + "]"),
            this.width / 2, 15, 0xFFFFFF);

        // 副标题 + 倒计时
        String timeColor = timeRemaining <= 5 ? "§c" : "§e";
        if (isCommander) {
            graphics.drawCenteredString(this.font, Component.literal("§e点击选择编制，已选择的显示§6★§e星标"),
                this.width / 2, 40, 0xAAAAAA);
            graphics.drawCenteredString(this.font, Component.literal(timeColor + "剩余时间: " + timeRemaining + "秒"),
                this.width / 2, 55, 0x888888);
        } else {
            graphics.drawCenteredString(this.font, Component.literal("§7请等待指挥官选择编制..."),
                this.width / 2, 40, 0xAAAAAA);
            graphics.drawCenteredString(this.font, Component.literal(timeColor + "剩余时间: " + timeRemaining + "秒"),
                this.width / 2, 55, 0x888888);
        }

        // 右侧显示当前阵营
        int rightX = this.width - 80;
        int infoY = 20;
        String teamLabel = "ATTACK".equals(team) ? "§c■ 攻方" : "§9■ 守方";
        String teamLabelFull = "ATTACK".equals(team) ? "§c进攻方" : "§9防守方";
        graphics.drawString(this.font, Component.literal("§7当前阵营:"), rightX, infoY, 0xAAAAAA);
        graphics.drawString(this.font, Component.literal(teamLabelFull), rightX, infoY + 12, "ATTACK".equals(team) ? 0xFF5555 : 0x5555FF);

        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
