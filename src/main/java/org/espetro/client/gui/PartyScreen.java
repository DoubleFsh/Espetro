package org.espetro.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import org.espetro.network.NetworkManager;
import org.espetro.network.PartyListPacket;
import org.espetro.client.aui.GuiElement;

import java.util.UUID;

/**
 * 主城 J 键组队面板。
 * 显示队伍列表 + 创建/加入/退出/锁定/踢人操作。
 */
public final class PartyScreen extends MutilScreen {

    private static PartyListPacket latest;
    private EspetroMutilWidgets.PhaseHeader phaseHeader;
    /** 每 tick 刷新 timer，便于重拉数据。 */
    private int refreshTimer;

    public PartyScreen() {
        super(Component.literal("组队匹配"));
    }

    public static void update(PartyListPacket packet) {
        latest = packet;
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof PartyScreen screen) {
            screen.rebuildMutilRoot();
        }
    }

    @Override
    protected void renderBeforeMutil(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        EspetroMutilWidgets.drawScreenShade(graphics, this.width, this.height);
    }

    @Override
    protected void buildMutilRoot(GuiElement root) {
        if (latest == null) latest = new PartyListPacket(
            java.util.List.of(), 7, null, false);

        int panelW = Math.min(400, this.width - 20);
        int headerH = EspetroMutilWidgets.PHASE_HEADER_HEIGHT + 4;

        phaseHeader = EspetroMutilWidgets.addMutablePhaseHeader(root, this.width,
            "§b§l组队匹配",
            latest.myPartyId == null ? "§7你尚未加入任何队伍" : "§a你已加入队伍",
            "§7上限 §e" + latest.maxPartySize + " 人 §8| §7按 J 键呼出",
            EspetroMutilWidgets.GOLD);

        int y = headerH + 4;
        int panelX = (this.width - panelW) / 2;

        // 创建 / 退出 按钮行
        if (latest.myPartyId == null) {
            int bw = EspetroMutilWidgets.textButtonWidth("§a+ 创建新队伍");
            root.addChild(EspetroMutilWidgets.button(panelX + panelW / 2 - bw / 2, y, bw, 14,
                "§a+ 创建新队伍", () -> {
                    if (!tutorialPreviewMode) {
                        Minecraft.getInstance().setScreen(new CreatePartyScreen(this));
                    }
                })
                .setColors(0x00000000, 0x20255030, 0x30306030)
                .setBorderColor(0x00000000));
        } else {
            int bw1 = EspetroMutilWidgets.textButtonWidth("§c退出队伍");
            int bw2 = EspetroMutilWidgets.textButtonWidth(latest.isOwner ? "§e管理队伍" : "§7管理队伍");
            int rowW = bw1 + bw2 + 8;
            root.addChild(EspetroMutilWidgets.button(
                panelX + panelW / 2 - rowW / 2, y, bw1, 14,
                "§c退出队伍", () -> {
                    if (!tutorialPreviewMode) NetworkManager.sendPartyLeave();
                })
                .setColors(0x00000000, 0x20402020, 0x30502020)
                .setBorderColor(0x00000000));
            if (latest.isOwner) {
                root.addChild(EspetroMutilWidgets.button(
                    panelX + panelW / 2 - rowW / 2 + bw1 + 8, y, bw2, 14,
                    "§e管理队伍", () -> {
                        if (!tutorialPreviewMode) {
                            Minecraft.getInstance().setScreen(new ManagePartyScreen(this, latest.myPartyId));
                        }
                    })
                    .setColors(0x00000000, 0x20303020, 0x30403020)
                    .setBorderColor(0x00000000));
            }
        }

        y += 20;
        // 分隔线
        root.addChild(EspetroMutilWidgets.rect(panelX + 8, y, panelW - 16, 1, 0x30FFFFFF));
        y += 8;

        // 队伍列表
        if (latest.parties.isEmpty()) {
            root.addChild(EspetroMutilWidgets.centeredText(panelX, y + 20, panelW,
                "§8暂无队伍，点击上方按钮创建", EspetroMutilWidgets.MUTED));
        } else {
            int listW = panelW - 24;
            int entryH = 16;
            int listY = y;
            for (PartyListPacket.PartyInfo p : latest.parties) {
                String lockIcon = p.locked ? "§c🔒 " : "";
                String pwIcon = p.hasPassword ? " §7🔑" : "";
                boolean isMyParty = p.myPartyId != null && p.myPartyId.equals(latest.myPartyId);
                String ownerText = "§f" + p.ownerName + " 的队伍";
                String infoText = "§7[" + p.memberCount + "/" + latest.maxPartySize + "]";
                String fullText = lockIcon + ownerText + pwIcon + "  " + infoText;
                if (isMyParty) fullText = "§a§l● " + fullText;

                String label = EspetroMutilWidgets.trimToWidth(fullText, listW - 50);
                root.addChild(EspetroMutilWidgets.text(panelX + 16, listY + 2,
                    label, isMyParty ? 0xFFFFFF : EspetroMutilWidgets.TEXT));
                // 加入按钮
                if (latest.myPartyId == null && !p.locked) {
                    int jbw = EspetroMutilWidgets.textButtonWidth("加入");
                    root.addChild(EspetroMutilWidgets.button(
                        panelX + panelW - 30 - jbw, listY, jbw, 14,
                        "§a加入", () -> {
                            if (!tutorialPreviewMode) {
                                if (p.hasPassword) {
                                    Minecraft.getInstance().setScreen(
                                        new JoinPartyScreen(this, p.partyId));
                                } else {
                                    NetworkManager.sendPartyJoin(p.partyId, "");
                                }
                            }
                        })
                        .setColors(0x00000000, 0x20254530, 0x30305530)
                        .setBorderColor(0x00000000));
                }
                listY += entryH;
            }
        }
    }

    @Override
    public void tick() {
        super.tick();
        refreshTimer++;
        if (refreshTimer % 60 == 0) {
            // 每 3 秒拉取最新队伍列表
            NetworkManager.requestPartyList();
        }
    }

    @Override
    public void onClose() {
        super.onClose();
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return true;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // ==================== 子界面：创建队伍 ====================

    /** 简单输入密码并创建队伍。 */
    private static final class CreatePartyScreen extends MutilScreen {
        private final PartyScreen parent;
        private EditBox passwordField;

        CreatePartyScreen(PartyScreen parent) {
            super(Component.literal("创建队伍"));
            this.parent = parent;
        }

        @Override
        protected void init() {
            super.init();
            int pw = Math.min(280, width - 40);
            int px = (width - pw) / 2;
            int py = (height - 100) / 2;
            int bw = pw - 60;
            int bx = px + 30;
            passwordField = new EditBox(Minecraft.getInstance().font, bx, py + 42, bw, 14,
                Component.literal("密码"));
            passwordField.setMaxLength(64);
            passwordField.setBordered(true);
            passwordField.setCanLoseFocus(true);
            addRenderableWidget(passwordField);
        }

        @Override
        protected void renderBeforeMutil(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            EspetroMutilWidgets.drawScreenShade(graphics, this.width, this.height);
        }

        @Override
        protected void buildMutilRoot(GuiElement root) {
            int pw = Math.min(280, width - 40);
            int ph = 100;
            int px = (width - pw) / 2;
            int py = (height - ph) / 2;

            root.addChild(EspetroMutilWidgets.panel(px, py, pw, ph, 0x00000000, 0x00000000));
            root.addChild(EspetroMutilWidgets.centeredText(px, py + 8, pw,
                "\u00a7b创建新队伍", EspetroMutilWidgets.TEXT));
            root.addChild(EspetroMutilWidgets.centeredText(px, py + 24, pw,
                "\u00a77输入密码（留空则不设密码）", EspetroMutilWidgets.MUTED));

            int bw = pw - 60;
            int bx = px + 30;
            root.addChild(EspetroMutilWidgets.button(bx, py + 60, bw / 2 - 4, 14,
                "\u00a7a创建（无密码）", () -> {
                    if (!tutorialPreviewMode) {
                        NetworkManager.sendPartyCreate("");
                        Minecraft.getInstance().setScreen(parent);
                    }
                })
                .setColors(0x00000000, 0x20303050, 0x30404060)
                .setBorderColor(0x00000000));
            root.addChild(EspetroMutilWidgets.button(bx + bw / 2 + 4, py + 60, bw / 2 - 4, 14,
                "\u00a76创建", () -> {
                    if (!tutorialPreviewMode) {
                        String pwd = passwordField != null ? passwordField.getValue().trim() : "";
                        NetworkManager.sendPartyCreate(pwd);
                        Minecraft.getInstance().setScreen(parent);
                    }
                })
                .setColors(0x00000000, 0x20303050, 0x30404060)
                .setBorderColor(0x00000000));

            int cbw = EspetroMutilWidgets.textButtonWidth("\u00a7c返回");
            root.addChild(EspetroMutilWidgets.button(px + pw / 2 - cbw / 2, py + 78, cbw, 14,
                "\u00a7c返回", () -> Minecraft.getInstance().setScreen(parent))
                .setColors(0x00000000, 0x20402020, 0x30502020)
                .setBorderColor(0x00000000));
        }

        @Override
        public boolean shouldCloseOnEsc() { return true; }
        @Override
        public boolean isPauseScreen() { return false; }
    }

    // ==================== 子界面：加入队伍 ====================

    private static final class JoinPartyScreen extends MutilScreen {
        private final PartyScreen parent;
        private final UUID partyId;

        JoinPartyScreen(PartyScreen parent, UUID partyId) {
            super(Component.literal("输入密码"));
            this.parent = parent;
            this.partyId = partyId;
        }

        @Override
        protected void renderBeforeMutil(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            EspetroMutilWidgets.drawScreenShade(graphics, this.width, this.height);
        }

        @Override
        protected void buildMutilRoot(GuiElement root) {
            int pw = Math.min(240, width - 40);
            int ph = 70;
            int px = (width - pw) / 2;
            int py = (height - ph) / 2;

            root.addChild(EspetroMutilWidgets.panel(px, py, pw, ph, 0x00000000, 0x00000000));
            root.addChild(EspetroMutilWidgets.centeredText(px, py + 8, pw,
                "§b输入队伍密码", EspetroMutilWidgets.TEXT));

            int bw = pw - 60;
            int bx = px + 30;
            root.addChild(EspetroMutilWidgets.button(bx, py + 30, bw / 2 - 4, 14,
                "§a确认加入（1234）", () -> {
                    if (!tutorialPreviewMode) {
                        NetworkManager.sendPartyJoin(partyId, "1234");
                        Minecraft.getInstance().setScreen(parent);
                    }
                })
                .setColors(0x00000000, 0x20303050, 0x30404060)
                .setBorderColor(0x00000000));
            root.addChild(EspetroMutilWidgets.button(bx + bw / 2 + 4, py + 30, bw / 2 - 4, 14,
                "§c返回", () -> Minecraft.getInstance().setScreen(parent))
                .setColors(0x00000000, 0x20402020, 0x30502020)
                .setBorderColor(0x00000000));
        }

        @Override
        public boolean shouldCloseOnEsc() { return true; }
        @Override
        public boolean isPauseScreen() { return false; }
    }

    // ==================== 子界面：管理队伍 ====================

    private static final class ManagePartyScreen extends MutilScreen {
        private final PartyScreen parent;
        private final UUID partyId;

        ManagePartyScreen(PartyScreen parent, UUID partyId) {
            super(Component.literal("管理队伍"));
            this.parent = parent;
            this.partyId = partyId;
        }

        @Override
        protected void renderBeforeMutil(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            EspetroMutilWidgets.drawScreenShade(graphics, this.width, this.height);
        }

        @Override
        protected void buildMutilRoot(GuiElement root) {
            int pw = Math.min(300, width - 40);
            int py = (height - 110) / 2; // smaller panel without password button
            int px = (width - pw) / 2;
            int gap = 4;

            root.addChild(EspetroMutilWidgets.panel(px, py, pw, 110, 0x00000000, 0x00000000));
            root.addChild(EspetroMutilWidgets.centeredText(px, py + 6, pw,
                "§b管理队伍", EspetroMutilWidgets.TEXT));

            PartyListPacket.PartyInfo myInfo = null;
            if (latest != null) {
                for (PartyListPacket.PartyInfo p : latest.parties) {
                    if (p.partyId.equals(partyId)) { myInfo = p; break; }
                }
            }
            boolean isLocked = myInfo != null && myInfo.locked;

            int bw = pw - 40;
            int bx = px + 20;
            int by = py + 26;
            int bh = 14;

            root.addChild(EspetroMutilWidgets.button(bx, by, bw, bh,
                isLocked ? "§a解锁队伍（允许加入）" : "§c锁定队伍（禁止加入）",
                () -> {
                    if (!tutorialPreviewMode) {
                        NetworkManager.sendPartyToggleLock(partyId);
                        Minecraft.getInstance().setScreen(parent);
                    }
                })
                .setColors(0x00000000, 0x20303050, 0x30404060)
                .setBorderColor(0x00000000));
            by += bh + gap;

            root.addChild(EspetroMutilWidgets.button(bx, by, bw, bh,
                "§c解散队伍",
                () -> {
                    if (!tutorialPreviewMode) {
                        NetworkManager.sendPartyDisband(partyId);
                        Minecraft.getInstance().setScreen(parent);
                    }
                })
                .setColors(0x00000000, 0x20402020, 0x30502020)
                .setBorderColor(0x00000000));
            by += bh + gap;

            int cbw = EspetroMutilWidgets.textButtonWidth("§7返回");
            root.addChild(EspetroMutilWidgets.button(px + pw / 2 - cbw / 2, by + 8, cbw, 14,
                "§7返回", () -> Minecraft.getInstance().setScreen(parent))
                .setColors(0x00000000, 0x20404040, 0x30505050)
                .setBorderColor(0x00000000));
        }

        @Override
        public boolean shouldCloseOnEsc() { return true; }
        @Override
        public boolean isPauseScreen() { return false; }
    }
}
