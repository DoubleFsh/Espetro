package org.espetro.team;

import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.Score;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.criteria.ObjectiveCriteria;
import org.espetro.Espetro;
import org.espetro.config.GameConfig;
import org.espetro.stats.PlayerMatchStatsManager;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 职业人数管理器
 * 使用记分板追踪每个职业的当前玩家数量
 */
public class ClassCountManager {

    private static final String SCOREBOARD_OBJECTIVE = "class_count";
    // Scoreboard objective names are limited to 16 characters.
    private static final String VARIANT_SCOREBOARD_OBJECTIVE = "class_variant";
    private static final String ATTACK_TEAM = "ATTACK";
    private static final String DEFEND_TEAM = "DEFEND";
    private static final String[] COUNT_TEAMS = {ATTACK_TEAM, DEFEND_TEAM};
    private static ClassCountManager INSTANCE;

    // 玩家UUID -> 当前职业ID (内存缓存，用于快速查询)
    private final Map<UUID, String> playerClasses = new HashMap<>();
    // 玩家UUID -> 当前职业装备变体ID
    private final Map<UUID, String> playerVariants = new HashMap<>();
    // 玩家UUID -> 当前阵营ID
    private final Map<UUID, String> playerFactions = new HashMap<>();
    // 玩家UUID -> 原始队伍（ATTACK/DEFEND，不受编制选择影响）
    private final Map<UUID, String> playerTeams = new HashMap<>();
    // 与当前职业记录分离，避免离队、死亡或短线重连绕过换职冷却。
    private final ClassSwitchCooldownTracker classSwitchCooldowns =
        new ClassSwitchCooldownTracker();
    /** 最近一次 TEAMMATES_NEED 拒绝时的需求人数（供 messageFor 读取后清除）。 */
    private static final Map<UUID, Integer> pendingTeammatesNeedMessage = new ConcurrentHashMap<>();

    public ClassCountManager() {
        INSTANCE = this;
    }

    public static ClassCountManager getInstance() {
        return INSTANCE;
    }

    /**
     * 获取记分板目标（不存在则创建）
     */
    private Objective getOrCreateObjective(Scoreboard scoreboard) {
        Objective objective = scoreboard.getObjective(SCOREBOARD_OBJECTIVE);
        if (objective == null) {
            objective = scoreboard.addObjective(
                SCOREBOARD_OBJECTIVE,
                ObjectiveCriteria.DUMMY,
                Component.literal("职业人数"),
                ObjectiveCriteria.RenderType.INTEGER
            );
        }
        return objective;
    }

    private Objective getOrCreateVariantObjective(Scoreboard scoreboard) {
        Objective objective = scoreboard.getObjective(VARIANT_SCOREBOARD_OBJECTIVE);
        if (objective == null) {
            objective = scoreboard.addObjective(
                VARIANT_SCOREBOARD_OBJECTIVE,
                ObjectiveCriteria.DUMMY,
                Component.literal("职业变体人数"),
                ObjectiveCriteria.RenderType.INTEGER
            );
        }
        return objective;
    }

    /**
     * 获取记分板（从服务器）
     */
    private Scoreboard getScoreboard() {
        MinecraftServer server = Espetro.getServer();
        if (server == null) {
            return null;
        }
        return server.getScoreboard();
    }

    /**
     * 获取指定队伍的职业当前人数（从记分板）
     */
    public int getCount(String team, String classId) {
        Scoreboard scoreboard = getScoreboard();
        if (scoreboard == null) {
            return 0;
        }
        Objective objective = scoreboard.getObjective(SCOREBOARD_OBJECTIVE);
        if (objective == null) {
            return 0;
        }
        String scoreHolder = getScoreHolder(team, classId);
        Score score = scoreboard.getOrCreatePlayerScore(scoreHolder, objective);
        return score.getScore();
    }

    /**
     * 兼容旧调用：返回攻守两队该职业人数总和。
     */
    public int getCount(String classId) {
        return getCount(ATTACK_TEAM, classId) + getCount(DEFEND_TEAM, classId);
    }

    public int getVariantCount(String team, String classId, String variantId) {
        Scoreboard scoreboard = getScoreboard();
        if (scoreboard == null) return 0;
        Objective objective = scoreboard.getObjective(VARIANT_SCOREBOARD_OBJECTIVE);
        if (objective == null) return 0;
        return scoreboard.getOrCreatePlayerScore(
            getVariantScoreHolder(team, classId, variantId), objective).getScore();
    }

    public int getVariantCount(String classId, String variantId) {
        return getVariantCount(ATTACK_TEAM, classId, variantId)
            + getVariantCount(DEFEND_TEAM, classId, variantId);
    }

    /**
     * 获取职业人数上限
     */
    public int getMaxCount(String classId) {
        FactionDataLoader loader = FactionDataProvider.getOrCreateLoader();
        FactionDataLoader.ClassKitData kit = loader.getClassKit(classId);
        return kit != null ? kit.maxPlayers : 5;
    }

    /**
     * 检查职业是否已满（仅非 team_count 职业的队伍总限）。
     * team_count 职业请用小队扫描，不应调用本方法做满员判断。
     */
    public boolean isFull(String team, String classId) {
        FactionDataLoader.ClassKitData kit = FactionDataProvider.getOrCreateLoader().getClassKit(classId);
        if (kit != null && kit.teamCount) {
            return false;
        }
        return getCount(team, classId) >= getMaxCount(classId);
    }

    /**
     * 兼容旧调用：任一队伍该职业满员就视为满员。
     */
    public boolean isFull(String classId) {
        return isFull(ATTACK_TEAM, classId) || isFull(DEFEND_TEAM, classId);
    }

    public boolean isVariantFull(String team, String classId, String variantId) {
        FactionDataLoader loader = FactionDataProvider.getOrCreateLoader();
        FactionDataLoader.ClassKitData kit = loader.getClassKit(classId);
        if (kit != null && !kit.strictCount) {
            // 非严格模式下变体不拥有独立人数名额，永不视为满员
            return false;
        }
        FactionDataLoader.ClassVariantData variant = loader.getClassVariant(classId, variantId);
        return variant == null || getVariantCount(team, classId, variantId) >= variant.maxPlayers;
    }

    /**
     * 增加职业分数
     */
    private void incrementScore(String team, String classId, int delta) {
        Scoreboard scoreboard = getScoreboard();
        if (scoreboard == null) return;

        Objective objective = getOrCreateObjective(scoreboard);
        String scoreHolder = getScoreHolder(team, classId);
        int currentScore = getCount(team, classId);
        int newScore = Math.max(0, currentScore + delta);
        scoreboard.getOrCreatePlayerScore(scoreHolder, objective).setScore(newScore);
    }

    private void incrementVariantScore(String team, String classId, String variantId, int delta) {
        Scoreboard scoreboard = getScoreboard();
        if (scoreboard == null) return;
        Objective objective = getOrCreateVariantObjective(scoreboard);
        String scoreHolder = getVariantScoreHolder(team, classId, variantId);
        int newScore = Math.max(0, getVariantCount(team, classId, variantId) + delta);
        scoreboard.getOrCreatePlayerScore(scoreHolder, objective).setScore(newScore);
    }

    /**
     * 玩家选择职业
     * 返回是否成功（职业未满）
     */
    public boolean selectClass(ServerPlayer player, String classId) {
        FactionDataLoader.ClassKitData kit = FactionDataProvider.getOrCreateLoader().getClassKit(classId);
        if (kit == null || kit.variants == null || kit.variants.size() != 1) {
            return false;
        }
        return selectClass(player, classId, kit.variants.keySet().iterator().next());
    }

    public boolean selectClass(ServerPlayer player, String classId, String variantId) {
        return selectClassVariant(player, classId, variantId) == SelectionResult.SUCCESS;
    }

    /** 服务端权威、原子地选择职业及装备变体。 */
    public SelectionResult selectClassVariant(ServerPlayer player, String classId, String variantId) {
        UUID uuid = player.getUUID();
        String team = getEffectivePlayerTeam(uuid);
        if (team == null) {
            Espetro.LOGGER.warn("玩家 {} 无队伍记录，无法选择职业 {}", player.getName().getString(), classId);
            return SelectionResult.NO_TEAM;
        }

        FactionDataLoader loader = FactionDataProvider.getOrCreateLoader();
        FactionDataLoader.ClassKitData kit = loader.getClassKit(classId);
        if (kit == null || kit.factionId == null
            || !kit.factionId.equals(playerFactions.get(uuid))) {
            Espetro.LOGGER.warn("玩家 {} 尝试选择不属于当前编制的职业 {}",
                player.getName().getString(), classId);
            return SelectionResult.INVALID_CLASS;
        }
        FactionDataLoader.ClassVariantData variant = kit.getVariant(variantId);
        if (variant == null) {
            return SelectionResult.INVALID_VARIANT;
        }
        variantId = variant.id;

        String oldClassId = playerClasses.get(uuid);
        String oldVariantId = playerVariants.get(uuid);

        // 完全相同的职业与变体不重复清空/发放装备。
        if (classId.equals(oldClassId) && variantId.equals(oldVariantId)) {
            PlayerMatchStatsManager.getInstance().onClassSelected(player, classId,
                kit.icon, kit.iconImage);
            return SelectionResult.SUCCESS;
        }

        if (getClassSwitchCooldownRemaining(uuid) > 0) {
            return SelectionResult.CLASS_SWITCH_COOLDOWN;
        }

        int squadId = SquadManager.getInstance().getPlayerSquadId(uuid);

        // 所有职业均须先加入班组小队后再选择。
        if (squadId == SquadManager.NO_SQUAD) {
            return SelectionResult.REQUIRES_SQUAD;
        }

        // teammates_need：小队至少 N 人（含自己）才可选该职
        if (kit.teammatesNeed > 0) {
            int squadSize = SquadManager.getInstance().getSquadMemberUuids(team, squadId).size();
            if (squadSize < kit.teammatesNeed) {
                pendingTeammatesNeedMessage.put(uuid, kit.teammatesNeed);
                return SelectionResult.TEAMMATES_NEED;
            }
        }

        // leader_only：仅小队长可选
        if (kit.leaderOnly && !SquadManager.getInstance().isSquadLeader(uuid)) {
            return SelectionResult.LEADER_ONLY;
        }

        int squadSize = SquadManager.getInstance().getSquadMemberUuids(team, squadId).size();

        // unlock_min_squad：小队达到此人数后解锁（优先级高于 unlock_per_n）
        if (kit.unlockMinSquad > 0 && squadSize < kit.unlockMinSquad) {
            return SelectionResult.UNLOCK_MIN_SQUAD;
        }

        // unlock_per_n：每 N 个小队员解锁 1 个名额
        if (kit.unlockPerN > 0) {
            int available = squadSize / kit.unlockPerN;
            if (available <= 0) {
                return SelectionResult.UNLOCK_PER_N;
            }
            if (countClassInSquad(team, squadId, classId) >= available) {
                return SelectionResult.UNLOCK_PER_N_FULL;
            }
        }

        // 同职业换变体时，玩家仍占父职业 1 个名额，不重复检查职业满员；
        // 换到其它职业时，目标职业人数按当前（不含自己）计算。
        boolean changingClass = !classId.equals(oldClassId);
        boolean changingVariantOnly = classId.equals(oldClassId) && !variantId.equals(oldVariantId);

        if (kit.teamCount) {
            // maxPlayers = 每小队父职业上限；任意变体选择都计入该上限。
            if (changingClass && countClassInSquad(team, squadId, classId) >= kit.maxPlayers) {
                Espetro.LOGGER.info("{} 方小队 {} 职业 {} 已满，玩家 {} 无法选择",
                    team, squadId, classId, player.getName().getString());
                return SelectionResult.SQUAD_CLASS_FULL;
            }
            // strict_count：变体有独立上限，但仍叠加在父职业小队计数之上。
            if (kit.strictCount && !variantId.equals(oldVariantId)
                && countVariantInSquad(team, squadId, classId, variantId) >= variant.maxPlayers) {
                return SelectionResult.VARIANT_FULL;
            }
        } else {
            // 编制/队伍总限：父职业人数（含所有变体）。
            if (changingClass && isFull(team, classId)) {
                Espetro.LOGGER.info("{} 方职业 {} 已满，玩家 {} 无法选择",
                    team, classId, player.getName().getString());
                return SelectionResult.CLASS_FULL;
            }
            if (kit.maxPerSquad > 0
                && changingClass
                && countClassInSquad(team, squadId, classId) >= kit.maxPerSquad) {
                return SelectionResult.SQUAD_CLASS_FULL;
            }
            // strict_count 变体独立上限；非 strict 时变体不单独限员，只占父职业名额。
            if (kit.strictCount && !variantId.equals(oldVariantId)
                && getVariantCount(team, classId, variantId) >= variant.maxPlayers) {
                return SelectionResult.VARIANT_FULL;
            }
        }

        if (oldClassId != null && oldVariantId == null) {
            FactionDataLoader.ClassKitData oldKit = loader.getClassKit(oldClassId);
            if (oldKit != null && oldKit.variants != null && oldKit.variants.size() == 1) {
                oldVariantId = oldKit.variants.keySet().iterator().next();
            }
        }

        // 队伍记分板只服务非 team_count 职业（编制总限）。
        // 父职业 +1/-1 覆盖该职业下所有变体；strict 时再维护变体分计数。
        FactionDataLoader.ClassKitData oldKitForBoard = oldClassId != null
            ? loader.getClassKit(oldClassId) : null;
        boolean oldUsesTeamBoard = oldKitForBoard == null || !oldKitForBoard.teamCount;
        boolean newUsesTeamBoard = !kit.teamCount;

        if (oldClassId != null && changingClass && oldUsesTeamBoard) {
            incrementScore(team, oldClassId, -1);
        }
        if (oldClassId != null && oldVariantId != null && oldUsesTeamBoard
            && (changingClass || changingVariantOnly)
            && (oldKitForBoard == null || oldKitForBoard.strictCount)) {
            incrementVariantScore(team, oldClassId, oldVariantId, -1);
        }

        if (changingClass && newUsesTeamBoard) {
            incrementScore(team, classId, 1);
        }
        if (kit.strictCount && newUsesTeamBoard
            && (changingClass || changingVariantOnly)) {
            incrementVariantScore(team, classId, variantId, 1);
        }
        playerClasses.put(uuid, classId);
        playerVariants.put(uuid, variantId);
        ClassEquipment.applyClassBonuses(player, kit);

        if (playerFactions.get(uuid) == null) {
            String factionId = extractFactionId(classId);
            playerFactions.put(uuid, factionId);
        }

        Espetro.LOGGER.debug(
            "玩家 {} 选择 {} 方职业 {} / {} (team_count={}, strict={}, 父职业队伍计数 {}/{})",
            player.getName().getString(), team, classId, variantId,
            kit.teamCount, kit.strictCount, getCount(team, classId), getMaxCount(classId));

        PlayerMatchStatsManager.getInstance().onClassSelected(player, classId,
                kit.icon, kit.iconImage);
        classSwitchCooldowns.start(
            uuid, GameConfig.getClassSwitchCooldownSeconds(), System.currentTimeMillis());
        org.espetro.vehicle.VehicleSeatAccessPolicy.revalidateCurrentSeat(player);
        return SelectionResult.SUCCESS;
    }

    public int getClassSwitchCooldownRemaining(UUID playerId) {
        return classSwitchCooldowns.getRemainingSeconds(
            playerId, System.currentTimeMillis());
    }

    public enum SelectionResult {
        SUCCESS,
        NO_TEAM,
        INVALID_CLASS,
        INVALID_VARIANT,
        CLASS_FULL,
        VARIANT_FULL,
        REQUIRES_SQUAD,
        SQUAD_CLASS_FULL,
        CLASS_SWITCH_COOLDOWN,
        OUT_OF_RANGE,
        /** 小队人数不足 teammates_need */
        TEAMMATES_NEED,
        /** 仅小队长可选 */
        LEADER_ONLY,
        /** 小队人数未达 unlock_min_squad */
        UNLOCK_MIN_SQUAD,
        /** unlock_per_n 目前无可选名额（available <= 0） */
        UNLOCK_PER_N,
        /** unlock_per_n 名额已用完 */
        UNLOCK_PER_N_FULL
    }

    /** 用户可读拒绝文案（聊天 / AuraTip）。 */
    public static String messageFor(SelectionResult result, UUID playerId) {
        if (result == null) {
            return "§c当前无法选择该职业。";
        }
        return switch (result) {
            case SUCCESS -> "";
            case CLASS_FULL -> "§c该职业全队人数已满！请选择其他职业。";
            case VARIANT_FULL -> "§c该装备变体人数已满！请选择其他变体。";
            case SQUAD_CLASS_FULL -> "§c本小队该职业人数已满！请选择其他职业或小队。";
            case REQUIRES_SQUAD -> "§c请先加入班组小队后再选择职业！";
            case TEAMMATES_NEED -> {
                Integer need = playerId != null ? pendingTeammatesNeedMessage.remove(playerId) : null;
                yield need != null
                    ? teammatesNeedMessage(need)
                    : "§c该职业需要小队人数达到要求后才能选择！";
            }
            case CLASS_SWITCH_COOLDOWN -> {
                int sec = INSTANCE != null
                    ? INSTANCE.getClassSwitchCooldownRemaining(playerId) : 0;
                yield "§c职业切换冷却中，还需等待 " + Math.max(1, sec) + " 秒。";
            }
            case INVALID_VARIANT -> "§c无效的职业装备变体。";
            case INVALID_CLASS -> "§c该职业不属于你当前选择的编制。";
            case NO_TEAM -> "§c你尚未加入攻防方，无法选择职业。";
            case OUT_OF_RANGE -> "§c只能在选择部署点时、主基地附近或己方 Radio 轮盘中选择职业！";
            case LEADER_ONLY -> "§c该职业仅小队长可选！";
            case UNLOCK_MIN_SQUAD -> "§c小队人数不足，无法选择该职业！";
            case UNLOCK_PER_N, UNLOCK_PER_N_FULL -> "§c该职业名额已用完或小队人数不足！";
        };
    }

    /**
     * 可读文案：带上具体 teammates_need 数字（客户端灰显提示可用）。
     */
    public static String teammatesNeedMessage(int need) {
        return "§c该职业需要小队至少 " + Math.max(1, need) + " 人！";
    }

    public int countClassInSquad(String team, int squadId, String classId) {
        if (team == null || classId == null || squadId == SquadManager.NO_SQUAD) {
            return 0;
        }
        int count = 0;
        for (UUID member : SquadManager.getInstance().getSquadMemberUuids(team, squadId)) {
            if (classId.equals(playerClasses.get(member))) {
                count++;
            }
        }
        return count;
    }

    public int countVariantInSquad(String team, int squadId, String classId, String variantId) {
        if (team == null || classId == null || variantId == null || squadId == SquadManager.NO_SQUAD) {
            return 0;
        }
        int count = 0;
        for (UUID member : SquadManager.getInstance().getSquadMemberUuids(team, squadId)) {
            if (classId.equals(playerClasses.get(member))
                && variantId.equals(playerVariants.get(member))) {
                count++;
            }
        }
        return count;
    }

    /**
     * 对查看者有效的职业当前人数（UI）。
     * team_count：本小队人数；否则：队伍人数。
     */
    public int getEffectiveClassCountForViewer(UUID viewerId, String team, String classId) {
        FactionDataLoader.ClassKitData kit = FactionDataProvider.getOrCreateLoader().getClassKit(classId);
        if (kit != null && kit.teamCount) {
            int squadId = SquadManager.getInstance().getPlayerSquadId(viewerId);
            if (squadId == SquadManager.NO_SQUAD) {
                return 0;
            }
            return countClassInSquad(team, squadId, classId);
        }
        return getCount(team, classId);
    }

    public int getSquadClassCountForViewer(UUID viewerId, String team, String classId) {
        int squadId = SquadManager.getInstance().getPlayerSquadId(viewerId);
        if (squadId == SquadManager.NO_SQUAD) {
            return 0;
        }
        return countClassInSquad(team, squadId, classId);
    }

    /**
     * 离开班组小队时撤销当前职业。
     *
     * 所有职业都要求玩家属于小队；因此离开、换队或小队解散后，不能保留职业记录或装备。
     * 非 {@code team_count} 职业还须归还其占用的队伍/变体人数名额。
     */
    public void onPlayerLeftSquad(ServerPlayer player) {
        if (player == null) {
            return;
        }
        clearClassOnSquadExit(player.getUUID(), player, true);
    }

    public void onPlayerLeftSquadOffline(UUID uuid) {
        clearClassOnSquadExit(uuid, null, false);
    }

    private void clearClassOnSquadExit(UUID uuid, ServerPlayer onlinePlayer, boolean notify) {
        String team = getEffectivePlayerTeam(uuid);
        String variantId = playerVariants.remove(uuid);
        String classId = playerClasses.remove(uuid);
        FactionDataLoader.ClassKitData kit = classId != null
            ? FactionDataProvider.getOrCreateLoader().getClassKit(classId) : null;

        if (team != null && classId != null && (kit == null || !kit.teamCount)) {
            incrementScore(team, classId, -1);
            if (variantId != null && (kit == null || kit.strictCount)) {
                incrementVariantScore(team, classId, variantId, -1);
            }
        }

        // 在线玩家必须立刻清空，不能在离队后携带职业物品或沿用职业状态。
        if (onlinePlayer != null) {
            ClassEquipment.clearEquipment(onlinePlayer);
            org.espetro.vehicle.VehicleSeatAccessPolicy.revalidateCurrentSeat(onlinePlayer);
        }
        PlayerMatchStatsManager.getInstance().onClassCleared(uuid);
        if (notify && onlinePlayer != null) {
            onlinePlayer.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                "§e已离开班组小队，背包和职业已清空。请重新选择职业。"));
        }
    }

    /**
     * 玩家离开时移除职业记录
     */
    public void removePlayer(Player player) {
        UUID uuid = player.getUUID();
        String team = getEffectivePlayerTeam(uuid);
        String classId = playerClasses.remove(uuid);
        String variantId = playerVariants.remove(uuid);
        if (team != null && classId != null) {
            FactionDataLoader.ClassKitData kit =
                FactionDataProvider.getOrCreateLoader().getClassKit(classId);
            // team_count 职业不占编制总限记分板，离线/移除时不改队伍分数。
            if (kit == null || !kit.teamCount) {
                incrementScore(team, classId, -1);
                if (variantId != null && (kit == null || kit.strictCount)) {
                    incrementVariantScore(team, classId, variantId, -1);
                }
            }
        }
        playerFactions.remove(uuid);
        playerTeams.remove(uuid);
        PlayerMatchStatsManager.getInstance().onClassCleared(uuid);
    }

    /**
     * 设置玩家的原始队伍（ATTACK/DEFEND）
     */
    public void setPlayerTeam(UUID uuid, String team) {
        playerTeams.put(uuid, team);
    }

    /**
     * 获取玩家的原始队伍
     */
    public String getPlayerTeam(UUID uuid) {
        return playerTeams.get(uuid);
    }

    /**
     * 获取玩家用于职业计数的队伍；没有显式队伍时尝试从 faction 回退推断。
     */
    public String getEffectivePlayerTeam(UUID uuid) {
        String team = playerTeams.get(uuid);
        if (team != null) {
            return normalizeTeam(team);
        }

        String factionId = playerFactions.get(uuid);
        if (factionId == null) {
            return null;
        }
        return normalizeTeam(GameStateManager.getTeamFromFactionStatic(factionId));
    }

    /**
     * 获取所有职业的人数
     */
    public Map<String, Integer> getAllCounts() {
        Map<String, Integer> result = new HashMap<>();
        FactionDataLoader loader = FactionDataProvider.getOrCreateLoader();
        for (FactionDataLoader.FactionData faction : loader.getFactionArray()) {
            if (faction != null && faction.id != null) {
                for (FactionDataLoader.ClassKitData kit : loader.getClassesForFaction(faction.id)) {
                    result.put(kit.id, getCount(kit.id));
                }
            }
        }
        return result;
    }

    /**
     * 获取指定队伍和阵营的所有职业人数
     */
    public Map<String, Integer> getCountsForFaction(String team, String factionId) {
        if (team == null) {
            return getCountsForFaction(factionId);
        }

        FactionDataLoader loader = FactionDataProvider.getOrCreateLoader();
        Map<String, Integer> result = new HashMap<>();
        for (FactionDataLoader.ClassKitData kit : loader.getClassesForFaction(factionId)) {
            result.put(kit.id, getCount(team, kit.id));
        }
        return result;
    }

    /**
     * 兼容旧调用：返回攻守两队合计人数。
     */
    public Map<String, Integer> getCountsForFaction(String factionId) {
        FactionDataLoader loader = FactionDataProvider.getOrCreateLoader();
        Map<String, Integer> result = new HashMap<>();
        for (FactionDataLoader.ClassKitData kit : loader.getClassesForFaction(factionId)) {
            result.put(kit.id, getCount(kit.id));
        }
        return result;
    }

    public Map<String, Map<String, Integer>> getVariantCountsForFaction(String team, String factionId) {
        FactionDataLoader loader = FactionDataProvider.getOrCreateLoader();
        Map<String, Map<String, Integer>> result = new HashMap<>();
        for (FactionDataLoader.ClassKitData kit : loader.getClassesForFaction(factionId)) {
            Map<String, Integer> variants = new HashMap<>();
            if (kit.variants != null) {
                for (FactionDataLoader.ClassVariantData variant : kit.variants.values()) {
                    variants.put(variant.id, team == null
                        ? getVariantCount(kit.id, variant.id)
                        : getVariantCount(team, kit.id, variant.id));
                }
            }
            result.put(kit.id, variants);
        }
        return result;
    }

    /** Per-viewer squad counts used by the lightweight deploy-state packet. */
    public Map<String, Integer> getSquadCountsForViewer(
        UUID viewerId,
        String team,
        String factionId
    ) {
        FactionDataLoader loader = FactionDataProvider.getOrCreateLoader();
        Map<String, Integer> result = new HashMap<>();
        for (FactionDataLoader.ClassKitData kit : loader.getClassesForFaction(factionId)) {
            result.put(kit.id, getSquadClassCountForViewer(viewerId, team, kit.id));
        }
        return result;
    }

    /**
     * Variant limits for team_count kits are squad-scoped; other kits remain
     * team-scoped. This mirrors selectClassVariant without rebuilding the full
     * equipment-preview packet.
     */
    public Map<String, Map<String, Integer>> getVariantCountsForViewer(
        UUID viewerId,
        String team,
        String factionId
    ) {
        FactionDataLoader loader = FactionDataProvider.getOrCreateLoader();
        int squadId = SquadManager.getInstance().getPlayerSquadId(viewerId);
        Map<String, Map<String, Integer>> result = new HashMap<>();
        for (FactionDataLoader.ClassKitData kit : loader.getClassesForFaction(factionId)) {
            Map<String, Integer> variants = new HashMap<>();
            if (kit.variants != null) {
                for (FactionDataLoader.ClassVariantData variant : kit.variants.values()) {
                    int count = kit.teamCount
                        ? countVariantInSquad(team, squadId, kit.id, variant.id)
                        : getVariantCount(team, kit.id, variant.id);
                    variants.put(variant.id, count);
                }
            }
            result.put(kit.id, variants);
        }
        return result;
    }

    /**
     * 获取玩家的当前职业
     */
    public String getPlayerClass(UUID uuid) {
        return playerClasses.get(uuid);
    }

    public String getPlayerVariant(UUID uuid) {
        return playerVariants.get(uuid);
    }

    /**
     * 获取玩家的当前阵营ID
     */
    public String getPlayerFaction(UUID uuid) {
        return playerFactions.get(uuid);
    }

    /**
     * 设置玩家的阵营ID
     */
    public void setPlayerFaction(UUID uuid, String factionId) {
        playerFactions.put(uuid, factionId);
    }

    /**
     * 从职业ID中提取阵营ID
     * 例如: us_airborne_infantry -> us_airborne
     */
    private String extractFactionId(String classId) {
        if (classId == null) return null;
        int lastUnderscore = classId.lastIndexOf('_');
        if (lastUnderscore > 0) {
            return classId.substring(0, lastUnderscore).toLowerCase();
        }
        return classId.toLowerCase();
    }

    /**
     * 初始化所有职业的记分板分数为0。
     * 记分板会随世界保存，服务端重启时必须强制清零，避免旧坑位残留。
     */
    public void initializeAllClassScores() {
        Scoreboard scoreboard = getScoreboard();
        if (scoreboard == null) return;

        Objective objective = getOrCreateObjective(scoreboard);
        Objective variantObjective = getOrCreateVariantObjective(scoreboard);
        FactionDataLoader loader = FactionDataProvider.getOrCreateLoader();

        for (FactionDataLoader.FactionData faction : loader.getFactionArray()) {
            if (faction != null && faction.id != null) {
                for (FactionDataLoader.ClassKitData kit : loader.getClassesForFaction(faction.id)) {
                    for (String team : COUNT_TEAMS) {
                        scoreboard.getOrCreatePlayerScore(getScoreHolder(team, kit.id), objective).setScore(0);
                    }
                    // 清理旧版本无队伍维度的计数，避免调试/旧调用看到残留。
                    scoreboard.getOrCreatePlayerScore(getLegacyScoreHolder(kit.id), objective).setScore(0);
                    if (kit.variants != null) {
                        for (FactionDataLoader.ClassVariantData variant : kit.variants.values()) {
                            for (String team : COUNT_TEAMS) {
                                scoreboard.getOrCreatePlayerScore(
                                    getVariantScoreHolder(team, kit.id, variant.id), variantObjective).setScore(0);
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * 重置所有职业人数和玩家记录
     */
    public void resetAll() {
        // 清空所有玩家职业和阵营记录
        playerClasses.clear();
        playerVariants.clear();
        playerFactions.clear();
        playerTeams.clear();
        classSwitchCooldowns.clearAll();

        // 重置所有职业分数为0
        Scoreboard scoreboard = getScoreboard();
        if (scoreboard == null) return;

        Objective objective = scoreboard.getObjective(SCOREBOARD_OBJECTIVE);
        Objective variantObjective = scoreboard.getObjective(VARIANT_SCOREBOARD_OBJECTIVE);
        if (objective == null && variantObjective == null) return;

        // 获取所有分数持有者并设置为0
        FactionDataLoader loader = FactionDataProvider.getOrCreateLoader();
        for (FactionDataLoader.FactionData faction : loader.getFactionArray()) {
            if (faction != null && faction.id != null) {
                for (FactionDataLoader.ClassKitData kit : loader.getClassesForFaction(faction.id)) {
                    for (String team : COUNT_TEAMS) {
                        String scoreHolder = getScoreHolder(team, kit.id);
                        if (objective != null && scoreboard.hasPlayerScore(scoreHolder, objective)) {
                            scoreboard.getOrCreatePlayerScore(scoreHolder, objective).setScore(0);
                        }
                    }

                    String legacyScoreHolder = getLegacyScoreHolder(kit.id);
                    if (objective != null && scoreboard.hasPlayerScore(legacyScoreHolder, objective)) {
                        scoreboard.getOrCreatePlayerScore(legacyScoreHolder, objective).setScore(0);
                    }
                    if (variantObjective != null && kit.variants != null) {
                        for (FactionDataLoader.ClassVariantData variant : kit.variants.values()) {
                            for (String team : COUNT_TEAMS) {
                                String holder = getVariantScoreHolder(team, kit.id, variant.id);
                                if (scoreboard.hasPlayerScore(holder, variantObjective)) {
                                    scoreboard.getOrCreatePlayerScore(holder, variantObjective).setScore(0);
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private String getScoreHolder(String team, String classId) {
        return "class_" + normalizeTeam(team) + "_" + classId;
    }

    private String getLegacyScoreHolder(String classId) {
        return "class_" + classId;
    }

    private String getVariantScoreHolder(String team, String classId, String variantId) {
        return "variant_" + normalizeTeam(team) + "_" + classId + "_" + variantId;
    }

    private String normalizeTeam(String team) {
        if (ATTACK_TEAM.equalsIgnoreCase(team)) {
            return ATTACK_TEAM;
        }
        if (DEFEND_TEAM.equalsIgnoreCase(team)) {
            return DEFEND_TEAM;
        }
        return team == null ? "UNKNOWN" : team.toUpperCase();
    }
}
