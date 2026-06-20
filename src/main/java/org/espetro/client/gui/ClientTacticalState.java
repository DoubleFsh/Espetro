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

    private static final Map<String, MarkerInfo> markersByName = new HashMap<>();
    private static final Set<String> commanderNames = new HashSet<>();
    private static int mySquadId = NO_SQUAD;
    private static double teammateNameTagDistance = 10.0;

    private ClientTacticalState() {
    }

    public static void updateSquads(List<UnifiedDeployScreenPacket.SquadInfo> squads, int updatedMySquadId,
                                    List<String> updatedCommanderNames, double updatedNameTagDistance) {
        markersByName.clear();
        commanderNames.clear();
        mySquadId = updatedMySquadId;
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

        for (UnifiedDeployScreenPacket.SquadInfo squad : squads) {
            for (UnifiedDeployScreenPacket.SquadMemberInfo member : squad.members) {
                markersByName.put(key(member.playerName),
                    new MarkerInfo(squad.id, member.leader, member.commander));
                if (member.commander) {
                    commanderNames.add(key(member.playerName));
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

    private static String key(String name) {
        return name == null ? "" : name.toLowerCase(Locale.ROOT);
    }

    private record MarkerInfo(int squadId, boolean leader, boolean commander) {
    }
}
