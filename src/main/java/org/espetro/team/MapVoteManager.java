package org.espetro.team;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.espetro.Espetro;
import org.espetro.dimension.BattlefieldWorldManager;
import org.espetro.mapconfig.ActiveMapConfig;
import org.espetro.mapconfig.ExternalConfigBootstrap;
import org.espetro.network.NetworkManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Global map vote (not per-team). Up to 6 candidates, 30s default, changeable votes.
 */
public final class MapVoteManager {

    private static MapVoteManager INSTANCE;

    private boolean active;
    private int tickCounter;
    private int timeoutSeconds = 30;
    private final List<ActiveMapConfig> candidates = new ArrayList<>();
    private final Map<UUID, String> votes = new HashMap<>(); // player -> mapFolder
    private ActiveMapConfig winner;
    private boolean voteStateDirty;
    private static final int VOTE_BROADCAST_INTERVAL_TICKS = 4;

    private MapVoteManager() {
        INSTANCE = this;
    }

    public static MapVoteManager getInstance() {
        if (INSTANCE == null) {
            INSTANCE = new MapVoteManager();
        }
        return INSTANCE;
    }

    public static void init() {
        INSTANCE = new MapVoteManager();
    }

    public boolean isActive() {
        return active;
    }

    public ActiveMapConfig getWinner() {
        return winner;
    }

    public List<ActiveMapConfig> getCandidates() {
        return Collections.unmodifiableList(candidates);
    }

    public Map<String, Integer> getTally() {
        Map<String, Integer> tally = new LinkedHashMap<>();
        for (ActiveMapConfig c : candidates) {
            tally.put(c.mapFolder, 0);
        }
        for (String map : votes.values()) {
            tally.computeIfPresent(map, (k, v) -> v + 1);
        }
        return tally;
    }

    public String getPlayerVote(UUID uuid) {
        return votes.get(uuid);
    }

    public int getRemainingSeconds() {
        if (!active) return 0;
        return Math.max(0, timeoutSeconds - tickCounter / 20);
    }

    public long getEndGameTime(MinecraftServer server) {
        if (!active || server == null) return 0L;
        return server.overworld().getGameTime() + getRemainingSeconds() * 20L;
    }

    public boolean start(MinecraftServer server) {
        if (!BattlefieldWorldManager.getInstance().isStartupReady()) {
            Espetro.LOGGER.error("战场启动门禁未 READY，拒绝开始地图投票: {}",
                BattlefieldWorldManager.getInstance().getStartupPreparation());
            return false;
        }
        List<ActiveMapConfig> pool = new ArrayList<>(ExternalConfigBootstrap.getUsableMaps());
        pool.removeIf(map -> {
            boolean prepared = BattlefieldWorldManager.getInstance().isPrepared(map);
            if (!prepared) {
                Espetro.LOGGER.warn("地图 {} 已从投票池排除：启动阶段未成功准备维度文件",
                    map.displayName);
            }
            return !prepared;
        });
        FactionDataLoader formations = FactionDataProvider.getOrCreateLoader();
        pool.removeIf(map -> {
            boolean playable = formations.isMapPlayable(map);
            if (!playable) {
                Espetro.LOGGER.warn("地图 {} 已从投票池排除：没有至少两个 faction_id 不同的兼容编制",
                    map.displayName);
            }
            return !playable;
        });
        if (pool.isEmpty()) {
            Espetro.LOGGER.error("无可用地图，无法开始地图投票");
            return false;
        }
        Collections.shuffle(pool, ThreadLocalRandom.current());
        int n = Math.min(6, pool.size());
        candidates.clear();
        candidates.addAll(pool.subList(0, n));
        votes.clear();
        winner = null;
        timeoutSeconds = ExternalConfigBootstrap.getMapVoteSeconds();
        tickCounter = 0;
        active = true;
        voteStateDirty = false;
        Espetro.LOGGER.info("地图投票开始: {} 个候选, {} 秒", candidates.size(), timeoutSeconds);
        NetworkManager.broadcastMapVoteState(this);
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            NetworkManager.sendOpenMapVoteScreen(player);
        }
        return true;
    }

    public boolean castVote(ServerPlayer player, String mapFolder) {
        if (!active) return false;
        boolean ok = candidates.stream().anyMatch(c -> c.mapFolder.equals(mapFolder));
        if (!ok) return false;
        votes.put(player.getUUID(), mapFolder);
        voteStateDirty = true;
        return true;
    }

    public void onPlayerLeave(UUID playerId) {
        if (playerId != null && votes.remove(playerId) != null && active) {
            voteStateDirty = true;
        }
    }

    public void onServerTick(MinecraftServer server) {
        if (!active) return;
        tickCounter++;
        if (voteStateDirty && tickCounter % VOTE_BROADCAST_INTERVAL_TICKS == 0) {
            voteStateDirty = false;
            NetworkManager.broadcastMapVoteState(this);
        }
        if (tickCounter >= timeoutSeconds * 20) {
            finish(server);
        }
    }

    public void finish(MinecraftServer server) {
        if (!active) return;
        active = false;
        voteStateDirty = false;
        Map<String, Integer> tally = getTally();
        int best = -1;
        List<ActiveMapConfig> tied = new ArrayList<>();
        for (ActiveMapConfig c : candidates) {
            int v = tally.getOrDefault(c.mapFolder, 0);
            if (v > best) {
                best = v;
                tied.clear();
                tied.add(c);
            } else if (v == best) {
                tied.add(c);
            }
        }
        if (tied.isEmpty()) {
            winner = candidates.get(ThreadLocalRandom.current().nextInt(candidates.size()));
        } else if (best <= 0) {
            // no votes
            winner = candidates.get(ThreadLocalRandom.current().nextInt(candidates.size()));
        } else if (tied.size() == 1) {
            winner = tied.get(0);
        } else {
            winner = tied.get(ThreadLocalRandom.current().nextInt(tied.size()));
        }
        Espetro.LOGGER.info("地图投票结束: 胜出 {} ({}), 票数统计 {}",
            winner.displayName, winner.mapFolder, tally);
        NetworkManager.broadcastMapVoteState(this);
        GameStateManager.getInstance().onMapVoteFinished(winner);
    }

    public void syncToPlayer(ServerPlayer player) {
        NetworkManager.sendMapVoteState(player, this);
        if (active) {
            NetworkManager.sendOpenMapVoteScreen(player);
        }
    }

    public void reset() {
        active = false;
        tickCounter = 0;
        candidates.clear();
        votes.clear();
        winner = null;
        voteStateDirty = false;
    }

    /** Pure logic for unit tests. */
    public static ActiveMapConfig resolveWinnerForTest(List<ActiveMapConfig> candidates,
                                                       Map<UUID, String> votes,
                                                       java.util.Random random) {
        Map<String, Integer> tally = new LinkedHashMap<>();
        for (ActiveMapConfig c : candidates) {
            tally.put(c.mapFolder, 0);
        }
        for (String map : votes.values()) {
            tally.computeIfPresent(map, (k, v) -> v + 1);
        }
        int best = -1;
        List<ActiveMapConfig> tied = new ArrayList<>();
        for (ActiveMapConfig c : candidates) {
            int v = tally.getOrDefault(c.mapFolder, 0);
            if (v > best) {
                best = v;
                tied.clear();
                tied.add(c);
            } else if (v == best) {
                tied.add(c);
            }
        }
        if (tied.isEmpty() || best <= 0) {
            return candidates.get(random.nextInt(candidates.size()));
        }
        if (tied.size() == 1) return tied.get(0);
        return tied.get(random.nextInt(tied.size()));
    }
}
