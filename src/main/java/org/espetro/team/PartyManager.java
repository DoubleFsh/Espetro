package org.espetro.team;

import net.minecraft.server.level.ServerPlayer;
import org.espetro.Espetro;
import org.espetro.network.NetworkManager;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 主城组队匹配管理器。
 * 玩家在主城按 J 键呼出组队界面，创建/加入队伍。
 * 游戏开始时，系统根据组队信息将同一队伍分配到同一边。
 */
public final class PartyManager {

    private static PartyManager INSTANCE;

    /** 队伍上限（管理员可通过指令修改）。 */
    private static int maxPartySize = 7;

    private final Map<UUID, PartyData> partiesByOwner = new LinkedHashMap<>();
    /** 成员 → 所属队伍 ID 反向索引。 */
    private final Map<UUID, UUID> playerPartyMap = new HashMap<>();

    private PartyManager() {
        INSTANCE = this;
    }

    public static PartyManager getInstance() {
        if (INSTANCE == null) {
            INSTANCE = new PartyManager();
        }
        return INSTANCE;
    }

    // -------------------- 队伍列表 --------------------

    public Collection<PartyData> getParties() {
        return Collections.unmodifiableCollection(partiesByOwner.values());
    }

    public PartyData getParty(UUID partyId) {
        return partiesByOwner.get(partyId);
    }

    public PartyData getPartyByMember(UUID playerId) {
        UUID partyId = playerPartyMap.get(playerId);
        return partyId != null ? partiesByOwner.get(partyId) : null;
    }

    public boolean isInParty(UUID playerId) {
        return playerPartyMap.containsKey(playerId);
    }

    public int getPartySize(UUID partyId) {
        PartyData p = partiesByOwner.get(partyId);
        return p != null ? p.members.size() : 0;
    }

    // -------------------- 创建 / 解散 --------------------

    public PartyData createParty(ServerPlayer owner, String password) {
        if (isInParty(owner.getUUID())) {
            return null; // 已在队伍中
        }
        UUID partyId = UUID.randomUUID();
        PartyData party = new PartyData(partyId, owner.getUUID(), owner.getName().getString(), password);
        party.members.add(owner.getUUID());
        partiesByOwner.put(partyId, party);
        playerPartyMap.put(owner.getUUID(), partyId);
        broadcastPartyList();
        return party;
    }

    public void disbandParty(UUID partyId) {
        PartyData party = partiesByOwner.remove(partyId);
        if (party == null) return;
        for (UUID m : party.members) {
            playerPartyMap.remove(m);
        }
        broadcastPartyList();
    }

    /** 解散最大的队伍，用于平衡分配。返回被解散的队员 UUID 列表。 */
    public List<UUID> disbandLargestParty() {
        if (partiesByOwner.isEmpty()) return List.of();
        PartyData largest = null;
        for (PartyData p : partiesByOwner.values()) {
            if (largest == null || p.members.size() > largest.members.size()) {
                largest = p;
            }
        }
        if (largest == null) return List.of();
        List<UUID> members = new ArrayList<>(largest.members);
        disbandParty(largest.partyId);
        return members;
    }

    // -------------------- 加入 / 离开 --------------------

    public String joinParty(UUID partyId, ServerPlayer player, String password) {
        PartyData party = partiesByOwner.get(partyId);
        if (party == null) return "该队伍不存在。";
        if (isInParty(player.getUUID())) return "你已在其他队伍中，请先退出。";
        if (party.locked) return "该队伍已锁定，无法加入。";
        if (party.members.size() >= maxPartySize) return "该队伍已满员（上限 " + maxPartySize + " 人）。";
        if (party.password != null && !party.password.isEmpty() && !party.password.equals(password)) {
            return "密码错误。";
        }
        party.members.add(player.getUUID());
        playerPartyMap.put(player.getUUID(), partyId);
        broadcastPartyList();
        return null; // 成功
    }

    public boolean leaveParty(UUID playerId) {
        UUID partyId = playerPartyMap.remove(playerId);
        if (partyId == null) return false;
        PartyData party = partiesByOwner.get(partyId);
        if (party == null) return false;
        party.members.remove(playerId);
        if (party.members.isEmpty()) {
            partiesByOwner.remove(partyId);
        }
        broadcastPartyList();
        return true;
    }

    public void removePlayerFromAnyParty(UUID playerId) {
        leaveParty(playerId);
    }

    // -------------------- 踢人 --------------------

    public String kickMember(UUID partyId, UUID kickerId, UUID targetId) {
        PartyData party = partiesByOwner.get(partyId);
        if (party == null) return "队伍不存在。";
        if (!party.ownerId.equals(kickerId)) return "只有队长才能踢人。";
        if (kickerId.equals(targetId)) return "不能踢自己，请使用解散队伍。";

        party.members.remove(targetId);
        playerPartyMap.remove(targetId);
        if (party.members.isEmpty()) {
            partiesByOwner.remove(partyId);
        }
        broadcastPartyList();
        return null;
    }

    // -------------------- 锁定 / 密码 --------------------

    public String toggleLock(UUID partyId, UUID ownerId) {
        PartyData party = partiesByOwner.get(partyId);
        if (party == null) return "队伍不存在。";
        if (!party.ownerId.equals(ownerId)) return "只有队长才能操作。";
        party.locked = !party.locked;
        broadcastPartyList();
        return null;
    }

    // -------------------- 广播 --------------------

    public void broadcastPartyList() {
        NetworkManager.broadcastPartyList(this);
    }

    public void syncToPlayer(ServerPlayer player) {
        NetworkManager.sendPartyListTo(player);
    }

    // -------------------- 管理 --------------------

    public static int getMaxPartySize() {
        return maxPartySize;
    }

    public static void setMaxPartySize(int size) {
        maxPartySize = Math.max(1, size);
    }

    /** 清空所有组队（游戏开始后调用）。 */
    public void clearAll() {
        partiesByOwner.clear();
        playerPartyMap.clear();
        broadcastPartyList();
    }

    // -------------------- 队伍分配辅助 --------------------

    /**
     * 将在线玩家按组队信息分配到两支队伍。
     * 返回 Map: playerId → "ATTACK"/"DEFEND"
     * 规则：
     *   1. 同一组队的玩家尽量分配到同一边
     *   2. 如果组队导致双方差异过大，解散最大的队伍
     *   3. 剩余无组队玩家均分，单数多出的优先分配到进攻方
     */
    public Map<UUID, String> computeTeamAssignment(List<ServerPlayer> allPlayers) {
        Map<UUID, String> result = new LinkedHashMap<>();
        List<UUID> unassigned = new ArrayList<>();

        // 按队伍分组
        Map<UUID, List<UUID>> partyGroups = new LinkedHashMap<>();
        Set<UUID> handled = new HashSet<>();
        for (ServerPlayer p : allPlayers) {
            UUID uid = p.getUUID();
            if (handled.contains(uid)) continue;
            PartyData party = getPartyByMember(uid);
            if (party != null) {
                partyGroups.computeIfAbsent(party.partyId, k -> new ArrayList<>())
                    .addAll(party.members);
                handled.addAll(party.members);
            } else {
                unassigned.add(uid);
                handled.add(uid);
            }
        }

        int attack = 0, defend = 0;
        // 按队伍大小降序排列
        List<List<UUID>> sortedGroups = new ArrayList<>(partyGroups.values());
        sortedGroups.sort((a, b) -> Integer.compare(b.size(), a.size()));

        for (List<UUID> group : sortedGroups) {
            if (attack <= defend) {
                for (UUID uid : group) result.put(uid, "ATTACK");
                attack += group.size();
            } else {
                for (UUID uid : group) result.put(uid, "DEFEND");
                defend += group.size();
            }
        }

        // 检查平衡：如果差异太大，解散最大的队伍
        while (Math.abs(attack - defend) > 2 && !partyGroups.isEmpty()) {
            // 找到人数更多的一方中最大的队伍
            String overTeam = attack > defend ? "ATTACK" : "DEFEND";
            PartyData largestParty = null;
            for (UUID pid : partyGroups.keySet()) {
                PartyData p = partiesByOwner.get(pid);
                if (p == null) continue;
                boolean onOverTeam = false;
                for (UUID m : p.members) {
                    if (overTeam.equals(result.get(m))) { onOverTeam = true; break; }
                }
                if (!onOverTeam) continue;
                if (largestParty == null || p.members.size() > largestParty.members.size()) {
                    largestParty = p;
                }
            }
            if (largestParty == null) break;

            // 解散该队伍
            for (UUID m : largestParty.members) {
                result.remove(m);
                unassigned.add(m);
                if ("ATTACK".equals(result.getOrDefault(m, null))) attack--;
                else if ("DEFEND".equals(result.getOrDefault(m, null))) defend--;
            }
            partyGroups.remove(largestParty.partyId);
        }

        // 重算当前计数
        attack = 0; defend = 0;
        for (String t : result.values()) {
            if ("ATTACK".equals(t)) attack++; else defend++;
        }

        // 分配无组队玩家
        for (UUID uid : unassigned) {
            if (attack <= defend) {
                result.put(uid, "ATTACK");
                attack++;
            } else {
                result.put(uid, "DEFEND");
                defend++;
            }
        }

        return result;
    }

    // ==================== 数据结构 ====================

    public static final class PartyData {
        public final UUID partyId;
        public final UUID ownerId;
        public final String ownerName;
        /** 成员集合（含队长）。 */
        public final Set<UUID> members;
        /** null 或空字符串表示无密码。 */
        public String password;
        public boolean locked;

        PartyData(UUID partyId, UUID ownerId, String ownerName, String password) {
            this.partyId = partyId;
            this.ownerId = ownerId;
            this.ownerName = ownerName;
            this.members = new LinkedHashSet<>();
            this.password = (password == null || password.isEmpty()) ? null : password;
            this.locked = password != null && !password.isEmpty();
        }

        public int size() {
            return members.size();
        }

        public boolean hasPassword() {
            return password != null && !password.isEmpty();
        }
    }
}
