package org.espetro.client.gui;

import se.mickelus.mutil.gui.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.espetro.client.HcrTacticalMapBridge;
import org.espetro.network.NetworkManager;
import org.espetro.network.UnifiedDeployScreenPacket;
import org.lwjgl.glfw.GLFW;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 统一部署/复活主界面 — 基于 mutil GuiElement 树架构
 *
 * Squad-style layout: squads | roles and spawn points | tactical map.
 */
public class UnifiedDeployScreen extends Screen {

    private static final int BTN_H = 12;
    private static final int CLASS_BTN_H = 22;
    private static final int CLASS_ICON_SIZE = 17;
    private static final int TITLE_H = 35;
    private static final int STATUS_BAR_H = 13;
    private static final int SECTION_TITLE_H = 10;
    private static final int INNER_PADDING = 3;
    private static final int SCROLLBAR_RESERVED_W = 6;
    private static final int SQUAD_ROW_H = 11;
    private static final int SQUAD_MEMBER_ROW_H = 9;
    private static final int SQUAD_ACTION_ROW_H = 10;
    private static final int VARIANT_POPUP_W = 180;
    private static final int VARIANT_HEADER_H = 18;
    private static final int VARIANT_ROW_H = 28;
    private static final int VARIANT_MAX_VISIBLE = 6;
    private static final int SQUAD_ROW_GAP = 1;
    private static final float UI_TEXT_SCALE = 0.72f;
    private static final float TOOLTIP_TEXT_SCALE = 0.68f;
    private static final float SQUAD_TEXT_SCALE = 0.68f;
    private static final float SQUAD_MEMBER_TEXT_SCALE = 0.64f;

    // EspButton 颜色
    private static final int BTN_BG_NORMAL   = 0xD01B1E20;
    private static final int BTN_BG_HOVER    = 0xE0435145;
    private static final int BTN_BG_DISABLED = 0xB0181B1D;
    private static final int BTN_BORDER      = 0xFF59605E;
    private static final int BTN_TEXT        = 0xFFFFFF;
    private static final Pattern COORDINATE_PATTERN =
        Pattern.compile("[-+]?\\d+(?:\\.\\d+)?");
    private static final ResourceLocation HAB_ICON =
        ResourceLocation.fromNamespaceAndPath("espetro", "textures/gui/squad/hab.png");
    private static final ResourceLocation RALLY_ICON =
        ResourceLocation.fromNamespaceAndPath("espetro", "textures/gui/squad/rally.png");

    // 数据字段
    private final String factionId;
    private final String factionName;
    private final String factionDescription;
    private final String factionIcon;
    private final List<UnifiedDeployScreenPacket.ClassInfo> classes;
    private final Map<String, Integer> classCounts;
    private final Map<String, Map<String, Integer>> variantCounts = new HashMap<>();
    private final boolean hasDeployPoint;
    private final String deployPointPos;
    private final List<UnifiedDeployScreenPacket.BastionItem> bastions;
    private final boolean isCommander;
    private final List<UnifiedDeployScreenPacket.SquadInfo> squads;
    private int mySquadId;
    private int deployTimeRemaining;
    private final String team;
    private boolean waitingForDeploySelection;
    private long outpostRedeployCooldownEndsAt;

    // ===== Element 树 =====
    private GuiElement root;

    // ===== 按钮引用 =====
    private final List<EspButton> classButtons = new ArrayList<>();
    private final List<EspButton> deployButtons = new ArrayList<>();
    private final Map<EspButton, String> deployButtonPositions = new HashMap<>();
    private final Map<EspButton, String> deployButtonCommands = new HashMap<>();
    private final Map<EspButton, String> deployButtonBaseLabels = new HashMap<>();
    private EspButton outpostRedeployButton;
    private EspButton confirmDeployButton;
    private PlainText statusText;
    private PlainText deployTitleText;
    private PlainText statusTimerText;
    private String pendingDeployPosition;
    private String pendingDeployCommand;
    private final Set<Integer> expandedSquadIds = new HashSet<>();
    private int variantPopupClassIndex = -1;
    private int variantPopupX;
    private int variantPopupY;
    private int variantPopupH;
    private int variantPopupScroll;
    private int lastMouseX;
    private int lastMouseY;

    // ===== 滚轮列表 =====
    private ScrollableList classScrollList;
    private ScrollableList deployScrollList;
    private ScrollableList squadScrollList;

    // ===== 区域边界 =====
    private int leftX, leftY, leftW, leftH;
    private int centerX, centerY, centerW, centerH;
    private int squadAreaX, squadAreaY, squadAreaW, squadAreaH;
    private int classAreaX, classAreaY, classAreaW, classAreaH;
    private int deployAreaX, deployAreaY, deployAreaW, deployAreaH;
    private int mapX, mapY, mapW, mapH;

    public UnifiedDeployScreen(UnifiedDeployScreenPacket data) {
        super(Component.literal("部署面板"));
        this.factionId = data.getFactionId();
        this.factionName = data.getFactionName();
        this.factionDescription = data.getFactionDescription();
        this.factionIcon = data.getFactionIcon();
        this.classes = data.getClasses();
        this.classCounts = new HashMap<>(data.getClassCounts());
        replaceVariantCounts(data.getVariantCounts());
        this.hasDeployPoint = data.hasDeployPoint();
        this.deployPointPos = data.getDeployPointPos();
        this.bastions = data.getBastions();
        this.isCommander = data.isCommander();
        this.squads = new ArrayList<>(data.getSquads());
        this.mySquadId = data.getMySquadId();
        this.deployTimeRemaining = data.getDeployTimeRemaining();
        this.team = data.getTeam();
        this.waitingForDeploySelection = data.isWaitingForDeploySelection();
        this.outpostRedeployCooldownEndsAt = System.currentTimeMillis()
            + data.getOutpostRedeployCooldownRemaining() * 1000L;
    }

    public void updateClassCounts(Map<String, Integer> counts) {
        updateClassCounts(counts, null);
    }

    public void updateClassCounts(Map<String, Integer> counts,
                                  Map<String, Map<String, Integer>> updatedVariantCounts) {
        this.classCounts.clear();
        this.classCounts.putAll(counts);
        if (updatedVariantCounts != null) {
            replaceVariantCounts(updatedVariantCounts);
        }
        refreshClassButtons();
    }

    private void replaceVariantCounts(Map<String, Map<String, Integer>> counts) {
        this.variantCounts.clear();
        if (counts == null) return;
        for (Map.Entry<String, Map<String, Integer>> entry : counts.entrySet()) {
            this.variantCounts.put(entry.getKey(), new HashMap<>(entry.getValue()));
        }
    }

    public void updateTimeRemaining(int seconds) {
        this.deployTimeRemaining = seconds;
        if (deployTitleText != null) {
            deployTitleText.setText(buildDeployTitle());
        }
        if (statusTimerText != null) {
            statusTimerText.setText(formatTime(seconds));
        }
    }

    public void updateDeploymentState(boolean waitingForSelection, int redeployCooldownRemaining) {
        this.waitingForDeploySelection = waitingForSelection;
        this.outpostRedeployCooldownEndsAt = System.currentTimeMillis()
            + Math.max(0, redeployCooldownRemaining) * 1000L;
        refreshDeployButtonStates();
    }

    public void updateSquads(List<UnifiedDeployScreenPacket.SquadInfo> updatedSquads, int updatedMySquadId) {
        List<UnifiedDeployScreenPacket.SquadInfo> nextSquads = updatedSquads == null
            ? List.of() : updatedSquads;
        if (this.mySquadId == updatedMySquadId && this.squads.equals(nextSquads)) {
            return;
        }
        this.squads.clear();
        this.squads.addAll(nextSquads);
        Set<Integer> availableSquadIds = new HashSet<>();
        for (UnifiedDeployScreenPacket.SquadInfo squad : this.squads) {
            availableSquadIds.add(squad.id);
        }
        expandedSquadIds.retainAll(availableSquadIds);
        this.mySquadId = updatedMySquadId;
        if (root != null) {
            rebuildGuiPreservingSquadScroll();
        }
    }

    // ==================== 自定义按钮 ====================

    private static class EspButton extends GuiElement {
        private final Runnable action;
        private String label;
        private boolean enabled = true;
        private boolean hovered = false;
        private int normalColor = BTN_BG_NORMAL;
        private int hoverColor = BTN_BG_HOVER;
        private int textColor = BTN_TEXT;
        private ResourceLocation icon;
        private int iconTextureWidth = 128;
        private int iconTextureHeight = 128;
        private int iconSize = 10;
        private String rightLabel = "";
        private float textScale = UI_TEXT_SCALE;
        private boolean centeredText = true;

        EspButton(int x, int y, int w, int h, String label, Runnable action) {
            super(x, y, w, h);
            this.label = label;
            this.action = action;
        }

        void setEnabled(boolean e) { enabled = e; }
        void setLabel(String l) { label = l; }
        boolean isEnabled() { return enabled; }
        void setIcon(ResourceLocation icon, int textureWidth, int textureHeight) {
            this.icon = icon;
            this.iconTextureWidth = textureWidth;
            this.iconTextureHeight = textureHeight;
        }
        void setIconSize(int size) { iconSize = Math.max(1, size); }
        void setRightLabel(String value) { rightLabel = value == null ? "" : value; }
        void setTextScale(float scale) { textScale = Math.max(0.5f, Math.min(1.0f, scale)); }
        void setCenteredText(boolean centered) { centeredText = centered; }

        @Override
        public boolean onMouseClick(int mx, int my, int button) {
            if (button != 0 || !enabled || !isVisible() || !hasFocus()) return false;
            if (action != null) action.run();
            return true;
        }

        @Override
        public void draw(GuiGraphics graphics, int x, int y, int w, int h, int mx, int my, float tick) {
            if (!isVisible()) return;
            hovered = hasFocus();

            int bx = x + getX(), by = y + getY(), bw = getWidth(), bh = getHeight();

            int bgCol;
            if (!enabled) bgCol = BTN_BG_DISABLED;
            else if (hovered) bgCol = hoverColor;
            else bgCol = normalColor;
            graphics.fill(bx, by, bx + bw, by + bh, bgCol);

            int borderCol = !enabled ? 0x60383848 : (hovered ? 0xFF9999BB : BTN_BORDER);
            graphics.renderOutline(bx, by, bw, bh, borderCol);

            int textCol = enabled ? textColor : 0x666666;
            int contentLeft = bx + 3;
            if (icon != null) {
                int drawnIconSize = Math.min(iconSize, bh - 3);
                graphics.blit(icon, bx + 2, by + (bh - drawnIconSize) / 2,
                    drawnIconSize, drawnIconSize, 0.0f, 0.0f,
                    iconTextureWidth, iconTextureHeight, iconTextureWidth, iconTextureHeight);
                contentLeft = bx + drawnIconSize + 4;
            }
            int rightLabelWidth = rightLabel.isEmpty() ? 0 : Math.round(
                Minecraft.getInstance().font.width(
                    EspetroMutilWidgets.stripFormatting(rightLabel)) * textScale);
            int rightLabelX = bx + bw - 3 - rightLabelWidth;
            int availableTextWidth = Math.max(8,
                rightLabelX - (rightLabel.isEmpty() ? 0 : 4) - contentLeft);
            int logicalTextWidth = Math.max(8, (int) (availableTextWidth / textScale));
            String drawnLabel = EspetroMutilWidgets.trimToWidth(label, logicalTextWidth);
            int textWidth = Math.round(Minecraft.getInstance().font.width(
                EspetroMutilWidgets.stripFormatting(drawnLabel)) * textScale);
            int textX = centeredText
                ? contentLeft + Math.max(0, (availableTextWidth - textWidth) / 2)
                : contentLeft;
            int textHeight = Math.max(1, Math.round(Minecraft.getInstance().font.lineHeight * textScale));
            drawScaledString(graphics, drawnLabel, textX,
                by + Math.max(0, (bh - textHeight) / 2), textCol, textScale);
            if (!rightLabel.isEmpty()) {
                drawScaledString(graphics, rightLabel, rightLabelX,
                    by + Math.max(0, (bh - textHeight) / 2), textCol, textScale);
            }
        }
    }

    private static class SquadMemberRow extends GuiElement {
        private final String label;
        private final int accentColor;

        SquadMemberRow(int x, int y, int width, String label, int accentColor) {
            super(x, y, width, SQUAD_MEMBER_ROW_H);
            this.label = label;
            this.accentColor = accentColor;
        }

        @Override
        public void draw(GuiGraphics graphics, int x, int y, int w, int h, int mx, int my, float tick) {
            if (!isVisible()) {
                return;
            }
            int bx = x + getX();
            int by = y + getY();
            graphics.fill(bx, by, bx + getWidth(), by + getHeight(),
                hasFocus() ? 0x80404743 : 0x60141617);
            graphics.fill(bx, by, bx + 1, by + getHeight(), accentColor);

            int textX = bx + 4;
            int availableWidth = Math.max(8, getWidth() - 7);
            String drawnLabel = EspetroMutilWidgets.trimToWidth(
                label, (int) (availableWidth / SQUAD_MEMBER_TEXT_SCALE));
            int textHeight = Math.max(1,
                Math.round(Minecraft.getInstance().font.lineHeight * SQUAD_MEMBER_TEXT_SCALE));
            drawScaledString(graphics, drawnLabel, textX,
                by + Math.max(0, (getHeight() - textHeight) / 2),
                BTN_TEXT, SQUAD_MEMBER_TEXT_SCALE);
        }
    }

    private static void drawScaledString(GuiGraphics graphics, String text, int x, int y,
                                         int color, float scale) {
        graphics.pose().pushPose();
        graphics.pose().scale(scale, scale, 1.0f);
        graphics.drawString(Minecraft.getInstance().font, Component.literal(text),
            Math.round(x / scale), Math.round(y / scale), color, false);
        graphics.pose().popPose();
    }

    // ==================== 自定义文本元素（绕过 GuiText，直接用 drawString) ====================

    /** 直接用 Minecraft Font 渲染的文本元素，亮度和按钮文字一致 */
    private static class PlainText extends GuiElement {
        private String text;
        private int color;

        PlainText(int x, int y, String text, int color) {
            super(x, y,
                Math.max(1, Math.round(Minecraft.getInstance().font.width(
                    EspetroMutilWidgets.stripFormatting(text)) * UI_TEXT_SCALE)),
                Math.max(1, Math.round(Minecraft.getInstance().font.lineHeight * UI_TEXT_SCALE)));
            this.text = text;
            this.color = color;
        }

        void setColor(int c) { color = c; }
        void setText(String value) { text = value == null ? "" : value; }

        @Override
        public void draw(GuiGraphics graphics, int x, int y, int w, int h, int mx, int my, float tick) {
            if (!isVisible()) return;
            drawScaledString(graphics, text, x + getX(), y + getY(), color, UI_TEXT_SCALE);
        }
    }

    // ==================== 界面构建 ====================

    @Override
    protected void init() {
        super.init();
        rebuildGui();
        // 打开包已携带服务端最新人数，后续变更也由服务端主动推送，
        // 不再打开界面后立即发起第二次请求。
    }

    private void rebuildGui() {
        this.root = new GuiElement(0, 0, this.width, this.height);
        this.outpostRedeployButton = null;
        this.confirmDeployButton = null;
        this.statusText = null;
        this.deployTitleText = null;
        this.statusTimerText = null;
        computeRegions();

        buildTitleBar();
        buildDividerLine(TITLE_H);

        root.addChild(new GuiRect(leftX, leftY, leftW, leftH, 0xB0171A1C));
        root.addChild(new GuiRect(centerX, centerY, centerW, centerH, 0xA8121517));
        root.addChild(new GuiRect(mapX, mapY, mapW, mapH, 0x80101416));

        buildSquadSection();
        buildClassSection();
        root.addChild(new GuiRect(centerX + 3, deployAreaY - 3, centerW - 6, 1, 0x50FFFFFF));
        buildDeploySection();

        root.addChild(new GuiRect(leftX + leftW, TITLE_H + 2, 1,
            this.height - TITLE_H - STATUS_BAR_H - 4, 0x50FFFFFF));
        root.addChild(new GuiRect(centerX + centerW, TITLE_H + 2, 1,
            this.height - TITLE_H - STATUS_BAR_H - 4, 0x50FFFFFF));

        buildMapPanel();
        buildStatusBar();
    }

    private void rebuildGuiPreservingSquadScroll() {
        double previousOffset = squadScrollList == null ? 0 : squadScrollList.getOffset();
        rebuildGui();
        if (squadScrollList != null) {
            squadScrollList.setOffset(previousOffset);
        }
    }

    /** 计算所有区域的坐标和尺寸 */
    private void computeRegions() {
        int usableH = this.height - TITLE_H - STATUS_BAR_H - 6;
        leftX = 4;
        leftY = TITLE_H + 2;
        leftW = Math.max(88, Math.min(190, (int) (this.width * 0.22f)));
        leftH = usableH;

        centerX = leftX + leftW + 4;
        centerY = leftY;
        centerW = Math.max(132, Math.min(260, (int) (this.width * 0.31f)));
        centerH = usableH;

        mapX = centerX + centerW + 4;
        mapY = leftY;
        mapW = this.width - mapX - 4;
        mapH = usableH;

        squadAreaX = leftX + INNER_PADDING;
        squadAreaY = leftY + INNER_PADDING;
        squadAreaW = leftW - INNER_PADDING * 2;
        squadAreaH = usableH - INNER_PADDING * 2;

        int halfH = usableH / 2;
        classAreaX = centerX + INNER_PADDING;
        classAreaY = centerY + INNER_PADDING;
        classAreaW = centerW - 2 * INNER_PADDING;
        classAreaH = halfH - 2 * INNER_PADDING;

        deployAreaX = centerX + INNER_PADDING;
        deployAreaY = centerY + halfH + 2;
        deployAreaW = centerW - 2 * INNER_PADDING;
        deployAreaH = usableH - halfH - 2 * INNER_PADDING - 4;
    }

    // ---------- 标题行 ----------
    private void buildTitleBar() {
        root.addChild(new GuiRect(0, 0, this.width, TITLE_H, 0xF016191B));
        int teamAccent = "ATTACK".equals(team) ? 0xFFD35B50 : 0xFF5685C7;
        root.addChild(new GuiRect(0, 0, 3, TITLE_H, teamAccent));

        String teamColor = "ATTACK".equals(team) ? "\u00a7c" : "\u00a79";
        String titleText = EspetroMutilWidgets.trimToWidth(
            "\u00a76\u00a7l部署阶段 \u00a77| " + teamColor + "\u00a7l"
                + factionIcon + " " + factionName,
            Math.max(80, this.width - 96));
        PlainText title = new PlainText(5, 3, titleText, 0xFFFFFF);
        root.addChild(title);

        String teamName = "ATTACK".equals(team) ? "进攻方" : "防守方";
        String sub = "\u00a7f" + teamName;
        if (factionDescription != null && !factionDescription.isEmpty())
            sub += " \u00a7f· " + factionDescription;
        PlainText subtitle = new PlainText(5, 15,
            EspetroMutilWidgets.trimToWidth(sub, Math.max(80, this.width - 16)), BTN_TEXT);
        root.addChild(subtitle);

        PlainText hint = new PlainText(5, 25,
            EspetroMutilWidgets.trimToWidth(
                "\u00a77选择班组、职业与部署点，确认后等待部署",
                Math.max(80, this.width - 16)), BTN_TEXT);
        root.addChild(hint);

        if (deployTimeRemaining >= 0) {
            String timer = formatTime(deployTimeRemaining);
            statusTimerText = new PlainText(
                this.width - Math.round(Minecraft.getInstance().font.width(timer) * UI_TEXT_SCALE) - 6,
                4, timer, 0xFFFFD27A);
            root.addChild(statusTimerText);
        }
    }

    private void buildDividerLine(int y) {
        root.addChild(new GuiRect(4, y, this.width - 8, 1, 0x50FFFFFF));
    }

    // ---------- 班组（左列） ----------
    private void buildSquadSection() {
        int sx = squadAreaX;
        int sy = squadAreaY;
        int areaW = squadAreaW;
        int areaH = squadAreaH;

        root.addChild(new PlainText(sx, sy, "\u00a76\u00a7l班组", 0xFFFFC766));
        int manageH = SQUAD_ROW_H;
        int listY = sy + SECTION_TITLE_H + 1;
        int listH = areaH - SECTION_TITLE_H - manageH - 5;

        squadScrollList = new ScrollableList(sx, listY, areaW, Math.max(SQUAD_ROW_H, listH))
            .setScrollStep(SQUAD_ROW_H + SQUAD_ROW_GAP)
            .setAlwaysShowScrollbar(false);
        root.addChild(squadScrollList);

        int rowW = areaW - 6;
        int rowY = 0;
        for (var squad : squads) {
            boolean mine = squad.id == mySquadId;
            boolean unavailable = squad.isLocked || squad.memberCount >= squad.maxMembers;
            boolean expanded = expandedSquadIds.contains(squad.id);
            String disclosure = expanded ? "\u00a7f\u25bc" : "\u00a7f\u25b6";
            String state = mine ? "\u00a7a\u25cf" : squad.isLocked ? "\u00a7c\u25a0" : "\u00a77\u25cb";
            String label = disclosure + " " + state + " \u00a7f" + squad.id + " " + squad.name
                + " \u00a77" + squad.memberCount + "/" + squad.maxMembers;
            EspButton button = new EspButton(0, rowY, rowW, SQUAD_ROW_H,
                label, () -> toggleSquadExpanded(squad.id));
            button.setTextScale(SQUAD_TEXT_SCALE);
            button.setCenteredText(false);
            if (mine) {
                button.normalColor = 0xD0344939;
                button.hoverColor = 0xE03C5542;
            }
            squadScrollList.addChild(button);
            rowY += SQUAD_ROW_H + SQUAD_ROW_GAP;

            if (!expanded) {
                continue;
            }

            if (squad.members.isEmpty()) {
                squadScrollList.addChild(new SquadMemberRow(
                    3, rowY, rowW - 3, "\u00a77暂无成员资料", 0xFF59605E));
                rowY += SQUAD_MEMBER_ROW_H + SQUAD_ROW_GAP;
            } else {
                for (UnifiedDeployScreenPacket.SquadMemberInfo member : squad.members) {
                    String marker = member.commander
                        ? "\u00a76\u25c6"
                        : member.leader ? "\u00a7d\u25c6" : "\u00a77\u00b7";
                    String role = member.className.isBlank()
                        ? ""
                        : " \u00a78| \u00a7b" + member.className;
                    int accent = member.commander
                        ? 0xFFFFC766
                        : member.leader ? 0xFFD48CFF : 0xFF67A7FF;
                    squadScrollList.addChild(new SquadMemberRow(
                        3, rowY, rowW - 3,
                        marker + " \u00a7f" + member.playerName + role, accent));
                    rowY += SQUAD_MEMBER_ROW_H + SQUAD_ROW_GAP;
                }
            }

            if (!mine && !unavailable) {
                EspButton join = new EspButton(3, rowY, rowW - 3, SQUAD_ACTION_ROW_H,
                    "\u00a7a+ 加入班组", () -> NetworkManager.joinSquad(squad.id));
                join.setTextScale(SQUAD_MEMBER_TEXT_SCALE);
                join.normalColor = 0xB025352B;
                join.hoverColor = 0xD03C5542;
                squadScrollList.addChild(join);
                rowY += SQUAD_ACTION_ROW_H + SQUAD_ROW_GAP;
            }
        }
        if (squads.isEmpty()) {
            squadScrollList.addChild(new PlainText(2, 3, "\u00a77暂无班组", 0xFFABB0B3));
        }

        EspButton manage = new EspButton(sx, sy + areaH - manageH, areaW, manageH,
            "\u00a7e管理班组", this::openSquadManagement);
        manage.setTextScale(SQUAD_TEXT_SCALE);
        root.addChild(manage);
    }

    private void toggleSquadExpanded(int squadId) {
        if (!expandedSquadIds.add(squadId)) {
            expandedSquadIds.remove(squadId);
        }
        rebuildGuiPreservingSquadScroll();
    }

    private void openSquadManagement() {
        Minecraft.getInstance().setScreen(
            new SquadScreen(new ArrayList<>(squads), mySquadId, team, this));
    }

    // ---------- 职业选择（中上，滚轮列表）----------
    private void buildClassSection() {
        int sx = classAreaX, sy = classAreaY;
        int areaW = classAreaW, areaH = classAreaH;

        PlainText ct = new PlainText(sx, sy, "\u00a76职业选择", 0xFFFFAA00);
        root.addChild(ct);

        // 滚轮列表区域：标题下方，占满剩余空间
        int listY = sy + SECTION_TITLE_H + 1;
        int listH = areaH - SECTION_TITLE_H - 2;

        classScrollList = new ScrollableList(sx, listY, areaW, listH)
            .setScrollStep(CLASS_BTN_H + 1)
            .setAlwaysShowScrollbar(true);
        root.addChild(classScrollList);

        classButtons.clear();
        int contentW = areaW - SCROLLBAR_RESERVED_W;
        int cols = 2;
        int btnW = (contentW - 1) / cols;
        int btnH = CLASS_BTN_H;
        int spacing = 1;

        for (int i = 0; i < classes.size(); i++) {
            var cls = classes.get(i);
            int count = classCounts.getOrDefault(cls.classId, 0);
            boolean full = count >= cls.maxPlayers;
            String countColor = full ? "\u00a7c" : "\u00a7a";
            String label = "\u00a7f" + cls.name;

            int col = i % cols;
            int row = i / cols;
            int bx = col * (btnW + spacing);
            int by = row * (btnH + spacing);

            final int idx = i;
            EspButton btn = new EspButton(bx, by, btnW, btnH, label, () -> selectClass(idx));
            btn.setIcon(RoleIconResources.resolve(cls.icon),
                RoleIconResources.TEXTURE_SIZE, RoleIconResources.TEXTURE_SIZE);
            btn.setIconSize(CLASS_ICON_SIZE);
            btn.setRightLabel(countColor + "[" + count + "/" + cls.maxPlayers + "]");
            btn.setCenteredText(false);
            btn.setEnabled(!full);
            if (full) { btn.hoverColor = 0xD0403050; btn.normalColor = 0xB0252035; }
            classScrollList.addChild(btn);
            classButtons.add(btn);
        }
    }

    // ---------- 部署点（中下，滚轮列表）----------
    private void buildDeploySection() {
        int sx = deployAreaX, sy = deployAreaY;
        int areaW = deployAreaW, areaH = deployAreaH;

        // 标题 + 倒计时
        deployTitleText = new PlainText(sx, sy, buildDeployTitle(), 0xFFFFAA00);
        root.addChild(deployTitleText);

        root.addChild(new GuiRect(sx, sy + SECTION_TITLE_H + 1, areaW, 1, 0x30FFFFFF));

        // 滚轮列表区域
        int listY = sy + SECTION_TITLE_H + 3;
        int confirmH = BTN_H;
        int listH = areaH - SECTION_TITLE_H - confirmH - 7;

        deployScrollList = new ScrollableList(sx, listY, areaW, listH)
            .setScrollStep(BTN_H + 1)
            .setAlwaysShowScrollbar(true);
        root.addChild(deployScrollList);

        deployButtons.clear();
        deployButtonPositions.clear();
        deployButtonCommands.clear();
        deployButtonBaseLabels.clear();
        int btnW = areaW - SCROLLBAR_RESERVED_W - 4;
        int btnSpacing = 1;
        int row = 0;

        // 原部署点
        if (hasDeployPoint) {
            String deployLabel = "\u00a7e\u25c6 原部署点 \u00a77(" + deployPointPos + ")";
            String deployCommand = "bastion deploy";
            EspButton btn = new EspButton(
                2, row * (BTN_H + btnSpacing), btnW, BTN_H,
                deployLabel,
                () -> selectDeploymentPoint(deployPointPos, deployCommand)
            );
            btn.setEnabled(waitingForDeploySelection);
            registerDeployButton(btn, deployPointPos, deployCommand, deployLabel);
            row++;
        }

        // 兵站/前哨列表。重新部署入口放在底部状态栏。
        for (var b : bastions) {
            final var bid = b.id;
            if (b.isOutpost()) {
                String deployCmd = "outpost deploy " + (b.getOutpostIndex() + 1);
                String deployLabel = "\u00a7d\u25c6 " + b.name
                    + (b.status.isBlank() ? "" : " \u00a77[" + b.status + "]");
                EspButton selectButton = new EspButton(
                    2, row * (BTN_H + btnSpacing), btnW, BTN_H,
                    deployLabel,
                    () -> selectDeploymentPoint(b.pos, deployCmd)
                );
                selectButton.setEnabled(waitingForDeploySelection);
                registerDeployButton(selectButton, b.pos, deployCmd, deployLabel);
            } else {
                String cmd = "bastion select " + bid;
                String markerColor = b.isRally() ? "\u00a7a" : "\u00a79";
                String marker = b.isRally() ? "\u2691 " : "\u25a0 ";
                String deployLabel = markerColor + marker + b.name
                    + (b.status.isBlank() ? "" : " \u00a77[" + b.status + "]");
                EspButton btn = new EspButton(
                    2, row * (BTN_H + btnSpacing), btnW, BTN_H,
                    deployLabel,
                    () -> selectDeploymentPoint(b.pos, cmd)
                );
                btn.setIcon(b.isRally() ? RALLY_ICON : HAB_ICON,
                    b.isRally() ? 256 : 128, 128);
                btn.setEnabled(waitingForDeploySelection);
                registerDeployButton(btn, b.pos, cmd, deployLabel);
            }
            row++;
        }

        confirmDeployButton = new EspButton(
            sx, sy + areaH - confirmH, areaW, confirmH,
            "\u00a77选择部署点",
            this::confirmDeploymentPoint
        );
        confirmDeployButton.setEnabled(false);
        root.addChild(confirmDeployButton);
    }

    private void registerDeployButton(EspButton button, String positionText, String command, String baseLabel) {
        deployScrollList.addChild(button);
        deployButtons.add(button);
        deployButtonPositions.put(button, positionText);
        deployButtonCommands.put(button, command);
        deployButtonBaseLabels.put(button, baseLabel);
        button.setLabel(buildDeployButtonLabel(baseLabel, positionText, command));
    }

    private int getRedeployCooldownRemaining() {
        long remainingMillis = outpostRedeployCooldownEndsAt - System.currentTimeMillis();
        return remainingMillis <= 0 ? 0 : (int) ((remainingMillis + 999L) / 1000L);
    }

    private void selectDeploymentPoint(String positionText, String command) {
        if (!waitingForDeploySelection) {
            return;
        }

        pendingDeployPosition = positionText;
        pendingDeployCommand = command;
        updateSelectedDeploymentPoint(positionText);
        refreshDeployButtonLabels();
        if (confirmDeployButton != null) {
            confirmDeployButton.setLabel("\u00a7a部署");
            confirmDeployButton.setEnabled(true);
        }
    }

    private void confirmDeploymentPoint() {
        if (!waitingForDeploySelection || pendingDeployCommand == null) {
            return;
        }
        double[] coordinates = parseDeploymentCoordinates(pendingDeployPosition);
        if (coordinates != null) {
            HcrTacticalMapBridge.setSelectedDeploymentPoint(coordinates[0], coordinates[1]);
        } else {
            HcrTacticalMapBridge.clearSelectedDeploymentPoint();
        }

        var player = Minecraft.getInstance().player;
        if (player != null) {
            player.connection.sendCommand(pendingDeployCommand);
            waitingForDeploySelection = false;
            clearPendingDeploySelection();
            refreshDeployButtonStates();
        }
    }

    private void updateSelectedDeploymentPoint(String positionText) {
        double[] coordinates = parseDeploymentCoordinates(positionText);
        if (coordinates != null) {
            HcrTacticalMapBridge.setSelectedDeploymentPoint(coordinates[0], coordinates[1]);
        } else {
            HcrTacticalMapBridge.clearSelectedDeploymentPoint();
        }
    }

    private boolean isPendingDeploySelection(String positionText, String command) {
        return Objects.equals(pendingDeployPosition, positionText)
            && Objects.equals(pendingDeployCommand, command);
    }

    private void clearPendingDeploySelection() {
        pendingDeployPosition = null;
        pendingDeployCommand = null;
        HcrTacticalMapBridge.clearSelectedDeploymentPoint();
        if (confirmDeployButton != null) {
            confirmDeployButton.setLabel("\u00a77选择部署点");
            confirmDeployButton.setEnabled(false);
        }
    }

    private static double[] parseDeploymentCoordinates(String positionText) {
        if (positionText == null || positionText.isBlank()) {
            return null;
        }

        Matcher matcher = COORDINATE_PATTERN.matcher(positionText);
        double[] values = new double[3];
        int count = 0;
        while (matcher.find() && count < values.length) {
            try {
                values[count++] = Double.parseDouble(matcher.group());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return count >= 3 ? new double[] {values[0], values[2]} : null;
    }

    private String buildRedeployLabel() {
        int remaining = getRedeployCooldownRemaining();
        return remaining > 0 ? "\u00a77重新部署 " + remaining + "s" : "\u00a7c重新部署";
    }

    @Override
    public void tick() {
        super.tick();
        if (outpostRedeployButton != null) {
            outpostRedeployButton.setLabel(buildRedeployLabel());
            outpostRedeployButton.setEnabled(
                !waitingForDeploySelection && getRedeployCooldownRemaining() == 0);
        }
    }

    @Override
    public void removed() {
        clearPendingDeploySelection();
        super.removed();
    }

    // ---------- 战术地图（右半屏，由 HCR AAD / ESPoints 绘制）----------
    private void buildMapPanel() {
        // 地图内容在 render() 中通过反射调用 ESPoints 的 TacticalMapHUD 绘制。
    }

    // ---------- 底部状态栏 ----------
    private void buildStatusBar() {
        int barY = this.height - STATUS_BAR_H;
        root.addChild(new GuiRect(0, barY, this.width, STATUS_BAR_H, 0xF016191B));

        statusText = new PlainText(5, barY + 3, buildStatusText(), BTN_TEXT);
        root.addChild(statusText);

        boolean hasOutpost = bastions.stream().anyMatch(UnifiedDeployScreenPacket.BastionItem::isOutpost);
        if (deployTimeRemaining >= 0 && "DEFEND".equals(team) && hasOutpost) {
            int redeployW = 66;
            outpostRedeployButton = new EspButton(
                this.width - redeployW - 4, barY + 1, redeployW, STATUS_BAR_H - 2,
                buildRedeployLabel(),
                () -> { var p = Minecraft.getInstance().player; if (p != null) p.connection.sendCommand("outpost redeploy"); }
            );
            outpostRedeployButton.setEnabled(
                !waitingForDeploySelection && getRedeployCooldownRemaining() == 0);
            root.addChild(outpostRedeployButton);
        }
    }

    private String buildDeployTitle() {
        return "\u00a76\u00a7l部署点";
    }

    private String formatTime(int seconds) {
        int safeSeconds = Math.max(0, seconds);
        return safeSeconds > 60
            ? (safeSeconds / 60) + ":" + String.format("%02d", safeSeconds % 60)
            : safeSeconds + "s";
    }

    private String buildStatusText() {
        String teamColor = "ATTACK".equals(team) ? "\u00a7c" : "\u00a79";
        String teamName = "ATTACK".equals(team) ? "进攻方" : "防守方";
        String squadStr = "";
        for (var squad : squads) {
            if (squad.id == mySquadId) {
                squadStr = " | \u00a7a" + squad.name;
                break;
            }
        }
        return teamColor + teamName + squadStr + " \u00a7f| " + factionName;
    }

    private void refreshDeployButtonStates() {
        if (!waitingForDeploySelection) {
            clearPendingDeploySelection();
        }
        for (EspButton button : deployButtons) {
            button.setEnabled(waitingForDeploySelection);
        }
        refreshDeployButtonLabels();
        if (outpostRedeployButton != null) {
            outpostRedeployButton.setLabel(buildRedeployLabel());
            outpostRedeployButton.setEnabled(
                !waitingForDeploySelection && getRedeployCooldownRemaining() == 0);
        }
    }

    private void refreshDeployButtonLabels() {
        for (EspButton button : deployButtons) {
            String baseLabel = deployButtonBaseLabels.get(button);
            if (baseLabel == null) {
                continue;
            }
            button.setLabel(buildDeployButtonLabel(
                baseLabel,
                deployButtonPositions.get(button),
                deployButtonCommands.get(button)
            ));
        }
    }

    private String buildDeployButtonLabel(String baseLabel, String positionText, String command) {
        if (!isPendingDeploySelection(positionText, command)) {
            return baseLabel;
        }

        String suffix = " \u00a7a\u25b6";
        int coordinateStart = baseLabel.indexOf(" \u00a77(");
        if (coordinateStart >= 0) {
            return baseLabel.substring(0, coordinateStart) + suffix + baseLabel.substring(coordinateStart);
        }
        return baseLabel + suffix;
    }

    // ==================== 渲染 ====================

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        EspetroMutilWidgets.drawScreenShade(graphics, this.width, this.height);
        computeRegions();
        root.updateFocusState(0, 0, mouseX, mouseY);
        root.draw(graphics, 0, 0, this.width, this.height, mouseX, mouseY, partialTick);
        renderTacticalMap(graphics, partialTick);
        renderClassTooltip(graphics, mouseX, mouseY);
        renderVariantPopup(graphics, mouseX, mouseY);
    }

    private void renderTacticalMap(GuiGraphics graphics, float partialTick) {
        HcrTacticalMapBridge.renderEmbeddedMap(
            graphics,
            mapX,
            mapY,
            mapW,
            mapH,
            partialTick
        );
        graphics.renderOutline(mapX, mapY, mapW, mapH, 0x805B6260);
    }

    private void renderClassTooltip(GuiGraphics graphics, int mx, int my) {
        if (variantPopupClassIndex >= 0) return;
        for (int i = 0; i < classButtons.size() && i < classes.size(); i++) {
            EspButton btn = classButtons.get(i);
            if (btn.hovered && btn.isEnabled()) {
                var cls = classes.get(i);
                List<String> lines = new ArrayList<>();
                lines.add("\u00a76\u00a7l" + cls.name);
                if (cls.role != null && !cls.role.isBlank()) {
                    lines.add("\u00a7f" + cls.role);
                }
                if (cls.description != null && !cls.description.isBlank()) {
                    lines.add("\u00a77" + cls.description);
                }
                String bonuses = "";
                if (cls.healthBonus != 0) {
                    bonuses += "\u00a7c\u2764 +" + cls.healthBonus;
                }
                if (cls.speedBonus != 0) {
                    bonuses += (bonuses.isEmpty() ? "" : "  ")
                        + "\u00a7b\u26a1 +" + String.format("%.1f", cls.speedBonus);
                }
                if (!bonuses.isEmpty()) {
                    lines.add(bonuses);
                }
                int c = classCounts.getOrDefault(cls.classId, 0);
                lines.add((c >= cls.maxPlayers ? "\u00a7c" : "\u00a7a")
                    + c + "/" + cls.maxPlayers + " \u00a77\u00b7兵力" + cls.troopValue);

                int lineH = Math.max(6,
                    Math.round(this.font.lineHeight * TOOLTIP_TEXT_SCALE) + 1);
                int pw = 132;
                int ph = 5 + lines.size() * lineH;
                int px = mx + 8;
                int py = my - ph / 2;
                if (px + pw > this.width) px = mx - pw - 8;
                if (py < 3) py = 3;
                if (py + ph > this.height - STATUS_BAR_H) {
                    py = this.height - STATUS_BAR_H - ph - 2;
                }

                graphics.fill(px, py, px + pw, py + ph, 0xDD111122);
                graphics.renderOutline(px, py, pw, ph, 0xFF555577);

                int logicalWidth = Math.max(8, (int) ((pw - 7) / TOOLTIP_TEXT_SCALE));
                int ty = py + 3;
                for (String line : lines) {
                    drawScaledString(graphics,
                        EspetroMutilWidgets.trimToWidth(line, logicalWidth),
                        px + 4, ty, BTN_TEXT, TOOLTIP_TEXT_SCALE);
                    ty += lineH;
                }
                break;
            }
        }
    }

    private void renderVariantPopup(GuiGraphics graphics, int mouseX, int mouseY) {
        if (!hasVariantPopup()) return;
        UnifiedDeployScreenPacket.ClassInfo cls = classes.get(variantPopupClassIndex);
        List<UnifiedDeployScreenPacket.VariantInfo> variants = cls.variants;
        int visible = Math.min(VARIANT_MAX_VISIBLE, variants.size());

        graphics.fill(variantPopupX, variantPopupY,
            variantPopupX + VARIANT_POPUP_W, variantPopupY + variantPopupH, 0xF0111418);
        graphics.renderOutline(variantPopupX, variantPopupY,
            VARIANT_POPUP_W, variantPopupH, 0xFFE8B85C);
        graphics.drawString(this.font, Component.literal("§6§l" + cls.name + " §7装备变体"),
            variantPopupX + 6, variantPopupY + 5, BTN_TEXT, false);

        int closeX = variantPopupX + VARIANT_POPUP_W - 16;
        int closeY = variantPopupY + 2;
        boolean closeHovered = inside(mouseX, mouseY, closeX, closeY, 13, 13);
        graphics.fill(closeX, closeY, closeX + 13, closeY + 13,
            closeHovered ? 0xFFD05A5A : 0xFF553535);
        graphics.drawCenteredString(this.font, Component.literal("§fX"), closeX + 6, closeY + 2, BTN_TEXT);

        for (int row = 0; row < visible; row++) {
            int variantIndex = variantPopupScroll + row;
            if (variantIndex >= variants.size()) break;
            UnifiedDeployScreenPacket.VariantInfo variant = variants.get(variantIndex);
            int rowX = variantPopupX + 3;
            int rowY = variantPopupY + VARIANT_HEADER_H + row * VARIANT_ROW_H;
            int rowW = VARIANT_POPUP_W - 6;
            int count = variantCounts
                .getOrDefault(cls.classId, Collections.emptyMap())
                .getOrDefault(variant.variantId, variant.currentCount);
            boolean full = count >= variant.maxPlayers;
            boolean hovered = inside(mouseX, mouseY, rowX, rowY, rowW, VARIANT_ROW_H - 1);
            int fill = full ? 0xD02B2025 : hovered ? 0xE0435145 : 0xD01B1E20;
            graphics.fill(rowX, rowY, rowX + rowW, rowY + VARIANT_ROW_H - 1, fill);
            graphics.renderOutline(rowX, rowY, rowW, VARIANT_ROW_H - 1,
                hovered && !full ? 0xFFB7C9B8 : 0x8059605E);

            String countText = (full ? "§c" : "§a") + "[" + count + "/" + variant.maxPlayers + "]";
            graphics.drawString(this.font, Component.literal((full ? "§8" : "§f") + variant.name),
                rowX + 5, rowY + 4, BTN_TEXT, false);
            int countW = this.font.width(EspetroMutilWidgets.stripFormatting(countText));
            graphics.drawString(this.font, Component.literal(countText),
                rowX + rowW - countW - 5, rowY + 4, BTN_TEXT, false);
            if (variant.description != null && !variant.description.isBlank()) {
                graphics.drawString(this.font, Component.literal("§7" +
                    EspetroMutilWidgets.trimToWidth(variant.description, rowW - 10)),
                    rowX + 5, rowY + 16, EspetroMutilWidgets.DIM, false);
            }
        }

        if (variants.size() > VARIANT_MAX_VISIBLE) {
            graphics.drawString(this.font, Component.literal("§8滚轮浏览"),
                variantPopupX + VARIANT_POPUP_W - 49, variantPopupY + variantPopupH - 10,
                EspetroMutilWidgets.DIM, false);
        }
    }

    // ==================== 输入事件 ====================

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        lastMouseX = (int) mx;
        lastMouseY = (int) my;
        if (hasVariantPopup()) {
            if (button == 0 && handleVariantPopupClick((int) mx, (int) my)) {
                return true;
            }
            // 点击菜单外也关闭，并消费本次点击，避免穿透到底层职业按钮。
            closeVariantPopup();
            return true;
        }
        root.updateFocusState(0, 0, (int) mx, (int) my);
        if (root.onMouseClick((int) mx, (int) my, button))
            return true;
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        root.onMouseRelease((int) mx, (int) my, button);
        return super.mouseReleased(mx, my, button);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double delta) {
        if (hasVariantPopup() && inside((int) mx, (int) my,
            variantPopupX, variantPopupY, VARIANT_POPUP_W, variantPopupH)) {
            int maxScroll = Math.max(0,
                classes.get(variantPopupClassIndex).variants.size() - VARIANT_MAX_VISIBLE);
            variantPopupScroll = Math.max(0, Math.min(maxScroll,
                variantPopupScroll + (delta < 0 ? 1 : -1)));
            return true;
        }
        root.updateFocusState(0, 0, (int) mx, (int) my);
        if (root.onMouseScroll(mx, my, delta)) {
            return true;
        }
        return super.mouseScrolled(mx, my, delta);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ESCAPE && hasVariantPopup()) {
            closeVariantPopup();
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_C) {
            HcrTacticalMapBridge.increaseRenderRange();
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_B) {
            HcrTacticalMapBridge.decreaseRenderRange();
            return true;
        }
        if (root.onKeyPress(keyCode, scanCode, modifiers)) return true;
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean keyReleased(int keyCode, int scanCode, int modifiers) {
        if (root.onKeyRelease(keyCode, scanCode, modifiers)) return true;
        return super.keyReleased(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        if (root.onCharType(codePoint, modifiers)) return true;
        return super.charTyped(codePoint, modifiers);
    }

    // ==================== 业务逻辑 ====================

    private void refreshClassButtons() {
        for (int i = 0; i < classButtons.size() && i < classes.size(); i++) {
            var cls = classes.get(i);
            int count = classCounts.getOrDefault(cls.classId, 0);
            boolean full = count >= cls.maxPlayers;
            String color = full ? "\u00a7c" : "\u00a7a";
            classButtons.get(i).setLabel("\u00a7f" + cls.name);
            classButtons.get(i).setRightLabel(color + "[" + count + "/" + cls.maxPlayers + "]");
            classButtons.get(i).setEnabled(!full);
            if (full) { classButtons.get(i).normalColor = 0xB0252035; classButtons.get(i).hoverColor = 0xD0403050; }
            else { classButtons.get(i).normalColor = BTN_BG_NORMAL; classButtons.get(i).hoverColor = BTN_BG_HOVER; }
        }
    }

    private void selectClass(int index) {
        if (index >= 0 && index < classes.size()) {
            var cls = classes.get(index);
            int count = classCounts.getOrDefault(cls.classId, 0);
            if (count >= cls.maxPlayers) return;
            if (cls.variants.size() == 1) {
                ClassSelectionGui.selectClass(factionId, cls.classId, cls.variants.get(0).variantId);
            } else if (cls.variants.size() > 1) {
                openVariantPopup(index, lastMouseX, lastMouseY);
            }
        }
    }

    private void openVariantPopup(int classIndex, int mouseX, int mouseY) {
        variantPopupClassIndex = classIndex;
        variantPopupScroll = 0;
        int count = classes.get(classIndex).variants.size();
        int visible = Math.min(VARIANT_MAX_VISIBLE, count);
        int footer = count > VARIANT_MAX_VISIBLE ? 10 : 3;
        variantPopupH = VARIANT_HEADER_H + visible * VARIANT_ROW_H + footer;
        variantPopupX = mouseX + 9;
        if (variantPopupX + VARIANT_POPUP_W > this.width - 3) {
            variantPopupX = mouseX - VARIANT_POPUP_W - 9;
        }
        variantPopupX = Math.max(3, Math.min(this.width - VARIANT_POPUP_W - 3, variantPopupX));
        variantPopupY = Math.max(3,
            Math.min(this.height - STATUS_BAR_H - variantPopupH - 2, mouseY + 7));
    }

    private boolean handleVariantPopupClick(int mouseX, int mouseY) {
        int closeX = variantPopupX + VARIANT_POPUP_W - 16;
        int closeY = variantPopupY + 2;
        if (inside(mouseX, mouseY, closeX, closeY, 13, 13)) {
            closeVariantPopup();
            return true;
        }
        if (!inside(mouseX, mouseY, variantPopupX, variantPopupY,
            VARIANT_POPUP_W, variantPopupH)) {
            closeVariantPopup();
            return true;
        }

        UnifiedDeployScreenPacket.ClassInfo cls = classes.get(variantPopupClassIndex);
        int row = (mouseY - (variantPopupY + VARIANT_HEADER_H)) / VARIANT_ROW_H;
        if (mouseY >= variantPopupY + VARIANT_HEADER_H && row >= 0 && row < VARIANT_MAX_VISIBLE) {
            int variantIndex = variantPopupScroll + row;
            if (variantIndex < cls.variants.size()) {
                UnifiedDeployScreenPacket.VariantInfo variant = cls.variants.get(variantIndex);
                int count = variantCounts.getOrDefault(cls.classId, Collections.emptyMap())
                    .getOrDefault(variant.variantId, variant.currentCount);
                if (count < variant.maxPlayers) {
                    ClassSelectionGui.selectClass(factionId, cls.classId, variant.variantId);
                    closeVariantPopup();
                }
            }
        }
        return true;
    }

    private boolean hasVariantPopup() {
        return variantPopupClassIndex >= 0 && variantPopupClassIndex < classes.size();
    }

    private void closeVariantPopup() {
        variantPopupClassIndex = -1;
        variantPopupScroll = 0;
    }

    private static boolean inside(int x, int y, int left, int top, int width, int height) {
        return x >= left && x < left + width && y >= top && y < top + height;
    }

    @Override
    public boolean shouldCloseOnEsc() {
        // 即使仍在等待选择部署点，也允许玩家暂时关闭面板查看游戏画面；
        // 选择状态保存在服务端，之后可按 J 重新打开并继续。
        return true;
    }

    @Override
    public boolean isPauseScreen() { return false; }
}
