package org.espetro.team;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.espetro.Espetro;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 班组小队管理器。
 * 小队按攻防方隔离，玩家同一时间只能属于一个小队。
 */
public class SquadManager {

    public static final int NO_SQUAD = -1;
    private static final int MAX_MEMBERS = 9;
    private static final int MAX_NAME_LENGTH = 18;
    private static final Pattern FORMAT_CODE = Pattern.compile("(?i)\u00a7[0-9A-FK-OR]");

    private static SquadManager INSTANCE;

    private final Map<String, LinkedHashMap<Integer, Squad>> squadsByTeam = new HashMap<>();
    private final Map<UUID, Integer> playerSquads = new HashMap<>();
    private int nextSquadId = 1;

    private SquadManager() {
        INSTANCE = this;
    }

    public static SquadManager getInstance() {
        if (INSTANCE == null) {
            INSTANCE = new SquadManager();
        }
        return INSTANCE;
    }

    public static void init() {
        INSTANCE = new SquadManager();
    }

    public ActionResult createSquad(ServerPlayer player, String requestedName) {
        String team = Espetro.getPlayerTeam(player);
        if (team == null) {
            return ActionResult.failure(null, "你尚未加入阵营，无法创建小队。");
        }
        if (getPlayerSquadId(player.getUUID()) != NO_SQUAD) {
            return ActionResult.failure(team, "你已经在小队中，不能重复创建小队。");
        }

        String name = sanitizeName(requestedName);
        if (name.isEmpty()) {
            name = player.getName().getString() + "的小队";
        }

        Squad squad = new Squad(nextSquadId++, team, name, player.getUUID());
        squad.members.add(player.getUUID());
        squadsByTeam.computeIfAbsent(team, ignored -> new LinkedHashMap<>()).put(squad.id, squad);
        playerSquads.put(player.getUUID(), squad.id);

        return ActionResult.success(team, "已创建小队 " + name + "，你是队长。");
    }

    public ActionResult joinSquad(ServerPlayer player, int squadId) {
        String team = Espetro.getPlayerTeam(player);
        if (team == null) {
            return ActionResult.failure(null, "你尚未加入阵营，无法加入小队。");
        }

        Squad squad = getSquad(team, squadId);
        if (squad == null) {
            return ActionResult.failure(team, "目标小队不存在。");
        }

        if (squad.members.contains(player.getUUID())) {
            return ActionResult.success(team, "你已经在 " + squad.name + " 中。");
        }

        if (squad.members.size() >= MAX_MEMBERS) {
            return ActionResult.failure(team, "目标小队人数已满。");
        }

        removePlayerFromCurrentSquad(player.getUUID());
        squad.members.add(player.getUUID());
        playerSquads.put(player.getUUID(), squad.id);

        return ActionResult.success(team, "已加入小队 " + squad.name + "。");
    }

    public ActionResult leaveSquad(ServerPlayer player) {
        String team = Espetro.getPlayerTeam(player);
        String affectedTeam = removePlayerFromCurrentSquad(player.getUUID());
        if (affectedTeam != null) {
            return ActionResult.success(affectedTeam, "已退出小队。");
        }
        return ActionResult.failure(team, "你当前不在小队中。");
    }

    public ActionResult deleteSquad(ServerPlayer player, int squadId) {
        String team = Espetro.getPlayerTeam(player);
        if (team == null) {
            return ActionResult.failure(null, "你尚未加入阵营，无法删除小队。");
        }

        LinkedHashMap<Integer, Squad> squads = squadsByTeam.get(team);
        Squad squad = squads != null ? squads.get(squadId) : null;
        if (squad == null) {
            return ActionResult.failure(team, "目标小队不存在。");
        }
        if (!player.getUUID().equals(squad.leader)) {
            return ActionResult.failure(team, "只有队长可以删除小队。");
        }

        for (UUID memberUuid : new ArrayList<>(squad.members)) {
            playerSquads.remove(memberUuid);
        }
        squads.remove(squad.id);

        return ActionResult.success(team, "已删除小队 " + squad.name + "。");
    }

    /**
     * 移除玩家并返回受影响的队伍，便于调用方同步 UI。
     */
    public String removePlayer(UUID uuid) {
        String affectedTeam = removePlayerFromCurrentSquad(uuid);
        playerSquads.remove(uuid);
        return affectedTeam;
    }

    public void reset() {
        squadsByTeam.clear();
        playerSquads.clear();
        nextSquadId = 1;
    }

    public int getPlayerSquadId(UUID uuid) {
        return playerSquads.getOrDefault(uuid, NO_SQUAD);
    }

    public List<SquadSnapshot> getSquadSnapshots(String team) {
        List<SquadSnapshot> result = new ArrayList<>();
        LinkedHashMap<Integer, Squad> squads = squadsByTeam.get(team);
        if (squads == null || squads.isEmpty()) {
            return result;
        }

        MinecraftServer server = Espetro.getServer();
        for (Squad squad : squads.values()) {
            List<MemberSnapshot> members = new ArrayList<>();
            for (UUID memberUuid : squad.members) {
                String playerName = getPlayerName(server, memberUuid);
                String className = getPlayerClassName(memberUuid);
                members.add(new MemberSnapshot(memberUuid, playerName, className, memberUuid.equals(squad.leader)));
            }
            result.add(new SquadSnapshot(squad.id, squad.name, getPlayerName(server, squad.leader),
                MAX_MEMBERS, false, members));
        }
        return result;
    }

    private Squad getSquad(String team, int squadId) {
        LinkedHashMap<Integer, Squad> squads = squadsByTeam.get(team);
        return squads != null ? squads.get(squadId) : null;
    }

    private String removePlayerFromCurrentSquad(UUID uuid) {
        Integer currentSquadId = playerSquads.remove(uuid);
        String affectedTeam = null;

        for (Map.Entry<String, LinkedHashMap<Integer, Squad>> teamEntry : squadsByTeam.entrySet()) {
            Iterator<Map.Entry<Integer, Squad>> iterator = teamEntry.getValue().entrySet().iterator();
            while (iterator.hasNext()) {
                Squad squad = iterator.next().getValue();
                if (currentSquadId != null && squad.id != currentSquadId && !squad.members.contains(uuid)) {
                    continue;
                }

                if (squad.members.remove(uuid)) {
                    affectedTeam = teamEntry.getKey();
                    if (squad.members.isEmpty()) {
                        iterator.remove();
                    } else if (uuid.equals(squad.leader)) {
                        squad.leader = squad.members.get(0);
                    }
                    return affectedTeam;
                }
            }
        }

        return affectedTeam;
    }

    private String getPlayerName(MinecraftServer server, UUID uuid) {
        if (server != null) {
            ServerPlayer player = server.getPlayerList().getPlayer(uuid);
            if (player != null) {
                return player.getName().getString();
            }
        }
        return uuid.toString().substring(0, 8);
    }

    private String getPlayerClassName(UUID uuid) {
        String classId = ClassCountManager.getInstance().getPlayerClass(uuid);
        if (classId == null || classId.isEmpty()) {
            return "未选择职业";
        }

        FactionDataLoader.ClassKitData kit = FactionDataProvider.getOrCreateLoader().getClassKit(classId);
        if (kit != null && kit.name != null && !kit.name.isEmpty()) {
            return kit.name;
        }
        return classId;
    }

    private String sanitizeName(String requestedName) {
        if (requestedName == null) {
            return "";
        }
        String name = FORMAT_CODE.matcher(requestedName).replaceAll("")
            .replace('\n', ' ')
            .replace('\r', ' ')
            .trim();
        if (name.length() > MAX_NAME_LENGTH) {
            name = name.substring(0, MAX_NAME_LENGTH);
        }
        return name;
    }

    private static class Squad {
        private final int id;
        private final String team;
        private final String name;
        private UUID leader;
        private final List<UUID> members = new ArrayList<>();

        private Squad(int id, String team, String name, UUID leader) {
            this.id = id;
            this.team = team;
            this.name = name;
            this.leader = leader;
        }
    }

    public static class SquadSnapshot {
        public final int id;
        public final String name;
        public final String leaderName;
        public final int maxMembers;
        public final boolean locked;
        public final List<MemberSnapshot> members;

        public SquadSnapshot(int id, String name, String leaderName, int maxMembers, boolean locked,
                             List<MemberSnapshot> members) {
            this.id = id;
            this.name = name;
            this.leaderName = leaderName;
            this.maxMembers = maxMembers;
            this.locked = locked;
            this.members = members != null ? members : new ArrayList<>();
        }
    }

    public static class MemberSnapshot {
        public final UUID uuid;
        public final String playerName;
        public final String className;
        public final boolean leader;

        public MemberSnapshot(UUID uuid, String playerName, String className, boolean leader) {
            this.uuid = uuid;
            this.playerName = playerName;
            this.className = className;
            this.leader = leader;
        }
    }

    public static class ActionResult {
        public final boolean success;
        public final String team;
        public final String message;

        private ActionResult(boolean success, String team, String message) {
            this.success = success;
            this.team = team;
            this.message = message;
        }

        public static ActionResult success(String team, String message) {
            return new ActionResult(true, team, message);
        }

        public static ActionResult failure(String team, String message) {
            return new ActionResult(false, team, message);
        }
    }
}
