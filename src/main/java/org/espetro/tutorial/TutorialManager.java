package org.espetro.tutorial;

import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.espetro.Espetro;
import org.espetro.config.GameConfig;
import org.espetro.network.NetworkManager;
import org.espetro.network.TutorialSyncPacket;
import org.espetro.team.GamePhase;

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

    /**
     * 进服：仅建立会话，不自动弹教程（show_on_join 强制忽略，改为手动 reopen）。
     */
    public void onPlayerJoin(ServerPlayer player, boolean midGame) {
        if (player == null) {
            return;
        }
        sessions.computeIfAbsent(player.getUUID(), id -> new Session());
    }

    public void onPlayerLeave(UUID uuid) {
        sessions.remove(uuid);
    }

    /**
     * 阶段切换：不自动弹 tip（仅手动教程）。
     */
    public void onPhaseChanged(GamePhase phase) {
        // no-op: 教程仅主城按钮 / /espetro tutorial 触发
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
                // 教程只推进当前事件已经排入的步骤，避免一次操作弹出整套手册。
                if (flushDisplay(player)) {
                    return;
                }
                clearClient(player);
                player.sendSystemMessage(Component.translatable("tutorial.msg.complete"));
            }
        }
    }

    /**
     * 主城 / 命令：按完整目录顺序播放全部 GUI/阶段说明。
     */
    public void reopen(ServerPlayer player) {
        if (!GameConfig.isTutorialEnabled()) {
            player.sendSystemMessage(Component.translatable("tutorial.msg.disabled"));
            return;
        }
        Session session = sessions.computeIfAbsent(player.getUUID(), id -> new Session());
        session.skipped = false;
        session.shownSteps.clear();
        session.pending.clear();
        session.currentStep = null;
        clearClient(player);

        for (TutorialStep step : TutorialStep.values()) {
            session.pending.add(step);
        }
        player.sendSystemMessage(Component.translatable("tutorial.msg.started"));
        flushDisplay(player);
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
    }

    private static final class Session {
        private boolean skipped;
        private TutorialStep currentStep;
        private final EnumSet<TutorialStep> shownSteps = EnumSet.noneOf(TutorialStep.class);
        private final Queue<TutorialStep> pending = new ArrayDeque<>();
    }
}
