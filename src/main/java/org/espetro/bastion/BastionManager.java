package org.espetro.bastion;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraftforge.common.MinecraftForge;
import org.espetro.Espetro;
import org.espetro.api.event.BastionLifecycleEvent;
import org.espetro.logistics.LogisticsConfig;

import javax.annotation.Nullable;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * 兵站管理器
 * 管理所有兵站的创建、存储和查询
 */
public class BastionManager {

    /** 每个队伍最多同时生效的兵站数量 */
    public static final int MAX_BASTIONS = 4;
    /**
     * 仅复活传送时临时拉目标区块（与原版传送/门票据一致，会自动过期）。
     * 禁止用自定义 ticket 长期强加载兵站区块。
     */
    private static final int TELEPORT_PORTAL_TICKET_RADIUS = 1;
    /** 兼容旧调用，实际含义为每个队伍的上限。 */
    @Deprecated
    public static final int MAX_BASTIONS_PER_TEAM = MAX_BASTIONS;
    private static BastionManager INSTANCE;

    // 所有兵站列表
    private final Map<UUID, BastionData> bastions = new HashMap<>();

    // 兵站记录表：部署传送只依赖记录坐标，不依赖区块当前是否加载。
    private final Map<UUID, BlockPos> bastionRecordPositions = new HashMap<>();

    // 核心盔甲架 UUID -> 兵站 UUID，用于盔甲架按原版逻辑死亡后快速释放兵站记录。
    private final Map<UUID, UUID> bastionIdsByArmorStand = new HashMap<>();

    // 正在等待复活选择的玩家
    private final Map<UUID, UUID> waitingPlayers = new HashMap<>(); // playerUUID -> bastionChoiceRequestId
    /** Deduplicated asynchronous FULL-chunk loads used only while deploying. */
    private final Map<HabChunkKey, CompletableFuture<Boolean>> pendingHabChunkLoads = new HashMap<>();
    private final Set<UUID> pendingHabTeleports = new HashSet<>();

    // 由死亡进入的等待复活状态。部署期残留等待会在开战时自动结算，死亡等待不会。
    private final Set<UUID> deathWaitingPlayers = new HashSet<>();

    // 玩家的原部署点位置
    private final Map<UUID, DeployPoint> playerDeployPoints = new HashMap<>(); // playerUUID -> DeployPoint

    // 玩家建造冷却记录
    private final Map<UUID, Long> bastionCooldowns = new HashMap<>(); // playerUUID -> lastUseTimestamp (毫秒)

    // 玩家位置锁定（等待复活选择时）
    private final Map<UUID, net.minecraft.world.phys.Vec3> playerLockPositions = new HashMap<>();

    // 弹药补给追踪（仅冷却，无次数限制）
    private final Map<UUID, Long> resupplyCooldowns = new HashMap<>(); // 已取消冷却，仅保留兼容

    /** 弹药补给冷却时间（毫秒）；已取消，保留常量仅为兼容。 */
    public static final long RESUPPLY_COOLDOWN_MS = 5 * 60 * 1000;

    // 从 JSON 配置读取的值
    private int cooldownSeconds = 800;
    private int requiredPlanks = 640;
    private int armorStandHealth = 5;
    private int destroyTroopPenalty = 20;

    private BastionManager() {
        INSTANCE = this;
        loadConfig();
    }

    /**
     * 从 JSON 文件加载配置
     */
    /** @deprecated 不从 datapack 加载；战场激活时 applyExternalJson。 */
    private void loadConfig() {
        // 有意留空：默认字段已在声明处初始化
    }

    public void reloadConfig() {
        // no-op: EsConfig 经 applyExternalJson 应用
    }

    /** Apply the frozen bastion.json belonging to the active map. */
    public void applyExternalJson(String rawJson) {
        cooldownSeconds = 800;
        requiredPlanks = 640;
        armorStandHealth = 5;
        destroyTroopPenalty = 20;
        JsonObject json = new Gson().fromJson(rawJson, JsonObject.class);
        if (json == null || !json.has("bastion")) return;
        JsonObject bastion = json.getAsJsonObject("bastion");
        if (bastion.has("cooldown_seconds")) cooldownSeconds = Math.max(0, bastion.get("cooldown_seconds").getAsInt());
        if (bastion.has("required_planks")) requiredPlanks = Math.max(0, bastion.get("required_planks").getAsInt());
        if (bastion.has("armor_stand_health")) armorStandHealth = Math.max(1, bastion.get("armor_stand_health").getAsInt());
        if (bastion.has("destroy_troop_penalty")) destroyTroopPenalty = Math.max(0, bastion.get("destroy_troop_penalty").getAsInt());
    }

    /**
     * 获取 bastion.json 中的冷却时间（秒）。放置请用 {@link #getEffectiveRadioCooldownSeconds()}。
     */
    public int getCooldownSeconds() {
        return cooldownSeconds;
    }

    /**
     * 获取 bastion.json 中的所需建材点数。放置请用 {@link #getEffectiveRadioRequiredConstruction()}。
     */
    public int getRequiredPlanks() {
        return requiredPlanks;
    }

    /**
     * Radio 放置冷却：logistics.radio.cooldown_seconds ≥ 0 时覆盖，否则 bastion.json。
     */
    public int getEffectiveRadioCooldownSeconds() {
        int configured = org.espetro.logistics.LogisticsConfig.get().getRadio().cooldownSeconds;
        return configured >= 0 ? configured : cooldownSeconds;
    }

    /**
     * Radio 放置始终免费（不消耗建材）。保留方法供 tooltip/旧调用兼容，恒为 0。
     */
    public int getEffectiveRadioRequiredConstruction() {
        return 0;
    }

    /**
     * 建造 HAB 所需建材点数（从覆盖 Radio 库存扣除）。
     */
    public int getHabConstructionCost() {
        return Math.max(0, LogisticsConfig.get().habConstructionCost);
    }

    /**
     * 获取盔甲架血量
     */
    public int getArmorStandHealth() {
        return armorStandHealth;
    }

    /**
     * 获取兵站被摧毁时扣除的兵力值
     */
    public int getDestroyTroopPenalty() {
        return destroyTroopPenalty;
    }

    public static BastionManager getInstance() {
        if (INSTANCE == null) {
            INSTANCE = new BastionManager();
        }
        return INSTANCE;
    }

    /**
     * 兼容旧调用：创建 Radio。
     */
    public BastionData createBastion(ServerLevel level, BlockPos pos, String team, String name) {
        return createStructure(level, pos, team, name, StructureKind.RADIO);
    }

    public BastionData createRadio(ServerLevel level, BlockPos pos, String team, String name) {
        return createStructure(level, pos, team, name, StructureKind.RADIO);
    }

    public BastionData createHab(ServerLevel level, BlockPos pos, String team, String name) {
        return createStructure(level, pos, team, name, StructureKind.HAB);
    }

    /**
     * 创建 Radio 或 HAB。
     * Radio 受每队上限约束；HAB 不占 Radio 上限。
     */
    public BastionData createStructure(ServerLevel level, BlockPos pos, String team, String name,
                                       StructureKind kind) {
        StructureKind structureKind = kind == null ? StructureKind.RADIO : kind;
        if (structureKind == StructureKind.RADIO && !hasBastionCapacity(team)) {
            Espetro.LOGGER.warn("队伍 {} 的生效 Radio 数量已达到上限 {}，拒绝创建: {} ({})",
                team, getBastionLimitPerTeam(), name, pos);
            return null;
        }
        if (structureKind == StructureKind.RADIO && wouldRadioCoverageOverlap(level, pos)) {
            Espetro.LOGGER.warn("Radio 作用范围与现有 Radio 重叠，拒绝创建: {} ({})", name, pos);
            return null;
        }

        BastionData bastion = new BastionData(team, name, pos, level, structureKind);
        bastion.setArmorStandPosition(pos.above());
        if (structureKind == StructureKind.HAB) {
            bastion.setHabBuilt(true);
            // 0 = 建成即可部署；>0 时才写入未来可用时间。压制/覆盖仍由 isHabOperational 判定。
            long activationMs = Math.max(0L, LogisticsConfig.get().habActivationSeconds * 1000L);
            bastion.setHabAvailableAt(activationMs == 0L ? 0L : System.currentTimeMillis() + activationMs);
        }

        if (!registerBastionRecord(bastion)) {
            Espetro.LOGGER.warn("结构记录失败，拒绝创建: {} ({})", name, pos);
            return null;
        }
        bastion.setCoreHealth(armorStandHealth);

        if (structureKind == StructureKind.RADIO) {
            // Radio 核心就是玩家放置的方块本身，不生成任何实体
            bastion.setArmorStandPosition(pos);
            radioBlockPositions.put(pos.immutable(), bastion.getBastionId());
        } else {
            ArmorStand armorStand = createCoreArmorStand(level, pos.above(), team, name);
            if (armorStand == null) {
                releaseBastionRecord(bastion);
                Espetro.LOGGER.error("无法创建盔甲架实体");
                return null;
            }
            level.addFreshEntity(armorStand);
            bastion.setArmorStandId(armorStand.getUUID());
            registerCoreEntity(bastion);
            bastion.setArmorStandPosition(armorStand.blockPosition());
        }

        updateBastionArmorStandPosition(bastion, bastion.getArmorStandPosition());
        bastion.setActive(true);

        bastions.put(bastion.getBastionId(), bastion);
        recomputeHabCoverage();

        Espetro.LOGGER.info("创建{}: {} (队伍: {}, 编号: {}, 核心位置: {})",
            structureKind, name, team, bastion.getBastionNumber(), bastion.getArmorStandPosition());
        MinecraftForge.EVENT_BUS.post(new BastionLifecycleEvent.Built(bastion));

        return bastion;
    }

    // Radio 方块位置 -> bastionId
    private final Map<BlockPos, UUID> radioBlockPositions = new HashMap<>();

    @Nullable
    public BastionData findRadioByBlockPos(BlockPos pos) {
        UUID id = radioBlockPositions.get(pos);
        if (id != null) {
            BastionData data = bastions.get(id);
            if (data != null && data.isRadio()) {
                return data;
            }
            radioBlockPositions.remove(pos);
        }
        // 兜底：按记录坐标线性查（读档后 map 为空时重建）
        for (BastionData bastion : bastions.values()) {
            if (bastion.isRadio() && bastion.isActive() && bastion.getPosition().equals(pos)) {
                radioBlockPositions.put(pos.immutable(), bastion.getBastionId());
                return bastion;
            }
        }
        return null;
    }

    public void releaseRadioBlockRecord(BastionData bastion) {
        if (bastion != null) {
            radioBlockPositions.remove(bastion.getPosition());
        }
    }

    /**
     * 己方指挥/小队长收起 Radio：静默注销记录（不扣兵力、不播报摧毁）并清放置冷却。
     */
    public void retrieveRadio(BastionData bastion, UUID retrieverId) {
        if (bastion == null || !bastion.isRadio()) {
            return;
        }
        releaseRadioBlockRecord(bastion);
        releaseBastionRecord(bastion);
        bastion.setActive(false);
        bastions.remove(bastion.getBastionId());
        if (retrieverId != null) {
            bastionCooldowns.remove(retrieverId);
        }
        Espetro.LOGGER.info("Radio {} 被 {} 收起", bastion.getName(), retrieverId);
        recomputeHabCoverage();
    }

    /**
     * 事件驱动重算所有 HAB 的覆盖缓存（Radio/HAB 增减时调用一次）。
     * 覆盖状态变化时向所属队伍播报。
     */
    public void recomputeHabCoverage() {
        double buildRadius = LogisticsConfig.get().radioBuildRadius;
        double radiusSq = buildRadius * buildRadius;

        List<BastionData> radios = new ArrayList<>();
        for (BastionData bastion : bastions.values()) {
            if (bastion.isActive() && bastion.isRadio()) {
                radios.add(bastion);
            }
        }

        for (BastionData hab : bastions.values()) {
            if (!hab.isHab() || !hab.isActive()) {
                continue;
            }
            boolean covered = false;
            for (BastionData radio : radios) {
                if (radio.getLevel() == hab.getLevel()
                    && Objects.equals(radio.getTeam(), hab.getTeam())
                    && radio.getPosition().distSqr(hab.getPosition()) <= radiusSq) {
                    covered = true;
                    break;
                }
            }
            if (covered != hab.isHabCoveredCache()) {
                hab.setHabCoveredCache(covered);
                Espetro.broadcastToTeam(hab.getTeam(), covered
                    ? "§a[兵站] §e" + hab.getName() + " §a已恢复 Radio 覆盖，可以复活。"
                    : "§c[兵站] §e" + hab.getName() + " §c失去 Radio 覆盖，无法复活！");
            }
        }
    }

    /**
     * 获取玩家所属队伍的 HAB 列表（不含纯 Radio），供部署 UI 展示。
     * <p>
     * 含启用倒计时中 / 无覆盖 / 被压制的兵站，由 {@link #getFobStatus} 标注原因；
     * 真正可否传送由 {@link #isHabOperational} / 选择入口再校验。
     * （默认建成即可用；若配置了 activation 秒数，倒计时期间仍会列出并标状态。）
     */
    public List<BastionData> getTeamBastions(String team) {
        List<BastionData> result = new ArrayList<>(MAX_BASTIONS);
        if (team == null) {
            return result;
        }
        for (BastionData bastion : bastions.values()) {
            if (bastion == null || !bastion.isActive() || !team.equals(bastion.getTeam())) {
                continue;
            }
            // 纯 Radio 不可作为复活点；旧合并 FOB 或 HAB 才进列表
            if (bastion.isRadio() && !bastion.isLegacyCombined()) {
                continue;
            }
            if (!bastion.isHabBuilt() && !bastion.isLegacyCombined()) {
                continue;
            }
            if (!bastionRecordPositions.containsKey(bastion.getBastionId())) {
                registerBastionRecord(bastion);
            }
            // UI 列表只读记录坐标，不 checkArmorStand（避免选职/刷面板时 N×getEntity）
            if (getRecordedArmorStandPosition(bastion) == null) {
                continue;
            }
            result.add(bastion);
        }
        result.sort(Comparator.comparing(BastionData::getName));
        return result;
    }

    /**
     * 队伍中当前真正可部署（可传送复活）的 HAB。
     */
    public List<BastionData> getTeamOperationalBastions(String team) {
        List<BastionData> result = new ArrayList<>();
        for (BastionData bastion : getTeamBastions(team)) {
            if (isHabOperational(bastion)) {
                result.add(bastion);
            }
        }
        return result;
    }

    /**
     * 获取所有结构列表（Radio + HAB）
     */
    public List<BastionData> getAllBastions() {
        return new ArrayList<>(bastions.values());
    }

    /**
     * 查找排斥半径内最近的 <strong>Radio</strong>（HAB 不参与 Radio 间距）。
     */
    @Nullable
    public BastionData findNearestBastion(ServerLevel level, BlockPos pos, @Nullable String team, double radius) {
        return findNearestRadio(level, pos, team, radius);
    }

    @Nullable
    public BastionData findNearestRadio(ServerLevel level, BlockPos pos, @Nullable String team, double radius) {
        BastionData nearest = null;
        double bestDistance = radius * radius;
        for (BastionData bastion : bastions.values()) {
            if (!bastion.isActive() || !bastion.isRadio() || bastion.getLevel() != level
                || (team != null && !team.equals(bastion.getTeam()))) {
                continue;
            }
            double distance = bastion.getPosition().distSqr(pos);
            if (distance <= bestDistance) {
                nearest = bastion;
                bestDistance = distance;
            }
        }
        return nearest;
    }

    /**
     * Checks all teams in the dimension. This is also called by the low-level
     * creation path so commands or future integrations cannot bypass placement validation.
     */
    public boolean wouldRadioCoverageOverlap(ServerLevel level, BlockPos pos) {
        if (level == null || pos == null) return true;
        LogisticsConfig.LogisticsSettings settings = LogisticsConfig.get();
        double separation = RadioCoveragePolicy.minimumCenterDistance(
            settings.radioBuildRadius, settings.radioExclusionRadius);
        for (BastionData bastion : bastions.values()) {
            if (!bastion.isActive() || !bastion.isRadio() || bastion.getLevel() != level) {
                continue;
            }
            if (RadioCoveragePolicy.overlaps(
                bastion.getPosition().distSqr(pos), separation)) {
                return true;
            }
        }
        return false;
    }

    public double getMinimumRadioCenterDistance() {
        LogisticsConfig.LogisticsSettings settings = LogisticsConfig.get();
        return RadioCoveragePolicy.minimumCenterDistance(
            settings.radioBuildRadius, settings.radioExclusionRadius);
    }

    /**
     * 覆盖 pos 的己方活跃 Radio（同维度），按建材升序（最少优先）。
     */
    public List<BastionData> findCoveringRadios(ServerLevel level, BlockPos pos, String team) {
        double buildRadius = LogisticsConfig.get().radioBuildRadius;
        double radiusSq = buildRadius * buildRadius;
        List<BastionData> covering = new ArrayList<>();
        for (BastionData bastion : bastions.values()) {
            if (!bastion.isActive() || !bastion.isRadio()
                || bastion.getLevel() != level
                || !Objects.equals(team, bastion.getTeam())) {
                continue;
            }
            if (bastion.getPosition().distSqr(pos) <= radiusSq) {
                covering.add(bastion);
            }
        }
        covering.sort(Comparator
            .comparingInt(BastionData::getConstructionSupplies)
            .thenComparing(b -> b.getBastionId().toString()));
        return covering;
    }

    public boolean isInsideFriendlyRadioBuildRadius(ServerLevel level, BlockPos pos, String team) {
        return !findCoveringRadios(level, pos, team).isEmpty();
    }

    /**
     * 从覆盖 pos 的己方 Radio 原子扣除建材：优先库存最少者。总和不足则不扣并返回 false。
     */
    public boolean tryDebitConstructionFromCoveringRadios(ServerLevel level, BlockPos pos,
                                                          String team, int amount) {
        if (amount <= 0) {
            return true;
        }
        List<BastionData> covering = findCoveringRadios(level, pos, team);
        if (covering.isEmpty()) {
            return false;
        }
        int total = 0;
        for (BastionData radio : covering) {
            total += radio.getConstructionSupplies();
            if (total >= amount) {
                break;
            }
        }
        if (total < amount) {
            return false;
        }
        int remaining = amount;
        for (BastionData radio : covering) {
            if (remaining <= 0) {
                break;
            }
            int take = Math.min(remaining, radio.getConstructionSupplies());
            if (take > 0 && radio.consumeConstructionSupplies(take)) {
                remaining -= take;
            }
        }
        return remaining <= 0;
    }

    public int sumConstructionInCoveringRadios(ServerLevel level, BlockPos pos, String team) {
        int total = 0;
        for (BastionData radio : findCoveringRadios(level, pos, team)) {
            total += radio.getConstructionSupplies();
        }
        return total;
    }

    /**
     * 已废弃：弹药箱改由 Alt「建造工事」手动放置，存入建材不再自动建成弹药箱。
     * 保留空实现以免旧调用方崩溃。
     */
    public void advanceFobConstruction(BastionData bastion) {
        // no-op
    }

    public boolean tryConsumeFobAmmunition(BastionData bastion, int amount) {
        // 弹药箱手建后由交互入口校验；此处只扣 Radio 弹药库存
        return bastion != null && bastion.isActive() && bastion.isRadio()
            && bastion.consumeAmmunitionSupplies(Math.max(0, amount));
    }

    public String getFobStatus(BastionData bastion) {
        if (bastion == null) {
            return "无效";
        }
        if (bastion.isRadio() && !bastion.isLegacyCombined()) {
            return bastion.isAmmoCrateBuilt()
                ? "Radio 弹药库存可用（弹药箱已建）"
                : "Radio 库存 " + bastion.getConstructionSupplies() + "/"
                    + bastion.getAmmunitionSupplies() + "（弹药箱需手动建造）";
        }
        if (!bastion.isActive()) {
            return "HAB 已失效";
        }
        if (!bastion.isHabBuilt()) {
            return "HAB 待建造";
        }
        long now = System.currentTimeMillis();
        if (bastion.getHabAvailableAt() > now) {
            return "HAB 启用中 " + ((bastion.getHabAvailableAt() - now + 999L) / 1000L) + "s";
        }
        if (bastion.getHabDisabledUntil() > now) {
            return "HAB 被压制 " + ((bastion.getHabDisabledUntil() - now + 999L) / 1000L) + "s";
        }
        if (bastion.isHab() && !isCoveredByFriendlyRadio(bastion)) {
            return "HAB 无 Radio 覆盖";
        }
        // 与 isHabOperational 对齐：记录坐标缺失时不可部署
        if (getRecordedArmorStandPosition(bastion) == null) {
            return "HAB 坐标缺失";
        }
        return "HAB 可部署";
    }

    /** UI / 客户端：状态文案是否表示当前可点选部署。 */
    public static boolean isDeployReadyStatus(String status) {
        return status != null && status.equals("HAB 可部署");
    }

    /** HAB 或旧版合并 FOB 是否仍被己方 Radio 建造半径覆盖。 */
    public boolean isCoveredByFriendlyRadio(BastionData hab) {
        if (hab == null || !hab.isActive()) {
            return false;
        }
        if (hab.isRadio() && hab.isLegacyCombined()) {
            return true;
        }
        if (!hab.isHab()) {
            return false;
        }
        // 读事件驱动缓存（recomputeHabCoverage 在结构增减时更新）
        return hab.isHabCoveredCache();
    }

    /**
     * 通过兵站核心盔甲架 UUID 查询兵站。
     */
    @Nullable
    public BastionData findBastionByArmorStand(UUID armorStandId) {
        UUID bastionId = bastionIdsByArmorStand.get(armorStandId);
        if (bastionId != null) {
            BastionData bastion = bastions.get(bastionId);
            if (bastion != null) {
                return bastion;
            }
            bastionIdsByArmorStand.remove(armorStandId);
        }

        for (BastionData bastion : bastions.values()) {
            if (bastion.getArmorStandId() != null && bastion.getArmorStandId().equals(armorStandId)) {
                registerCoreEntity(bastion);
                return bastion;
            }
        }
        return null;
    }

    /**
     * 获取指定ID的兵站
     */
    @Nullable
    public BastionData getBastion(UUID bastionId) {
        return bastions.get(bastionId);
    }

    /**
     * 设置兵站启用状态。兵站失效时同步清空记录坐标。
     */
    public void setBastionActive(BastionData bastion, boolean active) {
        if (!active) {
            releaseBastionRecord(bastion);
            bastion.setActive(false);
            return;
        }

        if (!bastion.isActive() && bastion.isRadio() && !hasBastionCapacity(bastion.getTeam())) {
            bastion.setActive(false);
            Espetro.LOGGER.warn("Radio {} 无法重新激活：队伍 {} 的生效 Radio 数量已达上限",
                bastion.getName(), bastion.getTeam());
            return;
        }

        if (!registerBastionRecord(bastion)) {
            bastion.setActive(false);
            Espetro.LOGGER.warn("兵站 {} 无法重新激活：记录坐标失败", bastion.getName());
            return;
        }
        bastion.setActive(active);
    }

    /**
     * 核心盔甲架按原版实体逻辑死亡后调用，释放该兵站坐标和编号。
     */
    public void onCoreArmorStandDestroyed(BastionData bastion, @Nullable Entity attacker) {
        destroyBastion(bastion, attacker, false);
    }

    /**
     * 统一摧毁兵站，负责释放编号、移除核心实体、广播和扣兵力。
     */
    public void destroyBastion(BastionData bastion, @Nullable Entity attacker) {
        destroyBastion(bastion, attacker, true, null);
    }

    /**
     * 摧毁并显式指定是否扣兵力（己方拆 Radio 时传 false）。
     * 独立命名避免与私有 (…, boolean removeLoadedCoreEntity) 重载因装箱歧义误分派。
     */
    public void destroyBastionWithManpower(BastionData bastion, @Nullable Entity attacker,
                                           boolean deductManpower) {
        destroyBastion(bastion, attacker, true, deductManpower);
    }

    private void destroyBastion(BastionData bastion, @Nullable Entity attacker, boolean removeLoadedCoreEntity) {
        destroyBastion(bastion, attacker, removeLoadedCoreEntity, null, false);
    }

    private void destroyBastion(BastionData bastion, @Nullable Entity attacker,
                                boolean removeLoadedCoreEntity, @Nullable Boolean manpowerOverride) {
        destroyBastion(bastion, attacker, removeLoadedCoreEntity, manpowerOverride, false);
    }

    private void destroyBastion(BastionData bastion, @Nullable Entity attacker,
                                boolean removeLoadedCoreEntity, @Nullable Boolean manpowerOverride,
                                boolean silent) {
        if (bastion == null || !bastion.isActive()) {
            return;
        }

        String bastionName = bastion.getName();
        String bastionTeam = bastion.getTeam();
        boolean radio = bastion.isRadio();
        // 旧合并 FOB 仍按 Radio 扣兵力；纯 HAB 不扣；己方拆 Radio 可 override 为不扣。
        boolean deductManpower = !silent && (manpowerOverride != null ? manpowerOverride : radio);
        int penalty = deductManpower ? getDestroyTroopPenalty() : 0;

        if (removeLoadedCoreEntity) {
            removeCoreEntityIfLoaded(bastion, true);
            if (radio) {
                removeRadioBlockIfLoaded(bastion);
            }
        }
        if (radio) {
            releaseRadioBlockRecord(bastion);
        }

        setBastionActive(bastion, false);
        // Radio 增减会影响 HAB 覆盖；silent 战局清理由 reset 整体清空，无需重算。
        if (radio && !silent) {
            recomputeHabCoverage();
        }

        if (!silent) {
            String attackerName = attacker == null ? "unknown" : attacker.getName().getString();
            if (deductManpower) {
                org.espetro.team.TroopCountManager troopManager = org.espetro.team.TroopCountManager.getInstance();
                if ("ATTACK".equals(bastionTeam)) {
                    troopManager.modifyAttackTroops(-penalty);
                } else {
                    troopManager.modifyDefendTroops(-penalty);
                }
                Espetro.LOGGER.info("Radio {} 被摧毁！攻击者={} 扣兵力={}", bastionName, attackerName, penalty);
                Espetro.broadcastToTeam(bastionTeam,
                    "§c[Radio] §e" + bastionName + " §c已被摧毁！- " + penalty + " 兵力");
            } else {
                Espetro.LOGGER.info("兵站 HAB {} 被摧毁！攻击者={}（不扣兵力）", bastionName, attackerName);
                Espetro.broadcastToTeam(bastionTeam,
                    "§c[兵站] §e" + bastionName + " §c已被摧毁！无法再从此点复活（不扣兵力）。");
            }

            ServerPlayer commander = findCommanderForTeam(bastionTeam);
            if (commander != null) {
                commander.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                    deductManpower
                        ? "§c你的 Radio §e" + bastionName + " §c已被摧毁！"
                        : "§c你的兵站 §e" + bastionName + " §c已被摧毁！"
                ));
            }
        }

        MinecraftForge.EVENT_BUS.post(
            new BastionLifecycleEvent.Destroyed(bastion, attacker, deductManpower, penalty));
    }

    /**
     * 战局结束 / 回城 / 卸载战场维度：摧毁全部 Radio 与 HAB。
     * 不扣兵力、不刷队内战报；仍发 {@link BastionLifecycleEvent.Destroyed}。
     * <p>
     * <b>不</b>为清理而强加载/临时拉区块：仅处理当前已加载区块内的核心实体与 Radio 方块；
     * 卸载区实体随维度删除或地图重建一并消失。禁止匹配结束时批量 {@code getChunk}。
     *
     * @return 摧毁的活跃结构数量
     */
    public int destroyAllBastionsForMatchEnd() {
        // 取消进行中的 HAB 读条，避免回合结束后 complete 再生成孤儿兵站
        HabChannelManager.getInstance().reset();

        List<BastionData> snapshot = new ArrayList<>(bastions.values());
        int destroyed = 0;
        for (BastionData bastion : snapshot) {
            if (bastion == null || !bastion.isActive()) {
                continue;
            }
            // removeLoadedCoreEntity=true 也只清「已加载」区块，不会 force-load
            destroyBastion(bastion, null, true, false, true);
            destroyed++;
        }
        // 清残余运行时表（等待复活、冷却等）
        reset(false);
        if (destroyed > 0) {
            Espetro.LOGGER.info("战局结束/回城：已摧毁全部兵站 {} 个（不扣兵力，未强加载区块）", destroyed);
        }
        return destroyed;
    }

    /** 仅在区块已加载时移除 Radio 方块；绝不主动加载区块。 */
    private void removeRadioBlockIfLoaded(BastionData bastion) {
        if (bastion == null || BastionItems.RADIO_BLOCK == null) {
            return;
        }
        ServerLevel level = bastion.getLevel();
        BlockPos pos = bastion.getPosition();
        if (level == null || pos == null || !isChunkLoaded(level, pos)) {
            return;
        }
        if (level.getBlockState(pos).is(BastionItems.RADIO_BLOCK)) {
            level.setBlock(pos, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 3);
        }
    }

    /**
     * 将玩家传送到 HAB 核心：优先原版「传送到实体」位姿。
     * <p>
     * 实体所在区块已加载：直接贴实体。未加载：用记录坐标传送，并挂<strong>一次性过期</strong>的
     * 原版 {@link TicketType#PORTAL}（与门/传送一致，非长期强加载、无自定义 ticket）。
     *
     * @return false 仅当缺少维度或记录坐标
     */
    public boolean teleportPlayerToHab(ServerPlayer player, BastionData bastion) {
        if (player == null || bastion == null) {
            return false;
        }
        ServerLevel level = bastion.getLevel();
        if (level == null) {
            return false;
        }

        UUID standId = bastion.getArmorStandId();
        if (standId != null) {
            Entity entity = level.getEntity(standId);
            if (entity instanceof ArmorStand stand && stand.isAlive()) {
                player.teleportTo(
                    level,
                    stand.getX(),
                    stand.getY(),
                    stand.getZ(),
                    stand.getYRot(),
                    stand.getXRot()
                );
                updateBastionArmorStandPosition(bastion, stand.blockPosition());
                return true;
            }
        }

        BlockPos targetPos = getRecordedArmorStandPosition(bastion);
        if (targetPos == null) {
            return false;
        }

        // Never synchronously load an unloaded HAB chunk from the server thread.
        if (!isChunkLoaded(level, targetPos)) {
            return false;
        }

        if (standId != null) {
            Entity entity = level.getEntity(standId);
            if (entity instanceof ArmorStand stand && stand.isAlive()) {
                player.teleportTo(
                    level,
                    stand.getX(),
                    stand.getY(),
                    stand.getZ(),
                    stand.getYRot(),
                    stand.getXRot()
                );
                updateBastionArmorStandPosition(bastion, stand.blockPosition());
                return true;
            }
        }

        player.teleportTo(
            level,
            targetPos.getX() + 0.5,
            targetPos.getY(),
            targetPos.getZ() + 0.5,
            player.getYRot(),
            player.getXRot()
        );
        return true;
    }

    /**
     * Prepare an unloaded HAB without blocking the server thread. Concurrent
     * requests targeting the same dimension/chunk share one chunk future.
     * Completion always runs on the Minecraft server executor.
     *
     * @return false when this player already has a deployment load pending
     */
    public boolean teleportPlayerToHabAsync(
        ServerPlayer player,
        BastionData bastion,
        Consumer<Boolean> completion
    ) {
        if (player == null || bastion == null || completion == null) {
            return false;
        }
        if (!pendingHabTeleports.add(player.getUUID())) {
            return false;
        }

        ServerLevel level = bastion.getLevel();
        BlockPos targetPos = getRecordedArmorStandPosition(bastion);
        if (level == null || targetPos == null) {
            pendingHabTeleports.remove(player.getUUID());
            completion.accept(false);
            return true;
        }
        if (isChunkLoaded(level, targetPos)) {
            level.getServer().execute(() -> {
                pendingHabTeleports.remove(player.getUUID());
                boolean valid = isWaitingForBastion(player.getUUID())
                    && bastion.isActive()
                    && isHabOperational(bastion, false);
                completion.accept(valid && teleportPlayerToHab(player, bastion));
            });
            return true;
        }

        ChunkPos chunk = new ChunkPos(targetPos);
        HabChunkKey key = new HabChunkKey(level, chunk);
        CompletableFuture<Boolean> future = pendingHabChunkLoads.computeIfAbsent(key, ignored -> {
            level.getChunkSource().addRegionTicket(
                TicketType.PORTAL,
                chunk,
                TELEPORT_PORTAL_TICKET_RADIUS,
                targetPos
            );
            CompletableFuture<Boolean> created = level.getChunkSource()
                .getChunkFuture(chunk.x, chunk.z, ChunkStatus.FULL, true)
                .thenApply(result -> result != null && result.left().isPresent())
                .completeOnTimeout(false, 10, TimeUnit.SECONDS);
            created.whenComplete((success, error) ->
                level.getServer().execute(() -> {
                    pendingHabChunkLoads.remove(key, created);
                    level.getChunkSource().removeRegionTicket(
                        TicketType.PORTAL,
                        chunk,
                        TELEPORT_PORTAL_TICKET_RADIUS,
                        targetPos
                    );
                }));
            return created;
        });

        future.whenComplete((loaded, error) -> level.getServer().execute(() -> {
            pendingHabTeleports.remove(player.getUUID());
            boolean valid = error == null
                && Boolean.TRUE.equals(loaded)
                && player.connection != null
                && isWaitingForBastion(player.getUUID())
                && bastion.isActive()
                && isHabOperational(bastion, false);
            completion.accept(valid && teleportPlayerToHab(player, bastion));
        }));
        return true;
    }

    public boolean isHabTeleportPending(UUID playerId) {
        return pendingHabTeleports.contains(playerId);
    }

    /**
     * 玩家选择兵站复活
     */
    public boolean selectBastion(ServerLevel level, UUID playerId, UUID bastionId) {
        BastionData bastion = bastions.get(bastionId);
        if (bastion == null || !bastion.isActive()) {
            return false;
        }

        // 检查玩家是否正在等待选择兵站
        if (!waitingPlayers.containsKey(playerId)) {
            return false;
        }
        clearWaiting(playerId);

        return true;
    }

    /**
     * 玩家死亡时，设置为等待兵站选择状态
     */
    public void onPlayerDeath(ServerLevel level, UUID playerId) {
        waitingPlayers.put(playerId, UUID.randomUUID());
        deathWaitingPlayers.add(playerId);
    }

    /**
     * 检查玩家是否在等待兵站选择
     */
    public boolean isWaitingForBastion(UUID playerId) {
        return waitingPlayers.containsKey(playerId);
    }

    public boolean isDeathWaiting(UUID playerId) {
        return deathWaitingPlayers.contains(playerId);
    }

    /**
     * 移除等待状态
     */
    public void clearWaiting(UUID playerId) {
        waitingPlayers.remove(playerId);
        deathWaitingPlayers.remove(playerId);
        pendingHabTeleports.remove(playerId);
        unlockPlayerPosition(playerId);
    }

    /**
     * 锁定玩家位置（等待复活选择时不可移动）
     */
    public void lockPlayerPosition(UUID playerId, net.minecraft.world.phys.Vec3 pos) {
        playerLockPositions.put(playerId, pos);
    }

    /**
     * 解锁玩家位置
     */
    public void unlockPlayerPosition(UUID playerId) {
        playerLockPositions.remove(playerId);
    }

    /**
     * 获取玩家锁定位置
     */
    @Nullable
    public net.minecraft.world.phys.Vec3 getPlayerLockPosition(UUID playerId) {
        return playerLockPositions.get(playerId);
    }

    /**
     * 轻量一致性清理：仅移除已标记失效的脏记录。
     * <p>
     * 真摧毁只走事件（{@link BastionEventHandler} 死亡/离开非 unload、方块破坏等）→
     * {@link #destroyBastion}。禁止「区块已加载却找不到实体」时当摧毁
     * （卸载竞态 + 无强加载时更易误杀）。
     * 若核心已加载，顺带刷新记录坐标（不强制加载区块）。
     */
    public void removeInvalidBastions() {
        Iterator<BastionData> iterator = bastions.values().iterator();
        while (iterator.hasNext()) {
            BastionData bastion = iterator.next();
            if (!bastion.isActive()) {
                iterator.remove();
                unregisterCoreEntity(bastion);
                continue;
            }
            // 可选：已加载时同步位置，不判摧毁
            if (bastion.getArmorStandId() == null || bastion.getLevel() == null) {
                continue;
            }
            if (!bastion.isChunkLoaded()) {
                continue;
            }
            Entity entity = bastion.getLevel().getEntity(bastion.getArmorStandId());
            if (entity instanceof ArmorStand stand && stand.isAlive()) {
                updateBastionArmorStandPosition(bastion, stand.blockPosition());
            }
        }
    }

    /**
     * 激活玩家的兵站选择状态
     */
    public void activatePlayerBastionSelection(UUID playerId) {
        waitingPlayers.put(playerId, UUID.randomUUID());
        deathWaitingPlayers.remove(playerId);
    }

    /**
     * 重置所有兵站。只清理已加载区块内的核心实体，避免重置或退出世界时同步加载远处区块。
     */
    public void reset() {
        reset(true);
    }

    /**
     * 仅清理运行时状态，不访问世界实体。用于服务器已停止后的兜底清理。
     */
    public void clearRuntimeState() {
        reset(false);
    }

    private void reset(boolean removeLoadedEntities) {
        BastionEventHandler.clearRadioDismantleAttempts();
        for (BastionData bastion : bastions.values()) {
            if (removeLoadedEntities) {
                removeCoreEntityIfLoaded(bastion, false);
            }
            releaseBastionRecord(bastion);
        }
        bastions.clear();
        bastionIdsByArmorStand.clear();
        radioBlockPositions.clear();
        waitingPlayers.clear();
        pendingHabChunkLoads.clear();
        pendingHabTeleports.clear();
        deathWaitingPlayers.clear();
        playerDeployPoints.clear();
        playerLockPositions.clear();
        bastionCooldowns.clear();
        resupplyCooldowns.clear();
        habProxyCache.clear();
        clearBastionRecords();
    }

    private record HabChunkKey(ServerLevel level, ChunkPos chunk) {
    }

    /**
     * 仅当核心所在区块<strong>已经</strong>加载时 discard HAB 盔甲架。
     * 绝不 force-load / 临时 PORTAL 拉块（{@code kill} 参数仅保留兼容旧调用，不再触发加载）。
     */
    private void removeCoreEntityIfLoaded(BastionData bastion, boolean kill) {
        if (bastion == null || bastion.getArmorStandId() == null) {
            return;
        }

        BlockPos entityPos = bastion.getArmorStandPosition();
        if (entityPos == null) {
            entityPos = bastion.getPosition() != null ? bastion.getPosition().above() : null;
        }
        if (entityPos == null) {
            return;
        }

        ServerLevel level = bastion.getLevel();
        if (level == null || !isChunkLoaded(level, entityPos)) {
            return;
        }

        Entity entity = level.getEntity(bastion.getArmorStandId());
        if (entity == null) {
            return;
        }

        entity.discard();
    }

    /**
     * 获取当前所有队伍生效 Radio 数量。
     */
    public int getActiveBastionCount() {
        int count = 0;
        for (BastionData bastion : bastions.values()) {
            if (bastion.isActive() && bastion.isRadio()) {
                count++;
            }
        }
        return count;
    }

    /**
     * 获取指定队伍当前生效 <strong>Radio</strong> 数量（HAB 不占上限）。
     */
    public int getActiveBastionCount(String team) {
        int count = 0;
        for (BastionData bastion : bastions.values()) {
            if (bastion.isActive() && bastion.isRadio()
                && Objects.equals(team, bastion.getTeam())) {
                count++;
            }
        }
        return count;
    }

    /**
     * 兼容旧调用：只检查所有队伍总数。
     */
    @Deprecated
    public boolean hasBastionCapacity() {
        return getActiveBastionCount() < MAX_BASTIONS;
    }

    /**
     * 指定队伍是否还能建造兵站。上限只统计当前场上同队伍生效兵站。
     */
    public boolean hasBastionCapacity(String team) {
        return getActiveBastionCount(team) < getBastionLimitPerTeam();
    }

    /** Radio 配置可覆盖默认的每队兵站上限。 */
    public int getBastionLimitPerTeam() {
        int configured = LogisticsConfig.get().getRadio().maxActivePerTeam;
        return configured >= 0 ? configured : MAX_BASTIONS;
    }

    /**
     * 获取兵站记录的盔甲架坐标。部署传送只依赖该坐标，不依赖强加载区块。
     */
    @Nullable
    public BlockPos getRecordedArmorStandPosition(BastionData bastion) {
        BlockPos recordPos = bastionRecordPositions.get(bastion.getBastionId());
        if (recordPos != null) {
            return recordPos;
        }
        BlockPos recorded = bastion.getArmorStandPosition();
        return recorded != null ? recorded : bastion.getPosition().above();
    }

    /**
     * 更新兵站盔甲架坐标，并同步到记录表。
     */
    public void updateBastionArmorStandPosition(BastionData bastion, BlockPos pos) {
        bastion.setArmorStandPosition(pos);
        bastionRecordPositions.put(bastion.getBastionId(), pos);
    }

    private boolean isBastionUsable(BastionData bastion) {
        if (!bastion.isActive()) {
            return false;
        }
        // 纯 Radio 不可作为复活点；旧合并 FOB 或 HAB 才可。
        if (bastion.isRadio() && !bastion.isLegacyCombined()) {
            return false;
        }
        if (!bastionRecordPositions.containsKey(bastion.getBastionId()) && !registerBastionRecord(bastion)) {
            return false;
        }
        if (bastion.checkArmorStand()) {
            BlockPos armorStandPos = bastion.getArmorStandPosition();
            if (armorStandPos != null) {
                updateBastionArmorStandPosition(bastion, armorStandPos);
            }
        }
        return getRecordedArmorStandPosition(bastion) != null && isHabOperational(bastion);
    }

    /**
     * 是否可部署。{@code applySuppression=true} 时才写入压制截止时间（真正选点时）；
     * 列表/status 查询传 false，避免误触副作用与缓存污染。
     */
    public boolean isHabOperational(BastionData bastion) {
        return isHabOperational(bastion, false);
    }

    public boolean isHabOperational(BastionData bastion, boolean applySuppression) {
        if (bastion == null || !bastion.isActive()) {
            return false;
        }
        if (bastion.isRadio() && !bastion.isLegacyCombined()) {
            return false;
        }
        if (!bastion.isHabBuilt()) {
            return false;
        }
        long now = System.currentTimeMillis();
        if (bastion.getHabAvailableAt() > now) {
            return false;
        }

        // HAB 必须仍在己方 Radio 建造半径内（旧合并 FOB 除外）。
        if (bastion.isHab() && !isCoveredByFriendlyRadio(bastion)) {
            return false;
        }

        // Radio 现为方块无血量：覆盖判定即足够；旧合并 FOB 仍按自身盔甲架血量压制。
        if (!bastion.isHab()) {
            float maximum = Math.max(1.0f, armorStandHealth);
            float healthPercent = bastion.getCoreHealth() * 100.0f / maximum;
            if (healthPercent <= LogisticsConfig.get().habDisableRadioHealth) {
                if (applySuppression) {
                    bastion.setHabDisabledUntil(now + LogisticsConfig.get().habReactivationSeconds * 1000L);
                }
                return false;
            }
        }

        if (isHabProxied(bastion)) {
            if (applySuppression) {
                bastion.setHabDisabledUntil(now + LogisticsConfig.get().habReactivationSeconds * 1000L);
            }
            return false;
        }
        return bastion.getHabDisabledUntil() <= now;
    }

    /** HAB 压制结果短缓存（gameTime），避免同 tick 多次部署校验扫玩家表。 */
    private final Map<UUID, long[]> habProxyCache = new HashMap<>(); // bastionId -> [gameTime, proxied 0/1]
    private static final int HAB_PROXY_CACHE_TICKS = 30;

    /**
     * 敌方贴近压制：单次遍历在线玩家，按半径环累计人数（O(玩家数)，不扫区块/实体表）。
     * 仅在选择部署等热路径按需调用，不做每 tick 轮询；30 tick 内同 HAB 复用结果。
     */
    private boolean isHabProxied(BastionData bastion) {
        ServerLevel level = bastion.getLevel();
        if (level == null) {
            return false;
        }
        long gameTime = level.getGameTime();
        UUID id = bastion.getBastionId();
        long[] cached = habProxyCache.get(id);
        if (cached != null && gameTime - cached[0] <= HAB_PROXY_CACHE_TICKS) {
            return cached[1] != 0L;
        }

        BlockPos center = bastion.getPosition();
        int[] radii = {20, 30, 40, 50, 60, 70, 80, 90};
        long maxR2 = (long) radii[radii.length - 1] * radii[radii.length - 1];
        int[] counts = new int[radii.length];
        String habTeam = bastion.getTeam();
        boolean proxied = false;
        for (ServerPlayer player : level.players()) {
            if (!player.isAlive() || player.isSpectator()
                || Objects.equals(habTeam, Espetro.getPlayerTeam(player))) {
                continue;
            }
            double distSq = player.blockPosition().distSqr(center);
            if (distSq > maxR2) {
                continue;
            }
            for (int index = 0; index < radii.length; index++) {
                long r = radii[index];
                if (distSq <= r * r) {
                    counts[index]++;
                    if (counts[index] >= index + 2) {
                        proxied = true;
                        break;
                    }
                }
            }
            if (proxied) {
                break;
            }
        }
        habProxyCache.put(id, new long[]{gameTime, proxied ? 1L : 0L});
        return proxied;
    }

    private boolean isChunkLoaded(ServerLevel level, BlockPos pos) {
        ChunkPos chunkPos = new ChunkPos(pos);
        return level.getChunkSource().hasChunk(chunkPos.x, chunkPos.z);
    }

    @Nullable
    private ArmorStand createCoreArmorStand(ServerLevel level, BlockPos corePos, String team, String name) {
        ArmorStand armorStand = net.minecraft.world.entity.EntityType.ARMOR_STAND.create(level);
        if (armorStand == null) {
            return null;
        }

        armorStand.setPos(corePos.getX() + 0.5, corePos.getY(), corePos.getZ() + 0.5);
        armorStand.setCustomName(net.minecraft.network.chat.Component.literal(name));
        armorStand.setCustomNameVisible(false);
        syncCoreArmorStand(armorStand);
        armorStand.setHealth(armorStandHealth);

        ItemStack helmet = new ItemStack(Items.LEATHER_HELMET);
        CompoundTag displayTag = new CompoundTag();
        displayTag.putInt("color", "ATTACK".equals(team) ? 0xAA0000 : 0x0000AA);
        CompoundTag tag = new CompoundTag();
        tag.put("display", displayTag);
        helmet.setTag(tag);
        armorStand.setItemSlot(EquipmentSlot.HEAD, helmet);

        return armorStand;
    }

    void syncCoreArmorStand(ArmorStand armorStand) {
        AttributeInstance maxHealth = armorStand.getAttribute(Attributes.MAX_HEALTH);
        if (maxHealth != null && maxHealth.getBaseValue() != armorStandHealth) {
            maxHealth.setBaseValue(armorStandHealth);
        }
        armorStand.setInvulnerable(false);
        armorStand.setSilent(true);
        armorStand.addTag("bastion_armor_stand");
    }

    @Nullable
    private ServerPlayer findCommanderForTeam(String team) {
        var server = Espetro.getServer();
        if (server == null) return null;

        var voteManager = org.espetro.team.VoteManager.getInstance();
        java.util.UUID commanderId = "ATTACK".equals(team) ?
            voteManager.getAttackCommander() : voteManager.getDefendCommander();

        if (commanderId != null) {
            return server.getPlayerList().getPlayer(commanderId);
        }
        return null;
    }

    private boolean registerBastionRecord(BastionData bastion) {
        BlockPos armorStandPos = bastion.getArmorStandPosition();
        if (armorStandPos == null) {
            armorStandPos = bastion.getPosition().above();
            bastion.setArmorStandPosition(armorStandPos);
        }

        bastionRecordPositions.put(bastion.getBastionId(), armorStandPos);
        bastion.setBastionNumber(findAvailableBastionNumber(bastion));
        return true;
    }

    private void releaseBastionRecord(BastionData bastion) {
        unregisterCoreEntity(bastion);
        bastionRecordPositions.remove(bastion.getBastionId());
        Espetro.LOGGER.debug("释放兵站记录: {}", bastion.getName());
        bastion.setBastionNumber(-1);
        bastion.clearArmorStandPosition();
        bastion.setArmorStandId(null);
    }

    private void clearBastionRecords() {
        bastionRecordPositions.clear();
    }

    private void registerCoreEntity(BastionData bastion) {
        UUID armorStandId = bastion.getArmorStandId();
        if (armorStandId != null) {
            bastionIdsByArmorStand.put(armorStandId, bastion.getBastionId());
        }
    }

    private void unregisterCoreEntity(BastionData bastion) {
        UUID armorStandId = bastion.getArmorStandId();
        if (armorStandId != null) {
            bastionIdsByArmorStand.remove(armorStandId);
        }
    }

    private int findAvailableBastionNumber(BastionData bastion) {
        boolean[] used = new boolean[MAX_BASTIONS + 1];
        for (BastionData other : bastions.values()) {
            if (other.getBastionId().equals(bastion.getBastionId())) {
                continue;
            }
            if (!other.isActive() || !Objects.equals(other.getTeam(), bastion.getTeam())) {
                continue;
            }
            int number = other.getBastionNumber();
            if (number >= 1 && number <= MAX_BASTIONS) {
                used[number] = true;
            }
        }

        int savedNumber = bastion.getBastionNumber();
        if (savedNumber >= 1 && savedNumber <= MAX_BASTIONS && !used[savedNumber]) {
            return savedNumber;
        }

        for (int number = 1; number <= MAX_BASTIONS; number++) {
            if (!used[number]) {
                return number;
            }
        }
        return MAX_BASTIONS;
    }

    /**
     * 检查玩家是否在建造冷却中
     * @return 剩余冷却秒数，0表示无冷却
     */
    public int getBastionCooldownRemaining(UUID playerId) {
        return getBastionCooldownRemaining(playerId, cooldownSeconds);
    }

    public int getBastionCooldownRemaining(UUID playerId, int effectiveCooldownSeconds) {
        Long lastUse = bastionCooldowns.get(playerId);
        if (lastUse == null) {
            return 0;
        }

        long elapsed = System.currentTimeMillis() - lastUse;
        int remaining = (int) ((Math.max(0, effectiveCooldownSeconds) * 1000L - elapsed) / 1000L);
        return Math.max(0, remaining);
    }

    /**
     * 设置玩家建造冷却
     */
    public void setBastionCooldown(UUID playerId) {
        bastionCooldowns.put(playerId, System.currentTimeMillis());
    }

    /**
     * 检查玩家是否可以建造兵站
     * @return null表示可以，String表示不能的原因
     */
    @Nullable
    public String canBuildBastion(UUID playerId) {
        return canBuildBastion(playerId, cooldownSeconds);
    }

    @Nullable
    public String canBuildBastion(UUID playerId, int effectiveCooldownSeconds) {
        int remaining = getBastionCooldownRemaining(playerId, effectiveCooldownSeconds);
        if (remaining > 0) {
            return "§c兵站建造冷却中！请等待 " + remaining + " 秒后再试。";
        }
        return null;
    }

    // ==================== 弹药补给（无冷却，无次数限制）====================

    /**
     * 尝试补给弹药（已取消冷却，始终放行）
     * @return null表示成功，String表示失败原因
     */
    @Nullable
    public String tryResupply(UUID playerId) {
        return null;
    }

    /**
     * 记录补给成功（已取消冷却，不再记录时间）
     */
    public void recordResupply(UUID playerId) {
        // 无冷却：保留空实现以兼容旧调用。
    }

    /**
     * 获取玩家补给冷却剩余秒数（已取消，恒为 0）
     */
    public int getResupplyCooldownRemaining(UUID playerId) {
        return 0;
    }

    /**
     * 根据潜影盒位置查找对应兵站
     */
    @Nullable
    public BastionData findBastionByShulkerPos(BlockPos pos) {
        for (BastionData b : bastions.values()) {
            if (b.isActive() && pos.equals(b.getShulkerPos())) {
                return b;
            }
        }
        return null;
    }

    /**
     * 保存玩家的原部署点位置
     */
    public void savePlayerDeployPoint(ServerPlayer player) {
        BlockPos bedPos = player.getRespawnPosition();
        ServerLevel level = player.serverLevel();
        BlockPos spawnPos = level.getSharedSpawnPos();

        BlockPos deployPos;
        if (bedPos != null) {
            deployPos = bedPos;
        } else {
            deployPos = spawnPos;
        }

        playerDeployPoints.put(player.getUUID(), new DeployPoint(deployPos, level));
    }

    /**
     * 保存玩家指定的部署点位置（用于战局中加入）
     */
    public void savePlayerDeployPoint(ServerPlayer player, BlockPos pos, ServerLevel level) {
        playerDeployPoints.put(player.getUUID(), new DeployPoint(pos, level));
    }

    /**
     * 获取玩家的原部署点
     */
    @Nullable
    public DeployPoint getPlayerDeployPoint(UUID playerId) {
        return playerDeployPoints.get(playerId);
    }

    /** Immutable, level-reference-free deploy points for tactical-map consumers. */
    public List<PlayerDeployPointSnapshot> getPlayerDeployPointSnapshots() {
        return playerDeployPoints.entrySet().stream()
            .filter(entry -> entry.getValue() != null
                && entry.getValue().pos != null
                && entry.getValue().level != null)
            .map(entry -> new PlayerDeployPointSnapshot(
                entry.getKey(),
                entry.getValue().level.dimension().location().toString(),
                entry.getValue().pos.getX(),
                entry.getValue().pos.getY(),
                entry.getValue().pos.getZ()))
            .sorted(Comparator.comparing(snapshot -> snapshot.playerId().toString()))
            .toList();
    }

    /**
     * 在原部署点复活玩家
     */
    public boolean respawnAtDeployPoint(ServerLevel level, ServerPlayer player) {
        DeployPoint deployPoint = playerDeployPoints.get(player.getUUID());
        if (deployPoint == null) {
            return false;
        }

        // 改选原部署点：取消未完成的 Rally 波次队列。
        org.espetro.team.TeamPackManager.getInstance().cancelPendingRespawn(player.getUUID());

        // 传送玩家到原部署点。该坐标由队伍复活点 JSON 配置保存，不再因目标区块未加载而取消。
        player.teleportTo(deployPoint.level, deployPoint.pos.getX() + 0.5, deployPoint.pos.getY() + 0.1, deployPoint.pos.getZ() + 0.5, 0f, 0f);
        // 先传送后清除等待，防止空窗期间移动包绕过检查
        clearWaiting(player.getUUID());

        // 设置生存模式
        player.setGameMode(GameType.SURVIVAL);

        // 移除所有效果
        player.removeAllEffects();

        // 给予短暂的无敌效果
        int invincibilityTicks = org.espetro.config.GameConfig.getRespawnInvincibilityTicks();
        player.addEffect(new net.minecraft.world.effect.MobEffectInstance(
            net.minecraft.world.effect.MobEffects.DAMAGE_RESISTANCE,
            invincibilityTicks,
            127, // 最大等级
            false, false, false
        ));
        org.espetro.team.GameStateManager.getInstance().applyBattlefieldMiningRestriction(player);

        player.sendSystemMessage(net.minecraft.network.chat.Component.literal("§a已在原部署点复活！"));

        return true;
    }

    /**
     * 原部署点数据类
     */
    public static class DeployPoint {
        public final BlockPos pos;
        public final ServerLevel level;

        public DeployPoint(BlockPos pos, ServerLevel level) {
            this.pos = pos;
            this.level = level;
        }
    }

    public record PlayerDeployPointSnapshot(UUID playerId, String dimension,
                                            int x, int y, int z) {
    }
}
