package org.espetro.team;

import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.espetro.Espetro;
import org.espetro.config.GameConfig;

import java.util.*;

/**
 * 编制选择管理器
 * 队伍全员投票选择编制
 * 分阶段选择：AAS 先攻后守；RAAS 由 GameStateManager 随机先手
 */
public class ClassSelectManager {

    private static ClassSelectManager INSTANCE;

    // 编制选择是否在进行
    private boolean selectingActive = false;
    // 当前正在选择的队伍: "DEFEND" 或 "ATTACK"
    private String currentSelectingTeam = null;

    // 选择计时器
    private int selectTickCounter = 0;
    private static final int TICKS_PER_SECOND = 20;

    // 编制投票：玩家UUID -> 编制ID（每人只保留最后一票）
    private final Map<UUID, String> attackFactionVotes = new HashMap<>();
    private final Map<UUID, String> defendFactionVotes = new HashMap<>();

    // 编制结果
    private String finalAttackClass = null;
    private String finalDefendClass = null;

    // 本局随机选中的编制ID（攻守双方共用同一个池，数量由 game.json 的 faction_pool_size 决定）
    private List<String> selectedFactionPool = null;

    private ClassSelectManager() {
        INSTANCE = this;
    }

    public static ClassSelectManager getInstance() {
        if (INSTANCE == null) {
            INSTANCE = new ClassSelectManager();
        }
        return INSTANCE;
    }

    public static void init() {
        INSTANCE = new ClassSelectManager();
    }

    /**
     * 初始化编制池
     */
    public void initFactionPool() {
        generateFactionPool();
    }

    /**
     * 开始守方编制选择阶段
     */
    public void startDefendSelecting() {
        selectingActive = true;
        currentSelectingTeam = "DEFEND";
        selectTickCounter = 0;
        defendFactionVotes.clear();

        int timeout = GameConfig.getDefendFactionSelectSeconds();
        Espetro.LOGGER.info("守方编制选择开始！限时{}秒", timeout);
        // 消息已通过 ClassSelectScreen GUI 实时显示，不再发送聊天消息

        // 发送编制投票界面给守方全员
        org.espetro.network.NetworkManager.broadcastClassSelectScreenForTeam("DEFEND", timeout);
    }

    /**
     * 开始攻方编制选择阶段
     */
    public void startAttackSelecting() {
        selectingActive = true;
        currentSelectingTeam = "ATTACK";
        selectTickCounter = 0;
        attackFactionVotes.clear();

        int timeout = GameConfig.getAttackFactionSelectSeconds();
        Espetro.LOGGER.info("攻方编制选择开始！限时{}秒", timeout);
        // 消息已通过 ClassSelectScreen GUI 实时显示，不再发送聊天消息

        // 发送编制投票界面给攻方全员
        org.espetro.network.NetworkManager.broadcastClassSelectScreenForTeam("ATTACK", timeout);
    }

    /**
     * 从所有加载的编制中随机选出（数量由 game.json faction_pool_size 决定）
     */
    private void generateFactionPool() {
        FactionDataLoader loader = FactionDataProvider.getOrCreateLoader();
        List<FactionDataLoader.FactionData> allFactions = new ArrayList<>();

        var ctx = org.espetro.mapconfig.BattlefieldContext.getOrNull();
        Espetro.LOGGER.info("[编制池诊断] 开始生成编制池, BattlefieldContext={}, poolSize={}, 总编制数={}",
            ctx != null ? ctx.displayName : "null",
            GameConfig.getFactionPoolSize(),
            loader.getFactionArray().length);

        for (FactionDataLoader.FactionData faction : loader.getFactionArray()) {
            if (faction != null && faction.id != null && !faction.id.isEmpty()) {
                int classCount = loader.getClassesForFaction(faction.id).length;
                boolean compatible = loader.isCompatibleWithMap(faction.id, ctx);
                if (classCount == 0) {
                    Espetro.LOGGER.warn("[编制池诊断] {} → 排除: 职业数为0", faction.id);
                    continue;
                }
                if (!compatible) {
                    Espetro.LOGGER.warn("[编制池诊断] {} → 排除: isCompatibleWithMap 返回 false", faction.id);
                    continue;
                }
                Espetro.LOGGER.info("[编制池诊断] {} → 通过 (职业数={})", faction.id, classCount);
                allFactions.add(faction);
            }
        }

        Espetro.LOGGER.info("[编制池诊断] 兼容编制数: {} (共{}个编制)", allFactions.size(),
            loader.getFactionArray().length);

        int poolSize = GameConfig.getFactionPoolSize();
        // 随机打乱并取前 N 个
        Collections.shuffle(allFactions, new Random());
        selectedFactionPool = new ArrayList<>();
        for (int i = 0; i < Math.min(poolSize, allFactions.size()); i++) {
            selectedFactionPool.add(allFactions.get(i).id);
        }

        long distinctAffiliations = allFactions.stream()
            .map(faction -> faction.factionId)
            .filter(Objects::nonNull)
            .distinct()
            .count();
        if (distinctAffiliations < 2) {
            Espetro.LOGGER.warn("可玩编制只有 {} 个不同 faction_id，攻守双方可能无法选择互不冲突的阵营",
                distinctAffiliations);
        }

        Espetro.LOGGER.info("本局编制池（{}个）：{}", selectedFactionPool.size(), selectedFactionPool);
    }

    /**
     * 获取本局随机选中的编制池（攻守双方共用）
     */
    public List<String> getSelectedFactionPool() {
        return selectedFactionPool;
    }

    /**
     * 获取指定队伍当前可选择的编制列表。
     * 第二方选择时会排除第一方最终编制所属 faction_id 下的全部编制。
     */
    public List<String> getAvailableFactionPoolForTeam(String team) {
        List<String> source = selectedFactionPool != null && !selectedFactionPool.isEmpty()
            ? selectedFactionPool
            : getAllPlayableFactionIds();

        int targetSize = Math.max(1, GameConfig.getFactionPoolSize());
        LinkedHashSet<String> available = new LinkedHashSet<>();
        for (String factionId : source) {
            if (isFactionAvailableForTeam(team, factionId)) {
                available.add(factionId);
                if (available.size() >= targetSize) break;
            }
        }

        // 第二方选择时会排除第一方已经确定的编制；从池外补足候选数，
        // 避免界面从 3×2 的六张卡片退化为五张。
        if (available.size() < targetSize) {
            for (String factionId : getAllPlayableFactionIds()) {
                if (isFactionAvailableForTeam(team, factionId)) {
                    available.add(factionId);
                    if (available.size() >= targetSize) break;
                }
            }
        }
        return new ArrayList<>(available);
    }

    /**
     * 检查编制是否在当前队伍可选列表中。服务端选择入口使用该校验，避免绕过客户端列表。
     */
    public boolean isFactionSelectableForTeam(String team, String factionId) {
        if (team == null || factionId == null || factionId.isBlank()) {
            return false;
        }
        return getAvailableFactionPoolForTeam(team).contains(factionId);
    }

    private boolean isFactionAvailableForTeam(String team, String factionId) {
        if (factionId == null || factionId.isBlank()) {
            return false;
        }
        FactionDataLoader loader = FactionDataProvider.getOrCreateLoader();
        if (!loader.isCompatibleWithMap(factionId,
            org.espetro.mapconfig.BattlefieldContext.getOrNull())) {
            return false;
        }
        String opponentFaction = "ATTACK".equals(team) ? finalDefendClass
            : "DEFEND".equals(team) ? finalAttackClass : null;
        return opponentFaction == null || !hasSameFactionId(factionId, opponentFaction);
    }

    private boolean hasSameFactionId(String firstFormationId, String secondFormationId) {
        FactionDataLoader loader = FactionDataProvider.getOrCreateLoader();
        FactionDataLoader.FactionData first = loader.getFaction(firstFormationId);
        FactionDataLoader.FactionData second = loader.getFaction(secondFormationId);
        if (first == null || second == null || first.factionId == null || second.factionId == null) {
            return false;
        }
        // 用户选择精确字符串比较：不 trim、不忽略大小写。
        return first.factionId.equals(second.factionId);
    }

    private List<String> getAllPlayableFactionIds() {
        FactionDataLoader loader = FactionDataProvider.getOrCreateLoader();
        List<String> ids = new ArrayList<>();

        for (FactionDataLoader.FactionData faction : loader.getFactionArray()) {
            if (faction == null || faction.id == null || faction.id.isEmpty()) {
                continue;
            }
            if (loader.getClassesForFaction(faction.id).length == 0) {
                continue;
            }
            if (!loader.isCompatibleWithMap(faction.id,
                org.espetro.mapconfig.BattlefieldContext.getOrNull())) {
                continue;
            }
            ids.add(faction.id);
        }
        Collections.sort(ids);
        return ids;
    }

    /**
     * 玩家投票选择编制（可改票，每人只保留最后一票）。
     */
    public boolean selectClass(ServerPlayer voter, String classId) {
        if (!selectingActive) return false;

        String team = Espetro.getPlayerTeam(voter);

        // 检查当前阶段是否允许该队伍选择
        if (team == null || !team.equals(currentSelectingTeam)) {
            Espetro.sendToPlayer(voter, "§c当前不是你所在阵营的编制投票时间！");
            return false;
        }

        if (!isFactionSelectableForTeam(team, classId)) {
            Espetro.sendToPlayer(voter, "§c该编制当前不可选，可能已被对方选择或不在本局候选池中！");
            return false;
        }

        Map<UUID, String> votes = "ATTACK".equals(team) ? attackFactionVotes : defendFactionVotes;
        votes.put(voter.getUUID(), classId);

        Espetro.LOGGER.info("{} 玩家 {} 投票编制: {}", team, voter.getName().getString(), classId);

        org.espetro.network.NetworkManager.sendClassSelectScreenForTeam(team, getRemainingSeconds());

        return true;
    }

    public Map<String, Integer> getFactionVoteCounts(String team) {
        Map<UUID, String> votes = "ATTACK".equals(team) ? attackFactionVotes : defendFactionVotes;
        Map<String, Integer> counts = new HashMap<>();
        for (String factionId : getAvailableFactionPoolForTeam(team)) {
            counts.put(factionId, 0);
        }
        for (String target : votes.values()) {
            if (counts.containsKey(target)) {
                counts.merge(target, 1, Integer::sum);
            }
        }
        return counts;
    }

    public String getPlayerFactionVote(UUID playerId, String team) {
        Map<UUID, String> votes = "ATTACK".equals(team) ? attackFactionVotes : defendFactionVotes;
        return votes.get(playerId);
    }

    public void removePlayerVote(UUID playerId) {
        if (playerId == null) return;
        boolean changed = attackFactionVotes.remove(playerId) != null;
        changed |= defendFactionVotes.remove(playerId) != null;
        if (changed && selectingActive && currentSelectingTeam != null) {
            org.espetro.network.NetworkManager.sendClassSelectScreenForTeam(
                currentSelectingTeam, getRemainingSeconds());
        }
    }

    /**
     * 获取编制/职业显示名称
     */
    private String getClassDisplayName(String classId) {
        FactionDataLoader loader = FactionDataProvider.getOrCreateLoader();
        FactionDataLoader.FactionData faction = loader.getFaction(classId);
        if (faction != null) {
            return faction.name;
        }
        FactionDataLoader.ClassKitData kit = loader.getClassKit(classId);
        return kit != null ? kit.name : classId;
    }

    /**
     * 结束当前队伍的编制选择
     * @return 当前完成的队伍 "DEFEND" 或 "ATTACK"
     */
    public String finishCurrentSelecting() {
        if (!selectingActive) return null;

        String finishedTeam = currentSelectingTeam;
        selectingActive = false;

        MinecraftServer server = Espetro.getServer();
        if (server == null) return finishedTeam;

        // 确定最终选择：最高票当选，并列时随机；无人投票则从候选池随机。
        if ("DEFEND".equals(finishedTeam)) {
            finalDefendClass = getWinningFaction(finishedTeam);
            String name = getClassDisplayName(finalDefendClass);
            Espetro.broadcastToTeam("DEFEND", "§6===== "
                + TeamDisplayNames.displayName("DEFEND") + "编制已确定: §9" + name + "§6 =====");
            Espetro.LOGGER.info("守方编制选择结束！编制: {}", name);
        } else {
            finalAttackClass = getWinningFaction(finishedTeam);
            String name = getClassDisplayName(finalAttackClass);
            Espetro.broadcastToTeam("ATTACK", "§6===== "
                + TeamDisplayNames.displayName("ATTACK") + "编制已确定: §c" + name + "§6 =====");
            Espetro.LOGGER.info("攻方编制选择结束！编制: {}", name);
        }

        return finishedTeam;
    }

    private String getWinningFaction(String team) {
        Map<String, Integer> counts = getFactionVoteCounts(team);
        int maxVotes = counts.values().stream().mapToInt(Integer::intValue).max().orElse(0);
        if (maxVotes <= 0) {
            return getRandomFactionFromPool(team);
        }

        List<String> winners = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            if (entry.getValue() == maxVotes) {
                winners.add(entry.getKey());
            }
        }
        return winners.get(new Random().nextInt(winners.size()));
    }

    /**
     * 从本局编制池随机选取一个编制
     */
    private String getRandomFactionFromPool(String team) {
        List<String> available = getAvailableFactionPoolForTeam(team);
        if (available.isEmpty()) {
            Espetro.LOGGER.warn("{} 方无可用编制，无法随机选择", team);
            return null;
        }
        return available.get(new Random().nextInt(available.size()));
    }

    /**
     * 所有编制选择结束后的最终处理
     */
    public void finalizeSelection() {
        MinecraftServer server = Espetro.getServer();
        if (server == null) return;

        String attackClassName = getClassDisplayName(finalAttackClass);
        String defendClassName = getClassDisplayName(finalDefendClass);

        Espetro.broadcastToAll("§6========================================");
        Espetro.broadcastToAll("§6★ 攻方编制: §c" + attackClassName + " §7| §9守方编制: " + defendClassName + " §6★");
        Espetro.broadcastToAll("§6========================================");

        // 更新每个玩家的 faction 为最终选择的编制
        updatePlayerFactions(server);
    }

    /**
     * 更新所有玩家的 faction 为最终选择的编制
     */
    private void updatePlayerFactions(MinecraftServer server) {
        ClassCountManager countManager = ClassCountManager.getInstance();
        VoteManager voteManager = VoteManager.getInstance();

        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            UUID uuid = player.getUUID();
            String team = null;

            if (voteManager.getAttackPlayers().contains(uuid)) {
                team = "ATTACK";
            } else if (voteManager.getDefendPlayers().contains(uuid)) {
                team = "DEFEND";
            }

            if (team == null) continue;

            String factionId = "ATTACK".equals(team) ? finalAttackClass : finalDefendClass;
            if (factionId != null) {
                countManager.setPlayerFaction(uuid, factionId);
                Espetro.LOGGER.info("玩家 {} 的阵营更新为: {}", player.getName().getString(), factionId);
            }
        }
    }

    /**
     * 服务器Tick
     */
    public void onServerTick() {
        if (!selectingActive) return;

        selectTickCounter++;
        int timeout = getCurrentTimeoutSeconds();
        int secondsRemaining = timeout - (selectTickCounter / TICKS_PER_SECOND);

        // 每秒只推轻量倒计时包，避免全量 ClassSelectScreenPacket 带来整页刷新感。
        if (selectTickCounter % TICKS_PER_SECOND == 0) {
            org.espetro.network.NetworkManager.broadcastClassSelectTimerForTeam(
                currentSelectingTeam, secondsRemaining);
        }
    }

    private int getCurrentTimeoutSeconds() {
        if ("DEFEND".equals(currentSelectingTeam)) {
            return GameConfig.getDefendFactionSelectSeconds();
        } else if ("ATTACK".equals(currentSelectingTeam)) {
            return GameConfig.getAttackFactionSelectSeconds();
        }
        return 30;
    }

    /**
     * 检查当前编制选择是否超时
     */
    public boolean isCurrentSelectTimedOut() {
        if (!selectingActive) return false;
        int timeout = getCurrentTimeoutSeconds();
        return selectTickCounter >= timeout * TICKS_PER_SECOND;
    }

    /**
     * 检查选择是否进行中
     */
    public boolean isSelectingActive() {
        return selectingActive;
    }

    /**
     * 获取当前正在选择的队伍
     */
    public String getCurrentSelectingTeam() {
        return currentSelectingTeam;
    }

    /**
     * 获取选择剩余秒数
     */
    public int getRemainingSeconds() {
        if (!selectingActive) return 0;
        int timeout = getCurrentTimeoutSeconds();
        return Math.max(0, timeout - (selectTickCounter / TICKS_PER_SECOND));
    }

    /**
     * 获取攻方已选择的编制
     */
    public Set<String> getAttackSelectedClasses() {
        return new HashSet<>(attackFactionVotes.values());
    }

    /**
     * 获取守方已选择的编制
     */
    public Set<String> getDefendSelectedClasses() {
        return new HashSet<>(defendFactionVotes.values());
    }

    /**
     * 获取最终攻方编制
     */
    public String getFinalAttackClass() {
        return finalAttackClass;
    }

    /**
     * 获取最终守方编制
     */
    public String getFinalDefendClass() {
        return finalDefendClass;
    }

    /**
     * 重置
     */
    public void reset() {
        selectingActive = false;
        currentSelectingTeam = null;
        selectTickCounter = 0;
        attackFactionVotes.clear();
        defendFactionVotes.clear();
        finalAttackClass = null;
        finalDefendClass = null;
        selectedFactionPool = null;
    }
}
