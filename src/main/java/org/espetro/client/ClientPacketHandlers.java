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
            org.espetro.client.gui.ClientGameState.setPlayerTeam(null);
            org.espetro.client.gui.ClientGameState.setPlayerFactionId(null);
            if (!(mc.screen instanceof org.espetro.client.gui.TeamSelectionScreen)) {
                mc.setScreen(new org.espetro.client.gui.TeamSelectionScreen());
            }
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

        org.espetro.client.gui.ClientGameState.setPlayerTeam(packet.getTeam());

        if (mc.screen instanceof org.espetro.client.gui.ClassSelectScreen screen) {
            // 已在编制选择界面，刷新本方/对方倒计时和当前权限
            screen.updateFromPacket(packet);
        } else {
            mc.setScreen(new org.espetro.client.gui.ClassSelectScreen(
                packet.getTeam(), packet.isCommander(), packet.getFactions(),
                packet.getTimeRemaining(), packet.getOpponentTeamName(),
                packet.getOpponentFaction(), packet.getOpponentTimeRemaining(),
                packet.getSelectedFactionId()));
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
        org.espetro.client.gui.ClientGameState.setPlayerTeam(packet.getTeam());
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        if (mc.screen instanceof org.espetro.client.gui.CommanderVoteScreen screen
            && screen.isForTeam(packet.getTeam())) {
            screen.updatePhaseData(packet.getPlayers(), packet.getTimeRemaining(),
                packet.getOpponentTeamName(), packet.getOpponentFaction(),
                packet.getOpponentTimeRemaining());
        } else {
            org.espetro.client.gui.CommanderVoteScreen.open(
                packet.getTeam(), packet.getPlayers(), packet.getTimeRemaining(),
                packet.getOpponentTeamName(), packet.getOpponentFaction(),
                packet.getOpponentTimeRemaining());
        }
    }

    // ==================== VoteDataPacket ====================

    public static void handleVoteData(VoteDataPacket packet) {
        org.espetro.client.gui.CommanderVoteScreen.updateVoteData(
            packet.getVoteCounts(), packet.getTimeRemaining(), packet.getOpponentTimeRemaining());
    }

    // ==================== FactionRevealPacket ====================

    public static void handleFactionReveal(FactionRevealPacket packet) {
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        if (mc.player != null) {
            if (!(mc.screen instanceof org.espetro.client.gui.FactionRevealScreen screen)
                || !screen.matches(packet.getAttackFactionName(), packet.getDefendFactionName())) {
                mc.setScreen(new org.espetro.client.gui.FactionRevealScreen(
                    packet.getAttackFactionName(),
                    packet.getDefendFactionName(),
                    packet.getAttackFactionImage(),
                    packet.getDefendFactionImage(),
                    packet.getDurationSeconds()
                ));
            }
        }
    }

    // ==================== TroopCountSyncPacket ====================

    public static void handleTroopCount(TroopCountSyncPacket packet) {
        org.espetro.client.gui.TroopCountOverlay.updateTroopCounts(
            packet.getAttackTroops(), packet.getDefendTroops());
    }

    // ==================== StaminaSyncPacket ====================

    public static void handleStamina(StaminaSyncPacket packet) {
        org.espetro.client.gui.StaminaOverlay.update(
            packet.isEnabled(), packet.getStamina(), packet.getMaxStamina(), packet.getJumpStaminaCost());
    }

    // ==================== ClassCountSyncPacket ====================

    public static void handleClassCountSync(ClassCountSyncPacket packet) {
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        if (packet.isError()) {
            String msg = packet.getErrorMessage();
            if (mc.screen instanceof org.espetro.client.gui.ClassSelectionScreen screen) {
                screen.showError(msg);
            }
            // 右侧 AuraTip（无模组时降级聊天）
            org.espetro.client.gui.EspetroTipNotifier.showRaw(msg);
            return;
        }

        if (mc.screen instanceof org.espetro.client.gui.ClassSelectionScreen screen) {
            screen.updateClassCounts(packet.getClassCounts(), packet.getVariantCounts());
        } else if (mc.screen instanceof org.espetro.client.gui.UnifiedDeployScreen screen) {
            screen.updateClassCounts(packet.getClassCounts(), packet.getVariantCounts(),
                packet.getSquadClassCounts());
        }
    }

    // ==================== GamePhaseSyncPacket ====================

    public static void handleGamePhase(String phaseName) {
        try {
            GamePhase phase = GamePhase.valueOf(phaseName);
            org.espetro.client.gui.ClientGameState.setCurrentPhase(phase);
            if (phase.isLobbyLike() || phase == GamePhase.ROUND_END || phase == GamePhase.CLEANUP) {
                org.espetro.client.gui.ClientGovernanceState.clear();
                org.espetro.client.ClientEquipZones.clear();
                org.espetro.client.gui.ClientGameState.setBattleTimeRemaining(-1);
            }

            net.minecraft.client.Minecraft phaseMc = net.minecraft.client.Minecraft.getInstance();
            if (phase == GamePhase.MAP_LOADING
                && phaseMc.screen instanceof org.espetro.client.gui.MapVoteScreen) {
                phaseMc.setScreen(null);
            }
            if (phase.isLobbyLike()
                && phaseMc.screen instanceof org.espetro.client.gui.RoundEndScreen) {
                phaseMc.setScreen(null);
            }

            // 攻方开始进攻时前哨已销毁，关闭仍包含旧前哨按钮的布防面板。
            if (phase == GamePhase.BATTLE) {
                net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
                if (mc.screen instanceof org.espetro.client.gui.UnifiedDeployScreen screen
                    && !screen.isWaitingForDeploySelection()) {
                    mc.setScreen(null);
                }
            }

            // 阶段变化时刷新已打开的部署界面的标题（阶段名 + 倒计时）
            {
                net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
                if (mc.screen instanceof org.espetro.client.gui.UnifiedDeployScreen screen) {
                    screen.updateBattleTimer();
                }
            }
        } catch (IllegalArgumentException ignored) {
        }
    }

    // ==================== VehicleDeployScreenPacket ====================

    public static void handleVehicleDeployScreen(VehicleDeployScreenPacket packet) {
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        if (mc.player == null) return;
        if (mc.screen instanceof org.espetro.client.gui.VehicleDeployScreen screen) {
            screen.updateFromPacket(packet.getVehicles());
        } else if (packet.shouldOpenScreen()) {
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
        if (org.espetro.client.gui.ClientGameState.getCurrentPhase() == GamePhase.BATTLE
            && packet.getDeployTimeRemaining() >= 0) {
            org.espetro.client.gui.ClientGameState.setBattleTimeRemaining(
                packet.getDeployTimeRemaining());
        }
        org.espetro.client.gui.ClientTacticalState.updateSquads(
            packet.getSquads(), packet.getMySquadId(),
            packet.getCommanderNames(), packet.getTeammateNameTagDistance());

        if (mc.screen instanceof org.espetro.client.gui.UnifiedDeployScreen screen) {
            // 已在统一界面中，只更新数据（避免 setScreen 整页重开）。
            // 顺序：可能触发 rebuild 的 bastions/squads 在前；classes 只刷按钮。
            screen.updateTimeRemaining(packet.getDeployTimeRemaining());
            screen.updateDeploymentState(
                packet.isWaitingForDeploySelection(),
                packet.getOutpostRedeployCooldownRemaining());
            screen.updateClassSwitchCooldown(packet.getClassSwitchCooldownRemaining());
            screen.updateBastions(packet.getBastions());
            screen.updateSquads(packet.getSquads(), packet.getMySquadId());
            screen.updateClasses(packet.getClasses(), packet.getClassCounts(), packet.getVariantCounts());
        } else if (mc.screen instanceof org.espetro.client.gui.SquadScreen screen) {
            // 班组管理是部署界面的子界面。部署阶段会持续发送该包，
            // 此处只同步实时状态，不能把玩家强制切回部署界面。
            screen.updateFromDeployPacket(packet);
        } else if (packet.shouldOpenScreen()) {
            mc.setScreen(new org.espetro.client.gui.UnifiedDeployScreen(packet));
        }
    }

    // ==================== SquadSyncPacket ====================

    public static void handleSquadSync(SquadSyncPacket packet) {
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        if (mc.player == null) return;

        org.espetro.client.gui.ClientTacticalState.updateSquads(
            packet.getSquads(), packet.getMySquadId(),
            packet.getCommanderNames(), packet.getTeammateNameTagDistance());

        if (mc.screen instanceof org.espetro.client.gui.SquadScreen screen) {
            screen.updateSquads(packet.getSquads(), packet.getMySquadId());
        } else if (mc.screen instanceof org.espetro.client.gui.UnifiedDeployScreen screen) {
            screen.updateSquads(packet.getSquads(), packet.getMySquadId());
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
        org.espetro.client.gui.ClientGameState.setPlayerTeam(
            playerTeam != null && !playerTeam.isEmpty() ? playerTeam : null);

        String playerFaction = packet.getPlayerFaction();
        org.espetro.client.gui.ClientGameState.setPlayerFactionId(
            playerFaction != null && !playerFaction.isEmpty() ? playerFaction : null);

        // 根据阶段自动打开对应界面
        String phaseName = packet.getPhaseName();
        String activeTeam = packet.getActiveTeam();
        String myTeam = org.espetro.client.gui.ClientGameState.getPlayerTeam();

        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        if (mc.player == null) return;

        if ("LOBBY".equals(phaseName) || "WAITING_FOR_PLAYERS".equals(phaseName)) {
            mc.setScreen(new org.espetro.client.gui.HubScreen(0,
                "等待管理员开始下一局"));
            return;
        }
        if ("MAP_VOTE".equals(phaseName)) {
            if (!(mc.screen instanceof org.espetro.client.gui.MapVoteScreen)) {
                mc.setScreen(new org.espetro.client.gui.MapVoteScreen());
            }
            return;
        }
        if ("TEAM_SELECT".equals(phaseName)) {
            if (!(mc.screen instanceof org.espetro.client.gui.TeamSelectionScreen)) {
                mc.setScreen(new org.espetro.client.gui.TeamSelectionScreen());
            }
            return;
        }

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
                if (!(mc.screen instanceof org.espetro.client.gui.TeamSelectionScreen)) {
                    mc.setScreen(new org.espetro.client.gui.TeamSelectionScreen());
                }
            }
        }
    }

    // ==================== CommanderSkillSyncPacket ====================

    public static void handleCommanderSkillSync(org.espetro.network.CommanderSkillSyncPacket packet) {
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        if (mc.player == null) return;

        // 更新 MUtil 轮盘技能缓存。
        org.espetro.client.gui.AuraTipRadialController.updateSkills(
            packet.isCommander(), packet.getCooldowns(), packet.getSkills());

        // 如果 CommanderSkillScreen 恰好已打开，同步更新数据
        if (mc.screen instanceof org.espetro.client.gui.CommanderSkillScreen screen) {
            screen.updateData(packet.isCommander(), packet.getCooldowns(), packet.getSkills());
        }
    }

    // ==================== Multi-dimension flow ====================

    public static void handleOpenHubScreen(OpenHubScreenPacket packet) {
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        if (mc.player == null) return;
        org.espetro.client.gui.ClientGameState.setPlayerTeam(null);
        org.espetro.client.gui.ClientGameState.setPlayerFactionId(null);
        // 已打开主城菜单时只更新文案，避免 setScreen 整页重建闪烁。
        if (mc.screen instanceof org.espetro.client.gui.HubScreen hub) {
            hub.updateStatus(packet.onlineCount, packet.statusMessage);
            return;
        }
        mc.setScreen(new org.espetro.client.gui.HubScreen(packet.onlineCount, packet.statusMessage));
    }

    public static void handleClassSelectTimer(ClassSelectTimerPacket packet) {
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        if (mc.screen instanceof org.espetro.client.gui.ClassSelectScreen screen) {
            screen.updateTimer(
                packet.getTimeRemaining(),
                packet.getOpponentTimeRemaining(),
                packet.getSelectedFactionId(),
                packet.isCommander());
        }
    }

    public static void handleEquipZoneSync(EquipZoneSyncPacket packet) {
        org.espetro.client.ClientEquipZones.setZones(packet.getZones());
    }

    public static void handleBattleTimer(BattleTimerPacket packet) {
        org.espetro.client.gui.ClientGameState.setBattleTimeRemaining(packet.getRemainingSeconds());
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        if (mc.screen instanceof org.espetro.client.gui.UnifiedDeployScreen screen) {
            screen.updateBattleTimer();
        }
    }

    public static void handleMapVoteState(MapVoteStatePacket packet) {
        org.espetro.client.gui.MapVoteScreen.update(packet);
    }

    public static void handleOpenMapVoteScreen() {
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        if (mc.player != null && !(mc.screen instanceof org.espetro.client.gui.MapVoteScreen)) {
            mc.setScreen(new org.espetro.client.gui.MapVoteScreen());
        }
    }

    public static void handleTeamSelectState(TeamSelectStatePacket packet) {
        org.espetro.client.gui.TeamSelectionScreen.updateTeamState(packet);
    }

    public static void handleMatchStats(MatchStatsSyncPacket packet) {
        org.espetro.client.gui.MatchScoreboardScreen.updateStats(packet);
    }

    public static void handleGovernanceState(GovernanceStatePacket packet) {
        org.espetro.client.gui.ClientGovernanceState.update(packet);
        org.espetro.client.gui.MatchScoreboardScreen.updateGovernance(packet);
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        if (mc.screen instanceof org.espetro.client.gui.UnifiedDeployScreen screen) {
            screen.updateGovernance(packet);
        } else if (mc.screen instanceof org.espetro.client.gui.MatchScoreboardScreen scoreboard
            && scoreboard.getParent() instanceof org.espetro.client.gui.UnifiedDeployScreen parent) {
            // Keep parent deploy panel in sync while scoreboard is open.
            parent.updateGovernance(packet);
        }
    }

    public static void handleRoundEnd(RoundEndPacket packet) {
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        if (mc.player != null) {
            mc.setScreen(new org.espetro.client.gui.RoundEndScreen(
                packet.winner, packet.displaySeconds,
                packet.winnerShowName, packet.loserShowName,
                packet.attackTickets, packet.defendTickets,
                packet.resultLevel, packet.attackerTimeout));
        }
    }

    // ==================== TutorialSyncPacket ====================

    public static void handleTutorialSync(byte action, String stepId, int index, int total, boolean allowSkip) {
        if (action == org.espetro.network.TutorialSyncPacket.ACTION_CLEAR) {
            org.espetro.client.gui.TutorialOverlay.clear();
            return;
        }
        org.espetro.client.gui.TutorialOverlay.show(stepId, index, total, allowSkip);
    }

    // ==================== PartyListPacket ====================

    public static void handlePartyList(PartyListPacket packet) {
        org.espetro.client.gui.PartyScreen.update(packet);
    }

    // ==================== VehicleSupplySyncPacket ====================

    public static void handleVehicleSupplySync(VehicleSupplySyncPacket packet) {
        org.espetro.client.gui.VehicleWheelController.updateSupply(packet);
    }

    // ==================== FobSupplySyncPacket ====================

    public static void handleFobSupplySync(FobSupplySyncPacket packet) {
        org.espetro.client.gui.FobSupplyHud.update(packet);
    }

    public static void handleFortificationCatalog(FortificationCatalogPacket packet) {
        org.espetro.client.gui.AuraTipRadialController
            .updateFortifications(packet.entries());
    }
}
