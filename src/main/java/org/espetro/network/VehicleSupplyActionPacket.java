package org.espetro.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;
import org.espetro.Espetro;
import org.espetro.bastion.BastionEventHandler;
import org.espetro.bastion.BastionData;
import org.espetro.bastion.BastionManager;
import org.espetro.bastion.FobSupplyTracker;
import org.espetro.bastion.FortificationConfig;
import org.espetro.bastion.FortificationManager;
import org.espetro.logistics.LogisticsConfig;
import org.espetro.team.SpawnPointConfig;
import org.espetro.vehicle.VehicleConfig;
import org.espetro.vehicle.VehicleManager;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/** Client-to-server vehicle supply action, with all permissions recomputed server-side. */
public final class VehicleSupplyActionPacket {

    public enum Action {
        LOAD_AMMO,
        UNLOAD_AMMO,
        LOAD_CONSTRUCTION,
        UNLOAD_CONSTRUCTION,
        /** 任何载具轮盘的“补给步兵”，消耗载具弹药。 */
        RESUPPLY_INFANTRY,
        CHANGE_CLASS
    }

    private static final Map<UUID, Long> LAST_TRANSFER_TICK = new HashMap<>();

    private final UUID vehicleId;
    private final Action action;

    public VehicleSupplyActionPacket(UUID vehicleId, Action action) {
        this.vehicleId = vehicleId;
        this.action = action;
    }

    public static VehicleSupplyActionPacket read(FriendlyByteBuf buf) {
        return new VehicleSupplyActionPacket(buf.readUUID(), buf.readEnum(Action.class));
    }

    public void write(FriendlyByteBuf buf) {
        buf.writeUUID(vehicleId);
        buf.writeEnum(action);
    }

    public void handle(Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context context = supplier.get();
        context.enqueueWork(() -> handleServer(context.getSender()));
        context.setPacketHandled(true);
    }

    private void handleServer(@Nullable ServerPlayer player) {
        if (player == null) return;
        Interaction interaction = resolveInteraction(player, vehicleId);
        if (interaction == null) {
            player.displayClientMessage(Component.literal("§c请正对五格内的己方载具。"), true);
            return;
        }

        if (action == Action.RESUPPLY_INFANTRY) {
            BastionEventHandler.performVehicleResupply(player, interaction.supply(),
                LogisticsConfig.get().defaultResupplyAmmoCost);
            sendSync(player, interaction);
            return;
        }
        if (action == Action.CHANGE_CLASS) {
            if (interaction.config().canChangeClass()) {
                NetworkManager.sendVehicleClassSelect(player, interaction.factionId());
            }
            sendSync(player, interaction);
            return;
        }

        if (!allowTransferNow(player)) {
            return;
        }
        if ((action == Action.LOAD_AMMO || action == Action.UNLOAD_AMMO)
            && !interaction.canTransferAmmo()) {
            player.displayClientMessage(Component.literal("§c载具不在有效补给范围内。"), true);
            return;
        }
        if ((action == Action.LOAD_CONSTRUCTION || action == Action.UNLOAD_CONSTRUCTION)
            && !interaction.canTransferConstruction()) {
            player.displayClientMessage(Component.literal("§c该载具无法在此装卸建材。"), true);
            return;
        }

        int amount = FortificationConfig.vehicleService().transferAmount;
        switch (action) {
            case LOAD_AMMO -> loadAmmo(player, interaction, amount);
            case UNLOAD_AMMO -> unloadAmmo(player, interaction, amount);
            case LOAD_CONSTRUCTION -> loadConstruction(player, interaction, amount);
            case UNLOAD_CONSTRUCTION -> unloadConstruction(player, interaction, amount);
            default -> { }
        }
        sendSync(player, interaction);
    }

    private static boolean allowTransferNow(ServerPlayer player) {
        long now = player.serverLevel().getGameTime();
        long previous = LAST_TRANSFER_TICK.getOrDefault(player.getUUID(), Long.MIN_VALUE / 2);
        int interval = FortificationConfig.vehicleService().transferIntervalTicks;
        if (now - previous < interval) return false;
        LAST_TRANSFER_TICK.put(player.getUUID(), now);
        return true;
    }

    public static void clearPlayerRateLimit(UUID playerId) {
        LAST_TRANSFER_TICK.remove(playerId);
    }

    public static void clearRateLimits() {
        LAST_TRANSFER_TICK.clear();
    }

    private static void loadAmmo(ServerPlayer player, Interaction interaction, int chunk) {
        VehicleManager.VehicleSupplyState supply = interaction.supply();
        int wanted = Math.min(chunk, supply.getFreeSpace());
        if (wanted <= 0) {
            player.displayClientMessage(Component.literal("§e载具容量已满。"), true);
            return;
        }
        int available = interaction.mainBase() ? wanted
            : Math.min(wanted, interaction.radio().getAmmunitionSupplies());
        if (available <= 0) {
            player.displayClientMessage(Component.literal("§c补给点弹药不足。"), true);
            return;
        }
        if (!interaction.mainBase()
            && !interaction.radio().consumeAmmunitionSupplies(available)) return;
        int added = supply.addAmmo(available);
        if (added < available && !interaction.mainBase()) {
            interaction.radio().addAmmunitionSupplies(
                available - added, LogisticsConfig.get().maxAmmunition);
        }
        notifyRadio(interaction.radio());
    }

    private static void unloadAmmo(ServerPlayer player, Interaction interaction, int chunk) {
        VehicleManager.VehicleSupplyState supply = interaction.supply();
        int removable = Math.min(chunk, supply.getAmmo());
        if (!interaction.mainBase()) {
            removable = Math.min(removable,
                Math.max(0, LogisticsConfig.get().maxAmmunition
                    - interaction.radio().getAmmunitionSupplies()));
        }
        if (removable <= 0) {
            player.displayClientMessage(Component.literal("§e没有可卸载的弹药或 Radio 已满。"), true);
            return;
        }
        int removed = supply.removeAmmo(removable);
        if (!interaction.mainBase()) {
            interaction.radio().addAmmunitionSupplies(removed, LogisticsConfig.get().maxAmmunition);
        }
        notifyRadio(interaction.radio());
    }

    private static void loadConstruction(ServerPlayer player, Interaction interaction, int chunk) {
        VehicleManager.VehicleSupplyState supply = interaction.supply();
        int wanted = Math.min(chunk, supply.getFreeSpace());
        if (wanted <= 0) {
            player.displayClientMessage(Component.literal("§e载具容量已满。"), true);
            return;
        }
        int available = interaction.mainBase() ? wanted
            : Math.min(wanted, interaction.radio().getConstructionSupplies());
        if (available <= 0) {
            player.displayClientMessage(Component.literal("§c补给点建材不足。"), true);
            return;
        }
        if (!interaction.mainBase()
            && !interaction.radio().consumeConstructionSupplies(available)) return;
        int added = supply.addConstruction(available);
        if (added < available && !interaction.mainBase()) {
            interaction.radio().addConstructionSupplies(
                available - added, LogisticsConfig.get().maxConstruction);
        }
        notifyRadio(interaction.radio());
    }

    private static void unloadConstruction(ServerPlayer player, Interaction interaction, int chunk) {
        VehicleManager.VehicleSupplyState supply = interaction.supply();
        int removable = Math.min(chunk, supply.getConstruction());
        if (!interaction.mainBase()) {
            removable = Math.min(removable,
                Math.max(0, LogisticsConfig.get().maxConstruction
                    - interaction.radio().getConstructionSupplies()));
        }
        if (removable <= 0) {
            player.displayClientMessage(Component.literal("§e没有可卸载的建材或 Radio 已满。"), true);
            return;
        }
        int removed = supply.removeConstruction(removable);
        if (!interaction.mainBase()) {
            interaction.radio().addConstructionSupplies(removed, LogisticsConfig.get().maxConstruction);
        }
        notifyRadio(interaction.radio());
    }

    @Nullable
    public static VehicleSupplySyncPacket createSyncResponse(ServerPlayer player, UUID vehicleId) {
        Interaction interaction = resolveInteraction(player, vehicleId);
        return interaction == null ? null : toPacket(interaction);
    }

    private static void sendSync(ServerPlayer player, Interaction interaction) {
        NetworkManager.NET.send(PacketDistributor.PLAYER.with(() -> player), toPacket(interaction));
    }

    private static VehicleSupplySyncPacket toPacket(Interaction interaction) {
        VehicleManager.VehicleSupplyState supply = interaction.supply();
        return VehicleSupplySyncPacket.state(
            interaction.vehicleId(), supply.getAmmo(), supply.getConstruction(),
            supply.getMaxCapacity(), interaction.config().supplyVeh,
            interaction.config().fightVeh, interaction.canTransferAmmo(),
            interaction.canTransferConstruction(),
            FortificationConfig.vehicleService().transferIntervalTicks);
    }

    @Nullable
    private static Interaction resolveInteraction(ServerPlayer player, UUID vehicleId) {
        VehicleManager vehicles = VehicleManager.getInstance();
        String factionId = vehicles.getVehicleFactionId(vehicleId);
        String vehicleType = vehicles.getVehicleType(vehicleId);
        if (factionId == null || vehicleType == null
            || !vehicles.canPlayerInteractWithVehicle(player, vehicleId)) return null;

        Entity entity = vehicles.getLoadedVehicle(player, vehicleId);
        VehicleConfig.VehicleTypeConfig config =
            VehicleConfig.getVehicleConfig(factionId, vehicleType);
        String team = Espetro.getPlayerTeam(player);
        if (entity == null || config == null || team == null) return null;

        VehicleManager.VehicleSupplyState supply =
            vehicles.getOrCreateVehicleSupply(vehicleId, factionId, vehicleType);
        if (supply == null) return null;

        ServerLevel level = player.serverLevel();
        BlockPos vehiclePos = entity.blockPosition();
        boolean mainBase = isAtMainBase(level, vehiclePos, team);
        BastionData radio = null;
        if (!mainBase) {
            if (config.supplyVeh) {
                var covering = BastionManager.getInstance()
                    .findCoveringRadios(level, vehiclePos, team);
                if (!covering.isEmpty()) radio = covering.get(0);
            } else if (config.fightVeh) {
                radio = FortificationManager.getInstance()
                    .findVehicleServiceRadio(level, vehiclePos, team);
            }
        }
        boolean supplyLike = config.supplyVeh || config.fightVeh;
        boolean canTransferAmmo = supplyLike && (mainBase || radio != null);
        boolean canTransferConstruction = config.supplyVeh && canTransferAmmo;
        return new Interaction(vehicleId, factionId, config, supply, mainBase, radio,
            canTransferAmmo, canTransferConstruction);
    }

    static boolean isAtMainBase(ServerLevel level, BlockPos vehiclePos, String team) {
        SpawnPointConfig.SpawnPoint spawn = SpawnPointConfig.getSpawnPoint(team);
        if (spawn == null) return false;
        double dx = vehiclePos.getX() + 0.5 - spawn.x;
        double dz = vehiclePos.getZ() + 0.5 - spawn.z;
        double radius = FortificationConfig.vehicleService().mainBaseRadius;
        return dx * dx + dz * dz <= radius * radius;
    }

    private static void notifyRadio(@Nullable BastionData radio) {
        if (radio != null) FobSupplyTracker.notifySupplyChanged(radio);
    }

    private record Interaction(
        UUID vehicleId,
        String factionId,
        VehicleConfig.VehicleTypeConfig config,
        VehicleManager.VehicleSupplyState supply,
        boolean mainBase,
        @Nullable BastionData radio,
        boolean canTransferAmmo,
        boolean canTransferConstruction
    ) {
    }
}
