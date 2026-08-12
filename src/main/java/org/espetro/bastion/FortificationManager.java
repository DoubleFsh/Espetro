package org.espetro.bastion;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.PacketDistributor;
import org.espetro.Espetro;
import org.espetro.logistics.LogisticsConfig;
import org.espetro.mapconfig.BattlefieldContext;
import org.espetro.network.FortificationPreviewPacket;
import org.espetro.network.FortificationProgressPacket;
import org.espetro.network.NetworkManager;
import org.espetro.team.GamePhase;
import org.espetro.team.GameStateManager;
import org.espetro.team.SquadManager;
import org.espetro.team.VoteManager;
import org.espetro.vehicle.VehicleManager;

import javax.annotation.Nullable;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Server-authoritative preview, construction, damage and repair state. */
public final class FortificationManager {
    public static final String BUILTIN_RADIO = "builtin_radio";
    public static final String BUILTIN_HAB = "builtin_hab";
    private static final double PLACE_REACH = 6.0D;
    private static final long PREVIEW_LIFETIME_TICKS = 1_200L;
    private static final long WORK_INTERVAL_TICKS = 5L;
    private static final FortificationManager INSTANCE = new FortificationManager();

    private final Map<UUID, Construction> constructions = new HashMap<>();
    private final Map<String, UUID> positionIndex = new HashMap<>();
    private final Map<String, PlacedFort> placed = new HashMap<>();
    private final Map<UUID, UUID> entityIndex = new HashMap<>();
    private final Map<UUID, UUID> bastionIndex = new HashMap<>();
    private final Map<UUID, PreviewSession> previews = new HashMap<>();
    private final Map<UUID, Long> lastWorkTick = new HashMap<>();

    private FortificationManager() {
    }

    public static FortificationManager getInstance() {
        return INSTANCE;
    }

    public record PlacedFort(String fortId, String team, @Nullable UUID radioId,
                             String dimension, BlockPos pos, @Nullable UUID entityId,
                             UUID mapId) {
    }

    public record RadioConstructionProgress(int progress, int required) {
    }

    private enum Kind { CONFIG_BLOCKS, CONFIG_ENTITY, RADIO, HAB }

    private record Slot(BlockPos offset, @Nullable BlockState state) {
    }

    private record Blueprint(Kind kind, String id, String displayName,
                             FortificationConfig.ConstructionProfile profile,
                             List<Slot> slots,
                             @Nullable FortificationConfig.FortificationDef definition) {
    }

    private record PreviewSession(UUID token, String dimension, Blueprint blueprint,
                                  String team, long expiresAt) {
    }

    private static final class Construction {
        final UUID id = UUID.randomUUID();
        final Blueprint blueprint;
        final String team;
        final String dimension;
        final BlockPos anchor;
        final Direction facing;
        final List<WorldSlot> finalSlots;
        final List<BlockPos> footprint;
        final Set<BlockPos> missing = new HashSet<>();
        final UUID radioId;
        final UUID mapId;
        int progress;
        boolean complete;
        UUID entityId;
        UUID bastionId;

        Construction(Blueprint blueprint, String team, String dimension, BlockPos anchor,
                     Direction facing, List<WorldSlot> finalSlots, List<BlockPos> footprint,
                     @Nullable UUID radioId) {
            this.blueprint = blueprint;
            this.team = team;
            this.dimension = dimension;
            this.anchor = anchor.immutable();
            this.facing = facing;
            this.finalSlots = List.copyOf(finalSlots);
            this.footprint = List.copyOf(footprint);
            this.radioId = radioId;
            this.mapId = stableMapId(dimension, anchor);
        }

        int required() { return blueprint.profile.requiredProgress; }
    }

    private record WorldSlot(BlockPos pos, @Nullable BlockState state) {
    }

    public void reset() {
        for (PlacedFort fort : new ArrayList<>(placed.values())) {
            VehicleManager.getInstance().unregisterMappedSupplyStation(fort.mapId());
        }
        constructions.clear();
        positionIndex.clear();
        placed.clear();
        entityIndex.clear();
        bastionIndex.clear();
        previews.clear();
        lastWorkTick.clear();
    }

    /** Drop bounded per-player transient state without scanning world data. */
    public void clearPlayer(UUID playerId) {
        if (playerId == null) return;
        previews.remove(playerId);
        lastWorkTick.remove(playerId);
    }

    /** Wheel selection: validate identity/role and send a bounded local preview blueprint. */
    @Nullable
    public String beginPreview(ServerPlayer player, String fortId) {
        String common = validateCommon(player);
        if (common != null) return common;
        String team = normalizeTeam(Espetro.getPlayerTeam(player));
        if (team == null) return "§c无法确定队伍。";

        Blueprint blueprint = createBlueprint(fortId, team);
        if (blueprint == null) return "§c工事配置无效或缺少所需模组。";
        String roleError = validateSelectionRole(player, blueprint);
        if (roleError != null) return roleError;

        UUID token = UUID.randomUUID();
        String dimension = player.level().dimension().location().toString();
        previews.put(player.getUUID(), new PreviewSession(token, dimension, blueprint, team,
            player.serverLevel().getGameTime() + PREVIEW_LIFETIME_TICKS));
        List<FortificationPreviewPacket.Offset> offsets = blueprint.slots.stream()
            .map(slot -> new FortificationPreviewPacket.Offset(
                slot.offset.getX(), slot.offset.getY(), slot.offset.getZ()))
            .toList();
        NetworkManager.NET.send(PacketDistributor.PLAYER.with(() -> player),
            new FortificationPreviewPacket(token, blueprint.id, blueprint.displayName, offsets));
        return null;
    }

    @Nullable
    public String cancelPreview(ServerPlayer player, UUID token) {
        PreviewSession preview = previews.get(player.getUUID());
        if (preview != null && preview.token.equals(token)) previews.remove(player.getUUID());
        return null;
    }

    /** Confirm the exact client outline after repeating every authoritative validation. */
    @Nullable
    public String confirmPreview(ServerPlayer player, UUID token, BlockPos anchor, Direction facing) {
        PreviewSession preview = previews.get(player.getUUID());
        if (preview == null || !preview.token.equals(token)) return "§c工事预览已失效，请重新选择。";
        if (preview.expiresAt < player.serverLevel().getGameTime()) {
            previews.remove(player.getUUID());
            return "§c工事预览已超时，请重新选择。";
        }
        if (!preview.dimension.equals(player.level().dimension().location().toString())) {
            previews.remove(player.getUUID());
            return "§c你已离开预览所在区域。";
        }
        String common = validateCommon(player);
        if (common != null) return common;
        if (facing == null || !facing.getAxis().isHorizontal()) return "§c无效的工事方向。";
        if (facing != player.getDirection()) return "§c工事方向已变化，请重新对准后确认。";
        BlockPos serverTarget = raycastPlacePos(player);
        if (serverTarget == null || !serverTarget.equals(anchor)
            || player.getEyePosition().distanceToSqr(Vec3.atCenterOf(anchor)) > 64.0D) {
            return "§c放置点已变化，请重新对准后确认。";
        }
        String roleError = validateSelectionRole(player, preview.blueprint);
        if (roleError != null) return roleError;

        List<WorldSlot> finalSlots = transform(preview.blueprint.slots, anchor, facing);
        List<BlockPos> footprint = footprint(finalSlots);
        if (!spaceIsClear(player.serverLevel(), finalSlots, player)) return "§c红色范围内存在方块或实体。";
        for (WorldSlot slot : finalSlots) {
            if (positionIndex.containsKey(posKey(player.serverLevel(), slot.pos))) {
                return "§c该空间已被其他工事占用。";
            }
        }

        PlacementBacking backing = validateBacking(player, preview, anchor);
        if (backing.error != null) return backing.error;
        if (!debitBacking(player, preview.blueprint, backing, anchor)) return "§c建造资源不足。";

        Construction construction = new Construction(preview.blueprint, preview.team,
            preview.dimension, anchor, facing, finalSlots, footprint, backing.radioId);
        if (!placeFoundations(player.serverLevel(), construction, 0)) {
            refundBacking(player, preview.blueprint, backing);
            return "§c施工底座放置失败。";
        }
        registerConstruction(player.serverLevel(), construction);
        previews.remove(player.getUUID());
        sendProgress(player, construction, true);
        player.sendSystemMessage(Component.literal("§a施工范围已确认，按住工兵铲左键开始修建。"));
        return null;
    }

    /** One O(1), per-player-rate-limited shovel operation. */
    public void work(ServerPlayer player, BlockPos target, boolean build) {
        if (player == null || target == null || player.isSpectator() || !player.isAlive()
            || player.getMainHandItem().getItem() != Items.IRON_SHOVEL
            || !(player.level() instanceof ServerLevel level)
            || player.getEyePosition().distanceToSqr(Vec3.atCenterOf(target)) > 49.0D
            || !isLookingAt(player, target)) return;
        UUID constructionId = positionIndex.get(posKey(level, target));
        Construction construction = constructionId == null ? null : constructions.get(constructionId);
        if (construction == null) return;
        applyWork(player, level, construction, build);
    }

    public void workEntity(ServerPlayer player, UUID target, boolean build) {
        if (player == null || target == null || player.isSpectator() || !player.isAlive()
            || player.getMainHandItem().getItem() != Items.IRON_SHOVEL
            || !(player.level() instanceof ServerLevel level)) return;
        UUID constructionId = entityIndex.get(target);
        Construction construction = constructionId == null ? null : constructions.get(constructionId);
        Entity entity = level.getEntity(target);
        if (construction == null || entity == null || player.distanceToSqr(entity) > 49.0D
            || !player.hasLineOfSight(entity)) return;
        applyWork(player, level, construction, build);
    }

    private void applyWork(ServerPlayer player, ServerLevel level, Construction construction,
                           boolean build) {
        long now = level.getGameTime();
        long previous = lastWorkTick.getOrDefault(player.getUUID(), Long.MIN_VALUE / 2);
        if (now - previous < WORK_INTERVAL_TICKS) return;
        lastWorkTick.put(player.getUUID(), now);

        if (build) {
            int old = construction.progress;
            construction.progress = Math.min(construction.required(), old
                + construction.blueprint.profile.buildPerHit);
            restoreProportional(level, construction);
            if (!construction.complete) updateFoundationStage(level, construction);
            if (!construction.complete && construction.progress >= construction.required()) {
                if (!complete(level, construction, player)) {
                    construction.progress = construction.required();
                    player.displayClientMessage(Component.literal("§e最终空间被占用，清空后再次铲击。"), true);
                }
            }
        } else {
            construction.progress = Math.max(0, construction.progress
                - construction.blueprint.profile.removePerHit);
            if (!construction.complete) updateFoundationStage(level, construction);
            if (construction.progress == 0) destroy(level, construction, player, true);
        }
        if (constructions.containsKey(construction.id)) sendProgress(player, construction, build);
        else NetworkManager.NET.send(PacketDistributor.PLAYER.with(() -> player),
            new FortificationProgressPacket(construction.blueprint.displayName, 0,
                construction.required(), build));
        if (construction.blueprint.kind == Kind.RADIO && constructions.containsKey(construction.id)) {
            FobSupplyTracker.notifyConstructionProgressChanged(level, construction.anchor, construction.team);
        }
    }

    /** External block destruction; duplicate explosion/break callbacks are idempotent. */
    public void removeAt(ServerLevel level, BlockPos pos) {
        damageAt(level, pos, null);
    }

    /** 爆炸按中心与半径直接对附近工事扣血，不依赖 affectedBlocks 是否包含工事方块。 */
    public void damageNearby(ServerLevel level, net.minecraft.world.phys.Vec3 center,
                             float radius, @Nullable Entity attacker) {
        if (level == null || center == null) return;
        double checkRadius = radius + 1.5D;
        double checkRadiusSq = checkRadius * checkRadius;
        String dimension = level.dimension().location().toString();
        for (Construction c : new ArrayList<>(constructions.values())) {
            if (!dimension.equals(c.dimension) || c.finalSlots.isEmpty()) continue;
            BlockPos probe = c.finalSlots.get(0).pos;
            if (probe.distToCenterSqr(center.x, center.y, center.z) > checkRadiusSq) continue;
            damageAt(level, probe, attacker, FortificationConfig.explosionDamageRatio());
        }
    }

    public void damageAt(ServerLevel level, BlockPos pos, @Nullable Entity attacker) {
        damageAt(level, pos, attacker, 1.0f);
    }

    public void damageAt(ServerLevel level, BlockPos pos, @Nullable Entity attacker,
                         float damageRatio) {
        UUID id = positionIndex.get(posKey(level, pos));
        Construction construction = id == null ? null : constructions.get(id);
        if (construction == null || !construction.missing.add(pos.immutable())) return;
        int parts = construction.complete ? construction.finalSlots.size() : construction.footprint.size();
        int baseDamage = FortificationProgressPolicy.damagePerPart(construction.required(), parts);
        int damage = damageRatio >= 1.0f
            ? baseDamage
            : Math.max(0, Math.round(baseDamage * damageRatio));
        construction.progress = Math.max(0, construction.progress - damage);
        if (construction.progress == 0) destroy(level, construction, attacker, true);
        else if (!construction.complete) updateFoundationStage(level, construction);
        if (construction.blueprint.kind == Kind.RADIO && constructions.containsKey(construction.id)) {
            FobSupplyTracker.notifyConstructionProgressChanged(level, construction.anchor, construction.team);
        }
    }

    /** 炮弹/导弹直接命中工事实体时扣除一次完整度。 */
    public void damageEntity(ServerLevel level, UUID entityId, @Nullable Entity attacker) {
        if (level == null || entityId == null) return;
        UUID constructionId = entityIndex.get(entityId);
        Construction construction = constructionId == null ? null : constructions.get(constructionId);
        if (construction == null || construction.finalSlots.isEmpty()) return;
        damageAt(level, construction.finalSlots.get(0).pos, attacker,
            FortificationConfig.projectileHitDamageRatio());
    }

    /** Entity fortifications are a single integrity part. */
    public void removeEntity(UUID entityId) {
        UUID id = entityIndex.remove(entityId);
        Construction construction = id == null ? null : constructions.get(id);
        if (construction == null) return;
        ServerLevel level = levelFor(construction);
        if (level != null) destroy(level, construction, null, true);
        else unregisterConstruction(construction);
    }

    public boolean contains(ServerLevel level, BlockPos pos) {
        return positionIndex.containsKey(posKey(level, pos));
    }

    public boolean containsEntity(UUID entityId) {
        return entityId != null && entityIndex.containsKey(entityId);
    }

    public boolean isFoundation(ServerLevel level, BlockPos pos) {
        UUID id = positionIndex.get(posKey(level, pos));
        Construction c = id == null ? null : constructions.get(id);
        return c != null && !c.complete && c.footprint.contains(pos);
    }

    public boolean isAmmoCrateAt(ServerLevel level, BlockPos pos, String team) {
        PlacedFort fort = placed.get(posKey(level, pos));
        if (fort != null) return "ammo_crate".equals(fort.fortId()) && team != null && team.equals(fort.team());
        BastionData radio = BastionManager.getInstance().findBastionByShulkerPos(pos);
        return radio != null && team != null && team.equals(radio.getTeam()) && radio.isAmmoCrateBuilt();
    }

    @Nullable
    public BastionData findRadioForAmmoCrate(ServerLevel level, BlockPos cratePos, String team) {
        PlacedFort fort = placed.get(posKey(level, cratePos));
        if (fort != null && "ammo_crate".equals(fort.fortId()) && team != null && team.equals(fort.team())) {
            BastionData radio = fort.radioId() == null ? null : BastionManager.getInstance().getBastion(fort.radioId());
            if (radio != null && radio.isActive() && radio.isRadio()) return radio;
        }
        return null;
    }

    @Nullable
    public BastionData findVehicleServiceRadio(ServerLevel level, BlockPos vehiclePos, String team) {
        double radiusSq = Math.pow(FortificationConfig.vehicleService().stationRadius, 2);
        for (PlacedFort fort : placed.values()) {
            if (!"vehicle_supply_station".equals(fort.fortId())
                || !fort.dimension().equals(level.dimension().location().toString())
                || !fort.team().equals(team) || fort.pos().distSqr(vehiclePos) > radiusSq) continue;
            BastionData radio = fort.radioId() == null ? null : BastionManager.getInstance().getBastion(fort.radioId());
            if (radio != null && radio.isActive() && radio.isRadio() && team.equals(radio.getTeam())
                && radio.getLevel() == level) return radio;
        }
        return null;
    }

    /** Compatibility entry point; selection now starts a preview instead of placing. */
    @Nullable
    public String place(ServerPlayer player, String fortId) {
        return beginPreview(player, fortId);
    }

    public static boolean canUse(ServerPlayer player, FortificationConfig.FortificationDef def) {
        UUID uuid = player.getUUID();
        for (String raw : def.usableBy == null ? List.<String>of() : def.usableBy) {
            String role = raw.toLowerCase(Locale.ROOT);
            if ("commander".equals(role) && VoteManager.getInstance().isCommander(uuid)) return true;
            if ("squad_leader".equals(role) && SquadManager.getInstance().isSquadLeader(uuid)) return true;
            if ("fireteam_leader".equals(role) && SquadManager.getInstance().isFireteamLeader(uuid)) return true;
        }
        return false;
    }

    public static boolean canOpenBuildMenu(ServerPlayer player) {
        return FortificationConfig.list().stream().anyMatch(def -> canUse(player, def));
    }

    /** 查询指定位置附近己方 Radio 工事的建造进度（含在建和已建成）。 */
    @Nullable
    public RadioConstructionProgress getRadioConstructionProgress(ServerLevel level, BlockPos pos,
                                                                  String team) {
        if (level == null || pos == null || team == null) return null;
        double radius = LogisticsConfig.get().radioBuildRadius;
        double radiusSq = radius * radius;
        String dimension = level.dimension().location().toString();
        Construction best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Construction c : constructions.values()) {
            if (c.blueprint.kind != Kind.RADIO
                || !team.equals(c.team)
                || !dimension.equals(c.dimension)) {
                continue;
            }
            double distance = c.anchor.distSqr(pos);
            if (distance <= radiusSq && distance < bestDistance) {
                best = c;
                bestDistance = distance;
            }
        }
        return best == null ? null
            : new RadioConstructionProgress(best.progress, best.required());
    }

    private Blueprint createBlueprint(String id, String team) {
        if (BUILTIN_RADIO.equals(id)) {
            if (BastionItems.RADIO_BLOCK == null) return null;
            return new Blueprint(Kind.RADIO, id, "Radio", FortificationConfig.radioConstruction(),
                List.of(new Slot(BlockPos.ZERO, BastionItems.RADIO_BLOCK.defaultBlockState())), null);
        }
        if (BUILTIN_HAB.equals(id)) {
            return new Blueprint(Kind.HAB, id, "兵站", FortificationConfig.habConstruction(),
                habSlots(team), null);
        }
        FortificationConfig.FortificationDef def = FortificationConfig.get(id);
        if (def == null) return null;
        List<Slot> slots = new ArrayList<>();
        Kind kind;
        if ("structure".equals(def.placeType)) {
            kind = Kind.CONFIG_BLOCKS;
            for (FortificationConfig.StructureBlockDef raw : def.blocks) {
                BlockState state = resolveBlock(raw.blockId);
                if (state == null) return null;
                slots.add(new Slot(new BlockPos(raw.offset.get(0), raw.offset.get(1), raw.offset.get(2)), state));
            }
        } else if ("entity".equals(def.placeType)) {
            kind = Kind.CONFIG_ENTITY;
            BlockState fallback = resolveBlock(def.fallbackBlockId);
            ResourceLocation entityId = ResourceLocation.tryParse(def.entityId);
            if ((entityId == null || !BuiltInRegistries.ENTITY_TYPE.containsKey(entityId)) && fallback == null) return null;
            slots.add(new Slot(BlockPos.ZERO, fallback));
        } else {
            kind = Kind.CONFIG_BLOCKS;
            BlockState state = resolveBlock(def.blockId);
            if (state == null) return null;
            slots.add(new Slot(BlockPos.ZERO, state));
        }
        return new Blueprint(kind, def.id, def.displayName,
            new FortificationConfig.ConstructionProfile(def.requiredProgress, def.buildPerHit, def.removePerHit),
            List.copyOf(slots), def);
    }

    private static List<Slot> habSlots(String team) {
        BlockState wall = "ATTACK".equals(team) ? Blocks.RED_WOOL.defaultBlockState() : Blocks.BLUE_WOOL.defaultBlockState();
        BlockState roof = Blocks.SPRUCE_TRAPDOOR.defaultBlockState();
        Map<BlockPos, BlockState> slots = new HashMap<>();
        for (int x = -1; x <= 0; x++) for (int y = 0; y <= 1; y++) slots.put(new BlockPos(x, y, -1), wall);
        for (int z = -1; z <= 2; z++) for (int y = 0; y <= 1; y++) slots.put(new BlockPos(1, y, z), wall);
        for (int x = -1; x <= 1; x++) for (int y = 0; y <= 1; y++) slots.put(new BlockPos(x, y, 2), wall);
        for (int y = 0; y <= 1; y++) {
            slots.put(new BlockPos(-3, y, 2), wall);
            slots.put(new BlockPos(-3, y, -1), wall);
        }
        for (int x = -3; x <= 1; x++) for (int z = -1; z <= 2; z++) slots.put(new BlockPos(x, 2, z), roof);
        slots.put(new BlockPos(0, 1, 1), Blocks.LANTERN.defaultBlockState());
        Comparator<BlockPos> order = Comparator.comparingInt((BlockPos pos) -> pos.getY())
            .thenComparingInt(pos -> pos.getX()).thenComparingInt(pos -> pos.getZ());
        return slots.entrySet().stream().sorted(Map.Entry.comparingByKey(order))
            .map(entry -> new Slot(entry.getKey(), entry.getValue())).toList();
    }

    @Nullable
    private String validateCommon(ServerPlayer player) {
        if (player == null || player.isSpectator() || !player.isAlive()) return "§c当前状态无法建造。";
        GamePhase phase = GameStateManager.getInstance().getCurrentPhase();
        if (phase != GamePhase.DEPLOYING && phase != GamePhase.BATTLE) return "§c当前阶段无法建造工事。";
        if (!(player.level() instanceof ServerLevel level) || !BattlefieldContext.isActiveBattlefield(level)) {
            return "§c只能在当前战场建造工事。";
        }
        return null;
    }

    @Nullable
    private String validateSelectionRole(ServerPlayer player, Blueprint blueprint) {
        if (blueprint.kind == Kind.CONFIG_BLOCKS || blueprint.kind == Kind.CONFIG_ENTITY) {
            return canUse(player, blueprint.definition) ? null : "§c你没有权限建造该工事。";
        }
        boolean commander = VoteManager.getInstance().isCommander(player.getUUID());
        boolean leader = SquadManager.getInstance().isSquadLeader(player.getUUID());
        boolean fireteamLeader = SquadManager.getInstance().isFireteamLeader(player.getUUID());
        if (!commander && !leader && !fireteamLeader) {
            return "§c只有指挥官、小队长或火力组长可以选择该工事。";
        }
        return null;
    }

    private record PlacementBacking(@Nullable BastionData radio, @Nullable UUID radioId,
                                    @Nullable String error) {
    }

    private PlacementBacking validateBacking(ServerPlayer player, PreviewSession preview, BlockPos anchor) {
        Blueprint blueprint = preview.blueprint;
        ServerLevel level = player.serverLevel();
        if (blueprint.kind == Kind.RADIO) {
            LogisticsConfig.RadioPlacementSettings cfg = LogisticsConfig.get().getRadio();
            GamePhase phase = GameStateManager.getInstance().getCurrentPhase();
            if (!cfg.allowsPhase(phase.name())) return new PlacementBacking(null, null, "§c当前阶段不能部署 Radio。" );
            BastionManager manager = BastionManager.getInstance();
            String cooldown = manager.canBuildBastion(player.getUUID(), manager.getEffectiveRadioCooldownSeconds());
            if (cooldown != null) return new PlacementBacking(null, null, cooldown);
            long pending = constructions.values().stream().filter(c -> c.blueprint.kind == Kind.RADIO && c.team.equals(preview.team)).count();
            if (manager.getActiveBastionCount(preview.team) + pending >= manager.getBastionLimitPerTeam()) {
                return new PlacementBacking(null, null, "§c本方 Radio 数量已达到上限。" );
            }
            if (manager.wouldRadioCoverageOverlap(level, anchor) || pendingRadioOverlap(level, anchor)) {
                return new PlacementBacking(null, null, "§cRadio 作用范围不能与其他 Radio 重叠。" );
            }
            if (cfg.teammateCount > 0 && countNearbyTeammates(player, preview.team, anchor, cfg.teammateRadius) < cfg.teammateCount) {
                return new PlacementBacking(null, null, "§c部署点附近队友数量不足。" );
            }
            return new PlacementBacking(null, null, null);
        }
        if (blueprint.kind == Kind.HAB) {
            List<BastionData> radios = BastionManager.getInstance().findCoveringRadios(level, anchor, preview.team);
            if (radios.isEmpty()) return new PlacementBacking(null, null, "§c兵站必须位于己方 Radio 范围内。" );
            BastionData radio = radios.get(0);
            if (habCountForRadio(radio, preview.team) >= maxHabsFor(player)) {
                return new PlacementBacking(radio, radio.getBastionId(), "§c该 Radio 范围内兵站已达到上限。" );
            }
            if (BastionManager.getInstance().sumConstructionInCoveringRadios(level, anchor, preview.team)
                < BastionManager.getInstance().getHabConstructionCost()) {
                return new PlacementBacking(radio, radio.getBastionId(), "§c覆盖 Radio 的建材不足。" );
            }
            return new PlacementBacking(radio, radio.getBastionId(), null);
        }
        FortificationConfig.FortificationDef def = blueprint.definition;
        BastionData radio = null;
        if (def.requireRadioRange) {
            List<BastionData> radios = BastionManager.getInstance().findCoveringRadios(level, anchor, preview.team);
            if (radios.isEmpty()) return new PlacementBacking(null, null, "§c必须在己方 Radio 范围内建造。" );
            radio = radios.get(0);
        }
        if (radio == null && (def.constructionCost > 0 || def.ammunitionCost > 0)) {
            return new PlacementBacking(null, null, "§c该工事需要 Radio 库存。" );
        }
        if (radio != null && (radio.getConstructionSupplies() < def.constructionCost
            || radio.getAmmunitionSupplies() < def.ammunitionCost)) {
            return new PlacementBacking(radio, radio.getBastionId(), "§c建造资源不足。" );
        }
        return new PlacementBacking(radio, radio == null ? null : radio.getBastionId(), null);
    }

    private boolean debitBacking(ServerPlayer player, Blueprint blueprint, PlacementBacking backing,
                                 BlockPos anchor) {
        if (blueprint.kind == Kind.RADIO) return true;
        if (blueprint.kind == Kind.HAB) {
            int cost = BastionManager.getInstance().getHabConstructionCost();
            return BastionManager.getInstance().tryDebitConstructionFromCoveringRadios(
                player.serverLevel(), anchor, Espetro.getPlayerTeam(player), cost);
        }
        return debit(backing.radio, blueprint.definition.constructionCost, blueprint.definition.ammunitionCost);
    }

    private void refundBacking(ServerPlayer player, Blueprint blueprint, PlacementBacking backing) {
        if (blueprint.kind == Kind.CONFIG_BLOCKS || blueprint.kind == Kind.CONFIG_ENTITY) {
            refund(backing.radio, blueprint.definition.constructionCost, blueprint.definition.ammunitionCost);
        } else if (blueprint.kind == Kind.HAB && backing.radio != null) {
            backing.radio.addConstructionSupplies(BastionManager.getInstance().getHabConstructionCost(),
                LogisticsConfig.get().maxConstruction);
            FobSupplyTracker.notifySupplyChanged(backing.radio);
        }
    }

    private boolean complete(ServerLevel level, Construction c, @Nullable Entity actor) {
        if (!completionSpaceAvailable(level, c)) return false;
        removeFoundations(level, c);
        boolean success;
        if (c.blueprint.kind == Kind.CONFIG_ENTITY) success = completeEntity(level, c);
        else success = placeFinalBlocks(level, c);
        if (!success) {
            placeFoundations(level, c, 6);
            return false;
        }

        if (c.blueprint.kind == Kind.RADIO) {
            String name = nextBastionName(c.team, true);
            BastionData radio = BastionManager.getInstance().createRadio(level, c.anchor, c.team, name);
            if (radio == null) {
                clearFinalBlocks(level, c);
                placeFoundations(level, c, 6);
                return false;
            }
            c.bastionId = radio.getBastionId();
            bastionIndex.put(c.bastionId, c.id);
            BastionManager.getInstance().setBastionCooldown(actor instanceof ServerPlayer p ? p.getUUID() : UUID.randomUUID());
            Espetro.broadcastToTeam(c.team, "§6[Radio] §a" + name + " §a已建成。");
        } else if (c.blueprint.kind == Kind.HAB) {
            String name = nextBastionName(c.team, false);
            BastionData hab = BastionManager.getInstance().createHab(level, c.anchor, c.team, name);
            if (hab == null) {
                clearFinalBlocks(level, c);
                placeFoundations(level, c, 6);
                return false;
            }
            c.bastionId = hab.getBastionId();
            bastionIndex.put(c.bastionId, c.id);
            Espetro.broadcastToTeam(c.team, "§6[兵站] §a" + name + " §a已建成。");
        } else {
            registerCompletedConfig(c);
        }
        c.complete = true;
        c.missing.clear();
        return true;
    }

    private void registerCompletedConfig(Construction c) {
        FortificationConfig.FortificationDef def = c.blueprint.definition;
        PlacedFort fort = new PlacedFort(def.id, c.team, c.radioId, c.dimension, c.anchor,
            c.entityId, c.entityId == null ? c.mapId : c.entityId);
        placed.put(posKey(c.dimension, c.anchor), fort);
        if (c.entityId != null) entityIndex.put(c.entityId, c.id);
        BastionData radio = c.radioId == null ? null : BastionManager.getInstance().getBastion(c.radioId);
        if ("ammo_crate".equals(def.id) && radio != null) {
            radio.setShulkerPos(c.anchor);
            radio.setAmmoCrateBuilt(true);
        }
        if ("vehicle_supply_station".equals(def.id)) {
            VehicleManager.getInstance().registerMappedSupplyStation(fort.mapId(), def.displayName,
                c.team, c.dimension, c.anchor);
        }
        FobSupplyTracker.notifySupplyChanged(radio);
    }

    /** Reconcile an externally destroyed Radio/HAB core without recursively destroying it again. */
    public void onBastionDestroyed(UUID bastionId, @Nullable ServerLevel knownLevel,
                                   @Nullable Entity attacker) {
        UUID constructionId = bastionIndex.remove(bastionId);
        Construction construction = constructionId == null ? null : constructions.get(constructionId);
        if (construction == null) return;
        ServerLevel level = knownLevel != null ? knownLevel : levelFor(construction);
        if (level == null) unregisterConstruction(construction);
        else destroy(level, construction, attacker, true, false);
    }

    private void destroy(ServerLevel level, Construction c, @Nullable Entity actor, boolean removeWorld) {
        destroy(level, c, actor, removeWorld, true);
    }

    private void destroy(ServerLevel level, Construction c, @Nullable Entity actor,
                         boolean removeWorld, boolean destroyBastionRecord) {
        unregisterConstruction(c);
        if (removeWorld) {
            for (BlockPos pos : c.footprint) {
                if (level.hasChunkAt(pos) && level.getBlockState(pos).is(BastionItems.ON_BUILDING_BLOCK)) {
                    level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
                }
            }
            if (c.complete) clearFinalBlocks(level, c);
            if (c.entityId != null) {
                Entity entity = level.getEntity(c.entityId);
                if (entity != null) entity.discard();
            }
        }
        if (destroyBastionRecord && c.bastionId != null) {
            BastionData bastion = BastionManager.getInstance().getBastion(c.bastionId);
            if (bastion != null) {
                if (c.blueprint.kind == Kind.RADIO && actor instanceof ServerPlayer player
                    && c.team.equals(normalizeTeam(Espetro.getPlayerTeam(player)))) {
                    BastionManager.getInstance().destroyBastionWithManpower(
                        bastion, actor, false);
                } else {
                    BastionManager.getInstance().destroyBastion(bastion, actor);
                }
            }
        }
        PlacedFort fort = placed.remove(posKey(c.dimension, c.anchor));
        if (fort != null) {
            VehicleManager.getInstance().unregisterMappedSupplyStation(fort.mapId());
            if ("ammo_crate".equals(fort.fortId()) && fort.radioId() != null) {
                BastionData radio = BastionManager.getInstance().getBastion(fort.radioId());
                if (radio != null && c.anchor.equals(radio.getShulkerPos())) {
                    radio.setAmmoCrateBuilt(false);
                    radio.setShulkerPos(null);
                    FobSupplyTracker.notifySupplyChanged(radio);
                }
            }
        }
    }

    private void registerConstruction(ServerLevel level, Construction c) {
        constructions.put(c.id, c);
        for (WorldSlot slot : c.finalSlots) positionIndex.put(posKey(level, slot.pos), c.id);
    }

    private void unregisterConstruction(Construction c) {
        constructions.remove(c.id);
        for (WorldSlot slot : c.finalSlots) positionIndex.remove(posKey(c.dimension, slot.pos), c.id);
        if (c.entityId != null) entityIndex.remove(c.entityId);
        if (c.bastionId != null) bastionIndex.remove(c.bastionId, c.id);
    }

    private static List<WorldSlot> transform(List<Slot> slots, BlockPos anchor, Direction facing) {
        Direction right = facing.getClockWise();
        List<WorldSlot> result = new ArrayList<>(slots.size());
        for (Slot slot : slots) {
            BlockPos o = slot.offset;
            int dx = right.getStepX() * o.getX() + facing.getStepX() * o.getZ();
            int dz = right.getStepZ() * o.getX() + facing.getStepZ() * o.getZ();
            result.add(new WorldSlot(anchor.offset(dx, o.getY(), dz), slot.state));
        }
        return result;
    }

    private static List<BlockPos> footprint(List<WorldSlot> slots) {
        int minY = slots.stream().mapToInt(slot -> slot.pos.getY()).min().orElse(0);
        LinkedHashSet<BlockPos> result = new LinkedHashSet<>();
        for (WorldSlot slot : slots) if (slot.pos.getY() == minY) result.add(slot.pos.immutable());
        return List.copyOf(result);
    }

    private static boolean spaceIsClear(ServerLevel level, List<WorldSlot> slots, ServerPlayer placer) {
        for (WorldSlot slot : slots) {
            BlockState state = level.getBlockState(slot.pos);
            if (!state.isAir() && !state.is(Blocks.SNOW)) return false;
            if (!level.getEntities((Entity) null, new AABB(slot.pos), entity -> entity != placer
                && entity instanceof LivingEntity && entity.isAlive()).isEmpty()) return false;
        }
        return true;
    }

    private static boolean completionSpaceAvailable(ServerLevel level, Construction c) {
        for (WorldSlot slot : c.finalSlots) {
            BlockState state = level.getBlockState(slot.pos);
            boolean foundation = c.footprint.contains(slot.pos) && state.is(BastionItems.ON_BUILDING_BLOCK);
            if (!foundation && !state.isAir() && !state.is(Blocks.SNOW)) return false;
            if (!level.getEntities((Entity) null, new AABB(slot.pos), entity -> entity instanceof LivingEntity
                && entity.isAlive()).isEmpty()) return false;
        }
        return true;
    }

    private static boolean placeFoundations(ServerLevel level, Construction c, int stage) {
        if (BastionItems.ON_BUILDING_BLOCK == null) return false;
        List<BlockPos> written = new ArrayList<>();
        BlockState state = BastionItems.ON_BUILDING_BLOCK.defaultBlockState()
            .setValue(OnBuildingBlock.STAGE, Math.max(0, Math.min(6, stage)));
        for (BlockPos pos : c.footprint) {
            BlockState old = level.getBlockState(pos);
            if (!old.isAir() && !old.is(Blocks.SNOW)) {
                for (BlockPos rollback : written) level.setBlock(rollback, Blocks.AIR.defaultBlockState(), 3);
                return false;
            }
            if (!level.setBlock(pos, state, 3)) {
                for (BlockPos rollback : written) {
                    if (level.getBlockState(rollback).is(BastionItems.ON_BUILDING_BLOCK)) {
                        level.setBlock(rollback, Blocks.AIR.defaultBlockState(), 3);
                    }
                }
                return false;
            }
            written.add(pos);
        }
        return true;
    }

    private static void removeFoundations(ServerLevel level, Construction c) {
        for (BlockPos pos : c.footprint) if (level.getBlockState(pos).is(BastionItems.ON_BUILDING_BLOCK)) level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
    }

    private static void updateFoundationStage(ServerLevel level, Construction c) {
        int stage = FortificationProgressPolicy.stage(c.progress, c.required());
        for (BlockPos pos : c.footprint) {
            BlockState state = level.getBlockState(pos);
            if (state.is(BastionItems.ON_BUILDING_BLOCK) && state.getValue(OnBuildingBlock.STAGE) != stage) {
                level.setBlock(pos, state.setValue(OnBuildingBlock.STAGE, stage), 2);
            }
        }
    }

    private static boolean placeFinalBlocks(ServerLevel level, Construction c) {
        List<WorldSlot> written = new ArrayList<>();
        for (WorldSlot slot : c.finalSlots) {
            if (slot.state == null || !level.setBlock(slot.pos, slot.state, 3)) {
                for (WorldSlot rollback : written) {
                    if (level.getBlockState(rollback.pos).equals(rollback.state)) {
                        level.setBlock(rollback.pos, Blocks.AIR.defaultBlockState(), 3);
                    }
                }
                return false;
            }
            written.add(slot);
        }
        return true;
    }

    private static void clearFinalBlocks(ServerLevel level, Construction c) {
        for (WorldSlot slot : c.finalSlots) {
            if (!c.missing.contains(slot.pos) && level.hasChunkAt(slot.pos)) {
                level.setBlock(slot.pos, Blocks.AIR.defaultBlockState(), 3);
            }
        }
    }

    private boolean completeEntity(ServerLevel level, Construction c) {
        FortificationConfig.FortificationDef def = c.blueprint.definition;
        ResourceLocation id = ResourceLocation.tryParse(def.entityId);
        EntityType<?> type = id == null ? null : BuiltInRegistries.ENTITY_TYPE.getOptional(id).orElse(null);
        Entity entity = type == null ? null : type.create(level);
        if (entity != null) {
            entity.setPos(c.anchor.getX() + 0.5D, c.anchor.getY(), c.anchor.getZ() + 0.5D);
            entity.setYRot(c.facing.toYRot());
            entity.setCustomName(Component.literal(def.displayName));
            if (VehicleManager.isAmmoSupplyStationEntity(entity) || "vehicle_supply_station".equals(def.id)) {
                VehicleManager.applySupplyStationMapTags(entity, c.team, "fort_" + entity.getUUID());
            } else {
                entity.addTag("espetro_fortification_" + def.id);
                entity.addTag("espetro_team_" + c.team);
            }
            if (level.addFreshEntity(entity)) {
                c.entityId = entity.getUUID();
                return true;
            }
        }
        BlockState fallback = resolveBlock(def.fallbackBlockId);
        return fallback != null && level.setBlock(c.anchor, fallback, 3);
    }

    private static void restoreProportional(ServerLevel level, Construction c) {
        if (c.missing.isEmpty()) return;
        int total = c.complete ? c.finalSlots.size() : c.footprint.size();
        int desired = FortificationProgressPolicy.desiredPresentParts(
            c.progress, c.required(), total);
        int present = total - c.missing.size();
        if (desired <= present) return;
        if (c.complete) {
            for (WorldSlot slot : c.finalSlots) {
                if (present >= desired) break;
                BlockState current = level.getBlockState(slot.pos);
                if (c.missing.contains(slot.pos) && slot.state != null
                    && (current.isAir() || current.is(Blocks.SNOW))
                    && level.setBlock(slot.pos, slot.state, 3)) {
                    c.missing.remove(slot.pos);
                    present++;
                }
            }
        } else {
            int stage = FortificationProgressPolicy.stage(c.progress, c.required());
            BlockState marker = BastionItems.ON_BUILDING_BLOCK.defaultBlockState().setValue(OnBuildingBlock.STAGE, stage);
            for (BlockPos pos : c.footprint) {
                if (present >= desired) break;
                BlockState current = level.getBlockState(pos);
                if (c.missing.contains(pos) && (current.isAir() || current.is(Blocks.SNOW))
                    && level.setBlock(pos, marker, 3)) {
                    c.missing.remove(pos);
                    present++;
                }
            }
        }
    }

    private static boolean debit(@Nullable BastionData radio, int construction, int ammunition) {
        if (radio == null) return construction == 0 && ammunition == 0;
        if (construction > 0 && !radio.consumeConstructionSupplies(construction)) return false;
        if (ammunition > 0 && !radio.consumeAmmunitionSupplies(ammunition)) {
            if (construction > 0) radio.addConstructionSupplies(construction, LogisticsConfig.get().maxConstruction);
            return false;
        }
        FobSupplyTracker.notifySupplyChanged(radio);
        return true;
    }

    private static void refund(@Nullable BastionData radio, int construction, int ammunition) {
        if (radio == null) return;
        if (construction > 0) radio.addConstructionSupplies(construction, LogisticsConfig.get().maxConstruction);
        if (ammunition > 0) radio.addAmmunitionSupplies(ammunition, LogisticsConfig.get().maxAmmunition);
        FobSupplyTracker.notifySupplyChanged(radio);
    }

    @Nullable
    private static BlockState resolveBlock(@Nullable String rawId) {
        ResourceLocation id = ResourceLocation.tryParse(rawId);
        if (id == null || !BuiltInRegistries.BLOCK.containsKey(id)) return null;
        Block block = BuiltInRegistries.BLOCK.get(id);
        return block == Blocks.AIR ? null : block.defaultBlockState();
    }

    @Nullable
    private static BlockPos raycastPlacePos(ServerPlayer player) {
        Vec3 eye = player.getEyePosition(1.0F);
        Vec3 end = eye.add(player.getLookAngle().scale(PLACE_REACH));
        BlockHitResult hit = player.level().clip(new ClipContext(eye, end,
            ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
        if (hit.getType() == HitResult.Type.MISS) return null;
        return player.level().getBlockState(hit.getBlockPos()).is(Blocks.SNOW)
            ? hit.getBlockPos() : hit.getBlockPos().relative(hit.getDirection());
    }

    private static boolean isLookingAt(ServerPlayer player, BlockPos target) {
        Vec3 eye = player.getEyePosition(1.0F);
        Vec3 end = eye.add(player.getLookAngle().scale(7.0D));
        BlockHitResult hit = player.level().clip(new ClipContext(eye, end,
            ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
        return hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(target);
    }

    private boolean pendingRadioOverlap(ServerLevel level, BlockPos anchor) {
        double min = BastionManager.getInstance().getMinimumRadioCenterDistance();
        double minSq = min * min;
        String dimension = level.dimension().location().toString();
        return constructions.values().stream().anyMatch(c -> c.blueprint.kind == Kind.RADIO
            && c.dimension.equals(dimension) && c.anchor.distSqr(anchor) < minSq);
    }

    private int habCountForRadio(BastionData radio, String team) {
        double radiusSq = Math.pow(LogisticsConfig.get().radioBuildRadius, 2);
        int count = 0;
        for (BastionData b : BastionManager.getInstance().getAllBastions()) {
            if (b.isActive() && b.isHab() && team.equals(b.getTeam())
                && b.getPosition().distSqr(radio.getPosition()) <= radiusSq) count++;
        }
        for (Construction c : constructions.values()) {
            if (c.blueprint.kind == Kind.HAB && radio.getBastionId().equals(c.radioId)) count++;
        }
        return count;
    }

    private static int maxHabsFor(ServerPlayer player) {
        String factionId = org.espetro.team.ClassCountManager.getInstance().getPlayerFaction(player.getUUID());
        var faction = factionId == null ? null : org.espetro.team.FactionDataProvider.getOrCreateLoader().getFaction(factionId);
        return faction == null ? 2 : Math.max(0, faction.maxHabsPerRadio);
    }

    private static int countNearbyTeammates(ServerPlayer player, String team, BlockPos center, double radius) {
        int count = 0;
        double radiusSq = radius * radius;
        for (ServerPlayer other : player.serverLevel().players()) {
            if (other != player && other.isAlive() && !other.isSpectator()
                && team.equals(Espetro.getPlayerTeam(other)) && other.blockPosition().distSqr(center) <= radiusSq) count++;
        }
        return count;
    }

    private static String nextBastionName(String team, boolean radio) {
        int number = 1;
        for (BastionData data : BastionManager.getInstance().getAllBastions()) {
            if (data.isActive() && team.equals(data.getTeam()) && (radio ? data.isRadio() : data.isHab())) number++;
        }
        return ("ATTACK".equals(team) ? "进攻" : "防守") + (radio ? "Radio-" : "兵站-") + number;
    }

    private static void sendProgress(ServerPlayer player, Construction c, boolean building) {
        NetworkManager.NET.send(PacketDistributor.PLAYER.with(() -> player),
            new FortificationProgressPacket(c.blueprint.displayName, c.progress, c.required(), building));
    }

    @Nullable
    private ServerLevel levelFor(Construction construction) {
        if (Espetro.getServer() == null) return null;
        for (ServerLevel level : Espetro.getServer().getAllLevels()) {
            if (level.dimension().location().toString().equals(construction.dimension)) return level;
        }
        return null;
    }

    private static UUID stableMapId(String dimension, BlockPos pos) {
        return UUID.nameUUIDFromBytes(("espetro-fort|" + dimension + "|" + pos.asLong())
            .getBytes(StandardCharsets.UTF_8));
    }

    private static String posKey(ServerLevel level, BlockPos pos) {
        return posKey(level.dimension().location().toString(), pos);
    }

    private static String posKey(String dimension, BlockPos pos) {
        return dimension + "|" + pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }

    @Nullable
    private static String normalizeTeam(@Nullable String team) {
        if (team == null) return null;
        String normalized = team.trim().toUpperCase(Locale.ROOT);
        return "ATTACK".equals(normalized) || "DEFEND".equals(normalized) ? normalized : null;
    }
}
