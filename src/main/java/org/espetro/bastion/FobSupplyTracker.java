package org.espetro.bastion;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.network.PacketDistributor;
import org.espetro.Espetro;
import org.espetro.logistics.LogisticsConfig;
import org.espetro.network.FobSupplySyncPacket;
import org.espetro.network.NetworkManager;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Movement- and mutation-driven Radio resource HUD subscriptions. */
@Mod.EventBusSubscriber(modid = Espetro.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class FobSupplyTracker {

    private static final int MOVEMENT_CHECK_INTERVAL = 5;
    private static final Map<UUID, BlockPos> lastPositions = new HashMap<>();
    private static final Map<UUID, String> lastSignatures = new HashMap<>();
    private static final Map<UUID, Set<UUID>> playerRadios = new HashMap<>();
    private static final Map<UUID, Set<UUID>> radioSubscribers = new HashMap<>();

    private FobSupplyTracker() {
    }

    @SubscribeEvent
    public static void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END
            || !(event.player instanceof ServerPlayer player)
            || player.tickCount % MOVEMENT_CHECK_INTERVAL != 0) return;
        BlockPos current = player.blockPosition();
        BlockPos previous = lastPositions.put(player.getUUID(), current.immutable());
        if (previous == null || !previous.equals(current)) syncPlayer(player, false);
    }

    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) syncPlayer(player, true);
    }

    @SubscribeEvent
    public static void onChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            lastPositions.remove(player.getUUID());
            syncPlayer(player, true);
        }
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        clearPlayer(event.getEntity().getUUID());
    }

    /** Notify only players currently subscribed to this Radio. */
    public static void notifySupplyChanged(BastionData radio) {
        if (radio == null || !radio.isRadio()) return;
        var server = Espetro.getServer();
        if (server == null) return;
        Set<UUID> subscribers = radioSubscribers.get(radio.getBastionId());
        if (subscribers == null || subscribers.isEmpty()) {
            // A newly placed Radio has no subscriptions yet: one bounded level-player pass.
            ServerLevel level = radio.getLevel();
            for (ServerPlayer player : level.players()) {
                if (radio.getTeam().equals(Espetro.getPlayerTeam(player))) {
                    double radius = LogisticsConfig.get().radioBuildRadius;
                    if (radio.getPosition().distSqr(player.blockPosition()) <= radius * radius) {
                        syncPlayer(player, true);
                    }
                }
            }
            return;
        }
        for (UUID playerId : new ArrayList<>(subscribers)) {
            ServerPlayer player = server.getPlayerList().getPlayer(playerId);
            if (player == null) clearPlayer(playerId);
            else syncPlayer(player, true);
        }
    }

    public static void clearPlayer(UUID playerId) {
        lastPositions.remove(playerId);
        lastSignatures.remove(playerId);
        Set<UUID> radios = playerRadios.remove(playerId);
        if (radios == null) return;
        for (UUID radioId : radios) {
            Set<UUID> subscribers = radioSubscribers.get(radioId);
            if (subscribers != null) {
                subscribers.remove(playerId);
                if (subscribers.isEmpty()) radioSubscribers.remove(radioId);
            }
        }
    }

    /** Drop all server-lifetime subscriptions between rounds and server instances. */
    public static void clearAll() {
        lastPositions.clear();
        lastSignatures.clear();
        playerRadios.clear();
        radioSubscribers.clear();
        OutpostSupplyTracker.clearAll();
    }

    private static void syncPlayer(ServerPlayer player, boolean force) {
        String team = Espetro.getPlayerTeam(player);
        if (team == null || !(player.level() instanceof ServerLevel level)) {
            updateMembership(player.getUUID(), Set.of());
            sendOut(player, force);
            return;
        }
        List<BastionData> radios = BastionManager.getInstance()
            .findCoveringRadios(level, player.blockPosition(), team);
        Set<UUID> ids = new HashSet<>();
        int construction = 0;
        int ammunition = 0;
        for (BastionData radio : radios) {
            ids.add(radio.getBastionId());
            construction += radio.getConstructionSupplies();
            ammunition += radio.getAmmunitionSupplies();
        }
        updateMembership(player.getUUID(), ids);
        if (radios.isEmpty()) {
            sendOut(player, force);
            return;
        }
        LogisticsConfig.LogisticsSettings config = LogisticsConfig.get();
        int maxConstruction = Math.max(1, config.maxConstruction);
        int maxAmmunition = Math.max(1, config.maxAmmunition);
        BastionData healthRadio = BastionManager.getInstance()
            .findNearestRadio(level, player.blockPosition(), team,
                LogisticsConfig.get().radioBuildRadius);
        int radioHealth = 0;
        int radioMaxHealth = 1;
        if (healthRadio != null) {
            radioHealth = (int) Math.ceil(healthRadio.getCoreHealth());
            radioMaxHealth = Math.max(1, BastionManager.getInstance().getArmorStandHealth());
        }
        String signature = "1|" + construction + '|' + ammunition + '|'
            + maxConstruction + '|' + maxAmmunition + '|'
            + radioHealth + '|' + radioMaxHealth;
        if (!force && signature.equals(lastSignatures.get(player.getUUID()))) return;
        lastSignatures.put(player.getUUID(), signature);
        NetworkManager.NET.send(PacketDistributor.PLAYER.with(() -> player),
            new FobSupplySyncPacket(true, construction, ammunition,
                maxConstruction, maxAmmunition, radioHealth, radioMaxHealth));
    }

    private static void updateMembership(UUID playerId, Set<UUID> next) {
        Set<UUID> previous = playerRadios.put(playerId, new HashSet<>(next));
        if (previous != null) {
            for (UUID removed : previous) {
                if (next.contains(removed)) continue;
                Set<UUID> subscribers = radioSubscribers.get(removed);
                if (subscribers != null) {
                    subscribers.remove(playerId);
                    if (subscribers.isEmpty()) radioSubscribers.remove(removed);
                }
            }
        }
        for (UUID radioId : next) {
            radioSubscribers.computeIfAbsent(radioId, ignored -> new HashSet<>()).add(playerId);
        }
    }

    private static void sendOut(ServerPlayer player, boolean force) {
        if (!force && "0".equals(lastSignatures.get(player.getUUID()))) return;
        lastSignatures.put(player.getUUID(), "0");
        NetworkManager.NET.send(PacketDistributor.PLAYER.with(() -> player),
            FobSupplySyncPacket.outOfRange());
    }
}
