package org.espetro.network;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;
import org.espetro.Espetro;
import org.espetro.config.GameConfig;
import org.espetro.team.ClassCountManager;
import org.espetro.team.ClassSelectManager;
import org.espetro.team.FactionDataLoader;
import org.espetro.team.FactionDataProvider;
import org.espetro.team.GamePhase;
import org.espetro.team.SquadManager;
import org.espetro.team.VoteManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 网络管理器
 * 注册所有数据包
 */
public class NetworkManager {

    public static final String PROTOCOL_VERSION = "1.1";

    public static final SimpleChannel NET = NetworkRegistry.newSimpleChannel(
        ResourceLocation.fromNamespaceAndPath(Espetro.MOD_ID, "main"),
        () -> PROTOCOL_VERSION,
        PROTOCOL_VERSION::equals,
        PROTOCOL_VERSION::equals
    );

    private static int packetId = 0;

    public static int nextId() {
        return packetId++;
    }

    public static void registerNetwork() {
        // 阵营选择包
        NET.registerMessage(
            nextId(),
            TeamSelectPacket.class,
            TeamSelectPacket::write,
            TeamSelectPacket::read,
            TeamSelectPacket::handle
        );

        // 职业选择包（包含结果）
        NET.registerMessage(
            nextId(),
            ClassSelectPacket.class,
            ClassSelectPacket::write,
            ClassSelectPacket::read,
            ClassSelectPacket::handle
        );

        // 职业人数请求/同步包
        NET.registerMessage(
            nextId(),
            ClassCountSyncPacket.class,
            ClassCountSyncPacket::write,
            ClassCountSyncPacket::read,
            ClassCountSyncPacket::handle
        );

        // 等待状态消息包
        NET.registerMessage(
            nextId(),
            WaitingStatusPacket.class,
            WaitingStatusPacket::write,
            WaitingStatusPacket::read,
            WaitingStatusPacket::handle
        );

        // 指挥官投票包
        NET.registerMessage(
            nextId(),
            CommanderVotePacket.class,
            CommanderVotePacket::write,
            CommanderVotePacket::read,
            CommanderVotePacket::handle
        );

        // 投票数据同步包
        NET.registerMessage(
            nextId(),
            VoteDataPacket.class,
            VoteDataPacket::write,
            VoteDataPacket::read,
            VoteDataPacket::handle
        );

        // 投票操作包
        NET.registerMessage(
            nextId(),
            CastVotePacket.class,
            CastVotePacket::write,
            CastVotePacket::read,
            CastVotePacket::handle
        );

        // 游戏阶段同步包
        NET.registerMessage(
            nextId(),
            GamePhaseSyncPacket.class,
            GamePhaseSyncPacket::write,
            GamePhaseSyncPacket::read,
            GamePhaseSyncPacket::handle
        );

        // 兵力统计同步包
        NET.registerMessage(
            nextId(),
            TroopCountSyncPacket.class,
            TroopCountSyncPacket::write,
            TroopCountSyncPacket::read,
            TroopCountSyncPacket::handle
        );

        // 强制打开攻防方选择界面包（S→C）
        NET.registerMessage(
            nextId(),
            OpenFactionScreenPacket.class,
            OpenFactionScreenPacket::write,
            OpenFactionScreenPacket::read,
            OpenFactionScreenPacket::handle
        );

        // 编制选择界面包（S→C）
        NET.registerMessage(
            nextId(),
            ClassSelectScreenPacket.class,
            ClassSelectScreenPacket::write,
            ClassSelectScreenPacket::read,
            ClassSelectScreenPacket::handle
        );

        // 双方编制揭示界面包（S→C）
        NET.registerMessage(
            nextId(),
            FactionRevealPacket.class,
            FactionRevealPacket::write,
            FactionRevealPacket::read,
            FactionRevealPacket::handle
        );

        // 职业选择界面包（S→C）
        NET.registerMessage(
            nextId(),
            OpenClassSelectionPacket.class,
            OpenClassSelectionPacket::write,
            OpenClassSelectionPacket::read,
            OpenClassSelectionPacket::handle
        );

        // 请求职业选择界面包（C→S）：客户端请求 → 服务端回传完整数据
        NET.registerMessage(
            nextId(),
            RequestClassSelectionPacket.class,
            RequestClassSelectionPacket::write,
            RequestClassSelectionPacket::read,
            RequestClassSelectionPacket::handle
        );

        // 请求游戏状态包（C→S）
        NET.registerMessage(
            nextId(),
            RequestGameStatePacket.class,
            RequestGameStatePacket::write,
            RequestGameStatePacket::read,
            RequestGameStatePacket::handle
        );

        // 游戏状态响应包（S→C）
        NET.registerMessage(
            nextId(),
            GameStateResponsePacket.class,
            GameStateResponsePacket::write,
            GameStateResponsePacket::read,
            GameStateResponsePacket::handle
        );

        // 载具部署界面包（S→C）
        NET.registerMessage(
            nextId(),
            VehicleDeployScreenPacket.class,
            VehicleDeployScreenPacket::write,
            VehicleDeployScreenPacket::read,
            VehicleDeployScreenPacket::handle
        );

        // 复活点选择界面包（S→C）
        NET.registerMessage(
            nextId(),
            DeployPointSelectPacket.class,
            DeployPointSelectPacket::write,
            DeployPointSelectPacket::read,
            DeployPointSelectPacket::handle
        );

        // 统一部署主界面包（S→C）
        NET.registerMessage(
            nextId(),
            UnifiedDeployScreenPacket.class,
            UnifiedDeployScreenPacket::write,
            UnifiedDeployScreenPacket::read,
            UnifiedDeployScreenPacket::handle
        );

        // 班组小队操作包（C→S）
        NET.registerMessage(
            nextId(),
            SquadActionPacket.class,
            SquadActionPacket::write,
            SquadActionPacket::read,
            SquadActionPacket::handle
        );

        // 班组小队同步包（S→C）
        NET.registerMessage(
            nextId(),
            SquadSyncPacket.class,
            SquadSyncPacket::write,
            SquadSyncPacket::read,
            SquadSyncPacket::handle
        );

        // 指挥官技能请求包（C→S）
        NET.registerMessage(
            nextId(),
            CommanderSkillPacket.class,
            CommanderSkillPacket::write,
            CommanderSkillPacket::read,
            CommanderSkillPacket::handle
        );

        // 指挥官技能同步包（S→C）
        NET.registerMessage(
            nextId(),
            CommanderSkillSyncPacket.class,
            CommanderSkillSyncPacket::write,
            CommanderSkillSyncPacket::read,
            CommanderSkillSyncPacket::handle
        );

        // 体力状态同步包（S→C）
        NET.registerMessage(
            nextId(),
            StaminaSyncPacket.class,
            StaminaSyncPacket::write,
            StaminaSyncPacket::read,
            StaminaSyncPacket::handle
        );

        // 跳跃体力动作包（C→S）
        NET.registerMessage(
            nextId(),
            StaminaJumpPacket.class,
            StaminaJumpPacket::write,
            StaminaJumpPacket::read,
            StaminaJumpPacket::handle
        );
    }

    /**
     * 发送职业选择包
     */
    public static void sendClassSelect(String factionId, String classId) {
        NET.sendToServer(new ClassSelectPacket(factionId, classId));
    }

    /**
     * 发送阵营选择包
     */
    public static void sendFactionSelect(String factionId) {
        NET.sendToServer(new TeamSelectPacket(factionId));
    }

    /**
     * 请求打开职业选择界面（C→S），服务端会返回完整数据后自动打开GUI
     */
    public static void requestClassSelection(String factionId) {
        NET.sendToServer(new RequestClassSelectionPacket(factionId));
    }

    /**
     * 请求职业人数同步
     */
    public static void requestClassCounts(String factionId) {
        NET.sendToServer(new ClassCountSyncPacket(factionId));
    }

    /**
     * 将某阵营当前编制的职业人数立即广播给同阵营全员。
     */
    public static void broadcastClassCounts(String team, String factionId) {
        MinecraftServer server = Espetro.getServer();
        if (server == null || team == null || factionId == null || factionId.isBlank()) return;

        ClassCountManager countManager = ClassCountManager.getInstance();
        java.util.Map<String, Integer> counts = countManager.getCountsForFaction(team, factionId);
        ClassCountSyncPacket packet = new ClassCountSyncPacket(counts, factionId);
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (team.equals(countManager.getEffectivePlayerTeam(player.getUUID()))) {
                NET.send(PacketDistributor.PLAYER.with(() -> player), packet);
            }
        }
    }

    /**
     * 请求当前游戏状态（C→S）
     */
    public static void requestGameState() {
        NET.sendToServer(new RequestGameStatePacket());
    }

    public static void sendStaminaJump() {
        NET.sendToServer(new StaminaJumpPacket());
    }

    /**
     * 创建班组小队。
     */
    public static void createSquad(String squadName) {
        NET.sendToServer(SquadActionPacket.create(squadName));
    }

    /**
     * 加入班组小队。
     */
    public static void joinSquad(int squadId) {
        NET.sendToServer(SquadActionPacket.join(squadId));
    }

    /**
     * 退出当前班组小队。
     */
    public static void leaveSquad() {
        NET.sendToServer(SquadActionPacket.leave());
    }

    /**
     * 删除当前队长管理的小队。
     */
    public static void deleteSquad(int squadId) {
        NET.sendToServer(SquadActionPacket.delete(squadId));
    }

    /**
     * 发送打开阵营选择界面包给指定玩家
     */
    public static void sendOpenFactionScreen(ServerPlayer player) {
        NET.send(PacketDistributor.PLAYER.with(() -> player), new OpenFactionScreenPacket());
    }

    /**
     * 通用方法：发送网络包给指定玩家（S→C）
     */
    public static <T> void sendToPlayer(ServerPlayer player, T packet) {
        NET.send(PacketDistributor.PLAYER.with(() -> player), packet);
    }
    
    /**
     * 发送编制选择界面给指定玩家
     */
    public static void sendClassSelectScreen(ServerPlayer player, String team, boolean isCommander, int timeRemaining) {
        List<ClassSelectScreenPacket.FactionInfo> factionList = getFactionListForTeam(team);
        String opponentTeamName = teamDisplayName(oppositeTeam(team));
        String opponentFaction = getOpponentFactionDisplayName(team);
        ClassSelectScreenPacket packet = new ClassSelectScreenPacket(team, isCommander, factionList,
            timeRemaining, opponentTeamName, opponentFaction, -1,
            ClassSelectManager.getInstance().getPlayerFactionVote(player.getUUID(), team));
        NET.send(PacketDistributor.PLAYER.with(() -> player), packet);
    }

    /**
     * 根据队伍获取可选编制列表（服务端调用）
     * 攻守双方使用同一个随机编制池，但排除对方已确定的编制。
     */
    private static List<ClassSelectScreenPacket.FactionInfo> getFactionListForTeam(String team) {
        List<String> pool = ClassSelectManager.getInstance().getAvailableFactionPoolForTeam(team);
        FactionDataLoader loader = FactionDataProvider.getOrCreateLoader();
        java.util.Map<String, Integer> voteCounts =
            ClassSelectManager.getInstance().getFactionVoteCounts(team);
        List<ClassSelectScreenPacket.FactionInfo> list = new ArrayList<>();

        for (String id : pool) {
            FactionDataLoader.FactionData faction = loader.getFaction(id);
            String name = faction != null ? faction.name : id;
            list.add(new ClassSelectScreenPacket.FactionInfo(
                id, name, voteCounts.getOrDefault(id, 0)));
        }
        return list;
    }

    /**
     * 广播打开阵营选择界面包给所有玩家
     */
    public static void broadcastOpenFactionScreen() {
        MinecraftServer server = Espetro.getServer();
        if (server != null) {
            OpenFactionScreenPacket packet = new OpenFactionScreenPacket();
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                NET.send(PacketDistributor.PLAYER.with(() -> player), packet);
            }
        }
    }

    /**
     * 发送等待状态消息给指定玩家
     * @param player 目标玩家
     * @param message 消息内容
     * @param isActionBar 是否使用操作栏显示
     */
    public static void sendWaitingStatus(ServerPlayer player, String message, boolean isActionBar) {
        NET.send(PacketDistributor.PLAYER.with(() -> player), new WaitingStatusPacket(message, isActionBar));
    }

    /**
     * 广播等待状态消息给所有已准备但等待中的玩家
     */
    public static void broadcastWaitingStatus(String message, boolean isActionBar) {
        MinecraftServer server = Espetro.getServer();
        if (server != null) {
            WaitingStatusPacket packet = new WaitingStatusPacket(message, isActionBar);
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                NET.send(PacketDistributor.PLAYER.with(() -> player), packet);
            }
        }
    }

    /**
     * 广播指挥官投票界面给指定队伍的玩家
     * @param team "DEFEND" 或 "ATTACK"
     * @param timeRemaining 剩余时间（秒）
     */
    public static void broadcastCommanderVoteScreenForTeam(String team, int timeRemaining) {
        MinecraftServer server = Espetro.getServer();
        if (server == null) return;

        sendCommanderVoteScreenForTeamView(server, team, timeRemaining, -1);
        sendCommanderVoteScreenForTeamView(server, oppositeTeam(team), 0, timeRemaining);
    }

    private static void sendCommanderVoteScreenForTeamView(MinecraftServer server, String viewTeam,
                                                           int timeRemaining, int opponentTimeRemaining) {
        VoteManager voteManager = VoteManager.getInstance();

        // 收集该队伍玩家名
        Set<UUID> teamUuids = "ATTACK".equals(viewTeam) ? voteManager.getAttackPlayers() : voteManager.getDefendPlayers();
        List<String> teamPlayers = new ArrayList<>();
        for (UUID uuid : teamUuids) {
            ServerPlayer player = server.getPlayerList().getPlayer(uuid);
            if (player != null) {
                teamPlayers.add(player.getName().getString());
            }
        }

        // 获取对手编制信息
        String opponentTeamName = teamDisplayName(oppositeTeam(viewTeam));
        String opponentFaction = getOpponentFactionDisplayName(viewTeam);

        CommanderVotePacket packet = new CommanderVotePacket(viewTeam, teamPlayers, timeRemaining,
            opponentTeamName, opponentFaction, opponentTimeRemaining);

        // 只发送给该队伍的玩家
        for (UUID uuid : teamUuids) {
            ServerPlayer player = server.getPlayerList().getPlayer(uuid);
            if (player != null) {
                NET.send(PacketDistributor.PLAYER.with(() -> player), packet);
            }
        }
    }

    /**
     * 发送指挥官投票界面给单个指定玩家（用于中途加入者同步）
     * @param player 目标玩家
     * @param team 玩家所在队伍 "DEFEND" 或 "ATTACK"
     * @param timeRemaining 投票剩余时间（秒）
     */
    public static void sendCommanderVoteScreenToPlayer(ServerPlayer player, String team, int timeRemaining) {
        MinecraftServer server = Espetro.getServer();
        if (server == null) return;

        VoteManager voteManager = VoteManager.getInstance();

        // 收集该队伍玩家名
        Set<UUID> teamUuids = "ATTACK".equals(team) ? voteManager.getAttackPlayers() : voteManager.getDefendPlayers();
        List<String> teamPlayers = new ArrayList<>();
        for (UUID uuid : teamUuids) {
            ServerPlayer p = server.getPlayerList().getPlayer(uuid);
            if (p != null) {
                teamPlayers.add(p.getName().getString());
            }
        }

        // 获取对手编制信息
        String opponentTeamName = teamDisplayName(oppositeTeam(team));
        String opponentFaction = getOpponentFactionDisplayName(team);
        String activeTeam = voteManager.getCurrentVotingTeam();
        int ownTimeRemaining = team.equals(activeTeam) ? timeRemaining : 0;
        int opponentTimeRemaining = team.equals(activeTeam) ? -1 : timeRemaining;

        CommanderVotePacket packet = new CommanderVotePacket(team, teamPlayers, ownTimeRemaining,
            opponentTeamName, opponentFaction, opponentTimeRemaining);
        NET.send(PacketDistributor.PLAYER.with(() -> player), packet);
    }

    /**
     * 只给指定队伍发送编制选择界面（投票后即时反馈，不给对方发）
     */
    public static void sendClassSelectScreenForTeam(String team, int timeRemaining) {
        MinecraftServer server = Espetro.getServer();
        if (server == null) return;

        sendClassSelectScreenForTeamView(server, team, timeRemaining, -1);
    }

    /**
     * 广播编制选择界面给指定队伍的玩家（含对方倒计时同步）
     * @param team "DEFEND" 或 "ATTACK"
     * @param timeRemaining 剩余时间（秒）
     */
    public static void broadcastClassSelectScreenForTeam(String team, int timeRemaining) {
        MinecraftServer server = Espetro.getServer();
        if (server == null) return;

        sendClassSelectScreenForTeamView(server, team, timeRemaining, -1);
        sendClassSelectScreenForTeamView(server, oppositeTeam(team), 0, timeRemaining);
    }

    private static void sendClassSelectScreenForTeamView(MinecraftServer server, String viewTeam,
                                                         int timeRemaining, int opponentTimeRemaining) {
        VoteManager voteManager = VoteManager.getInstance();

        Set<UUID> teamUuids = "ATTACK".equals(viewTeam) ? voteManager.getAttackPlayers() : voteManager.getDefendPlayers();

        // 对手编制信息
        String opponentTeamName = teamDisplayName(oppositeTeam(viewTeam));
        String opponentFaction = getOpponentFactionDisplayName(viewTeam);
        List<ClassSelectScreenPacket.FactionInfo> factionList = getFactionListForTeam(viewTeam);

        for (UUID uuid : teamUuids) {
            ServerPlayer player = server.getPlayerList().getPlayer(uuid);
            if (player == null) continue;

            boolean isCommander = voteManager.isCommanderOf(uuid, viewTeam);
            ClassSelectScreenPacket packet = new ClassSelectScreenPacket(viewTeam, isCommander, factionList,
                timeRemaining, opponentTeamName, opponentFaction, opponentTimeRemaining,
                ClassSelectManager.getInstance().getPlayerFactionVote(uuid, viewTeam));
            NET.send(PacketDistributor.PLAYER.with(() -> player), packet);
        }
    }

    /**
     * 广播双方最终编制揭示界面给所有在线玩家。
     */
    public static void broadcastFactionRevealScreen(String attackFactionId, String defendFactionId, int durationSeconds) {
        MinecraftServer server = Espetro.getServer();
        if (server == null) return;

        FactionRevealPacket packet = new FactionRevealPacket(
            getFactionDisplayName(attackFactionId),
            getFactionDisplayName(defendFactionId),
            durationSeconds
        );

        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            NET.send(PacketDistributor.PLAYER.with(() -> player), packet);
        }
    }

    /**
     * 发送投票
     */
    public static void sendCastVote(String targetPlayerName) {
        NET.sendToServer(new CastVotePacket(targetPlayerName));
    }

    /**
     * 广播游戏阶段到所有玩家
     */
    public static void broadcastGamePhase(GamePhase phase) {
        MinecraftServer server = Espetro.getServer();
        if (server == null) return;
        GamePhaseSyncPacket packet = new GamePhaseSyncPacket(phase);
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            NET.send(PacketDistributor.PLAYER.with(() -> player), packet);
        }
    }

    /**
     * 广播职业选择界面给所有玩家
     * 根据玩家所在队伍的编制发送对应的职业列表
     */
    public static void broadcastClassSelectionScreen(String attackFactionId, String defendFactionId) {
        MinecraftServer server = Espetro.getServer();
        if (server == null) return;

        FactionDataLoader loader = org.espetro.team.FactionDataProvider.getOrCreateLoader();

        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            String playerTeam = Espetro.getPlayerTeam(player);
            if (playerTeam == null) continue;

            String factionId = "ATTACK".equals(playerTeam) ? attackFactionId : defendFactionId;
            if (factionId == null) continue;

            OpenClassSelectionPacket packet = new OpenClassSelectionPacket(factionId, loader);
            NET.send(PacketDistributor.PLAYER.with(() -> player), packet);
        }
    }

    /**
     * 发送职业选择界面给指定玩家
     */
    public static void sendClassSelectionScreen(ServerPlayer player, String factionId) {
        FactionDataLoader loader = org.espetro.team.FactionDataProvider.getOrCreateLoader();
        OpenClassSelectionPacket packet = new OpenClassSelectionPacket(factionId, loader);
        NET.send(PacketDistributor.PLAYER.with(() -> player), packet);
    }

    /**
     * 广播兵力统计给所有玩家
     */
    public static void broadcastTroopCounts(int attack, int defend) {
        MinecraftServer server = Espetro.getServer();
        if (server == null) return;
        TroopCountSyncPacket packet = new TroopCountSyncPacket(attack, defend);
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            NET.send(PacketDistributor.PLAYER.with(() -> player), packet);
        }
    }

    /**
     * 向指挥官发送载具部署界面
     */
    /**
     * 向指挥官发送载具部署界面
     */
    public static void sendVehicleDeployScreen(ServerPlayer player, String factionId) {
        java.util.Map<String, org.espetro.vehicle.VehicleConfig.VehicleTypeConfig> configs =
            org.espetro.vehicle.VehicleConfig.getFactionVehicles(factionId);
        java.util.List<VehicleDeployScreenPacket.VehicleInfo> list = new java.util.ArrayList<>();

        org.espetro.vehicle.VehicleManager vm = org.espetro.vehicle.VehicleManager.getInstance();
        for (java.util.Map.Entry<String, org.espetro.vehicle.VehicleConfig.VehicleTypeConfig> entry : configs.entrySet()) {
            String type = entry.getKey();
            org.espetro.vehicle.VehicleConfig.VehicleTypeConfig cfg = entry.getValue();
            int current = vm.getActiveCount(factionId, type);
            long cooldown = vm.getCooldownRemaining(factionId, type);
            String displayName = org.espetro.vehicle.VehicleManager.getDisplayName(factionId, type);

            list.add(new VehicleDeployScreenPacket.VehicleInfo(
                type, displayName, cfg.max, current, (int)(cooldown / 1000), cfg.respawnMinutes));
        }

        NET.send(PacketDistributor.PLAYER.with(() -> player), new VehicleDeployScreenPacket(list));
    }

    /**
     * 旧复活点选择入口的兼容转发：统一改为发送 mutil 部署面板。
     */
    public static void sendDeployPointSelectScreen(ServerPlayer player) {
        sendUnifiedDeployScreen(player, -1);
    }

    /**
     * 发送统一部署主界面给指定玩家
     * 集成：职业选择、复活点选择、载具部署、小队选择、地图
     */
    public static void sendUnifiedDeployScreen(ServerPlayer player, int deployTimeRemaining) {
        MinecraftServer server = Espetro.getServer();
        if (server == null) return;

        String team = Espetro.getPlayerTeam(player);
        if (team == null) return;

        VoteManager voteManager = VoteManager.getInstance();
        ClassSelectManager selectManager = ClassSelectManager.getInstance();
        org.espetro.bastion.BastionManager bm = org.espetro.bastion.BastionManager.getInstance();
        FactionDataLoader loader = FactionDataProvider.getOrCreateLoader();

        String factionId = "ATTACK".equals(team)
            ? selectManager.getFinalAttackClass()
            : selectManager.getFinalDefendClass();
        if (factionId == null) factionId = team;

        // === 职业数据 ===
        FactionDataLoader.FactionData factionData = loader.getFaction(factionId);
        String factionName = factionData != null ? factionData.name : factionId;
        String factionDesc = factionData != null ? factionData.description : "";
        String factionIcon = factionData != null ? (factionData.icon != null ? factionData.icon : "") : "";

        java.util.List<UnifiedDeployScreenPacket.ClassInfo> classList = new java.util.ArrayList<>();
        java.util.Map<String, Integer> classCountMap = new java.util.HashMap<>();
        FactionDataLoader.ClassKitData[] kits = loader.getClassesForFaction(factionId);
        if (kits != null) {
            for (FactionDataLoader.ClassKitData kit : kits) {
                int count = ClassCountManager.getInstance().getCount(team, kit.id);
                classList.add(new UnifiedDeployScreenPacket.ClassInfo(
                    kit.id, kit.name, kit.description, kit.role,
                    kit.maxPlayers, count, kit.troopValue, kit.healthBonus, kit.speedBonus
                ));
                classCountMap.put(kit.id, count);
            }
        }

        // === 复活点 / 部署点数据 ===
        boolean hasDeploy = false;
        String deployPos = "";
        org.espetro.bastion.BastionManager.DeployPoint dp = bm.getPlayerDeployPoint(player.getUUID());
        if (dp != null && dp.pos != null) {
            hasDeploy = true;
            deployPos = dp.pos.getX() + ", " + dp.pos.getY() + ", " + dp.pos.getZ();
        }

        java.util.List<UnifiedDeployScreenPacket.BastionItem> bastionList = new java.util.ArrayList<>();
        for (org.espetro.bastion.BastionData bd : bm.getTeamBastions(team)) {
            net.minecraft.core.BlockPos armorStandPos = bm.getRecordedArmorStandPosition(bd);
            if (armorStandPos == null) {
                continue;
            }
            bastionList.add(new UnifiedDeployScreenPacket.BastionItem(
                bd.getBastionId(), bd.getName(),
                armorStandPos.getX() + ", " + armorStandPos.getY() + ", " + armorStandPos.getZ()
            ));
        }
        bastionList.addAll(org.espetro.team.TeamPackManager.getInstance().getDeployItemsForPlayer(player));

        // 防守方在部署阶段可使用前哨基地
        if ("DEFEND".equals(team) && org.espetro.team.OutpostManager.getInstance().isAvailable()) {
            var outposts = org.espetro.team.OutpostManager.getInstance().getOutposts();
            for (int i = 0; i < outposts.size(); i++) {
                var op = outposts.get(i);
                // 用特殊 UUID 标记前哨基地：MSB=0, LSB=index+1
                bastionList.add(new UnifiedDeployScreenPacket.BastionItem(
                    new java.util.UUID(0L, i + 1L),
                    "§d前哨: " + op.name,
                    op.getPosString()
                ));
            }
        }

        // 载具部署已分离到独立界面，J 键主面板不再枚举载具运行时状态。
        boolean isCmd = voteManager.isCommanderOf(player.getUUID(), team);
        java.util.List<UnifiedDeployScreenPacket.VehicleInfo> vehicleList = java.util.Collections.emptyList();

        // === 小队数据 ===
        java.util.List<UnifiedDeployScreenPacket.SquadInfo> squadList = buildSquadInfoList(team);
        java.util.List<String> commanderNames = getCommanderNames(team);
        int mySquadId = SquadManager.getInstance().getPlayerSquadId(player.getUUID());

        UnifiedDeployScreenPacket packet = new UnifiedDeployScreenPacket(
            factionId, factionName, factionDesc, factionIcon,
            classList, classCountMap,
            hasDeploy, deployPos, bastionList,
            isCmd, vehicleList,
            squadList, mySquadId,
            deployTimeRemaining, team,
            commanderNames, GameConfig.getTeammateNameTagDistance(),
            bm.isWaitingForBastion(player.getUUID()),
            org.espetro.team.OutpostManager.getInstance()
                .getRedeployCooldownRemaining(player.getUUID())
        );

        NET.send(PacketDistributor.PLAYER.with(() -> player), packet);
    }

    /**
     * 同步指定玩家的班组小队数据。
     */
    public static void sendSquadSync(ServerPlayer player) {
        String team = Espetro.getPlayerTeam(player);
        if (team == null) return;

        sendSquadSync(player, team, buildSquadInfoList(team), getCommanderNames(team));
    }

    /**
     * 同步某一攻/守方所有在线玩家的班组小队数据。
     */
    public static void syncSquadsToTeam(String team) {
        MinecraftServer server = Espetro.getServer();
        if (server == null || team == null) return;

        java.util.List<UnifiedDeployScreenPacket.SquadInfo> squadList = buildSquadInfoList(team);
        java.util.List<String> commanderNames = getCommanderNames(team);
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (team.equals(Espetro.getPlayerTeam(player))) {
                sendSquadSync(player, team, squadList, commanderNames);
            }
        }
    }

    private static void sendSquadSync(ServerPlayer player, String team,
                                      java.util.List<UnifiedDeployScreenPacket.SquadInfo> squadList,
                                      java.util.List<String> commanderNames) {
        SquadSyncPacket packet = new SquadSyncPacket(
            team,
            squadList,
            SquadManager.getInstance().getPlayerSquadId(player.getUUID()),
            commanderNames,
            GameConfig.getTeammateNameTagDistance()
        );
        NET.send(PacketDistributor.PLAYER.with(() -> player), packet);
    }

    private static java.util.List<UnifiedDeployScreenPacket.SquadInfo> buildSquadInfoList(String team) {
        java.util.List<UnifiedDeployScreenPacket.SquadInfo> squadList = new java.util.ArrayList<>();
        java.util.Set<UUID> commanderUuids = getCommanderUuids(team);
        for (SquadManager.SquadSnapshot squad : SquadManager.getInstance().getSquadSnapshots(team)) {
            java.util.List<UnifiedDeployScreenPacket.SquadMemberInfo> members = new java.util.ArrayList<>();
            for (SquadManager.MemberSnapshot member : squad.members) {
                members.add(new UnifiedDeployScreenPacket.SquadMemberInfo(
                    member.playerName, member.className, member.leader, commanderUuids.contains(member.uuid)));
            }
            squadList.add(new UnifiedDeployScreenPacket.SquadInfo(
                squad.id, squad.name, members.size(), squad.maxMembers, squad.locked,
                squad.leaderName, members
            ));
        }
        return squadList;
    }

    private static java.util.List<String> getCommanderNames(String team) {
        MinecraftServer server = Espetro.getServer();
        java.util.List<String> names = new java.util.ArrayList<>();
        if (server == null) return names;

        for (UUID uuid : getCommanderUuids(team)) {
            ServerPlayer player = server.getPlayerList().getPlayer(uuid);
            if (player != null) {
                names.add(player.getName().getString());
            }
        }
        return names;
    }

    private static java.util.Set<UUID> getCommanderUuids(String team) {
        java.util.Set<UUID> result = new java.util.HashSet<>();
        VoteManager voteManager = VoteManager.getInstance();
        if ("ATTACK".equals(team)) {
            UUID uuid = voteManager.getAttackCommander();
            if (uuid != null) result.add(uuid);
        } else if ("DEFEND".equals(team)) {
            UUID uuid = voteManager.getDefendCommander();
            if (uuid != null) result.add(uuid);
        }
        return result;
    }

    /**
     * 获取对手队伍的已定编制显示名称。
     * 如果对方编制尚未确定，返回 null。
     */
    private static String getOpponentFactionDisplayName(String myTeam) {
        org.espetro.team.ClassSelectManager selectManager =
            org.espetro.team.ClassSelectManager.getInstance();

        String opponentFactionId;
        if ("ATTACK".equals(myTeam)) {
            opponentFactionId = selectManager.getFinalDefendClass();
        } else {
            opponentFactionId = selectManager.getFinalAttackClass();
        }

        return getFactionDisplayName(opponentFactionId);
    }

    private static String getFactionDisplayName(String factionId) {
        if (factionId == null || factionId.isEmpty()) return null;

        org.espetro.team.FactionDataLoader loader =
            org.espetro.team.FactionDataProvider.getOrCreateLoader();
        if (loader != null) {
            org.espetro.team.FactionDataLoader.FactionData faction =
                loader.getFaction(factionId);
            if (faction != null && faction.name != null) {
                return faction.name;
            }
        }
        return factionId;
    }

    private static String oppositeTeam(String team) {
        return "ATTACK".equals(team) ? "DEFEND" : "ATTACK";
    }

    private static String teamDisplayName(String team) {
        return "ATTACK".equals(team) ? "进攻方" : "防守方";
    }

    /**
     * 请求指挥官技能同步（C→S），服务端返回 CommanderSkillSyncPacket
     */
    public static void requestCommanderSkillSync() {
        NET.sendToServer(CommanderSkillPacket.query());
    }

    /**
     * 发送指挥官技能激活请求（C→S）
     */
    public static void sendCommanderSkillActivate(org.espetro.team.CommanderSkillType type) {
        NET.sendToServer(CommanderSkillPacket.activate(type));
    }

    /**
     * 发送指挥官技能同步包给指定玩家（S→C）
     */
    public static void sendCommanderSkillSync(ServerPlayer player) {
        boolean isCommander = org.espetro.team.VoteManager.getInstance().isCommander(player.getUUID());
        java.util.Map<String, Integer> cooldowns =
            org.espetro.team.CommanderSkillManager.getInstance().getCooldownData(player.getUUID());
        CommanderSkillSyncPacket packet = new CommanderSkillSyncPacket(isCommander, cooldowns);
        NET.send(PacketDistributor.PLAYER.with(() -> player), packet);
    }
}
