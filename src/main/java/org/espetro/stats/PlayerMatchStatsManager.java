package org.espetro.stats;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.espetro.Espetro;
import org.espetro.mapconfig.BattlefieldContext;
import org.espetro.team.GamePhase;
import org.espetro.team.GameStateManager;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Per-match kill/death stats. Cleared on next prestart, retained across disconnect.
 */
public final class PlayerMatchStatsManager {

    public static final class PlayerMatchStats {
        public final UUID uuid;
        public String name;
        public String team; // ATTACK / DEFEND / null
        public String lastTeam;
        public int kills;
        public int deaths;
        public String classId;
        /** Jar role slug, e.g. {@code rifleman}. */
        public String classIcon;
        /** Optional absolute IconImage path for disk textures. */
        public String classIconImage;
        public boolean online;

        public PlayerMatchStats(UUID uuid, String name) {
            this.uuid = uuid;
            this.name = name;
            this.online = true;
        }
    }

    private static PlayerMatchStatsManager INSTANCE;
    private final Map<UUID, PlayerMatchStats> stats = new LinkedHashMap<>();
    private boolean dirty;
    private int ticksUntilBroadcast;

    private PlayerMatchStatsManager() {
        INSTANCE = this;
    }

    public static PlayerMatchStatsManager getInstance() {
        if (INSTANCE == null) {
            INSTANCE = new PlayerMatchStatsManager();
        }
        return INSTANCE;
    }

    public static void init() {
        INSTANCE = new PlayerMatchStatsManager();
    }

    public void resetMatch() {
        stats.clear();
        dirty = true;
        broadcastIfDirty();
    }

    /** Reset and seed all current players with a single network broadcast. */
    public void resetMatch(Iterable<ServerPlayer> players) {
        stats.clear();
        if (players != null) {
            for (ServerPlayer player : players) {
                if (player != null) {
                    stats.put(player.getUUID(),
                        new PlayerMatchStats(player.getUUID(), player.getName().getString()));
                }
            }
        }
        dirty = true;
        broadcastIfDirty();
    }

    public PlayerMatchStats ensure(ServerPlayer player) {
        return stats.computeIfAbsent(player.getUUID(), id -> new PlayerMatchStats(id, player.getName().getString()));
    }

    public void onPlayerJoin(ServerPlayer player) {
        PlayerMatchStats s = ensure(player);
        s.name = player.getName().getString();
        s.online = true;
        dirty = true;
        broadcastIfDirty();
    }

    public void onPlayerLeave(UUID uuid) {
        PlayerMatchStats s = stats.get(uuid);
        if (s != null) {
            s.online = false;
            s.team = null; // cleared on disconnect per plan; lastTeam retained for board
            dirty = true;
            broadcastIfDirty();
        }
    }

    public void onTeamSelected(ServerPlayer player, String team) {
        PlayerMatchStats s = ensure(player);
        s.team = team;
        s.lastTeam = team;
        dirty = true;
        broadcastIfDirty();
    }

    public void onClassSelected(ServerPlayer player, String classId, @Nullable String icon) {
        onClassSelected(player, classId, icon, null);
    }

    public void onClassSelected(ServerPlayer player, String classId,
                                @Nullable String iconSlug, @Nullable String iconImage) {
        PlayerMatchStats s = ensure(player);
        s.classId = classId;
        s.classIcon = iconSlug;
        s.classIconImage = iconImage;
        // Legacy single-arg path may pass a disk path as "icon" — split for clients.
        if ((s.classIcon == null || s.classIcon.isBlank())
            && iconImage != null && !iconImage.isBlank()) {
            s.classIconImage = iconImage;
        } else if (s.classIcon != null
            && (s.classIcon.contains("/") || s.classIcon.contains("\\"))) {
            s.classIconImage = s.classIcon;
            s.classIcon = null;
        }
        dirty = true;
        broadcastIfDirty();
    }

    public void onClassCleared(UUID uuid) {
        PlayerMatchStats s = stats.get(uuid);
        if (s == null || (s.classId == null && s.classIcon == null && s.classIconImage == null)) {
            return;
        }
        s.classId = null;
        s.classIcon = null;
        s.classIconImage = null;
        dirty = true;
        broadcastIfDirty();
    }

    public void onPlayerDeath(ServerPlayer victim, DamageSource source) {
        GamePhase phase = GameStateManager.getInstance().getCurrentPhase();
        if (phase != GamePhase.BATTLE) {
            return;
        }
        if (!BattlefieldContext.isActiveBattlefield(victim.serverLevel())) {
            return;
        }
        PlayerMatchStats victimStats = ensure(victim);
        victimStats.deaths++;
        dirty = true;

        Entity killerEntity = source.getEntity();
        if (killerEntity instanceof ServerPlayer killer && !killer.getUUID().equals(victim.getUUID())) {
            String victimTeam = victimStats.team != null ? victimStats.team : victimStats.lastTeam;
            PlayerMatchStats killerStats = ensure(killer);
            String killerTeam = killerStats.team != null ? killerStats.team : killerStats.lastTeam;
            if (victimTeam != null && killerTeam != null && !victimTeam.equals(killerTeam)) {
                killerStats.kills++;
            }
        }
        broadcastIfDirty();
    }

    public List<PlayerMatchStats> snapshot() {
        return new ArrayList<>(stats.values());
    }

    public java.util.Optional<PlayerMatchStats> get(UUID id) {
        return java.util.Optional.ofNullable(stats.get(id));
    }

    private void broadcastIfDirty() {
        // Changes are coalesced by onServerTick. A class-selection burst from
        // 100 players must produce one roster packet, not 100 full broadcasts.
        if (ticksUntilBroadcast <= 0) {
            ticksUntilBroadcast = 20;
        }
    }

    public void markDirtyAndBroadcast() {
        dirty = true;
        broadcastIfDirty();
    }

    public void onServerTick() {
        if (!dirty) {
            ticksUntilBroadcast = 20;
            return;
        }
        if (ticksUntilBroadcast > 0) {
            ticksUntilBroadcast--;
            return;
        }
        dirty = false;
        ticksUntilBroadcast = 20;
        org.espetro.network.NetworkManager.broadcastMatchStats(this);
    }
}
