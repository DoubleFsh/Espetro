package org.espetro.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.espetro.network.NetworkManager;
import org.espetro.team.CommanderSkillManager;
import se.mickelus.mutil.gui.GuiElement;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class CommanderSkillScreen extends MutilScreen {

    private Map<String, Integer> cooldowns = new HashMap<>();
    private final Map<String, Long> cooldownEndsAtMillis = new HashMap<>();
    private List<CommanderSkillManager.SkillView> skills = new ArrayList<>();
    private boolean isCommander;
    private int lastCooldownSignature = Integer.MIN_VALUE;
    private final Map<String, SkillRow> skillRows = new HashMap<>();

    public CommanderSkillScreen(boolean isCommander, Map<String, Integer> cooldowns) {
        this(isCommander, cooldowns, List.of());
    }

    public CommanderSkillScreen(boolean isCommander, Map<String, Integer> cooldowns,
                                List<CommanderSkillManager.SkillView> skills) {
        super(Component.literal("指挥官技能"));
        this.isCommander = isCommander;
        setSkills(skills);
        setCooldowns(cooldowns);
    }

    public static void open(boolean isCommander, Map<String, Integer> cooldowns) {
        open(isCommander, cooldowns, List.of());
    }

    public static void open(boolean isCommander, Map<String, Integer> cooldowns,
                            List<CommanderSkillManager.SkillView> skills) {
        Minecraft mc = Minecraft.getInstance();
        mc.setScreen(new CommanderSkillScreen(isCommander, cooldowns, skills));
    }

    public void updateData(boolean isCommander, Map<String, Integer> cooldowns) {
        updateData(isCommander, cooldowns, List.of());
    }

    public void updateData(boolean isCommander, Map<String, Integer> cooldowns,
                           List<CommanderSkillManager.SkillView> skills) {
        List<CommanderSkillManager.SkillView> nextSkills = skills != null
            ? new ArrayList<>(skills) : new ArrayList<>();
        boolean layoutChanged = this.isCommander != isCommander || !this.skills.equals(nextSkills);
        this.isCommander = isCommander;
        setSkills(nextSkills);
        setCooldowns(cooldowns);
        if (root != null) {
            if (layoutChanged) {
                rebuildMutilRoot();
            } else {
                refreshSkillRows();
            }
        }
    }

    private void setSkills(List<CommanderSkillManager.SkillView> skills) {
        this.skills = skills != null ? new ArrayList<>(skills) : new ArrayList<>();
    }

    private void setCooldowns(Map<String, Integer> cooldowns) {
        this.cooldowns = cooldowns != null ? new HashMap<>(cooldowns) : new HashMap<>();
        this.cooldownEndsAtMillis.clear();

        long now = System.currentTimeMillis();
        for (Map.Entry<String, Integer> entry : this.cooldowns.entrySet()) {
            int seconds = entry.getValue() != null ? entry.getValue() : 0;
            if (seconds > 0) {
                this.cooldownEndsAtMillis.put(entry.getKey(), now + seconds * 1000L);
            }
        }
        this.lastCooldownSignature = Integer.MIN_VALUE;
    }

    @Override
    protected void buildMutilRoot(GuiElement root) {
        skillRows.clear();
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
            lastCooldownSignature = getCooldownSignature();
            return;
        }

        int contentY = panelY + 34;
        int contentX = panelX + 10;
        int contentW = panelW - 20;

        for (CommanderSkillManager.SkillView skill : skills) {
            contentY = buildSkillCard(root, contentX, contentY, contentW, skill);
            contentY += 8;
        }

        lastCooldownSignature = getCooldownSignature();
    }

    private int buildSkillCard(GuiElement root, int x, int y, int width,
                               CommanderSkillManager.SkillView skill) {
        int cardH = 62;
        int cooldownSec = getRemainingCooldownSeconds(skill.id());
        boolean onCooldown = cooldownSec > 0;

        int cardColor = onCooldown ? 0x55363636 : 0x60404040;
        int borderColor = onCooldown ? EspetroMutilWidgets.BORDER : 0x806D7482;
        EspetroMutilWidgets.Panel panel = EspetroMutilWidgets.panel(
            x, y, width, cardH, cardColor, borderColor);
        root.addChild(panel);

        root.addChild(EspetroMutilWidgets.text(x + 8, y + 6,
            "\u00a7e" + skill.displayName(), EspetroMutilWidgets.GOLD));

        root.addChild(EspetroMutilWidgets.text(x + 8, y + 20,
            "\u00a77" + skill.description(), EspetroMutilWidgets.MUTED));

        String stats = skill.stats() == null ? "" : skill.stats();
        root.addChild(EspetroMutilWidgets.text(x + 8, y + 34,
            stats, EspetroMutilWidgets.DIM));

        int btnW = 56;
        int btnH = 16;
        int btnX = x + width - btnW - 8;
        int btnY = y + (cardH - btnH) / 2;

        EspetroMutilWidgets.ActionButton button = EspetroMutilWidgets.button(
            btnX, btnY, btnW, btnH,
            onCooldown ? cooldownSec + "秒" : "发动",
            () -> activateSkill(skill.id()))
            .setEnabled(!onCooldown)
            .setTextColor(onCooldown ? EspetroMutilWidgets.DIM : EspetroMutilWidgets.POSITIVE);
        root.addChild(button);
        skillRows.put(skill.id(), new SkillRow(panel, button));

        return y + cardH;
    }

    private int getRemainingCooldownSeconds(String skillId) {
        Long endsAt = cooldownEndsAtMillis.get(skillId);
        if (endsAt == null) {
            return 0;
        }

        long remainingMillis = endsAt - System.currentTimeMillis();
        return remainingMillis <= 0 ? 0 : (int) Math.ceil(remainingMillis / 1000.0);
    }

    private int getCooldownSignature() {
        int signature = 1;
        for (CommanderSkillManager.SkillView skill : skills) {
            signature = 31 * signature + getRemainingCooldownSeconds(skill.id());
        }
        return signature;
    }

    private void refreshSkillRows() {
        for (CommanderSkillManager.SkillView skill : skills) {
            SkillRow row = skillRows.get(skill.id());
            if (row == null) {
                continue;
            }
            int cooldownSec = getRemainingCooldownSeconds(skill.id());
            boolean onCooldown = cooldownSec > 0;
            row.panel()
                .setColor(onCooldown ? 0x55363636 : 0x60404040)
                .setBorderColor(onCooldown ? EspetroMutilWidgets.BORDER : 0x806D7482);
            row.button()
                .setLabel(onCooldown ? cooldownSec + "秒" : "发动")
                .setEnabled(!onCooldown)
                .setTextColor(onCooldown ? EspetroMutilWidgets.DIM : EspetroMutilWidgets.POSITIVE);
        }
        lastCooldownSignature = getCooldownSignature();
    }

    private record SkillRow(EspetroMutilWidgets.Panel panel,
                            EspetroMutilWidgets.ActionButton button) {
    }

    private void activateSkill(String skillId) {
        NetworkManager.sendCommanderSkillActivate(skillId);
        Minecraft.getInstance().setScreen(null);
    }

    @Override
    public void tick() {
        super.tick();
        if (root == null) {
            return;
        }

        int cooldownSignature = getCooldownSignature();
        if (cooldownSignature != lastCooldownSignature) {
            refreshSkillRows();
        }
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
