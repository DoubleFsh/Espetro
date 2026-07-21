package org.espetro.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.espetro.network.NetworkManager;
import org.espetro.network.OpenClassSelectionPacket;
import org.lwjgl.glfw.GLFW;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 职业选择界面
 * 支持两种数据来源：
 * 1. 服务端通过网络包发送的完整数据（局域网联机客户端）
 * 2. 本地 FactionDataLoader 加载（J键手动打开时的回退）
 */
public class ClassSelectionScreen extends Screen {

    private final String factionId;
    // 服务端发来的数据（优先使用）
    private final String serverFactionName;
    private final String serverFactionDesc;
    private final String serverFactionIcon;
    private final List<OpenClassSelectionPacket.ClassInfo> serverClasses;

    // 从本地加载的数据（回退）
    private org.espetro.team.FactionDataLoader.FactionData localFaction;
    private org.espetro.team.FactionDataLoader.ClassKitData[] localClasses;

    // 统一渲染用的职业列表
    private ClassDisplay[] displayClasses;
    private int hoveredClassIndex = -1;

    private final Map<String, Integer> classCounts = new HashMap<>();
    private final Map<String, Map<String, Integer>> variantCounts = new HashMap<>();
    private final List<Button> classButtons = new java.util.ArrayList<>();

    // 错误消息
    private String errorMessage = null;
    private int errorDisplayTime = 0;
    // 按钮布局参数
    private final int buttonWidth = 150;
    private final int buttonHeight = 24;
    private final int vSpacing = 2;
    private final int columns = 1;
    private int startX;
    private int startY = 55;
    private int popupClassIndex = -1;
    private int popupX, popupY, popupH, popupScroll;
    private int lastMouseX, lastMouseY;
    private static final int POPUP_W = 180;
    private static final int POPUP_HEADER_H = 18;
    private static final int POPUP_ROW_H = 28;
    private static final int POPUP_MAX_VISIBLE = 6;

    /** 服务端数据构造函数 —— 局域网联机时使用 */
    public ClassSelectionScreen(String factionId, String factionName, String factionDescription,
                                 String factionIcon, List<OpenClassSelectionPacket.ClassInfo> classes) {
        super(Component.literal("选择职业"));
        this.factionId = factionId;
        this.serverFactionName = factionName;
        this.serverFactionDesc = factionDescription;
        this.serverFactionIcon = factionIcon;
        this.serverClasses = classes;
        this.localFaction = null;
        this.localClasses = null;
    }

    /** 本地数据构造函数 —— J键手动打开时使用 */
    public ClassSelectionScreen(String factionId) {
        super(Component.literal("选择职业"));
        this.factionId = factionId;
        this.serverFactionName = null;
        this.serverFactionDesc = null;
        this.serverFactionIcon = null;
        this.serverClasses = null;
        this.localFaction = null;
        this.localClasses = null;
    }

    @Override
    protected void init() {
        super.init();

        if (serverClasses != null && !serverClasses.isEmpty()) {
            // 使用服务端数据
            initFromServerData();
        } else {
            // 回退：从本地加载
            initFromLocalData();
        }
        NetworkManager.requestClassCounts(factionId);
    }

    private void initFromServerData() {
        displayClasses = new ClassDisplay[serverClasses.size()];
        for (int i = 0; i < serverClasses.size(); i++) {
            OpenClassSelectionPacket.ClassInfo ci = serverClasses.get(i);
            List<VariantDisplay> variants = ci.variants.stream()
                .map(v -> new VariantDisplay(v.variantId, v.name, v.description, v.maxPlayers))
                .toList();
            displayClasses[i] = new ClassDisplay(ci.classId, ci.name, ci.description, ci.role, ci.icon,
                ci.maxPlayers, ci.troopValue, ci.healthBonus, ci.speedBonus, variants);
            classCounts.put(ci.classId, 0);
        }

        createButtons();
    }

    private void initFromLocalData() {
        org.espetro.team.FactionDataLoader loader = org.espetro.team.FactionDataProvider.getOrCreateLoader();
        loader.ensureLoaded(Minecraft.getInstance().getResourceManager());

        localFaction = loader.getFaction(factionId);
        localClasses = loader.getClassesForFaction(factionId);

        if (localClasses != null && localClasses.length > 0) {
            displayClasses = new ClassDisplay[localClasses.length];
            for (int i = 0; i < localClasses.length; i++) {
                org.espetro.team.FactionDataLoader.ClassKitData kit = localClasses[i];
                List<VariantDisplay> variants = kit.variants.values().stream()
                    .map(v -> new VariantDisplay(v.id, v.name, v.description, v.maxPlayers))
                    .toList();
                displayClasses[i] = new ClassDisplay(kit.id, kit.name, kit.description, kit.role, kit.icon,
                    kit.maxPlayers, kit.troopValue, kit.healthBonus, kit.speedBonus, variants);
                classCounts.put(kit.id, 0);
            }
        } else {
            displayClasses = new ClassDisplay[0];
        }

        createButtons();
    }

    private void createButtons() {
        startX = 10;
        startY = 50;

        if (displayClasses == null || displayClasses.length == 0) return;

        classButtons.clear();
        for (int i = 0; i < displayClasses.length; i++) {
            final int classIndex = i;

            int col = i % columns;
            int row = i / columns;
            int x = startX + col * buttonWidth;
            int y = startY + row * (buttonHeight + vSpacing);

            int currentCount = classCounts.getOrDefault(displayClasses[i].classId, 0);
            boolean full = currentCount >= displayClasses[i].maxPlayers;
            String roleColor = full ? "§c" : getRoleColor(displayClasses[i].role);
            Component buttonText = Component.literal(
                "    " + roleColor + displayClasses[i].name + " §7[" + currentCount + "/" + displayClasses[i].maxPlayers + "]");

            Button.OnPress onPress = btn -> selectClass(classIndex);

            Button button = Button.builder(buttonText, onPress)
                .bounds(x, y, buttonWidth, buttonHeight)
                .build();
            button.active = !full;
            this.addRenderableWidget(button);
            classButtons.add(button);
        }
    }

    private void refreshButtons() {
        if (displayClasses == null) return;
        for (int i = 0; i < displayClasses.length && i < classButtons.size(); i++) {
            ClassDisplay cls = displayClasses[i];
            int currentCount = classCounts.getOrDefault(cls.classId, 0);
            boolean full = currentCount >= cls.maxPlayers;
            String roleColor = full ? "§c" : getRoleColor(cls.role);
            classButtons.get(i).setMessage(Component.literal(
                "    " + roleColor + cls.name + " §7[" + currentCount + "/" + cls.maxPlayers + "]"));
            classButtons.get(i).active = !full;
        }
    }

    /**
     * 从服务器更新职业人数
     */
    public void updateClassCounts(Map<String, Integer> counts) {
        updateClassCounts(counts, null);
    }

    public void updateClassCounts(Map<String, Integer> counts,
                                  Map<String, Map<String, Integer>> updatedVariantCounts) {
        this.classCounts.clear();
        this.classCounts.putAll(counts);
        if (updatedVariantCounts != null) {
            this.variantCounts.clear();
            for (Map.Entry<String, Map<String, Integer>> entry : updatedVariantCounts.entrySet()) {
                this.variantCounts.put(entry.getKey(), new HashMap<>(entry.getValue()));
            }
        }
        refreshButtons();
    }

    /**
     * 显示错误消息
     */
    public void showError(String message) {
        this.errorMessage = message;
        this.errorDisplayTime = 100;
    }

    private String getRoleColor(String role) {
        if (role == null) return "§e";
        role = role.toLowerCase();
        if (role.contains("突击") || role.contains("战斗") || role.contains("主力")) {
            return "§c";
        } else if (role.contains("医疗") || role.contains("医护")) {
            return "§a";
        } else if (role.contains("狙击") || role.contains("远程")) {
            return "§9";
        } else if (role.contains("工程") || role.contains("工兵") || role.contains("支援")) {
            return "§e";
        } else if (role.contains("侦察")) {
            return "§d";
        } else if (role.contains("运输") || role.contains("护卫")) {
            return "§6";
        }
        return "§f";
    }

    private void selectClass(int index) {
        if (index >= 0 && index < displayClasses.length && displayClasses[index] != null) {
            ClassDisplay cls = displayClasses[index];

            int current = classCounts.getOrDefault(cls.classId, 0);
            if (current >= cls.maxPlayers) {
                showError("§c" + cls.name + " 人数已满！请选择其他职业。");
                return;
            }

            if (cls.variants.size() == 1) {
                ClassSelectionGui.selectClass(factionId, cls.classId, cls.variants.get(0).variantId);
                this.onClose();
            } else if (cls.variants.size() > 1) {
                openVariantPopup(index, lastMouseX, lastMouseY);
            }
        }
    }

    private void updateHoveredButton(int mouseX, int mouseY) {
        hoveredClassIndex = -1;
        if (displayClasses == null) return;
        for (int i = 0; i < displayClasses.length; i++) {
            int col = i % columns;
            int row = i / columns;
            int x = startX + col * buttonWidth;
            int y = startY + row * (buttonHeight + vSpacing);

            if (mouseX >= x && mouseX <= x + buttonWidth && mouseY >= y && mouseY <= y + buttonHeight) {
                hoveredClassIndex = i;
                break;
            }
        }
    }

    private void renderEquipmentPanel(GuiGraphics graphics, ClassDisplay cls) {
        int panelX = startX + buttonWidth + 20;
        int panelY = 35;
        int panelWidth = this.width - panelX - 10;

        graphics.fill(panelX - 4, panelY - 4, panelX + panelWidth, panelY + 180, 0x00000000);

        int lineY = panelY;
        int lineHeight = 11;
        int margin = 8;

        int currentCount = classCounts.getOrDefault(cls.classId, 0);
        String countColor = currentCount >= cls.maxPlayers ? "§c" : "§a";
        graphics.drawString(this.font, Component.literal("§6§l" + cls.name + " §7- " + cls.role),
            panelX + margin, lineY, 0xFFFFFF);
        lineY += lineHeight + 2;

        graphics.drawString(this.font, Component.literal(countColor + "人数: " + currentCount + "/" + cls.maxPlayers),
            panelX + margin, lineY, 0xFFFFFF);
        lineY += lineHeight + 6;

        if (cls.description != null && !cls.description.isEmpty()) {
            graphics.drawString(this.font, Component.literal("§7" + cls.description), panelX + margin, lineY, 0xAAAAAA);
            lineY += lineHeight + 3;
        }

        lineY += 3;
        if (cls.healthBonus != 0) {
            graphics.drawString(this.font, Component.literal("§c生命 +" + cls.healthBonus), panelX + margin, lineY, 0xFF8888);
            lineY += lineHeight;
        }
        if (cls.speedBonus != 0) {
            graphics.drawString(this.font, Component.literal("§b速度 +" + String.format("%.1f", cls.speedBonus)), panelX + margin, lineY, 0x88CCFF);
        }
    }

    private void renderClassIcons(GuiGraphics graphics) {
        if (displayClasses == null) return;
        for (int i = 0; i < displayClasses.length && i < classButtons.size(); i++) {
            ResourceLocation icon = displayClasses[i].iconResource;
            if (icon == null) continue;
            Button button = classButtons.get(i);
            int iconSize = 16;
            graphics.blit(icon, button.getX() + 4,
                button.getY() + (button.getHeight() - iconSize) / 2,
                iconSize, iconSize, 0.0f, 0.0f,
                RoleIconResources.TEXTURE_SIZE, RoleIconResources.TEXTURE_SIZE,
                RoleIconResources.TEXTURE_SIZE, RoleIconResources.TEXTURE_SIZE);
        }
    }

    @Override
    public void renderBackground(GuiGraphics graphics) {
        EspetroMutilWidgets.drawScreenShade(graphics, this.width, this.height);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        lastMouseX = mouseX;
        lastMouseY = mouseY;
        this.renderBackground(graphics);
        if (!hasVariantPopup()) {
            updateHoveredButton(mouseX, mouseY);
        }

        // 阵营标题
        if (serverFactionName != null) {
            // 服务端数据
            String icon = serverFactionIcon != null ? serverFactionIcon : "";
            graphics.drawString(this.font, Component.literal("§6§l" + icon + " " + serverFactionName),
                10, 8, 0xFFFFFF);
            if (serverFactionDesc != null && !serverFactionDesc.isEmpty()) {
                graphics.drawString(this.font, Component.literal("§7" + serverFactionDesc),
                    10, 22, 0xAAAAAA);
            }
        } else if (localFaction != null) {
            // 本地数据
            graphics.drawString(this.font, Component.literal("§6§l" + localFaction.icon + " " + localFaction.name),
                10, 8, 0xFFFFFF);
            graphics.drawString(this.font, Component.literal("§7" + localFaction.description),
                10, 22, 0xAAAAAA);
        }

        graphics.drawString(this.font, Component.literal("§e选择职业 §7(悬停查看装备)"),
            10, 36, 0xFFFFFF);

        // 错误消息
        if (errorMessage != null && errorDisplayTime > 0) {
            graphics.drawCenteredString(this.font, Component.literal(errorMessage),
                this.width / 2, this.height / 2, 0xFF5555);
            errorDisplayTime--;
        }

        // 悬停详情面板
        if (hoveredClassIndex >= 0 && displayClasses != null && hoveredClassIndex < displayClasses.length) {
            renderEquipmentPanel(graphics, displayClasses[hoveredClassIndex]);
        }

        super.render(graphics, mouseX, mouseY, partialTick);
        renderClassIcons(graphics);
        renderVariantPopup(graphics, mouseX, mouseY);
    }

    private void renderVariantPopup(GuiGraphics graphics, int mouseX, int mouseY) {
        if (!hasVariantPopup()) return;
        ClassDisplay cls = displayClasses[popupClassIndex];
        int visible = Math.min(POPUP_MAX_VISIBLE, cls.variants.size());

        graphics.fill(popupX, popupY, popupX + POPUP_W, popupY + popupH, 0xF0111418);
        graphics.renderOutline(popupX, popupY, POPUP_W, popupH, 0xFFE8B85C);
        graphics.drawString(this.font, Component.literal("§6§l" + cls.name + " §7装备变体"),
            popupX + 6, popupY + 5, 0xFFFFFF, false);

        int closeX = popupX + POPUP_W - 16;
        int closeY = popupY + 2;
        boolean closeHovered = inside(mouseX, mouseY, closeX, closeY, 13, 13);
        graphics.fill(closeX, closeY, closeX + 13, closeY + 13,
            closeHovered ? 0xFFD05A5A : 0xFF553535);
        graphics.drawCenteredString(this.font, Component.literal("§fX"),
            closeX + 6, closeY + 2, 0xFFFFFF);

        for (int row = 0; row < visible; row++) {
            int variantIndex = popupScroll + row;
            if (variantIndex >= cls.variants.size()) break;
            VariantDisplay variant = cls.variants.get(variantIndex);
            int rowX = popupX + 3;
            int rowY = popupY + POPUP_HEADER_H + row * POPUP_ROW_H;
            int rowW = POPUP_W - 6;
            int count = variantCounts.getOrDefault(cls.classId, Collections.emptyMap())
                .getOrDefault(variant.variantId(), 0);
            boolean full = count >= variant.maxPlayers();
            boolean hovered = inside(mouseX, mouseY, rowX, rowY, rowW, POPUP_ROW_H - 1);
            graphics.fill(rowX, rowY, rowX + rowW, rowY + POPUP_ROW_H - 1,
                full ? 0xD02B2025 : hovered ? 0xE0435145 : 0xD01B1E20);
            graphics.renderOutline(rowX, rowY, rowW, POPUP_ROW_H - 1,
                hovered && !full ? 0xFFB7C9B8 : 0x8059605E);

            String countText = (full ? "§c" : "§a") + "[" + count + "/" + variant.maxPlayers() + "]";
            graphics.drawString(this.font,
                Component.literal((full ? "§8" : "§f") + variant.name()),
                rowX + 5, rowY + 4, 0xFFFFFF, false);
            int countW = this.font.width(EspetroMutilWidgets.stripFormatting(countText));
            graphics.drawString(this.font, Component.literal(countText),
                rowX + rowW - countW - 5, rowY + 4, 0xFFFFFF, false);
            if (variant.description() != null && !variant.description().isBlank()) {
                graphics.drawString(this.font, Component.literal("§7" +
                    EspetroMutilWidgets.trimToWidth(variant.description(), rowW - 10)),
                    rowX + 5, rowY + 16, EspetroMutilWidgets.DIM, false);
            }
        }

        if (cls.variants.size() > POPUP_MAX_VISIBLE) {
            graphics.drawString(this.font, Component.literal("§8滚轮浏览"),
                popupX + POPUP_W - 49, popupY + popupH - 10,
                EspetroMutilWidgets.DIM, false);
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        lastMouseX = (int) mouseX;
        lastMouseY = (int) mouseY;
        if (hasVariantPopup()) {
            if (button == 0) {
                handleVariantPopupClick((int) mouseX, (int) mouseY);
            } else {
                closeVariantPopup();
            }
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (hasVariantPopup() && inside((int) mouseX, (int) mouseY,
            popupX, popupY, POPUP_W, popupH)) {
            int maxScroll = Math.max(0,
                displayClasses[popupClassIndex].variants.size() - POPUP_MAX_VISIBLE);
            popupScroll = Math.max(0, Math.min(maxScroll,
                popupScroll + (delta < 0 ? 1 : -1)));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ESCAPE && hasVariantPopup()) {
            closeVariantPopup();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    private void openVariantPopup(int classIndex, int mouseX, int mouseY) {
        popupClassIndex = classIndex;
        popupScroll = 0;
        int count = displayClasses[classIndex].variants.size();
        int visible = Math.min(POPUP_MAX_VISIBLE, count);
        int footer = count > POPUP_MAX_VISIBLE ? 10 : 3;
        popupH = POPUP_HEADER_H + visible * POPUP_ROW_H + footer;
        popupX = mouseX + 9;
        if (popupX + POPUP_W > this.width - 3) {
            popupX = mouseX - POPUP_W - 9;
        }
        popupX = Math.max(3, Math.min(this.width - POPUP_W - 3, popupX));
        popupY = Math.max(3, Math.min(this.height - popupH - 3, mouseY + 7));
    }

    private void handleVariantPopupClick(int mouseX, int mouseY) {
        int closeX = popupX + POPUP_W - 16;
        int closeY = popupY + 2;
        if (inside(mouseX, mouseY, closeX, closeY, 13, 13)
            || !inside(mouseX, mouseY, popupX, popupY, POPUP_W, popupH)) {
            closeVariantPopup();
            return;
        }

        ClassDisplay cls = displayClasses[popupClassIndex];
        int row = (mouseY - (popupY + POPUP_HEADER_H)) / POPUP_ROW_H;
        if (mouseY >= popupY + POPUP_HEADER_H && row >= 0 && row < POPUP_MAX_VISIBLE) {
            int variantIndex = popupScroll + row;
            if (variantIndex < cls.variants.size()) {
                VariantDisplay variant = cls.variants.get(variantIndex);
                int count = variantCounts.getOrDefault(cls.classId, Collections.emptyMap())
                    .getOrDefault(variant.variantId(), 0);
                if (count < variant.maxPlayers()) {
                    ClassSelectionGui.selectClass(factionId, cls.classId, variant.variantId());
                    closeVariantPopup();
                    this.onClose();
                }
            }
        }
    }

    private boolean hasVariantPopup() {
        return displayClasses != null && popupClassIndex >= 0 && popupClassIndex < displayClasses.length;
    }

    private void closeVariantPopup() {
        popupClassIndex = -1;
        popupScroll = 0;
    }

    private static boolean inside(int x, int y, int left, int top, int width, int height) {
        return x >= left && x < left + width && y >= top && y < top + height;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // ========== 内部显示用 DTO ==========

    private static class ClassDisplay {
        final String classId;
        final String name;
        final String description;
        final String role;
        final String icon;
        final ResourceLocation iconResource;
        final int maxPlayers;
        final int troopValue;
        final int healthBonus;
        final float speedBonus;
        final List<VariantDisplay> variants;

        ClassDisplay(String classId, String name, String description, String role, String icon,
                     int maxPlayers, int troopValue, int healthBonus, float speedBonus,
                     List<VariantDisplay> variants) {
            this.classId = classId;
            this.name = name;
            this.description = description;
            this.role = role;
            this.icon = icon;
            this.iconResource = RoleIconResources.resolve(icon);
            this.maxPlayers = maxPlayers;
            this.troopValue = troopValue;
            this.healthBonus = healthBonus;
            this.speedBonus = speedBonus;
            this.variants = variants;
        }
    }

    private record VariantDisplay(String variantId, String name, String description, int maxPlayers) {}
}
