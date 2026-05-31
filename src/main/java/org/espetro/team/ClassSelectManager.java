package org.espetro.team;

import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.espetro.Espetro;
import org.espetro.config.GameConfig;

import java.util.*;

/**
 * 编制选择管理器
 * 指挥官选择队伍编制
 * 支持分阶段：先守方(30s)，后攻方(30s)
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

    // 攻方已选择的编制ID列表
    private final Set<String> attackSelectedClasses = new HashSet<>();
    // 守方已选择的编制ID列表
    private final Set<String> defendSelectedClasses = new HashSet<>();

    // 攻方指挥官选择的编制
    private String attackCommanderClass = null;
    // 守方指挥官选择的编制
    private String defendCommanderClass = null;

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
        defendSelectedClasses.clear();
        defendCommanderClass = null;

        int timeout = GameConfig.getDefendFactionSelectSeconds();
        Espetro.LOGGER.info("守方编制选择开始！限时{}秒", timeout);
        Espetro.broadcastToTeam("DEFEND", "§6★ 指挥官请选择队伍编制！剩余时间: " + timeout + "秒 ★");
        Espetro.broadcastToTeam("ATTACK", "§7守方正在选择编制，请稍候...");

        // 发送编制选择界面给守方指挥官
        org.espetro.network.NetworkManager.broadcastClassSelectScreenForTeam("DEFEND", timeout);
    }

    /**
     * 开始攻方编制选择阶段
     */
    public void startAttackSelecting() {
        selectingActive = true;
        currentSelectingTeam = "ATTACK";
        selectTickCounter = 0;
        attackSelectedClasses.clear();
        attackCommanderClass = null;

        int timeout = GameConfig.getAttackFactionSelectSeconds();
        Espetro.LOGGER.info("攻方编制选择开始！限时{}秒", timeout);
        Espetro.broadcastToTeam("ATTACK", "§6★ 指挥官请选择队伍编制！剩余时间: " + timeout + "秒 ★");
        Espetro.broadcastToTeam("DEFEND", "§7攻方正在选择编制，请稍候...");

        // 发送编制选择界面给攻方指挥官
        org.espetro.network.NetworkManager.broadcastClassSelectScreenForTeam("ATTACK", timeout);
    }

    /**
     * 从所有加载的编制中随机选出（数量由 game.json faction_pool_size 决定）
     */
    private void generateFactionPool() {
        FactionDataLoader loader = FactionDataProvider.getOrCreateLoader();
        List<FactionDataLoader.FactionData> allFactions = new ArrayList<>();

        for (FactionDataLoader.FactionData faction : loader.getFactionArray()) {
            if (faction != null && faction.id != null && !faction.id.isEmpty()) {
                // 排除空文件的编制
                if (loader.getClassesForFaction(faction.id).length == 0) continue;
                allFactions.add(faction);
            }
        }

        int poolSize = GameConfig.getFactionPoolSize();
        // 随机打乱并取前 N 个
        Collections.shuffle(allFactions, new Random());
        selectedFactionPool = new ArrayList<>();
        for (int i = 0; i < Math.min(poolSize, allFactions.size()); i++) {
            selectedFactionPool.add(allFactions.get(i).id);
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
     * 指挥官选择编制（可重新选择，只保留最后一次）
     */
    public boolean selectClass(ServerPlayer commander, String classId) {
        if (!selectingActive) return false;

        // 检查是否是指挥官
        UUID commanderUUID = commander.getUUID();
        VoteManager voteManager = VoteManager.getInstance();

        String team = null;
        if (voteManager.isCommanderOf(commanderUUID, "ATTACK")) {
            team = "ATTACK";
        } else if (voteManager.isCommanderOf(commanderUUID, "DEFEND")) {
            team = "DEFEND";
        } else {
            Espetro.sendToPlayer(commander, "§c你不是指挥官，无法选择编制！");
            return false;
        }

        // 检查当前阶段是否允许该队伍选择
        if (!team.equals(currentSelectingTeam)) {
            Espetro.sendToPlayer(commander, "§c当前不是你的编制选择时间！");
            return false;
        }

        Set<String> selectedSet = "ATTACK".equals(team) ? attackSelectedClasses : defendSelectedClasses;

        // 清除该指挥官之前的选择（只保留最后一次）
        if ("ATTACK".equals(team)) {
            if (attackCommanderClass != null) {
                selectedSet.remove(attackCommanderClass);
            }
            attackCommanderClass = classId;
        } else {
            if (defendCommanderClass != null) {
                selectedSet.remove(defendCommanderClass);
            }
            defendCommanderClass = classId;
        }
        selectedSet.add(classId);

        Espetro.LOGGER.info("{} 指挥官 {} 选择了编制: {}", team, commander.getName().getString(), classId);

        // 通知同队伍玩家（显示星标）
        String notifyMessage = "§6★ 指挥官选择了: " + getClassDisplayName(classId) + " ★";
        Espetro.broadcastClassSelection(team, classId, notifyMessage);

        return true;
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

        // 确定最终选择：指挥官已选则用已选，否则从编制池随机
        if ("DEFEND".equals(finishedTeam)) {
            finalDefendClass = defendCommanderClass != null
                ? defendCommanderClass
                : getRandomFactionFromPool();
            String name = getClassDisplayName(finalDefendClass);
            Espetro.broadcastToTeam("DEFEND", "§6===== 守方编制已确定: §9" + name + "§6 =====");
            Espetro.LOGGER.info("守方编制选择结束！编制: {}", name);
        } else {
            finalAttackClass = attackCommanderClass != null
                ? attackCommanderClass
                : getRandomFactionFromPool();
            String name = getClassDisplayName(finalAttackClass);
            Espetro.broadcastToTeam("ATTACK", "§6===== 攻方编制已确定: §c" + name + "§6 =====");
            Espetro.LOGGER.info("攻方编制选择结束！编制: {}", name);
        }

        return finishedTeam;
    }

    /**
     * 从本局编制池随机选取一个编制
     */
    private String getRandomFactionFromPool() {
        if (selectedFactionPool == null || selectedFactionPool.isEmpty()) {
            Espetro.LOGGER.warn("编制池为空，无可用编制");
            return null;
        }
        return selectedFactionPool.get(new Random().nextInt(selectedFactionPool.size()));
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

        // 每秒更新一次
        if (selectTickCounter % TICKS_PER_SECOND == 0) {
            // 每秒广播编制选择界面（同步倒计时）
            org.espetro.network.NetworkManager.broadcastClassSelectScreenForTeam(currentSelectingTeam, secondsRemaining);

            if (secondsRemaining > 0 && secondsRemaining <= 5) {
                String status = "ATTACK".equals(currentSelectingTeam)
                    ? (attackCommanderClass != null ? "已选" : "待选")
                    : (defendCommanderClass != null ? "已选" : "待选");
                Espetro.broadcastToTeam(currentSelectingTeam,
                    "§e编制选择剩余: §c" + secondsRemaining + "秒 §7| 状态:" + status);
            }

            // 向等待方也显示倒计时
            String waitingTeam = "DEFEND".equals(currentSelectingTeam) ? "ATTACK" : "DEFEND";
            String selectingName = "DEFEND".equals(currentSelectingTeam) ? "守方" : "攻方";
            Espetro.broadcastToTeam(waitingTeam, "§7" + selectingName + "正在选择编制，请稍候... [§e" + secondsRemaining + "秒§7]");
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
        return new HashSet<>(attackSelectedClasses);
    }

    /**
     * 获取守方已选择的编制
     */
    public Set<String> getDefendSelectedClasses() {
        return new HashSet<>(defendSelectedClasses);
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
        attackSelectedClasses.clear();
        defendSelectedClasses.clear();
        attackCommanderClass = null;
        defendCommanderClass = null;
        finalAttackClass = null;
        finalDefendClass = null;
        selectedFactionPool = null;
    }
}
