package org.espetro.client;

import org.espetro.network.*;
import org.espetro.team.GamePhase;

/**
 * 客户端数据包处理器
 * 所有客户端专属的GUI操作、Minecraft类引用均集中在此，
 * packet 类通过 Class.forName + 反射调用此处的静态方法，
 * 确保 packet 字节码不包含任何客户端类引用。
 */
public class ClientPacketHandlers {

    // ==================== OpenFactionScreenPacket ====================

    public static void handleOpenFactionScreen() {
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        if (mc.player != null) {
            mc.setScreen(new org.espetro.client.gui.TeamSelectionScreen());
        }
    }

    // ==================== WaitingStatusPacket ====================

    public static void handleWaitingStatus(String message, boolean isActionBar) {
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        if (mc.player != null) {
            net.minecraft.network.chat.Component component =
                net.minecraft.network.chat.Component.literal(message);
            if (isActionBar) {
                mc.player.displayClientMessage(component, true);
            } else {
                mc.player.sendSystemMessage(component);
            }
        }
    }

    // ==================== ClassSelectScreenPacket ====================

    public static void handleClassSelectScreen(ClassSelectScreenPacket packet) {
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        if (mc.player == null) return;

        if (mc.screen instanceof org.espetro.client.gui.ClassSelectScreen screen) {
            // 已在编制选择界面，只更新倒计时
            screen.updateTimeRemaining(packet.getTimeRemaining());
        } else {
            mc.setScreen(new org.espetro.client.gui.ClassSelectScreen(
                packet.getTeam(), packet.isCommander(), packet.getFactions(), packet.getTimeRemaining()));
        }
    }

    // ==================== OpenClassSelectionPacket ====================

    public static void handleOpenClassSelection(OpenClassSelectionPacket packet) {
        org.espetro.client.gui.ClientGameState.setPlayerFactionId(packet.getFactionId());
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        if (mc.player != null) {
            mc.setScreen(new org.espetro.client.gui.ClassSelectionScreen(
                packet.getFactionId(),
                packet.getFactionName(),
                packet.getFactionDescription(),
                packet.getFactionIcon(),
                packet.getClasses()
            ));
        }
    }

    // ==================== CommanderVotePacket ====================

    public static void handleCommanderVote(CommanderVotePacket packet) {
        org.espetro.client.gui.CommanderVoteScreen.open(
            packet.getTeam(), packet.getPlayers(), packet.getTimeRemaining());
    }

    // ==================== VoteDataPacket ====================

    public static void handleVoteData(VoteDataPacket packet) {
        org.espetro.client.gui.CommanderVoteScreen.updateVoteData(
            packet.getVoteCounts(), packet.getTimeRemaining());
    }

    // ==================== TroopCountSyncPacket ====================

    public static void handleTroopCount(TroopCountSyncPacket packet) {
        org.espetro.client.gui.TroopCountOverlay.updateTroopCounts(
            packet.getAttackTroops(), packet.getDefendTroops());
    }

    // ==================== ClassCountSyncPacket ====================

    public static void handleClassCountSync(ClassCountSyncPacket packet) {
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        if (mc.screen instanceof org.espetro.client.gui.ClassSelectionScreen screen) {
            if (packet.isError()) {
                screen.showError(packet.getErrorMessage());
            } else {
                screen.updateClassCounts(packet.getClassCounts());
            }
        }
    }

    // ==================== GamePhaseSyncPacket ====================

    public static void handleGamePhase(String phaseName) {
        try {
            org.espetro.client.gui.ClientGameState.setCurrentPhase(
                GamePhase.valueOf(phaseName));
        } catch (IllegalArgumentException ignored) {
        }
    }

    // ==================== VehicleDeployScreenPacket ====================

    public static void handleVehicleDeployScreen(VehicleDeployScreenPacket packet) {
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        if (mc.player != null) {
            mc.setScreen(new org.espetro.client.gui.VehicleDeployScreen(packet.getVehicles()));
        }
    }

    // ==================== DeployPointSelectPacket ====================

    public static void handleDeployPointSelect(DeployPointSelectPacket packet) {
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        if (mc.player != null) {
            mc.setScreen(new org.espetro.client.gui.DeployPointSelectScreen(
                packet.hasDeployPoint(), packet.getDeployPointPos(), packet.getBastions()));
        }
    }

    // ==================== UnifiedDeployScreenPacket ====================

    public static void handleUnifiedDeployScreen(UnifiedDeployScreenPacket packet) {
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        if (mc.player == null) return;

        // 记录当前阵营/编制ID
        org.espetro.client.gui.ClientGameState.setPlayerFactionId(packet.getFactionId());
        org.espetro.client.gui.ClientGameState.setPlayerTeam(packet.getTeam());

        if (mc.screen instanceof org.espetro.client.gui.UnifiedDeployScreen screen) {
            // 已在统一界面中，只更新数据
            screen.updateClassCounts(packet.getClassCounts());
            screen.updateTimeRemaining(packet.getDeployTimeRemaining());
            // 载具部署已分离到 VehicleDeployScreen，通过"载具部署指令"物品单独打开
        } else {
            mc.setScreen(new org.espetro.client.gui.UnifiedDeployScreen(packet));
        }
    }

    // ==================== GameStateResponsePacket ====================

    public static void handleGameStateResponse(org.espetro.network.GameStateResponsePacket packet) {
        // 更新客户端游戏状态
        try {
            org.espetro.client.gui.ClientGameState.setCurrentPhase(
                GamePhase.valueOf(packet.getPhaseName()));
        } catch (IllegalArgumentException ignored) {
        }

        String playerTeam = packet.getPlayerTeam();
        if (playerTeam != null && !playerTeam.isEmpty()) {
            org.espetro.client.gui.ClientGameState.setPlayerTeam(playerTeam);
        }

        String playerFaction = packet.getPlayerFaction();
        if (playerFaction != null && !playerFaction.isEmpty()) {
            org.espetro.client.gui.ClientGameState.setPlayerFactionId(playerFaction);
        }

        // 根据阶段自动打开对应界面
        String phaseName = packet.getPhaseName();
        String activeTeam = packet.getActiveTeam();
        String myTeam = org.espetro.client.gui.ClientGameState.getPlayerTeam();

        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        if (mc.player == null) return;

        // 如果是投票阶段且是当前投票方，打开投票界面
        if (("DEFEND_COMMANDER_VOTE".equals(phaseName) && "DEFEND".equals(myTeam)) ||
            ("ATTACK_COMMANDER_VOTE".equals(phaseName) && "ATTACK".equals(myTeam))) {
            // 投票界面由服务端主动发送 CommanderVotePacket 打开
            return;
        }

        // 如果是编制选择阶段且是当前选择方且是指挥官，打开编制界面
        if (("DEFEND_FACTION_SELECT".equals(phaseName) && "DEFEND".equals(myTeam)) ||
            ("ATTACK_FACTION_SELECT".equals(phaseName) && "ATTACK".equals(myTeam))) {
            // 编制界面由服务端主动发送 ClassSelectScreenPacket 打开
            return;
        }

        // K键请求的响应：如果在允许打开阵营选择的阶段且未选择队伍，打开阵营选择
        if (org.espetro.client.gui.ClientGameState.canOpenTeamSelection()) {
            if (myTeam == null || myTeam.isEmpty()) {
                mc.setScreen(new org.espetro.client.gui.TeamSelectionScreen());
            }
        }
    }
}
