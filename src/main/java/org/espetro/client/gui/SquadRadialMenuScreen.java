package org.espetro.client.gui;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.espetro.Espetro;
import org.espetro.network.NetworkManager;
import org.espetro.network.RadialActionPacket;
import org.espetro.team.CommanderSkillManager;
import se.mickelus.mutil.gui.GuiElement;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class SquadRadialMenuScreen extends MutilScreen {

    private static final ResourceLocation RADIO =
        ResourceLocation.fromNamespaceAndPath("espetro", "textures/gui/squad/radio.png");
    private static final ResourceLocation RALLY =
        ResourceLocation.fromNamespaceAndPath("espetro", "textures/gui/squad/rally.png");
    private static final ResourceLocation CONSTRUCTION =
        ResourceLocation.fromNamespaceAndPath("espetro", "textures/gui/squad/construction_supply.png");
    private static final ResourceLocation AMMO =
        ResourceLocation.fromNamespaceAndPath("espetro", "textures/gui/squad/ammo_supply.png");
    private static final ResourceLocation COMMAND =
        ResourceLocation.fromNamespaceAndPath("espetro", "textures/gui/commander_skills/command.png");

    private final List<Option> options = new ArrayList<>();
    private int selectedIndex = -1;
    private EspetroMutilWidgets.Text centerLabel;

    public SquadRadialMenuScreen(boolean commander, Map<String, Integer> cooldowns,
                                 List<CommanderSkillManager.SkillView> skills) {
        super(Component.literal("战术交互"));
        addTacticalOption("部署 Radio", RADIO, 128, RadialActionPacket.Action.DEPLOY_RADIO);
        addTacticalOption("部署 Rally", RALLY, 256, RadialActionPacket.Action.DEPLOY_RALLY);
        addTacticalOption("存入补给", CONSTRUCTION, 128, RadialActionPacket.Action.DEPOSIT_SUPPLIES);
        addTacticalOption("查看 FOB 状态", AMMO, 128, RadialActionPacket.Action.FOB_STATUS);

        if (commander && skills != null) {
            for (CommanderSkillManager.SkillView skill : skills) {
                int cooldown = cooldowns == null ? 0 : cooldowns.getOrDefault(skill.id(), 0);
                ResourceLocation icon = skill.icon() == null
                    ? null : ResourceLocation.tryParse(skill.icon());
                if (icon == null) icon = COMMAND;
                String label = cooldown > 0
                    ? skill.displayName() + " §7(" + cooldown + "s)"
                    : skill.displayName();
                options.add(new Option(label, icon, 128, cooldown <= 0,
                    () -> NetworkManager.sendCommanderSkillActivate(skill.id())));
            }
        }
    }

    private void addTacticalOption(String label, ResourceLocation icon, int textureWidth,
                                   RadialActionPacket.Action action) {
        options.add(new Option(label, icon, textureWidth, true,
            () -> NetworkManager.sendRadialAction(action)));
    }

    @Override
    public void tick() {
        super.tick();
        if (Espetro.KEY_RADIAL instanceof net.minecraft.client.KeyMapping key && !key.isDown()) {
            if (selectedIndex >= 0 && selectedIndex < options.size()) {
                Option selected = options.get(selectedIndex);
                if (selected.enabled) selected.action.run();
            }
            onClose();
        }
    }

    @Override
    protected void buildMutilRoot(GuiElement root) {
        int centerX = width / 2;
        int centerY = height / 2;
        int radius = options.size() > 6 ? 94 : 72;
        double step = Math.PI * 2.0 / Math.max(1, options.size());
        for (int i = 0; i < options.size(); i++) {
            double angle = -Math.PI / 2.0 + step * i;
            int optionX = centerX + (int) Math.round(Math.cos(angle) * radius);
            int optionY = centerY + (int) Math.round(Math.sin(angle) * radius);
            root.addChild(new RadialOption(i, optionX, optionY, options.get(i)));
        }
        centerLabel = EspetroMutilWidgets.centeredText(centerX - 90, centerY - 5, 180,
            "移动鼠标选择", EspetroMutilWidgets.TEXT);
        root.addChild(centerLabel);
    }

    @Override
    protected void renderBeforeMutil(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        int centerX = width / 2;
        int centerY = height / 2;
        double dx = mouseX - centerX;
        double dy = mouseY - centerY;
        selectedIndex = select(dx, dy);
        graphics.fill(0, 0, width, height, 0x66000000);
        if (centerLabel != null) {
            centerLabel.setText(selectedIndex < 0 ? "移动鼠标选择"
                : options.get(selectedIndex).label);
        }
    }

    private final class RadialOption extends GuiElement {
        private final int index;
        private final int centerX;
        private final int centerY;
        private final Option option;

        private RadialOption(int index, int centerX, int centerY, Option option) {
            super(centerX - 30, centerY - 30, 60, 76);
            this.index = index;
            this.centerX = centerX;
            this.centerY = centerY;
            this.option = option;
        }

        @Override
        public void draw(GuiGraphics graphics, int x, int y, int width, int height,
                         int mouseX, int mouseY, float partialTick) {
            boolean active = selectedIndex == index;
            int half = active ? 23 : 20;
            graphics.fill(centerX - half - 3, centerY - half - 3,
                centerX + half + 3, centerY + half + 3,
                !option.enabled ? 0xD03A2424 : active ? 0xE04B5D4B : 0xD0181A1D);
            graphics.renderOutline(centerX - half - 3, centerY - half - 3,
                (half + 3) * 2, (half + 3) * 2,
                active ? 0xFFFFFFFF : 0xFF777B80);
            RenderSystem.setShaderColor(1f, 1f, 1f,
                option.enabled ? (active ? 1f : 0.8f) : 0.45f);
            graphics.blit(option.icon, centerX - half, centerY - half, half * 2, half * 2,
                0f, 0f, 128, 128, option.textureWidth, 128);
            RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
            graphics.drawCenteredString(font, option.label, centerX, centerY + half + 6,
                !option.enabled ? EspetroMutilWidgets.NEGATIVE
                    : active ? 0xFFFFFFFF : 0xFFBFC3C7);
            super.draw(graphics, x, y, width, height, mouseX, mouseY, partialTick);
        }
    }

    private int select(double dx, double dy) {
        if (options.isEmpty() || dx * dx + dy * dy < 25 * 25) return -1;
        double normalized = Math.atan2(dy, dx) + Math.PI / 2.0;
        if (normalized < 0) normalized += Math.PI * 2.0;
        double step = Math.PI * 2.0 / options.size();
        return (int) Math.round(normalized / step) % options.size();
    }

    private record Option(String label, ResourceLocation icon, int textureWidth,
                          boolean enabled, Runnable action) {}

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
