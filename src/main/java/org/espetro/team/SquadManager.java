package org.espetro.team;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.espetro.Espetro;
import org.espetro.governance.CommanderGovernanceManager;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
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
 * <p>
 * 每个小队内部分 A/B/C 三个火力组，各组不设独立人数上限。
 * 新成员默认进入 A 组，由小队长手动调整；组内首个进入者成为该火力组组长；
 * 小队长强制属于 A 组并任 A 组长。
 */
public class SquadManager {

    public static final int NO_SQUAD = -1;
    private static final int MAX_MEMBERS = 9;
    private static final int MAX_NAME_LENGTH = 18;
    private static final Pattern FORMAT_CODE = Pattern.compile("(?i)\u00a7[0-9A-FK-OR]");

    private static SquadManager INSTANCE;

    private final Map<String, LinkedHashMap<Integer, Squad>> squadsByTeam = new HashMap<>();
    private final Map<UUID, Integer> playerSquads = new HashMap<>();
    private final Map<String, Integer> nextDisplayIdByTeam = new HashMap<>();
    /** Internal, server-wide ID used by packets and lookups. */
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
        return createSquad(player, requestedName, org.espetro.mapconfig.SquadTypesSnapshot.NONE_ID);
    }

    public ActionResult createSquad(ServerPlayer player, String requestedName, String categoryId) {
        String team = Espetro.getPlayerTeam(player);
        if (team == null) {
            return ActionResult.failure(null, "你尚未加入阵营，无法创建小队。");
        }
        if (getPlayerSquadId(player.getUUID()) != NO_SQUAD) {
            return ActionResult.failure(team, "你已经在小队中，不能重复创建小队。");
        }

        String name = sanitizeName(requestedName);

        String catId = categoryId == null || categoryId.isBlank()
            ? org.espetro.mapconfig.SquadTypesSnapshot.NONE_ID
            : categoryId;
        String catDisplay = org.espetro.mapconfig.SquadTypesSnapshot.NONE_DISPLAY;
        var active = org.espetro.mapconfig.BattlefieldContext.getOrNull();
        if (active != null && active.squadTypes != null) {
            var cat = active.squadTypes.find(catId);
            if (cat != null) {
                catId = cat.id();
                catDisplay = cat.displayName();
            } else if (!org.espetro.mapconfig.SquadTypesSnapshot.NONE_ID.equals(catId)) {
                return ActionResult.failure(team, "无效的小队类别。");
            }
        } else {
            var defaults = org.espetro.mapconfig.SquadTypesSnapshot.defaults().find(catId);
            if (defaults != null) {
                catId = defaults.id();
                catDisplay = defaults.displayName();
            }
        }

        int displayId = claimNextDisplayId(team);
        if (name.isEmpty()) {
            name = "小队" + displayId;
        }
        Squad squad = new Squad(nextSquadId++, displayId, team, name, player.getUUID());
        squad.categoryId = catId;
        squad.categoryDisplayName = catDisplay;
        addMemberToFireteam(squad, player.getUUID(), Fireteam.A, true);
        squadsByTeam.computeIfAbsent(team, ignored -> new LinkedHashMap<>()).put(squad.id, squad);
        playerSquads.put(player.getUUID(), squad.id);

        return ActionResult.success(team, "已创建小队 " + name + "，你是队长（火力组 A 组长）。");
    }

    /**
     * Force-add target into leader's squad (no confirmation). Server re-validates all rules.
     */
    public ActionResult forceJoinSquad(ServerPlayer leader, UUID targetUuid) {
        if (!isSquadLeader(leader.getUUID())) {
            return ActionResult.failure(Espetro.getPlayerTeam(leader), "只有队长可以拉人入队。");
        }
        String team = Espetro.getPlayerTeam(leader);
        if (team == null) {
            return ActionResult.failure(null, "你尚未加入阵营。");
        }
        int squadId = getPlayerSquadId(leader.getUUID());
        Squad squad = getSquad(team, squadId);
        if (squad == null) {
            return ActionResult.failure(team, "小队不存在。");
        }
        MinecraftServer server = Espetro.getServer();
        if (server == null) {
            return ActionResult.failure(team, "服务器不可用。");
        }
        ServerPlayer target = server.getPlayerList().getPlayer(targetUuid);
        if (target == null) {
            return ActionResult.failure(team, "目标玩家不在线。");
        }
        if (!team.equals(Espetro.getPlayerTeam(target))) {
            return ActionResult.failure(team, "只能拉同阵营玩家。");
        }
        if (getPlayerSquadId(targetUuid) != NO_SQUAD) {
            return ActionResult.failure(team, "目标已在小队中。");
        }
        if (squad.members.size() >= MAX_MEMBERS) {
            return ActionResult.failure(team, "小队人数已满。");
        }
        if (squad.locked) {
            return ActionResult.failure(team, "该小队已锁定，无法加入。");
        }
        addMemberToFireteam(squad, targetUuid, Fireteam.A, false);
        playerSquads.put(targetUuid, squad.id);
        return ActionResult.success(team, "已将 " + target.getName().getString()
            + " 拉进小队（火力组 A）。");
    }

    /**
     * Kick a member from leader's squad.
     */
    public ActionResult kickMember(ServerPlayer leader, UUID targetUuid) {
        if (!isSquadLeader(leader.getUUID())) {
            return ActionResult.failure(Espetro.getPlayerTeam(leader), "只有队长可以踢人。");
        }
        if (leader.getUUID().equals(targetUuid)) {
            return ActionResult.failure(Espetro.getPlayerTeam(leader), "不能踢出自己。");
        }
        String team = Espetro.getPlayerTeam(leader);
        int squadId = getPlayerSquadId(leader.getUUID());
        if (squadId == NO_SQUAD || getPlayerSquadId(targetUuid) != squadId) {
            return ActionResult.failure(team, "目标不在你的小队中。");
        }
        String affected = removePlayerFromCurrentSquad(targetUuid);
        MinecraftServer server = Espetro.getServer();
        if (server != null) {
            ServerPlayer target = server.getPlayerList().getPlayer(targetUuid);
            if (target != null) {
                ClassCountManager.getInstance().onPlayerLeftSquad(target);
            } else {
                ClassCountManager.getInstance().onPlayerLeftSquadOffline(targetUuid);
            }
        } else {
            ClassCountManager.getInstance().onPlayerLeftSquadOffline(targetUuid);
        }
        return ActionResult.success(affected != null ? affected : team, "已踢出队员。");
    }

    public long getLeaderSinceTick(UUID uuid) {
        Integer squadId = playerSquads.get(uuid);
        if (squadId == null) return Long.MAX_VALUE;
        for (LinkedHashMap<Integer, Squad> squads : squadsByTeam.values()) {
            Squad squad = squads.get(squadId);
            if (squad != null && uuid.equals(squad.leader)) {
                return squad.leaderSinceTick;
            }
        }
        return Long.MAX_VALUE;
    }

    public String getPlayerCategoryId(UUID uuid) {
        Integer squadId = playerSquads.get(uuid);
        if (squadId == null) return null;
        for (LinkedHashMap<Integer, Squad> squads : squadsByTeam.values()) {
            Squad squad = squads.get(squadId);
            if (squad != null) {
                return squad.categoryId;
            }
        }
        return null;
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

        if (squad.locked) {
            return ActionResult.failure(team, "该小队已锁定，无法加入。");
        }

        int previousSquadId = getPlayerSquadId(player.getUUID());
        removePlayerFromCurrentSquad(player.getUUID());
        if (previousSquadId != NO_SQUAD) {
            // 切换小队也属于离队：职业记录与装备必须先撤销。
            ClassCountManager.getInstance().onPlayerLeftSquad(player);
        }
        addMemberToFireteam(squad, player.getUUID(), Fireteam.A, false);
        playerSquads.put(player.getUUID(), squad.id);

        return ActionResult.success(team, "已加入小队 " + squad.name
            + "（火力组 A）。");
    }

    public ActionResult leaveSquad(ServerPlayer player) {
        String team = Espetro.getPlayerTeam(player);
        String affectedTeam = removePlayerFromCurrentSquad(player.getUUID());
        if (affectedTeam != null) {
            ClassCountManager.getInstance().onPlayerLeftSquad(player);
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

        List<UUID> formerMembers = new ArrayList<>(squad.members);
        CommanderGovernanceManager.getInstance().onSquadLeaderLost(squad.leader);
        for (UUID memberUuid : formerMembers) {
            playerSquads.remove(memberUuid);
        }
        squads.remove(squad.id);

        // 删除小队等同于所有成员离队：职业记录与装备需清除。
        MinecraftServer server = Espetro.getServer();
        ClassCountManager countManager = ClassCountManager.getInstance();
        for (UUID memberUuid : formerMembers) {
            if (server != null) {
                ServerPlayer member = server.getPlayerList().getPlayer(memberUuid);
                if (member != null) {
                    countManager.onPlayerLeftSquad(member);
                    continue;
                }
            }
            countManager.onPlayerLeftSquadOffline(memberUuid);
        }

        return ActionResult.success(team, "已删除小队 " + squad.name + "。");
    }

    /**
     * 移除玩家并返回受影响的队伍，便于调用方同步 UI。
     */
    public String removePlayer(UUID uuid) {
        String affectedTeam = removePlayerFromCurrentSquad(uuid);
        playerSquads.remove(uuid);
        MinecraftServer server = Espetro.getServer();
        if (server != null) {
            ServerPlayer player = server.getPlayerList().getPlayer(uuid);
            if (player != null) {
                ClassCountManager.getInstance().onPlayerLeftSquad(player);
            } else {
                ClassCountManager.getInstance().onPlayerLeftSquadOffline(uuid);
            }
        } else {
            ClassCountManager.getInstance().onPlayerLeftSquadOffline(uuid);
        }
        return affectedTeam;
    }

    public void reset() {
        squadsByTeam.clear();
        playerSquads.clear();
        nextDisplayIdByTeam.clear();
        nextSquadId = 1;
    }

    public int getPlayerSquadId(UUID uuid) {
        return playerSquads.getOrDefault(uuid, NO_SQUAD);
    }

    /**
     * 返回指定队伍下某小队的成员 UUID 列表（拷贝）；小队不存在时返回空列表。
     */
    public List<UUID> getSquadMemberUuids(String team, int squadId) {
        Squad squad = getSquad(team, squadId);
        if (squad == null) {
            return List.of();
        }
        return new ArrayList<>(squad.members);
    }

    public boolean isSquadLeader(UUID uuid) {
        Integer squadId = playerSquads.get(uuid);
        if (squadId == null) {
            return false;
        }

        for (LinkedHashMap<Integer, Squad> squads : squadsByTeam.values()) {
            Squad squad = squads.get(squadId);
            if (squad != null) {
                return uuid.equals(squad.leader);
            }
        }
        return false;
    }

    /** 是否为所在火力组组长（不含“仅小队长”语义；小队长同时是 A 组长）。 */
    public boolean isFireteamLeader(UUID uuid) {
        Squad squad = getSquadOf(uuid);
        if (squad == null) {
            return false;
        }
        Fireteam ft = squad.memberFireteam.get(uuid);
        return ft != null && uuid.equals(squad.fireteamLeaders.get(ft));
    }

    /** 小队长锁定小队，锁定后其他人无法加入（队员仍可自行退出）。 */
    public ActionResult lockSquad(ServerPlayer player) {
        String team = Espetro.getPlayerTeam(player);
        if (team == null) {
            return ActionResult.failure(null, "你尚未加入阵营。");
        }
        UUID uuid = player.getUUID();
        if (!isSquadLeader(uuid)) {
            return ActionResult.failure(team, "只有小队长可以锁定小队。");
        }
        Squad squad = getSquadOf(uuid);
        if (squad == null) {
            return ActionResult.failure(team, "你不在小队中。");
        }
        if (squad.locked) {
            return ActionResult.failure(team, "小队已经处于锁定状态。");
        }
        squad.locked = true;
        return ActionResult.success(team, "小队已锁定，其他人无法加入。");
    }

    /** 小队长解锁小队，允许其他人加入。 */
    public ActionResult unlockSquad(ServerPlayer player) {
        String team = Espetro.getPlayerTeam(player);
        if (team == null) {
            return ActionResult.failure(null, "你尚未加入阵营。");
        }
        UUID uuid = player.getUUID();
        if (!isSquadLeader(uuid)) {
            return ActionResult.failure(team, "只有小队长可以解锁小队。");
        }
        Squad squad = getSquadOf(uuid);
        if (squad == null) {
            return ActionResult.failure(team, "你不在小队中。");
        }
        if (!squad.locked) {
            return ActionResult.failure(team, "小队当前未锁定。");
        }
        squad.locked = false;
        return ActionResult.success(team, "小队已解锁，其他人可以加入。");
    }

    public boolean isSquadLocked(String team, int squadId) {
        Squad squad = getSquad(team, squadId);
        return squad != null && squad.locked;
    }

    public Fireteam getPlayerFireteam(UUID uuid) {
        Squad squad = getSquadOf(uuid);
        if (squad == null) {
            return null;
        }
        return squad.memberFireteam.get(uuid);
    }

    /**
     * 小队长将队长职位移交给同小队成员。
     * 新队长进入 A 组并任 A 组长；原队长不再是小队长，也不再是 A 组长。
     */
    public ActionResult transferSquadLeader(ServerPlayer actor, UUID targetUuid) {
        String team = Espetro.getPlayerTeam(actor);
        if (team == null) {
            return ActionResult.failure(null, "你尚未加入阵营。");
        }
        if (!isSquadLeader(actor.getUUID())) {
            return ActionResult.failure(team, "只有小队长可以转移队长。");
        }
        if (actor.getUUID().equals(targetUuid)) {
            return ActionResult.failure(team, "不能转移给自己。");
        }
        Squad squad = getSquad(team, getPlayerSquadId(actor.getUUID()));
        if (squad == null || !squad.members.contains(targetUuid)) {
            return ActionResult.failure(team, "目标不在你的小队中。");
        }

        UUID oldLeader = squad.leader;
        // 新队长必须进入 A；旧队长和其他成员均保留现有分组。
        moveMemberToFireteam(squad, targetUuid, Fireteam.A);
        squad.leader = targetUuid;
        squad.leaderSinceTick = currentGameTime();
        // A 组长固定为新队长；旧队长不再保留 A 组长权限。
        squad.fireteamLeaders.put(Fireteam.A, targetUuid);
        // 治理：弹劾发起者等若失去队长资格需清理
        if (oldLeader != null && !oldLeader.equals(targetUuid)) {
            CommanderGovernanceManager.getInstance().onSquadLeaderLost(oldLeader);
        }
        return ActionResult.success(team, "已将队长转移给 "
            + getPlayerName(Espetro.getServer(), targetUuid) + "。");
    }

    /**
     * 火力组组长将组长职位移交给同组队员。
     */
    public ActionResult transferFireteamLeader(ServerPlayer actor, UUID targetUuid) {
        String team = Espetro.getPlayerTeam(actor);
        if (team == null) {
            return ActionResult.failure(null, "你尚未加入阵营。");
        }
        Squad squad = getSquadOf(actor.getUUID());
        if (squad == null) {
            return ActionResult.failure(team, "你不在小队中。");
        }
        Fireteam actorFt = squad.memberFireteam.get(actor.getUUID());
        if (actorFt == null || !actor.getUUID().equals(squad.fireteamLeaders.get(actorFt))) {
            return ActionResult.failure(team, "只有火力组组长可以转移组长。");
        }
        if (actor.getUUID().equals(squad.leader)) {
            return ActionResult.failure(team, "小队长需要使用“转移队长”。");
        }
        if (actor.getUUID().equals(targetUuid)) {
            return ActionResult.failure(team, "不能转移给自己。");
        }
        if (!squad.members.contains(targetUuid)
            || squad.memberFireteam.get(targetUuid) != actorFt) {
            return ActionResult.failure(team, "目标不在你的火力组中。");
        }
        squad.fireteamLeaders.put(actorFt, targetUuid);
        return ActionResult.success(team, "已将火力组 " + actorFt.label() + " 组长转移给 "
            + getPlayerName(Espetro.getServer(), targetUuid) + "。");
    }

    /**
     * 小队长直接任命 B/C 火力组组长。目标不在指定组时先移入该组，
     * 指定组的原组长保留组员身份并立即失去组长权限。
     */
    public ActionResult appointFireteamLeader(ServerPlayer actor, UUID targetUuid, Fireteam fireteam) {
        String team = Espetro.getPlayerTeam(actor);
        if (team == null) {
            return ActionResult.failure(null, "你尚未加入阵营。");
        }
        if (!isSquadLeader(actor.getUUID())) {
            return ActionResult.failure(team, "只有小队长可以指认火力组长。");
        }
        if (fireteam == null || fireteam == Fireteam.A) {
            return ActionResult.failure(team, "只能指认火力组 B 或 C 的组长。");
        }
        Squad squad = getSquad(team, getPlayerSquadId(actor.getUUID()));
        if (squad == null || !squad.members.contains(targetUuid)) {
            return ActionResult.failure(team, "目标不在你的小队中。");
        }
        if (targetUuid.equals(squad.leader)) {
            return ActionResult.failure(team, "小队长必须担任火力组 A 组长。");
        }
        if (squad.memberFireteam.get(targetUuid) == fireteam
            && targetUuid.equals(squad.fireteamLeaders.get(fireteam))) {
            return ActionResult.failure(team, "该玩家已经是火力组 " + fireteam.label() + " 组长。");
        }

        moveMemberToFireteam(squad, targetUuid, fireteam);
        squad.fireteamLeaders.put(fireteam, targetUuid);
        return ActionResult.success(team, "已指认 "
            + getPlayerName(Espetro.getServer(), targetUuid)
            + " 为火力组 " + fireteam.label() + " 组长。");
    }

    /**
     * 小队长将队员分配到指定火力组。
     */
    public ActionResult assignFireteam(ServerPlayer actor, UUID targetUuid, Fireteam fireteam) {
        String team = Espetro.getPlayerTeam(actor);
        if (team == null) {
            return ActionResult.failure(null, "你尚未加入阵营。");
        }
        if (!isSquadLeader(actor.getUUID())) {
            return ActionResult.failure(team, "只有小队长可以分配火力组。");
        }
        if (fireteam == null) {
            return ActionResult.failure(team, "无效的火力组。");
        }
        Squad squad = getSquad(team, getPlayerSquadId(actor.getUUID()));
        if (squad == null || !squad.members.contains(targetUuid)) {
            return ActionResult.failure(team, "目标不在你的小队中。");
        }
        Fireteam current = squad.memberFireteam.get(targetUuid);
        if (current == fireteam) {
            return ActionResult.failure(team, "该玩家已在火力组 " + fireteam.label() + "。");
        }
        // 小队长本人始终应在 A：禁止把队长移出 A
        if (targetUuid.equals(squad.leader) && fireteam != Fireteam.A) {
            return ActionResult.failure(team, "小队长必须留在火力组 A。");
        }
        moveMemberToFireteam(squad, targetUuid, fireteam);
        // 队长若被“分配到 A”（已在 A 则前面已失败），保持 A 组长为队长
        if (targetUuid.equals(squad.leader)) {
            squad.fireteamLeaders.put(Fireteam.A, squad.leader);
        }
        return ActionResult.success(team, "已将玩家分配到火力组 " + fireteam.label() + "。");
    }

    public boolean hasSquad(String team, int squadId) {
        LinkedHashMap<Integer, Squad> squads = squadsByTeam.get(team);
        return squads != null && squads.containsKey(squadId);
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
            // 按火力组 A→B→C 聚集；组内保持入队顺序
            List<UUID> ordered = orderedMembersByFireteam(squad);
            for (UUID memberUuid : ordered) {
                String playerName = getPlayerName(server, memberUuid);
                String className = getPlayerClassName(memberUuid);
                Fireteam ft = squad.memberFireteam.getOrDefault(memberUuid, Fireteam.A);
                boolean ftLead = memberUuid.equals(squad.fireteamLeaders.get(ft));
                members.add(new MemberSnapshot(
                    memberUuid, playerName, className,
                    memberUuid.equals(squad.leader), ft, ftLead));
            }
            result.add(new SquadSnapshot(squad.id, squad.displayId, squad.name, getPlayerName(server, squad.leader),
                squad.leader, MAX_MEMBERS, squad.locked, squad.categoryId, squad.categoryDisplayName, members));
        }
        return result;
    }

    private Squad getSquad(String team, int squadId) {
        LinkedHashMap<Integer, Squad> squads = squadsByTeam.get(team);
        return squads != null ? squads.get(squadId) : null;
    }

    /** Claims the next strictly increasing visible squad number within one faction. */
    private int claimNextDisplayId(String team) {
        int displayId = nextDisplayIdByTeam.getOrDefault(team, 1);
        nextDisplayIdByTeam.put(team, displayId + 1);
        return displayId;
    }

    /** 返回指定小队的队长 UUID，不存在时返回 null。 */
    public UUID getSquadLeaderUuid(String team, int squadId) {
        Squad squad = getSquad(team, squadId);
        return squad != null ? squad.leader : null;
    }

    /** 返回指定小队的名称，不存在时返回 null。 */
    public String getSquadName(String team, int squadId) {
        Squad squad = getSquad(team, squadId);
        return squad != null ? squad.name : null;
    }

    private Squad getSquadOf(UUID uuid) {
        Integer squadId = playerSquads.get(uuid);
        if (squadId == null) {
            return null;
        }
        for (LinkedHashMap<Integer, Squad> squads : squadsByTeam.values()) {
            Squad squad = squads.get(squadId);
            if (squad != null && squad.members.contains(uuid)) {
                return squad;
            }
        }
        return null;
    }

    private static List<UUID> orderedMembersByFireteam(Squad squad) {
        List<UUID> ordered = new ArrayList<>(squad.members);
        ordered.sort(Comparator
            .comparingInt((UUID id) -> squad.memberFireteam.getOrDefault(id, Fireteam.A).index())
            .thenComparingInt(id -> squad.members.indexOf(id)));
        return ordered;
    }

    /**
     * 将成员加入小队并写入火力组；若该组尚无组长则任命为组长。
     * {@code forceLead} 为 true 时强制设为该组组长（创建小队用）。
     */
    private static void addMemberToFireteam(Squad squad, UUID uuid, Fireteam ft, boolean forceLead) {
        if (!squad.members.contains(uuid)) {
            squad.members.add(uuid);
        }
        squad.memberFireteam.put(uuid, ft);
        if (forceLead || squad.fireteamLeaders.get(ft) == null) {
            squad.fireteamLeaders.put(ft, uuid);
        }
    }

    /**
     * 将已在小队中的成员移到目标火力组，并处理旧组/新组组长继承。
     */
    private static void moveMemberToFireteam(Squad squad, UUID uuid, Fireteam target) {
        Fireteam current = squad.memberFireteam.get(uuid);
        if (current == target) {
            return;
        }
        // 卸任旧组组长
        if (current != null && uuid.equals(squad.fireteamLeaders.get(current))) {
            squad.fireteamLeaders.remove(current);
            promoteFireteamLeader(squad, current, uuid);
        }
        squad.memberFireteam.put(uuid, target);
        if (squad.fireteamLeaders.get(target) == null) {
            squad.fireteamLeaders.put(target, uuid);
        }
    }

    /** 为火力组指定新组长：组内第一个成员（入队顺序），排除 {@code exclude}。 */
    private static void promoteFireteamLeader(Squad squad, Fireteam ft, UUID exclude) {
        for (UUID id : squad.members) {
            if (id.equals(exclude)) {
                continue;
            }
            if (squad.memberFireteam.get(id) == ft) {
                squad.fireteamLeaders.put(ft, id);
                return;
            }
        }
        squad.fireteamLeaders.remove(ft);
    }

    private void removeMemberFireteamState(Squad squad, UUID uuid) {
        Fireteam ft = squad.memberFireteam.remove(uuid);
        if (ft != null && uuid.equals(squad.fireteamLeaders.get(ft))) {
            squad.fireteamLeaders.remove(ft);
            promoteFireteamLeader(squad, ft, uuid);
        }
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
                    removeMemberFireteamState(squad, uuid);
                    affectedTeam = teamEntry.getKey();
                    if (squad.members.isEmpty()) {
                        if (uuid.equals(squad.leader)) {
                            CommanderGovernanceManager.getInstance().onSquadLeaderLost(uuid);
                        }
                        iterator.remove();
                    } else if (uuid.equals(squad.leader)) {
                        CommanderGovernanceManager.getInstance().onSquadLeaderLost(uuid);
                        // 新队长：成员列表首位，并强制 A 组 + A 组长
                        UUID newLeader = squad.members.get(0);
                        squad.leader = newLeader;
                        squad.leaderSinceTick = currentGameTime();
                        moveMemberToFireteam(squad, newLeader, Fireteam.A);
                        squad.fireteamLeaders.put(Fireteam.A, newLeader);
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
        /** Server-wide operation ID; never shown to players. */
        private final int id;
        /** Human-facing sequence number, counted independently for each faction. */
        private final int displayId;
        private final String team;
        private final String name;
        private UUID leader;
        private final List<UUID> members = new ArrayList<>();
        /** 成员 → 火力组 */
        private final Map<UUID, Fireteam> memberFireteam = new HashMap<>();
        /** 火力组 → 组长（可空） */
        private final Map<Fireteam, UUID> fireteamLeaders = new EnumMap<>(Fireteam.class);
        private String categoryId = org.espetro.mapconfig.SquadTypesSnapshot.NONE_ID;
        private String categoryDisplayName = org.espetro.mapconfig.SquadTypesSnapshot.NONE_DISPLAY;
        private long leaderSinceTick;
        private boolean locked;

        private Squad(int id, int displayId, String team, String name, UUID leader) {
            this.id = id;
            this.displayId = displayId;
            this.team = team;
            this.name = name;
            this.leader = leader;
            this.leaderSinceTick = currentGameTime();
        }
    }

    private static long currentGameTime() {
        MinecraftServer server = Espetro.getServer();
        return server != null ? server.overworld().getGameTime() : 0L;
    }

    public static class SquadSnapshot {
        public final int id;
        public final int displayId;
        public final String name;
        public final String leaderName;
        public final UUID leaderUuid;
        public final int maxMembers;
        public final boolean locked;
        public final String categoryId;
        public final String categoryDisplayName;
        public final List<MemberSnapshot> members;

        public SquadSnapshot(int id, String name, String leaderName, int maxMembers, boolean locked,
                             List<MemberSnapshot> members) {
            this(id, id, name, leaderName, null, maxMembers, locked,
                org.espetro.mapconfig.SquadTypesSnapshot.NONE_ID,
                org.espetro.mapconfig.SquadTypesSnapshot.NONE_DISPLAY,
                members);
        }

        public SquadSnapshot(int id, String name, String leaderName, UUID leaderUuid,
                             int maxMembers, boolean locked,
                             String categoryId, String categoryDisplayName,
                             List<MemberSnapshot> members) {
            this(id, id, name, leaderName, leaderUuid, maxMembers, locked,
                categoryId, categoryDisplayName, members);
        }

        public SquadSnapshot(int id, int displayId, String name, String leaderName, UUID leaderUuid,
                             int maxMembers, boolean locked,
                             String categoryId, String categoryDisplayName,
                             List<MemberSnapshot> members) {
            this.id = id;
            this.displayId = displayId;
            this.name = name;
            this.leaderName = leaderName;
            this.leaderUuid = leaderUuid;
            this.maxMembers = maxMembers;
            this.locked = locked;
            this.categoryId = categoryId != null ? categoryId : org.espetro.mapconfig.SquadTypesSnapshot.NONE_ID;
            this.categoryDisplayName = categoryDisplayName != null
                ? categoryDisplayName
                : org.espetro.mapconfig.SquadTypesSnapshot.NONE_DISPLAY;
            this.members = members != null ? members : new ArrayList<>();
        }
    }

    public static class MemberSnapshot {
        public final UUID uuid;
        public final String playerName;
        public final String className;
        public final boolean leader;
        public final Fireteam fireteam;
        public final boolean fireteamLeader;

        public MemberSnapshot(UUID uuid, String playerName, String className, boolean leader) {
            this(uuid, playerName, className, leader, Fireteam.A, leader);
        }

        public MemberSnapshot(UUID uuid, String playerName, String className, boolean leader,
                              Fireteam fireteam, boolean fireteamLeader) {
            this.uuid = uuid;
            this.playerName = playerName;
            this.className = className;
            this.leader = leader;
            this.fireteam = fireteam != null ? fireteam : Fireteam.A;
            this.fireteamLeader = fireteamLeader;
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
