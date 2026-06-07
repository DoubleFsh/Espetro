package org.espetro.client.gui;

import com.example.hcrpoints.hud.TacticalMapHUD;
import se.mickelus.mutil.gui.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.espetro.network.NetworkManager;
import org.espetro.network.UnifiedDeployScreenPacket;
import org.lwjgl.glfw.GLFW;

import java.util.*;

/**
 * 统一部署/复活主界面 — 基于 mutil GuiElement 树架构
 *
 * 布局：左半屏(职业+部署点) | 右半屏(hcrpoints战术地图)
 * ┌─────────────────────────┬──────────────────────────┐
 * │  §l标题行                │                          │
 * ├─────────────────────────┤       战术地图              │
 * │ 职业选择  (左上)         │                          │
 * │ [反坦克兵][0/2]          │                          │
 * │ [精确射手][1/2]         │                          │
 * ├─────────────────────────┤                          │
 * │ §l部署点  (左下)         │                          │
 * │ ◆原部署点               │                          │
 * │ ◆兵站1 ◆兵站2           │                          │
 * └─────────────────────────┴──────────────────────────┘
 */
public class UnifiedDeployScreen extends Screen {

    private static final int BTN_H = 18;
    private static final int TITLE_H = 22;
    private static final int STATUS_BAR_H = 15;
    private static final int SECTION_TITLE_H = 14;
    private static final int INNER_PADDING = 4;

    // EspButton 颜色
    private static final int BTN_BG_NORMAL   = 0xC0101010;
    private static final int BTN_BG_HOVER    = 0xE0383868;
    private static final int BTN_BG_DISABLED = 0xA01A1A28;
    private static final int BTN_BORDER      = 0xFF606088;
    private static final int BTN_TEXT        = 0xFFFFFF;

    // 数据字段
    private final String factionId;
    private final String factionName;
    private final String factionDescription;
    private final String factionIcon;
    private final List<UnifiedDeployScreenPacket.ClassInfo> classes;
    private final Map<String, Integer> classCounts;
    private final boolean hasDeployPoint;
    private final String deployPointPos;
    private final List<UnifiedDeployScreenPacket.BastionItem> bastions;
    private final boolean isCommander;
    private final List<UnifiedDeployScreenPacket.SquadInfo> squads;
    private int mySquadId;
    private int deployTimeRemaining;
    private final String team;

    // ===== Element 树 =====
    private GuiElement root;

    // ===== 按钮引用 =====
    private final List<EspButton> classButtons = new ArrayList<>();
    private final List<EspButton> deployButtons = new ArrayList<>();

    // ===== 区域边界 =====
    private int leftX, leftY, leftW, leftH;
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
        this.hasDeployPoint = data.hasDeployPoint();
        this.deployPointPos = data.getDeployPointPos();
        this.bastions = data.getBastions();
        this.isCommander = data.isCommander();
        this.squads = data.getSquads();
        this.mySquadId = data.getMySquadId();
        this.deployTimeRemaining = data.getDeployTimeRemaining();
        this.team = data.getTeam();
    }

    public void updateClassCounts(Map<String, Integer> counts) {
        this.classCounts.clear();
        this.classCounts.putAll(counts);
        refreshClassButtons();
    }

    public void updateTimeRemaining(int seconds) {
        this.deployTimeRemaining = seconds;
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

        EspButton(int x, int y, int w, int h, String label, Runnable action) {
            super(x, y, w, h);
            this.label = label;
            this.action = action;
        }

        void setEnabled(boolean e) { enabled = e; }
        void setLabel(String l) { label = l; }
        boolean isEnabled() { return enabled; }

        @Override
        public boolean onMouseClick(int mx, int my, int button) {
            if (!enabled || !isVisible()) return false;
            if (mx >= getX() && mx <= getX() + getWidth() && my >= getY() && my <= getY() + getHeight()) {
                if (action != null) action.run();
                return true;
            }
            return false;
        }

        @Override
        public void updateFocusState(int mx, int my, int w, int h) {
            hovered = enabled && isVisible()
                && mx >= getX() && mx <= getX() + getWidth()
                && my >= getY() && my <= getY() + getHeight();
        }

        @Override
        public void draw(GuiGraphics graphics, int x, int y, int w, int h, int mx, int my, float tick) {
            if (!isVisible()) return;
            updateFocusState(mx, my, w, h);

            int bx = getX(), by = getY(), bw = getWidth(), bh = getHeight();

            int bgCol;
            if (!enabled) bgCol = BTN_BG_DISABLED;
            else if (hovered) bgCol = hoverColor;
            else bgCol = normalColor;
            graphics.fill(bx, by, bx + bw, by + bh, bgCol);

            int borderCol = !enabled ? 0x60383848 : (hovered ? 0xFF9999BB : BTN_BORDER);
            graphics.renderOutline(bx, by, bw, bh, borderCol);

            int textCol = enabled ? textColor : 0x666666;
            int textWidth = Minecraft.getInstance().font.width(label.replace("\u00a7", ""));
            graphics.drawString(Minecraft.getInstance().font, Component.literal(label),
                bx + (bw - textWidth) / 2, by + (bh - 8) / 2, textCol, false);
        }
    }

    // ==================== 自定义文本元素（绕过 GuiText，直接用 drawString) ====================

    /** 直接用 Minecraft Font 渲染的文本元素，亮度和按钮文字一致 */
    private static class PlainText extends GuiElement {
        private final String text;
        private int color;

        PlainText(int x, int y, String text, int color) {
            super(x, y, Minecraft.getInstance().font.width(text), Minecraft.getInstance().font.lineHeight);
            this.text = text;
            this.color = color;
        }

        void setColor(int c) { color = c; }

        @Override
        public void draw(GuiGraphics graphics, int x, int y, int w, int h, int mx, int my, float tick) {
            if (!isVisible()) return;
            graphics.drawString(Minecraft.getInstance().font, Component.literal(text),
                getX(), getY(), color, false);
        }
    }

    // ==================== 界面构建 ====================

    @Override
    protected void init() {
        super.init();
        rebuildGui();
        NetworkManager.requestClassCounts(factionId);
    }

    private void rebuildGui() {
        this.root = new GuiElement(0, 0, this.width, this.height);
        computeRegions();

        buildTitleBar();
        buildDividerLine(TITLE_H);

        // 左半屏区域背景（与按钮底色一致）
        root.addChild(new GuiRect(leftX - 2, leftY, leftW + 4, leftH, 0xE0252a35));
        // 地图区域背景（与按钮底色一致）
        root.addChild(new GuiRect(mapX - 2, mapY, mapW + 4, mapH, 0xE0252a35));

        buildClassSection();
        buildDividerLine(deployAreaY);
        buildDeploySection();

        // 中间竖分隔线
        root.addChild(new GuiRect(leftX + leftW, TITLE_H + 2, 1,
            this.height - TITLE_H - STATUS_BAR_H - 4, 0x50FFFFFF));

        buildMapPanel();
        buildStatusBar();
    }

    /** 计算所有区域的坐标和尺寸 */
    private void computeRegions() {
        int usableH = this.height - TITLE_H - STATUS_BAR_H - 6;
        int midX = this.width / 2;

        leftX = 4;
        leftY = TITLE_H + 2;
        leftW = midX - 8;
        leftH = usableH;

        mapX = midX + 4;
        mapY = leftY;
        mapW = this.width - mapX - 4;
        mapH = usableH;

        int halfH = usableH / 2;
        classAreaX = leftX + INNER_PADDING;
        classAreaY = leftY + INNER_PADDING;
        classAreaW = leftW - 2 * INNER_PADDING;
        classAreaH = halfH - 2 * INNER_PADDING;

        deployAreaX = leftX + INNER_PADDING;
        deployAreaY = leftY + halfH + 2;
        deployAreaW = leftW - 2 * INNER_PADDING;
        deployAreaH = usableH - halfH - 2 * INNER_PADDING - 4;
    }

    // ---------- 标题行 ----------
    private void buildTitleBar() {
        String teamColor = "ATTACK".equals(team) ? "\u00a7c" : "\u00a79";
        PlainText title = new PlainText(6, 1,
            teamColor + "\u00a7l" + factionIcon + " " + factionName, 0xFFFFFF);
        root.addChild(title);

        String teamName = "ATTACK".equals(team) ? "进攻方" : "防守方";
        String sub = "\u00a7f" + teamName;
        if (factionDescription != null && !factionDescription.isEmpty())
            sub += " \u00a7f· " + factionDescription;
        PlainText subtitle = new PlainText(6, 13, sub, BTN_TEXT);
        root.addChild(subtitle);
    }

    private void buildDividerLine(int y) {
        root.addChild(new GuiRect(4, y, this.width - 8, 1, 0x50FFFFFF));
    }

    // ---------- 职业选择（左上）----------
    private void buildClassSection() {
        int sx = classAreaX, sy = classAreaY;
        int areaW = classAreaW, areaH = classAreaH;

        PlainText ct = new PlainText(sx, sy, "\u00a76职业选择", 0xFFFFAA00);
        root.addChild(ct);

        classButtons.clear();
        int gridY = sy + SECTION_TITLE_H + 2;
        int btnW = (areaW - 6) / 2;
        int btnH = BTN_H;
        int spacing = 2;
        int cols = 2;

        for (int i = 0; i < classes.size(); i++) {
            var cls = classes.get(i);
            int count = classCounts.getOrDefault(cls.classId, 0);
            boolean full = count >= cls.maxPlayers;
            String color = full ? "\u00a7c" : "\u00a7a";
            String label = color + cls.name + " \u00a77[" + count + "/" + cls.maxPlayers + "]";

            int col = i % cols;
            int row = i / cols;
            int bx = sx + col * (btnW + spacing);
            int by = gridY + row * (btnH + spacing);

            final int idx = i;
            EspButton btn = new EspButton(bx, by, btnW, btnH, label, () -> selectClass(idx));
            btn.setEnabled(!full);
            if (full) { btn.hoverColor = 0xD0403050; btn.normalColor = 0xB0252035; }
            root.addChild(btn);
            classButtons.add(btn);
        }
    }

    // ---------- 部署点（左下，无滚轮，直接排列）----------
    private void buildDeploySection() {
        int sx = deployAreaX, sy = deployAreaY;
        int areaW = deployAreaW, areaH = deployAreaH;

        // 标题 + 倒计时
        String titleStr = "\u00a76\u00a7l部署点";
        if (deployTimeRemaining >= 0) {
            String ts = deployTimeRemaining > 60
                ? (deployTimeRemaining / 60) + ":" + String.format("%02d", deployTimeRemaining % 60)
                : deployTimeRemaining + "s";
            titleStr += "  \u00a7e\u23f1 " + ts;
        }
        PlainText dt = new PlainText(sx, sy, titleStr, 0xFFFFAA00);
        root.addChild(dt);

        root.addChild(new GuiRect(sx, sy + SECTION_TITLE_H + 2, areaW, 1, 0x30FFFFFF));

        // 按钮区域起始
        int btnX = sx + 2;
        int btnBaseY = sy + SECTION_TITLE_H + 4;
        int btnW = areaW - 4;
        int btnSpacing = 2;
        int row = 0;

        deployButtons.clear();

        // 原部署点
        if (hasDeployPoint) {
            String deployLabel = "\u00a7e\u25c6 原部署点 \u00a77(" + deployPointPos + ")";
            EspButton btn = new EspButton(
                btnX, btnBaseY + row * (BTN_H + btnSpacing), btnW, BTN_H,
                deployLabel,
                () -> { var p = Minecraft.getInstance().player; if (p != null) p.connection.sendCommand("bastion deploy"); }
            );
            root.addChild(btn);
            deployButtons.add(btn);
            row++;
        }

        // 兵站列表（最多4个，不需要滚轮）
        for (var b : bastions) {
            final var bid = b.id;
            EspButton btn = new EspButton(
                btnX, btnBaseY + row * (BTN_H + btnSpacing), btnW, BTN_H,
                "\u00a79\u25c6 " + b.name,
                () -> { var p = Minecraft.getInstance().player; if (p != null) p.connection.sendCommand("bastion select " + bid); }
            );
            root.addChild(btn);
            deployButtons.add(btn);
            row++;
        }
    }

    // ---------- 战术地图（右半屏，由 hcrpoints 绘制）----------
    private void buildMapPanel() {
        // 地图内容在 render() 中直接调用 hcrpoints 的 TacticalMapHUD 绘制。
    }

    // ---------- 底部状态栏 ----------
    private void buildStatusBar() {
        int barY = this.height - STATUS_BAR_H;
        root.addChild(new GuiRect(0, barY, this.width, STATUS_BAR_H, 0xDD000000));

        String teamColor = "ATTACK".equals(team) ? "\u00a7c" : "\u00a79";
        String teamName = "ATTACK".equals(team) ? "进攻方" : "防守方";
        String squadStr = "";
        for (var s : squads) { if (s.id == mySquadId) { squadStr = " | \u00a7a" + s.name; break; } }
        String status = teamColor + teamName + squadStr + " \u00a7f| " + factionName;
        PlainText st = new PlainText(6, barY + 1, status, BTN_TEXT);
        root.addChild(st);

        if (deployTimeRemaining >= 0) {
            String ts = deployTimeRemaining > 60
                ? (deployTimeRemaining / 60) + ":" + String.format("%02d", deployTimeRemaining % 60)
                : deployTimeRemaining + "s";
            PlainText timer = new PlainText(this.width - 50, barY + 1, ts, 0xFFFFFF);
            root.addChild(timer);
        }
    }

    // ==================== 渲染 ====================

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // 使用与按钮一致的底色填充全屏背景
        graphics.fill(0, 0, this.width, this.height, 0xE0252a35);
        computeRegions();
        root.draw(graphics, 0, 0, this.width, this.height, mouseX, mouseY, partialTick);
        renderTacticalMap(graphics, partialTick);
        renderClassTooltip(graphics, mouseX, mouseY);
    }

    private void renderTacticalMap(GuiGraphics graphics, float partialTick) {
        TacticalMapHUD.getInstance().renderEmbeddedMap(
            graphics,
            mapX - 2,
            mapY,
            mapW + 4,
            mapH,
            partialTick
        );
    }

    private void renderClassTooltip(GuiGraphics graphics, int mx, int my) {
        for (int i = 0; i < classButtons.size() && i < classes.size(); i++) {
            EspButton btn = classButtons.get(i);
            if (btn.hovered && btn.isEnabled()) {
                var cls = classes.get(i);
                int pw = 180, ph = 95;
                int px = mx + 14, py = my - ph / 2;
                if (px + pw > this.width) px = mx - pw - 14;
                if (py < 4) py = 4;
                if (py + ph > this.height - 18) py = this.height - ph - 22;

                graphics.fill(px, py, px + pw, py + ph, 0xDD111122);
                graphics.renderOutline(px, py, pw, ph, 0xFF555577);

                int ty = py + 4;
                graphics.drawString(this.font, Component.literal("\u00a76\u00a7l" + cls.name), px + 4, ty, 0xFFFFFF);
                ty += 11;
                graphics.drawString(this.font, Component.literal("\u00a7f" + cls.role), px + 4, ty, BTN_TEXT);
                ty += 10;
                if (cls.description != null && !cls.description.isEmpty()) {
                    graphics.drawString(this.font, Component.literal("\u00a7f" + cls.description), px + 4, ty, BTN_TEXT);
                    ty += 10;
                }
                if (cls.healthBonus != 0)
                    graphics.drawString(this.font, Component.literal("\u00a7c\u2764 +" + cls.healthBonus), px + 4, ty, 0xFF8888);
                if (cls.speedBonus != 0) { ty += 10; graphics.drawString(this.font,
                    Component.literal("\u00a7b\u26a1 +" + String.format("%.1f", cls.speedBonus)), px + 4, ty, 0x88CCFF); }
                ty += 10;
                int c = classCounts.getOrDefault(cls.classId, 0);
                graphics.drawString(this.font, Component.literal(
                    (c >= cls.maxPlayers ? "\u00a7c" : "\u00a7a") + c + "/" + cls.maxPlayers +
                        " \u00a77·兵力" + cls.troopValue), px + 4, ty, 0xFFFFFF);
                break;
            }
        }
    }

    // ==================== 输入事件 ====================

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
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
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_C) {
            TacticalMapHUD.getInstance().increaseRenderRange();
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_B) {
            TacticalMapHUD.getInstance().decreaseRenderRange();
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
            classButtons.get(i).setLabel(color + cls.name + " \u00a77[" + count + "/" + cls.maxPlayers + "]");
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
            ClassSelectionGui.selectClass(factionId, cls.classId);
        }
    }

    @Override
    public boolean shouldCloseOnEsc() { return true; }

    @Override
    public boolean isPauseScreen() { return false; }
}
