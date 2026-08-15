package org.espetro.client.gui;

import org.espetro.network.UnifiedDeployScreenPacket;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 客户端战术显示状态。
 * 头顶名牌和班组小队界面共用这里的颜色优先级。
 */
public final class ClientTacticalState {

    private static final int NO_SQUAD = -1;
    private static final byte FIRETEAM_NONE = -1;

    private static final Map<String, MarkerInfo> markersByName = new HashMap<>();
    private static final Set<String> commanderNames = new HashSet<>();
    private static int mySquadId = NO_SQUAD;
    private static byte myFireteam = FIRETEAM_NONE;
    private static double teammateNameTagDistance = 10.0;

    private ClientTacticalState() {
    }

    public static void updateSquads(List<UnifiedDeployScreenPacket.SquadInfo> squads, int updatedMySquadId,
                                    List<String> updatedCommanderNames, double updatedNameTagDistance) {
        markersByName.clear();
        commanderNames.clear();
        mySquadId = updatedMySquadId;
        myFireteam = FIRETEAM_NONE;
        teammateNameTagDistance = updatedNameTagDistance > 0 ? updatedNameTagDistance : 10.0;

        if (updatedCommanderNames != null) {
            for (String commanderName : updatedCommanderNames) {
                if (commanderName != null && !commanderName.isEmpty()) {
                    commanderNames.add(key(commanderName));
                }
            }
        }

        if (squads == null) {
            return;
        }

        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        String localName = mc.player != null ? key(mc.player.getName().getString()) : "";

        for (UnifiedDeployScreenPacket.SquadInfo squad : squads) {
            for (UnifiedDeployScreenPacket.SquadMemberInfo member : squad.members) {
                String memberKey = key(member.playerName);
                markersByName.put(memberKey,
                    new MarkerInfo(squad.id, squad.displayId, member.leader, member.fireteamLeader,
                        member.commander, member.fireteam));
                if (member.commander) {
                    commanderNames.add(memberKey);
                }
                if (memberKey.equals(localName)) {
                    myFireteam = member.fireteam;
                }
            }
        }
    }

    public static int getNameColor(String playerName) {
        String key = key(playerName);
        MarkerInfo info = markersByName.get(key);
        if (commanderNames.contains(key) || (info != null && info.commander)) {
            return EspetroMutilWidgets.GOLD;
        }
        if (info != null && info.squadId == mySquadId && mySquadId != NO_SQUAD) {
            return info.leader ? EspetroMutilWidgets.PURPLE : EspetroMutilWidgets.SQUAD_BLUE;
        }
        return EspetroMutilWidgets.TEXT;
    }

    public static int getSquadMemberColor(int squadId, UnifiedDeployScreenPacket.SquadMemberInfo member) {
        if (member.commander || commanderNames.contains(key(member.playerName))) {
            return EspetroMutilWidgets.GOLD;
        }
        if (squadId == mySquadId && mySquadId != NO_SQUAD) {
            return member.leader ? EspetroMutilWidgets.PURPLE : EspetroMutilWidgets.SQUAD_BLUE;
        }
        return EspetroMutilWidgets.TEXT;
    }

    public static double getTeammateNameTagDistance() {
        return teammateNameTagDistance;
    }

    public static boolean isInSquad() {
        return mySquadId != NO_SQUAD;
    }

    public static int getMySquadId() {
        return mySquadId;
    }

    public static boolean isLocalSquadLeader(String playerName) {
        MarkerInfo info = markersByName.get(key(playerName));
        return info != null && info.squadId == mySquadId && mySquadId != NO_SQUAD && info.leader;
    }

    /**
     * 本地玩家是否拥有领导类战术权限。用于在客户端打开轮盘前拦截普通队员；
     * 具体动作仍由服务端再次权威校验。
     */
    public static boolean canLocalPlayerOpenTacticalRadial(String playerName) {
        MarkerInfo info = markersByName.get(key(playerName));
        return hasSquadLeaderAccess(mySquadId, info);
    }

    /** ESPoints 标点轮盘仍允许指挥官、小队长和火力组长。 */
    public static boolean canLocalPlayerPlacePing(String playerName) {
        String normalized = key(playerName);
        MarkerInfo info = markersByName.get(normalized);
        return commanderNames.contains(normalized)
            || (info != null && (info.commander || info.leader || info.fireteamLeader));
    }

    static boolean hasSquadLeaderAccess(int localSquadId, MarkerInfo info) {
        return localSquadId != NO_SQUAD
            && info != null
            && info.squadId == localSquadId
            && info.leader;
    }

    private static String key(String name) {
        return name == null ? "" : name.toLowerCase(Locale.ROOT);
    }

    /** 公开：获取玩家的战术标记信息。 */
    public static MarkerInfo getMarker(String playerName) {
        return markersByName.get(key(playerName));
    }

    /** 公开：判断指定玩家是否为指挥官。 */
    public static boolean isCommander(String playerName) {
        return commanderNames.contains(key(playerName));
    }

    /** 公开：获取本地玩家的火力组索引（0=A, 1=B, 2=C），不在小队中返回 -1。 */
    public static byte getMyFireteam() {
        return myFireteam;
    }

    public record MarkerInfo(int squadId, int displayId, boolean leader, boolean fireteamLeader,
                             boolean commander, byte fireteam) {
        /** 兼容旧构造（显示序号与内部 ID 相同）。 */
        public MarkerInfo(int squadId, boolean leader, boolean fireteamLeader,
                          boolean commander, byte fireteam) {
            this(squadId, squadId, leader, fireteamLeader, commander, fireteam);
        }

        /** 兼容旧构造（无 fireteam 时默认 0 = A 组）。 */
        public MarkerInfo(int squadId, boolean leader, boolean fireteamLeader,
                          boolean commander) {
            this(squadId, squadId, leader, fireteamLeader, commander, (byte) 0);
        }
    }
}
