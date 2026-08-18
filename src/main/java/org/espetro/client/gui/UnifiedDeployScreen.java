package org.espetro.client.gui;

import se.mickelus.mutil.gui.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.espetro.client.HcrTacticalMapBridge;
import org.espetro.client.ClientEquipZones;
import org.espetro.network.NetworkManager;
import org.espetro.network.UnifiedDeployScreenPacket;
import org.espetro.network.GovernanceStatePacket;
import org.espetro.network.GovernanceActionPacket;
import org.espetro.team.Fireteam;
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
    /** 身份色条与火力组色块同宽（像素），事件驱动静态绘制。 */
    private static final int SQUAD_ACCENT_BAR_W = 2;
    private static final int FIRETEAM_CONTEXT_W = 118;
    private static final int FIRETEAM_CONTEXT_ROW_H = 12;
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
    private static final int CLASS_BG_UNAVAILABLE = 0xFF4A2024;
    private static final int CLASS_BG_SELECTED   = 0xFF6A6A20;
    private static final int CLASS_BORDER_UNAVAILABLE = 0xFF8A3A42;
    private static final int BTN_BORDER      = 0xFF59605E;
    private static final int BTN_TEXT        = 0xFFFFFF;
    /** J 键主 GUI 的所有非地图背景统一使用不透明纯黑，避免世界画面穿透或色块交替闪烁。 */
    private static final int CHROME_BG       = 0xFF000000;
    private static final int PANEL_LEFT_BG   = 0xFF000000;
    private static final int PANEL_CENTER_BG = 0xFF000000;
    private static final int PANEL_MAP_FOOTER_BG = 0xFF000000;
    private static final int SCREEN_SHADE    = 0xFF000000;
    private static final int STATIC_DIVIDER = 0xFF3A3F3D;
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
    private long classSwitchCooldownEndsAt;
    /** 服务端明确下发的本人职业 ID，不再从可能延迟的小队成员列表反推。 */
    private String selectedClassId;
    private int lastDisplayedClassSwitchCooldown = -1;
    private boolean lastClassSelectionLocationAllowed;
    private GovernanceStatePacket governanceState = ClientGovernanceState.get();
    private long governanceReceivedAtMs = ClientGovernanceState.getReceivedAtMs();
    /** candidate UUID string → vote button, for in-place label refresh */
    private final Map<String, EspButton> governanceVoteButtons = new HashMap<>();
    /** 数据事件只使相关区域失效；下一客户端 tick 合并后局部重建。 */
    private enum Section {
        SQUAD,
        CLASS,
        DEPLOY,
        MAP_CONTROLS,
        STATUS
    }
    private final EnumSet<Section> dirtySections = EnumSet.noneOf(Section.class);

    /** 班组成员右键上下文菜单（仅数据变更/点击时重建，非 tick 驱动）。 */
    private UUID fireteamContextTarget;
    private int fireteamContextX;
    private int fireteamContextY;
    private final List<FireteamContextEntry> fireteamContextEntries = new ArrayList<>();
    /** MUtil 弹出层；必须始终作为根节点最后一个子元素以拦截下层点击。 */
    private GuiElement fireteamContextRoot;

    // ===== 按钮引用 =====
    private final List<EspButton> classButtons = new ArrayList<>();
    private final Map<EspButton, Integer> classButtonToClassIndex = new HashMap<>();
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
    /** HAB 行：激活就绪 epoch ms；0 表示无需等待激活。 */
    private final Map<EspButton, Long> deployButtonHabAvailableAt = new HashMap<>();
    /** HAB 行：激活总秒数（用于倒计时显示）。 */
    private final Map<EspButton, Integer> deployButtonHabActivationSeconds = new HashMap<>();
    private EspButton outpostRedeployButton;
    private EspButton confirmDeployButton;
    private PlainText classTitleText;
    private PlainText statusText;
    private PlainText statusTimerText;
    /** 右上角倒计时正下方的兵力行（事件驱动 setText，不 rebuild）。 */
    private PlainText troopCountText;
    private PlainText phaseTitleText;
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

    // ===== 可独立重建的区域容器 =====
    private GuiElement squadSectionRoot;
    private GuiElement classSectionRoot;
    private GuiElement deploySectionRoot;
    private GuiElement mapControlsRoot;
    private GuiElement statusSectionRoot;

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
        this.selectedClassId = normalizeSelectedClassId(data.getSelectedClassId());
        this.waitingForDeploySelection = data.isWaitingForDeploySelection();
        this.outpostRedeployCooldownEndsAt = System.currentTimeMillis()
            + data.getOutpostRedeployCooldownRemaining() * 1000L;
        this.classSwitchCooldownEndsAt = System.currentTimeMillis()
            + data.getClassSwitchCooldownRemaining() * 1000L;
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
                    || !Objects.equals(a.pos, b.pos)
                    || !Objects.equals(a.status, b.status)) {
                    structureChanged = true;
                    break;
                }
            }
        }
        bastions.clear();
        bastions.addAll(next);
        if (structureChanged) {
            invalidateSections(Section.DEPLOY, Section.STATUS);
            return;
        }
        // 结构相同：只刷新 Rally/HAB 时间戳与 label。
        Map<java.util.UUID, Long> waveById = new HashMap<>();
        Map<java.util.UUID, Integer> totalById = new HashMap<>();
        Map<java.util.UUID, Long> habAtById = new HashMap<>();
        Map<java.util.UUID, Integer> habTotalById = new HashMap<>();
        for (UnifiedDeployScreenPacket.BastionItem item : bastions) {
            waveById.put(item.id, item.nextWaveAtEpochMs);
            totalById.put(item.id, item.waveSeconds);
            habAtById.put(item.id, item.habAvailableAtEpochMs);
            habTotalById.put(item.id, item.habActivationTotalSeconds);
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
                // 同步 HAB 激活时间戳
                Long habAt = habAtById.get(id);
                if (habAt != null && habAt > 0L) {
                    deployButtonHabAvailableAt.put(button, habAt);
                } else {
                    deployButtonHabAvailableAt.remove(button);
                }
                Integer habTotal = habTotalById.get(id);
                if (habTotal != null && habTotal > 0) {
                    deployButtonHabActivationSeconds.put(button, habTotal);
                } else {
                    deployButtonHabActivationSeconds.remove(button);
                }
            } catch (IllegalArgumentException ignored) {
            }
        }
        refreshRallyWaveLabels();
        refreshHabActivationLabels();
        refreshConfirmDeployButton();
    }

    public void updateClassCounts(Map<String, Integer> counts) {
        updateClassCounts(counts, null, null);
    }

    public void updateClassCounts(Map<String, Integer> counts,
                                  Map<String, Map<String, Integer>> updatedVariantCounts) {
        updateClassCounts(counts, updatedVariantCounts, null);
    }

    public void updateClassCounts(Map<String, Integer> counts,
                                  Map<String, Map<String, Integer>> updatedVariantCounts,
                                  Map<String, Integer> updatedSquadCounts) {
        boolean countsChanged = counts != null && !counts.equals(this.classCounts);
        boolean variantsChanged = updatedVariantCounts != null
            && !variantCountsEqual(this.variantCounts, updatedVariantCounts);
        boolean squadCountsChanged = false;
        if (updatedSquadCounts != null) {
            for (UnifiedDeployScreenPacket.ClassInfo cls : classes) {
                int next = Math.max(0, updatedSquadCounts.getOrDefault(cls.classId, 0));
                if (cls.squadCurrentCount != next) {
                    cls.squadCurrentCount = next;
                    squadCountsChanged = true;
                }
            }
        }
        if (!countsChanged && !variantsChanged && !squadCountsChanged) {
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
            // 即使按钮展示态相同，也采用最新描述与装备预览；替换数据本身不会动 GUI 树。
            this.classes.clear();
            this.classes.addAll(nextClasses);
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
        if (classGridChanged) {
            closeVariantPopup();
            activePreviewClassIndex = -1;
            activePreviewVariantId = null;
            previewGraceDeadlineMs = 0L;
            invalidateSections(Section.CLASS);
            return;
        }
        refreshClassButtons();
    }

    public void updateSelectedClass(String updatedSelectedClassId) {
        String next = normalizeSelectedClassId(updatedSelectedClassId);
        if (Objects.equals(this.selectedClassId, next)) {
            return;
        }
        this.selectedClassId = next;
        refreshDeployButtonStates();
        refreshClassButtons();
    }

    private static String normalizeSelectedClassId(String classId) {
        return classId == null || classId.isBlank() ? null : classId;
    }

    /** 职业交互结构（职业及其变体的数量、ID 与顺序）。 */
    private static boolean classGridEquals(
            List<UnifiedDeployScreenPacket.ClassInfo> a,
            List<UnifiedDeployScreenPacket.ClassInfo> b) {
        if (a == b) return true;
        if (a == null || b == null || a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            UnifiedDeployScreenPacket.ClassInfo x = a.get(i);
            UnifiedDeployScreenPacket.ClassInfo y = b.get(i);
            if (!Objects.equals(x.classId, y.classId)
                || x.variants.size() != y.variants.size()) {
                return false;
            }
            for (int v = 0; v < x.variants.size(); v++) {
                if (!Objects.equals(
                    x.variants.get(v).variantId, y.variants.get(v).variantId)) {
                    return false;
                }
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
        boolean statusLayoutChanged = (deployTimeRemaining < 0) != (seconds < 0);
        anchorDeployTimer(seconds);
        refreshTitleTimer();
        if (statusLayoutChanged) {
            invalidateSections(Section.STATUS);
        }
    }

    public void updateBattleTimer() {
        refreshTitleTimer();
    }

    public void updateDeploymentState(boolean waitingForSelection, int redeployCooldownRemaining) {
        this.waitingForDeploySelection = waitingForSelection;
        this.outpostRedeployCooldownEndsAt = System.currentTimeMillis()
            + Math.max(0, redeployCooldownRemaining) * 1000L;
        refreshDeployButtonStates();
    }

    /** 同步服务端权威的个人换职冷却；只刷新职业标题和按钮，不重建 GUI。 */
    public void updateClassSwitchCooldown(int remainingSeconds) {
        this.classSwitchCooldownEndsAt = System.currentTimeMillis()
            + Math.max(0, remainingSeconds) * 1000L;
        refreshClassSwitchCooldown();
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
        this.squads.clear();
        this.squads.addAll(nextSquads);
        Set<Integer> availableSquadIds = new HashSet<>();
        for (UnifiedDeployScreenPacket.SquadInfo squad : this.squads) {
            availableSquadIds.add(squad.id);
        }
        expandedSquadIds.retainAll(availableSquadIds);
        this.mySquadId = updatedMySquadId;
        // 成员/火力组变更后关闭上下文菜单，避免指向过时 UUID。
        closeFireteamContextMenu();
        // 成员职业、人数或结构变化都只重建班组区域，其他区域保持原树不动。
        invalidateSections(Section.SQUAD);
        if (statusText != null) {
            statusText.setText(buildStatusText());
        }
        // 入队状态会影响职业按钮是否可用，但无需重建职业网格。
        refreshClassButtons();
    }

    public void updateGovernance(GovernanceStatePacket packet) {
        GovernanceStatePacket.TeamState previous = activeGovernance();
        if (packet != null) {
            ClientGovernanceState.update(packet);
        }
        governanceState = ClientGovernanceState.get();
        governanceReceivedAtMs = ClientGovernanceState.getReceivedAtMs();
        GovernanceStatePacket.TeamState current = activeGovernance();
        if (!sameGovernanceLayout(previous, current)) {
            invalidateSections(Section.MAP_CONTROLS);
        } else {
            refreshGovernanceLabels(current);
        }
    }

    private void refreshGovernanceLabels(GovernanceStatePacket.TeamState current) {
        if (current == null) {
            return;
        }
        if (governanceTimerText != null) {
            governanceTimerText.setText("\u00a7e剩余 "
                + ClientGovernanceState.secondsLeft(current) + "s");
        }
        for (Map.Entry<String, EspButton> e : governanceVoteButtons.entrySet()) {
            try {
                UUID candidate = UUID.fromString(e.getKey());
                e.getValue().setLabel(buildVoteButtonLabel(current, candidate,
                    votePrefixFor(current, candidate)));
            } catch (IllegalArgumentException ignored) {
            }
        }
    }

    private static String votePrefixFor(GovernanceStatePacket.TeamState state, UUID candidate) {
        if (state == null || candidate == null) {
            return "候选人";
        }
        if ("IMPEACHMENT_VOTE".equals(state.state)) {
            if (candidate.equals(state.commander)) {
                return "原指挥官";
            }
            if (candidate.equals(state.challenger)) {
                return "挑战者";
            }
        }
        return "志愿者";
    }

    private static String buildVoteButtonLabel(GovernanceStatePacket.TeamState state,
                                              UUID candidate, String prefix) {
        String name = MatchScoreboardScreen.nameFor(candidate);
        int votes = ClientGovernanceState.voteCount(state, candidate);
        boolean mine = ClientGovernanceState.isMyVote(state, candidate);
        String mark = mine ? "\u00a7a\u2713 " : "\u00a7f";
        return mark + prefix + "\u00a7f\uff1a\u00a7e" + name + "  \u00a7b[" + votes + "\u7968]";
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

    // ==================== 自定义按钮 ====================

    private static class EspButton extends GuiElement {
        private final Runnable action;
        private String label;
        private boolean enabled = true;
        private boolean hovered = false;
        private int normalColor = BTN_BG_NORMAL;
        private int hoverColor = BTN_BG_HOVER;
        private int disabledColor = BTN_BG_DISABLED;
        private int disabledBorderColor = 0x60383848;
        private int disabledTextColor = 0x666666;
        private int textColor = BTN_TEXT;
        private ResourceLocation icon;
        private int iconTextureWidth = 128;
        private int iconTextureHeight = 128;
        private int iconSize = 10;
        private String rightLabel = "";
        private float textScale = UI_TEXT_SCALE;
        private boolean centeredText = true;
        /** 禁用态仍可点击以弹出原因说明（如选职拒绝）。 */
        private Runnable disabledAction;

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
        void setDisabledAction(Runnable r) { disabledAction = r; }
        void setIcon(ResourceLocation icon, int textureWidth, int textureHeight) {
            this.icon = icon;
            this.iconTextureWidth = textureWidth;
            this.iconTextureHeight = textureHeight;
        }
        void setIconSize(int size) { iconSize = Math.max(1, size); }
        void setRightLabel(String value) { rightLabel = value == null ? "" : value; }
        void setTextScale(float scale) { textScale = Math.max(0.5f, Math.min(1.0f, scale)); }
        void setCenteredText(boolean centered) { centeredText = centered; }
        void setDisabledStyle(int background, int border, int text) {
            disabledColor = background;
            disabledBorderColor = border;
            disabledTextColor = text;
        }

        @Override
        public boolean onMouseClick(int mx, int my, int button) {
            if (button != 0 || !isVisible() || !hasFocus()) return false;
            if (!enabled) {
                if (disabledAction != null) {
                    disabledAction.run();
                    return true;
                }
                return false;
            }
            if (action != null) action.run();
            return true;
        }

        @Override
        public void draw(GuiGraphics graphics, int x, int y, int w, int h, int mx, int my, float tick) {
            if (!isVisible()) return;
            hovered = hasFocus();

            int bx = x + getX(), by = y + getY(), bw = getWidth(), bh = getHeight();

            int bgCol;
            if (!enabled) bgCol = disabledColor;
            else if (hovered) bgCol = hoverColor;
            else bgCol = normalColor;
            graphics.fill(bx, by, bx + bw, by + bh, bgCol);

            int borderCol = !enabled
                ? disabledBorderColor : (hovered ? 0xFF9999BB : BTN_BORDER);
            graphics.renderOutline(bx, by, bw, bh, borderCol);

            int textCol = enabled ? textColor : disabledTextColor;
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

    /**
     * 班组成员信息卡：最左火力组色块 + 身份色条（同宽）+ 文本。
     * 静态 GuiElement，仅在班组数据 invalidate 后重建，不逐帧改结构。
     */
    private class SquadMemberRow extends GuiElement {
        private final String label;
        private final int fireteamColor;
        private final int roleAccentColor;
        private final UUID memberUuid;
        private final int squadId;

        SquadMemberRow(int x, int y, int width, String label,
                       int fireteamColor, int roleAccentColor,
                       UUID memberUuid, int squadId) {
            super(x, y, width, SQUAD_MEMBER_ROW_H);
            this.label = label;
            this.fireteamColor = fireteamColor;
            this.roleAccentColor = roleAccentColor;
            this.memberUuid = memberUuid;
            this.squadId = squadId;
        }

        @Override
        public boolean onMouseClick(int mx, int my, int button) {
            if (!isVisible() || !hasFocus() || memberUuid == null) {
                return false;
            }
            // 右键打开火力组/队长上下文（用屏幕最后鼠标坐标，避免 GuiElement 相对坐标偏移）
            if (button == 1) {
                openFireteamContextMenu(memberUuid, squadId, lastMouseX, lastMouseY);
                return true;
            }
            return false;
        }

        @Override
        public void draw(GuiGraphics graphics, int x, int y, int w, int h, int mx, int my, float tick) {
            if (!isVisible()) {
                return;
            }
            int bx = x + getX();
            int by = y + getY();
            int bar = SQUAD_ACCENT_BAR_W;
            graphics.fill(bx, by, bx + getWidth(), by + getHeight(),
                hasFocus() ? 0xF0404743 : 0xE0141617);
            // 最左：火力组色块
            graphics.fill(bx, by, bx + bar, by + getHeight(), fireteamColor);
            // 紧邻：阵营内身份色条（金指挥官 / 紫队长 / 蓝队员）
            graphics.fill(bx + bar, by, bx + bar * 2, by + getHeight(), roleAccentColor);

            int textX = bx + bar * 2 + 3;
            int availableWidth = Math.max(8, getWidth() - (bar * 2 + 5));
            String drawnLabel = EspetroMutilWidgets.trimToWidth(
                label, (int) (availableWidth / SQUAD_MEMBER_TEXT_SCALE));
            int textHeight = Math.max(1,
                Math.round(Minecraft.getInstance().font.lineHeight * SQUAD_MEMBER_TEXT_SCALE));
            drawScaledString(graphics, drawnLabel, textX,
                by + Math.max(0, (getHeight() - textHeight) / 2),
                BTN_TEXT, SQUAD_MEMBER_TEXT_SCALE);
        }
    }

    private record FireteamContextEntry(String label, boolean enabled, Runnable action) {}

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
        dirtySections.clear();
        populateGui();
        // 打开包已携带服务端最新人数，后续变更也由服务端主动推送，
        // 不再打开界面后立即发起第二次请求。
    }

    private void populateGui() {
        this.outpostRedeployButton = null;
        this.confirmDeployButton = null;
        this.classTitleText = null;
        this.statusText = null;
        this.statusTimerText = null;
        this.troopCountText = null;
        this.phaseTitleText = null;
        this.governanceTimerText = null;
        this.governanceVoteButtons.clear();
        // Seed from global cache so mid-impeachment open still shows vote UI.
        this.governanceState = ClientGovernanceState.get();
        this.governanceReceivedAtMs = ClientGovernanceState.getReceivedAtMs();
        computeRegions();

        buildTitleBar();

        // 所有非地图区域使用同一层不透明纯黑底；地图视口由桥接渲染器逐帧绘制。
        root.addChild(new GuiRect(leftX, leftY, leftW, leftH, PANEL_LEFT_BG));
        root.addChild(new GuiRect(centerX, centerY, centerW, centerH, PANEL_CENTER_BG));
        root.addChild(new GuiRect(mapX, mapY + Math.max(0, mapH - MAP_FOOTER_H),
            mapW, MAP_FOOTER_H, PANEL_MAP_FOOTER_BG));

        squadSectionRoot = new GuiElement(0, 0, this.width, this.height);
        classSectionRoot = new GuiElement(0, 0, this.width, this.height);
        deploySectionRoot = new GuiElement(0, 0, this.width, this.height);
        mapControlsRoot = new GuiElement(0, 0, this.width, this.height);
        statusSectionRoot = new GuiElement(0, 0, this.width, this.height);

        root.addChild(squadSectionRoot);
        buildSquadSection(squadSectionRoot);
        root.addChild(classSectionRoot);
        buildClassSection(classSectionRoot);
        root.addChild(deploySectionRoot);
        buildDeploySection(deploySectionRoot);
        root.addChild(mapControlsRoot);
        buildMapPanel(mapControlsRoot);
        root.addChild(statusSectionRoot);
        buildStatusBar(statusSectionRoot);
        fireteamContextRoot = new GuiElement(0, 0, this.width, this.height);
        fireteamContextRoot.setVisible(false);
        root.addChild(fireteamContextRoot);
    }

    private void invalidateSections(Section... sections) {
        if (root == null) {
            return;
        }
        dirtySections.addAll(Arrays.asList(sections));
    }

    /** 在 tick 边界合并同一批数据事件，避免渲染遍历期间替换 MUtil 子树。 */
    private void flushDirtySections() {
        if (dirtySections.isEmpty()) {
            return;
        }
        EnumSet<Section> pending = EnumSet.copyOf(dirtySections);
        dirtySections.clear();
        if (pending.contains(Section.SQUAD)) {
            rebuildSquadSection();
        }
        if (pending.contains(Section.CLASS)) {
            rebuildClassSection();
        }
        if (pending.contains(Section.DEPLOY)) {
            rebuildDeploySection();
        }
        if (pending.contains(Section.MAP_CONTROLS)) {
            rebuildMapControls();
        }
        if (pending.contains(Section.STATUS)) {
            rebuildStatusSection();
        }
    }

    private void rebuildSquadSection() {
        if (squadSectionRoot == null) {
            return;
        }
        double scrollOffset = squadScrollList == null ? 0.0D : squadScrollList.getOffset();
        squadSectionRoot.clearChildren();
        buildSquadSection(squadSectionRoot);
        if (squadScrollList != null) {
            squadScrollList.setOffset(scrollOffset);
        }
    }

    private void rebuildClassSection() {
        if (classSectionRoot == null) {
            return;
        }
        double scrollOffset = classScrollList == null ? 0.0D : classScrollList.getOffset();
        classSectionRoot.clearChildren();
        buildClassSection(classSectionRoot);
        if (classScrollList != null) {
            classScrollList.setOffset(scrollOffset);
        }
    }

    private void rebuildDeploySection() {
        if (deploySectionRoot == null) {
            return;
        }
        double scrollOffset = deployScrollList == null ? 0.0D : deployScrollList.getOffset();
        deploySectionRoot.clearChildren();
        buildDeploySection(deploySectionRoot);
        if (deployScrollList != null) {
            deployScrollList.setOffset(scrollOffset);
        }
        if (pendingDeployCommand != null
            && findDeployButton(pendingDeployPosition, pendingDeployCommand) == null) {
            clearPendingDeploySelection();
        } else {
            refreshConfirmDeployButton();
        }
    }

    private void rebuildMapControls() {
        if (mapControlsRoot == null) {
            return;
        }
        governanceTimerText = null;
        governanceVoteButtons.clear();
        mapControlsRoot.clearChildren();
        buildMapPanel(mapControlsRoot);
    }

    private void rebuildStatusSection() {
        if (statusSectionRoot == null) {
            return;
        }
        statusText = null;
        outpostRedeployButton = null;
        statusSectionRoot.clearChildren();
        buildStatusBar(statusSectionRoot);
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
        String teamColor = "ATTACK".equals(team) ? "\u00a7c" : "\u00a79";
        // Phase text — update dynamically by refreshTitleTimer()
        phaseTitleText = new PlainText(5, 3,
            "\u00a76\u00a7l部署阶段 \u00a77| " + teamColor + "\u00a7l"
                + factionIcon + " " + factionName,
            0xFFFFFF);
        root.addChild(phaseTitleText);

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

        statusTimerText = new PlainText(this.width - 6, 4, "", 0xFFFFD27A);
        root.addChild(statusTimerText);
        troopCountText = new PlainText(this.width - 6, 16, "", 0xFFFFFFFF);
        root.addChild(troopCountText);
        refreshTitleTimer();
        updateTroopLabel(TroopCountOverlay.getDisplayLine());
    }

    /**
     * 兵力包 / 阵营变更驱动：只改右上角兵力文案，不 rebuild 整页。
     */
    public void updateTroopLabel(String line) {
        if (troopCountText == null) return;
        String text = line == null ? "" : line;
        troopCountText.setText(text);
        if (text.isEmpty()) {
            troopCountText.setX(this.width - 6);
            return;
        }
        int w = Math.round(Minecraft.getInstance().font.width(text) * UI_TEXT_SCALE);
        troopCountText.setX(this.width - w - 6);
        troopCountText.setY(16);
    }

    private int teamAccentColor() {
        return "ATTACK".equals(team) ? 0xFFD35B50 : 0xFF5685C7;
    }

    // ---------- 班组（左列） ----------
    private void buildSquadSection(GuiElement sectionRoot) {
        int sx = squadAreaX;
        int sy = squadAreaY;
        int areaW = squadAreaW;
        int areaH = squadAreaH;

        sectionRoot.addChild(new PlainText(sx, sy, "\u00a76\u00a7l班组", 0xFFFFC766));
        int manageH = SQUAD_ROW_H;
        int listY = sy + SECTION_TITLE_H + 1;
        int listH = areaH - SECTION_TITLE_H - manageH - 5;

        squadScrollList = new ScrollableList(sx, listY, areaW, Math.max(SQUAD_ROW_H, listH))
            .setScrollStep(SQUAD_ROW_H + SQUAD_ROW_GAP)
            .setAlwaysShowScrollbar(false);
        sectionRoot.addChild(squadScrollList);

        int rowW = areaW - 6;
        int rowY = 0;
        for (var squad : squads) {
            boolean mine = squad.id == mySquadId;
            boolean unavailable = squad.isLocked || squad.memberCount >= squad.maxMembers;
            boolean expanded = expandedSquadIds.contains(squad.id);
            String disclosure = expanded ? "\u00a7f\u25bc" : "\u00a7f\u25b6";
            String state = mine ? "\u00a7a\u25cf" : squad.isLocked ? "\u00a7c\u25a0" : "\u00a77\u25cb";
            String label = disclosure + " " + state + " \u00a7f" + squad.displayId + ". " + squad.name
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
                    3, rowY, rowW - 3, "\u00a77暂无成员资料",
                    0xFF59605E, 0xFF59605E, null, squad.id));
                rowY += SQUAD_MEMBER_ROW_H + SQUAD_ROW_GAP;
            } else {
                // 服务端已按 A→B→C 排序；同组聚集后插组标题（事件重建，非 tick）
                byte lastFt = -1;
                for (UnifiedDeployScreenPacket.SquadMemberInfo member : squad.members) {
                    if (member.fireteam != lastFt) {
                        lastFt = member.fireteam;
                        Fireteam ft = Fireteam.fromIndex(member.fireteam);
                        squadScrollList.addChild(new PlainText(
                            5, rowY + 1,
                            "\u00a78火力组 " + ft.label()
                                + (member.fireteamLeader ? "" : ""),
                            ft.color()));
                        // PlainText 高度随字号；占一行间距
                        rowY += SQUAD_MEMBER_ROW_H;
                    }
                    String marker = member.commander
                        ? "\u00a76\u25c6"
                        : member.leader ? "\u00a7d\u25c6"
                        : member.fireteamLeader ? "\u00a7b\u25b8" : "\u00a77\u00b7";
                    String role = member.className.isBlank()
                        ? ""
                        : " \u00a78| \u00a7b" + member.className;
                    int roleAccent = member.commander
                        ? 0xFFFFC766
                        : member.leader ? 0xFFD48CFF : 0xFF67A7FF;
                    Fireteam ft = Fireteam.fromIndex(member.fireteam);
                    squadScrollList.addChild(new SquadMemberRow(
                        3, rowY, rowW - 3,
                        marker + " \u00a7f" + member.playerName + role,
                        ft.color(), roleAccent, member.uuid, squad.id));
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
        sectionRoot.addChild(manage);
    }

    private void toggleSquadExpanded(int squadId) {
        if (!expandedSquadIds.add(squadId)) {
            expandedSquadIds.remove(squadId);
        }
        invalidateSections(Section.SQUAD);
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

    // ---------- 职业选择（中上，5行网格）----------
    private static final int ICON_BTN = 15;          // 按钮边长 = 2/3 * CLASS_BTN_H
    private static final int ICON_GRID_GAP = 2;      // 图标间距
    private static final int ROW_LABEL_W = 10;       // 行标宽度

    private void buildClassSection(GuiElement sectionRoot) {
        int sx = classAreaX, sy = classAreaY;
        int areaW = classAreaW, areaH = classAreaH;

        classTitleText = new PlainText(sx, sy, buildClassTitle(), 0xFFFFAA00);
        sectionRoot.addChild(classTitleText);
        lastDisplayedClassSwitchCooldown = getClassSwitchCooldownRemaining();
        lastClassSelectionLocationAllowed = isClassSelectionLocationAllowed();

        int listY = sy + SECTION_TITLE_H + 1;
        int listH = areaH - SECTION_TITLE_H - 2;
        classScrollList = new ScrollableList(sx, listY, areaW, listH)
            .setScrollStep(ICON_BTN + ICON_GRID_GAP)
            .setAlwaysShowScrollbar(true);
        sectionRoot.addChild(classScrollList);
        classButtons.clear();
        classButtonToClassIndex.clear();

        int contentW = areaW - SCROLLBAR_RESERVED_W;
        int cols = Math.max(1, (contentW - ROW_LABEL_W) / (ICON_BTN + ICON_GRID_GAP));

        // 按 row 分组（1-5 为步兵行，0 为其它）
        java.util.Map<Integer, java.util.List<UnifiedDeployScreenPacket.ClassInfo>> rows = new java.util.LinkedHashMap<>();
        for (int r = 1; r <= 5; r++) rows.put(r, new java.util.ArrayList<>());
        java.util.List<UnifiedDeployScreenPacket.ClassInfo> otherClasses = new java.util.ArrayList<>();
        for (var cls : classes) {
            if (cls.row >= 1 && cls.row <= 5) rows.get(cls.row).add(cls);
            else otherClasses.add(cls);
        }

        int gy = 0;
        for (int r = 1; r <= 5; r++) {
            var rowList = rows.get(r);
            if (rowList.isEmpty()) continue;
            for (int i = 0; i < rowList.size(); i++) {
                int col = i % cols;
                int bx = ROW_LABEL_W + col * (ICON_BTN + ICON_GRID_GAP);
                int by = gy + (i / cols) * (ICON_BTN + ICON_GRID_GAP);
                addClassIconButton(rowList.get(i), rowList, bx, by, ICON_BTN);
            }
            gy += ((rowList.size() + cols - 1) / cols) * (ICON_BTN + ICON_GRID_GAP) + 2;
        }

        // row=0 职业（载具兵等）紧凑列表
        for (var cls : otherClasses) {
            int count = classCounts.getOrDefault(cls.classId, cls.currentCount);
            boolean disabled = isClassButtonDisabled(cls);
            boolean emphasizeRed = isClassEmphasizeRed(cls, disabled);
            String label = "\u00a7f" + cls.name;
            String right = buildClassCountRightLabel(cls, emphasizeRed);

            EspButton btn = new EspButton(0, gy, contentW, ICON_BTN + 2, label,
                () -> selectClass(classes.indexOf(cls)));
            btn.setIcon(RoleIconResources.resolve(cls.iconImage, cls.icon),
                RoleIconResources.TEXTURE_SIZE, RoleIconResources.TEXTURE_SIZE);
            btn.setIconSize(ICON_BTN);
            btn.setRightLabel(right);
            btn.setCenteredText(false);
            btn.setEnabled(!disabled);
            btn.setDisabledAction(() -> selectClass(classes.indexOf(cls)));
            if (disabled) {
                boolean coolingDown = getClassSwitchCooldownRemaining() > 0;
                btn.setDisabledStyle(
                    coolingDown ? BTN_BG_DISABLED : CLASS_BG_UNAVAILABLE,
                    coolingDown ? 0x60383848 : CLASS_BORDER_UNAVAILABLE,
                    coolingDown ? 0xFF777777 : 0xFFFF9A9A);
            }
            classScrollList.addChild(btn);
            classButtons.add(btn);
            classButtonToClassIndex.put(btn, classes.indexOf(cls));
            gy += ICON_BTN + ICON_GRID_GAP + 2;
        }
    }

    private void addClassIconButton(UnifiedDeployScreenPacket.ClassInfo cls,
                                     java.util.List<UnifiedDeployScreenPacket.ClassInfo> rowList,
                                     int bx, int by, int size) {
        int clsIdx = classes.indexOf(cls);
        boolean disabled = isClassButtonDisabled(cls);
        boolean emphasizeRed = isClassEmphasizeRed(cls, disabled);

        EspButton btn = new EspButton(bx, by, size, size, "", () -> selectClass(clsIdx));
        btn.setIcon(RoleIconResources.resolve(cls.iconImage, cls.icon),
            RoleIconResources.TEXTURE_SIZE, RoleIconResources.TEXTURE_SIZE);
        btn.setIconSize(size - 2);
        btn.setCenteredText(false);
        btn.setEnabled(!disabled);
        btn.setDisabledAction(() -> selectClass(clsIdx));
        if (disabled) {
            boolean coolingDown = getClassSwitchCooldownRemaining() > 0;
            btn.setDisabledStyle(
                coolingDown ? BTN_BG_DISABLED : CLASS_BG_UNAVAILABLE,
                coolingDown ? 0x60383848 : CLASS_BORDER_UNAVAILABLE,
                coolingDown ? 0xFF777777 : 0xFFFF9A9A);
        }
        classScrollList.addChild(btn);
        classButtons.add(btn);
        classButtonToClassIndex.put(btn, clsIdx);
    }

    // ---------- 部署点（中下，滚轮列表）----------
    private void buildDeploySection(GuiElement sectionRoot) {
        int sx = deployAreaX, sy = deployAreaY;
        int areaW = deployAreaW, areaH = deployAreaH;

        // 标题 + 倒计时
        sectionRoot.addChild(new PlainText(sx, sy, buildDeployTitle(), 0xFFFFAA00));

        // 滚轮列表区域
        int listY = sy + SECTION_TITLE_H + 2;
        int confirmH = BTN_H;
        int listH = areaH - SECTION_TITLE_H - confirmH - 6;

        deployScrollList = new ScrollableList(sx, listY, areaW, listH)
            .setScrollStep(BTN_H + 1)
            .setAlwaysShowScrollbar(true);
        sectionRoot.addChild(deployScrollList);

        deployButtons.clear();
        deployButtonPositions.clear();
        deployButtonCommands.clear();
        deployButtonBaseLabels.clear();
        deployButtonNextWaveAt.clear();
        deployButtonNameCores.clear();
        deployButtonWaveSeconds.clear();
        deployButtonHabAvailableAt.clear();
        deployButtonHabActivationSeconds.clear();
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
                // HAB 激活倒计时使用客户端本地计时，不再依赖静态 status 字符串
                String habActivationPart = b.isRally() ? ""
                    : formatHabStatus(b.habAvailableAtEpochMs, b.habActivationTotalSeconds);
                String statusPart = b.isRally()
                    ? formatWaveStatus(waveAt, b.waveSeconds)
                    : (habActivationPart.isEmpty()
                        ? (b.status.isBlank() ? "" : " \u00a77[" + b.status + "]")
                        : habActivationPart);
                String deployLabel = nameCore + statusPart;
                // HAB：habAvailableAtEpochMs > 0 时用时间戳判断可部署性；
                // 无激活倒计时时仍用原有字符串匹配。
                boolean habReady;
                if (b.isRally()) {
                    habReady = true;
                } else if (b.habAvailableAtEpochMs > 0L) {
                    habReady = b.habAvailableAtEpochMs <= System.currentTimeMillis();
                } else {
                    habReady = "HAB 可部署".equals(b.status);
                }
                EspButton btn = new EspButton(
                    2, row * (BTN_H + btnSpacing), btnW, BTN_H,
                    deployLabel,
                    () -> selectDeploymentPoint(b.pos, cmd)
                );
                btn.setIcon(b.isRally() ? RALLY_ICON : HAB_ICON,
                    b.isRally() ? 256 : 128, 128);
                btn.setEnabled(waitingForDeploySelection && habReady);
                if (waitingForDeploySelection && !habReady) {
                    final String habCountdown = (b.habAvailableAtEpochMs > 0L && !habReady)
                        ? "HAB 启用中 " + Math.max(1,
                            (b.habAvailableAtEpochMs - System.currentTimeMillis() + 999L) / 1000L) + "s"
                        : "";
                    final String reason = !habCountdown.isEmpty() ? habCountdown
                        : ((b.status == null || b.status.isBlank())
                            ? "该兵站当前不可用" : b.status);
                    btn.setDisabledAction(() -> EspetroTipNotifier.showDenial(
                        "无法部署到该兵站", reason));
                } else {
                    btn.setDisabledAction(null);
                }
                registerDeployButton(btn, b.pos, cmd, deployLabel, waveAt, nameCore, b.waveSeconds);
                // 追踪 HAB 激活倒计时以便本地更新
                if (b.habAvailableAtEpochMs > 0L) {
                    deployButtonHabAvailableAt.put(btn, b.habAvailableAtEpochMs);
                    deployButtonHabActivationSeconds.put(btn, b.habActivationTotalSeconds);
                }
            }
            row++;
        }

        confirmDeployButton = new EspButton(
            sx, sy + areaH - confirmH, areaW, confirmH,
            "\u00a77选择部署点",
            this::confirmDeploymentPoint
        );
        confirmDeployButton.setEnabled(false);
        sectionRoot.addChild(confirmDeployButton);
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

    private String formatHabStatus(long habAvailableAtEpochMs, int totalSeconds) {
        if (habAvailableAtEpochMs <= 0L) {
            return "";
        }
        long remaining = Math.max(0L,
            (habAvailableAtEpochMs - System.currentTimeMillis() + 999L) / 1000L);
        if (remaining <= 0L) {
            return ""; // 已激活，不再显示倒计时
        }
        return " " + "\u00a7" + "7[启用中 " + remaining + "s]";
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

    private void refreshHabActivationLabels() {
        long now = System.currentTimeMillis();
        for (Map.Entry<EspButton, Long> entry : deployButtonHabAvailableAt.entrySet()) {
            EspButton button = entry.getKey();
            long habAt = entry.getValue();
            int total = deployButtonHabActivationSeconds.getOrDefault(button, 0);
            String nameCore = deployButtonNameCores.get(button);
            if (nameCore == null) continue;
            String habPart = formatHabStatus(habAt, total);
            // 如果已激活（habPart 为空），不再显示倒计时
            String base = nameCore + (habPart.isEmpty() ? "" : habPart);
            // 如果激活完成且 status 不是 "HAB 可部署"，补充显示最终状态
            if (habAt > 0L && habAt <= now) {
                base = nameCore; // 已激活，显示干净名称
            }
            button.setLabel(buildDeployButtonLabel(
                base,
                deployButtonPositions.get(button),
                deployButtonCommands.get(button)));
            deployButtonBaseLabels.put(button, base);
            // HAB 激活完成后启用按钮
            if (habAt > 0L && habAt <= now && waitingForDeploySelection) {
                button.setEnabled(true);
            }
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
        if (!hasLocalPlayerSelectedClass()) {
            EspetroTipNotifier.showDenial("未选择职业", "请先选择职业，再选择部署点。");
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
        if (!hasLocalPlayerSelectedClass()) {
            EspetroTipNotifier.showDenial("未选择职业", "请先选择职业，再确认部署。");
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
        flushDirtySections();
        // 动态文案（Rally 冷却 / 重新部署 / 治理倒计时）按整秒节流，避免每 tick 改 label。
        if (!onceEverySecond()) {
            return;
        }
        // 本地推进阶段倒计时（服务端全量包不保证每秒到达）。每秒闸只
        // 消费一次，避免旧实现的第二次 onceEverySecond() 永远提前返回。
        tickLocalDeployTimer();
        if (outpostRedeployButton != null) {
            outpostRedeployButton.setLabel(buildRedeployLabel());
            outpostRedeployButton.setEnabled(
                !waitingForDeploySelection && getRedeployCooldownRemaining() == 0);
        }
        // 仅更新 Rally 个人冷却文案，不重建部署区域。
        refreshRallyWaveLabels();
        // 更新 HAB 激活倒计时文案，激活完成后自动启用按钮。
        refreshHabActivationLabels();
        refreshConfirmDeployButton();
        refreshClassSwitchCooldown();
        refreshClassSelectionLocation();
        GovernanceStatePacket.TeamState governance = activeGovernance();
        if (governanceTimerText != null && governance != null) {
            governanceTimerText.setText("\u00a7e剩余 "
                + ClientGovernanceState.secondsLeft(governance) + "s");
        }
    }

    private void tickLocalDeployTimer() {
        boolean changed = false;
        if (deployTimerAnchorSeconds >= 0) {
            int elapsed = (int) ((System.currentTimeMillis() - deployTimerAnchorMs) / 1000L);
            int display = Math.max(0, deployTimerAnchorSeconds - elapsed);
            if (display != deployTimeRemaining) {
                deployTimeRemaining = display;
                changed = true;
            }
        }
        // 此方法只在每秒节流闸之后调用；战斗计时使用 ClientGameState 的
        // 本地锚点，因此即使部署锚未变化也必须刷新标题。
        if (changed || ClientGameState.getCurrentPhase() == GamePhase.BATTLE) {
            refreshTitleTimer();
        }
    }

    @Override
    public void removed() {
        clearPendingDeploySelection();
        previewRenderer.clear();
        super.removed();
    }

    // ---------- 战术地图（右半屏，由 HCR AAD / ESPoints 绘制）----------
    private void buildMapPanel(GuiElement sectionRoot) {
        governanceVoteButtons.clear();
        int bx = mapX + 5;
        int by = mapY + mapH - BTN_H - 4;
        EspButton score = new EspButton(bx, by, 64, BTN_H, "\u00a76玩家分数板",
            () -> Minecraft.getInstance().setScreen(new MatchScoreboardScreen(this)));
        score.setTextScale(UI_TEXT_SCALE);
        sectionRoot.addChild(score);

        GovernanceStatePacket.TeamState state = activeGovernance();
        boolean battle = ClientGameState.getCurrentPhase() == GamePhase.BATTLE;
        if (battle && (state == null || "IDLE".equals(state.state))) {
            EspButton impeach = new EspButton(bx + 68, by, 56, BTN_H, "\u00a7c发起弹劾",
                () -> NetworkManager.sendGovernanceAction(
                    GovernanceActionPacket.Action.START_IMPEACHMENT, null));
            impeach.setTextScale(UI_TEXT_SCALE);
            sectionRoot.addChild(impeach);

            EspButton vehicleInfo = new EspButton(bx + 128, by, 64, BTN_H, "\u00a7b载具信息",
                () -> NetworkManager.requestVehicleInfo());
            vehicleInfo.setTextScale(UI_TEXT_SCALE);
            sectionRoot.addChild(vehicleInfo);
            return;
        }

        if (state == null || "IDLE".equals(state.state)) {
            return;
        }

        int governanceH = Math.max(20, mapH - MAP_FOOTER_H - 6);
        sectionRoot.addChild(new GuiRect(mapX + 3, mapY + 3, mapW - 6, governanceH, 0xE0181818));
        String stateTitle = switch (state.state) {
            case "IMPEACHMENT_VOTE" -> "弹劾投票";
            case "VACANCY_VOLUNTEER" -> "指挥官空缺";
            case "VACANCY_VOTE" -> "空缺公投";
            default -> state.state;
        };
        sectionRoot.addChild(new PlainText(mapX + 10, mapY + 30,
            "\u00a76\u00a7l指挥官治理：" + stateTitle, 0xFFFFC766));
        governanceTimerText = new PlainText(mapX + 10, mapY + 43,
            "\u00a7e剩余 " + ClientGovernanceState.secondsLeft(state) + "s", 0xFFFFD27A);
        sectionRoot.addChild(governanceTimerText);
        int rowY = mapY + 60;
        if ("IMPEACHMENT_VOTE".equals(state.state)) {
            addGovernanceVoteButton(sectionRoot, state, state.commander, "原指挥官", rowY,
                GovernanceActionPacket.Action.VOTE_IMPEACHMENT);
            addGovernanceVoteButton(sectionRoot, state, state.challenger, "挑战者", rowY + 18,
                GovernanceActionPacket.Action.VOTE_IMPEACHMENT);
        } else if ("VACANCY_VOLUNTEER".equals(state.state)) {
            EspButton volunteer = new EspButton(mapX + 10, rowY, Math.max(80, mapW - 20), BTN_H,
                "\u00a7a志愿补位", () -> NetworkManager.sendGovernanceAction(
                    GovernanceActionPacket.Action.VOLUNTEER_VACANCY, null));
            volunteer.setTextScale(UI_TEXT_SCALE);
            sectionRoot.addChild(volunteer);
            if (!state.volunteers.isEmpty()) {
                String names = state.volunteers.stream()
                    .map(MatchScoreboardScreen::nameFor)
                    .reduce((a, b) -> a + ", " + b).orElse("");
                sectionRoot.addChild(new PlainText(mapX + 10, rowY + 18,
                    "\u00a77已志愿: " + names, 0xFFB0B0B0));
            }
        } else if ("VACANCY_VOTE".equals(state.state)) {
            int y = rowY;
            for (UUID volunteer : state.volunteers) {
                addGovernanceVoteButton(sectionRoot, state, volunteer, "志愿者", y,
                    GovernanceActionPacket.Action.VOTE_VACANCY);
                y += 18;
                if (y > mapY + mapH - MAP_FOOTER_H - 18) break;
            }
        }
    }

    private void addGovernanceVoteButton(GuiElement sectionRoot,
                                         GovernanceStatePacket.TeamState state,
                                         UUID candidate, String prefix, int y,
                                         GovernanceActionPacket.Action action) {
        if (candidate == null) return;
        EspButton button = new EspButton(mapX + 10, y, Math.max(80, mapW - 20), BTN_H,
            buildVoteButtonLabel(state, candidate, prefix),
            () -> NetworkManager.sendGovernanceAction(action, candidate));
        button.setTextScale(UI_TEXT_SCALE);
        sectionRoot.addChild(button);
        governanceVoteButtons.put(candidate.toString(), button);
    }

    private GovernanceStatePacket.TeamState activeGovernance() {
        GovernanceStatePacket.TeamState cached = ClientGovernanceState.forTeam(team);
        if (cached != null) {
            return cached;
        }
        for (GovernanceStatePacket.TeamState state : governanceState.teams) {
            if (team.equals(state.team)) return state;
        }
        return null;
    }

    // ---------- 底部状态栏 ----------
    private void buildStatusBar(GuiElement sectionRoot) {
        int barY = this.height - STATUS_BAR_H;
        sectionRoot.addChild(new GuiRect(0, barY, this.width, STATUS_BAR_H, CHROME_BG));

        statusText = new PlainText(5, barY + 3, buildStatusText(), BTN_TEXT);
        sectionRoot.addChild(statusText);

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
            sectionRoot.addChild(outpostRedeployButton);
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

    private void refreshTitleTimer() {
        if (statusTimerText == null) return;

        String teamColor = "ATTACK".equals(team) ? "\u00a7c" : "\u00a79";
        GamePhase phase = ClientGameState.getCurrentPhase();

        // 左上角阶段名
        String phaseName = switch (phase) {
            case WAITING_FOR_PLAYERS -> "\u00a76\u00a7l等待";
            case LOBBY -> "\u00a76\u00a7l主城等待";
            case MAP_VOTE -> "\u00a76\u00a7l地图投票";
            case MAP_LOADING -> "\u00a76\u00a7l地图加载";
            case TEAM_SELECT -> "\u00a76\u00a7l选边";
            case ATTACK_COMMANDER_VOTE, DEFEND_COMMANDER_VOTE -> "\u00a76\u00a7l指挥官投票";
            case ATTACK_FACTION_SELECT, DEFEND_FACTION_SELECT -> "\u00a76\u00a7l编制选择";
            case FACTION_REVEAL -> "\u00a76\u00a7l编制揭示";
            case DEPLOYING -> "\u00a76\u00a7l部署阶段";
            case BATTLE -> "\u00a76\u00a7l战斗阶段";
            case ROUND_END -> "\u00a76\u00a7l结算";
            case CLEANUP -> "\u00a76\u00a7l清理";
        };
        String objectiveMode = ClientGameState.getObjectiveMode();
        String modePart = objectiveMode != null
            && (phase == GamePhase.DEPLOYING || phase == GamePhase.BATTLE)
            ? " \u00a78· \u00a7e" + objectiveMode
            : "";
        String factionPart = "\u00a77| " + teamColor + "\u00a7l" + factionIcon + " " + factionName;
        String title = EspetroMutilWidgets.trimToWidth(
            phaseName + modePart + " " + factionPart, Math.max(80, this.width - 96));
        phaseTitleText.setText(title);

        // 右上角倒计时
        int battleRemaining = ClientGameState.getBattleTimeRemaining();
        String timer;
        if (phase == GamePhase.BATTLE && battleRemaining >= 0) {
            timer = formatTime(battleRemaining);
        } else if (deployTimeRemaining >= 0) {
            timer = formatTime(deployTimeRemaining);
        } else {
            timer = "";
        }
        statusTimerText.setText(timer);
        int timerWidth = Math.round(
            Minecraft.getInstance().font.width(timer) * UI_TEXT_SCALE);
        statusTimerText.setX(this.width - timerWidth - 6);
        // 兵力贴在时间正下方
        if (troopCountText != null) {
            updateTroopLabel(TroopCountOverlay.getDisplayLine());
        }
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

    /** 本地玩家是否已有职业（由服务端按接收玩家独立同步）。 */
    private boolean hasLocalPlayerSelectedClass() {
        return selectedClassId != null;
    }

    private void refreshDeployButtonStates() {
        if (!waitingForDeploySelection) {
            clearPendingDeploySelection();
        }
        boolean hasClass = hasLocalPlayerSelectedClass();
        for (EspButton button : deployButtons) {
            String command = deployButtonCommands.get(button);
            boolean ready = isDeployButtonSelectable(command);
            button.setEnabled(waitingForDeploySelection && ready && hasClass);
            if (waitingForDeploySelection && ready && !hasClass) {
                button.setDisabledAction(() ->
                    EspetroTipNotifier.showDenial("未选择职业", "请先选择职业，再选择部署点。"));
            } else {
                button.setDisabledAction(null);
            }
        }
        refreshDeployButtonLabels();
        if (outpostRedeployButton != null) {
            outpostRedeployButton.setLabel(buildRedeployLabel());
            outpostRedeployButton.setEnabled(
                !waitingForDeploySelection && getRedeployCooldownRemaining() == 0);
        }
    }

    /**
     * HAB 仅在状态为「可部署」时可点；原部署点 / 前哨 / Rally 在等待部署时均可点。
     * 有 habAvailableAtEpochMs 时，用客户端本地时间戳判断是否已激活。
     */
    private boolean isDeployButtonSelectable(String command) {
        if (command == null || !command.startsWith("bastion select ")) {
            return true;
        }
        String idText = command.substring("bastion select ".length()).trim();
        try {
            java.util.UUID id = java.util.UUID.fromString(idText);
            for (UnifiedDeployScreenPacket.BastionItem item : bastions) {
                if (item == null || item.id == null || !item.id.equals(id)) {
                    continue;
                }
                if (item.isRally()) {
                    return true;
                }
                if (item.habAvailableAtEpochMs > 0L) {
                    return item.habAvailableAtEpochMs <= System.currentTimeMillis();
                }
                return "HAB 可部署".equals(item.status);
            }
        } catch (IllegalArgumentException ignored) {
        }
        return true;
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
        CurrentMapBackgroundRenderer.render(
            graphics, this.width, this.height, ClientGameState.getCurrentMapFolder());
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
        // 阵营强调条属于稳定背景层，不加入会被局部更新影响的 MUtil 元素树。
        graphics.fill(0, 0, 3, TITLE_H, teamAccentColor());
        int barY = this.height - STATUS_BAR_H;
        if (barY > TITLE_H) {
            graphics.fill(0, barY, this.width, this.height, CHROME_BG);
        }
        renderStaticDividers(graphics);
    }

    /**
     * 仅依赖当前窗口布局的装饰线。它们与阵营强调条同属稳定背景层，
     * 不加入任何会因网络数据更新而清空、替换的 MUtil 区域容器。
     */
    private void renderStaticDividers(GuiGraphics graphics) {
        graphics.fill(4, TITLE_H, Math.max(4, this.width - 4), TITLE_H + 1,
            STATIC_DIVIDER);

        int dividerBottom = Math.max(TITLE_H + 2, this.height - STATUS_BAR_H - 2);
        graphics.fill(leftX + leftW, TITLE_H + 2, leftX + leftW + 1, dividerBottom,
            STATIC_DIVIDER);
        graphics.fill(centerX + centerW, TITLE_H + 2, centerX + centerW + 1, dividerBottom,
            STATIC_DIVIDER);
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
        int viewportH = mapViewportHeight();
        // 实心底再画地图（每帧），避免限频导致地图空白闪烁。
        renderMapViewportBackground(graphics, viewportH);
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
        renderMapViewportBorder(graphics, viewportH);
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
        // 人物预览与战术地图共用同一视口背景、裁剪范围和边框。
        int viewportH = mapViewportHeight();
        renderMapViewportBackground(graphics, viewportH);
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
        renderMapViewportBorder(graphics, viewportH);
    }

    private int mapViewportHeight() {
        return Math.max(1, mapH - MAP_FOOTER_H);
    }

    private void renderMapViewportBackground(GuiGraphics graphics, int viewportH) {
        graphics.fill(mapX, mapY, mapX + mapW, mapY + viewportH, PANEL_MAP_FOOTER_BG);
    }

    private void renderMapViewportBorder(GuiGraphics graphics, int viewportH) {
        graphics.renderOutline(mapX, mapY, mapW, viewportH, 0xFF5B6260);
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
            for (EspButton btn : classButtons) {
                if (btn.hovered) {
                    Integer idx = classButtonToClassIndex.get(btn);
                    if (idx != null && idx >= 0 && idx < classes.size()) {
                        newClassIndex = idx;
                        newVariantId = resolveDefaultVariantId(classes.get(idx));
                    }
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
        for (EspButton btn : classButtons) {
            if (!btn.hovered) continue;
            Integer clsIdx = classButtonToClassIndex.get(btn);
            if (clsIdx == null || clsIdx < 0 || clsIdx >= classes.size()) continue;
            var cls = classes.get(clsIdx);
            boolean enabled = btn.isEnabled();
            List<String> lines = new ArrayList<>();
            lines.add((enabled ? "\u00a76\u00a7l" : "\u00a7c") + cls.name);
            if (enabled) {
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
            // 禁用原因
            if (!enabled) {
                String denial = resolveClassDenialMessage(cls);
                if (!denial.isEmpty()) {
                    // 去掉颜色代码以便在 tooltip 中显示
                    String clean = denial.replaceAll("(?i)\u00a7[0-9A-FK-OR]", "");
                    lines.add("\u00a7c" + clean);
                }
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
        if (keyCode == GLFW.GLFW_KEY_ESCAPE && hasFireteamContextMenu()) {
            closeFireteamContextMenu();
            return true;
        }
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

    private int mySquadSize() {
        if (!inSquad()) {
            return 0;
        }
        for (UnifiedDeployScreenPacket.SquadInfo squad : squads) {
            if (squad.id == mySquadId) {
                return squad.members.size();
            }
        }
        return 0;
    }

    /**
     * 职业按钮右侧人数文案（只显示一组 [当前/上限]）。
     * 未入队：不显示任何人数，避免在尚不能选职时造成可选错觉。
     * 已入队：显示 [本小队当前/本小队上限]
     *   （team_count → maxPlayers；否则 max_per_squad>0 → max_per_squad；无则回退 maxPlayers）。
     */
    private String buildClassCountRightLabel(UnifiedDeployScreenPacket.ClassInfo cls,
                                            boolean disabled) {
        String color = disabled ? "§c" : "§a";
        if (!inSquad()) {
            return "";
        }
        int squadCur = Math.max(0, cls.squadCurrentCount);
        int squadCap = getSquadDisplayCap(cls);
        return color + "[" + squadCur + "/" + squadCap + "]";
    }

    /** 客户端侧选职拒绝原因（与服务端规则对齐的展示层）。 */
    private String resolveClassDenialMessage(UnifiedDeployScreenPacket.ClassInfo cls) {
        if (!isClassSelectionLocationAllowed()) {
            return "只能在选择部署点时、原部署点附近或己方 Radio 轮盘中选择职业。";
        }
        if (getClassSwitchCooldownRemaining() > 0) {
            return "职业切换冷却中，还需等待 " + getClassSwitchCooldownRemaining() + " 秒。";
        }
        if (!inSquad()) {
            return "请先加入班组小队后再选择职业。";
        }
        if (cls.teammatesNeed > 0 && mySquadSize() < cls.teammatesNeed) {
            return "小队达到 " + cls.teammatesNeed + " 人后才能选择该职业。";
        }
        // unlock_min_squad 优先级高于 unlock_per_n
        if (cls.unlockMinSquad > 0 && mySquadSize() < cls.unlockMinSquad) {
            return "小队达到 " + cls.unlockMinSquad + " 人后才能解锁该职业。";
        }
        int squadCur = Math.max(0, cls.squadCurrentCount);
        if (cls.unlockPerN > 0) {
            int available = mySquadSize() / cls.unlockPerN;
            if (available <= 0) {
                return "小队需满 " + cls.unlockPerN + " 人才能解锁 1 个该职业名额。";
            }
            if (squadCur >= available) {
                return "该职业名额已用完（每 " + cls.unlockPerN + " 人解锁 1 个，当前 " + available + " 个）。";
            }
        }
        if (cls.teamCount) {
            if (squadCur >= cls.maxPlayers) {
                return "本小队该职业人数已满（" + squadCur + "/" + cls.maxPlayers + "）。";
            }
            return "";
        }
        int teamCur = classCounts.getOrDefault(cls.classId, cls.currentCount);
        if (teamCur >= cls.maxPlayers) {
            return "该职业全队人数已满（" + teamCur + "/" + cls.maxPlayers
                + "），小队显示未满也不能再选。";
        }
        if (cls.maxPerSquad > 0 && squadCur >= cls.maxPerSquad) {
            return "本小队该职业人数已满（" + squadCur + "/" + cls.maxPerSquad + "）。";
        }
        return "";
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
        if (!isClassSelectionLocationAllowed()) {
            return true;
        }
        if (getClassSwitchCooldownRemaining() > 0) {
            return true;
        }
        if (!inSquad()) {
            return true;
        }
        if (cls.teammatesNeed > 0 && mySquadSize() < cls.teammatesNeed) {
            return true;
        }
        // unlock_min_squad 优先级高于 unlock_per_n
        if (cls.unlockMinSquad > 0 && mySquadSize() < cls.unlockMinSquad) {
            return true;
        }
        int squadCur = Math.max(0, cls.squadCurrentCount);
        if (cls.unlockPerN > 0) {
            int available = mySquadSize() / cls.unlockPerN;
            if (available <= 0 || squadCur >= available) {
                return true;
            }
        }
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
        if (!isClassSelectionLocationAllowed()) {
            return true;
        }
        if (getClassSwitchCooldownRemaining() > 0) {
            return false;
        }
        if (!disabled) {
            return false;
        }
        if (!inSquad()) {
            return true;
        }
        if (cls.teammatesNeed > 0 && mySquadSize() < cls.teammatesNeed) {
            return true;
        }
        if (cls.unlockMinSquad > 0 && mySquadSize() < cls.unlockMinSquad) {
            return true;
        }
        int squadCur = Math.max(0, cls.squadCurrentCount);
        if (cls.unlockPerN > 0) {
            int available = mySquadSize() / cls.unlockPerN;
            if (available <= 0 || squadCur >= available) {
                return true;
            }
        }
        if (cls.teamCount) {
            return squadCur >= cls.maxPlayers;
        }
        int teamCount = classCounts.getOrDefault(cls.classId, cls.currentCount);
        if (teamCount >= cls.maxPlayers) {
            return true;
        }
        return cls.maxPerSquad > 0 && squadCur >= cls.maxPerSquad;
    }

    /**
     * J 键界面的本地即时灰显。服务端仍会在 ClassSelectPacket 中按同样规则复核。
     * 等待选点时放行；落地后只认服务端同步的本方原部署点黄框。
     */
    private boolean isClassSelectionLocationAllowed() {
        if (waitingForDeploySelection) {
            return true;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return false;
        }
        for (var zone : ClientEquipZones.getZones()) {
            if (!"spawn".equals(zone.type())) {
                continue;
            }
            double dx = mc.player.getX() - zone.x();
            double dy = mc.player.getY() - zone.y();
            double dz = mc.player.getZ() - zone.z();
            double range = Math.max(0.1, zone.range());
            if (dx * dx + dy * dy + dz * dz < range * range) {
                return true;
            }
        }
        return false;
    }

    private void refreshClassButtons() {
        boolean coolingDown = getClassSwitchCooldownRemaining() > 0;
        for (EspButton btn : classButtons) {
            Integer clsIdx = classButtonToClassIndex.get(btn);
            if (clsIdx == null || clsIdx < 0 || clsIdx >= classes.size()) continue;
            var cls = classes.get(clsIdx);
            boolean disabled = isClassButtonDisabled(cls);
            boolean emphasizeRed = isClassEmphasizeRed(cls, disabled);
            boolean isSelected = selectedClassId != null && selectedClassId.equals(cls.classId);
            // 仅非图标按钮（compact list）更新文字 label；图标按钮保持图标不变
            boolean isCompactBtn = btn.getWidth() > ICON_BTN + 4;
            if (isCompactBtn) {
                btn.setLabel("\u00a7f" + cls.name);
                btn.setRightLabel(buildClassCountRightLabel(cls, emphasizeRed));
            } else {
                btn.setLabel("");
                btn.setRightLabel(null);
            }
            btn.setIcon(RoleIconResources.resolve(cls.iconImage, cls.icon),
                RoleIconResources.TEXTURE_SIZE, RoleIconResources.TEXTURE_SIZE);
            btn.setEnabled(!disabled);
            final int classIndex = clsIdx;
            btn.setDisabledAction(() -> selectClass(classIndex));
            if (disabled) {
                if (isSelected) {
                    btn.setDisabledStyle(
                        CLASS_BG_SELECTED,
                        0xFFE8B85C,
                        0xFFFFFFFF);
                } else {
                    btn.setDisabledStyle(
                        coolingDown ? BTN_BG_DISABLED : CLASS_BG_UNAVAILABLE,
                        coolingDown ? 0x60383848 : CLASS_BORDER_UNAVAILABLE,
                        coolingDown ? 0xFF777777 : 0xFFFF9A9A);
                }
            } else {
                btn.setDisabledAction(null);
                btn.normalColor = isSelected ? CLASS_BG_SELECTED : BTN_BG_NORMAL;
                btn.hoverColor = BTN_BG_HOVER;
            }
        }
    }

    private int getClassSwitchCooldownRemaining() {
        long remainingMs = classSwitchCooldownEndsAt - System.currentTimeMillis();
        return remainingMs <= 0L ? 0 : (int) ((remainingMs + 999L) / 1000L);
    }

    private String buildClassTitle() {
        int remaining = getClassSwitchCooldownRemaining();
        return remaining > 0
            ? "\u00a76职业选择 \u00a77| \u00a7e" + remaining + "秒后可更换"
            : "\u00a76职业选择";
    }

    private void refreshClassSwitchCooldown() {
        int remaining = getClassSwitchCooldownRemaining();
        if (remaining == lastDisplayedClassSwitchCooldown) {
            return;
        }
        lastDisplayedClassSwitchCooldown = remaining;
        if (classTitleText != null) {
            classTitleText.setText(buildClassTitle());
        }
        if (remaining > 0) {
            closeVariantPopup();
        }
        refreshClassButtons();
    }

    private void refreshClassSelectionLocation() {
        boolean allowed = isClassSelectionLocationAllowed();
        if (allowed == lastClassSelectionLocationAllowed) {
            return;
        }
        lastClassSelectionLocationAllowed = allowed;
        if (!allowed) {
            closeVariantPopup();
        }
        refreshClassButtons();
    }

    private void selectClass(int index) {
        if (index >= 0 && index < classes.size()) {
            var cls = classes.get(index);
            if (isClassButtonDisabled(cls)) {
                String reason = resolveClassDenialMessage(cls);
                if (reason.isEmpty()) {
                    reason = "当前无法选择该职业。";
                }
                EspetroTipNotifier.showDenial("无法选择职业", reason);
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
        if (getClassSwitchCooldownRemaining() > 0) {
            closeVariantPopup();
            return true;
        }
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

    private boolean hasFireteamContextMenu() {
        return fireteamContextTarget != null
            && fireteamContextRoot != null
            && fireteamContextRoot.isVisible()
            && !fireteamContextEntries.isEmpty();
    }

    private void closeFireteamContextMenu() {
        fireteamContextTarget = null;
        fireteamContextEntries.clear();
        if (fireteamContextRoot != null) {
            fireteamContextRoot.clearChildren();
            fireteamContextRoot.setVisible(false);
        }
    }

    /**
     * 右键成员卡：按本机权限构建静态菜单项（仅此时计算一次，直至关闭/数据刷新）。
     */
    private void openFireteamContextMenu(UUID targetUuid, int squadId, int mouseX, int mouseY) {
        closeFireteamContextMenu();
        if (targetUuid == null) {
            return;
        }
        UnifiedDeployScreenPacket.SquadInfo squad = null;
        for (UnifiedDeployScreenPacket.SquadInfo s : squads) {
            if (s.id == squadId) {
                squad = s;
                break;
            }
        }
        if (squad == null) {
            return;
        }
        UnifiedDeployScreenPacket.SquadMemberInfo target = null;
        UnifiedDeployScreenPacket.SquadMemberInfo self = null;
        UUID localId = Minecraft.getInstance().player != null
            ? Minecraft.getInstance().player.getUUID() : null;
        for (UnifiedDeployScreenPacket.SquadMemberInfo m : squad.members) {
            if (m.uuid.equals(targetUuid)) {
                target = m;
            }
            if (localId != null && m.uuid.equals(localId)) {
                self = m;
            }
        }
        if (target == null || self == null) {
            return;
        }
        // 只能管理本小队
        if (squad.id != mySquadId) {
            return;
        }
        boolean selfIsSquadLeader = self.leader;
        boolean selfIsFtLeader = self.fireteamLeader;
        boolean sameFireteam = self.fireteam == target.fireteam;
        final UUID targetId = target.uuid;
        final byte targetFt = target.fireteam;
        boolean isSelf = targetId.equals(self.uuid);

        List<FireteamContextEntry> entries = new ArrayList<>();
        if (selfIsSquadLeader && !isSelf) {
            entries.add(new FireteamContextEntry("转移队长？", true, () -> {
                NetworkManager.transferSquadLeader(targetId);
                closeFireteamContextMenu();
            }));
            for (Fireteam ft : Fireteam.values()) {
                boolean already = targetFt == ft.toNetwork();
                final Fireteam assignFt = ft;
                entries.add(new FireteamContextEntry(
                    "将该队员移至" + ft.label() + "组",
                    !already,
                    already ? null : () -> {
                        NetworkManager.assignFireteam(targetId, assignFt);
                        closeFireteamContextMenu();
                    }));
            }
            for (Fireteam ft : new Fireteam[]{Fireteam.B, Fireteam.C}) {
                boolean alreadyLeader = targetFt == ft.toNetwork() && target.fireteamLeader;
                final Fireteam appointFt = ft;
                entries.add(new FireteamContextEntry(
                    "指认为" + ft.label() + "组长",
                    !alreadyLeader,
                    alreadyLeader ? null : () -> {
                        NetworkManager.appointFireteamLeader(targetId, appointFt);
                        closeFireteamContextMenu();
                    }));
            }
        }
        // 小队长只交接“队长”；不能通过火力组菜单卸掉 A 组长身份。
        if (!selfIsSquadLeader && selfIsFtLeader && sameFireteam && !isSelf) {
            entries.add(new FireteamContextEntry("转移组长？", true, () -> {
                NetworkManager.transferFireteamLeader(targetId);
                closeFireteamContextMenu();
            }));
        }
        if (entries.isEmpty()) {
            return;
        }
        fireteamContextTarget = targetUuid;
        fireteamContextEntries.clear();
        fireteamContextEntries.addAll(entries);
        int menuH = 6 + entries.size() * FIRETEAM_CONTEXT_ROW_H;
        fireteamContextX = Math.max(3, Math.min(this.width - FIRETEAM_CONTEXT_W - 3, mouseX + 6));
        fireteamContextY = Math.max(3, Math.min(this.height - menuH - 3, mouseY + 4));
        if (fireteamContextRoot == null) {
            return;
        }
        fireteamContextRoot.clearChildren();
        fireteamContextRoot.setVisible(true);
        // MUtil 反序遍历子元素：遮罩先添加，后添加的按钮优先响应。
        fireteamContextRoot.addChild(new GuiElement(0, 0, this.width, this.height) {
            @Override
            public boolean onMouseClick(int mx, int my, int button) {
                closeFireteamContextMenu();
                return true;
            }
        });
        fireteamContextRoot.addChild(new GuiRect(
            fireteamContextX, fireteamContextY,
            FIRETEAM_CONTEXT_W, menuH, 0xF0111418));
        int y = fireteamContextY + 3;
        for (FireteamContextEntry entry : fireteamContextEntries) {
            EspButton button = new EspButton(
                fireteamContextX + 2, y,
                FIRETEAM_CONTEXT_W - 4, FIRETEAM_CONTEXT_ROW_H - 1,
                entry.label, entry.action);
            button.setEnabled(entry.enabled);
            button.setTextScale(SQUAD_MEMBER_TEXT_SCALE);
            button.setCenteredText(false);
            fireteamContextRoot.addChild(button);
            y += FIRETEAM_CONTEXT_ROW_H;
        }
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
