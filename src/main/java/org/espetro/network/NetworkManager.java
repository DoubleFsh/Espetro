package org.espetro.network;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;
import org.espetro.Espetro;
import org.espetro.config.GameConfig;
import org.espetro.mapconfig.BattlefieldContext;
import org.espetro.team.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 网络管理器
 * 注册所有数据包
 */
public class NetworkManager {

    public static final String PROTOCOL_VERSION = "1.33";

    public static final SimpleChannel NET = NetworkRegistry.newSimpleChannel(
        ResourceLocation.fromNamespaceAndPath(Espetro.MOD_ID, "main"),
        () -> PROTOCOL_VERSION,
        PROTOCOL_VERSION::equals,
        PROTOCOL_VERSION::equals
    );

    private static int packetId = 0;
    private static final int FULL_DEPLOY_PACKETS_PER_TICK = 8;
    /** 单张编制图允许随包发送的最大字节数，超过则回退客户端本地加载。 */
    private static final int MAX_SELECTION_IMAGE_BYTES = 180_000;
    /** 整个编制选择包内图片数据的总预算，避免超过 Forge 网络包 1MB 上限。 */
    private static final int MAX_SELECTION_IMAGE_TOTAL_BYTES = 700_000;
    private static final Map<UUID, QueuedDeployScreen> QUEUED_FULL_DEPLOY_SCREENS = new LinkedHashMap<>();

    public static int nextId() {
        return packetId++;
    }

    public static void queueUnifiedDeployScreen(ServerPlayer player, int deployTimeRemaining) {
        queueUnifiedDeployScreen(player, deployTimeRemaining, false);
    }

    public static void queueUnifiedDeployScreen(ServerPlayer player, int deployTimeRemaining,
                                                 boolean playEntryAudio) {
        if (player != null) {
            QUEUED_FULL_DEPLOY_SCREENS.put(player.getUUID(),
                new QueuedDeployScreen(deployTimeRemaining, playEntryAudio));
        }
    }

    public static void drainQueuedFullScreens() {
        MinecraftServer server = Espetro.getServer();
        if (server == null || QUEUED_FULL_DEPLOY_SCREENS.isEmpty()) {
            return;
        }
        int sent = 0;
        var iterator = QUEUED_FULL_DEPLOY_SCREENS.entrySet().iterator();
        while (iterator.hasNext() && sent < FULL_DEPLOY_PACKETS_PER_TICK) {
            Map.Entry<UUID, QueuedDeployScreen> entry = iterator.next();
            iterator.remove();
            ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            if (player != null) {
                QueuedDeployScreen queued = entry.getValue();
                sendUnifiedDeployScreen(player, queued.deployTimeRemaining());
                if (queued.playEntryAudio()) {
                    org.espetro.audio.FactionAudioCoordinator.sendEntry(player);
                }
                sent++;
            }
        }
    }

    public static void clearQueuedFullScreens() {
        QUEUED_FULL_DEPLOY_SCREENS.clear();
    }

    private record QueuedDeployScreen(int deployTimeRemaining, boolean playEntryAudio) {
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

        // 客户端补加实体后请求重同步位置/数据（方案 A：修复开局载具错位）
        NET.registerMessage(
            nextId(),
            EntityResyncRequestPacket.class,
            EntityResyncRequestPacket::write,
            EntityResyncRequestPacket::read,
            EntityResyncRequestPacket::handle
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

        // 载具信息请求包（C→S）
        NET.registerMessage(
            nextId(),
            RequestVehicleInfoPacket.class,
            RequestVehicleInfoPacket::write,
            RequestVehicleInfoPacket::read,
            RequestVehicleInfoPacket::handle
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

        NET.registerMessage(
            nextId(),
            RadialActionPacket.class,
            RadialActionPacket::write,
            RadialActionPacket::read,
            RadialActionPacket::handle
        );

        // ===== multi-dimension battlefield packets (protocol 1.11) =====
        NET.registerMessage(nextId(), MapVoteStatePacket.class, MapVoteStatePacket::write, MapVoteStatePacket::read, MapVoteStatePacket::handle);
        NET.registerMessage(nextId(), MapVoteCastPacket.class, MapVoteCastPacket::write, MapVoteCastPacket::read, MapVoteCastPacket::handle);
        NET.registerMessage(nextId(), OpenMapVoteScreenPacket.class, OpenMapVoteScreenPacket::write, OpenMapVoteScreenPacket::read, OpenMapVoteScreenPacket::handle);
        NET.registerMessage(nextId(), TeamSelectStatePacket.class, TeamSelectStatePacket::write, TeamSelectStatePacket::read, TeamSelectStatePacket::handle);
        NET.registerMessage(nextId(), MatchStatsSyncPacket.class, MatchStatsSyncPacket::write, MatchStatsSyncPacket::read, MatchStatsSyncPacket::handle);
        NET.registerMessage(nextId(), MatchStatsActionPacket.class, MatchStatsActionPacket::write, MatchStatsActionPacket::read, MatchStatsActionPacket::handle);
        NET.registerMessage(nextId(), GovernanceStatePacket.class, GovernanceStatePacket::write, GovernanceStatePacket::read, GovernanceStatePacket::handle);
        NET.registerMessage(nextId(), GovernanceActionPacket.class, GovernanceActionPacket::write, GovernanceActionPacket::read, GovernanceActionPacket::handle);
        NET.registerMessage(nextId(), SquadCreateWithCategoryPacket.class, SquadCreateWithCategoryPacket::write, SquadCreateWithCategoryPacket::read, SquadCreateWithCategoryPacket::handle);
        NET.registerMessage(nextId(), OpenHubScreenPacket.class, OpenHubScreenPacket::write, OpenHubScreenPacket::read, OpenHubScreenPacket::handle);
        NET.registerMessage(nextId(), RoundEndPacket.class, RoundEndPacket::write, RoundEndPacket::read, RoundEndPacket::handle);
        // 新手教程（此前仅有 send API，未 register → 点击教程直接 Invalid message 崩溃）
        NET.registerMessage(nextId(), TutorialSyncPacket.class, TutorialSyncPacket::write, TutorialSyncPacket::read, TutorialSyncPacket::handle);
        NET.registerMessage(nextId(), TutorialActionPacket.class, TutorialActionPacket::write, TutorialActionPacket::read, TutorialActionPacket::handle);
        // 编制选择倒计时轻量包（避免每秒全量 ClassSelectScreenPacket）
        NET.registerMessage(nextId(), ClassSelectTimerPacket.class, ClassSelectTimerPacket::write, ClassSelectTimerPacket::read, ClassSelectTimerPacket::handle);
        NET.registerMessage(nextId(), EquipZoneSyncPacket.class, EquipZoneSyncPacket::write, EquipZoneSyncPacket::read, EquipZoneSyncPacket::handle);
        NET.registerMessage(nextId(), RadioRadialPacket.class,
            RadioRadialPacket::write, RadioRadialPacket::read, RadioRadialPacket::handle);
        // 战局倒计时包
        NET.registerMessage(nextId(), BattleTimerPacket.class,
            BattleTimerPacket::write, BattleTimerPacket::read, BattleTimerPacket::handle);
        // 组队匹配
        NET.registerMessage(nextId(), PartyActionPacket.class,
            PartyActionPacket::write, PartyActionPacket::read, PartyActionPacket::handle);
        NET.registerMessage(nextId(), PartyListPacket.class,
            PartyListPacket::write, PartyListPacket::read, PartyListPacket::handle);
        NET.registerMessage(nextId(), VehicleSupplyActionPacket.class,
            VehicleSupplyActionPacket::write, VehicleSupplyActionPacket::read, VehicleSupplyActionPacket::handle);
        NET.registerMessage(nextId(), VehicleSupplySyncPacket.class,
            VehicleSupplySyncPacket::write, VehicleSupplySyncPacket::read, VehicleSupplySyncPacket::handle);
        NET.registerMessage(nextId(), FobSupplySyncPacket.class,
            FobSupplySyncPacket::write, FobSupplySyncPacket::read, FobSupplySyncPacket::handle);
        NET.registerMessage(nextId(), OutpostSupplySyncPacket.class,
            OutpostSupplySyncPacket::write, OutpostSupplySyncPacket::read, OutpostSupplySyncPacket::handle);
        NET.registerMessage(nextId(), BuildFortificationPacket.class,
            BuildFortificationPacket::write, BuildFortificationPacket::read, BuildFortificationPacket::handle);
        NET.registerMessage(nextId(), FortificationCatalogPacket.class,
            FortificationCatalogPacket::write, FortificationCatalogPacket::read,
            FortificationCatalogPacket::handle);
        NET.registerMessage(nextId(), FortificationPreviewPacket.class,
            FortificationPreviewPacket::write, FortificationPreviewPacket::read,
            FortificationPreviewPacket::handle);
        NET.registerMessage(nextId(), FortificationPlacementPacket.class,
            FortificationPlacementPacket::write, FortificationPlacementPacket::read,
            FortificationPlacementPacket::handle);
        NET.registerMessage(nextId(), FortificationWorkPacket.class,
            FortificationWorkPacket::write, FortificationWorkPacket::read,
            FortificationWorkPacket::handle);
        NET.registerMessage(nextId(), FortificationProgressPacket.class,
            FortificationProgressPacket::write, FortificationProgressPacket::read,
            FortificationProgressPacket::handle);
        NET.registerMessage(nextId(), AudioCuePacket.class,
            AudioCuePacket::write, AudioCuePacket::read, AudioCuePacket::handle);
        NET.registerMessage(nextId(), TaczGunPackSyncChunkPacket.class,
            TaczGunPackSyncChunkPacket::write, TaczGunPackSyncChunkPacket::read,
            TaczGunPackSyncChunkPacket::handle);
        NET.registerMessage(nextId(), DeployPointSyncPacket.class,
            DeployPointSyncPacket::write, DeployPointSyncPacket::read,
            DeployPointSyncPacket::handle);

        NET.messageBuilder(RequestResupplyCatalogPacket.class, nextId(),
                NetworkDirection.PLAY_TO_SERVER)
            .encoder(RequestResupplyCatalogPacket::write)
            .decoder(RequestResupplyCatalogPacket::read)
            .consumerMainThread(RequestResupplyCatalogPacket::handle)
            .add();
        NET.messageBuilder(ResupplyCatalogPacket.class, nextId(),
                NetworkDirection.PLAY_TO_CLIENT)
            .encoder(ResupplyCatalogPacket::write)
            .decoder(ResupplyCatalogPacket::read)
            .consumerMainThread(ResupplyCatalogPacket::handle)
            .add();
        NET.messageBuilder(SelectResupplyEntryPacket.class, nextId(),
                NetworkDirection.PLAY_TO_SERVER)
            .encoder(SelectResupplyEntryPacket::write)
            .decoder(SelectResupplyEntryPacket::read)
            .consumerMainThread(SelectResupplyEntryPacket::handle)
            .add();
        NET.messageBuilder(ResupplyEntryDeltaPacket.class, nextId(),
                NetworkDirection.PLAY_TO_CLIENT)
            .encoder(ResupplyEntryDeltaPacket::write)
            .decoder(ResupplyEntryDeltaPacket::read)
            .consumerMainThread(ResupplyEntryDeltaPacket::handle)
            .add();
        NET.messageBuilder(CloseResupplySessionPacket.class, nextId(),
                NetworkDirection.PLAY_TO_SERVER)
            .encoder(CloseResupplySessionPacket::write)
            .decoder(CloseResupplySessionPacket::read)
            .consumerMainThread(CloseResupplySessionPacket::handle)
            .add();

        NET.messageBuilder(MountRequestPacket.class, nextId(), NetworkDirection.PLAY_TO_SERVER)
            .encoder(MountRequestPacket::write)
            .decoder(MountRequestPacket::read)
            .consumerMainThread(MountRequestPacket::handle)
            .add();
        NET.messageBuilder(MountProgressPacket.class, nextId(), NetworkDirection.PLAY_TO_CLIENT)
            .encoder(MountProgressPacket::write)
            .decoder(MountProgressPacket::read)
            .consumerMainThread(MountProgressPacket::handle)
            .add();
        NET.messageBuilder(DismountRequestPacket.class, nextId(), NetworkDirection.PLAY_TO_SERVER)
            .encoder(DismountRequestPacket::write)
            .decoder(DismountRequestPacket::read)
            .consumerMainThread(DismountRequestPacket::handle)
            .add();
        NET.messageBuilder(SeatSwitchReadyPacket.class, nextId(), NetworkDirection.PLAY_TO_SERVER)
            .encoder(SeatSwitchReadyPacket::write)
            .decoder(SeatSwitchReadyPacket::read)
            .consumerMainThread(SeatSwitchReadyPacket::handle)
            .add();
    }

    public static void sendBuildFortification(String fortId) {
        NET.sendToServer(new BuildFortificationPacket(fortId));
    }

    public static void sendFortificationPlacement(FortificationPlacementPacket.Action action,
                                                   UUID token,
                                                   net.minecraft.core.BlockPos anchor,
                                                   net.minecraft.core.Direction facing) {
        NET.sendToServer(new FortificationPlacementPacket(action, token, anchor, facing));
    }

    public static void sendFortificationWork(net.minecraft.core.BlockPos target, boolean build) {
        NET.sendToServer(FortificationWorkPacket.block(target, build));
    }

    public static void sendFortificationEntityWork(UUID target, boolean build) {
        NET.sendToServer(FortificationWorkPacket.entity(target, build));
    }

    public static void requestFortificationCatalog() {
        NET.sendToServer(FortificationCatalogPacket.request());
    }

    public static void sendRadioOpen(net.minecraft.core.BlockPos pos) {
        NET.sendToServer(RadioRadialPacket.openRequest(pos));
    }

    public static void sendRadioResupply(net.minecraft.core.BlockPos pos) {
        NET.sendToServer(RadioRadialPacket.resupply(pos));
    }

    /** 同步同阵营换装黄框区域。 */
    public static void sendEquipZones(ServerPlayer player) {
        if (player == null) return;
        List<EquipZoneSyncPacket.Zone> zones =
            org.espetro.team.ClassEquipmentZones.collectForPlayer(player);
        NET.send(PacketDistributor.PLAYER.with(() -> player), new EquipZoneSyncPacket(zones));
    }

    /** 向玩家发送载具职业选择（不依赖 Radio，战斗载具专用） */
    public static void sendVehicleClassSelect(ServerPlayer player, String factionId) {
        if (factionId == null) return;
        var loader = org.espetro.team.FactionDataProvider.getOrCreateLoader();
        var server = player.getServer();
        if (server != null) loader.ensureLoaded(server.getResourceManager());
        var kits = loader.getClassesForFaction(factionId);
        var counts = org.espetro.team.ClassCountManager.getInstance();
        String team = counts.getEffectivePlayerTeam(player.getUUID());
        int squadId = org.espetro.team.SquadManager.getInstance().getPlayerSquadId(player.getUUID());
        boolean inSquad = squadId != org.espetro.team.SquadManager.NO_SQUAD;
        int squadSize = inSquad
            ? org.espetro.team.SquadManager.getInstance().getSquadMemberUuids(team, squadId).size()
            : 0;
        int cooldown = counts.getClassSwitchCooldownRemaining(player.getUUID());
        var list = new java.util.ArrayList<RadioRadialPacket.ClassEntry>();
        if (kits != null) {
            for (var kit : kits) {
                if (kit == null) continue;
                int squadCount = counts.getSquadClassCountForViewer(
                    player.getUUID(), team, kit.id);
                int maxCount = kit.teamCount
                    ? Math.max(1, kit.maxPlayers)
                    : kit.maxPerSquad > 0 ? kit.maxPerSquad : Math.max(1, kit.maxPlayers);
                int teamCount = counts.getCount(team, kit.id);
                String denial = "";
                boolean cooldownBlocked = cooldown > 0;
                if (cooldownBlocked) {
                    denial = "职业切换冷却中，还需等待 " + cooldown + " 秒。";
                } else if (!inSquad) {
                    denial = "请先加入班组小队后再选择职业。";
                } else if (kit.teammatesNeed > 0 && squadSize < kit.teammatesNeed) {
                    denial = "小队达到 " + kit.teammatesNeed + " 人后才能选择该职业。";
                } else if (kit.teamCount && squadCount >= kit.maxPlayers) {
                    denial = "本小队该职业人数已满（" + squadCount + "/" + kit.maxPlayers + "）。";
                } else if (!kit.teamCount && teamCount >= kit.maxPlayers) {
                    denial = "该职业全队人数已满（" + teamCount + "/" + kit.maxPlayers + "）。";
                } else if (!kit.teamCount && kit.maxPerSquad > 0 && squadCount >= kit.maxPerSquad) {
                    denial = "本小队该职业人数已满（" + squadCount + "/" + kit.maxPerSquad + "）。";
                }
                boolean enabled = denial.isEmpty();
                var variants = new java.util.ArrayList<RadioRadialPacket.VariantEntry>();
                String defaultVariantId = "";
                if (kit.variants != null) {
                    var defaultVariant = kit.variants.get("default");
                    if (defaultVariant != null) defaultVariantId = defaultVariant.id;
                    else if (!kit.variants.isEmpty()) defaultVariantId = kit.variants.values().iterator().next().id;
                    for (var variant : kit.variants.values()) {
                        int variantCount = kit.teamCount
                            ? counts.countVariantInSquad(team, squadId, kit.id, variant.id)
                            : counts.getVariantCount(team, kit.id, variant.id);
                        boolean variantEnabled = enabled
                            && (!kit.strictCount || variantCount < variant.maxPlayers);
                        String variantDenial = denial;
                        if (enabled && !variantEnabled)
                            variantDenial = "该装备变体人数已满（" + variantCount + "/" + variant.maxPlayers + "）。";
                        variants.add(new RadioRadialPacket.VariantEntry(
                            variant.id, variant.name, variantCount, variant.maxPlayers,
                            kit.strictCount, variantEnabled, variantDenial));
                    }
                }
                String icon = kit.icon != null ? kit.icon : "";
                String iconImage = kit.iconImage != null ? kit.iconImage : "";
                list.add(new RadioRadialPacket.ClassEntry(
                    kit.id, kit.name, icon, iconImage,
                    defaultVariantId, squadCount, maxCount,
                    true, enabled, cooldownBlocked, denial, variants));
            }
        }
        NET.send(PacketDistributor.PLAYER.with(() -> player), RadioRadialPacket.classList(list));
    }

    private static void sendRadioClassList(ServerPlayer player, net.minecraft.core.BlockPos radioPos) {
        // 打开客户端 RadioRadialController，复用其职业选择逻辑
        NET.send(PacketDistributor.PLAYER.with(() -> player),
            RadioRadialPacket.openRequest(radioPos != null ? radioPos : net.minecraft.core.BlockPos.ZERO));
    }

    public static void broadcastEquipZonesForTeam(String team) {
        MinecraftServer server = Espetro.getServer();
        if (server == null || team == null) return;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (team.equals(Espetro.getPlayerTeam(player))) {
                sendEquipZones(player);
            }
        }
    }

    /**
     * 发送职业选择包
     */
    public static void sendClassSelect(String factionId, String classId) {
        NET.sendToServer(new ClassSelectPacket(factionId, classId));
    }

    public static void sendClassSelect(String factionId, String classId, String variantId) {
        NET.sendToServer(new ClassSelectPacket(factionId, classId, variantId));
    }

    public static void sendRadioClassSelect(String factionId, String classId, String variantId,
                                            net.minecraft.core.BlockPos radioPos) {
        NET.sendToServer(ClassSelectPacket.fromRadio(
            factionId, classId, variantId, radioPos));
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
     * 向同队在线玩家刷新部署面板数据（openScreen=false，不强制弹窗）。
     * <p>
     * 必须带上每人的 {@code squadCurrentCount} 等小队作用域字段，否则 J 面板灰显/人数错误。
     * 装备预览走 {@link ClassLoadoutPreviewResolver} 缓存，避免旧版「N×ItemParser」尖峰；
     * 仍比无缓存的 full 风暴轻一个数量级。打开面板仍用 {@link #sendUnifiedDeployScreen}。
     */
    public static void refreshUnifiedDeployScreensForTeam(String team) {
        MinecraftServer server = Espetro.getServer();
        if (server == null || team == null) {
            return;
        }
        int remaining = -1;
        if (org.espetro.team.GameStateManager.getInstance().getCurrentPhase()
            == org.espetro.team.GamePhase.DEPLOYING) {
            remaining = org.espetro.team.GameStateManager.getInstance()
                .getDeployTimeRemainingSeconds();
        }
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (team.equals(Espetro.getPlayerTeam(player))) {
                // 预览已缓存：主要成本是组包；保证小队人数/满员态正确
                syncUnifiedDeployScreen(player, remaining);
            }
        }
    }

    public static void broadcastClassCounts(String team, String factionId) {
        MinecraftServer server = Espetro.getServer();
        if (server == null || team == null || factionId == null || factionId.isBlank()) return;

        ClassCountManager countManager = ClassCountManager.getInstance();
        java.util.Map<String, Integer> counts = countManager.getCountsForFaction(team, factionId);
        java.util.Map<Integer, ClassCountSyncPacket> packetsBySquad = new java.util.HashMap<>();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (team.equals(countManager.getEffectivePlayerTeam(player.getUUID()))) {
                int squadId = SquadManager.getInstance().getPlayerSquadId(player.getUUID());
                ClassCountSyncPacket packet = packetsBySquad.computeIfAbsent(squadId, ignored ->
                    new ClassCountSyncPacket(
                        counts,
                        countManager.getSquadCountsForViewer(player.getUUID(), team, factionId),
                        countManager.getVariantCountsForViewer(player.getUUID(), team, factionId),
                        factionId));
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



    public static void sendRadialAction(RadialActionPacket.Action action) {
        NET.sendToServer(new RadialActionPacket(action));
    }

    /**
     * 创建班组小队。
     */
    public static void sendTutorialAction(byte action, String stepId) {
        try {
            NET.sendToServer(new TutorialActionPacket(action, stepId == null ? "" : stepId));
        } catch (Exception e) {
            Espetro.LOGGER.error("发送教程操作包失败", e);
        }
    }

    public static void sendTutorialReopen() {
        try {
            NET.sendToServer(TutorialActionPacket.reopen());
        } catch (Exception e) {
            Espetro.LOGGER.error("发送教程重开包失败", e);
        }
    }

    public static void sendTutorialNext(String stepId) {
        try {
            NET.sendToServer(TutorialActionPacket.next(stepId));
        } catch (Exception e) {
            Espetro.LOGGER.error("发送教程下一步包失败", e);
        }
    }


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

    public static void transferSquadLeader(java.util.UUID targetUuid) {
        if (targetUuid != null) {
            NET.sendToServer(SquadActionPacket.transferSquadLeader(targetUuid));
        }
    }

    public static void transferFireteamLeader(java.util.UUID targetUuid) {
        if (targetUuid != null) {
            NET.sendToServer(SquadActionPacket.transferFireteamLeader(targetUuid));
        }
    }

    public static void appointFireteamLeader(java.util.UUID targetUuid, org.espetro.team.Fireteam fireteam) {
        if (targetUuid != null && fireteam != null && fireteam != org.espetro.team.Fireteam.A) {
            NET.sendToServer(SquadActionPacket.appointFireteamLeader(targetUuid, fireteam));
        }
    }

    public static void assignFireteam(java.util.UUID targetUuid, org.espetro.team.Fireteam fireteam) {
        if (targetUuid != null && fireteam != null) {
            NET.sendToServer(SquadActionPacket.assignFireteam(targetUuid, fireteam));
        }
    }

    public static void lockSquad() {
        NET.sendToServer(SquadActionPacket.lock());
    }

    public static void unlockSquad() {
        NET.sendToServer(SquadActionPacket.unlock());
    }

    /**
     * 发送打开阵营选择界面包给指定玩家（同时发送当前队伍状态，含编制图片）。
     */
    public static void sendOpenFactionScreen(ServerPlayer player) {
        NET.send(PacketDistributor.PLAYER.with(() -> player), new OpenFactionScreenPacket());
        // 同时发送当前队伍选择状态（含人数 + 编制图片），让中途加入者看到实时数据
        sendCurrentTeamSelectState(player);
    }

    /** 向单个玩家发送当前的队伍选择状态。 */
    public static void sendCurrentTeamSelectState(ServerPlayer player) {
        MinecraftServer server = Espetro.getServer();
        if (server == null) return;
        GameStateManager gsm = GameStateManager.getInstance();
        int attack = 0, defend = 0;
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            String t = Espetro.getPlayerTeam(p);
            if ("ATTACK".equals(t)) attack++;
            else if ("DEFEND".equals(t)) defend++;
        }
        // 获取编制图片
        ClassSelectManager csm = ClassSelectManager.getInstance();
        String atkImg = getFactionSelectionImage(csm.getFinalAttackClass());
        String defImg = getFactionSelectionImage(csm.getFinalDefendClass());
        long end = server.overworld().getGameTime() + 999999L;
        String myTeam = Espetro.getPlayerTeam(player);
        NET.send(PacketDistributor.PLAYER.with(() -> player),
            new TeamSelectStatePacket(attack, defend, 0, end, false, myTeam, null, atkImg, defImg));
    }

    private static String getFactionSelectionImage(String factionId) {
        return getFactionSelectionImageStatic(factionId);
    }

    /** 公开版本，供 GameStateManager 等直接调用。 */
    public static String getFactionSelectionImageStatic(String factionId) {
        if (factionId == null || factionId.isEmpty()) return null;
        org.espetro.team.FactionDataLoader loader =
            org.espetro.team.FactionDataProvider.getOrCreateLoader();
        if (loader != null) {
            org.espetro.team.FactionDataLoader.FactionData faction =
                loader.getFaction(factionId);
            if (faction != null && faction.selectionImage != null && !faction.selectionImage.isEmpty()) {
                return faction.selectionImage;
            }
        }
        return null;
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
        int totalImageBytes = 0;

        for (String id : pool) {
            FactionDataLoader.FactionData faction = loader.getFaction(id);
            String name = faction != null ? faction.name : id;
            String selectionImage = faction != null ? faction.selectionImage : "";
            byte[] imageData = loadEsFactionsImage(selectionImage);
            if (imageData != null
                && (imageData.length > MAX_SELECTION_IMAGE_BYTES
                    || totalImageBytes + imageData.length > MAX_SELECTION_IMAGE_TOTAL_BYTES)) {
                // 图片太大就不随包发送；客户端会尝试从本地 EsFactions/ 或资源包加载。
                imageData = null;
            } else if (imageData != null) {
                totalImageBytes += imageData.length;
            }
            list.add(new ClassSelectScreenPacket.FactionInfo(
                id, name, selectionImage, voteCounts.getOrDefault(id, 0), imageData));
        }
        return list;
    }

    /**
     * 从服务端 EsFactions/ 目录加载编制图片字节。
     * 仅当 selectionImage 为简单文件名（不含冒号）且文件存在时返回数据。
     */
    private static byte[] loadEsFactionsImage(String selectionImage) {
        if (selectionImage == null || selectionImage.isBlank()) return null;
        // 含冒号的是 ResourceLocation 格式，从 jar/资源包加载，不需要发送字节
        if (selectionImage.contains(":")) return null;
        try {
            Path imagePath = FMLPaths.GAMEDIR.get().resolve("EsFactions")
                .resolve(selectionImage).normalize();
            Path esFactionsDir = FMLPaths.GAMEDIR.get().resolve("EsFactions").normalize();
            if (!imagePath.startsWith(esFactionsDir)) return null;
            if (!Files.isRegularFile(imagePath)) return null;
            if (Files.size(imagePath) > MAX_SELECTION_IMAGE_BYTES) return null;
            return Files.readAllBytes(imagePath);
        } catch (Exception e) {
            Espetro.LOGGER.debug("EsFactions 图片读取失败: {} ({})", selectionImage, e.toString());
            return null;
        }
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

    /**
     * 每秒轻量同步编制倒计时（不附带 faction 列表，避免整页刷新感）。
     */
    public static void broadcastClassSelectTimerForTeam(String team, int timeRemaining) {
        MinecraftServer server = Espetro.getServer();
        if (server == null || team == null) return;

        VoteManager voteManager = VoteManager.getInstance();
        ClassSelectManager selectManager = ClassSelectManager.getInstance();
        sendClassSelectTimerForTeamView(server, team, timeRemaining, -1, voteManager, selectManager);
        sendClassSelectTimerForTeamView(server, oppositeTeam(team), 0, timeRemaining, voteManager, selectManager);
    }

    private static void sendClassSelectTimerForTeamView(MinecraftServer server, String viewTeam,
                                                        int timeRemaining, int opponentTimeRemaining,
                                                        VoteManager voteManager,
                                                        ClassSelectManager selectManager) {
        Set<UUID> teamUuids = "ATTACK".equals(viewTeam)
            ? voteManager.getAttackPlayers() : voteManager.getDefendPlayers();
        for (UUID uuid : teamUuids) {
            ServerPlayer player = server.getPlayerList().getPlayer(uuid);
            if (player == null) continue;
            boolean isCommander = voteManager.isCommanderOf(uuid, viewTeam);
            String selected = selectManager.getPlayerFactionVote(uuid, viewTeam);
            NET.send(PacketDistributor.PLAYER.with(() -> player),
                new ClassSelectTimerPacket(timeRemaining, opponentTimeRemaining, selected, isCommander));
        }
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
            getFactionSelectionImage(attackFactionId),
            getFactionSelectionImage(defendFactionId),
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
        GamePhaseSyncPacket packet = new GamePhaseSyncPacket(
            phase,
            org.espetro.team.GameStateManager.getInstance().getCurrentMapFolder(),
            BattlefieldContext.getObjectiveMode());
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            NET.send(PacketDistributor.PLAYER.with(() -> player), packet);
        }
    }

    /**
     * 广播战局倒计时到所有在线玩家。
     */
    public static void broadcastBattleTimer(int remainingSeconds) {
        MinecraftServer server = Espetro.getServer();
        if (server == null) return;
        BattleTimerPacket packet = new BattleTimerPacket(remainingSeconds);
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
     * 向玩家发送只读载具信息界面
     */
    public static void sendVehicleDeployScreen(ServerPlayer player, String factionId) {
        sendVehicleDeployScreen(player, factionId, true);
    }

    public static void syncVehicleDeployScreen(ServerPlayer player, String factionId) {
        sendVehicleDeployScreen(player, factionId, false);
    }

    /** 客户端请求打开只读载具信息面板。 */
    public static void requestVehicleInfo() {
        NET.sendToServer(new RequestVehicleInfoPacket());
    }

    private static void sendVehicleDeployScreen(ServerPlayer player, String factionId,
                                                boolean openScreen) {
        java.util.Map<String, org.espetro.vehicle.VehicleConfig.VehicleTypeConfig> configs =
            org.espetro.vehicle.VehicleConfig.getFactionVehicles(factionId);
        java.util.List<VehicleDeployScreenPacket.VehicleInfo> list = new java.util.ArrayList<>();

        org.espetro.vehicle.VehicleManager vm = org.espetro.vehicle.VehicleManager.getInstance();
        for (java.util.Map.Entry<String, org.espetro.vehicle.VehicleConfig.VehicleTypeConfig> entry : configs.entrySet()) {
            String type = entry.getKey();
            org.espetro.vehicle.VehicleConfig.VehicleTypeConfig cfg = entry.getValue();
            String team = Espetro.getPlayerTeam(player);
            int current = team == null ? vm.getActiveCount(factionId, type)
                : vm.getActiveCount(team, factionId, type);
            long cooldown = vm.getCooldownRemaining(team, factionId, type);
            String displayName = org.espetro.vehicle.VehicleManager.getDisplayName(factionId, type);

            list.add(new VehicleDeployScreenPacket.VehicleInfo(
                type, displayName, cfg.max, current,
                System.currentTimeMillis() + cooldown, cfg.respawnMinutes));
        }

        NET.send(PacketDistributor.PLAYER.with(() -> player),
            new VehicleDeployScreenPacket(openScreen, list));
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
        sendUnifiedDeployScreen(player, deployTimeRemaining, true);
    }

    /**
     * 静默同步统一部署数据。面板已经打开时就地刷新；面板关闭时不得主动弹出。
     */
    public static void syncUnifiedDeployScreen(ServerPlayer player, int deployTimeRemaining) {
        sendUnifiedDeployScreen(player, deployTimeRemaining, false);
    }

    private static void sendUnifiedDeployScreen(
            ServerPlayer player, int deployTimeRemaining, boolean openScreen) {
        MinecraftServer server = Espetro.getServer();
        if (server == null) return;

        // The same packet backs the main GUI in both deployment and battle. Always
        // attach the current authoritative phase timer so opening J mid-battle does
        // not render an empty timer until a later BattleTimerPacket arrives.
        GameStateManager gameState = GameStateManager.getInstance();
        int phaseTimeRemaining = switch (gameState.getCurrentPhase()) {
            case DEPLOYING -> gameState.getDeployTimeRemainingSeconds();
            case BATTLE -> gameState.getBattleTimeRemainingSeconds();
            default -> deployTimeRemaining;
        };

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
            ClassCountManager counts = ClassCountManager.getInstance();
            boolean isLeader = SquadManager.getInstance().isSquadLeader(player.getUUID());
            for (FactionDataLoader.ClassKitData kit : kits) {
                // leaderOnly: 非队长不显示，后续向前补位
                if (kit.leaderOnly && !isLeader) continue;
                int count = counts.getEffectiveClassCountForViewer(player.getUUID(), team, kit.id);
                int squadCount = counts.getSquadClassCountForViewer(player.getUUID(), team, kit.id);
                java.util.List<UnifiedDeployScreenPacket.VariantInfo> variants = new java.util.ArrayList<>();
                if (kit.variants != null) {
                    for (FactionDataLoader.ClassVariantData variant : kit.variants.values()) {
                        int vCount = kit.teamCount
                            ? counts.countVariantInSquad(team,
                                org.espetro.team.SquadManager.getInstance().getPlayerSquadId(player.getUUID()),
                                kit.id, variant.id)
                            : counts.getVariantCount(team, kit.id, variant.id);
                        // 服务端权威装备预览：解析装备/命令配置为 6 槽位 ItemStack，
                        // 客户端无需解析命令即可渲染人物模型。
                        ClassLoadoutPreviewResolver.Preview preview =
                            ClassLoadoutPreviewResolver.resolve(server, kit, variant);
                        variants.add(new UnifiedDeployScreenPacket.VariantInfo(
                            variant.id, variant.name, variant.description, variant.maxPlayers, vCount,
                            new UnifiedDeployScreenPacket.LoadoutPreview(
                                preview.head, preview.chest, preview.legs, preview.feet,
                                preview.mainHand, preview.offHand)));
                    }
                }
                classList.add(new UnifiedDeployScreenPacket.ClassInfo(
                    kit.id, kit.name, kit.description, kit.role, kit.icon, kit.iconImage,
                    kit.maxPlayers, kit.strictCount, count, kit.troopValue, kit.healthBonus, kit.speedBonus,
                    kit.teamCount, kit.maxPerSquad, squadCount,
                    Math.max(0, kit.teammatesNeed), kit.row, kit.unlockPerN, kit.unlockMinSquad, kit.leaderOnly,
                    variants
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
        // 列出全部己方 HAB（含启用倒计时/无覆盖等），状态文案供 UI 展示；选择时再 isHabOperational
        for (org.espetro.bastion.BastionData bd : bm.getTeamBastions(team)) {
            net.minecraft.core.BlockPos armorStandPos = bm.getRecordedArmorStandPosition(bd);
            if (armorStandPos == null) {
                continue;
            }
            bastionList.add(new UnifiedDeployScreenPacket.BastionItem(
                bd.getBastionId(), bd.getName(),
                armorStandPos.getX() + ", " + armorStandPos.getY() + ", " + armorStandPos.getZ(),
                UnifiedDeployScreenPacket.BastionItem.TYPE_HAB,
                bm.getFobStatus(bd),
                0L, 0,
                bd.getHabAvailableAt(),
                org.espetro.logistics.LogisticsConfig.get().habActivationSeconds
            ));
        }
        bastionList.addAll(org.espetro.team.TeamPackManager.getInstance().getDeployItemsForPlayer(player));

        // AAS 防守方在部署阶段可使用前哨基地；RAAS 双方都不可用。
        if (org.espetro.team.OutpostManager.getInstance().canListFor(team)) {
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
        java.util.List<UnifiedDeployScreenPacket.SquadCategoryInfo> squadCategories =
            new java.util.ArrayList<>();
        org.espetro.mapconfig.ActiveMapConfig activeMap =
            org.espetro.mapconfig.BattlefieldContext.getOrNull();
        org.espetro.mapconfig.SquadTypesSnapshot types = activeMap != null
            ? activeMap.squadTypes : org.espetro.mapconfig.SquadTypesSnapshot.defaults();
        for (org.espetro.mapconfig.SquadTypesSnapshot.Category category : types.categories) {
            squadCategories.add(new UnifiedDeployScreenPacket.SquadCategoryInfo(
                category.id(), category.displayName()));
        }

        UnifiedDeployScreenPacket packet = new UnifiedDeployScreenPacket(
            factionId, factionName, factionDesc, factionIcon,
            classList, classCountMap,
            hasDeploy, deployPos, bastionList,
            isCmd, vehicleList,
            squadList, mySquadId,
            phaseTimeRemaining, team,
            commanderNames, GameConfig.getTeammateNameTagDistance(),
            bm.isWaitingForBastion(player.getUUID()),
            org.espetro.team.OutpostManager.getInstance()
                .getRedeployCooldownRemaining(player.getUUID()),
            squadCategories,
            ClassCountManager.getInstance()
                .getClassSwitchCooldownRemaining(player.getUUID()),
            openScreen,
            java.util.Objects.toString(
                ClassCountManager.getInstance().getPlayerClass(player.getUUID()), "")
        );

        NET.send(PacketDistributor.PLAYER.with(() -> player), packet);
        NET.send(PacketDistributor.PLAYER.with(() -> player),
            MatchStatsSyncPacket.from(org.espetro.stats.PlayerMatchStatsManager.getInstance()));
        NET.send(PacketDistributor.PLAYER.with(() -> player),
            GovernanceStatePacket.from(
                org.espetro.governance.CommanderGovernanceManager.getInstance(),
                player.getUUID()));
        sendEquipZones(player);
    }

    public static void sendDeployPointSync(ServerPlayer player) {
        if (player == null || player.connection == null) return;
        String team = Espetro.getPlayerTeam(player);
        if (team == null) return;
        NET.send(PacketDistributor.PLAYER.with(() -> player),
            new DeployPointSyncPacket(buildDeployPointItems(player, team)));
    }

    /** 对仍处于部署等待状态的玩家进行低成本兜底同步。 */
    public static void refreshWaitingDeployPoints() {
        MinecraftServer server = Espetro.getServer();
        if (server == null) return;
        GamePhase phase = GameStateManager.getInstance().getCurrentPhase();
        if (phase != GamePhase.DEPLOYING && phase != GamePhase.BATTLE) return;
        org.espetro.bastion.BastionManager bm = org.espetro.bastion.BastionManager.getInstance();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (bm.isWaitingForBastion(player.getUUID())) {
                sendDeployPointSync(player);
            }
        }
    }

    /** HAB/Radio 生命周期变化后立即刷新该阵营正在等待部署的成员。 */
    public static void refreshDeployPointsForTeam(String team) {
        MinecraftServer server = Espetro.getServer();
        if (server == null || team == null) return;
        GamePhase phase = GameStateManager.getInstance().getCurrentPhase();
        if (phase != GamePhase.DEPLOYING && phase != GamePhase.BATTLE) return;
        org.espetro.bastion.BastionManager bm = org.espetro.bastion.BastionManager.getInstance();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (team.equals(Espetro.getPlayerTeam(player))
                && bm.isWaitingForBastion(player.getUUID())) {
                sendDeployPointSync(player);
            }
        }
    }

    private static java.util.List<UnifiedDeployScreenPacket.BastionItem> buildDeployPointItems(
            ServerPlayer player, String team) {
        org.espetro.bastion.BastionManager bm = org.espetro.bastion.BastionManager.getInstance();
        java.util.List<UnifiedDeployScreenPacket.BastionItem> items = new java.util.ArrayList<>();
        for (org.espetro.bastion.BastionData bd : bm.getTeamBastions(team)) {
            net.minecraft.core.BlockPos armorStandPos = bm.getRecordedArmorStandPosition(bd);
            if (armorStandPos == null) continue;
            items.add(new UnifiedDeployScreenPacket.BastionItem(
                bd.getBastionId(), bd.getName(),
                armorStandPos.getX() + ", " + armorStandPos.getY() + ", " + armorStandPos.getZ(),
                UnifiedDeployScreenPacket.BastionItem.TYPE_HAB,
                bm.getFobStatus(bd), 0L, 0,
                bd.getHabAvailableAt(),
                org.espetro.logistics.LogisticsConfig.get().habActivationSeconds));
        }
        items.addAll(org.espetro.team.TeamPackManager.getInstance().getDeployItemsForPlayer(player));

        if (org.espetro.team.OutpostManager.getInstance().canListFor(team)) {
            var outposts = org.espetro.team.OutpostManager.getInstance().getOutposts();
            for (int i = 0; i < outposts.size(); i++) {
                var op = outposts.get(i);
                items.add(new UnifiedDeployScreenPacket.BastionItem(
                    new java.util.UUID(0L, i + 1L), "§d前哨: " + op.name, op.getPosString()));
            }
        }
        return items;
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
            GameConfig.getTeammateNameTagDistance(),
            java.util.Objects.toString(
                ClassCountManager.getInstance().getPlayerClass(player.getUUID()), "")
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
                    member.uuid, member.playerName, member.className,
                    member.leader, commanderUuids.contains(member.uuid),
                    member.fireteam.toNetwork(), member.fireteamLeader));
            }
            squadList.add(new UnifiedDeployScreenPacket.SquadInfo(
                squad.id, squad.displayId, squad.name, members.size(), squad.maxMembers, squad.locked,
                squad.leaderName, squad.categoryId, squad.categoryDisplayName, members
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
        return TeamDisplayNames.displayName(team);
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

    public static void sendCommanderSkillActivate(String skillId) {
        NET.sendToServer(new CommanderSkillPacket(skillId));
    }

    /**
     * 发送指挥官技能同步包给指定玩家（S→C）
     */
    public static void sendCommanderSkillSync(ServerPlayer player) {
        boolean isCommander = org.espetro.team.VoteManager.getInstance().isCommander(player.getUUID());
        org.espetro.team.CommanderSkillManager skills =
            org.espetro.team.CommanderSkillManager.getInstance();
        // 仅同步该玩家 usableBy 允许的技能；冷却仍按个人 UUID
        java.util.Map<String, Integer> cooldowns = skills.getCooldownData(player.getUUID());
        java.util.List<org.espetro.team.CommanderSkillManager.SkillView> views =
            skills.getSkillViewsFor(player);
        // isCommander 字段：有可用技能或是指挥官时客户端显示技能入口
        boolean showSkillsEntry = isCommander || !views.isEmpty();
        CommanderSkillSyncPacket packet = new CommanderSkillSyncPacket(showSkillsEntry, cooldowns, views);
        NET.send(PacketDistributor.PLAYER.with(() -> player), packet);
    }

    // ===== multi-dimension helpers =====

    public static void broadcastMapVoteState(org.espetro.team.MapVoteManager mgr) {
        MinecraftServer server = Espetro.getServer();
        if (server == null) return;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            sendMapVoteState(player, mgr);
        }
    }

    public static void sendMapVoteState(ServerPlayer player, org.espetro.team.MapVoteManager mgr) {
        NET.send(PacketDistributor.PLAYER.with(() -> player), MapVoteStatePacket.from(mgr, player));
    }

    public static void sendOpenMapVoteScreen(ServerPlayer player) {
        NET.send(PacketDistributor.PLAYER.with(() -> player), new OpenMapVoteScreenPacket());
    }

    public static void broadcastTeamSelectState(int attack, int defend, int remaining, long endGameTime, boolean active, String lockedTeam) {
        broadcastTeamSelectState(attack, defend, remaining, endGameTime, active, lockedTeam, null, null);
    }

    /** 带 faction 编制图片的版本。 */
    public static void broadcastTeamSelectState(int attack, int defend, int remaining,
                                                 long endGameTime, boolean active, String lockedTeam,
                                                 String attackFactionImage, String defendFactionImage) {
        MinecraftServer server = Espetro.getServer();
        if (server == null) return;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            String team = Espetro.getPlayerTeam(player);
            NET.send(PacketDistributor.PLAYER.with(() -> player),
                new TeamSelectStatePacket(attack, defend, remaining, endGameTime, active, team, lockedTeam,
                    attackFactionImage, defendFactionImage));
        }
    }

    public static void broadcastMatchStats(org.espetro.stats.PlayerMatchStatsManager mgr) {
        MinecraftServer server = Espetro.getServer();
        if (server == null) return;
        MatchStatsSyncPacket packet = MatchStatsSyncPacket.from(mgr);
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            NET.send(PacketDistributor.PLAYER.with(() -> player), packet);
        }
    }

    public static void broadcastGovernanceState(org.espetro.governance.CommanderGovernanceManager mgr) {
        MinecraftServer server = Espetro.getServer();
        if (server == null) return;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            NET.send(PacketDistributor.PLAYER.with(() -> player),
                GovernanceStatePacket.from(mgr, player.getUUID()));
        }
    }

    public static void sendOpenHubScreen(ServerPlayer player, int online, String status) {
        NET.send(PacketDistributor.PLAYER.with(() -> player), new OpenHubScreenPacket(online, status));
    }

    public static void broadcastRoundEnd(String winner, int seconds,
                                          String winnerShowName, String loserShowName,
                                          int attackTickets, int defendTickets,
                                          int resultLevel, boolean attackerTimedOut) {
        MinecraftServer server = Espetro.getServer();
        if (server == null) return;
        RoundEndPacket packet = new RoundEndPacket(winner, seconds,
            winnerShowName, loserShowName, attackTickets, defendTickets,
            resultLevel, attackerTimedOut);
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            NET.send(PacketDistributor.PLAYER.with(() -> player), packet);
        }
    }

    public static void sendMapVoteCast(String mapFolder) {
        NET.sendToServer(new MapVoteCastPacket(mapFolder));
    }

    public static void sendGovernanceAction(GovernanceActionPacket.Action action, java.util.UUID candidate) {
        NET.sendToServer(new GovernanceActionPacket(action, candidate));
    }

    public static void sendSquadCreateWithCategory(String name, String categoryId) {
        NET.sendToServer(new SquadCreateWithCategoryPacket(name, categoryId));
    }

    public static void sendMatchStatsAction(MatchStatsActionPacket.Action action, java.util.UUID target) {
        NET.sendToServer(new MatchStatsActionPacket(action, target));
    }

    // ==================== 组队匹配 ====================

    public static void sendPartyCreate(String password) {
        NET.sendToServer(PartyActionPacket.create(password));
    }

    public static void sendPartyJoin(java.util.UUID partyId, String password) {
        NET.sendToServer(PartyActionPacket.join(partyId, password));
    }

    public static void sendPartyLeave() {
        NET.sendToServer(PartyActionPacket.leave());
    }

    public static void sendPartyKick(java.util.UUID partyId, java.util.UUID targetId) {
        NET.sendToServer(PartyActionPacket.kick(partyId, targetId));
    }

    public static void sendPartyToggleLock(java.util.UUID partyId) {
        NET.sendToServer(PartyActionPacket.toggleLock(partyId));
    }

    public static void sendPartyDisband(java.util.UUID partyId) {
        NET.sendToServer(PartyActionPacket.disband(partyId));
    }

    /** 客户端请求最新的组队列表。 */
    public static void requestPartyList() {
        NET.sendToServer(PartyActionPacket.requestList());
    }

    /** 服务端：向所有在线玩家广播队伍列表。 */
    public static void broadcastPartyList(org.espetro.team.PartyManager pm) {
        var server = org.espetro.Espetro.getServer();
        if (server == null) return;
        for (var player : server.getPlayerList().getPlayers()) {
            sendPartyListTo(player);
        }
    }

    /** 服务端：向指定玩家发送队伍列表。 */
    public static void sendPartyListTo(net.minecraft.server.level.ServerPlayer player) {
        NET.send(net.minecraftforge.network.PacketDistributor.PLAYER.with(() -> player),
            PartyListPacket.from(org.espetro.team.PartyManager.getInstance(), player.getUUID()));
    }
}
