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
        }
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
        if (team == null) return false;
        TeamGovernance g = getTeam(team);
        if (g.state != State.IMPEACHMENT_VOTE) return false;
        if (!candidate.equals(g.commander) && !candidate.equals(g.challenger)) return false;
        g.votes.put(voter.getUUID(), candidate);
        NetworkManager.broadcastGovernanceState(this);
        return true;
    }

    public boolean volunteerForVacancy(ServerPlayer player) {
        String team = Espetro.getPlayerTeam(player);
        if (team == null) return false;
        TeamGovernance g = getTeam(team);
        if (g.state != State.VACANCY_VOLUNTEER) return false;
        if (!SquadManager.getInstance().isSquadLeader(player.getUUID())) {
            return fail(player, "只有小队长可以志愿补位");
        }
        g.volunteers.add(player.getUUID());
        NetworkManager.broadcastGovernanceState(this);
        return true;
    }

    public boolean castVacancyVote(ServerPlayer voter, UUID candidate) {
        String team = Espetro.getPlayerTeam(voter);
        if (team == null) return false;
        TeamGovernance g = getTeam(team);
        if (g.state != State.VACANCY_VOTE) return false;
        if (!g.volunteers.contains(candidate)) return false;
        g.votes.put(voter.getUUID(), candidate);
        NetworkManager.broadcastGovernanceState(this);
        return true;
    }

    public void onCommanderDisconnected(String team, UUID commanderUuid) {
        TeamGovernance g = getTeam(team);
        if (commanderUuid.equals(g.commander) || VoteManager.getInstance().isCommander(commanderUuid)) {
            clearCommander(team, "disconnect");
            startVacancy(team);
        }
        if (g.state == State.IMPEACHMENT_VOTE && commanderUuid.equals(g.commander)) {
            // Incumbent left during impeachment → vacancy
            g.state = State.IDLE;
            startVacancy(team);
        }
        if (g.state == State.IMPEACHMENT_VOTE && commanderUuid.equals(g.challenger)) {
            // Challenger left → impeachment fails
            g.state = State.IDLE;
            g.challenger = null;
            g.votes.clear();
            Espetro.broadcastToTeam(team, "§c弹劾发起者离线，弹劾失败，指挥官留任");
            NetworkManager.broadcastGovernanceState(this);
        }
        // Remove from volunteers
        g.volunteers.remove(commanderUuid);
        g.votes.entrySet().removeIf(e -> e.getKey().equals(commanderUuid) || e.getValue().equals(commanderUuid));
    }

    public void onSquadLeaderLost(UUID uuid) {
        for (Map.Entry<String, TeamGovernance> e : byTeam.entrySet()) {
            TeamGovernance g = e.getValue();
            if (g.state == State.IMPEACHMENT_VOTE && uuid.equals(g.challenger)) {
                g.state = State.IDLE;
                g.challenger = null;
                g.votes.clear();
                Espetro.broadcastToTeam(e.getKey(), "§c弹劾发起者失去队长资格，弹劾失败");
                NetworkManager.broadcastGovernanceState(this);
            }
            g.volunteers.remove(uuid);
        }
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
        for (Map.Entry<String, TeamGovernance> e : byTeam.entrySet()) {
            String team = e.getKey();
            TeamGovernance g = e.getValue();
            if (g.state == State.IDLE) continue;
            g.tickCounter++;
            if (g.tickCounter >= g.timeoutSeconds * 20) {
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
        Map<UUID, Integer> tally = new HashMap<>();
        if (g.commander != null) tally.put(g.commander, 0);
        if (g.challenger != null) tally.put(g.challenger, 0);
        for (UUID c : g.votes.values()) {
            tally.computeIfPresent(c, (k, v) -> v + 1);
        }
        int cmdVotes = g.commander != null ? tally.getOrDefault(g.commander, 0) : 0;
        int chVotes = g.challenger != null ? tally.getOrDefault(g.challenger, 0) : 0;
        // Incumbent stays on tie or no votes
        UUID winner = g.commander;
        if (chVotes > cmdVotes) {
            winner = g.challenger;
        }
        if (winner != null && !winner.equals(g.commander)) {
            assignCommander(team, winner, "impeachment");
            Espetro.broadcastToTeam(team, "§a弹劾成功，新指挥官已就任");
        } else {
            Espetro.broadcastToTeam(team, "§e弹劾失败，指挥官留任");
        }
        g.state = State.IDLE;
        g.challenger = null;
        g.votes.clear();
        NetworkManager.broadcastGovernanceState(this);
    }

    private void finishVacancyVolunteer(String team, TeamGovernance g) {
        List<UUID> vols = new ArrayList<>(g.volunteers);
        vols.removeIf(u -> !SquadManager.getInstance().isSquadLeader(u));
        if (vols.isEmpty()) {
            UUID earliest = findEarliestSquadLeader(team);
            if (earliest != null) {
                assignCommander(team, earliest, "vacancy_auto_earliest");
            }
            g.state = State.IDLE;
            NetworkManager.broadcastGovernanceState(this);
            return;
        }
        if (vols.size() == 1) {
            assignCommander(team, vols.get(0), "vacancy_single_volunteer");
            g.state = State.IDLE;
            g.volunteers.clear();
            NetworkManager.broadcastGovernanceState(this);
            return;
        }
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
        Map<UUID, Integer> tally = new HashMap<>();
        for (UUID v : g.volunteers) {
            tally.put(v, 0);
        }
        for (UUID c : g.votes.values()) {
            tally.computeIfPresent(c, (k, v) -> v + 1);
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
        UUID winner;
        if (tied.isEmpty()) {
            winner = findEarliestSquadLeader(team);
        } else if (tied.size() == 1) {
            winner = tied.get(0);
        } else {
            // earliest leaderSince, then UUID
            winner = tied.stream()
                .min((a, b) -> {
                    long la = SquadManager.getInstance().getLeaderSinceTick(a);
                    long lb = SquadManager.getInstance().getLeaderSinceTick(b);
                    int cmp = Long.compare(la, lb);
                    if (cmp != 0) return cmp;
                    return a.compareTo(b);
                })
                .orElse(null);
        }
        if (winner != null) {
            assignCommander(team, winner, "vacancy_vote");
        }
        g.state = State.IDLE;
        g.volunteers.clear();
        g.votes.clear();
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

    @Nullable
    private UUID findEarliestSquadLeader(String team) {
        long best = Long.MAX_VALUE;
        UUID bestId = null;
        for (SquadManager.SquadSnapshot snap : SquadManager.getInstance().getSquadSnapshots(team)) {
            for (SquadManager.MemberSnapshot m : snap.members) {
                if (m.leader) {
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

    private boolean fail(ServerPlayer player, String msg) {
        player.sendSystemMessage(net.minecraft.network.chat.Component.literal("§c" + msg));
        return false;
    }
}
