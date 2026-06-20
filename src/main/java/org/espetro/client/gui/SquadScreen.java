package org.espetro.client.gui;

import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.espetro.network.NetworkManager;
import org.espetro.network.UnifiedDeployScreenPacket;
import org.lwjgl.glfw.GLFW;
import se.mickelus.mutil.gui.GuiElement;

import java.util.ArrayList;
import java.util.List;

/**
 * 班组小队界面。
 */
public class SquadScreen extends MutilScreen {

    private static final int NO_SQUAD = -1;
    private static final int ROW_H = 18;
    private static final int GAP = 4;

    private final List<UnifiedDeployScreenPacket.SquadInfo> squads = new ArrayList<>();
    private final String team;
    private final Screen parent;

    private int mySquadId;
    private int selectedSquadId;
    private NameField nameField;

    public SquadScreen(List<UnifiedDeployScreenPacket.SquadInfo> squads, int mySquadId, String team, Screen parent) {
        super(Component.literal("班组小队"));
        if (squads != null) {
            this.squads.addAll(squads);
        }
        this.mySquadId = mySquadId;
        this.team = team;
        this.parent = parent;
        this.selectedSquadId = NO_SQUAD;
    }

    public void updateSquads(List<UnifiedDeployScreenPacket.SquadInfo> updatedSquads, int updatedMySquadId) {
        this.squads.clear();
        if (updatedSquads != null) {
            this.squads.addAll(updatedSquads);
        }
        this.mySquadId = updatedMySquadId;
        if (findSquad(selectedSquadId) == null) {
            selectedSquadId = NO_SQUAD;
        }
        if (parent instanceof UnifiedDeployScreen deployScreen) {
            deployScreen.updateSquads(updatedSquads, updatedMySquadId);
        }
        if (root != null) {
            rebuildMutilRoot();
        }
    }

    @Override
    protected void buildMutilRoot(GuiElement root) {
        int panelW = Math.max(280, Math.min(590, this.width - 20));
        int panelH = Math.max(190, Math.min(250, this.height - 24));
        int panelX = (this.width - panelW) / 2;
        int panelY = Math.max(8, (this.height - panelH) / 2);

        root.addChild(EspetroMutilWidgets.panel(panelX, panelY, panelW, panelH,
            EspetroMutilWidgets.PANEL, EspetroMutilWidgets.BORDER));

        root.addChild(EspetroMutilWidgets.text(panelX + 8, panelY + 8,
            "\u00a76\u00a7l班组小队", EspetroMutilWidgets.GOLD));
        root.addChild(EspetroMutilWidgets.text(panelX + 8, panelY + 20,
            EspetroMutilWidgets.teamPrefix(team) + EspetroMutilWidgets.teamName(team),
            EspetroMutilWidgets.teamColor(team)));

        EspetroMutilWidgets.ActionButton closeButton = EspetroMutilWidgets.button(
            panelX + panelW - 48, panelY + 7, 40, 14, "关闭", this::returnToParent);
        root.addChild(closeButton);

        int inputY = panelY + 38;
        int createW = 46;
        int inputW = Math.min(180, panelW / 2 - createW - 14);
        nameField = new NameField(panelX + 8, inputY, inputW, 16, "小队名称", this::createSquad);
        root.addChild(nameField);
        root.addChild(EspetroMutilWidgets.button(panelX + 8 + inputW + 4, inputY, createW, 16,
            "创建", this::createSquad)
            .setTextColor(EspetroMutilWidgets.POSITIVE));
        root.addChild(EspetroMutilWidgets.button(panelX + 8 + inputW + createW + 8, inputY, 46, 16,
            "退出", NetworkManager::leaveSquad)
            .setEnabled(mySquadId != NO_SQUAD)
            .setTextColor(EspetroMutilWidgets.WARNING));

        int contentY = panelY + 60;
        int contentH = panelH - 68;
        int detailW = Math.max(132, Math.min(240, (panelW - 22) / 2));
        int listW = panelW - detailW - 20;
        int listX = panelX + 8;
        int detailX = listX + listW + 8;

        buildSquadList(root, listX, contentY, listW, contentH);
        buildDetails(root, detailX, contentY, detailW, contentH);
    }

    private void buildSquadList(GuiElement root, int x, int y, int width, int height) {
        root.addChild(EspetroMutilWidgets.panel(x, y, width, height,
            EspetroMutilWidgets.PANEL_SOFT, EspetroMutilWidgets.BORDER));

        ScrollableList list = new ScrollableList(x + 4, y + 4, width - 8, height - 8)
            .setScrollStep(ROW_H + GAP)
            .setAlwaysShowScrollbar(true);
        root.addChild(list);

        if (squads.isEmpty()) {
            list.addChild(EspetroMutilWidgets.centeredText(0, 6, width - 14,
                "暂无小队", EspetroMutilWidgets.MUTED));
            return;
        }

        int rowY = 0;
        int buttonW = list.getWidth() - 26;
        for (UnifiedDeployScreenPacket.SquadInfo squad : squads) {
            boolean joined = squad.id == mySquadId;
            boolean full = squad.memberCount >= squad.maxMembers && !joined;
            String count = "\u00a77[" + squad.memberCount + "/" + squad.maxMembers + "]";
            String label = (joined ? "\u00a7a" : full ? "\u00a7c" : "\u00a7f") + squad.name + " " + count;

            EspetroMutilWidgets.ActionButton joinButton = EspetroMutilWidgets.button(
                0, rowY, buttonW, ROW_H, label, () -> NetworkManager.joinSquad(squad.id))
                .setSelected(joined)
                .setEnabled(!full)
                .setTextColor(full ? EspetroMutilWidgets.DIM : EspetroMutilWidgets.TEXT);
            list.addChild(joinButton);

            String triangle = squad.id == selectedSquadId ? "\u25bc" : "\u25b6";
            EspetroMutilWidgets.ActionButton detailButton = EspetroMutilWidgets.button(
                buttonW + 4, rowY, 18, ROW_H, triangle, () -> {
                    selectedSquadId = selectedSquadId == squad.id ? NO_SQUAD : squad.id;
                    rebuildMutilRoot();
                })
                .setSelected(squad.id == selectedSquadId)
                .setTextColor(EspetroMutilWidgets.GOLD);
            list.addChild(detailButton);

            rowY += ROW_H + GAP;
        }
    }

    private void buildDetails(GuiElement root, int x, int y, int width, int height) {
        root.addChild(EspetroMutilWidgets.panel(x, y, width, height,
            EspetroMutilWidgets.PANEL_SOFT, EspetroMutilWidgets.BORDER));

        UnifiedDeployScreenPacket.SquadInfo squad = findSquad(selectedSquadId);
        if (squad == null) {
            return;
        }

        root.addChild(EspetroMutilWidgets.text(x + 6, y + 6, width - 12,
            "\u00a76\u00a7l" + squad.name, EspetroMutilWidgets.GOLD));
        root.addChild(EspetroMutilWidgets.text(x + 6, y + 18, width - 12,
            "\u00a77成员 " + squad.memberCount + "/" + squad.maxMembers,
            EspetroMutilWidgets.MUTED));

        if (isLocalPlayerLeader(squad)) {
            root.addChild(EspetroMutilWidgets.button(x + width - 50, y + 17, 42, 14,
                "删除", () -> NetworkManager.deleteSquad(squad.id))
                .setTextColor(EspetroMutilWidgets.NEGATIVE));
        }

        ScrollableList detailList = new ScrollableList(x + 6, y + 33, width - 12, height - 39)
            .setScrollStep(12)
            .setAlwaysShowScrollbar(true);
        root.addChild(detailList);

        if (squad.members.isEmpty()) {
            detailList.addChild(EspetroMutilWidgets.text(0, 0, "暂无成员", EspetroMutilWidgets.MUTED));
            return;
        }

        int lineY = 0;
        for (UnifiedDeployScreenPacket.SquadMemberInfo member : squad.members) {
            String label = member.leader
                ? "[队长] " + member.playerName + " - " + member.className
                : member.playerName + " - " + member.className;
            detailList.addChild(EspetroMutilWidgets.text(0, lineY, detailList.getWidth() - 8,
                label, ClientTacticalState.getSquadMemberColor(squad.id, member)));
            lineY += 12;
        }
    }

    private void createSquad() {
        String name = nameField != null ? nameField.getValue() : "";
        NetworkManager.createSquad(name);
        if (nameField != null) {
            nameField.clear();
        }
    }

    private UnifiedDeployScreenPacket.SquadInfo findSquad(int id) {
        for (UnifiedDeployScreenPacket.SquadInfo squad : squads) {
            if (squad.id == id) {
                return squad;
            }
        }
        return null;
    }

    private boolean isLocalPlayerLeader(UnifiedDeployScreenPacket.SquadInfo squad) {
        if (squad.id != mySquadId || Minecraft.getInstance().player == null) {
            return false;
        }
        String localName = Minecraft.getInstance().player.getName().getString();
        for (UnifiedDeployScreenPacket.SquadMemberInfo member : squad.members) {
            if (member.leader && localName.equals(member.playerName)) {
                return true;
            }
        }
        return false;
    }

    private void returnToParent() {
        if (parent != null) {
            Minecraft.getInstance().setScreen(parent);
        } else {
            Minecraft.getInstance().setScreen(null);
        }
    }

    @Override
    public void onClose() {
        returnToParent();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private static class NameField extends GuiElement {
        private static final int MAX_LENGTH = 18;

        private final String placeholder;
        private final Runnable submit;
        private String value = "";
        private boolean active = false;

        NameField(int x, int y, int width, int height, String placeholder, Runnable submit) {
            super(x, y, width, height);
            this.placeholder = placeholder;
            this.submit = submit;
        }

        String getValue() {
            return value.trim();
        }

        void clear() {
            value = "";
        }

        @Override
        public boolean onMouseClick(int mouseX, int mouseY, int button) {
            if (button != 0 || !isVisible()) {
                return false;
            }
            active = hasFocus();
            return active;
        }

        @Override
        public boolean onKeyPress(int keyCode, int scanCode, int modifiers) {
            if (!active) {
                return false;
            }

            if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
                if (submit != null) {
                    submit.run();
                }
                return true;
            }

            if (keyCode == GLFW.GLFW_KEY_BACKSPACE && !value.isEmpty()) {
                int cut = value.offsetByCodePoints(value.length(), -1);
                value = value.substring(0, cut);
                return true;
            }

            return false;
        }

        @Override
        public boolean onCharType(char codePoint, int modifiers) {
            if (!active || value.length() >= MAX_LENGTH || !SharedConstants.isAllowedChatCharacter(codePoint)) {
                return false;
            }
            value += codePoint;
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
            graphics.fill(bx, by, bx + getWidth(), by + getHeight(), active ? 0x80504834 : 0x70404040);
            graphics.renderOutline(bx, by, getWidth(), getHeight(),
                active ? EspetroMutilWidgets.BORDER_ACTIVE : EspetroMutilWidgets.BORDER);

            String drawn = value.isEmpty() ? placeholder : value;
            int color = value.isEmpty() ? EspetroMutilWidgets.DIM : EspetroMutilWidgets.TEXT;
            String trimmed = Minecraft.getInstance().font.plainSubstrByWidth(drawn, getWidth() - 8);
            graphics.drawString(Minecraft.getInstance().font, Component.literal(trimmed),
                bx + 4, by + Math.max(1, (getHeight() - Minecraft.getInstance().font.lineHeight) / 2),
                color, false);

            if (active && !value.isEmpty() && (System.currentTimeMillis() / 500) % 2 == 0) {
                int textW = Minecraft.getInstance().font.width(trimmed);
                int cursorX = Math.min(bx + getWidth() - 4, bx + 4 + textW + 1);
                graphics.fill(cursorX, by + 3, cursorX + 1, by + getHeight() - 3, EspetroMutilWidgets.TEXT);
            }
        }
    }
}
