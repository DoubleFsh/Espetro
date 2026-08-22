package org.espetro.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.espetro.network.NetworkManager;
import org.espetro.network.UnifiedDeployScreenPacket;
import org.espetro.client.aui.GuiElement;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 班组小队界面。
 *
 * 更新策略（tetra/mutil 风格）：树只建一次；
 * 类别弹窗用 setVisible 切换；展开详情/选中态只重建对应子树；
 * 仅当小队列表结构（id+成员）变化时整树 rebuild。
 */
public class SquadScreen extends MutilScreen {

    private static final int NO_SQUAD = -1;
    private static final int BUTTON_H = 12;
    private static final int ROW_H = 12;
    private static final int GAP = 1;
    private static final float TEXT_SCALE = 0.72f;
    private static final float MEMBER_TEXT_SCALE = 0.68f;
    /** 与 J 键部署屏同色阶：不依赖全局透明 PANEL。 */
    private static final int SCREEN_SHADE = 0xFF121517;
    private static final int PANEL_BG = 0xFF191C1E;
    private static final int PANEL_SOFT_BG = 0xFF212527;

    private final List<UnifiedDeployScreenPacket.SquadInfo> squads = new ArrayList<>();
    private final String team;
    private final List<UnifiedDeployScreenPacket.SquadCategoryInfo> categories = new ArrayList<>();
    private final Screen parent;

    private int mySquadId;
    private int selectedSquadId;
    private SquadNameField nameField;
    private String pendingSquadName = "";

    // 保留引用做原地更新
    private GuiElement categoryPopup;
    private GuiElement detailContainer;
    private int detailX, detailY, detailW, detailH;
    private final Map<Integer, EspetroMutilWidgets.ActionButton> rowJoinButtons = new HashMap<>();
    private final Map<Integer, EspetroMutilWidgets.ActionButton> rowDetailButtons = new HashMap<>();

    public SquadScreen(List<UnifiedDeployScreenPacket.SquadInfo> squads, int mySquadId, String team,
                       List<UnifiedDeployScreenPacket.SquadCategoryInfo> categories, Screen parent) {
        super(Component.literal("班组小队"));
        if (squads != null) {
            this.squads.addAll(squads);
        }
        this.mySquadId = mySquadId;
        this.team = team;
        if (categories != null) this.categories.addAll(categories);
        this.parent = parent;
        this.selectedSquadId = NO_SQUAD;
    }

    public void updateSquads(List<UnifiedDeployScreenPacket.SquadInfo> updatedSquads, int updatedMySquadId) {
        List<UnifiedDeployScreenPacket.SquadInfo> nextSquads = updatedSquads == null
            ? List.of() : updatedSquads;
        if (this.mySquadId == updatedMySquadId && this.squads.equals(nextSquads)) {
            return;
        }
        boolean structureChanged = this.mySquadId != updatedMySquadId
            || !squadStructureSignature(this.squads).equals(squadStructureSignature(nextSquads));
        this.squads.clear();
        this.squads.addAll(nextSquads);
        this.mySquadId = updatedMySquadId;
        if (findSquad(selectedSquadId) == null) {
            selectedSquadId = NO_SQUAD;
        }
        if (parent instanceof UnifiedDeployScreen deployScreen) {
            deployScreen.updateSquads(updatedSquads, updatedMySquadId);
        }
        if (root == null) {
            return;
        }
        if (structureChanged) {
            rebuildMutilRoot();
        } else {
            // 仅成员职业名等展示字段变化：原地刷新行标签与详情
            refreshSquadRowLabels();
            rebuildDetailContainer();
        }
    }

    /** 结构签名 = 小队 id + 成员 UUID 列表（忽略 className 等展示字段）。 */
    private static List<Object> squadStructureSignature(List<UnifiedDeployScreenPacket.SquadInfo> list) {
        List<Object> signature = new ArrayList<>();
        for (UnifiedDeployScreenPacket.SquadInfo squad : list) {
            signature.add(squad.id);
            for (UnifiedDeployScreenPacket.SquadMemberInfo member : squad.members) {
                signature.add(member.uuid);
                signature.add(member.leader);
            }
        }
        return signature;
    }

    public void updateFromDeployPacket(UnifiedDeployScreenPacket packet) {
        updateSquads(packet.getSquads(), packet.getMySquadId());
        if (parent instanceof UnifiedDeployScreen deployScreen) {
            deployScreen.updateClasses(packet.getClasses(), packet.getClassCounts(), packet.getVariantCounts());
            deployScreen.updateTimeRemaining(packet.getDeployTimeRemaining());
            deployScreen.updateDeploymentState(
                packet.isWaitingForDeploySelection(),
                packet.getOutpostRedeployCooldownRemaining());
            deployScreen.updateClassSwitchCooldown(
                packet.getClassSwitchCooldownRemaining());
        }
    }

    @Override
    protected void renderBeforeMutil(net.minecraft.client.gui.GuiGraphics graphics,
                                     int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, this.width, this.height, SCREEN_SHADE);
    }

    @Override
    protected void buildMutilRoot(GuiElement root) {
        rowJoinButtons.clear();
        rowDetailButtons.clear();

        int panelW = Math.max(250, Math.min(520, this.width - 12));
        int panelH = Math.max(154, Math.min(210, this.height - 12));
        int panelX = (this.width - panelW) / 2;
        int panelY = Math.max(4, (this.height - panelH) / 2);

        root.addChild(EspetroMutilWidgets.panel(panelX, panelY, panelW, panelH,
            PANEL_BG, EspetroMutilWidgets.BORDER));

        root.addChild(compactText(panelX + 5, panelY + 5,
            "§6§l班组小队", EspetroMutilWidgets.GOLD));
        root.addChild(compactText(panelX + 5, panelY + 14,
            EspetroMutilWidgets.teamPrefix(team) + EspetroMutilWidgets.teamName(team),
            EspetroMutilWidgets.teamColor(team)));

        EspetroMutilWidgets.ActionButton closeButton = compactButton(
            panelX + panelW - 35, panelY + 4, 30, BUTTON_H, "关闭", this::returnToParent);
        root.addChild(closeButton);

        int inputY = panelY + 25;
        int createW = 32;
        int inputW = Math.min(150, panelW / 2 - createW - 10);
        nameField = new SquadNameField(panelX + 5, inputY, inputW, BUTTON_H, "小队名称", this::createSquad);
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
        detailW = Math.max(112, Math.min(210, (panelW - 14) / 2));
        int listW = panelW - detailW - 13;
        int listX = panelX + 5;
        detailX = listX + listW + 3;
        detailY = contentY;
        detailH = contentH;

        buildSquadList(root, listX, contentY, listW, contentH);

        root.addChild(EspetroMutilWidgets.panel(detailX, detailY, detailW, detailH,
            PANEL_SOFT_BG, EspetroMutilWidgets.BORDER));
        detailContainer = new GuiElement(0, 0, this.width, this.height);
        root.addChild(detailContainer);
        rebuildDetailContainer();

        // 类别弹窗常驻构建，setVisible 切换（tetra 式：不为弹窗重建整树）
        categoryPopup = buildCategoryPopup(panelX, panelY, panelW, panelH);
        categoryPopup.setVisible(false);
        root.addChild(categoryPopup);
    }

    private void buildSquadList(GuiElement root, int x, int y, int width, int height) {
        root.addChild(EspetroMutilWidgets.panel(x, y, width, height,
            PANEL_SOFT_BG, EspetroMutilWidgets.BORDER));

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
            final int squadId = squad.id;
            EspetroMutilWidgets.ActionButton joinButton = compactButton(
                0, rowY, buttonW, ROW_H, squadRowLabel(squad),
                () -> NetworkManager.joinSquad(squadId));
            list.addChild(joinButton);
            rowJoinButtons.put(squadId, joinButton);

            EspetroMutilWidgets.ActionButton detailButton = compactButton(
                buttonW + 2, rowY, 12, ROW_H, "▶", () -> toggleDetail(squadId))
                .setTextColor(EspetroMutilWidgets.GOLD);
            list.addChild(detailButton);
            rowDetailButtons.put(squadId, detailButton);

            rowY += ROW_H + GAP;
        }
        refreshSquadRowLabels();
    }

    private String squadRowLabel(UnifiedDeployScreenPacket.SquadInfo squad) {
        boolean joined = squad.id == mySquadId;
        boolean full = squad.memberCount >= squad.maxMembers && !joined;
        String count = "§7[" + squad.memberCount + "/" + squad.maxMembers + "]";
        String lockIcon = squad.isLocked ? " §c🔒" : "";
        String marker = firstCodePoint(squad.categoryId, squad.categoryDisplayName);
        return (joined ? "§a" : full ? "§c" : "§f")
            + squad.displayId + ". " + squad.name + " " + count + lockIcon
            + (marker.isEmpty() ? "" : " §6[" + marker + "]");
    }

    /** 原地刷新行按钮的标签/状态与展开三角。 */
    private void refreshSquadRowLabels() {
        for (UnifiedDeployScreenPacket.SquadInfo squad : squads) {
            EspetroMutilWidgets.ActionButton join = rowJoinButtons.get(squad.id);
            if (join != null) {
                boolean joined = squad.id == mySquadId;
                boolean full = squad.memberCount >= squad.maxMembers && !joined;
                boolean blockedByLock = squad.isLocked && !joined;
                join.setLabel(squadRowLabel(squad))
                    .setSelected(joined)
                    .setEnabled(!full && !blockedByLock)
                    .setTextColor(full ? EspetroMutilWidgets.DIM : EspetroMutilWidgets.TEXT);
            }
            EspetroMutilWidgets.ActionButton detail = rowDetailButtons.get(squad.id);
            if (detail != null) {
                detail.setLabel(squad.id == selectedSquadId ? "▼" : "▶")
                    .setSelected(squad.id == selectedSquadId);
            }
        }
    }

    /** 展开/收起详情：只重建详情子树 + 原地更新三角，不整树 rebuild。 */
    private void toggleDetail(int squadId) {
        selectedSquadId = selectedSquadId == squadId ? NO_SQUAD : squadId;
        refreshSquadRowLabels();
        rebuildDetailContainer();
    }

    /** 详情面板子树重建（背景板常驻，只换内容元素）。 */
    private void rebuildDetailContainer() {
        if (detailContainer == null) {
            return;
        }
        detailContainer.clearChildren();

        UnifiedDeployScreenPacket.SquadInfo squad = findSquad(selectedSquadId);
        if (squad == null) {
            return;
        }
        int x = detailX;
        int y = detailY;
        int width = detailW;
        int height = detailH;

        detailContainer.addChild(compactText(x + 4, y + 4, width - 8,
            "§6§l" + squad.name, EspetroMutilWidgets.GOLD, TEXT_SCALE));
        detailContainer.addChild(compactText(x + 4, y + 13, width - 43,
            "§7成员 " + squad.memberCount + "/" + squad.maxMembers,
            EspetroMutilWidgets.MUTED, TEXT_SCALE));

        if (isLocalPlayerLeader(squad)) {
            String lockLabel = squad.isLocked ? "解锁" : "锁定";
            int lockColor = squad.isLocked ? EspetroMutilWidgets.POSITIVE : EspetroMutilWidgets.WARNING;
            detailContainer.addChild(compactButton(x + width - 70, y + 11, 32, BUTTON_H,
                lockLabel, () -> {
                    if (squad.isLocked) {
                        NetworkManager.unlockSquad();
                    } else {
                        NetworkManager.lockSquad();
                    }
                }).setTextColor(lockColor));
            detailContainer.addChild(compactButton(x + width - 36, y + 11, 32, BUTTON_H,
                "删除", () -> NetworkManager.deleteSquad(squad.id))
                .setTextColor(EspetroMutilWidgets.NEGATIVE));
        } else if (squad.isLocked) {
            detailContainer.addChild(compactText(x + width - 70, y + 13, 66,
                "§c🔒 已锁定", EspetroMutilWidgets.MUTED, TEXT_SCALE));
        }

        ScrollableList detailList = new ScrollableList(x + 4, y + 25, width - 8, height - 29)
            .setScrollStep(8)
            .setAlwaysShowScrollbar(true);
        detailContainer.addChild(detailList);

        if (squad.members.isEmpty()) {
            detailList.addChild(compactText(0, 0, "暂无成员", EspetroMutilWidgets.MUTED));
            return;
        }

        int lineY = 0;
        boolean localLeader = isLocalPlayerLeader(squad);
        UUID localId = Minecraft.getInstance().player != null
            ? Minecraft.getInstance().player.getUUID() : null;
        for (UnifiedDeployScreenPacket.SquadMemberInfo member : squad.members) {
            String label = member.leader
                ? "[队长] " + member.playerName + " - " + member.className
                : member.playerName + " - " + member.className;
            int textWidth = localLeader && !member.leader ? detailList.getWidth() - 39
                : detailList.getWidth() - 6;
            detailList.addChild(compactText(0, lineY, textWidth,
                label, ClientTacticalState.getSquadMemberColor(squad.id, member),
                MEMBER_TEXT_SCALE));
            if (localLeader && !member.leader && member.uuid != null
                && !member.uuid.equals(new UUID(0L, 0L))
                && !member.uuid.equals(localId)) {
                detailList.addChild(compactButton(detailList.getWidth() - 35, lineY - 1,
                    29, 9, "踢出", () -> NetworkManager.sendMatchStatsAction(
                        org.espetro.network.MatchStatsActionPacket.Action.KICK_FROM_SQUAD,
                        member.uuid)).setTextColor(EspetroMutilWidgets.NEGATIVE));
            }
            lineY += 8;
        }
    }

    private GuiElement buildCategoryPopup(int panelX, int panelY, int panelW, int panelH) {
        GuiElement popup = new GuiElement(0, 0, this.width, this.height);
        List<UnifiedDeployScreenPacket.SquadCategoryInfo> options = categories.isEmpty()
            ? List.of(new UnifiedDeployScreenPacket.SquadCategoryInfo("none", "无"))
            : categories;
        int rowH = BUTTON_H + 2;
        int visibleRows = Math.max(1, Math.min(6, options.size()));
        int popupW = Math.min(180, panelW - 12);
        int popupH = 22 + visibleRows * rowH + 5;
        int x = panelX + 5;
        int y = Math.min(panelY + panelH - popupH - 5, panelY + 39);
        // 点击拦截：弹窗打开时吞掉弹窗矩形内的空白点击，防止穿透到下层小队按钮。
        // mutil 反序遍历子元素，此元素放最前 → 按钮优先响应，拦截兜底。
        final int clickX = x, clickY = y, clickW = popupW, clickH = popupH;
        popup.addChild(new GuiElement(clickX, clickY, clickW, clickH) {
            @Override
            public boolean onMouseClick(int mouseX, int mouseY, int button) {
                return mouseX >= clickX && mouseX < clickX + clickW
                    && mouseY >= clickY && mouseY < clickY + clickH;
            }
        });
        popup.addChild(EspetroMutilWidgets.panel(x, y, popupW, popupH,
            0xF0181818, EspetroMutilWidgets.BORDER_ACTIVE));
        popup.addChild(compactText(x + 6, y + 6, "§6§l选择小队类别",
            EspetroMutilWidgets.GOLD));
        popup.addChild(compactButton(x + popupW - 34, y + 4, 28, BUTTON_H,
            "取消", this::cancelCategoryPopup));
        ScrollableList list = new ScrollableList(x + 6, y + 22, popupW - 12, popupH - 28)
            .setScrollStep(BUTTON_H + 2)
            .setAlwaysShowScrollbar(options.size() > visibleRows);
        popup.addChild(list);
        int rowY = 0;
        for (UnifiedDeployScreenPacket.SquadCategoryInfo category : options) {
            list.addChild(compactButton(0, rowY, list.getWidth() - 12, BUTTON_H,
                category.displayName, () -> finishCreate(category.id)));
            rowY += BUTTON_H + 2;
        }
        return popup;
    }

    private void cancelCategoryPopup() {
        pendingSquadName = "";
        if (categoryPopup != null) {
            categoryPopup.setVisible(false);
        }
    }

    private static String firstCodePoint(String categoryId, String displayName) {
        if ("none".equals(categoryId) || displayName == null || displayName.isEmpty()) return "";
        int cp = displayName.codePointAt(0);
        return new String(Character.toChars(cp));
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
        pendingSquadName = name;
        if (categoryPopup != null) {
            categoryPopup.setVisible(true);
        }
    }

    private void finishCreate(String categoryId) {
        NetworkManager.sendSquadCreateWithCategory(pendingSquadName, categoryId);
        pendingSquadName = "";
        if (categoryPopup != null) {
            categoryPopup.setVisible(false);
        }
        if (nameField != null) nameField.clear();
    }

    private boolean isCategoryPopupOpen() {
        return categoryPopup != null && categoryPopup.isVisible();
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
        if (isCategoryPopupOpen()) {
            cancelCategoryPopup();
            return;
        }
        returnToParent();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
