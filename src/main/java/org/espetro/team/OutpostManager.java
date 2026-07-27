package org.espetro.team;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.TicketType;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.ChunkPos;
import org.espetro.Espetro;
import org.espetro.bastion.BastionManager;
import org.espetro.config.GameConfig;
import org.espetro.mapconfig.BattlefieldContext;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 前哨基地管理器。
 *
 * 前哨基地仅供防守方在部署阶段（DEPLOYING）使用，
 * 可直接传送到前哨基地位置。
 * 当游戏正式开始（进入 BATTLE 阶段）时，前哨基地被停用，不再生效。
 * 它只复用兵站的部署点界面样式，不创建 BastionData、建筑、核心或补给，
 * 也不计入任何队伍的兵站数量上限。
 *
 * 配置文件：data/espetro/config/outposts.json
 * 格式：
 * {
 *   "outposts": [
 *     { "name": "前哨A", "x": 100, "y": 64, "z": 200, "yaw": 0 },
 *     ...
 *   ]
 * }
 */
public class OutpostManager {

    private static final Gson GSON = new Gson();

    private static OutpostManager INSTANCE;

    private final List<Outpost> outposts = new ArrayList<>();
    private final Map<UUID, Long> redeployCooldowns = new HashMap<>();
    private int redeployCooldownSeconds = 60;
    private boolean active = false;

    private OutpostManager() {
        INSTANCE = this;
    }

    public static OutpostManager getInstance() {
        if (INSTANCE == null) {
            INSTANCE = new OutpostManager();
        }
        return INSTANCE;
    }

    public static void init() {
        INSTANCE = new OutpostManager();
    }

    /**
     * 前哨基地数据
     */
    public static class Outpost {
        public final String name;
        public final double x, y, z;
        public final float yaw;

        public Outpost(String name, double x, double y, double z, float yaw) {
            this.name = name;
            this.x = x;
            this.y = y;
            this.z = z;
            this.yaw = yaw;
        }

        public String getPosString() {
            return (int) x + ", " + (int) y + ", " + (int) z;
        }
    }

    /**
     * 从数据包加载前哨基地配置
     */
    /** @deprecated 不从 datapack 加载；战场激活时 applyExternalJson。 */
    public void loadConfig(MinecraftServer server) {
        // 有意留空
    }

    private void parseAndApply(String json) {
        JsonObject root = GSON.fromJson(json, JsonObject.class);
        if (root == null) return;

        if (root.has("redeploy_cooldown_seconds")) {
            redeployCooldownSeconds = Math.max(0, root.get("redeploy_cooldown_seconds").getAsInt());
        }
        if (!root.has("outposts")) return;

        JsonArray arr = root.getAsJsonArray("outposts");
        for (JsonElement elem : arr) {
            if (!elem.isJsonObject()) continue;
            JsonObject obj = elem.getAsJsonObject();
            String name = obj.has("name") ? obj.get("name").getAsString() : "前哨";
            double x = obj.has("x") ? obj.get("x").getAsDouble() : 0;
            double y = obj.has("y") ? obj.get("y").getAsDouble() : 64;
            double z = obj.has("z") ? obj.get("z").getAsDouble() : 0;
            float yaw = obj.has("yaw") ? (float) obj.get("yaw").getAsDouble() : 0f;
            outposts.add(new Outpost(name, x, y, z, yaw));
        }
    }

    /** Apply the frozen outposts.json belonging to the active map. */
    public void applyExternalJson(String json) {
        outposts.clear();
        redeployCooldownSeconds = 60;
        parseAndApply(json);
        active = false;
        redeployCooldowns.clear();
    }

    /**
     * 获取所有前哨基地
     */
    public List<Outpost> getOutposts() {
        return new ArrayList<>(outposts);
    }

    /**
     * 前哨基地是否可用（部署阶段且已激活）
     */
    public boolean isAvailable() {
        return active
            && GameStateManager.getInstance().getCurrentPhase() == GamePhase.DEPLOYING
            && !outposts.isEmpty();
    }

    /**
     * 激活前哨基地（部署阶段开始时调用）
     */
    public void activate() {
        active = true;
        Espetro.LOGGER.info("前哨基地已激活: {} 个", outposts.size());
    }

    /**
     * 停用前哨基地（战斗开始时调用）
     */
    public void deactivate() {
        if (active) {
            active = false;
            Espetro.LOGGER.info("前哨基地已停用（战斗开始）");
        }
    }

    /**
     * 尝试传送到指定前哨基地
     * @param player 玩家
     * @param outpostIndex 前哨基地索引
     * @return null 表示成功，String 表示失败原因
     */
    public String tryDeploy(ServerPlayer player, int outpostIndex) {
        if (!isAvailable()) {
            return "§c前哨基地已失效！";
        }

        // 仅防守方可使用
        String team = Espetro.getPlayerTeam(player);
        if (!"DEFEND".equals(team)) {
            return "§c只有防守方可以使用前哨基地！";
        }

        if (!BastionManager.getInstance().isWaitingForBastion(player.getUUID())) {
            return "§c只有阵亡或使用“重新部署”后才能选择前哨基地！";
        }

        if (outpostIndex < 0 || outpostIndex >= outposts.size()) {
            return "§c前哨基地不存在！";
        }

        Outpost outpost = outposts.get(outpostIndex);
        ServerLevel battlefield = BattlefieldContext.requireBattlefield(player.server);
        // 改选前哨：取消未完成的 Rally 波次队列。
        TeamPackManager.getInstance().cancelPendingRespawn(player.getUUID());
        BastionManager.getInstance().clearWaiting(player.getUUID());
        player.teleportTo(battlefield, outpost.x, outpost.y, outpost.z, outpost.yaw, 0f);
        player.setGameMode(GameType.SURVIVAL);
        player.removeAllEffects();
        player.addEffect(new MobEffectInstance(
            MobEffects.DAMAGE_RESISTANCE,
            GameConfig.getRespawnInvincibilityTicks(),
            127,
            false, false, false
        ));
        GameStateManager.getInstance().applyBattlefieldMiningRestriction(player);
        player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
            "§a已传送到前哨基地: §f" + outpost.name));
        return null;
    }

    /**
     * 在布防期发起一次不扣兵力的重新部署。
     * 死亡事件会把玩家转入统一部署点选择状态。
     */
    public String tryStartRedeploy(ServerPlayer player) {
        if (!isAvailable()) {
            return "§c只能在布防阶段重新部署！";
        }
        if (!"DEFEND".equals(Espetro.getPlayerTeam(player))) {
            return "§c只有防守方可以使用前哨重新部署！";
        }
        if (BastionManager.getInstance().isWaitingForBastion(player.getUUID())) {
            return "§c你已在等待选择部署点！";
        }

        int remaining = getRedeployCooldownRemaining(player.getUUID());
        if (remaining > 0) {
            return "§c重新部署冷却中，请等待 " + remaining + " 秒！";
        }

        redeployCooldowns.put(player.getUUID(), System.currentTimeMillis());
        prepareDeployTargets(BattlefieldContext.requireBattlefield(player.server));
        player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
            "§e正在重新部署，请在复活后选择部署点。"));
        player.kill();
        return null;
    }

    /**
     * 在死亡界面和复活选择期间预加载前哨区块，
     * 避免点击部署点时才同步加载远处区块。票据会由原版自动过期。
     */
    public void prepareDeployTargets(ServerLevel level) {
        if (!isAvailable() || level == null) return;

        for (Outpost outpost : outposts) {
            BlockPos target = BlockPos.containing(outpost.x, outpost.y, outpost.z);
            level.getChunkSource().addRegionTicket(
                TicketType.PORTAL, new ChunkPos(target), 3, target);
        }
    }

    public int getRedeployCooldownRemaining(UUID playerId) {
        Long lastUse = redeployCooldowns.get(playerId);
        if (lastUse == null || redeployCooldownSeconds <= 0) {
            return 0;
        }
        long remainingMillis = redeployCooldownSeconds * 1000L - (System.currentTimeMillis() - lastUse);
        return remainingMillis <= 0 ? 0 : (int) ((remainingMillis + 999L) / 1000L);
    }

    public int getRedeployCooldownSeconds() {
        return redeployCooldownSeconds;
    }

    /**
     * 检查玩家是否位于任一当前可用的前哨基地附近。
     * 部署到前哨后需要通过该检查允许玩家在部署面板选择职业。
     */
    public boolean isPlayerNearAvailableOutpost(ServerPlayer player, double radius) {
        if (!isAvailable() || !"DEFEND".equals(Espetro.getPlayerTeam(player))) {
            return false;
        }

        BlockPos playerPos = player.blockPosition();
        for (Outpost outpost : outposts) {
            BlockPos outpostPos = BlockPos.containing(outpost.x, outpost.y, outpost.z);
            if (playerPos.closerThan(outpostPos, radius)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 重置（游戏重置时调用）
     */
    public void reset() {
        active = false;
        redeployCooldowns.clear();
    }
}
