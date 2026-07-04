package org.espetro.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.espetro.network.NetworkManager;
import org.espetro.team.CommanderSkillType;
import se.mickelus.mutil.gui.GuiElement;

import java.util.Map;

public class CommanderSkillScreen extends MutilScreen {

    private Map<String, Integer> cooldowns;
    private boolean isCommander;

    public CommanderSkillScreen(boolean isCommander, Map<String, Integer> cooldowns) {
        super(Component.literal("指挥官技能"));
        this.isCommander = isCommander;
        this.cooldowns = cooldowns;
    }

    public static void open(boolean isCommander, Map<String, Integer> cooldowns) {
        Minecraft mc = Minecraft.getInstance();
        mc.setScreen(new CommanderSkillScreen(isCommander, cooldowns));
    }

    public void updateData(boolean isCommander, Map<String, Integer> cooldowns) {
        this.isCommander = isCommander;
        this.cooldowns = cooldowns;
        if (root != null) {
            rebuildMutilRoot();
        }
    }

    @Override
    protected void buildMutilRoot(GuiElement root) {
        int panelW = Math.max(300, Math.min(420, this.width - 20));
        int panelH = Math.max(180, Math.min(320, this.height - 24));
        int panelX = (this.width - panelW) / 2;
        int panelY = Math.max(8, (this.height - panelH) / 2);

        root.addChild(EspetroMutilWidgets.panel(panelX, panelY, panelW, panelH,
            EspetroMutilWidgets.PANEL, EspetroMutilWidgets.BORDER));

        root.addChild(EspetroMutilWidgets.text(panelX + 10, panelY + 8,
            "\u00a76\u00a7l指挥官技能", EspetroMutilWidgets.GOLD));

        root.addChild(EspetroMutilWidgets.button(panelX + panelW - 50, panelY + 7, 40, 14,
            "关闭", () -> Minecraft.getInstance().setScreen(null)));

        root.addChild(EspetroMutilWidgets.rect(panelX + 10, panelY + 26, panelW - 20, 1, 0x25FFFFFF));

        if (!isCommander) {
            root.addChild(EspetroMutilWidgets.centeredText(panelX, panelY + 50, panelW,
                "\u00a7c你不是指挥官，无法使用技能", EspetroMutilWidgets.NEGATIVE));
            return;
        }

        int contentY = panelY + 34;
        int contentX = panelX + 10;
        int contentW = panelW - 20;

        for (CommanderSkillType skillType : CommanderSkillType.values()) {
            contentY = buildSkillCard(root, contentX, contentY, contentW, skillType);
            contentY += 8;
        }
    }

    private int buildSkillCard(GuiElement root, int x, int y, int width, CommanderSkillType skillType) {
        int cardH = 62;
        int cooldownSec = cooldowns != null ? cooldowns.getOrDefault(skillType.getId(), 0) : 0;
        boolean onCooldown = cooldownSec > 0;

        int cardColor = onCooldown ? 0x55363636 : 0x60404040;
        int borderColor = onCooldown ? EspetroMutilWidgets.BORDER : 0x806D7482;
        root.addChild(EspetroMutilWidgets.panel(x, y, width, cardH, cardColor, borderColor));

        root.addChild(EspetroMutilWidgets.text(x + 8, y + 6,
            "\u00a7e" + skillType.getDisplayName(), EspetroMutilWidgets.GOLD));

        root.addChild(EspetroMutilWidgets.text(x + 8, y + 20,
            "\u00a77" + skillType.getDescription(), EspetroMutilWidgets.MUTED));

        String stats = getSkillStats(skillType);
        root.addChild(EspetroMutilWidgets.text(x + 8, y + 34,
            stats, EspetroMutilWidgets.DIM));

        int btnW = 56;
        int btnH = 16;
        int btnX = x + width - btnW - 8;
        int btnY = y + (cardH - btnH) / 2;

        if (onCooldown) {
            String cdText = cooldownSec + "秒";
            root.addChild(EspetroMutilWidgets.button(btnX, btnY, btnW, btnH, cdText, () -> {})
                .setEnabled(false)
                .setTextColor(EspetroMutilWidgets.DIM));
        } else {
            root.addChild(EspetroMutilWidgets.button(btnX, btnY, btnW, btnH, "发动", () -> activateSkill(skillType))
                .setTextColor(EspetroMutilWidgets.POSITIVE));
        }

        return y + cardH;
    }

    private String getSkillStats(CommanderSkillType type) {
        return switch (type) {
            case DRONE_DETECTION -> {
                double range = org.espetro.config.GameConfig.getDroneDetectionRange();
                int duration = org.espetro.config.GameConfig.getDroneDetectionDurationSeconds();
                int cooldown = org.espetro.config.GameConfig.getDroneDetectionCooldownSeconds();
                yield "\u00a78范围: " + (int) range + "格 | 持续: " + duration + "秒 | 冷却: " + cooldown + "秒";
            }
        };
    }

    private void activateSkill(CommanderSkillType type) {
        NetworkManager.sendCommanderSkillActivate(type);
        Minecraft.getInstance().setScreen(null);
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return true;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}