package org.espetro.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.espetro.network.GovernanceActionPacket;
import org.espetro.network.GovernanceStatePacket;
import org.espetro.network.MatchStatsActionPacket;
import org.espetro.network.MatchStatsSyncPacket;
import org.espetro.network.NetworkManager;
import se.mickelus.mutil.gui.GuiElement;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Two-column per-round scoreboard. Rows are grouped by squad; unsquadded and
 * offline players are sorted alphabetically at the bottom. Right-click opens
 * the server-authoritative squad context menu.
 */
public final class MatchScoreboardScreen extends MutilScreen {

    private static MatchStatsSyncPacket latestStats = new MatchStatsSyncPacket(List.of());

    private final Screen parent;
    private final List<HitRow> hitRows = new ArrayList<>();
    private final List<GovernanceHit> governanceHits = new ArrayList<>();
    private MatchStatsSyncPacket.Row contextRow;
    private int contextX;
    private int contextY;

    public MatchScoreboardScreen(Screen parent) {
        super(Component.literal("玩家分数板"));
        this.parent = parent;
    }

    public Screen getParent() {
        return parent;
    }

    public static void updateStats(MatchStatsSyncPacket packet) {
        latestStats = packet == null ? new MatchStatsSyncPacket(List.of()) : packet;
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof MatchScoreboardScreen screen) {
            screen.contextRow = null;
        }
    }

    /** Kept for callers; state lives in {@link ClientGovernanceState}. */
    public static void updateGovernance(GovernanceStatePacket packet) {
        ClientGovernanceState.update(packet);
    }

    public static String nameFor(UUID uuid) {
        if (uuid == null) return "无";
        for (MatchStatsSyncPacket.Row row : latestStats.rows) {
            if (uuid.equals(row.uuid)) return row.name;
        }
        return uuid.toString().substring(0, 8);
    }

    @Override
    protected void buildMutilRoot(GuiElement root) {
        int top = 8;
        root.addChild(new ScoreboardCanvas(width, height));
        root.addChild(EspetroMutilWidgets.button(width - 50, top, 42, 18,
            "返回", this::onClose));
    }

    private final class ScoreboardCanvas extends GuiElement {
        private ScoreboardCanvas(int canvasWidth, int canvasHeight) {
            super(0, 0, canvasWidth, canvasHeight);
        }

        @Override
        public void draw(GuiGraphics graphics, int x, int y, int drawWidth, int drawHeight,
                         int mouseX, int mouseY, float partialTick) {
            hitRows.clear();
            governanceHits.clear();
            graphics.drawCenteredString(font, "§6§l本回合玩家分数板",
                width / 2, 10, 0xFFFFFF);
            renderTeamColumn(graphics, "ATTACK", 6, 34, width / 2 - 9, 0xFFD35B50);
            renderTeamColumn(graphics, "DEFEND", width / 2 + 3, 34,
                width / 2 - 9, 0xFF5685C7);
            renderGovernance(graphics, mouseX, mouseY);
            renderContextMenu(graphics, mouseX, mouseY);
            super.draw(graphics, x, y, drawWidth, drawHeight, mouseX, mouseY, partialTick);
        }
    }

    private void renderTeamColumn(GuiGraphics graphics, String team, int x, int y, int w, int accent) {
        graphics.fill(x, y, x + w, height - 8, 0xB0181B1D);
        graphics.renderOutline(x, y, w, height - 8 - y, accent);
        String title = "ATTACK".equals(team) ? "§c进攻方" : "§9防守方";
        graphics.drawString(font, title, x + 5, y + 5, 0xFFFFFF);
        graphics.drawString(font, "玩家", x + 5, y + 18, 0xFFBFC3C5);
        graphics.drawString(font, "击杀", x + w - 92, y + 18, 0xFFBFC3C5);
        graphics.drawString(font, "死亡", x + w - 61, y + 18, 0xFFBFC3C5);
        graphics.drawString(font, "职业", x + w - 30, y + 18, 0xFFBFC3C5);

        List<MatchStatsSyncPacket.Row> rows = latestStats.rows.stream()
            .filter(r -> team.equals(r.team))
            .sorted(Comparator
                .comparing((MatchStatsSyncPacket.Row r) -> !r.online || r.squadId < 0)
                .thenComparing(r -> r.squadName == null ? "" : r.squadName,
                    String.CASE_INSENSITIVE_ORDER)
                .thenComparing(r -> r.name == null ? "" : r.name,
                    String.CASE_INSENSITIVE_ORDER))
            .toList();

        int rowY = y + 31;
        String previousSquad = null;
        for (MatchStatsSyncPacket.Row row : rows) {
            if (rowY > height - 78) break;
            String group = row.online && row.squadId >= 0 ? row.squadName : "未编组/离线";
            if (!Objects.equals(group, previousSquad)) {
                graphics.fill(x + 3, rowY, x + w - 3, rowY + 11, 0x70363B3E);
                graphics.drawString(font, "§6" + (group == null || group.isBlank() ? "小队" : group),
                    x + 6, rowY + 2, 0xFFFFFF);
                rowY += 12;
                previousSquad = group;
            }

            int color = row.online ? 0xFFF1F1F1 : 0xFF777777;
            if ((rowY / 12 & 1) == 0) graphics.fill(x + 3, rowY, x + w - 3, rowY + 12, 0x30111111);
            String name = font.plainSubstrByWidth(row.name, Math.max(20, w - 105));
            graphics.drawString(font, name, x + 6, rowY + 2, color);
            graphics.drawString(font, Integer.toString(row.kills), x + w - 83, rowY + 2, color);
            graphics.drawString(font, Integer.toString(row.deaths), x + w - 51, rowY + 2, color);
            // Prefer path (IconImage) then jar slug (icon), then classId fallback.
            ResourceLocation icon = RoleIconResources.resolveForScoreboard(
                row.classIconImage, row.classIcon, row.classId);
            if (icon != null) {
                // Full 128×128 UV → 11×11 screen (same as deploy class buttons).
                int iconSize = 11;
                int iconX = x + w - 27;
                int iconY = rowY;
                graphics.blit(icon, iconX, iconY, iconSize, iconSize,
                    0.0f, 0.0f,
                    RoleIconResources.TEXTURE_SIZE, RoleIconResources.TEXTURE_SIZE,
                    RoleIconResources.TEXTURE_SIZE, RoleIconResources.TEXTURE_SIZE);
            } else {
                graphics.drawString(font, row.classId == null || row.classId.isBlank() ? "-" : "●",
                    x + w - 24, rowY + 2, color);
            }
            hitRows.add(new HitRow(x + 3, rowY, w - 6, 12, row));
            rowY += 12;
        }
    }

    private void renderGovernance(GuiGraphics graphics, int mouseX, int mouseY) {
        String myTeam = ClientGameState.getPlayerTeam();
        if (myTeam == null) return;
        GovernanceStatePacket.TeamState state = ClientGovernanceState.forTeam(myTeam);
        if (state == null || "IDLE".equals(state.state)) return;

        int barH = 62;
        int w = Math.min(420, width - 24);
        int x = (width - w) / 2;
        int y = height - barH - 8;
        graphics.fill(x, y, x + w, height - 8, 0xF0191714);
        graphics.renderOutline(x, y, w, height - 8 - y, 0xFFFFB84D);

        String stateLabel = switch (state.state) {
            case "IMPEACHMENT_VOTE" -> "弹劾投票";
            case "VACANCY_VOLUNTEER" -> "指挥官空缺·志愿";
            case "VACANCY_VOTE" -> "空缺公投";
            default -> state.state;
        };
        graphics.drawCenteredString(font,
            "§6" + stateLabel + " §e" + ClientGovernanceState.secondsLeft(state) + "s",
            width / 2, y + 4, 0xFFFFFF);

        if ("VACANCY_VOLUNTEER".equals(state.state)) {
            graphics.drawCenteredString(font,
                "§7小队长请按 J 打开战术面板点击「志愿补位」",
                width / 2, y + 20, 0xFFE0E0E0);
            graphics.drawCenteredString(font,
                "志愿者: " + state.volunteers.stream()
                    .map(MatchScoreboardScreen::nameFor).reduce((a, b) -> a + ", " + b).orElse("暂无"),
                width / 2, y + 36, 0xFFB0B0B0);
            return;
        }

        List<UUID> candidates = new ArrayList<>();
        List<String> prefixes = new ArrayList<>();
        if ("IMPEACHMENT_VOTE".equals(state.state)) {
            if (state.commander != null) {
                candidates.add(state.commander);
                prefixes.add("原指挥官");
            }
            if (state.challenger != null) {
                candidates.add(state.challenger);
                prefixes.add("挑战者");
            }
        } else if ("VACANCY_VOTE".equals(state.state)) {
            for (UUID v : state.volunteers) {
                candidates.add(v);
                prefixes.add("志愿者");
            }
        }

        if (candidates.isEmpty()) {
            graphics.drawCenteredString(font, "§7等待候选人…", width / 2, y + 28, 0xFF999999);
            return;
        }

        int slotW = Math.min(180, (w - 16) / candidates.size());
        int totalW = slotW * candidates.size();
        int startX = x + (w - totalW) / 2;
        int slotY = y + 18;
        int slotH = 34;

        GovernanceActionPacket.Action action = "IMPEACHMENT_VOTE".equals(state.state)
            ? GovernanceActionPacket.Action.VOTE_IMPEACHMENT
            : GovernanceActionPacket.Action.VOTE_VACANCY;

        for (int i = 0; i < candidates.size(); i++) {
            UUID candidate = candidates.get(i);
            int sx = startX + i * slotW + 2;
            int sw = slotW - 4;
            boolean mine = ClientGovernanceState.isMyVote(state, candidate);
            boolean hovered = mouseX >= sx && mouseX < sx + sw
                && mouseY >= slotY && mouseY < slotY + slotH;
            int bg = mine ? 0xE02A4A32 : (hovered ? 0xE0404048 : 0xE028282C);
            int border = mine ? 0xFF6FCF97 : (hovered ? 0xFFFFB84D : 0xFF666666);
            graphics.fill(sx, slotY, sx + sw, slotY + slotH, bg);
            graphics.renderOutline(sx, slotY, sw, slotH, border);

            int votes = ClientGovernanceState.voteCount(state, candidate);
            String name = nameFor(candidate);
            String title = (mine ? "§a✓ " : "") + "§f" + prefixes.get(i);
            graphics.drawCenteredString(font, title, sx + sw / 2, slotY + 4, 0xFFFFFF);
            graphics.drawCenteredString(font, "§e" + name, sx + sw / 2, slotY + 14, 0xFFFFFF);
            graphics.drawCenteredString(font, "§b" + votes + " 票", sx + sw / 2, slotY + 24, 0xFFFFFF);

            governanceHits.add(new GovernanceHit(sx, slotY, sw, slotH, action, candidate));
        }
    }

    private void renderContextMenu(GuiGraphics graphics, int mouseX, int mouseY) {
        if (contextRow == null) return;
        int menuW = 76;
        int menuH = 34;
        int x = Math.min(contextX, width - menuW - 2);
        int y = Math.min(contextY, height - menuH - 2);
        graphics.fill(x, y, x + menuW, y + menuH, 0xF0202020);
        graphics.renderOutline(x, y, menuW, menuH, 0xFF777777);
        boolean canJoin = canForceJoin(contextRow);
        boolean canKick = canKick(contextRow);
        graphics.drawString(font, (canJoin ? "§a" : "§8") + "拉进小队", x + 6, y + 5, 0xFFFFFF);
        graphics.drawString(font, (canKick ? "§c" : "§8") + "踢出小队", x + 6, y + 19, 0xFFFFFF);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (contextRow != null && button == 0) {
            int x = Math.min(contextX, width - 78);
            int y = Math.min(contextY, height - 36);
            if (mouseX >= x && mouseX <= x + 76) {
                if (mouseY >= y && mouseY < y + 17 && canForceJoin(contextRow)) {
                    NetworkManager.sendMatchStatsAction(MatchStatsActionPacket.Action.FORCE_JOIN_SQUAD,
                        contextRow.uuid);
                    contextRow = null;
                    return true;
                }
                if (mouseY >= y + 17 && mouseY <= y + 34 && canKick(contextRow)) {
                    NetworkManager.sendMatchStatsAction(MatchStatsActionPacket.Action.KICK_FROM_SQUAD,
                        contextRow.uuid);
                    contextRow = null;
                    return true;
                }
            }
            contextRow = null;
        }
        if (button == 0 && handleGovernanceVote(mouseX, mouseY)) return true;
        if (button == 1) {
            for (HitRow hit : hitRows) {
                if (hit.contains(mouseX, mouseY)) {
                    contextRow = hit.row;
                    contextX = (int) mouseX;
                    contextY = (int) mouseY;
                    return true;
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    private boolean handleGovernanceVote(double mouseX, double mouseY) {
        for (GovernanceHit hit : governanceHits) {
            if (hit.contains(mouseX, mouseY) && hit.candidate != null) {
                NetworkManager.sendGovernanceAction(hit.action, hit.candidate);
                return true;
            }
        }
        return false;
    }

    private boolean canForceJoin(MatchStatsSyncPacket.Row row) {
        Minecraft mc = Minecraft.getInstance();
        return mc.player != null && row.online
            && !mc.player.getUUID().equals(row.uuid)
            && Objects.equals(ClientGameState.getPlayerTeam(), row.team)
            && ClientTacticalState.isLocalSquadLeader(mc.player.getName().getString())
            && row.squadId < 0;
    }

    private boolean canKick(MatchStatsSyncPacket.Row row) {
        Minecraft mc = Minecraft.getInstance();
        return mc.player != null && row.online
            && !mc.player.getUUID().equals(row.uuid)
            && Objects.equals(ClientGameState.getPlayerTeam(), row.team)
            && ClientTacticalState.isLocalSquadLeader(mc.player.getName().getString())
            && row.squadId >= 0 && row.squadId == ClientTacticalState.getMySquadId();
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private record HitRow(int x, int y, int w, int h, MatchStatsSyncPacket.Row row) {
        boolean contains(double px, double py) {
            return px >= x && px < x + w && py >= y && py < y + h;
        }
    }

    private record GovernanceHit(int x, int y, int w, int h,
                                 GovernanceActionPacket.Action action, UUID candidate) {
        boolean contains(double px, double py) {
            return px >= x && px < x + w && py >= y && py < y + h;
        }
    }
}
