package org.espetro.bastion;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
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
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.PacketDistributor;
import org.espetro.Espetro;
import org.espetro.dimension.BattlefieldWorldManager;
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
import java.util.Collection;
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
    /** Legacy action ids remain aliases; the definitions themselves are JSON v2 entries. */
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
    private final Map<UUID, Boolean> damageableEntityIndex = new HashMap<>();
    private final Map<UUID, PreviewSession> previews = new HashMap<>();
    private final Map<UUID, Long> lastWorkTick = new HashMap<>();
    private final FortificationSpatialIndex spatialIndex = new FortificationSpatialIndex();

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

    public enum DamageKind { EXPLOSION, PROJECTILE, DIRECT_BREAK }

    private enum Kind { STRUCTURE, ENTITY }

    private record Slot(int templateIndex, BlockPos offset, @Nullable BlockState state,
                        @Nullable CompoundTag blockEntityNbt,
                        FortificationTemplateCompiler.Touch touch) {
    }

    private record Blueprint(Kind kind, String id, String displayName,
                             FortificationConfig.ConstructionProfile profile,
                             List<Slot> slots,
                             FortificationConfig.FortificationDef definition,
                             @Nullable FortificationTemplateCompiler.CompiledTemplate template) {
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
        final Set<UUID> spawnedEntities = new HashSet<>();
        final Set<UUID> settledEntityParts = new HashSet<>();
        final UUID radioId;
        final UUID mapId;
        int progress;
        int structuralValue;
        boolean complete;
        boolean fallbackMode;
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

    private record WorldSlot(int templateIndex, BlockPos pos, @Nullable BlockState state,
                             @Nullable CompoundTag blockEntityNbt,
                             FortificationTemplateCompiler.Touch touch) {
    }

    public void reset() {
        for (PlacedFort fort : new ArrayList<>(placed.values())) {
            VehicleManager.getInstance().unregisterMappedSupplyStation(fort.mapId());
        }
        constructions.clear();
        positionIndex.clear();
        placed.clear();
        entityIndex.clear();
        damageableEntityIndex.clear();
        bastionIndex.clear();
        previews.clear();
        lastWorkTick.clear();
        spatialIndex.clear();
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

        if (construction.complete) {
            if (build) {
                construction.structuralValue = Math.min(
                    construction.blueprint.definition.durability.structuralValue,
                    construction.structuralValue
                        + construction.blueprint.definition.durability.repairPerHit);
                restoreProportional(level, construction);
            } else {
                construction.structuralValue = Math.max(0, construction.structuralValue
                    - construction.blueprint.profile.removePerHit);
                if (construction.structuralValue == 0) {
                    destroy(level, construction, player, true, true, true);
                }
            }
        } else if (build) {
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
            if (construction.progress == 0) {
                destroy(level, construction, player, true, true, true);
            }
        }
        if (constructions.containsKey(construction.id)) sendProgress(player, construction, build);
        else NetworkManager.NET.send(PacketDistributor.PLAYER.with(() -> player),
            new FortificationProgressPacket(construction.blueprint.displayName, 0,
                construction.required(), build));
        if (construction.blueprint.definition.behaviorType == FortificationConfig.Behavior.RADIO
            && constructions.containsKey(construction.id)) {
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
        double checkRadius = Math.max(0.0D, radius) + 1.5D;
        double radiusSq = Math.max(0.0D, radius) * Math.max(0.0D, radius);
        damageExplosionParts(level, center, checkRadius, attacker,
            pos -> Vec3.atCenterOf(pos).distanceToSqr(center) <= radiusSq);
    }

    /**
     * Forge's detonation list is the exact set of block cells selected by the
     * vanilla/modded explosion.  The spatial index supplies the broad phase;
     * every intersecting damageable part is then settled independently.
     */
    public void damageExplosion(ServerLevel level, Vec3 center, float radius,
                                Collection<BlockPos> affectedBlocks,
                                @Nullable Entity attacker) {
        if (level == null || center == null || affectedBlocks == null
            || affectedBlocks.isEmpty()) return;
        Set<BlockPos> affected = new HashSet<>();
        for (BlockPos pos : affectedBlocks) {
            if (pos != null) affected.add(pos.immutable());
        }
        if (affected.isEmpty()) return;
        damageExplosionParts(level, center, Math.max(0.0D, radius) + 1.5D,
            attacker, affected::contains);
    }

    private void damageExplosionParts(ServerLevel level, Vec3 center, double checkRadius,
                                      @Nullable Entity attacker,
                                      java.util.function.Predicate<BlockPos> hitPart) {
        String dimension = level.dimension().location().toString();
        AABB query = new AABB(center.x - checkRadius, center.y - checkRadius,
            center.z - checkRadius, center.x + checkRadius, center.y + checkRadius,
            center.z + checkRadius);
        for (UUID id : spatialIndex.query(dimension, query)) {
            Construction construction = constructions.get(id);
            if (construction == null) continue;
            List<BlockPos> parts = construction.complete
                ? construction.finalSlots.stream()
                    .filter(slot -> slot.touch == FortificationTemplateCompiler.Touch.BLOCK)
                    .map(WorldSlot::pos).toList()
                : construction.footprint;
            boolean changed = false;
            for (BlockPos pos : parts) {
                if (!hitPart.test(pos) || !construction.missing.add(pos.immutable())) continue;
                if (isCompletedRadioCore(construction, pos)) {
                    destroy(level, construction, attacker, true);
                    break;
                }
                changed = true;
                damageConstruction(level, construction, pos, attacker, DamageKind.EXPLOSION);
                if (!constructions.containsKey(construction.id)) break;
            }
            if (changed
                && construction.blueprint.definition.behaviorType
                    == FortificationConfig.Behavior.RADIO
                && constructions.containsKey(construction.id)) {
                FobSupplyTracker.notifyConstructionProgressChanged(
                    level, construction.anchor, construction.team);
            }
        }
    }

    public void damageAt(ServerLevel level, BlockPos pos, @Nullable Entity attacker) {
        damageAt(level, pos, attacker, DamageKind.DIRECT_BREAK);
    }

    public void damageAt(ServerLevel level, BlockPos pos, @Nullable Entity attacker,
                         float damageRatio) {
        damageAt(level, pos, attacker,
            damageRatio <= 0.2F ? DamageKind.EXPLOSION : DamageKind.DIRECT_BREAK);
    }

    public void damageAt(ServerLevel level, BlockPos pos, @Nullable Entity attacker,
                         DamageKind kind) {
        UUID id = positionIndex.get(posKey(level, pos));
        Construction construction = id == null ? null : constructions.get(id);
        if (construction == null) return;
        WorldSlot part = construction.finalSlots.stream()
            .filter(slot -> slot.pos.equals(pos)
                && slot.touch == FortificationTemplateCompiler.Touch.BLOCK)
            .findFirst().orElse(null);
        if (part == null || !construction.missing.add(pos.immutable())) return;
        damageConstruction(level, construction, pos, attacker, kind);
        if (construction.blueprint.definition.behaviorType == FortificationConfig.Behavior.RADIO
            && constructions.containsKey(construction.id)) {
            FobSupplyTracker.notifyConstructionProgressChanged(level, construction.anchor, construction.team);
        }
    }

    /** 炮弹/导弹直接命中工事实体时扣除一次完整度。 */
    public void damageEntity(ServerLevel level, UUID entityId, @Nullable Entity attacker) {
        if (level == null || entityId == null) return;
        UUID constructionId = entityIndex.get(entityId);
        Construction construction = constructionId == null ? null : constructions.get(constructionId);
        if (construction == null || !Boolean.TRUE.equals(damageableEntityIndex.get(entityId))
            || construction.blueprint.kind == Kind.ENTITY
            || !construction.settledEntityParts.add(entityId)) return;
        damageConstruction(level, construction, null, attacker, DamageKind.PROJECTILE);
    }

    /** Entity fortifications are a single integrity part. */
    public void removeEntity(UUID entityId) {
        UUID id = entityIndex.get(entityId);
        Construction construction = id == null ? null : constructions.get(id);
        if (construction == null) return;
        ServerLevel level = levelFor(construction);
        if (!Boolean.TRUE.equals(damageableEntityIndex.get(entityId))) {
            entityIndex.remove(entityId);
            construction.spawnedEntities.remove(entityId);
            return;
        }
        if (!construction.settledEntityParts.add(entityId)) return;
        if (level == null) {
            unregisterConstruction(construction);
        } else if (construction.blueprint.kind == Kind.ENTITY && !construction.fallbackMode) {
            construction.structuralValue = 0;
            destroy(level, construction, null, true);
        } else {
            damageConstruction(level, construction, null, null, DamageKind.DIRECT_BREAK);
        }
    }

    private static boolean isCompletedRadioCore(Construction construction, @Nullable BlockPos pos) {
        return pos != null
            && construction.complete
            && construction.blueprint.definition.behaviorType == FortificationConfig.Behavior.RADIO
            && pos.equals(construction.anchor);
    }

    private void damageConstruction(ServerLevel level, Construction construction,
                                    @Nullable BlockPos part, @Nullable Entity attacker,
                                    DamageKind kind) {
        int maximum = construction.complete
            ? construction.blueprint.definition.durability.structuralValue
            : construction.required();
        int parts = construction.complete ? damageablePartCount(construction)
            : Math.max(1, construction.footprint.size());
        int baseDamage = Math.max(1, (int) Math.ceil((double) maximum / parts));
        double reduction = construction.blueprint.definition.durability.damageReduction.forKind(kind);
        int damage = reduction >= 1.0 ? 0
            : Math.max(1, (int) Math.ceil(baseDamage * (1.0 - reduction)));
        if (damage <= 0) return;
        if (construction.complete) {
            construction.structuralValue = Math.max(0, construction.structuralValue - damage);
            if (construction.structuralValue == 0) destroy(level, construction, attacker, true);
        } else {
            construction.progress = Math.max(0, construction.progress - damage);
            if (construction.progress == 0) destroy(level, construction, attacker, true);
            else updateFoundationStage(level, construction);
        }
    }

    private static int damageablePartCount(Construction construction) {
        if (construction.blueprint.kind == Kind.ENTITY && !construction.fallbackMode) return 1;
        return construction.blueprint.template == null ? 1
            : Math.max(1, construction.blueprint.template.damageablePartCount());
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
        if (fort != null) return behaviorOf(level, fort.fortId())
            == FortificationConfig.Behavior.AMMO_CRATE
            && team != null && team.equals(fort.team());
        BastionData radio = BastionManager.getInstance().findBastionByShulkerPos(pos);
        return radio != null && team != null && team.equals(radio.getTeam()) && radio.isAmmoCrateBuilt();
    }

    @Nullable
    public BastionData findRadioForAmmoCrate(ServerLevel level, BlockPos cratePos, String team) {
        PlacedFort fort = placed.get(posKey(level, cratePos));
        if (fort != null && behaviorOf(level, fort.fortId())
            == FortificationConfig.Behavior.AMMO_CRATE
            && team != null && team.equals(fort.team())) {
            BastionData radio = fort.radioId() == null ? null : BastionManager.getInstance().getBastion(fort.radioId());
            if (radio != null && radio.isActive() && radio.isRadio()) return radio;
        }
        return null;
    }

    @Nullable
    public BastionData findVehicleServiceRadio(ServerLevel level, BlockPos vehiclePos, String team) {
        double radiusSq = Math.pow(FortificationConfig.vehicleService().stationRadius, 2);
        for (PlacedFort fort : placed.values()) {
            if (behaviorOf(level, fort.fortId())
                    != FortificationConfig.Behavior.VEHICLE_SUPPLY_STATION
                || !fort.dimension().equals(level.dimension().location().toString())
                || !fort.team().equals(team) || fort.pos().distSqr(vehiclePos) > radiusSq) continue;
            BastionData radio = fort.radioId() == null ? null : BastionManager.getInstance().getBastion(fort.radioId());
            if (radio != null && radio.isActive() && radio.isRadio() && team.equals(radio.getTeam())
                && radio.getLevel() == level) return radio;
        }
        return null;
    }

    @Nullable
    private static FortificationConfig.Behavior behaviorOf(ServerLevel level, String id) {
        FortificationConfig.FortificationDef def = FortificationConfig.get(
            level.dimension().location(), id);
        return def == null ? null : def.behaviorType;
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
            if (c.blueprint.definition.behaviorType != FortificationConfig.Behavior.RADIO
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
        ResourceLocation dimension = BattlefieldContext.getActiveDimensionKey()
            .map(net.minecraft.resources.ResourceKey::location).orElse(null);
        FortificationConfig.FortificationDef def = dimension == null
            ? FortificationConfig.get(id) : FortificationConfig.get(dimension, id);
        if (def == null) return null;
        FortificationTemplateCompiler.CompiledTemplate template = def.templateFor(team);
        List<Slot> slots = new ArrayList<>();
        if (template != null) {
            for (FortificationTemplateCompiler.OrientedBlock block
                : template.oriented(Direction.NORTH).blocks()) {
                slots.add(new Slot(block.templateIndex(), block.relativePos(), block.state(),
                    block.blockEntityNbt(), block.touch()));
            }
        }
        if (slots.isEmpty() && "structure".equals(def.placement.type)) return null;
        if (slots.isEmpty()) {
            slots.add(new Slot(-1, BlockPos.ZERO, null, null,
                FortificationTemplateCompiler.Touch.EXPLICIT_AIR));
        }
        return new Blueprint("entity".equals(def.placement.type) ? Kind.ENTITY : Kind.STRUCTURE,
            def.id, def.displayName,
            new FortificationConfig.ConstructionProfile(def.construction.requiredProgress,
                def.construction.buildPerHit, def.construction.removePerHit),
            List.copyOf(slots), def, template);
    }

    @Nullable
    private String validateCommon(ServerPlayer player) {
        if (player == null || player.isSpectator() || !player.isAlive()) return "§c当前状态无法建造。";
        if (!BattlefieldWorldManager.getInstance().isStartupReady()
            || !FortificationConfig.isFrozenReady()) {
            return "§c战场启动门禁未就绪，本次会话无法建造工事。";
        }
        GamePhase phase = GameStateManager.getInstance().getCurrentPhase();
        if (phase != GamePhase.DEPLOYING && phase != GamePhase.BATTLE) return "§c当前阶段无法建造工事。";
        if (!(player.level() instanceof ServerLevel level) || !BattlefieldContext.isActiveBattlefield(level)) {
            return "§c只能在当前战场建造工事。";
        }
        return null;
    }

    @Nullable
    private String validateSelectionRole(ServerPlayer player, Blueprint blueprint) {
        return canUse(player, blueprint.definition) ? null : "§c你没有权限建造该工事。";
    }

    private record PlacementBacking(@Nullable BastionData radio, @Nullable UUID radioId,
                                    @Nullable String error) {
    }

    private PlacementBacking validateBacking(ServerPlayer player, PreviewSession preview, BlockPos anchor) {
        Blueprint blueprint = preview.blueprint;
        ServerLevel level = player.serverLevel();
        FortificationConfig.Behavior behavior = blueprint.definition.behaviorType;
        if (behavior == FortificationConfig.Behavior.RADIO) {
            LogisticsConfig.RadioPlacementSettings cfg = LogisticsConfig.get().getRadio();
            GamePhase phase = GameStateManager.getInstance().getCurrentPhase();
            if (!cfg.allowsPhase(phase.name())) return new PlacementBacking(null, null, "§c当前阶段不能部署 Radio。" );
            BastionManager manager = BastionManager.getInstance();
            String cooldown = manager.canBuildBastion(player.getUUID(), manager.getEffectiveRadioCooldownSeconds());
            if (cooldown != null) return new PlacementBacking(null, null, cooldown);
            long pending = constructions.values().stream().filter(c ->
                c.blueprint.definition.behaviorType == FortificationConfig.Behavior.RADIO
                    && c.team.equals(preview.team)).count();
            if (manager.getActiveBastionCount(preview.team) + pending >= manager.getBastionLimitPerTeam()) {
                return new PlacementBacking(null, null, "§c本方 Radio 数量已达到上限。" );
            }
            if (manager.wouldRadioCoverageOverlap(level, anchor, preview.team)
                || pendingRadioOverlap(level, anchor, preview.team)) {
                return new PlacementBacking(null, null, "§cRadio 作用范围不能与其他 Radio 重叠。" );
            }
            if (cfg.teammateCount > 0 && countNearbyTeammates(player, preview.team, anchor, cfg.teammateRadius) < cfg.teammateCount) {
                return new PlacementBacking(null, null, "§c部署点附近队友数量不足。" );
            }
            return new PlacementBacking(null, null, null);
        }
        if (behavior == FortificationConfig.Behavior.HAB) {
            List<BastionData> radios = BastionManager.getInstance().findCoveringRadios(level, anchor, preview.team);
            if (radios.isEmpty()) return new PlacementBacking(null, null, "§c兵站必须位于己方 Radio 范围内。" );
            BastionData radio = radios.get(0);
            if (habCountForRadio(radio, preview.team) >= maxHabsFor(player)) {
                return new PlacementBacking(radio, radio.getBastionId(), "§c该 Radio 范围内兵站已达到上限。" );
            }
            if (BastionManager.getInstance().sumConstructionInCoveringRadios(level, anchor, preview.team)
                < blueprint.definition.cost.construction) {
                return new PlacementBacking(radio, radio.getBastionId(), "§c覆盖 Radio 的建材不足。" );
            }
            return new PlacementBacking(radio, radio.getBastionId(), null);
        }
        FortificationConfig.FortificationDef def = blueprint.definition;
        BastionData radio = null;
        if (def.requirements.requireRadioRange) {
            List<BastionData> radios = BastionManager.getInstance().findCoveringRadios(level, anchor, preview.team);
            if (radios.isEmpty()) return new PlacementBacking(null, null, "§c必须在己方 Radio 范围内建造。" );
            radio = radios.get(0);
        }
        if (radio == null && (def.cost.construction > 0 || def.cost.ammunition > 0)) {
            return new PlacementBacking(null, null, "§c该工事需要 Radio 库存。" );
        }
        if (radio != null && (radio.getConstructionSupplies() < def.cost.construction
            || radio.getAmmunitionSupplies() < def.cost.ammunition)) {
            return new PlacementBacking(radio, radio.getBastionId(), "§c建造资源不足。" );
        }
        return new PlacementBacking(radio, radio == null ? null : radio.getBastionId(), null);
    }

    private boolean debitBacking(ServerPlayer player, Blueprint blueprint, PlacementBacking backing,
                                 BlockPos anchor) {
        if (blueprint.definition.behaviorType == FortificationConfig.Behavior.RADIO) return true;
        if (blueprint.definition.behaviorType == FortificationConfig.Behavior.HAB) {
            int cost = blueprint.definition.cost.construction;
            return BastionManager.getInstance().tryDebitConstructionFromCoveringRadios(
                player.serverLevel(), anchor, Espetro.getPlayerTeam(player), cost);
        }
        return debit(backing.radio, blueprint.definition.cost.construction,
            blueprint.definition.cost.ammunition);
    }

    private void refundBacking(ServerPlayer player, Blueprint blueprint, PlacementBacking backing) {
        if (blueprint.definition.behaviorType == FortificationConfig.Behavior.HAB
            && backing.radio != null) {
            backing.radio.addConstructionSupplies(blueprint.definition.cost.construction,
                LogisticsConfig.get().maxConstruction);
            FobSupplyTracker.notifySupplyChanged(backing.radio);
        } else if (blueprint.definition.behaviorType != FortificationConfig.Behavior.RADIO) {
            refund(backing.radio, blueprint.definition.cost.construction,
                blueprint.definition.cost.ammunition);
        }
    }

    private boolean complete(ServerLevel level, Construction c, @Nullable Entity actor) {
        if (!completionSpaceAvailable(level, c)) return false;
        removeFoundations(level, c);
        boolean success = c.blueprint.kind == Kind.ENTITY
            ? completeEntity(level, c) : placeFinalBlocks(level, c);
        if (!success) {
            placeFoundations(level, c, 6);
            return false;
        }

        FortificationConfig.Behavior behavior = c.blueprint.definition.behaviorType;
        if (behavior == FortificationConfig.Behavior.RADIO) {
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
        } else if (behavior == FortificationConfig.Behavior.HAB) {
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
        c.structuralValue = c.blueprint.definition.durability.structuralValue;
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
        if (def.behaviorType == FortificationConfig.Behavior.AMMO_CRATE && radio != null) {
            radio.setShulkerPos(c.anchor);
            radio.setAmmoCrateBuilt(true);
        }
        if (def.behaviorType == FortificationConfig.Behavior.VEHICLE_SUPPLY_STATION) {
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
        destroy(level, c, actor, removeWorld, true, false);
    }

    private void destroy(ServerLevel level, Construction c, @Nullable Entity actor,
                         boolean removeWorld, boolean destroyBastionRecord) {
        destroy(level, c, actor, removeWorld, destroyBastionRecord, false);
    }

    private void destroy(ServerLevel level, Construction c, @Nullable Entity actor,
                         boolean removeWorld, boolean destroyBastionRecord, boolean shovelDismantle) {
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
                boolean radio = c.blueprint.definition.behaviorType == FortificationConfig.Behavior.RADIO;
                boolean friendlyShovel = shovelDismantle
                    && actor instanceof ServerPlayer player
                    && c.team.equals(normalizeTeam(Espetro.getPlayerTeam(player)));
                if (radio) {
                    BastionManager.getInstance().destroyBastionWithManpower(
                        bastion, actor,
                        RadioLossPolicy.deductManpower(true, friendlyShovel, false));
                } else {
                    BastionManager.getInstance().destroyBastion(bastion, actor);
                }
            }
        }
        PlacedFort fort = placed.remove(posKey(c.dimension, c.anchor));
        if (fort != null) {
            VehicleManager.getInstance().unregisterMappedSupplyStation(fort.mapId());
            FortificationConfig.FortificationDef def = FortificationConfig.get(fort.fortId());
            if (def != null && def.behaviorType == FortificationConfig.Behavior.AMMO_CRATE
                && fort.radioId() != null) {
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
        AABB bounds = boundsOf(c.finalSlots, c.anchor);
        spatialIndex.put(c.id, c.dimension, bounds);
    }

    private void unregisterConstruction(Construction c) {
        constructions.remove(c.id);
        for (WorldSlot slot : c.finalSlots) positionIndex.remove(posKey(c.dimension, slot.pos), c.id);
        for (UUID entityId : c.spawnedEntities) {
            entityIndex.remove(entityId);
            damageableEntityIndex.remove(entityId);
        }
        if (c.entityId != null) {
            entityIndex.remove(c.entityId);
            damageableEntityIndex.remove(c.entityId);
        }
        if (c.bastionId != null) bastionIndex.remove(c.bastionId, c.id);
        spatialIndex.remove(c.id);
    }

    private static List<WorldSlot> transform(List<Slot> slots, BlockPos anchor, Direction facing) {
        List<WorldSlot> result = new ArrayList<>(slots.size());
        for (Slot slot : slots) {
            BlockPos relative = FortificationTransform.rotate(slot.offset, facing);
            BlockState state = slot.state == null ? null
                : slot.state.rotate(FortificationTransform.rotation(facing));
            result.add(new WorldSlot(slot.templateIndex, anchor.offset(relative), state,
                slot.blockEntityNbt == null ? null : slot.blockEntityNbt.copy(), slot.touch));
        }
        return result;
    }

    private static List<BlockPos> footprint(List<WorldSlot> slots) {
        int minY = slots.stream()
            .filter(slot -> slot.touch == FortificationTemplateCompiler.Touch.BLOCK)
            .mapToInt(slot -> slot.pos.getY()).min().orElse(
                slots.stream().mapToInt(slot -> slot.pos.getY()).min().orElse(0));
        LinkedHashSet<BlockPos> result = new LinkedHashSet<>();
        for (WorldSlot slot : slots) {
            if (slot.pos.getY() == minY
                && (slot.touch == FortificationTemplateCompiler.Touch.BLOCK || result.isEmpty())) {
                result.add(slot.pos.immutable());
            }
        }
        return List.copyOf(result);
    }

    private static boolean spaceIsClear(ServerLevel level, List<WorldSlot> slots, ServerPlayer placer) {
        for (WorldSlot slot : slots) {
            BlockState state = level.getBlockState(slot.pos);
            if (!isReplaceable(state)) return false;
            if (!level.getEntities((Entity) null, new AABB(slot.pos), entity -> entity != placer
                && entity instanceof LivingEntity && entity.isAlive()).isEmpty()) return false;
        }
        return true;
    }

    private static boolean completionSpaceAvailable(ServerLevel level, Construction c) {
        for (WorldSlot slot : c.finalSlots) {
            BlockState state = level.getBlockState(slot.pos);
            boolean foundation = c.footprint.contains(slot.pos) && state.is(BastionItems.ON_BUILDING_BLOCK);
            if (!foundation && !isReplaceable(state)) return false;
            if (!level.getEntities((Entity) null, new AABB(slot.pos), entity -> entity instanceof LivingEntity
                && entity.isAlive()).isEmpty()) return false;
        }
        return true;
    }

    private static boolean isReplaceable(BlockState state) {
        return state.isAir() || state.is(Blocks.SNOW) || state.canBeReplaced();
    }

    private static AABB boundsOf(List<WorldSlot> slots, BlockPos fallback) {
        AABB box = null;
        for (WorldSlot slot : slots) {
            AABB cell = new AABB(slot.pos);
            box = box == null ? cell : box.minmax(cell);
        }
        return box == null ? new AABB(fallback) : box;
    }

    private static boolean placeFoundations(ServerLevel level, Construction c, int stage) {
        if (BastionItems.ON_BUILDING_BLOCK == null) return false;
        List<BlockSnapshot> snapshots = new ArrayList<>();
        BlockState state = BastionItems.ON_BUILDING_BLOCK.defaultBlockState()
            .setValue(OnBuildingBlock.STAGE, Math.max(0, Math.min(6, stage)));
        for (BlockPos pos : c.footprint) {
            BlockState old = level.getBlockState(pos);
            if (!isReplaceable(old)) {
                restore(level, snapshots);
                return false;
            }
            snapshots.add(snapshot(level, pos));
            if (!level.setBlock(pos, state, 3)) {
                restore(level, snapshots);
                return false;
            }
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

    private boolean placeFinalBlocks(ServerLevel level, Construction c) {
        List<BlockSnapshot> snapshots = new ArrayList<>();
        List<UUID> spawned = new ArrayList<>();
        try {
            for (WorldSlot slot : c.finalSlots) {
                if (slot.touch == FortificationTemplateCompiler.Touch.IGNORE) continue;
                snapshots.add(snapshot(level, slot.pos));
                BlockState state = slot.touch == FortificationTemplateCompiler.Touch.EXPLICIT_AIR
                    ? Blocks.AIR.defaultBlockState() : slot.state;
                if (state == null || !level.setBlock(slot.pos, state, 3)) {
                    throw new IllegalStateException("setBlock 返回 false: " + slot.pos);
                }
                if (slot.blockEntityNbt != null) {
                    BlockEntity target = level.getBlockEntity(slot.pos);
                    if (target == null) throw new IllegalStateException("方块实体未创建: " + slot.pos);
                    CompoundTag clean = slot.blockEntityNbt.copy();
                    clean.putInt("x", slot.pos.getX());
                    clean.putInt("y", slot.pos.getY());
                    clean.putInt("z", slot.pos.getZ());
                    target.load(clean);
                    target.setChanged();
                }
            }
            if (c.blueprint.template != null) {
                for (FortificationTemplateCompiler.OrientedEntity info
                    : c.blueprint.template.oriented(c.facing).entities()) {
                    CompoundTag tag = info.visualNbt().copy();
                    Entity entity = EntityType.loadEntityRecursive(tag, level, loaded -> {
                        Vec3 relative = info.relativePosition();
                        loaded.setPos(c.anchor.getX() + relative.x,
                            c.anchor.getY() + relative.y, c.anchor.getZ() + relative.z);
                        loaded.setYRot(c.facing.toYRot());
                        return loaded;
                    });
                    if (entity == null || !level.addFreshEntity(entity)) {
                        throw new IllegalStateException("结构实体生成失败: " + info.type());
                    }
                    spawned.add(entity.getUUID());
                    c.spawnedEntities.add(entity.getUUID());
                    entityIndex.put(entity.getUUID(), c.id);
                    damageableEntityIndex.put(entity.getUUID(), info.damageable());
                }
            }
            return true;
        } catch (Exception e) {
            Espetro.LOGGER.error("工事结构事务放置失败 {}，正在回滚", c.blueprint.id, e);
            for (UUID id : spawned) {
                Entity entity = level.getEntity(id);
                if (entity != null) entity.discard();
                c.spawnedEntities.remove(id);
                entityIndex.remove(id);
                damageableEntityIndex.remove(id);
            }
            restore(level, snapshots);
            return false;
        }
    }

    private void clearFinalBlocks(ServerLevel level, Construction c) {
        for (WorldSlot slot : c.finalSlots) {
            if (slot.touch == FortificationTemplateCompiler.Touch.BLOCK
                && !c.missing.contains(slot.pos) && level.hasChunkAt(slot.pos)
                && slot.state != null && level.getBlockState(slot.pos).equals(slot.state)) {
                level.setBlock(slot.pos, Blocks.AIR.defaultBlockState(), 3);
            }
        }
        for (UUID id : new ArrayList<>(c.spawnedEntities)) {
            Entity entity = level.getEntity(id);
            if (entity != null) entity.discard();
            entityIndex.remove(id);
            damageableEntityIndex.remove(id);
        }
        c.spawnedEntities.clear();
        c.entityId = null;
    }

    private boolean completeEntity(ServerLevel level, Construction c) {
        FortificationConfig.FortificationDef def = c.blueprint.definition;
        ResourceLocation id = ResourceLocation.tryParse(def.placement.entityId);
        EntityType<?> type = id == null ? null : BuiltInRegistries.ENTITY_TYPE.getOptional(id).orElse(null);
        CompoundTag tag = def.placement.sanitizedEntityNbt == null
            ? new CompoundTag() : def.placement.sanitizedEntityNbt.copy();
        if (id != null) tag.putString("id", id.toString());
        Entity entity = type == null ? null : EntityType.loadEntityRecursive(tag, level, loaded -> loaded);
        if (entity != null) {
            double[] offset = def.placement.spawnOffset;
            double ox = offset != null && offset.length == 3 ? offset[0] : 0.5D;
            double oy = offset != null && offset.length == 3 ? offset[1] : 0.0D;
            double oz = offset != null && offset.length == 3 ? offset[2] : 0.5D;
            entity.setPos(c.anchor.getX() + ox, c.anchor.getY() + oy, c.anchor.getZ() + oz);
            entity.setYRot(c.facing.toYRot());
            entity.setCustomName(Component.literal(def.displayName));
            if (def.behaviorType == FortificationConfig.Behavior.VEHICLE_SUPPLY_STATION) {
                VehicleManager.applySupplyStationMapTags(entity, c.team, "fort_" + entity.getUUID());
            } else {
                entity.addTag("espetro_fortification_" + def.id);
                entity.addTag("espetro_team_" + c.team);
            }
            if (level.addFreshEntity(entity)) {
                c.entityId = entity.getUUID();
                c.spawnedEntities.add(entity.getUUID());
                entityIndex.put(entity.getUUID(), c.id);
                damageableEntityIndex.put(entity.getUUID(), true);
                return true;
            }
        }
        c.fallbackMode = true;
        return c.blueprint.template != null && placeFinalBlocks(level, c);
    }

    private static BlockSnapshot snapshot(ServerLevel level, BlockPos pos) {
        BlockEntity blockEntity = level.getBlockEntity(pos);
        return new BlockSnapshot(pos.immutable(), level.getBlockState(pos),
            blockEntity == null ? null : blockEntity.saveWithFullMetadata());
    }

    private static void restore(ServerLevel level, List<BlockSnapshot> snapshots) {
        for (int i = snapshots.size() - 1; i >= 0; i--) {
            BlockSnapshot snapshot = snapshots.get(i);
            level.setBlock(snapshot.pos, snapshot.state, 3);
            if (snapshot.blockEntityNbt != null) {
                BlockEntity restored = level.getBlockEntity(snapshot.pos);
                if (restored != null) {
                    restored.load(snapshot.blockEntityNbt.copy());
                    restored.setChanged();
                }
            }
        }
    }

    private record BlockSnapshot(BlockPos pos, BlockState state,
                                 @Nullable CompoundTag blockEntityNbt) {
    }

    private static void restoreProportional(ServerLevel level, Construction c) {
        if (c.missing.isEmpty()) return;
        int total = c.complete ? (int) c.finalSlots.stream()
            .filter(slot -> slot.touch == FortificationTemplateCompiler.Touch.BLOCK).count()
            : c.footprint.size();
        int desired = FortificationProgressPolicy.desiredPresentParts(
            c.complete ? c.structuralValue : c.progress,
            c.complete ? c.blueprint.definition.durability.structuralValue : c.required(), total);
        int present = total - c.missing.size();
        if (desired <= present) return;
        if (c.complete) {
            for (WorldSlot slot : c.finalSlots) {
                if (present >= desired) break;
                BlockState current = level.getBlockState(slot.pos);
                if (slot.touch == FortificationTemplateCompiler.Touch.BLOCK
                    && c.missing.contains(slot.pos) && slot.state != null
                    && isReplaceable(current)
                    && level.setBlock(slot.pos, slot.state, 3)) {
                    if (slot.blockEntityNbt != null) {
                        BlockEntity blockEntity = level.getBlockEntity(slot.pos);
                        if (blockEntity != null) {
                            CompoundTag clean = slot.blockEntityNbt.copy();
                            clean.putInt("x", slot.pos.getX());
                            clean.putInt("y", slot.pos.getY());
                            clean.putInt("z", slot.pos.getZ());
                            blockEntity.load(clean);
                            blockEntity.setChanged();
                        }
                    }
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

    private boolean pendingRadioOverlap(ServerLevel level, BlockPos anchor, String team) {
        double min = BastionManager.getInstance().getMinimumRadioCenterDistance();
        String dimension = level.dimension().location().toString();
        return constructions.values().stream().anyMatch(c ->
            c.blueprint.definition.behaviorType == FortificationConfig.Behavior.RADIO
            && c.dimension.equals(dimension)
            && RadioCoveragePolicy.blocksPlacement(c.team, team, c.anchor.distSqr(anchor), min));
    }

    private int habCountForRadio(BastionData radio, String team) {
        double radiusSq = Math.pow(LogisticsConfig.get().radioBuildRadius, 2);
        int count = 0;
        for (BastionData b : BastionManager.getInstance().getAllBastions()) {
            if (b.isActive() && b.isHab() && team.equals(b.getTeam())
                && b.getPosition().distSqr(radio.getPosition()) <= radiusSq) count++;
        }
        for (Construction c : constructions.values()) {
            if (c.blueprint.definition.behaviorType == FortificationConfig.Behavior.HAB
                && radio.getBastionId().equals(c.radioId)) count++;
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
        int value = c.complete ? c.structuralValue : c.progress;
        int maximum = c.complete ? c.blueprint.definition.durability.structuralValue : c.required();
        NetworkManager.NET.send(PacketDistributor.PLAYER.with(() -> player),
            new FortificationProgressPacket(c.blueprint.displayName, value, maximum, building));
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
