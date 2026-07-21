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
    private static final int BUTTON_H = 12;
    private static final int ROW_H = 12;
    private static final int GAP = 1;
    private static final float TEXT_SCALE = 0.72f;
    private static final float MEMBER_TEXT_SCALE = 0.68f;

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
        List<UnifiedDeployScreenPacket.SquadInfo> nextSquads = updatedSquads == null
            ? List.of() : updatedSquads;
        if (this.mySquadId == updatedMySquadId && this.squads.equals(nextSquads)) {
            return;
        }
        this.squads.clear();
        this.squads.addAll(nextSquads);
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

    public void updateFromDeployPacket(UnifiedDeployScreenPacket packet) {
        updateSquads(packet.getSquads(), packet.getMySquadId());
        if (parent instanceof UnifiedDeployScreen deployScreen) {
            deployScreen.updateClassCounts(packet.getClassCounts(), packet.getVariantCounts());
            deployScreen.updateTimeRemaining(packet.getDeployTimeRemaining());
            deployScreen.updateDeploymentState(
                packet.isWaitingForDeploySelection(),
                packet.getOutpostRedeployCooldownRemaining());
        }
    }

    @Override
    protected void buildMutilRoot(GuiElement root) {
        int panelW = Math.max(250, Math.min(520, this.width - 12));
        int panelH = Math.max(154, Math.min(210, this.height - 12));
        int panelX = (this.width - panelW) / 2;
        int panelY = Math.max(4, (this.height - panelH) / 2);

        root.addChild(EspetroMutilWidgets.panel(panelX, panelY, panelW, panelH,
            EspetroMutilWidgets.PANEL, EspetroMutilWidgets.BORDER));

        root.addChild(compactText(panelX + 5, panelY + 5,
            "\u00a76\u00a7l班组小队", EspetroMutilWidgets.GOLD));
        root.addChild(compactText(panelX + 5, panelY + 14,
            EspetroMutilWidgets.teamPrefix(team) + EspetroMutilWidgets.teamName(team),
            EspetroMutilWidgets.teamColor(team)));

        EspetroMutilWidgets.ActionButton closeButton = compactButton(
            panelX + panelW - 35, panelY + 4, 30, BUTTON_H, "关闭", this::returnToParent);
        root.addChild(closeButton);

        int inputY = panelY + 25;
        int createW = 32;
        int inputW = Math.min(150, panelW / 2 - createW - 10);
        nameField = new NameField(panelX + 5, inputY, inputW, BUTTON_H, "小队名称", this::createSquad);
        root.addChild(nameField);
        root.addChild(compactButton(panelX + 5 + inputW + 3, inputY, createW, BUTTON_H,
            "创建", this::createSquad)
            .setTextColor(EspetroMutilWidgets.POSITIVE));
        root.addChild(compactButton(panelX + 5 + inputW + createW + 6, inputY, 32, BUTTON_H,
            "退出", NetworkManager::leaveSquad)
            .setEnabled(mySquadId != NO_SQUAD)
            .setTextColor(EspetroMutilWidgets.WARNING));

        int contentY = panelY + 40;
        int contentH = panelH - 45;
        int detailW = Math.max(112, Math.min(210, (panelW - 14) / 2));
        int listW = panelW - detailW - 13;
        int listX = panelX + 5;
        int detailX = listX + listW + 3;

        buildSquadList(root, listX, contentY, listW, contentH);
        buildDetails(root, detailX, contentY, detailW, contentH);
    }

    private void buildSquadList(GuiElement root, int x, int y, int width, int height) {
        root.addChild(EspetroMutilWidgets.panel(x, y, width, height,
            EspetroMutilWidgets.PANEL_SOFT, EspetroMutilWidgets.BORDER));

        ScrollableList list = new ScrollableList(x + 3, y + 3, width - 6, height - 6)
            .setScrollStep(ROW_H + GAP)
            .setAlwaysShowScrollbar(true);
        root.addChild(list);

        if (squads.isEmpty()) {
            list.addChild(compactCenteredText(0, 3, width - 10,
                "暂无小队", EspetroMutilWidgets.MUTED));
            return;
        }

        int rowY = 0;
        int buttonW = list.getWidth() - 18;
        for (UnifiedDeployScreenPacket.SquadInfo squad : squads) {
            boolean joined = squad.id == mySquadId;
            boolean full = squad.memberCount >= squad.maxMembers && !joined;
            String count = "\u00a77[" + squad.memberCount + "/" + squad.maxMembers + "]";
            String label = (joined ? "\u00a7a" : full ? "\u00a7c" : "\u00a7f") + squad.name + " " + count;

            EspetroMutilWidgets.ActionButton joinButton = compactButton(
                0, rowY, buttonW, ROW_H, label, () -> NetworkManager.joinSquad(squad.id))
                .setSelected(joined)
                .setEnabled(!full)
                .setTextColor(full ? EspetroMutilWidgets.DIM : EspetroMutilWidgets.TEXT);
            list.addChild(joinButton);

            String triangle = squad.id == selectedSquadId ? "\u25bc" : "\u25b6";
            EspetroMutilWidgets.ActionButton detailButton = compactButton(
                buttonW + 2, rowY, 12, ROW_H, triangle, () -> {
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

        root.addChild(compactText(x + 4, y + 4, width - 8,
            "\u00a76\u00a7l" + squad.name, EspetroMutilWidgets.GOLD, TEXT_SCALE));
        root.addChild(compactText(x + 4, y + 13, width - 43,
            "\u00a77成员 " + squad.memberCount + "/" + squad.maxMembers,
            EspetroMutilWidgets.MUTED, TEXT_SCALE));

        if (isLocalPlayerLeader(squad)) {
            root.addChild(compactButton(x + width - 36, y + 11, 32, BUTTON_H,
                "删除", () -> NetworkManager.deleteSquad(squad.id))
                .setTextColor(EspetroMutilWidgets.NEGATIVE));
        }

        ScrollableList detailList = new ScrollableList(x + 4, y + 25, width - 8, height - 29)
            .setScrollStep(8)
            .setAlwaysShowScrollbar(true);
        root.addChild(detailList);

        if (squad.members.isEmpty()) {
            detailList.addChild(compactText(0, 0, "暂无成员", EspetroMutilWidgets.MUTED));
            return;
        }

        int lineY = 0;
        for (UnifiedDeployScreenPacket.SquadMemberInfo member : squad.members) {
            String label = member.leader
                ? "[队长] " + member.playerName + " - " + member.className
                : member.playerName + " - " + member.className;
            detailList.addChild(compactText(0, lineY, detailList.getWidth() - 6,
                label, ClientTacticalState.getSquadMemberColor(squad.id, member),
                MEMBER_TEXT_SCALE));
            lineY += 8;
        }
    }

    private static EspetroMutilWidgets.Text compactText(int x, int y, String value, int color) {
        return EspetroMutilWidgets.text(x, y, value, color).setTextScale(TEXT_SCALE);
    }

    private static EspetroMutilWidgets.Text compactText(int x, int y, int width,
                                                       String value, int color, float scale) {
        return EspetroMutilWidgets.text(x, y, width, value, color).setTextScale(scale);
    }

    private static EspetroMutilWidgets.Text compactCenteredText(int x, int y, int width,
                                                               String value, int color) {
        return EspetroMutilWidgets.centeredText(x, y, width, value, color)
            .setTextScale(TEXT_SCALE);
    }

    private static EspetroMutilWidgets.ActionButton compactButton(
            int x, int y, int width, int height, String label, Runnable action) {
        return EspetroMutilWidgets.button(x, y, width, height, label, action)
            .setTextScale(TEXT_SCALE);
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
            int logicalTextWidth = Math.max(8, (int) ((getWidth() - 6) / TEXT_SCALE));
            String trimmed = Minecraft.getInstance().font.plainSubstrByWidth(drawn, logicalTextWidth);
            int textHeight = Math.max(1,
                Math.round(Minecraft.getInstance().font.lineHeight * TEXT_SCALE));
            EspetroMutilWidgets.drawScaledString(graphics, trimmed,
                bx + 3, by + Math.max(1, (getHeight() - textHeight) / 2),
                color, TEXT_SCALE);

            if (active && !value.isEmpty() && (System.currentTimeMillis() / 500) % 2 == 0) {
                int textW = Math.round(Minecraft.getInstance().font.width(trimmed) * TEXT_SCALE);
                int cursorX = Math.min(bx + getWidth() - 3, bx + 3 + textW + 1);
                graphics.fill(cursorX, by + 2, cursorX + 1, by + getHeight() - 2,
                    EspetroMutilWidgets.TEXT);
            }
        }
    }
}
