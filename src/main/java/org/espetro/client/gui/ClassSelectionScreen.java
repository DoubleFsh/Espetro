package org.espetro.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.espetro.network.NetworkManager;
import org.espetro.network.OpenClassSelectionPacket;

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

    // 职业人数映射（从服务端同步）
    private final Map<String, Integer> classCounts = new HashMap<>();

    // 错误消息
    private String errorMessage = null;
    private int errorDisplayTime = 0;

    // 按钮布局参数
    private final int buttonWidth = 90;
    private final int buttonHeight = 20;
    private final int vSpacing = 2;
    private final int columns = 1;
    private int startX;
    private int startY = 55;

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
    }

    private void initFromServerData() {
        displayClasses = new ClassDisplay[serverClasses.size()];
        for (int i = 0; i < serverClasses.size(); i++) {
            OpenClassSelectionPacket.ClassInfo ci = serverClasses.get(i);
            displayClasses[i] = new ClassDisplay(ci.classId, ci.name, ci.description, ci.role,
                ci.maxPlayers, ci.troopValue, ci.healthBonus, ci.speedBonus);
            classCounts.put(ci.classId, 0);
        }

        createButtons();

        // 请求服务端人数同步
        NetworkManager.requestClassCounts(factionId);
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
                displayClasses[i] = new ClassDisplay(kit.id, kit.name, kit.description, kit.role,
                    kit.maxPlayers, kit.troopValue, kit.healthBonus, kit.speedBonus);
                classCounts.put(kit.id, 0);
            }
        } else {
            displayClasses = new ClassDisplay[0];
        }

        createButtons();

        NetworkManager.requestClassCounts(factionId);
    }

    private void createButtons() {
        startX = 10;
        startY = 50;

        if (displayClasses == null || displayClasses.length == 0) return;

        for (int i = 0; i < displayClasses.length; i++) {
            final int classIndex = i;

            int col = i % columns;
            int row = i / columns;
            int x = startX + col * buttonWidth;
            int y = startY + row * (buttonHeight + vSpacing);

            String roleColor = getRoleColor(displayClasses[i].role);
            Component buttonText = Component.literal(roleColor + displayClasses[i].name);

            Button.OnPress onPress = btn -> selectClass(classIndex);

            this.addRenderableWidget(
                Button.builder(buttonText, onPress)
                    .bounds(x, y, buttonWidth, buttonHeight)
                    .build()
            );
        }
    }

    /**
     * 从服务器更新职业人数
     */
    public void updateClassCounts(Map<String, Integer> counts) {
        this.classCounts.clear();
        this.classCounts.putAll(counts);
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

            ClassSelectionGui.selectClass(factionId, cls.classId);
            this.onClose();
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

        graphics.fill(panelX - 4, panelY - 4, panelX + panelWidth, panelY + 180, 0xCC000000);

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

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(graphics);

        updateHoveredButton(mouseX, mouseY);

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

        // 渲染按钮旁人数
        if (displayClasses != null) {
            for (int i = 0; i < displayClasses.length; i++) {
                ClassDisplay cls = displayClasses[i];
                int col = i % columns;
                int row = i / columns;
                int x = startX + col * buttonWidth;
                int y = startY + row * (buttonHeight + vSpacing);

                int c = classCounts.getOrDefault(cls.classId, 0);
                String cc = c >= cls.maxPlayers ? "§c" : "§a";
                graphics.drawString(this.font, Component.literal(cc + "[" + c + "/" + cls.maxPlayers + "]"),
                    x + buttonWidth + 4, y + 4, 0xFFFFFF);
            }
        }

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
        final int maxPlayers;
        final int troopValue;
        final int healthBonus;
        final float speedBonus;

        ClassDisplay(String classId, String name, String description, String role,
                     int maxPlayers, int troopValue, int healthBonus, float speedBonus) {
            this.classId = classId;
            this.name = name;
            this.description = description;
            this.role = role;
            this.maxPlayers = maxPlayers;
            this.troopValue = troopValue;
            this.healthBonus = healthBonus;
            this.speedBonus = speedBonus;
        }
    }
}
