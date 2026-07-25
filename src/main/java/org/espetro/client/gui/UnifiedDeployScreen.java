package org.espetro.client.gui;

import se.mickelus.mutil.gui.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.espetro.client.HcrTacticalMapBridge;
import org.espetro.network.NetworkManager;
import org.espetro.network.UnifiedDeployScreenPacket;
import org.espetro.network.GovernanceStatePacket;
import org.espetro.network.GovernanceActionPacket;
import org.espetro.team.GamePhase;
import org.lwjgl.glfw.GLFW;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 统一部署/复活主界面 — 基于 mutil GuiElement 树架构
 *
 * Squad-style layout: squads | roles and spawn points | tactical map.
 */
public class UnifiedDeployScreen extends MutilScreen {

    private static final int BTN_H = 12;
    private static final int CLASS_BTN_H = 22;
    private static final int CLASS_ICON_SIZE = 17;
    private static final int TITLE_H = 35;
    private static final int STATUS_BAR_H = 13;
    private static final int MAP_FOOTER_H = BTN_H + 8;
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

    // EspButton 颜色：全不透明，避免 isPauseScreen=false 时世界从按钮透出（Embeddium 叠影）。
    private static final int BTN_BG_NORMAL   = 0xFF1B1E20;
    private static final int BTN_BG_HOVER    = 0xFF435145;
    private static final int BTN_BG_DISABLED = 0xFF181B1D;
    private static final int BTN_BORDER      = 0xFF59605E;
    private static final int BTN_TEXT        = 0xFFFFFF;
    /** 标题/底栏/列面板：完全不透明，挡住 isPauseScreen=false 时透出的世界（防 Embeddium 叠影）。 */
    private static final int CHROME_BG       = 0xFF16191B;
    private static final int PANEL_LEFT_BG   = 0xFF171A1C;
    private static final int PANEL_CENTER_BG = 0xFF121517;
    private static final int PANEL_MAP_FOOTER_BG = 0xFF101416;
    /** 全屏深黑灰遮罩：J 键主 GUI 专用，阶段投票 UI 不走此路径。 */
    private static final int SCREEN_SHADE    = 0xFF121517;
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
    private final List<UnifiedDeployScreenPacket.SquadCategoryInfo> squadCategories;
    private int mySquadId;
    private int deployTimeRemaining;
    /** 上次从服务端同步倒计时时的 epoch ms / 秒数，用于本地每秒递减，避免依赖全量包刷新。 */
    private long deployTimerAnchorMs;
    private int deployTimerAnchorSeconds;
    private final String team;
    private boolean waitingForDeploySelection;
    private long outpostRedeployCooldownEndsAt;
    private GovernanceStatePacket governanceState = new GovernanceStatePacket(List.of());
    private long governanceReceivedAtMs;
    /** 上一帧已刷新过的 epoch 秒，用于把 Rally/冷却文案节流到每秒一次。 */
    private int lastDynamicLabelEpochSec = -1;
    /** 同 tick 内多次结构更新只 rebuild 一次。 */
    private boolean structureRebuildQueued;

    // ===== 按钮引用 =====
    private final List<EspButton> classButtons = new ArrayList<>();
    private final List<EspButton> deployButtons = new ArrayList<>();
    private final Map<EspButton, String> deployButtonPositions = new HashMap<>();
    private final Map<EspButton, String> deployButtonCommands = new HashMap<>();
    private final Map<EspButton, String> deployButtonBaseLabels = new HashMap<>();
    /** Rally 行：按钮 → 个人就绪 epoch ms；非 Rally 为 0。 */
    private final Map<EspButton, Long> deployButtonNextWaveAt = new HashMap<>();
    /** 构建部署行时使用的名称片段（不含动态 [波次 Xs]）。 */
    private final Map<EspButton, String> deployButtonNameCores = new HashMap<>();
    /** Rally 行：冷却总秒数 m（用于 n/m 显示）。 */
    private final Map<EspButton, Integer> deployButtonWaveSeconds = new HashMap<>();
    private EspButton outpostRedeployButton;
    private EspButton confirmDeployButton;
    private PlainText statusText;
    private PlainText deployTitleText;
    private PlainText statusTimerText;
    private PlainText governanceTimerText;
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

    // ===== 职业装备人物预览 =====
    /** 预览渲染器实例（懒初始化虚拟玩家实体）。 */
    private final ClassPreviewRenderer previewRenderer = new ClassPreviewRenderer();
    /** 当前正在显示的人物预览（class index + variantId）；-1/null 表示无预览。 */
    private int activePreviewClassIndex = -1;
    private String activePreviewVariantId = null;
    /** 鼠标离开所有预览目标后的宽限截止时刻（epoch ms）；0 表示未在宽限中。 */
    private long previewGraceDeadlineMs = 0L;
    /** 宽限过短时，鼠标在职业格间移动会频繁在战术地图/预览间切换，右侧整块闪烁。 */
    private static final long PREVIEW_GRACE_MS = 350L;

    // ===== 滚轮列表 =====
    private ScrollableList classScrollList;
    private ScrollableList deployScrollList;
    private ScrollableList squadScrollList;
    private Double pendingSquadScrollOffset;
    private Double pendingDeployScrollOffset;

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
        this.classes = new ArrayList<>(data.getClasses());
        this.classCounts = new HashMap<>(data.getClassCounts());
        replaceVariantCounts(data.getVariantCounts());
        this.hasDeployPoint = data.hasDeployPoint();
        this.deployPointPos = data.getDeployPointPos();
        this.bastions = new ArrayList<>(data.getBastions() == null ? List.of() : data.getBastions());
        this.bastions.sort(Comparator
            .comparing((UnifiedDeployScreenPacket.BastionItem b) -> b.type == null ? "" : b.type)
            .thenComparing(b -> b.id == null ? "" : b.id.toString()));
        this.isCommander = data.isCommander();
        this.squads = new ArrayList<>(data.getSquads());
        this.squadCategories = new ArrayList<>(data.getSquadCategories());
        this.mySquadId = data.getMySquadId();
        this.team = data.getTeam();
        this.waitingForDeploySelection = data.isWaitingForDeploySelection();
        this.outpostRedeployCooldownEndsAt = System.currentTimeMillis()
            + data.getOutpostRedeployCooldownRemaining() * 1000L;
        anchorDeployTimer(data.getDeployTimeRemaining());
    }

    private void anchorDeployTimer(int seconds) {
        this.deployTimeRemaining = seconds;
        this.deployTimerAnchorSeconds = seconds;
        this.deployTimerAnchorMs = System.currentTimeMillis();
    }

    /**
     * 同步部署点列表。结构不变时只更新 Rally 个人冷却时间戳并改 label，避免 rebuild 高度闪烁。
     */
    public void updateBastions(List<UnifiedDeployScreenPacket.BastionItem> nextBastions) {
        List<UnifiedDeployScreenPacket.BastionItem> next = nextBastions == null
            ? new ArrayList<>() : new ArrayList<>(nextBastions);
        // 固定顺序，避免 Hash 遍历导致「结构变化」误 rebuild。
        next.sort(Comparator
            .comparing((UnifiedDeployScreenPacket.BastionItem b) -> b.type == null ? "" : b.type)
            .thenComparing(b -> b.id == null ? "" : b.id.toString()));
        boolean structureChanged = bastions.size() != next.size();
        if (!structureChanged) {
            for (int i = 0; i < bastions.size(); i++) {
                UnifiedDeployScreenPacket.BastionItem a = bastions.get(i);
                UnifiedDeployScreenPacket.BastionItem b = next.get(i);
                if (!Objects.equals(a.id, b.id)
                    || !Objects.equals(a.type, b.type)
                    || !Objects.equals(a.name, b.name)
                    || !Objects.equals(a.pos, b.pos)) {
                    structureChanged = true;
                    break;
                }
            }
        }
        bastions.clear();
        bastions.addAll(next);
        if (structureChanged) {
            if (root != null) {
                rebuildGuiPreservingDeployAndSquadScroll();
            }
            return;
        }
        // 结构相同：只刷新 Rally 时间戳与 label。
        Map<java.util.UUID, Long> waveById = new HashMap<>();
        Map<java.util.UUID, Integer> totalById = new HashMap<>();
        for (UnifiedDeployScreenPacket.BastionItem item : bastions) {
            waveById.put(item.id, item.nextWaveAtEpochMs);
            totalById.put(item.id, item.waveSeconds);
        }
        for (EspButton button : deployButtons) {
            String command = deployButtonCommands.get(button);
            if (command == null || !command.startsWith("bastion select ")) {
                continue;
            }
            String idText = command.substring("bastion select ".length()).trim();
            try {
                java.util.UUID id = java.util.UUID.fromString(idText);
                Long wave = waveById.get(id);
                if (wave != null) {
                    if (wave > 0L) {
                        deployButtonNextWaveAt.put(button, wave);
                    } else {
                        deployButtonNextWaveAt.remove(button);
                    }
                }
                Integer total = totalById.get(id);
                if (total != null && total > 0) {
                    deployButtonWaveSeconds.put(button, total);
                }
            } catch (IllegalArgumentException ignored) {
            }
        }
        refreshRallyWaveLabels();
        refreshConfirmDeployButton();
    }

    private void rebuildGuiPreservingDeployAndSquadScroll() {
        pendingSquadScrollOffset = squadScrollList != null ? squadScrollList.getOffset() : 0;
        pendingDeployScrollOffset = deployScrollList != null ? deployScrollList.getOffset() : 0;
        queueStructureRebuild();
    }

    public void updateClassCounts(Map<String, Integer> counts) {
        updateClassCounts(counts, null);
    }

    public void updateClassCounts(Map<String, Integer> counts,
                                  Map<String, Map<String, Integer>> updatedVariantCounts) {
        boolean countsChanged = counts != null && !counts.equals(this.classCounts);
        boolean variantsChanged = updatedVariantCounts != null
            && !variantCountsEqual(this.variantCounts, updatedVariantCounts);
        if (!countsChanged && !variantsChanged) {
            return;
        }
        if (countsChanged) {
            this.classCounts.clear();
            this.classCounts.putAll(counts);
        }
        if (variantsChanged) {
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

    private static boolean variantCountsEqual(Map<String, Map<String, Integer>> a,
                                              Map<String, Map<String, Integer>> b) {
        if (a == b) return true;
        if (a == null || b == null || a.size() != b.size()) return false;
        for (Map.Entry<String, Map<String, Integer>> e : a.entrySet()) {
            Map<String, Integer> other = b.get(e.getKey());
            if (other == null || !other.equals(e.getValue())) {
                return false;
            }
        }
        return true;
    }

    /**
     * 同步职业元数据与对玩家有效的人数（含 team_count / max_per_squad / 小队当前人数）。
     * 不 rebuild 整页，只刷新按钮 label/enabled；仅职业格子数量/id 变化时重建网格。
     */
    public void updateClasses(List<UnifiedDeployScreenPacket.ClassInfo> nextClasses,
                              Map<String, Integer> counts,
                              Map<String, Map<String, Integer>> updatedVariantCounts) {
        boolean countsChanged = counts != null && !counts.equals(this.classCounts);
        boolean variantsChanged = updatedVariantCounts != null
            && !variantCountsEqual(this.variantCounts, updatedVariantCounts);
        boolean classDataChanged = false;
        boolean classGridChanged = false;

        if (nextClasses != null) {
            classGridChanged = !classGridEquals(this.classes, nextClasses);
            classDataChanged = !classDisplayStateEquals(this.classes, nextClasses);
            if (classGridChanged || classDataChanged) {
                this.classes.clear();
                this.classes.addAll(nextClasses);
            }
        }
        if (countsChanged) {
            this.classCounts.clear();
            this.classCounts.putAll(counts);
        }
        if (variantsChanged) {
            replaceVariantCounts(updatedVariantCounts);
        }
        if (!classGridChanged && !classDataChanged && !countsChanged && !variantsChanged) {
            return;
        }
        if (classGridChanged && root != null) {
            rebuildGuiPreservingDeployAndSquadScroll();
            return;
        }
        refreshClassButtons();
    }

    /** 职业按钮网格结构（数量 + classId 顺序）。 */
    private static boolean classGridEquals(
            List<UnifiedDeployScreenPacket.ClassInfo> a,
            List<UnifiedDeployScreenPacket.ClassInfo> b) {
        if (a == b) return true;
        if (a == null || b == null || a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            if (!Objects.equals(a.get(i).classId, b.get(i).classId)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 影响按钮 label/enabled 的展示态是否一致（含小队当前人数、上限、图标名）。
     */
    private static boolean classDisplayStateEquals(
            List<UnifiedDeployScreenPacket.ClassInfo> a,
            List<UnifiedDeployScreenPacket.ClassInfo> b) {
        if (a == b) return true;
        if (a == null || b == null || a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            UnifiedDeployScreenPacket.ClassInfo x = a.get(i);
            UnifiedDeployScreenPacket.ClassInfo y = b.get(i);
            if (x == y) continue;
            if (x == null || y == null) return false;
            if (x.maxPlayers != y.maxPlayers
                || x.strictCount != y.strictCount
                || x.teamCount != y.teamCount
                || x.maxPerSquad != y.maxPerSquad
                || x.squadCurrentCount != y.squadCurrentCount
                || !Objects.equals(x.classId, y.classId)
                || !Objects.equals(x.name, y.name)
                || !Objects.equals(x.icon, y.icon)
                || !Objects.equals(x.iconImage, y.iconImage)
                || x.variants.size() != y.variants.size()) {
                return false;
            }
            for (int v = 0; v < x.variants.size(); v++) {
                UnifiedDeployScreenPacket.VariantInfo vx = x.variants.get(v);
                UnifiedDeployScreenPacket.VariantInfo vy = y.variants.get(v);
                if (!Objects.equals(vx.variantId, vy.variantId)
                    || vx.maxPlayers != vy.maxPlayers
                    || vx.currentCount != vy.currentCount
                    || !Objects.equals(vx.name, vy.name)) {
                    return false;
                }
            }
        }
        return true;
    }

    public void updateTimeRemaining(int seconds) {
        anchorDeployTimer(seconds);
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

    public boolean isWaitingForDeploySelection() {
        return waitingForDeploySelection;
    }

    public void updateSquads(List<UnifiedDeployScreenPacket.SquadInfo> updatedSquads, int updatedMySquadId) {
        List<UnifiedDeployScreenPacket.SquadInfo> nextSquads = updatedSquads == null
            ? List.of() : updatedSquads;
        // 完全一致：无需任何更新。
        if (this.mySquadId == updatedMySquadId && this.squads.equals(nextSquads)) {
            return;
        }
        // 仅成员职业名等展示态变化：更新内存，不 rebuild 整页（避免选职时高度闪烁）。
        boolean structureChanged = this.mySquadId != updatedMySquadId
            || !squadListStructureEquals(this.squads, nextSquads);
        this.squads.clear();
        this.squads.addAll(nextSquads);
        Set<Integer> availableSquadIds = new HashSet<>();
        for (UnifiedDeployScreenPacket.SquadInfo squad : this.squads) {
            availableSquadIds.add(squad.id);
        }
        expandedSquadIds.retainAll(availableSquadIds);
        this.mySquadId = updatedMySquadId;
        if (root == null) {
            return;
        }
        if (structureChanged) {
            rebuildGuiPreservingSquadScroll();
        }
        // 结构未变时只刷职业按钮（小队作用域人数/禁用态）；结构变时 rebuild 已重建职业区。
        refreshClassButtons();
    }

    public void updateGovernance(GovernanceStatePacket packet) {
        GovernanceStatePacket.TeamState previous = activeGovernance();
        governanceState = packet == null ? new GovernanceStatePacket(List.of()) : packet;
        governanceReceivedAtMs = System.currentTimeMillis();
        GovernanceStatePacket.TeamState current = activeGovernance();
        if (root != null && !sameGovernanceLayout(previous, current)) {
            rebuildGuiPreservingSquadScroll();
        } else if (governanceTimerText != null && current != null) {
            governanceTimerText.setText("\u00a7e剩余 " + current.remainingSeconds + "s");
        }
    }

    private static boolean sameGovernanceLayout(GovernanceStatePacket.TeamState a,
                                                GovernanceStatePacket.TeamState b) {
        if (a == b) return true;
        if (a == null || b == null) return false;
        return Objects.equals(a.state, b.state)
            && Objects.equals(a.commander, b.commander)
            && Objects.equals(a.challenger, b.challenger)
            && Objects.equals(a.volunteers, b.volunteers);
    }

    /**
     * 小队列表结构是否相同（忽略成员 className，避免选职导致整页 rebuild 闪烁）。
     * 比较：小队 id/名/队长/人数上限/锁定，以及成员 playerName + leader/commander 顺序。
     */
    private static boolean squadListStructureEquals(
            List<UnifiedDeployScreenPacket.SquadInfo> a,
            List<UnifiedDeployScreenPacket.SquadInfo> b) {
        if (a == b) {
            return true;
        }
        if (a == null || b == null || a.size() != b.size()) {
            return false;
        }
        for (int i = 0; i < a.size(); i++) {
            if (!squadStructureEquals(a.get(i), b.get(i))) {
                return false;
            }
        }
        return true;
    }

    private static boolean squadStructureEquals(
            UnifiedDeployScreenPacket.SquadInfo a,
            UnifiedDeployScreenPacket.SquadInfo b) {
        if (a == b) {
            return true;
        }
        if (a == null || b == null) {
            return false;
        }
        if (a.id != b.id
            || a.memberCount != b.memberCount
            || a.maxMembers != b.maxMembers
            || a.isLocked != b.isLocked
            || !Objects.equals(a.name, b.name)
            || !Objects.equals(a.leaderName, b.leaderName)
            || !Objects.equals(a.categoryId, b.categoryId)
            || !Objects.equals(a.categoryDisplayName, b.categoryDisplayName)) {
            return false;
        }
        List<UnifiedDeployScreenPacket.SquadMemberInfo> am = a.members;
        List<UnifiedDeployScreenPacket.SquadMemberInfo> bm = b.members;
        if (am == bm) {
            return true;
        }
        if (am == null || bm == null || am.size() != bm.size()) {
            return false;
        }
        for (int i = 0; i < am.size(); i++) {
            UnifiedDeployScreenPacket.SquadMemberInfo x = am.get(i);
            UnifiedDeployScreenPacket.SquadMemberInfo y = bm.get(i);
            if (x == y) {
                continue;
            }
            if (x == null || y == null) {
                return false;
            }
            // 故意忽略 className：队友换职业不应触发整页重建。
            if (x.leader != y.leader
                || x.commander != y.commander
                || !Objects.equals(x.playerName, y.playerName)) {
                return false;
            }
        }
        return true;
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
        void setLabel(String l) {
            String next = l == null ? "" : l;
            if (java.util.Objects.equals(label, next)) {
                return;
            }
            label = next;
        }
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
                hasFocus() ? 0xF0404743 : 0xE0141617);
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
        void setText(String value) {
            String next = value == null ? "" : value;
            if (java.util.Objects.equals(text, next)) {
                return;
            }
            text = next;
        }

        @Override
        public void draw(GuiGraphics graphics, int x, int y, int w, int h, int mx, int my, float tick) {
            if (!isVisible()) return;
            drawScaledString(graphics, text, x + getX(), y + getY(), color, UI_TEXT_SCALE);
        }
    }

    // ==================== 界面构建 ====================

    @Override
    protected void buildMutilRoot(GuiElement root) {
        this.root = root;
        populateGui();
        if (pendingSquadScrollOffset != null && squadScrollList != null) {
            squadScrollList.setOffset(pendingSquadScrollOffset);
        }
        if (pendingDeployScrollOffset != null && deployScrollList != null) {
            deployScrollList.setOffset(pendingDeployScrollOffset);
        }
        pendingSquadScrollOffset = null;
        pendingDeployScrollOffset = null;
        // 打开包已携带服务端最新人数，后续变更也由服务端主动推送，
        // 不再打开界面后立即发起第二次请求。
    }

    private void rebuildGui() {
        rebuildMutilRoot();
    }

    private void populateGui() {
        this.outpostRedeployButton = null;
        this.confirmDeployButton = null;
        this.statusText = null;
        this.deployTitleText = null;
        this.statusTimerText = null;
        this.governanceTimerText = null;
        computeRegions();

        buildTitleBar();
        buildDividerLine(TITLE_H);

        // 左/中列不透明底，挡住背后世界与聊天；地图视口不铺半透明盖层（地图在 renderBefore 绘制）。
        root.addChild(new GuiRect(leftX, leftY, leftW, leftH, PANEL_LEFT_BG));
        root.addChild(new GuiRect(centerX, centerY, centerW, centerH, PANEL_CENTER_BG));
        root.addChild(new GuiRect(mapX, mapY + Math.max(0, mapH - MAP_FOOTER_H),
            mapW, MAP_FOOTER_H, PANEL_MAP_FOOTER_BG));

        buildSquadSection();
        buildClassSection();
        root.addChild(new GuiRect(centerX + 3, deployAreaY - 3, centerW - 6, 1, 0xFF3A3F3D));
        buildDeploySection();

        root.addChild(new GuiRect(leftX + leftW, TITLE_H + 2, 1,
            this.height - TITLE_H - STATUS_BAR_H - 4, 0xFF3A3F3D));
        root.addChild(new GuiRect(centerX + centerW, TITLE_H + 2, 1,
            this.height - TITLE_H - STATUS_BAR_H - 4, 0xFF3A3F3D));

        buildMapPanel();
        buildStatusBar();
    }

    private void rebuildGuiPreservingSquadScroll() {
        pendingSquadScrollOffset = squadScrollList == null ? 0 : squadScrollList.getOffset();
        queueStructureRebuild();
    }

    private void queueStructureRebuild() {
        if (structureRebuildQueued) {
            return;
        }
        structureRebuildQueued = true;
        rebuildGui();
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
        root.addChild(new GuiRect(0, 0, this.width, TITLE_H, CHROME_BG));
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
        root.addChild(new GuiRect(4, y, this.width - 8, 1, 0xFF3A3F3D));
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
            String label = disclosure + " " + state + " \u00a7f" + squad.name
                + " \u00a77" + squad.memberCount + "/" + squad.maxMembers;
            EspButton button = new EspButton(0, rowY, rowW, SQUAD_ROW_H,
                label, () -> toggleSquadExpanded(squad.id));
            button.setTextScale(SQUAD_TEXT_SCALE);
            button.setCenteredText(false);
            // \u884c\u6700\u53f3\u4fa7\u663e\u793a\u5fd7\u613f/\u73ed\u7ec4\u7c7b\u522b\uff08\u521b\u5efa\u65f6\u9009\u62e9\u7684 category\uff09\u3002
            String category = squad.categoryDisplayName;
            if (category != null && !category.isBlank()
                && !"none".equalsIgnoreCase(squad.categoryId)
                && !"\u65e0".equals(category)) {
                button.setRightLabel("\u00a7e" + category);
            }
            if (mine) {
                button.normalColor = 0xFF344939;
                button.hoverColor = 0xFF3C5542;
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
                join.normalColor = 0xFF25352B;
                join.hoverColor = 0xFF3C5542;
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
        try {
            Minecraft.getInstance().setScreen(
                new SquadScreen(new ArrayList<>(squads), mySquadId, team,
                    new ArrayList<>(squadCategories), this));
        } catch (Throwable t) {
            // 避免班组界面类加载失败时拖垮整局客户端。
            org.espetro.Espetro.LOGGER.error("打开班组管理界面失败", t);
            var player = Minecraft.getInstance().player;
            if (player != null) {
                player.displayClientMessage(
                    Component.literal("§c无法打开班组管理界面，请查看日志。"), false);
            }
        }
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
            int count = classCounts.getOrDefault(cls.classId, cls.currentCount);
            boolean disabled = isClassButtonDisabled(cls);
            boolean emphasizeRed = isClassEmphasizeRed(cls, disabled);
            String label = "\u00a7f" + cls.name;
            String right = buildClassCountRightLabel(cls, emphasizeRed || disabled);

            int col = i % cols;
            int row = i / cols;
            int bx = col * (btnW + spacing);
            int by = row * (btnH + spacing);

            final int idx = i;
            EspButton btn = new EspButton(bx, by, btnW, btnH, label, () -> selectClass(idx));
            btn.setIcon(RoleIconResources.resolve(cls.iconImage, cls.icon),
                RoleIconResources.TEXTURE_SIZE, RoleIconResources.TEXTURE_SIZE);
            btn.setIconSize(CLASS_ICON_SIZE);
            btn.setRightLabel(right);
            btn.setCenteredText(false);
            btn.setEnabled(!disabled);
            if (disabled) { btn.hoverColor = 0xF0403050; btn.normalColor = 0xE0252035; }
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
        deployButtonNextWaveAt.clear();
        deployButtonNameCores.clear();
        deployButtonWaveSeconds.clear();
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
            registerDeployButton(btn, deployPointPos, deployCommand, deployLabel, 0L, deployLabel, 0);
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
                registerDeployButton(selectButton, b.pos, deployCmd, deployLabel, 0L, deployLabel, 0);
            } else {
                String cmd = "bastion select " + bid;
                String markerColor = b.isRally() ? "\u00a7a" : "\u00a79";
                String marker = b.isRally() ? "\u2691 " : "\u25a0 ";
                String nameCore = markerColor + marker + b.name;
                long waveAt = b.isRally() ? b.nextWaveAtEpochMs : 0L;
                String statusPart = b.isRally()
                    ? formatWaveStatus(waveAt, b.waveSeconds)
                    : (b.status.isBlank() ? "" : " \u00a77[" + b.status + "]");
                String deployLabel = nameCore + statusPart;
                EspButton btn = new EspButton(
                    2, row * (BTN_H + btnSpacing), btnW, BTN_H,
                    deployLabel,
                    () -> selectDeploymentPoint(b.pos, cmd)
                );
                btn.setIcon(b.isRally() ? RALLY_ICON : HAB_ICON,
                    b.isRally() ? 256 : 128, 128);
                btn.setEnabled(waitingForDeploySelection);
                registerDeployButton(btn, b.pos, cmd, deployLabel, waveAt, nameCore, b.waveSeconds);
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

    private void registerDeployButton(EspButton button, String positionText, String command,
                                      String baseLabel, long nextWaveAtEpochMs, String nameCore,
                                      int waveSeconds) {
        deployScrollList.addChild(button);
        deployButtons.add(button);
        deployButtonPositions.put(button, positionText);
        deployButtonCommands.put(button, command);
        deployButtonBaseLabels.put(button, baseLabel);
        deployButtonNameCores.put(button, nameCore);
        if (nextWaveAtEpochMs > 0L) {
            deployButtonNextWaveAt.put(button, nextWaveAtEpochMs);
        } else {
            deployButtonNextWaveAt.remove(button);
        }
        if (waveSeconds > 0) {
            deployButtonWaveSeconds.put(button, waveSeconds);
        } else {
            deployButtonWaveSeconds.remove(button);
        }
        button.setLabel(buildDeployButtonLabel(
            buildLiveDeployBaseLabel(button, baseLabel), positionText, command));
    }

    private String formatWaveStatus(long nextWaveAtEpochMs, int waveSeconds) {
        if (nextWaveAtEpochMs <= 0L) {
            return "";
        }
        long remaining = Math.max(0L,
            (nextWaveAtEpochMs - System.currentTimeMillis() + 999L) / 1000L);
        if (remaining <= 0L) {
            return " " + "\u00a7" + "7[就绪]";
        }
        int total = waveSeconds > 0 ? waveSeconds : (int) remaining;
        return " " + "\u00a7" + "7[冷却 " + remaining + "/" + total + "s]";
    }

    private String buildLiveDeployBaseLabel(EspButton button, String fallbackBase) {
        Long waveAt = deployButtonNextWaveAt.get(button);
        String nameCore = deployButtonNameCores.get(button);
        if (waveAt == null || waveAt <= 0L || nameCore == null) {
            return fallbackBase;
        }
        int total = deployButtonWaveSeconds.getOrDefault(button, 0);
        return nameCore + formatWaveStatus(waveAt, total);
    }

    private void refreshRallyWaveLabels() {
        for (EspButton button : deployButtons) {
            Long waveAt = deployButtonNextWaveAt.get(button);
            if (waveAt == null || waveAt <= 0L) {
                continue;
            }
            String base = buildLiveDeployBaseLabel(button, deployButtonBaseLabels.get(button));
            button.setLabel(buildDeployButtonLabel(
                base,
                deployButtonPositions.get(button),
                deployButtonCommands.get(button)));
            // 同步 baseLabels，避免选中刷新时退回旧秒数。
            deployButtonBaseLabels.put(button, base);
        }
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
        refreshConfirmDeployButton();
    }

    private void confirmDeploymentPoint() {
        if (!waitingForDeploySelection || pendingDeployCommand == null) {
            return;
        }
        // 冷却中的 Rally 仍可确认排队，但确认按钮保持冷却文案。
        double[] coordinates = parseDeploymentCoordinates(pendingDeployPosition);
        if (coordinates != null) {
            HcrTacticalMapBridge.setSelectedDeploymentPoint(coordinates[0], coordinates[1]);
        } else {
            HcrTacticalMapBridge.clearSelectedDeploymentPoint();
        }

        var player = Minecraft.getInstance().player;
        if (player != null) {
            player.connection.sendCommand(pendingDeployCommand);
            boolean rallyQueued = isPendingRallyOnCooldown();
            if (!rallyQueued) {
                waitingForDeploySelection = false;
                clearPendingDeploySelection();
                refreshDeployButtonStates();
                // 落地部署成功：关闭面板，避免 isPauseScreen=false 下继续跟镜头闪。
                // 战斗中仍可按 J 重新打开。
                Minecraft.getInstance().setScreen(null);
            } else {
                // 仍在等待自动部署，只刷新确认按钮文案。
                refreshConfirmDeployButton();
            }
        }
    }

    private EspButton findDeployButton(String positionText, String command) {
        for (EspButton button : deployButtons) {
            if (Objects.equals(deployButtonPositions.get(button), positionText)
                && Objects.equals(deployButtonCommands.get(button), command)) {
                return button;
            }
        }
        return null;
    }

    private boolean isPendingRallyOnCooldown() {
        if (pendingDeployCommand == null || !pendingDeployCommand.startsWith("bastion select ")) {
            return false;
        }
        EspButton button = findDeployButton(pendingDeployPosition, pendingDeployCommand);
        if (button == null) {
            return false;
        }
        Long waveAt = deployButtonNextWaveAt.get(button);
        if (waveAt == null || waveAt <= 0L) {
            return false;
        }
        return waveAt > System.currentTimeMillis();
    }

    private void refreshConfirmDeployButton() {
        if (confirmDeployButton == null) {
            return;
        }
        if (!waitingForDeploySelection || pendingDeployCommand == null) {
            confirmDeployButton.setLabel("\u00a77选择部署点");
            confirmDeployButton.setEnabled(false);
            return;
        }
        EspButton button = findDeployButton(pendingDeployPosition, pendingDeployCommand);
        Long waveAt = button == null ? null : deployButtonNextWaveAt.get(button);
        int total = button == null ? 0 : deployButtonWaveSeconds.getOrDefault(button, 0);
        if (waveAt != null && waveAt > System.currentTimeMillis()) {
            long remaining = Math.max(1L, (waveAt - System.currentTimeMillis() + 999L) / 1000L);
            int m = total > 0 ? total : (int) remaining;
            confirmDeployButton.setLabel(
                "\u00a7e当前队包部署冷却时间[" + remaining + "/" + m + "]");
            confirmDeployButton.setEnabled(true);
            return;
        }
        confirmDeployButton.setLabel("\u00a7a部署");
        confirmDeployButton.setEnabled(true);
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
        structureRebuildQueued = false;
        // 本地推进部署倒计时（服务端全量包不保证每秒到达）。
        tickLocalDeployTimer();
        // 动态文案（Rally 冷却 / 重新部署 / 治理倒计时）按整秒节流，避免每 tick 改 label。
        int epochSec = (int) (System.currentTimeMillis() / 1000L);
        if (epochSec == lastDynamicLabelEpochSec) {
            return;
        }
        lastDynamicLabelEpochSec = epochSec;
        if (outpostRedeployButton != null) {
            outpostRedeployButton.setLabel(buildRedeployLabel());
            outpostRedeployButton.setEnabled(
                !waitingForDeploySelection && getRedeployCooldownRemaining() == 0);
        }
        // 仅更新 Rally 个人冷却文案，不 rebuildGui，避免高度闪烁。
        refreshRallyWaveLabels();
        refreshConfirmDeployButton();
        GovernanceStatePacket.TeamState governance = activeGovernance();
        if (governanceTimerText != null && governance != null) {
            int elapsed = (int) ((System.currentTimeMillis() - governanceReceivedAtMs) / 1000L);
            governanceTimerText.setText("\u00a7e剩余 "
                + Math.max(0, governance.remainingSeconds - elapsed) + "s");
        }
    }

    private void tickLocalDeployTimer() {
        if (deployTimerAnchorSeconds < 0) {
            return;
        }
        int elapsed = (int) ((System.currentTimeMillis() - deployTimerAnchorMs) / 1000L);
        int display = Math.max(0, deployTimerAnchorSeconds - elapsed);
        if (display == deployTimeRemaining) {
            return;
        }
        deployTimeRemaining = display;
        if (statusTimerText != null) {
            statusTimerText.setText(formatTime(display));
        }
    }

    @Override
    public void removed() {
        clearPendingDeploySelection();
        previewRenderer.clear();
        super.removed();
    }

    // ---------- 战术地图（右半屏，由 HCR AAD / ESPoints 绘制）----------
    private void buildMapPanel() {
        int bx = mapX + 5;
        int by = mapY + mapH - BTN_H - 4;
        EspButton score = new EspButton(bx, by, 64, BTN_H, "\u00a76玩家分数板",
            () -> Minecraft.getInstance().setScreen(new MatchScoreboardScreen(this)));
        score.setTextScale(UI_TEXT_SCALE);
        root.addChild(score);

        GovernanceStatePacket.TeamState state = activeGovernance();
        boolean battle = ClientGameState.getCurrentPhase() == GamePhase.BATTLE;
        if (battle && (state == null || "IDLE".equals(state.state))) {
            EspButton impeach = new EspButton(bx + 68, by, 56, BTN_H, "\u00a7c发起弹劾",
                () -> NetworkManager.sendGovernanceAction(
                    GovernanceActionPacket.Action.START_IMPEACHMENT, null));
            impeach.setTextScale(UI_TEXT_SCALE);
            root.addChild(impeach);
            return;
        }

        if (state == null || "IDLE".equals(state.state)) {
            return;
        }

        int governanceH = Math.max(20, mapH - MAP_FOOTER_H - 6);
        root.addChild(new GuiRect(mapX + 3, mapY + 3, mapW - 6, governanceH, 0xE0181818));
        root.addChild(new PlainText(mapX + 10, mapY + 30,
            "\u00a76\u00a7l指挥官治理：" + state.state, 0xFFFFC766));
        governanceTimerText = new PlainText(mapX + 10, mapY + 43,
            "\u00a7e剩余 " + state.remainingSeconds + "s", 0xFFFFD27A);
        root.addChild(governanceTimerText);
        int rowY = mapY + 60;
        if ("IMPEACHMENT_VOTE".equals(state.state)) {
            addGovernanceVoteButton(state.commander, "原指挥官", rowY,
                GovernanceActionPacket.Action.VOTE_IMPEACHMENT);
            addGovernanceVoteButton(state.challenger, "挑战者", rowY + 18,
                GovernanceActionPacket.Action.VOTE_IMPEACHMENT);
        } else if ("VACANCY_VOLUNTEER".equals(state.state)) {
            EspButton volunteer = new EspButton(mapX + 10, rowY, Math.max(80, mapW - 20), BTN_H,
                "\u00a7a志愿补位", () -> NetworkManager.sendGovernanceAction(
                    GovernanceActionPacket.Action.VOLUNTEER_VACANCY, null));
            volunteer.setTextScale(UI_TEXT_SCALE);
            root.addChild(volunteer);
        } else if ("VACANCY_VOTE".equals(state.state)) {
            int y = rowY;
            for (UUID volunteer : state.volunteers) {
                addGovernanceVoteButton(volunteer, "志愿者", y,
                    GovernanceActionPacket.Action.VOTE_VACANCY);
                y += 18;
                if (y > mapY + mapH - MAP_FOOTER_H - 18) break;
            }
        }
    }

    private void addGovernanceVoteButton(UUID candidate, String prefix, int y,
                                         GovernanceActionPacket.Action action) {
        if (candidate == null) return;
        String name = MatchScoreboardScreen.nameFor(candidate);
        EspButton button = new EspButton(mapX + 10, y, Math.max(80, mapW - 20), BTN_H,
            "\u00a7f" + prefix + "：\u00a7e" + name,
            () -> NetworkManager.sendGovernanceAction(action, candidate));
        button.setTextScale(UI_TEXT_SCALE);
        root.addChild(button);
    }

    private GovernanceStatePacket.TeamState activeGovernance() {
        for (GovernanceStatePacket.TeamState state : governanceState.teams) {
            if (team.equals(state.team)) return state;
        }
        return null;
    }

    // ---------- 底部状态栏 ----------
    private void buildStatusBar() {
        int barY = this.height - STATUS_BAR_H;
        root.addChild(new GuiRect(0, barY, this.width, STATUS_BAR_H, CHROME_BG));

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
            String liveBase = buildLiveDeployBaseLabel(button, baseLabel);
            // 保持 base 与当前波次一致，避免选中态刷新时退回旧秒数。
            if (deployButtonNextWaveAt.containsKey(button)) {
                deployButtonBaseLabels.put(button, liveBase);
            }
            button.setLabel(buildDeployButtonLabel(
                liveBase,
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
    protected void renderBeforeMutil(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // 始终铺不透明深黑灰，挡住世界与聊天。
        graphics.fill(0, 0, this.width, this.height, SCREEN_SHADE);
        computeRegions();
        // 左/中列先铺实心，即使地图污染 GL 也不会露出世界。
        graphics.fill(leftX, leftY, leftX + leftW, leftY + leftH, PANEL_LEFT_BG);
        graphics.fill(centerX, centerY, centerX + centerW, centerY + centerH, PANEL_CENTER_BG);
        UnifiedDeployScreenPacket.LoadoutPreview activePreview = getActivePreview();
        if (activePreview != null) {
            renderClassPreview(graphics, activePreview, mouseX, mouseY);
        } else if (activeGovernance() == null || "IDLE".equals(activeGovernance().state)) {
            renderTacticalMap(graphics, partialTick);
        } else {
            int viewportH = Math.max(1, mapH - MAP_FOOTER_H);
            graphics.fill(mapX, mapY, mapX + mapW, mapY + viewportH, PANEL_MAP_FOOTER_BG);
        }
        // 地图/预览可能改动全局 RenderSystem；复位后再画 MUtil 树，避免左栏整页闪。
        resetGuiRenderState(graphics);
        // 再盖一次左/中列，挡住地图 scissor 外溢。
        graphics.fill(leftX, leftY, leftX + leftW, leftY + leftH, PANEL_LEFT_BG);
        graphics.fill(centerX, centerY, centerX + centerW, centerY + centerH, PANEL_CENTER_BG);
        graphics.fill(0, 0, this.width, TITLE_H, CHROME_BG);
        int barY = this.height - STATUS_BAR_H;
        if (barY > TITLE_H) {
            graphics.fill(0, barY, this.width, this.height, CHROME_BG);
        }
    }

    private static void resetGuiRenderState(GuiGraphics graphics) {
        graphics.flush();
        graphics.setColor(1f, 1f, 1f, 1f);
        com.mojang.blaze3d.systems.RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
        com.mojang.blaze3d.systems.RenderSystem.enableBlend();
        com.mojang.blaze3d.systems.RenderSystem.defaultBlendFunc();
        com.mojang.blaze3d.systems.RenderSystem.disableDepthTest();
        com.mojang.blaze3d.systems.RenderSystem.depthMask(true);
        com.mojang.blaze3d.systems.RenderSystem.disableCull();
    }

    @Override
    protected void renderAfterMutil(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        computeRegions();

        // 在 GUI 元素树绘制完成后解析悬停目标，此时 EspButton.hovered 已被更新。
        updatePreviewTarget(mouseX, mouseY);
        if (activeGovernance() != null && !"IDLE".equals(activeGovernance().state)) {
            graphics.renderOutline(mapX, mapY, mapW, mapH, 0x805B6260);
        }

        renderClassTooltip(graphics, mouseX, mouseY);
        renderVariantPopup(graphics, mouseX, mouseY);
    }

    private void renderTacticalMap(GuiGraphics graphics, float partialTick) {
        int viewportH = Math.max(1, mapH - MAP_FOOTER_H);
        // 实心底再画地图（每帧），避免限频导致地图空白闪烁。
        graphics.fill(mapX, mapY, mapX + mapW, mapY + viewportH, PANEL_MAP_FOOTER_BG);
        graphics.flush();
        HcrTacticalMapBridge.renderEmbeddedMap(
            graphics,
            mapX,
            mapY,
            mapW,
            viewportH,
            partialTick
        );
        resetGuiRenderState(graphics);
        graphics.renderOutline(mapX, mapY, mapW, viewportH, 0xFF5B6260);
    }

    /**
     * 在右侧地图区域绘制人物预览，替代战术地图。
     * 使用 scissor 限制模型只能在 mapX/mapY/mapW/mapH 范围内显示。
     */
    private void renderClassPreview(GuiGraphics graphics,
                                    UnifiedDeployScreenPacket.LoadoutPreview preview,
                                    int mouseX, int mouseY) {
        previewRenderer.update(preview);
        if (!previewRenderer.isReady()) {
            // 本地玩家不可用时回退到战术地图，避免右侧区域空白。
            renderTacticalMap(graphics, 0.0f);
            return;
        }
        // 预览背景与战术地图背景一致，避免视觉跳变。
        int viewportH = Math.max(1, mapH - MAP_FOOTER_H);
        graphics.fill(mapX, mapY, mapX + mapW, mapY + viewportH, PANEL_MAP_FOOTER_BG);
        int centerX = mapX + mapW / 2;
        // 实体渲染锚点略低于区域中心，给头部留出空间并平衡面板下方留白。
        int centerY = mapY + (viewportH * 11) / 16;
        // 动态缩放：与较短边成比例，但限制在合理范围内。
        int scale = Math.max(40, Math.min(mapW, viewportH) / 3);
        // 注意符号必须与原版 InventoryScreen 保持一致：
        //   原版调用为 (leftPos + 51) - xMouse 与 (topPos + 75 - 10) - yMouse，
        //   即 center - mouse（减法）。若写成 mouse - center，人物朝向会左右反转。
        float mouseDeltaX = (float) (centerX - mouseX);
        float mouseDeltaY = (float) (centerY - mouseY);

        graphics.enableScissor(mapX, mapY, mapX + mapW, mapY + viewportH);
        try {
            previewRenderer.render(graphics, centerX, centerY, scale, mouseDeltaX, mouseDeltaY);
        } finally {
            graphics.disableScissor();
        }
        resetGuiRenderState(graphics);
        graphics.renderOutline(mapX, mapY, mapW, mapH, 0x805B6260);
    }

    /**
     * 解析当前鼠标悬停的预览目标，更新 activePreview* 状态。
     * <p>
     * 解析规则：
     * <ol>
     *   <li>有变体弹窗时：鼠标在变体行上 → 显示该变体；其他位置（标题、空白、关闭按钮）
     *       → 显示父职业的 default 或首个变体预览。</li>
     *   <li>无弹窗时：鼠标悬停任意职业按钮 → 显示该职业的 default 或首个变体预览。
     *       不检查按钮 isEnabled()，禁用按钮也可预览。</li>
     *   <li>鼠标离开所有目标后：进入宽限时间（约 100ms）；宽限期内进入新目标立即更新；
     *       宽限期结束后清除预览，恢复战术地图。</li>
     * </ol>
     */
    private void updatePreviewTarget(int mouseX, int mouseY) {
        int newClassIndex = -1;
        String newVariantId = null;

        if (hasVariantPopup()) {
            UnifiedDeployScreenPacket.ClassInfo cls = classes.get(variantPopupClassIndex);
            int row = computeVariantPopupHoveredRow(mouseX, mouseY);
            if (row >= 0) {
                int variantIndex = variantPopupScroll + row;
                if (variantIndex < cls.variants.size()) {
                    newClassIndex = variantPopupClassIndex;
                    newVariantId = cls.variants.get(variantIndex).variantId;
                }
            }
            if (newClassIndex < 0) {
                // 鼠标在弹窗标题/空白/关闭按钮上：保留父职业默认变体预览。
                newClassIndex = variantPopupClassIndex;
                newVariantId = resolveDefaultVariantId(cls);
            }
        } else {
            for (int i = 0; i < classButtons.size() && i < classes.size(); i++) {
                EspButton btn = classButtons.get(i);
                if (btn.hovered) {
                    newClassIndex = i;
                    newVariantId = resolveDefaultVariantId(classes.get(i));
                    break;
                }
            }
        }

        if (newClassIndex >= 0) {
            activePreviewClassIndex = newClassIndex;
            activePreviewVariantId = newVariantId;
            previewGraceDeadlineMs = 0L;
        } else if (activePreviewClassIndex >= 0 && previewGraceDeadlineMs == 0L) {
            // 鼠标刚离开所有预览目标，开始宽限计时。
            previewGraceDeadlineMs = System.currentTimeMillis() + PREVIEW_GRACE_MS;
        }
        // 若宽限已开始且未到期，保留当前预览；若已到期，则在 getActivePreview 中清除。
    }

    /**
     * 计算变体弹窗中当前悬停的变体行索引（0-based，相对可见区域）。
     * 返回 -1 表示鼠标不在任何变体行上（在标题、空白、关闭按钮或弹窗外）。
     */
    private int computeVariantPopupHoveredRow(int mouseX, int mouseY) {
        if (!inside(mouseX, mouseY, variantPopupX, variantPopupY,
            VARIANT_POPUP_W, variantPopupH)) {
            return -1;
        }
        int closeX = variantPopupX + VARIANT_POPUP_W - 16;
        int closeY = variantPopupY + 2;
        if (inside(mouseX, mouseY, closeX, closeY, 13, 13)) {
            return -1; // 关闭按钮
        }
        if (mouseY < variantPopupY + VARIANT_HEADER_H) {
            return -1; // 标题区
        }
        int row = (mouseY - (variantPopupY + VARIANT_HEADER_H)) / VARIANT_ROW_H;
        int visible = Math.min(VARIANT_MAX_VISIBLE,
            classes.get(variantPopupClassIndex).variants.size());
        if (row < 0 || row >= visible) {
            return -1;
        }
        return row;
    }

    /**
     * 解析职业的默认变体 ID：优先 variantId=="default"，否则取第一个变体。
     * 没有任何变体时返回 null（仍可显示裸体人物模型）。
     */
    private String resolveDefaultVariantId(UnifiedDeployScreenPacket.ClassInfo cls) {
        if (cls.variants.isEmpty()) return null;
        for (UnifiedDeployScreenPacket.VariantInfo v : cls.variants) {
            if ("default".equals(v.variantId)) return v.variantId;
        }
        return cls.variants.get(0).variantId;
    }

    /**
     * 返回当前应显示的预览数据；返回 null 表示应恢复战术地图。
     * <p>
     * 处理以下情况：
     * <ul>
     *   <li>当前 class index 越界（例如数据刷新后职业消失）→ 返回 null，恢复地图。</li>
     *   <li>当前 variantId 不存在 → 退回父职业默认/首个变体；若父职业也无变体，
     *       返回空预览（仍显示裸体人物）。</li>
     *   <li>宽限时间已过 → 返回 null，恢复地图。</li>
     * </ul>
     */
    private UnifiedDeployScreenPacket.LoadoutPreview getActivePreview() {
        if (activePreviewClassIndex < 0 || activePreviewClassIndex >= classes.size()) {
            return null;
        }
        if (previewGraceDeadlineMs != 0L
            && System.currentTimeMillis() >= previewGraceDeadlineMs) {
            // 宽限到期：清除预览状态。
            activePreviewClassIndex = -1;
            activePreviewVariantId = null;
            previewGraceDeadlineMs = 0L;
            return null;
        }
        UnifiedDeployScreenPacket.ClassInfo cls = classes.get(activePreviewClassIndex);
        if (cls.variants.isEmpty()) {
            return UnifiedDeployScreenPacket.LoadoutPreview.empty();
        }
        // 先按 variantId 精确匹配。
        for (UnifiedDeployScreenPacket.VariantInfo v : cls.variants) {
            if (Objects.equals(v.variantId, activePreviewVariantId)) {
                return v.preview;
            }
        }
        // 变体已消失：退回 default，否则首个变体。
        String fallbackId = resolveDefaultVariantId(cls);
        for (UnifiedDeployScreenPacket.VariantInfo v : cls.variants) {
            if (Objects.equals(v.variantId, fallbackId)) {
                return v.preview;
            }
        }
        return cls.variants.get(0).preview;
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
                int c = classCounts.getOrDefault(cls.classId, cls.currentCount);
                if (!inSquad()) {
                    if (cls.teamCount) {
                        lines.add("\u00a7c需先加入班组小队");
                    } else {
                        lines.add((c >= cls.maxPlayers ? "\u00a7c" : "\u00a7a")
                            + c + "/" + cls.maxPlayers
                            + " \u00a77\u00b7兵力" + cls.troopValue);
                    }
                    lines.add("\u00a77入队后可选职业");
                } else {
                    int squadCur = Math.max(0, cls.squadCurrentCount);
                    int squadCap = getSquadDisplayCap(cls);
                    lines.add((squadCur >= squadCap ? "\u00a7c" : "\u00a7a")
                        + squadCur + "/" + squadCap
                        + " \u00a77\u00b7兵力" + cls.troopValue);
                }

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
            // 父职业名额已满时，所有变体均不可新选；strict 时再叠加变体自身上限。
            boolean parentFull = isClassButtonDisabled(cls);
            boolean full = parentFull || (cls.strictCount && count >= variant.maxPlayers);
            boolean hovered = inside(mouseX, mouseY, rowX, rowY, rowW, VARIANT_ROW_H - 1);
            int fill = full ? 0xD02B2025 : hovered ? 0xE0435145 : 0xD01B1E20;
            graphics.fill(rowX, rowY, rowX + rowW, rowY + VARIANT_ROW_H - 1, fill);
            graphics.renderOutline(rowX, rowY, rowW, VARIANT_ROW_H - 1,
                hovered && !full ? 0xFFB7C9B8 : 0x8059605E);

            String countText;
            if (cls.strictCount) {
                countText = (full ? "§c" : "§a") + "[" + count + "/" + variant.maxPlayers + "]";
            } else {
                // 非严格模式：仅显示当前选择人数，不显示变体上限
                countText = "§a" + count + "人";
            }
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
        if (tutorialPreviewMode) {
            return super.mouseClicked(mx, my, button);
        }
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
        return super.mouseClicked(mx, my, button);
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
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    // ==================== 业务逻辑 ====================


    /** 是否已加入班组（mySquadId 有效）。 */
    private boolean inSquad() {
        return mySquadId >= 0;
    }

    /**
     * 职业按钮右侧人数文案（只显示一组 [当前/上限]）。
     * 未入队：team_count 不显示；其它显示 [编制当前/编制总上限]。
     * 已入队：显示 [本小队当前/本小队上限]
     *   （team_count → maxPlayers；否则 max_per_squad>0 → max_per_squad；无则回退 maxPlayers）。
     */
    private String buildClassCountRightLabel(UnifiedDeployScreenPacket.ClassInfo cls,
                                            boolean disabled) {
        String color = disabled ? "§c" : "§a";
        if (!inSquad()) {
            if (cls.teamCount) {
                return "";
            }
            int teamCount = classCounts.getOrDefault(cls.classId, cls.currentCount);
            return color + "[" + teamCount + "/" + cls.maxPlayers + "]";
        }
        int squadCur = Math.max(0, cls.squadCurrentCount);
        int squadCap = getSquadDisplayCap(cls);
        return color + "[" + squadCur + "/" + squadCap + "]";
    }

    /** 本小队该职业显示用上限。 */
    private int getSquadDisplayCap(UnifiedDeployScreenPacket.ClassInfo cls) {
        if (cls.teamCount) {
            return Math.max(1, cls.maxPlayers);
        }
        if (cls.maxPerSquad > 0) {
            return cls.maxPerSquad;
        }
        return Math.max(1, cls.maxPlayers);
    }

    /**
     * 未入队全部禁用；入队后 team_count 看小队满，非 team_count 看编制总限 + max_per_squad。
     */
    private boolean isClassButtonDisabled(UnifiedDeployScreenPacket.ClassInfo cls) {
        if (!inSquad()) {
            return true;
        }
        int squadCur = Math.max(0, cls.squadCurrentCount);
        if (cls.teamCount) {
            return squadCur >= cls.maxPlayers;
        }
        int teamCount = classCounts.getOrDefault(cls.classId, cls.currentCount);
        if (teamCount >= cls.maxPlayers) {
            return true;
        }
        return cls.maxPerSquad > 0 && squadCur >= cls.maxPerSquad;
    }

    private boolean isClassEmphasizeRed(UnifiedDeployScreenPacket.ClassInfo cls, boolean disabled) {
        if (!disabled) {
            return false;
        }
        if (!inSquad() && cls.teamCount) {
            return true;
        }
        if (!inSquad()) {
            int teamCount = classCounts.getOrDefault(cls.classId, cls.currentCount);
            return teamCount >= cls.maxPlayers;
        }
        int squadCur = Math.max(0, cls.squadCurrentCount);
        if (cls.teamCount) {
            return squadCur >= cls.maxPlayers;
        }
        int teamCount = classCounts.getOrDefault(cls.classId, cls.currentCount);
        if (teamCount >= cls.maxPlayers) {
            return true;
        }
        return cls.maxPerSquad > 0 && squadCur >= cls.maxPerSquad;
    }

    private void refreshClassButtons() {
        for (int i = 0; i < classButtons.size() && i < classes.size(); i++) {
            var cls = classes.get(i);
            boolean disabled = isClassButtonDisabled(cls);
            boolean emphasizeRed = isClassEmphasizeRed(cls, disabled);
            classButtons.get(i).setLabel("§f" + cls.name);
            classButtons.get(i).setRightLabel(buildClassCountRightLabel(cls, emphasizeRed || disabled));
            classButtons.get(i).setEnabled(!disabled);
            if (disabled) {
                classButtons.get(i).normalColor = 0xE0252035;
                classButtons.get(i).hoverColor = 0xF0403050;
            } else {
                classButtons.get(i).normalColor = BTN_BG_NORMAL;
                classButtons.get(i).hoverColor = BTN_BG_HOVER;
            }
        }
    }

    private void selectClass(int index) {
        if (index >= 0 && index < classes.size()) {
            var cls = classes.get(index);
            if (isClassButtonDisabled(cls)) {
                return;
            }
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
                // 非严格模式下不检查变体独立人数上限
                if (!cls.strictCount || count < variant.maxPlayers) {
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
        return !isDeploymentSelectionRequired();
    }

    @Override
    public void onClose() {
        if (!isDeploymentSelectionRequired()) {
            super.onClose();
        }
    }

    private boolean isDeploymentSelectionRequired() {
        // 只有尚未完成部署点选择时强制锁定主 GUI。
        // 选完部署点后（waitingForDeploySelection=false）允许 Esc/关闭；
        // 部署阶段内仍可按 J 再次打开以改职业或班组。
        return waitingForDeploySelection;
    }

    @Override
    public boolean isPauseScreen() { return false; }
}
