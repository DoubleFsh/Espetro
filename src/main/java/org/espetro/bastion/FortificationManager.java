package org.espetro.bastion;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.espetro.Espetro;
import org.espetro.logistics.LogisticsConfig;
import org.espetro.mapconfig.BattlefieldContext;
import org.espetro.team.GamePhase;
import org.espetro.team.GameStateManager;
import org.espetro.team.SquadManager;
import org.espetro.team.VoteManager;
import org.espetro.vehicle.VehicleManager;

import javax.annotation.Nullable;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Places, indexes and removes server-authoritative fortifications. */
public final class FortificationManager {

    private static final FortificationManager INSTANCE = new FortificationManager();
    private static final double PLACE_REACH = 6.0;

    /** dimension + block position -> fortification. Accessed on the server thread. */
    private final Map<String, PlacedFort> placed = new HashMap<>();
    /** entity UUID -> position key, avoiding a full scan on entity removal. */
    private final Map<UUID, String> entityIndex = new HashMap<>();

    private FortificationManager() {
    }

    public static FortificationManager getInstance() {
        return INSTANCE;
    }

    public void reset() {
        for (PlacedFort fort : new ArrayList<>(placed.values())) {
            VehicleManager.getInstance().unregisterMappedSupplyStation(fort.mapId());
        }
        placed.clear();
        entityIndex.clear();
    }

    public record PlacedFort(
        String fortId,
        String team,
        UUID radioId,
        String dimension,
        BlockPos pos,
        @Nullable UUID entityId,
        UUID mapId
    ) {
    }

    /** @return null on success, otherwise a user-facing error. */
    @Nullable
    public String place(ServerPlayer player, String fortId) {
        if (player == null || fortId == null || fortId.isBlank()) {
            return "§c无效请求。";
        }
        GamePhase phase = GameStateManager.getInstance().getCurrentPhase();
        if (phase != GamePhase.DEPLOYING && phase != GamePhase.BATTLE) {
            return "§c当前阶段无法建造工事。";
        }
        if (!(player.level() instanceof ServerLevel level)
            || !BattlefieldContext.isActiveBattlefield(level)) {
            return "§c只能在当前战场建造工事。";
        }

        FortificationConfig.FortificationDef def = FortificationConfig.get(fortId);
        if (def == null) return "§c未知工事。";
        if (!canUse(player, def)) return "§c你没有权限建造该工事。";

        String team = normalizeTeam(Espetro.getPlayerTeam(player));
        if (team == null) return "§c无法确定队伍。";

        BlockPos placePos = raycastPlacePos(player);
        if (placePos == null) return "§c请对准可放置的位置。";

        BastionData radio = null;
        if (def.requireRadioRange) {
            List<BastionData> radios = BastionManager.getInstance()
                .findCoveringRadios(level, placePos, team);
            if (radios.isEmpty()) return "§c必须在己方 Radio 作用范围内建造。";
            radio = radios.get(0);
        }

        int constructionCost = Math.max(0, def.constructionCost);
        int ammunitionCost = Math.max(0, def.ammunitionCost);
        if (radio == null && (constructionCost > 0 || ammunitionCost > 0)) {
            return "§c该工事需要 Radio 库存。";
        }
        if (radio != null
            && (radio.getConstructionSupplies() < constructionCost
                || radio.getAmmunitionSupplies() < ammunitionCost)) {
            return "§c建造资源不足。";
        }

        if (!canPlaceAt(level, placePos)) {
            placePos = placePos.above();
            if (!canPlaceAt(level, placePos)) return "§c目标位置被占用。";
        }
        String key = posKey(level, placePos);
        if (placed.containsKey(key)) return "§c该位置已经有工事。";

        if (!debit(radio, constructionCost, ammunitionCost)) {
            return "§c资源扣除失败。";
        }

        UUID spawnedEntity = null;
        boolean placedSuccessfully = false;
        try {
            if ("entity".equalsIgnoreCase(def.placeType)) {
                spawnedEntity = spawnEntity(level, placePos, def, team);
                placedSuccessfully = spawnedEntity != null;
                if (!placedSuccessfully && def.fallbackBlockId != null) {
                    placedSuccessfully = placeBlock(level, placePos, def.fallbackBlockId);
                }
            } else {
                placedSuccessfully = placeBlock(level, placePos, def.blockId);
            }
        } catch (RuntimeException ex) {
            Espetro.LOGGER.error("放置工事 {} 失败", def.id, ex);
        }

        if (!placedSuccessfully) {
            refund(radio, constructionCost, ammunitionCost);
            return "§c工事配置无效或放置失败。";
        }

        UUID radioId = radio == null ? null : radio.getBastionId();
        UUID mapId = spawnedEntity != null ? spawnedEntity : stableMapId(level, placePos);
        PlacedFort fort = new PlacedFort(
            def.id, team, radioId, level.dimension().location().toString(),
            placePos.immutable(), spawnedEntity, mapId);
        placed.put(key, fort);
        if (spawnedEntity != null) entityIndex.put(spawnedEntity, key);

        if ("ammo_crate".equals(def.id) && radio != null) {
            radio.setShulkerPos(placePos.immutable());
            radio.setAmmoCrateBuilt(true);
        }
        if ("vehicle_supply_station".equals(def.id)) {
            VehicleManager.getInstance().registerMappedSupplyStation(
                mapId, def.displayName, team, level.dimension().location().toString(), placePos);
        }

        FobSupplyTracker.notifySupplyChanged(radio);
        return null;
    }

    private static boolean debit(@Nullable BastionData radio, int construction, int ammunition) {
        if (radio == null) return construction == 0 && ammunition == 0;
        if (construction > 0 && !radio.consumeConstructionSupplies(construction)) return false;
        if (ammunition > 0 && !radio.consumeAmmunitionSupplies(ammunition)) {
            if (construction > 0) {
                radio.addConstructionSupplies(construction, LogisticsConfig.get().maxConstruction);
            }
            return false;
        }
        return true;
    }

    private static void refund(@Nullable BastionData radio, int construction, int ammunition) {
        if (radio == null) return;
        if (construction > 0) {
            radio.addConstructionSupplies(construction, LogisticsConfig.get().maxConstruction);
        }
        if (ammunition > 0) {
            radio.addAmmunitionSupplies(ammunition, LogisticsConfig.get().maxAmmunition);
        }
        FobSupplyTracker.notifySupplyChanged(radio);
    }

    @Nullable
    private static UUID spawnEntity(ServerLevel level, BlockPos pos,
                                    FortificationConfig.FortificationDef def, String team) {
        ResourceLocation id = ResourceLocation.tryParse(def.entityId);
        if (id == null) return null;
        EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getOptional(id).orElse(null);
        if (type == null) return null;
        Entity entity = type.create(level);
        if (entity == null) return null;
        entity.setPos(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
        entity.setCustomName(Component.literal(def.displayName));
        entity.setCustomNameVisible(false);
        if (VehicleManager.isAmmoSupplyStationEntity(entity)
            || "vehicle_supply_station".equals(def.id)) {
            VehicleManager.applySupplyStationMapTags(entity, team,
                "fort_" + entity.getUUID());
        } else {
            entity.addTag("espetro_fortification_" + def.id);
            entity.addTag("espetro_team_" + team);
        }
        if (!level.addFreshEntity(entity)) {
            entity.discard();
            return null;
        }
        return entity.getUUID();
    }

    private static boolean placeBlock(ServerLevel level, BlockPos pos, @Nullable String rawId) {
        BlockState state = resolveBlock(rawId);
        return state != null && level.setBlock(pos, state, 3);
    }

    @Nullable
    private static BlockState resolveBlock(@Nullable String rawId) {
        ResourceLocation id = ResourceLocation.tryParse(rawId);
        if (id == null || !BuiltInRegistries.BLOCK.containsKey(id)) return null;
        Block block = BuiltInRegistries.BLOCK.get(id);
        return block == Blocks.AIR ? null : block.defaultBlockState();
    }

    private static boolean canPlaceAt(ServerLevel level, BlockPos pos) {
        return level.getBlockState(pos).canBeReplaced()
            && level.getEntities(null, new net.minecraft.world.phys.AABB(pos)).isEmpty();
    }

    public boolean isAmmoCrateAt(ServerLevel level, BlockPos pos, String team) {
        PlacedFort fort = placed.get(posKey(level, pos));
        if (fort != null) {
            return "ammo_crate".equals(fort.fortId())
                && team != null && team.equals(fort.team());
        }
        BastionData radio = BastionManager.getInstance().findBastionByShulkerPos(pos);
        return radio != null && team != null && team.equals(radio.getTeam())
            && radio.isAmmoCrateBuilt();
    }

    @Nullable
    public BastionData findRadioForAmmoCrate(ServerLevel level, BlockPos cratePos, String team) {
        PlacedFort fort = placed.get(posKey(level, cratePos));
        if (fort != null && "ammo_crate".equals(fort.fortId())
            && team != null && team.equals(fort.team())) {
            BastionData radio = fort.radioId() == null ? null
                : BastionManager.getInstance().getBastion(fort.radioId());
            if (radio != null && radio.isActive() && radio.isRadio()) return radio;
        }
        return null;
    }

    /** Find the active Radio backing a friendly vehicle supply station near the vehicle. */
    @Nullable
    public BastionData findVehicleServiceRadio(ServerLevel level, BlockPos vehiclePos, String team) {
        double radius = FortificationConfig.vehicleService().stationRadius;
        double radiusSq = radius * radius;
        for (PlacedFort fort : placed.values()) {
            if (!"vehicle_supply_station".equals(fort.fortId())
                || !fort.dimension().equals(level.dimension().location().toString())
                || !fort.team().equals(team)
                || fort.pos().distSqr(vehiclePos) > radiusSq) {
                continue;
            }
            BastionData radio = fort.radioId() == null ? null
                : BastionManager.getInstance().getBastion(fort.radioId());
            if (radio != null && radio.isActive() && radio.isRadio()
                && team.equals(radio.getTeam()) && radio.getLevel() == level) {
                return radio;
            }
        }
        return null;
    }

    /** Remove a block fortification and all associated indexes. */
    public void removeAt(ServerLevel level, BlockPos pos) {
        removeRecord(placed.remove(posKey(level, pos)));
    }

    /** Remove an entity fortification without scanning the complete catalogue. */
    public void removeEntity(UUID entityId) {
        String key = entityIndex.remove(entityId);
        if (key != null) removeRecord(placed.remove(key));
    }

    private void removeRecord(@Nullable PlacedFort fort) {
        if (fort == null) return;
        if (fort.entityId() != null) entityIndex.remove(fort.entityId());
        VehicleManager.getInstance().unregisterMappedSupplyStation(fort.mapId());
        if ("ammo_crate".equals(fort.fortId()) && fort.radioId() != null) {
            BastionData radio = BastionManager.getInstance().getBastion(fort.radioId());
            if (radio != null && fort.pos().equals(radio.getShulkerPos())) {
                radio.setAmmoCrateBuilt(false);
                radio.setShulkerPos(null);
                FobSupplyTracker.notifySupplyChanged(radio);
            }
        }
    }

    public static boolean canUse(ServerPlayer player, FortificationConfig.FortificationDef def) {
        UUID uuid = player.getUUID();
        List<String> roles = def.usableBy != null ? def.usableBy : List.of();
        for (String rawRole : roles) {
            String role = rawRole.toLowerCase(Locale.ROOT);
            if ("commander".equals(role) && VoteManager.getInstance().isCommander(uuid)) return true;
            if ("squad_leader".equals(role) && SquadManager.getInstance().isSquadLeader(uuid)) return true;
            if ("fireteam_leader".equals(role) && SquadManager.getInstance().isFireteamLeader(uuid)) return true;
        }
        return false;
    }

    public static boolean canOpenBuildMenu(ServerPlayer player) {
        return FortificationConfig.list().stream().anyMatch(def -> canUse(player, def));
    }

    @Nullable
    private static BlockPos raycastPlacePos(ServerPlayer player) {
        Vec3 eye = player.getEyePosition(1f);
        Vec3 end = eye.add(player.getLookAngle().scale(PLACE_REACH));
        BlockHitResult hit = player.level().clip(new ClipContext(
            eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
        return hit.getType() == HitResult.Type.MISS
            ? BlockPos.containing(end)
            : hit.getBlockPos().relative(hit.getDirection());
    }

    private static UUID stableMapId(ServerLevel level, BlockPos pos) {
        String raw = "espetro-fort|" + level.dimension().location() + "|" + pos.asLong();
        return UUID.nameUUIDFromBytes(raw.getBytes(StandardCharsets.UTF_8));
    }

    private static String posKey(ServerLevel level, BlockPos pos) {
        return level.dimension().location() + "|" + pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }

    @Nullable
    private static String normalizeTeam(@Nullable String team) {
        if (team == null) return null;
        String normalized = team.trim().toUpperCase(Locale.ROOT);
        return "ATTACK".equals(normalized) || "DEFEND".equals(normalized)
            ? normalized : null;
    }
}
