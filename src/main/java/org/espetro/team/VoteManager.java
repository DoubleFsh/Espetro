package org.espetro.team;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.espetro.Espetro;
import org.espetro.config.GameConfig;

import java.util.*;

/**
 * 指挥官投票管理器
 * 支持分阶段投票：先守方(20s)，后攻方(20s)
 */
public class VoteManager {

    private static VoteManager INSTANCE;

    // 投票阶段是否在进行
    private boolean votingActive = false;
    // 当前正在投票的队伍: "DEFEND" 或 "ATTACK"
    private String currentVotingTeam = null;

    // 投票计时器
    private int voteTickCounter = 0;
    private static final int TICKS_PER_SECOND = 20;

    // 攻方投票: 玩家UUID -> 投票目标玩家UUID
    private final Map<UUID, UUID> attackVotes = new HashMap<>();
    // 守方投票: 玩家UUID -> 投票目标玩家UUID
    private final Map<UUID, UUID> defendVotes = new HashMap<>();

    // 攻方玩家列表
    private final Set<UUID> attackPlayers = new HashSet<>();
    // 守方玩家列表
    private final Set<UUID> defendPlayers = new HashSet<>();

    // 投票结果
    private UUID attackCommander = null;
    private UUID defendCommander = null;

    private VoteManager() {
        INSTANCE = this;
    }

    public static VoteManager getInstance() {
        if (INSTANCE == null) {
            INSTANCE = new VoteManager();
        }
        return INSTANCE;
    }

    public static void init() {
        INSTANCE = new VoteManager();
    }

    /**
     * 初始化投票（收集双方玩家）
     */
    public void initPlayers() {
        MinecraftServer server = Espetro.getServer();
        if (server == null) return;

        attackPlayers.clear();
        defendPlayers.clear();

        String factionId;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            factionId = ClassCountManager.getInstance().getPlayerFaction(player.getUUID());
            if (factionId != null) {
                String team = GameStateManager.getTeamFromFactionStatic(factionId);
                if ("ATTACK".equals(team)) {
                    attackPlayers.add(player.getUUID());
                } else {
                    defendPlayers.add(player.getUUID());
                }
            }
        }

        Espetro.LOGGER.info("指挥官投票初始化！攻方{}人，守方{}人", attackPlayers.size(), defendPlayers.size());
    }

    /**
     * 开始守方指挥官投票
     */
    public void startDefendVote() {
        votingActive = true;
        currentVotingTeam = "DEFEND";
        voteTickCounter = 0;
        defendVotes.clear();

        int timeout = GameConfig.getDefendCommanderVoteSeconds();
        Espetro.LOGGER.info("守方指挥官投票开始！限时{}秒", timeout);
        Espetro.broadcastToTeam("DEFEND", "§6★ 请投票选择指挥官！剩余时间: " + timeout + "秒 ★");
        Espetro.broadcastToTeam("ATTACK", "§7守方正在选择指挥官，请稍候...");

        // 发送投票界面给守方玩家
        org.espetro.network.NetworkManager.broadcastCommanderVoteScreenForTeam("DEFEND", timeout);
    }

    /**
     * 开始攻方指挥官投票
     */
    public void startAttackVote() {
        votingActive = true;
        currentVotingTeam = "ATTACK";
        voteTickCounter = 0;
        attackVotes.clear();

        int timeout = GameConfig.getAttackCommanderVoteSeconds();
        Espetro.LOGGER.info("攻方指挥官投票开始！限时{}秒", timeout);
        Espetro.broadcastToTeam("ATTACK", "§6★ 请投票选择指挥官！剩余时间: " + timeout + "秒 ★");
        Espetro.broadcastToTeam("DEFEND", "§7攻方正在选择指挥官，请稍候...");

        // 发送投票界面给攻方玩家
        org.espetro.network.NetworkManager.broadcastCommanderVoteScreenForTeam("ATTACK", timeout);
    }

    /**
     * 玩家投票
     */
    public boolean castVote(ServerPlayer voter, UUID targetUUID) {
        if (!votingActive) return false;

        // 不能投自己
        if (voter.getUUID().equals(targetUUID)) return false;

        // 检查当前阶段是否允许该玩家投票
        String voterTeam = getPlayerTeam(voter.getUUID());
        if (voterTeam == null || !voterTeam.equals(currentVotingTeam)) {
            Espetro.sendToPlayer(voter, "§c当前不是你的投票时间！");
            return false;
        }

        // 检查目标是否和投票者在同一队伍
        String targetTeam = getPlayerTeam(targetUUID);
        if (targetTeam == null || !targetTeam.equals(currentVotingTeam)) {
            return false;
        }

        if ("ATTACK".equals(currentVotingTeam)) {
            attackVotes.put(voter.getUUID(), targetUUID);
            Espetro.LOGGER.info("玩家 {} 投票给攻方玩家 {}", voter.getName().getString(), targetUUID);
        } else {
            defendVotes.put(voter.getUUID(), targetUUID);
            Espetro.LOGGER.info("玩家 {} 投票给守方玩家 {}", voter.getName().getString(), targetUUID);
        }

        // 广播投票数据更新
        broadcastVoteUpdate();
        return true;
    }

    private String getPlayerTeam(UUID uuid) {
        if (attackPlayers.contains(uuid)) return "ATTACK";
        if (defendPlayers.contains(uuid)) return "DEFEND";
        return null;
    }

    /**
     * 获取玩家的投票目标
     */
    public UUID getVoteTarget(UUID voterUUID) {
        if (attackVotes.containsKey(voterUUID)) {
            return attackVotes.get(voterUUID);
        }
        return defendVotes.get(voterUUID);
    }

    /**
     * 计算某玩家的得票数
     */
    public int getVoteCount(UUID playerUUID) {
        int count = 0;
        Map<UUID, UUID> votes = "ATTACK".equals(currentVotingTeam) ? attackVotes : defendVotes;
        for (UUID target : votes.values()) {
            if (target.equals(playerUUID)) count++;
        }
        return count;
    }

    /**
     * 获取当前投票方的得票最高者
     */
    private UUID getWinningCandidate(Set<UUID> players, Map<UUID, UUID> votes) {
        if (players.isEmpty()) return null;

        Map<UUID, Integer> voteCounts = new HashMap<>();
        for (UUID player : players) {
            voteCounts.put(player, 0);
        }

        for (UUID target : votes.values()) {
            voteCounts.merge(target, 1, Integer::sum);
        }

        // 找出最高票数
        int maxVotes = 0;
        for (int count : voteCounts.values()) {
            if (count > maxVotes) maxVotes = count;
        }

        // 收集所有最高票数的玩家
        List<UUID> candidates = new ArrayList<>();
        for (Map.Entry<UUID, Integer> entry : voteCounts.entrySet()) {
            if (entry.getValue() == maxVotes) {
                candidates.add(entry.getKey());
            }
        }

        // 如果有多个最高票数，随机选一个
        if (candidates.size() > 1) {
            return candidates.get(new Random().nextInt(candidates.size()));
        }

        return candidates.isEmpty() ? null : candidates.get(0);
    }

    /**
     * 结束当前队伍的投票
     * @return 当前完成的队伍 "DEFEND" 或 "ATTACK"
     */
    public String finishCurrentVote() {
        if (!votingActive) return null;

        String finishedTeam = currentVotingTeam;
        votingActive = false;

        MinecraftServer server = Espetro.getServer();
        if (server == null) return finishedTeam;

        if ("DEFEND".equals(finishedTeam)) {
            defendCommander = getWinningCandidate(defendPlayers, defendVotes);
            String name = getPlayerName(server, defendCommander);
            Espetro.broadcastToTeam("DEFEND", "§6★ 你所在的队伍指挥官为§9" + name + "§6！★");
            if (defendCommander != null) {
                Espetro.sendToPlayer(server.getPlayerList().getPlayer(defendCommander), "§a你已被选为§9守方§a指挥官！");
            }
            Espetro.LOGGER.info("守方指挥官投票结束！指挥官: {}", name);
        } else {
            attackCommander = getWinningCandidate(attackPlayers, attackVotes);
            String name = getPlayerName(server, attackCommander);
            Espetro.broadcastToTeam("ATTACK", "§6★ 你所在的队伍指挥官为§c" + name + "§6！★");
            if (attackCommander != null) {
                Espetro.sendToPlayer(server.getPlayerList().getPlayer(attackCommander), "§a你已被选为§c攻方§a指挥官！");
            }
            Espetro.LOGGER.info("攻方指挥官投票结束！指挥官: {}", name);
        }

        return finishedTeam;
    }

    private String getPlayerName(MinecraftServer server, UUID uuid) {
        if (uuid == null) return "无";
        ServerPlayer player = server.getPlayerList().getPlayer(uuid);
        return player != null ? player.getName().getString() : "离线玩家";
    }

    /**
     * 广播投票数据更新
     */
    private void broadcastVoteUpdate() {
        if (!votingActive || currentVotingTeam == null) return;
        
        MinecraftServer server = Espetro.getServer();
        if (server == null) return;

        Set<UUID> players = "ATTACK".equals(currentVotingTeam) ? attackPlayers : defendPlayers;
        Map<UUID, UUID> votes = "ATTACK".equals(currentVotingTeam) ? attackVotes : defendVotes;

        java.util.Map<String, Integer> voteCounts = new java.util.HashMap<>();
        for (UUID uuid : players) {
            ServerPlayer p = server.getPlayerList().getPlayer(uuid);
            if (p != null) {
                int count = 0;
                for (UUID target : votes.values()) {
                    if (target.equals(uuid)) count++;
                }
                voteCounts.put(p.getName().getString(), count);
            }
        }

        org.espetro.network.VoteDataPacket packet = new org.espetro.network.VoteDataPacket(voteCounts, getRemainingSeconds());
        for (UUID uuid : players) {
            ServerPlayer p = server.getPlayerList().getPlayer(uuid);
            if (p != null) {
                org.espetro.network.NetworkManager.NET.send(
                    net.minecraftforge.network.PacketDistributor.PLAYER.with(() -> p), packet);
            }
        }
    }

    /**
     * 服务器Tick - 处理投票超时
     */
    public void onServerTick() {
        if (!votingActive) return;

        voteTickCounter++;
        int timeout = getCurrentTimeoutSeconds();
        int secondsRemaining = timeout - (voteTickCounter / TICKS_PER_SECOND);

        // 每秒更新一次投票状态给当前投票方
        if (voteTickCounter % TICKS_PER_SECOND == 0) {
            // 每秒广播投票数据（同步倒计时和票数）
            broadcastVoteUpdate();

            if (secondsRemaining > 0 && secondsRemaining <= 5) {
                Espetro.broadcastToTeam(currentVotingTeam, "§e指挥官投票剩余时间: §c" + secondsRemaining + "秒");
            }

            // 向等待方也显示倒计时
            String waitingTeam = "DEFEND".equals(currentVotingTeam) ? "ATTACK" : "DEFEND";
            String waitingName = "DEFEND".equals(currentVotingTeam) ? "守方" : "攻方";
            Espetro.broadcastToTeam(waitingTeam, "§7" + waitingName + "正在选择指挥官，请稍候... [§e" + secondsRemaining + "秒§7]");
        }
    }

    private int getCurrentTimeoutSeconds() {
        if ("DEFEND".equals(currentVotingTeam)) {
            return GameConfig.getDefendCommanderVoteSeconds();
        } else if ("ATTACK".equals(currentVotingTeam)) {
            return GameConfig.getAttackCommanderVoteSeconds();
        }
        return 20;
    }

    /**
     * 检查当前投票是否超时
     */
    public boolean isCurrentVoteTimedOut() {
        if (!votingActive) return false;
        int timeout = getCurrentTimeoutSeconds();
        return voteTickCounter >= timeout * TICKS_PER_SECOND;
    }

    /**
     * 检查投票是否进行中
     */
    public boolean isVotingActive() {
        return votingActive;
    }

    /**
     * 获取当前投票的队伍
     */
    public String getCurrentVotingTeam() {
        return currentVotingTeam;
    }

    /**
     * 获取投票剩余秒数
     */
    public int getRemainingSeconds() {
        if (!votingActive) return 0;
        int timeout = getCurrentTimeoutSeconds();
        return Math.max(0, timeout - (voteTickCounter / TICKS_PER_SECOND));
    }

    /**
     * 获取攻方玩家列表
     */
    public Set<UUID> getAttackPlayers() {
        return new HashSet<>(attackPlayers);
    }

    /**
     * 获取守方玩家列表
     */
    public Set<UUID> getDefendPlayers() {
        return new HashSet<>(defendPlayers);
    }

    /**
     * 获取攻方指挥官
     */
    public UUID getAttackCommander() {
        return attackCommander;
    }

    /**
     * 获取守方指挥官
     */
    public UUID getDefendCommander() {
        return defendCommander;
    }

    /**
     * 检查玩家是否是指挥官
     */
    public boolean isCommander(UUID uuid) {
        return (attackCommander != null && attackCommander.equals(uuid)) ||
               (defendCommander != null && defendCommander.equals(uuid));
    }

    /**
     * 检查玩家是否是指定队伍的指挥官
     */
    public boolean isCommanderOf(UUID uuid, String team) {
        if ("ATTACK".equals(team)) {
            return attackCommander != null && attackCommander.equals(uuid);
        } else {
            return defendCommander != null && defendCommander.equals(uuid);
        }
    }

    /**
     * 添加攻方玩家（战局中加入）
     */
    public void addAttackPlayer(UUID uuid) {
        attackPlayers.add(uuid);
    }

    /**
     * 添加守方玩家（战局中加入）
     */
    public void addDefendPlayer(UUID uuid) {
        defendPlayers.add(uuid);
    }

    /**
     * 重置投票
     */
    public void reset() {
        votingActive = false;
        currentVotingTeam = null;
        voteTickCounter = 0;
        attackVotes.clear();
        defendVotes.clear();
        attackPlayers.clear();
        defendPlayers.clear();
        attackCommander = null;
        defendCommander = null;
    }
}
