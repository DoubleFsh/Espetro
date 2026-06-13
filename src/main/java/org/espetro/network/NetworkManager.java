package org.espetro.network;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;
import org.espetro.Espetro;
import org.espetro.team.ClassCountManager;
import org.espetro.team.ClassSelectManager;
import org.espetro.team.FactionDataLoader;
import org.espetro.team.FactionDataProvider;
import org.espetro.team.GamePhase;
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

    public static final String PROTOCOL_VERSION = "1.0";

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
     * 请求当前游戏状态（C→S）
     */
    public static void requestGameState() {
        NET.sendToServer(new RequestGameStatePacket());
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
        ClassSelectScreenPacket packet = new ClassSelectScreenPacket(team, isCommander, factionList, timeRemaining);
        NET.send(PacketDistributor.PLAYER.with(() -> player), packet);
    }

    /**
     * 根据队伍获取可选编制列表（服务端调用）
     * 攻守双方使用同一个随机编制池
     */
    private static List<ClassSelectScreenPacket.FactionInfo> getFactionListForTeam(String team) {
        List<String> pool = ClassSelectManager.getInstance().getSelectedFactionPool();
        FactionDataLoader loader = FactionDataProvider.getOrCreateLoader();
        List<ClassSelectScreenPacket.FactionInfo> list = new ArrayList<>();

        if (pool != null && !pool.isEmpty()) {
            for (String id : pool) {
                FactionDataLoader.FactionData faction = loader.getFaction(id);
                String name = faction != null ? faction.name : id;
                list.add(new ClassSelectScreenPacket.FactionInfo(id, name));
            }
            return list;
        }
        // fallback：如果编制池未生成，返回所有编制
        for (FactionDataLoader.FactionData faction : loader.getFactionArray()) {
            if (faction != null && faction.id != null && !faction.id.isEmpty()) {
                if (loader.getClassesForFaction(faction.id).length == 0) continue;
                list.add(new ClassSelectScreenPacket.FactionInfo(faction.id, faction.name));
            }
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

        VoteManager voteManager = VoteManager.getInstance();

        // 收集该队伍玩家名
        Set<UUID> teamUuids = "ATTACK".equals(team) ? voteManager.getAttackPlayers() : voteManager.getDefendPlayers();
        List<String> teamPlayers = new ArrayList<>();
        for (UUID uuid : teamUuids) {
            ServerPlayer player = server.getPlayerList().getPlayer(uuid);
            if (player != null) {
                teamPlayers.add(player.getName().getString());
            }
        }

        CommanderVotePacket packet = new CommanderVotePacket(team, teamPlayers, timeRemaining);

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

        CommanderVotePacket packet = new CommanderVotePacket(team, teamPlayers, timeRemaining);
        NET.send(PacketDistributor.PLAYER.with(() -> player), packet);
    }

    /**
     * 广播编制选择界面给指定队伍的玩家
     * @param team "DEFEND" 或 "ATTACK"
     * @param timeRemaining 剩余时间（秒）
     */
    public static void broadcastClassSelectScreenForTeam(String team, int timeRemaining) {
        MinecraftServer server = Espetro.getServer();
        if (server == null) return;

        VoteManager voteManager = VoteManager.getInstance();
        ClassSelectManager selectManager = ClassSelectManager.getInstance();

        Set<UUID> teamUuids = "ATTACK".equals(team) ? voteManager.getAttackPlayers() : voteManager.getDefendPlayers();

        for (UUID uuid : teamUuids) {
            ServerPlayer player = server.getPlayerList().getPlayer(uuid);
            if (player == null) continue;

            boolean isCommander = voteManager.isCommanderOf(uuid, team);
            List<ClassSelectScreenPacket.FactionInfo> factionList = getFactionListForTeam(team);
            ClassSelectScreenPacket packet = new ClassSelectScreenPacket(team, isCommander, factionList, timeRemaining);
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

        // === 载具数据（仅指挥官有） ===
        boolean isCmd = voteManager.isCommanderOf(player.getUUID(), team);
        java.util.List<UnifiedDeployScreenPacket.VehicleInfo> vehicleList = new java.util.ArrayList<>();
        if (isCmd) {
            java.util.Map<String, org.espetro.vehicle.VehicleConfig.VehicleTypeConfig> configs =
                org.espetro.vehicle.VehicleConfig.getFactionVehicles(factionId);
            org.espetro.vehicle.VehicleManager vm = org.espetro.vehicle.VehicleManager.getInstance();
            for (java.util.Map.Entry<String, org.espetro.vehicle.VehicleConfig.VehicleTypeConfig> entry : configs.entrySet()) {
                String type = entry.getKey();
                org.espetro.vehicle.VehicleConfig.VehicleTypeConfig cfg = entry.getValue();
                int current = vm.getActiveCount(factionId, type);
                long cooldown = vm.getCooldownRemaining(factionId, type);
                String displayName = org.espetro.vehicle.VehicleManager.getDisplayName(factionId, type);
                vehicleList.add(new UnifiedDeployScreenPacket.VehicleInfo(
                    type, displayName, cfg.max, current, (int)(cooldown / 1000), cfg.respawnMinutes));
            }
        }

        // === 小队数据（暂用默认6小队） ===
        java.util.List<UnifiedDeployScreenPacket.SquadInfo> squadList = new java.util.ArrayList<>();
        String[] squadNames = {"A小队", "B小队", "C小队", "D小队", "E小队", "F小队"};
        for (int i = 0; i < 6; i++) {
            squadList.add(new UnifiedDeployScreenPacket.SquadInfo(i, squadNames[i], 0, 9, false));
        }

        UnifiedDeployScreenPacket packet = new UnifiedDeployScreenPacket(
            factionId, factionName, factionDesc, factionIcon,
            classList, classCountMap,
            hasDeploy, deployPos, bastionList,
            isCmd, vehicleList,
            squadList, 0,
            deployTimeRemaining, team
        );

        NET.send(PacketDistributor.PLAYER.with(() -> player), packet);
    }
}
