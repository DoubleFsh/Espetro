package org.espetro.governance;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.espetro.Espetro;
import org.espetro.config.GameConfig;
import org.espetro.network.NetworkManager;
import org.espetro.team.GamePhase;
import org.espetro.team.GameStateManager;
import org.espetro.team.SquadManager;
import org.espetro.team.VoteManager;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Commander impeachment and vacancy replacement.
 */
public final class CommanderGovernanceManager {

    public enum State {
        IDLE,
        IMPEACHMENT_VOTE,
        VACANCY_VOLUNTEER,
        VACANCY_VOTE
    }

    private static CommanderGovernanceManager INSTANCE;

    // Shared global impeachment cooldown (both teams).
    private long impeachmentCooldownUntilTick = 0;

    private final Map<String, TeamGovernance> byTeam = new HashMap<>();

    /** 最近断线的指挥官及其时间戳（UUID → 系统毫秒），用于 2 分钟内重连恢复 */
    private final Map<UUID, Long> recentCommanderDisconnect = new HashMap<>();
    private final Map<UUID, String> recentCommanderTeam = new HashMap<>();
    private static final long RECONNECT_GRACE_MILLIS = 2 * 60 * 1000; // 2 分钟

    public static final class TeamGovernance {
        public State state = State.IDLE;
        public UUID commander;
        public UUID challenger; // impeachment initiator
        public final Map<UUID, UUID> votes = new HashMap<>(); // voter -> candidate
        public final Set<UUID> volunteers = new HashSet<>();
        public int tickCounter;
        public int timeoutSeconds;
        public long endGameTime;
    }

    private CommanderGovernanceManager() {
        INSTANCE = this;
        byTeam.put("ATTACK", new TeamGovernance());
        byTeam.put("DEFEND", new TeamGovernance());
    }

    public static CommanderGovernanceManager getInstance() {
        if (INSTANCE == null) {
            INSTANCE = new CommanderGovernanceManager();
        }
        return INSTANCE;
    }

    public static void init() {
        INSTANCE = new CommanderGovernanceManager();
    }

    public TeamGovernance getTeam(String team) {
        return byTeam.computeIfAbsent(team, t -> new TeamGovernance());
    }

    public void reset() {
        impeachmentCooldownUntilTick = 0;
        for (TeamGovernance g : byTeam.values()) {
            g.state = State.IDLE;
            g.commander = null;
            g.challenger = null;
            g.votes.clear();
            g.volunteers.clear();
            g.tickCounter = 0;
            g.endGameTime = 0L;
        }
        recentCommanderDisconnect.clear();
        recentCommanderTeam.clear();
        NetworkManager.broadcastGovernanceState(this);
    }

    public void syncCommandersFromVoteManager() {
        VoteManager vm = VoteManager.getInstance();
        getTeam("ATTACK").commander = vm.getAttackCommander();
        getTeam("DEFEND").commander = vm.getDefendCommander();
        NetworkManager.broadcastGovernanceState(this);
        if (GameStateManager.getInstance().getCurrentPhase() != GamePhase.BATTLE) {
            return;
        }
        MinecraftServer server = Espetro.getServer();
        if (server == null) {
            return;
        }
        for (String team : List.of("ATTACK", "DEFEND")) {
            UUID commander = getTeam(team).commander;
            if (commander != null && server.getPlayerList().getPlayer(commander) == null) {
                clearCommander(team, "offline_at_battle_start");
                startVacancy(team);
            }
        }
    }

    /**
     * Records an election result immediately. A commander selected by a
     * no-vote random fallback is governed exactly like a voted commander.
     */
    public void acceptElectionResult(String team, @Nullable UUID commander) {
        if (!"ATTACK".equals(team) && !"DEFEND".equals(team)) {
            return;
        }
        TeamGovernance governance = getTeam(team);
        governance.state = State.IDLE;
        governance.commander = commander;
        governance.challenger = null;
        governance.votes.clear();
        governance.volunteers.clear();
        governance.tickCounter = 0;
        governance.endGameTime = 0L;
        NetworkManager.syncSquadsToTeam(team);
        NetworkManager.broadcastGovernanceState(this);
        Espetro.LOGGER.info("指挥官选举结果已同步到治理状态: team={}, commander={}",
            team, commander);
    }

    public boolean tryStartImpeachment(ServerPlayer initiator) {
        if (GameStateManager.getInstance().getCurrentPhase() != GamePhase.BATTLE) {
            return fail(initiator, "仅战斗阶段可弹劾指挥官");
        }
        String team = Espetro.getPlayerTeam(initiator);
        if (team == null) {
            return fail(initiator, "你尚未加入阵营");
        }
        if (!SquadManager.getInstance().isSquadLeader(initiator.getUUID())) {
            return fail(initiator, "只有小队长可以发起弹劾");
        }
        TeamGovernance g = getTeam(team);
        if (g.state != State.IDLE) {
            return fail(initiator, "当前已有治理流程进行中");
        }
        if (initiator.getUUID().equals(g.commander)) {
            return fail(initiator, "指挥官不能弹劾自己");
        }
        MinecraftServer server = initiator.server;
        if (server.overworld().getGameTime() < impeachmentCooldownUntilTick) {
            long left = (impeachmentCooldownUntilTick - server.overworld().getGameTime()) / 20;
            return fail(initiator, "弹劾冷却中，剩余 " + left + " 秒");
        }
        if (g.commander == null) {
            return fail(initiator, "当前没有指挥官可弹劾");
        }

        // Start cooldown immediately regardless of outcome.
        impeachmentCooldownUntilTick = server.overworld().getGameTime()
            + (long) GameConfig.getImpeachmentCooldownSeconds() * 20L;

        g.state = State.IMPEACHMENT_VOTE;
        g.challenger = initiator.getUUID();
        g.votes.clear();
        g.tickCounter = 0;
        g.timeoutSeconds = GameConfig.getImpeachmentVoteSeconds();
        g.endGameTime = server.overworld().getGameTime() + g.timeoutSeconds * 20L;
        Espetro.broadcastToTeam(team, "§e⚔ 小队长 " + initiator.getName().getString()
            + " 发起弹劾！按 J 打开战术面板投票（" + g.timeoutSeconds + "秒）");
        NetworkManager.broadcastGovernanceState(this);
        return true;
    }

    public boolean castImpeachmentVote(ServerPlayer voter, UUID candidate) {
        String team = Espetro.getPlayerTeam(voter);
        if (team == null) {
            return fail(voter, "你尚未加入阵营");
        }
        TeamGovernance g = getTeam(team);
        if (g.state != State.IMPEACHMENT_VOTE) {
            return fail(voter, "当前没有进行中的弹劾投票");
        }
        if (candidate == null
            || (!candidate.equals(g.commander) && !candidate.equals(g.challenger))) {
            return fail(voter, "无效的候选人");
        }
        g.votes.put(voter.getUUID(), candidate);
        String name = playerName(candidate);
        voter.displayClientMessage(
            net.minecraft.network.chat.Component.literal("§a已投票给 " + name), true);
        NetworkManager.broadcastGovernanceState(this);
        return true;
    }

    public boolean volunteerForVacancy(ServerPlayer player) {
        String team = Espetro.getPlayerTeam(player);
        if (team == null) {
            return fail(player, "你尚未加入阵营");
        }
        TeamGovernance g = getTeam(team);
        if (g.state != State.VACANCY_VOLUNTEER) {
            return fail(player, "当前不在指挥官空缺志愿阶段");
        }
        if (!SquadManager.getInstance().isSquadLeader(player.getUUID())) {
            return fail(player, "只有小队长可以志愿补位");
        }
        g.volunteers.add(player.getUUID());
        player.displayClientMessage(
            net.minecraft.network.chat.Component.literal("§a已登记志愿补位"), true);
        NetworkManager.broadcastGovernanceState(this);
        return true;
    }

    public boolean castVacancyVote(ServerPlayer voter, UUID candidate) {
        String team = Espetro.getPlayerTeam(voter);
        if (team == null) {
            return fail(voter, "你尚未加入阵营");
        }
        TeamGovernance g = getTeam(team);
        if (g.state != State.VACANCY_VOTE) {
            return fail(voter, "当前没有进行中的空缺公投");
        }
        if (candidate == null || !g.volunteers.contains(candidate)) {
            return fail(voter, "无效的候选人");
        }
        g.votes.put(voter.getUUID(), candidate);
        voter.displayClientMessage(
            net.minecraft.network.chat.Component.literal("§a已投票给 " + playerName(candidate)), true);
        NetworkManager.broadcastGovernanceState(this);
        return true;
    }

    /**
     * Unified leave/disconnect handler for any battle player.
     * Handles commander vacancy, challenger cancel, and volunteer/vote cleanup.
     */
    public void onPlayerLeft(@Nullable String team, UUID uuid) {
        if (uuid == null) {
            return;
        }
        if (team != null) {
            handlePlayerLeftTeam(team, uuid);
            return;
        }
        // Team unknown: scrub both sides.
        for (String t : List.of("ATTACK", "DEFEND")) {
            handlePlayerLeftTeam(t, uuid);
        }
    }

    /** @deprecated use {@link #onPlayerLeft(String, UUID)} */
    @Deprecated
    public void onCommanderDisconnected(String team, UUID commanderUuid) {
        onPlayerLeft(team, commanderUuid);
    }

    private void handlePlayerLeftTeam(String team, UUID uuid) {
        TeamGovernance g = getTeam(team);
        boolean isCommander = uuid.equals(g.commander)
            || VoteManager.getInstance().isCommanderOf(uuid, team);

        // Challenger left during impeachment → cancel, keep commander.
        if (g.state == State.IMPEACHMENT_VOTE && uuid.equals(g.challenger) && !isCommander) {
            cancelImpeachment(team, g, "§c弹劾发起者离线，弹劾失败，指挥官留任");
            return;
        }

        if (isCommander) {
            // 记录断线指挥官，2 分钟内重连可恢复身份
            recentCommanderDisconnect.put(uuid, System.currentTimeMillis());
            recentCommanderTeam.put(uuid, team);
            // Incumbent left (including mid-impeachment) → vacancy.
            if (g.state == State.IMPEACHMENT_VOTE) {
                g.challenger = null;
                g.votes.clear();
            }
            clearCommander(team, "disconnect");
            startVacancy(team);
            return;
        }

        // Non-commander: scrub volunteer / votes and maybe collapse vacancy vote.
        boolean changed = g.volunteers.remove(uuid);
        int beforeVotes = g.votes.size();
        g.votes.entrySet().removeIf(e -> e.getKey().equals(uuid) || e.getValue().equals(uuid));
        changed = changed || g.votes.size() != beforeVotes;

        if (g.state == State.VACANCY_VOTE) {
            collapseVacancyVoteIfNeeded(team, g);
            return;
        }
        if (changed) {
            NetworkManager.broadcastGovernanceState(this);
        }
    }

    public void onSquadLeaderLost(UUID uuid) {
        for (Map.Entry<String, TeamGovernance> e : byTeam.entrySet()) {
            String team = e.getKey();
            TeamGovernance g = e.getValue();
            if (g.state == State.IMPEACHMENT_VOTE && uuid.equals(g.challenger)
                && !uuid.equals(g.commander)) {
                cancelImpeachment(team, g, "§c弹劾发起者失去队长资格，弹劾失败");
                continue;
            }
            boolean removed = g.volunteers.remove(uuid);
            g.votes.entrySet().removeIf(v -> v.getKey().equals(uuid) || v.getValue().equals(uuid));
            if (g.state == State.VACANCY_VOTE) {
                collapseVacancyVoteIfNeeded(team, g);
            } else if (removed) {
                NetworkManager.broadcastGovernanceState(this);
            }
        }
    }

    /**
     * 尝试恢复断线重连的指挥官。仅在 2 分钟内重连且仍在原队伍时生效。
     * @param player 重连的玩家
     * @return true 如果成功恢复，false 否则
     */
    public boolean tryRestoreCommanderOnRejoin(ServerPlayer player) {
        UUID uuid = player.getUUID();
        Long disconnectTime = recentCommanderDisconnect.get(uuid);
        String recordedTeam = recentCommanderTeam.get(uuid);
        if (disconnectTime == null || recordedTeam == null) {
            return false;
        }
        // 超时
        if (System.currentTimeMillis() - disconnectTime > RECONNECT_GRACE_MILLIS) {
            recentCommanderDisconnect.remove(uuid);
            recentCommanderTeam.remove(uuid);
            return false;
        }
        // 确认玩家仍在原队伍
        VoteManager vm = VoteManager.getInstance();
        String currentTeam = null;
        if (vm.getAttackPlayers().contains(uuid)) {
            currentTeam = "ATTACK";
        } else if (vm.getDefendPlayers().contains(uuid)) {
            currentTeam = "DEFEND";
        }
        if (!recordedTeam.equals(currentTeam)) {
            recentCommanderDisconnect.remove(uuid);
            recentCommanderTeam.remove(uuid);
            return false;
        }
        // 恢复指挥官身份
        TeamGovernance g = getTeam(recordedTeam);
        // 取消正在进行的补位流程
        if (g.state == State.VACANCY_VOLUNTEER || g.state == State.VACANCY_VOTE) {
            g.state = State.IDLE;
            g.volunteers.clear();
            g.votes.clear();
            g.challenger = null;
            g.tickCounter = 0;
            g.endGameTime = 0L;
        }
        assignCommander(recordedTeam, uuid, "reconnect");
        // 清理记录
        recentCommanderDisconnect.remove(uuid);
        recentCommanderTeam.remove(uuid);
        Espetro.broadcastToTeam(recordedTeam, "§a指挥官 " + player.getName().getString() + " 已重新上线，恢复指挥权");
        return true;
    }

    private void cancelImpeachment(String team, TeamGovernance g, String message) {
        g.state = State.IDLE;
        g.challenger = null;
        g.votes.clear();
        g.tickCounter = 0;
        g.endGameTime = 0L;
        Espetro.broadcastToTeam(team, message);
        NetworkManager.broadcastGovernanceState(this);
    }

    private void collapseVacancyVoteIfNeeded(String team, TeamGovernance g) {
        if (g.state != State.VACANCY_VOTE) {
            return;
        }
        // Drop offline candidates; keep online volunteers even if leadership transferred mid-vote.
        List<UUID> remaining = new ArrayList<>(g.volunteers);
        remaining.removeIf(u -> !isOnline(u));
        g.volunteers.clear();
        g.volunteers.addAll(remaining);

        if (remaining.isEmpty()) {
            UUID fallback = findSuccessor(team);
            if (fallback != null) {
                assignCommander(team, fallback, "vacancy_vote_all_left");
            } else {
                Espetro.broadcastToTeam(team, "§c空缺公投候选人全部离线，暂无指挥官");
            }
            g.state = State.IDLE;
            g.votes.clear();
            g.volunteers.clear();
            NetworkManager.broadcastGovernanceState(this);
            return;
        }
        if (remaining.size() == 1) {
            assignCommander(team, remaining.get(0), "vacancy_vote_last_remaining");
            g.state = State.IDLE;
            g.votes.clear();
            g.volunteers.clear();
            NetworkManager.broadcastGovernanceState(this);
            return;
        }
        NetworkManager.broadcastGovernanceState(this);
    }

    private void startVacancy(String team) {
        TeamGovernance g = getTeam(team);
        g.state = State.VACANCY_VOLUNTEER;
        g.volunteers.clear();
        g.votes.clear();
        g.challenger = null;
        g.tickCounter = 0;
        g.timeoutSeconds = GameConfig.getCommanderVacancySeconds();
        MinecraftServer server = Espetro.getServer();
        g.endGameTime = server != null
            ? server.overworld().getGameTime() + g.timeoutSeconds * 20L
            : 0L;
        Espetro.broadcastToTeam(team, "§e指挥官空缺！小队长可志愿补位（" + g.timeoutSeconds + "秒）");
        NetworkManager.broadcastGovernanceState(this);
    }

    public void onServerTick(MinecraftServer server) {
        long now = server.overworld().getGameTime();
        for (Map.Entry<String, TeamGovernance> e : byTeam.entrySet()) {
            String team = e.getKey();
            TeamGovernance g = e.getValue();
            if (g.state == State.IDLE) continue;
            g.tickCounter++;
            boolean timedOut = g.endGameTime > 0
                ? now >= g.endGameTime
                : g.tickCounter >= g.timeoutSeconds * 20L;
            if (timedOut) {
                switch (g.state) {
                    case IMPEACHMENT_VOTE -> finishImpeachment(team, g);
                    case VACANCY_VOLUNTEER -> finishVacancyVolunteer(team, g);
                    case VACANCY_VOTE -> finishVacancyVote(team, g);
                    default -> {
                    }
                }
            }
        }
    }

    private void finishImpeachment(String team, TeamGovernance g) {
        UUID winner = resolveImpeachmentWinner(g.commander, g.challenger, g.votes);
        if (winner != null && !winner.equals(g.commander)) {
            assignCommander(team, winner, "impeachment");
            Espetro.broadcastToTeam(team, "§a弹劾成功，新指挥官已就任");
        } else {
            Espetro.broadcastToTeam(team, "§e弹劾失败，指挥官留任");
        }
        g.state = State.IDLE;
        g.challenger = null;
        g.votes.clear();
        g.endGameTime = 0L;
        NetworkManager.broadcastGovernanceState(this);
    }

    /**
     * Incumbent stays on tie or no votes; challenger wins only with strictly more votes.
     * Visible for unit tests.
     */
    static UUID resolveImpeachmentWinner(@Nullable UUID commander, @Nullable UUID challenger,
                                         Map<UUID, UUID> votes) {
        Map<UUID, Integer> tally = new HashMap<>();
        if (commander != null) tally.put(commander, 0);
        if (challenger != null) tally.put(challenger, 0);
        if (votes != null) {
            for (UUID c : votes.values()) {
                tally.computeIfPresent(c, (k, v) -> v + 1);
            }
        }
        int cmdVotes = commander != null ? tally.getOrDefault(commander, 0) : 0;
        int chVotes = challenger != null ? tally.getOrDefault(challenger, 0) : 0;
        if (chVotes > cmdVotes) {
            return challenger;
        }
        return commander;
    }

    /**
     * Highest vote wins; ties broken by earlier leaderSince then UUID order.
     * Visible for unit tests.
     */
    static UUID resolveVacancyVoteWinner(Set<UUID> volunteers, Map<UUID, UUID> votes,
                                         java.util.function.ToLongFunction<UUID> leaderSince) {
        Map<UUID, Integer> tally = new HashMap<>();
        if (volunteers != null) {
            for (UUID v : volunteers) {
                tally.put(v, 0);
            }
        }
        if (votes != null) {
            for (UUID c : votes.values()) {
                tally.computeIfPresent(c, (k, v) -> v + 1);
            }
        }
        int best = -1;
        List<UUID> tied = new ArrayList<>();
        for (Map.Entry<UUID, Integer> e : tally.entrySet()) {
            if (e.getValue() > best) {
                best = e.getValue();
                tied.clear();
                tied.add(e.getKey());
            } else if (e.getValue() == best) {
                tied.add(e.getKey());
            }
        }
        if (tied.isEmpty()) {
            return null;
        }
        if (tied.size() == 1) {
            return tied.get(0);
        }
        return tied.stream()
            .min((a, b) -> {
                long la = leaderSince != null ? leaderSince.applyAsLong(a) : 0L;
                long lb = leaderSince != null ? leaderSince.applyAsLong(b) : 0L;
                int cmp = Long.compare(la, lb);
                if (cmp != 0) return cmp;
                return a.compareTo(b);
            })
            .orElse(null);
    }

    private void finishVacancyVolunteer(String team, TeamGovernance g) {
        List<UUID> vols = new ArrayList<>(g.volunteers);
        vols.removeIf(u -> !SquadManager.getInstance().isSquadLeader(u) || !isOnline(u));
        if (vols.isEmpty()) {
            UUID successor = findSuccessor(team);
            if (successor != null) {
                assignCommander(team, successor, "vacancy_auto_fallback");
                Espetro.broadcastToTeam(team, "§e无人志愿，系统自动任命继任指挥官");
            } else {
                Espetro.broadcastToTeam(team, "§c无人可继任指挥官，职位暂时空缺");
            }
            g.state = State.IDLE;
            g.volunteers.clear();
            g.endGameTime = 0L;
            NetworkManager.broadcastGovernanceState(this);
            return;
        }
        if (vols.size() == 1) {
            assignCommander(team, vols.get(0), "vacancy_single_volunteer");
            g.state = State.IDLE;
            g.volunteers.clear();
            g.endGameTime = 0L;
            NetworkManager.broadcastGovernanceState(this);
            return;
        }
        g.volunteers.clear();
        g.volunteers.addAll(vols);
        g.state = State.VACANCY_VOTE;
        g.votes.clear();
        g.tickCounter = 0;
        g.timeoutSeconds = GameConfig.getImpeachmentVoteSeconds(); // 60s per plan
        MinecraftServer server = Espetro.getServer();
        g.endGameTime = server != null
            ? server.overworld().getGameTime() + g.timeoutSeconds * 20L
            : 0L;
        Espetro.broadcastToTeam(team, "§e多名小队长志愿补位，开始全员公投（" + g.timeoutSeconds + "秒）");
        NetworkManager.broadcastGovernanceState(this);
    }

    private void finishVacancyVote(String team, TeamGovernance g) {
        UUID winner = resolveVacancyVoteWinner(g.volunteers, g.votes,
            u -> SquadManager.getInstance().getLeaderSinceTick(u));
        if (winner == null) {
            winner = findSuccessor(team);
        }
        if (winner != null) {
            assignCommander(team, winner, "vacancy_vote");
        } else {
            Espetro.broadcastToTeam(team, "§c空缺公投未能产生指挥官");
        }
        g.state = State.IDLE;
        g.volunteers.clear();
        g.votes.clear();
        g.endGameTime = 0L;
        NetworkManager.broadcastGovernanceState(this);
    }

    public void assignCommander(String team, UUID uuid, String reason) {
        VoteManager vm = VoteManager.getInstance();
        if ("ATTACK".equals(team)) {
            vm.setAttackCommander(uuid);
        } else {
            vm.setDefendCommander(uuid);
        }
        getTeam(team).commander = uuid;
        MinecraftServer server = Espetro.getServer();
        String name = uuid.toString();
        if (server != null) {
            ServerPlayer p = server.getPlayerList().getPlayer(uuid);
            if (p != null) {
                name = p.getName().getString();
                NetworkManager.sendCommanderSkillSync(p);
            }
        }
        NetworkManager.syncSquadsToTeam(team);
        NetworkManager.broadcastGovernanceState(this);
        Espetro.LOGGER.info("任命指挥官 {} 为 {} ({})", name, team, reason);
        Espetro.broadcastToTeam(team, "§a新指挥官已就任：" + name);
    }

    private void clearCommander(String team, String reason) {
        VoteManager vm = VoteManager.getInstance();
        if ("ATTACK".equals(team)) {
            vm.setAttackCommander(null);
        } else {
            vm.setDefendCommander(null);
        }
        getTeam(team).commander = null;
        Espetro.LOGGER.info("清除 {} 指挥官 ({})", team, reason);
    }

    /**
     * Prefer earliest squad leader; if none, any online teammate (stable UUID order).
     */
    @Nullable
    private UUID findSuccessor(String team) {
        UUID leader = findEarliestSquadLeader(team);
        if (leader != null) {
            return leader;
        }
        return findAnyOnlineTeammate(team);
    }

    @Nullable
    private UUID findEarliestSquadLeader(String team) {
        long best = Long.MAX_VALUE;
        UUID bestId = null;
        for (SquadManager.SquadSnapshot snap : SquadManager.getInstance().getSquadSnapshots(team)) {
            for (SquadManager.MemberSnapshot m : snap.members) {
                if (m.leader && isOnline(m.uuid)) {
                    long since = SquadManager.getInstance().getLeaderSinceTick(m.uuid);
                    if (since < best || (since == best && (bestId == null || m.uuid.compareTo(bestId) < 0))) {
                        best = since;
                        bestId = m.uuid;
                    }
                }
            }
        }
        return bestId;
    }

    @Nullable
    private UUID findAnyOnlineTeammate(String team) {
        MinecraftServer server = Espetro.getServer();
        if (server == null) {
            return null;
        }
        UUID best = null;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (!team.equals(Espetro.getPlayerTeam(player))) {
                continue;
            }
            if (best == null || player.getUUID().compareTo(best) < 0) {
                best = player.getUUID();
            }
        }
        return best;
    }

    private boolean isOnline(UUID uuid) {
        MinecraftServer server = Espetro.getServer();
        return server != null && server.getPlayerList().getPlayer(uuid) != null;
    }

    private String playerName(UUID uuid) {
        MinecraftServer server = Espetro.getServer();
        if (server != null) {
            ServerPlayer p = server.getPlayerList().getPlayer(uuid);
            if (p != null) {
                return p.getName().getString();
            }
        }
        return uuid != null ? uuid.toString().substring(0, 8) : "?";
    }

    private boolean fail(ServerPlayer player, String msg) {
        player.sendSystemMessage(net.minecraft.network.chat.Component.literal("§c" + msg));
        return false;
    }
}
