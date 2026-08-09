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
import org.espetro.network.NetworkManager;
import org.espetro.network.OutpostSupplySyncPacket;
import org.espetro.team.OutpostManager;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** 靠近前哨基地时的 Radio 血量/补给/兵站状态 HUD 同步。 */
@Mod.EventBusSubscriber(modid = Espetro.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class OutpostSupplyTracker {

    private static final int MOVEMENT_CHECK_INTERVAL = 5;
    private static final double OUTPOST_RANGE = 6.0;
    private static final Map<UUID, String> lastSignatures = new HashMap<>();

    private OutpostSupplyTracker() {
    }

    @SubscribeEvent
    public static void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END
            || !(event.player instanceof ServerPlayer player)
            || player.tickCount % MOVEMENT_CHECK_INTERVAL != 0) return;
        syncPlayer(player, false);
    }

    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) syncPlayer(player, true);
    }

    @SubscribeEvent
    public static void onChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) syncPlayer(player, true);
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        lastSignatures.remove(event.getEntity().getUUID());
    }

    public static void clearAll() {
        lastSignatures.clear();
    }

    private static void syncPlayer(ServerPlayer player, boolean force) {
        String team = Espetro.getPlayerTeam(player);
        if (team == null || !"DEFEND".equals(team)
            || !(player.level() instanceof ServerLevel level)
            || !OutpostManager.getInstance().isAvailable()) {
            sendOut(player, force);
            return;
        }

        OutpostManager.Outpost outpost = findNearestOutpost(player);
        if (outpost == null) {
            sendOut(player, force);
            return;
        }

        BlockPos outpostPos = BlockPos.containing(outpost.x, outpost.y, outpost.z);
        double radioRadius = LogisticsConfig.get().radioBuildRadius;
        BastionData radio = BastionManager.getInstance()
            .findNearestRadio(level, outpostPos, team, radioRadius);
        if (radio == null) {
            sendOut(player, force);
            return;
        }

        int health = (int) Math.ceil(radio.getCoreHealth());
        int maxHealth = Math.max(1, BastionManager.getInstance().getArmorStandHealth());
        int ammunition = radio.getAmmunitionSupplies();
        int construction = radio.getConstructionSupplies();
        boolean habEnabled = hasEnabledHab(level, radio, team);

        String signature = "1|" + health + '|' + maxHealth + '|'
            + ammunition + '|' + construction + '|' + habEnabled;
        if (!force && signature.equals(lastSignatures.get(player.getUUID()))) return;
        lastSignatures.put(player.getUUID(), signature);
        NetworkManager.NET.send(PacketDistributor.PLAYER.with(() -> player),
            new OutpostSupplySyncPacket(true, health, maxHealth,
                ammunition, construction, habEnabled));
    }

    @Nullable
    private static OutpostManager.Outpost findNearestOutpost(ServerPlayer player) {
        BlockPos playerPos = player.blockPosition();
        double best = OUTPOST_RANGE * OUTPOST_RANGE;
        OutpostManager.Outpost nearest = null;
        for (OutpostManager.Outpost outpost : OutpostManager.getInstance().getOutposts()) {
            BlockPos pos = BlockPos.containing(outpost.x, outpost.y, outpost.z);
            double distance = playerPos.distSqr(pos);
            if (distance <= best) {
                best = distance;
                nearest = outpost;
            }
        }
        return nearest;
    }

    private static boolean hasEnabledHab(ServerLevel level, BastionData radio, String team) {
        double radius = LogisticsConfig.get().radioBuildRadius;
        double radiusSq = radius * radius;
        for (BastionData bastion : BastionManager.getInstance().getAllBastions()) {
            if (!bastion.isActive() || !bastion.isHab()
                || !team.equals(bastion.getTeam()) || bastion.getLevel() != level) {
                continue;
            }
            if (radio.getPosition().distSqr(bastion.getPosition()) > radiusSq) {
                continue;
            }
            if (BastionManager.getInstance().isHabOperational(bastion, false)) {
                return true;
            }
        }
        return false;
    }

    private static void sendOut(ServerPlayer player, boolean force) {
        if (!force && "0".equals(lastSignatures.get(player.getUUID()))) return;
        lastSignatures.put(player.getUUID(), "0");
        NetworkManager.NET.send(PacketDistributor.PLAYER.with(() -> player),
            OutpostSupplySyncPacket.outOfRange());
    }
}
