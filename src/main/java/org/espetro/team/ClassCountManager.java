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

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 职业人数管理器
 * 使用记分板追踪每个职业的当前玩家数量
 */
public class ClassCountManager {

    private static final String SCOREBOARD_OBJECTIVE = "class_count";
    private static ClassCountManager INSTANCE;

    // 玩家UUID -> 当前职业ID (内存缓存，用于快速查询)
    private final Map<UUID, String> playerClasses = new HashMap<>();
    // 玩家UUID -> 当前阵营ID
    private final Map<UUID, String> playerFactions = new HashMap<>();
    // 玩家UUID -> 原始队伍（ATTACK/DEFEND，不受编制选择影响）
    private final Map<UUID, String> playerTeams = new HashMap<>();

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
     * 获取职业当前人数（从记分板）
     */
    public int getCount(String classId) {
        Scoreboard scoreboard = getScoreboard();
        if (scoreboard == null) {
            return 0;
        }
        Objective objective = scoreboard.getObjective(SCOREBOARD_OBJECTIVE);
        if (objective == null) {
            return 0;
        }
        String scoreHolder = "class_" + classId;
        Score score = scoreboard.getOrCreatePlayerScore(scoreHolder, objective);
        return score.getScore();
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
     * 检查职业是否已满
     */
    public boolean isFull(String classId) {
        return getCount(classId) >= getMaxCount(classId);
    }

    /**
     * 增加职业分数
     */
    private void incrementScore(String classId, int delta) {
        Scoreboard scoreboard = getScoreboard();
        if (scoreboard == null) return;

        Objective objective = getOrCreateObjective(scoreboard);
        String scoreHolder = "class_" + classId;
        int currentScore = getCount(classId);
        int newScore = Math.max(0, currentScore + delta);
        scoreboard.getOrCreatePlayerScore(scoreHolder, objective).setScore(newScore);
    }

    /**
     * 玩家选择职业
     * 返回是否成功（职业未满）
     */
    public boolean selectClass(ServerPlayer player, String classId) {
        UUID uuid = player.getUUID();
        String oldClassId = playerClasses.get(uuid);

        // 如果选择了同一个职业，不做任何操作
        if (classId.equals(oldClassId)) {
            return true;
        }

        // 检查目标职业是否已满
        if (isFull(classId)) {
            Espetro.LOGGER.info("职业 {} 已满，玩家 {} 无法选择", classId, player.getName().getString());
            return false;
        }

        // 离开旧职业（从记分板减1）
        if (oldClassId != null) {
            incrementScore(oldClassId, -1);
        }

        // 加入新职业（记分板加1）
        incrementScore(classId, 1);
        playerClasses.put(uuid, classId);
        
        // 仅在玩家没有faction记录时才从职业ID提取阵营
        // 避免覆盖编制选择阶段已设置好的 faction（如 russia_army）
        if (playerFactions.get(uuid) == null) {
            String factionId = extractFactionId(classId);
            playerFactions.put(uuid, factionId);
        }

        Espetro.LOGGER.debug("玩家 {} 选择职业 {} ({}/{})",
            player.getName().getString(), classId,
            getCount(classId), getMaxCount(classId));

        return true;
    }

    /**
     * 玩家离开时移除职业记录
     */
    public void removePlayer(Player player) {
        UUID uuid = player.getUUID();
        String classId = playerClasses.remove(uuid);
        if (classId != null) {
            incrementScore(classId, -1);
        }
        playerFactions.remove(uuid);
        playerTeams.remove(uuid);
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
     * 获取指定阵营的所有职业人数
     */
    public Map<String, Integer> getCountsForFaction(String factionId) {
        FactionDataLoader loader = FactionDataProvider.getOrCreateLoader();
        Map<String, Integer> result = new HashMap<>();
        for (FactionDataLoader.ClassKitData kit : loader.getClassesForFaction(factionId)) {
            result.put(kit.id, getCount(kit.id));
        }
        return result;
    }

    /**
     * 获取玩家的当前职业
     */
    public String getPlayerClass(UUID uuid) {
        return playerClasses.get(uuid);
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
     * 初始化所有职业的记分板分数为0
     */
    public void initializeAllClassScores() {
        Scoreboard scoreboard = getScoreboard();
        if (scoreboard == null) return;

        Objective objective = getOrCreateObjective(scoreboard);
        FactionDataLoader loader = FactionDataProvider.getOrCreateLoader();

        for (FactionDataLoader.FactionData faction : loader.getFactionArray()) {
            if (faction != null && faction.id != null) {
                for (FactionDataLoader.ClassKitData kit : loader.getClassesForFaction(faction.id)) {
                    String scoreHolder = "class_" + kit.id;
                    if (!scoreboard.hasPlayerScore(scoreHolder, objective)) {
                        scoreboard.getOrCreatePlayerScore(scoreHolder, objective).setScore(0);
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
        playerFactions.clear();
        playerTeams.clear();

        // 重置所有职业分数为0
        Scoreboard scoreboard = getScoreboard();
        if (scoreboard == null) return;

        Objective objective = scoreboard.getObjective(SCOREBOARD_OBJECTIVE);
        if (objective == null) return;

        // 获取所有分数持有者并设置为0
        FactionDataLoader loader = FactionDataProvider.getOrCreateLoader();
        for (FactionDataLoader.FactionData faction : loader.getFactionArray()) {
            if (faction != null && faction.id != null) {
                for (FactionDataLoader.ClassKitData kit : loader.getClassesForFaction(faction.id)) {
                    String scoreHolder = "class_" + kit.id;
                    if (scoreboard.hasPlayerScore(scoreHolder, objective)) {
                        scoreboard.getOrCreatePlayerScore(scoreHolder, objective).setScore(0);
                    }
                }
            }
        }
    }
}
