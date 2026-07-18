package org.espetro.tutorial;

import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.espetro.Espetro;
import org.espetro.config.GameConfig;
import org.espetro.network.NetworkManager;
import org.espetro.network.TutorialSyncPacket;
import org.espetro.team.GamePhase;
import org.espetro.team.GameStateManager;
import org.espetro.team.VoteManager;

import java.util.ArrayDeque;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;

/**
 * 引导式教程服务端会话管理。进度仅保留在当前连接会话内。
 * 同一时刻只展示一步；其余进入队列，由「下一步」或关闭后弹出。
 */
public final class TutorialManager {

    public enum Action {
        NEXT,
        SKIP_ALL,
        DISMISS
    }

    private static final TutorialManager INSTANCE = new TutorialManager();

    private final Map<UUID, Session> sessions = new HashMap<>();

    private TutorialManager() {
    }

    public static TutorialManager getInstance() {
        return INSTANCE;
    }

    public void onPlayerJoin(ServerPlayer player, boolean midGame) {
        if (!GameConfig.isTutorialEnabled()) {
            return;
        }
        Session session = sessions.computeIfAbsent(player.getUUID(), id -> new Session());
        session.skipped = false;
        session.shownSteps.clear();
        session.pending.clear();
        session.currentStep = null;

        if (!GameConfig.isTutorialShowOnJoin()) {
            return;
        }

        if (midGame) {
            enqueue(player, TutorialStep.MID_JOIN);
        } else {
            enqueue(player, TutorialStep.WELCOME);
            enqueue(player, TutorialStep.TEAM_SELECT);
            enqueue(player, TutorialStep.PHASE_OVERVIEW);
            enqueue(player, TutorialStep.KEYS_KJY);
        }

        GamePhase phase = GameStateManager.getInstance().getCurrentPhase();
        TutorialStep phaseStep = TutorialStep.primaryForPhase(phase);
        if (phaseStep != null && phaseStep != TutorialStep.TEAM_SELECT) {
            enqueue(player, phaseStep);
        }
        enqueueTeamSpecificForPhase(player, phase);
        flushDisplay(player);
    }

    public void onPlayerLeave(UUID uuid) {
        sessions.remove(uuid);
    }

    public void onPhaseChanged(GamePhase phase) {
        if (!GameConfig.isTutorialEnabled() || phase == null) {
            return;
        }
        MinecraftServer server = Espetro.getServer();
        if (server == null) {
            return;
        }

        TutorialStep primary = TutorialStep.primaryForPhase(phase);
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (primary != null) {
                enqueue(player, primary);
            }
            enqueueTeamSpecificForPhase(player, phase);

            if (phase == GamePhase.BATTLE) {
                enqueue(player, TutorialStep.TROOPS);
                enqueue(player, TutorialStep.STAMINA);
                enqueue(player, TutorialStep.NAMETAG);
                if (VoteManager.getInstance().isCommander(player.getUUID())) {
                    enqueue(player, TutorialStep.BASTION);
                    enqueue(player, TutorialStep.VEHICLE);
                    enqueue(player, TutorialStep.COMMANDER_SKILLS);
                }
            }
            flushDisplay(player);
        }
    }

    private void enqueueTeamSpecificForPhase(ServerPlayer player, GamePhase phase) {
        if (phase == null) {
            return;
        }
        String team = Espetro.getPlayerTeam(player);
        if (phase == GamePhase.DEPLOYING) {
            if ("ATTACK".equals(team)) {
                enqueue(player, TutorialStep.DEPLOY_ATTACK_WAIT);
            } else if ("DEFEND".equals(team)) {
                enqueue(player, TutorialStep.DEPLOY_DEFEND_BUILD);
                enqueue(player, TutorialStep.OUTPOST);
            }
            if (VoteManager.getInstance().isCommander(player.getUUID())) {
                enqueue(player, TutorialStep.BASTION);
                enqueue(player, TutorialStep.VEHICLE);
                enqueue(player, TutorialStep.COMMANDER_SKILLS);
            }
            enqueue(player, TutorialStep.SQUAD);
            enqueue(player, TutorialStep.TEAM_PACK);
        }
    }

    /**
     * 尝试向玩家展示某一步（未展示过则入队；当前无展示则立即弹出）。
     */
    public boolean tryShow(ServerPlayer player, TutorialStep step) {
        return tryShow(player, step, false);
    }

    public boolean tryShow(ServerPlayer player, TutorialStep step, boolean force) {
        if (player == null || step == null || !GameConfig.isTutorialEnabled()) {
            return false;
        }
        Session session = sessions.computeIfAbsent(player.getUUID(), id -> new Session());
        if (session.skipped && !force) {
            return false;
        }
        if (force) {
            session.skipped = false;
            session.shownSteps.remove(step);
            session.pending.remove(step);
            // 强制时优先立即展示
            display(player, session, step);
            return true;
        }
        if (!enqueue(player, step)) {
            return false;
        }
        flushDisplay(player);
        return true;
    }

    public void handleAction(ServerPlayer player, Action action, String stepId) {
        if (player == null || action == null) {
            return;
        }
        if (!GameConfig.isTutorialEnabled()) {
            clearClient(player);
            return;
        }

        Session session = sessions.computeIfAbsent(player.getUUID(), id -> new Session());
        TutorialStep step = TutorialStep.byId(stepId);
        if (step == null) {
            step = session.currentStep;
        }

        switch (action) {
            case SKIP_ALL -> {
                if (!GameConfig.isTutorialAllowSkip()) {
                    player.sendSystemMessage(Component.translatable("tutorial.msg.skip_disabled"));
                    return;
                }
                session.skipped = true;
                session.currentStep = null;
                session.pending.clear();
                clearClient(player);
                player.sendSystemMessage(Component.translatable("tutorial.msg.skipped"));
            }
            case DISMISS -> {
                if (step != null) {
                    session.shownSteps.add(step);
                }
                session.currentStep = null;
                if (!flushDisplay(player)) {
                    clearClient(player);
                }
            }
            case NEXT -> {
                if (step != null) {
                    session.shownSteps.add(step);
                }
                session.currentStep = null;
                // 优先队列，再按枚举顺序找未展示步骤
                if (flushDisplay(player)) {
                    return;
                }
                TutorialStep next = findNextEligible(player, session, step);
                if (next != null) {
                    display(player, session, next);
                } else {
                    clearClient(player);
                    player.sendSystemMessage(Component.translatable("tutorial.msg.complete"));
                }
            }
        }
    }

    /**
     * 命令：重新打开欢迎与当前阶段步骤。
     */
    public void reopen(ServerPlayer player) {
        if (!GameConfig.isTutorialEnabled()) {
            player.sendSystemMessage(Component.translatable("tutorial.msg.disabled"));
            return;
        }
        Session session = sessions.computeIfAbsent(player.getUUID(), id -> new Session());
        session.skipped = false;
        tryShow(player, TutorialStep.WELCOME, true);
        TutorialStep phaseStep = TutorialStep.primaryForPhase(
            GameStateManager.getInstance().getCurrentPhase());
        if (phaseStep != null && phaseStep != TutorialStep.WELCOME) {
            enqueue(player, phaseStep);
        }
    }

    public void skipAll(ServerPlayer player) {
        handleAction(player, Action.SKIP_ALL, null);
    }

    public String statusLine(ServerPlayer player) {
        boolean enabled = GameConfig.isTutorialEnabled();
        Session session = sessions.get(player.getUUID());
        boolean skipped = session != null && session.skipped;
        String current = session != null && session.currentStep != null
            ? session.currentStep.getId() : "-";
        int shown = session != null ? session.shownSteps.size() : 0;
        int pending = session != null ? session.pending.size() : 0;
        return "enabled=" + enabled
            + " show_on_join=" + GameConfig.isTutorialShowOnJoin()
            + " allow_skip=" + GameConfig.isTutorialAllowSkip()
            + " skipped=" + skipped
            + " current=" + current
            + " pending=" + pending
            + " shown=" + shown + "/" + TutorialStep.totalCount();
    }

    /**
     * 配置热重载后：若教程关闭则清理全部客户端。
     */
    public void onConfigReloaded() {
        if (GameConfig.isTutorialEnabled()) {
            return;
        }
        MinecraftServer server = Espetro.getServer();
        if (server == null) {
            sessions.clear();
            return;
        }
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            clearClient(player);
        }
        sessions.clear();
    }

    public void clearClient(ServerPlayer player) {
        NetworkManager.sendToPlayer(player, TutorialSyncPacket.clear());
    }

    private boolean enqueue(ServerPlayer player, TutorialStep step) {
        if (player == null || step == null || !GameConfig.isTutorialEnabled()) {
            return false;
        }
        Session session = sessions.computeIfAbsent(player.getUUID(), id -> new Session());
        if (session.skipped) {
            return false;
        }
        if (session.shownSteps.contains(step) || session.currentStep == step || session.pending.contains(step)) {
            return false;
        }
        if (!matchesFilter(player, step.getTeamFilter())) {
            return false;
        }
        session.pending.add(step);
        return true;
    }

    private boolean flushDisplay(ServerPlayer player) {
        Session session = sessions.get(player.getUUID());
        if (session == null || session.skipped || session.currentStep != null) {
            return session != null && session.currentStep != null;
        }
        while (!session.pending.isEmpty()) {
            TutorialStep next = session.pending.poll();
            if (next == null || session.shownSteps.contains(next)) {
                continue;
            }
            if (!matchesFilter(player, next.getTeamFilter())) {
                continue;
            }
            display(player, session, next);
            return true;
        }
        return false;
    }

    private void display(ServerPlayer player, Session session, TutorialStep step) {
        session.currentStep = step;
        session.pending.remove(step);
        NetworkManager.sendToPlayer(player, TutorialSyncPacket.show(
            step.getId(),
            step.ordinalIndex(),
            TutorialStep.totalCount(),
            GameConfig.isTutorialAllowSkip()
        ));
        sendChatHint(player, step);
    }

    private TutorialStep findNextEligible(ServerPlayer player, Session session, TutorialStep from) {
        TutorialStep[] all = TutorialStep.values();
        int start = from == null ? 0 : from.ordinal() + 1;
        for (int i = start; i < all.length; i++) {
            TutorialStep candidate = all[i];
            if (session.shownSteps.contains(candidate) || session.pending.contains(candidate)) {
                continue;
            }
            if (!matchesFilter(player, candidate.getTeamFilter())) {
                continue;
            }
            return candidate;
        }
        return null;
    }

    private boolean matchesFilter(ServerPlayer player, TutorialStep.TeamFilter filter) {
        if (filter == null || filter == TutorialStep.TeamFilter.ALL) {
            return true;
        }
        if (filter == TutorialStep.TeamFilter.COMMANDER) {
            return VoteManager.getInstance().isCommander(player.getUUID());
        }
        String team = Espetro.getPlayerTeam(player);
        if (team == null) {
            return false;
        }
        if (filter == TutorialStep.TeamFilter.ATTACK) {
            return "ATTACK".equals(team);
        }
        if (filter == TutorialStep.TeamFilter.DEFEND) {
            return "DEFEND".equals(team);
        }
        return true;
    }

    private void sendChatHint(ServerPlayer player, TutorialStep step) {
        MutableComponent header = Component.literal("§6[Espetro 教程] §e")
            .append(Component.translatable(step.titleKey()))
            .append(Component.literal(" §7(" + step.ordinalIndex() + "/" + TutorialStep.totalCount() + ")"));
        player.sendSystemMessage(header);

        MutableComponent body = Component.literal("§7")
            .append(Component.translatable(step.bodyKey()));
        player.sendSystemMessage(body);

        MutableComponent actions = Component.literal("§a");
        actions.append(clickable("tutorial.btn.next", "/espetro tutorial next", "§a"));
        actions.append(Component.literal(" §8| "));
        actions.append(clickable("tutorial.btn.dismiss", "/espetro tutorial dismiss", "§e"));
        if (GameConfig.isTutorialAllowSkip()) {
            actions.append(Component.literal(" §8| "));
            actions.append(clickable("tutorial.btn.skip", "/espetro tutorial skip", "§c"));
        }
        player.sendSystemMessage(actions);
    }

    private MutableComponent clickable(String langKey, String command, String colorCode) {
        return Component.literal(colorCode + "[")
            .append(Component.translatable(langKey))
            .append(Component.literal("]"))
            .withStyle(Style.EMPTY
                .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, command))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                    Component.translatable(langKey))));
    }

    private static final class Session {
        private boolean skipped;
        private TutorialStep currentStep;
        private final EnumSet<TutorialStep> shownSteps = EnumSet.noneOf(TutorialStep.class);
        private final Queue<TutorialStep> pending = new ArrayDeque<>();
    }
}
