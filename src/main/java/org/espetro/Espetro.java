package org.espetro;

import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.food.FoodData;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameRules;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.AddReloadListenerEvent;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.item.ItemTossEvent;
import net.minecraftforge.event.entity.living.LivingDropsEvent;
import net.minecraftforge.event.entity.living.LivingEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerAboutToStartEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.server.ServerLifecycleHooks;
import org.espetro.command.EspetroCommand;
import org.espetro.bastion.BastionManager;
import org.espetro.dimension.BattlefieldWorldManager;
import org.espetro.kubejs.EspetroKubeJSDefaultScripts;
import org.espetro.network.NetworkManager;
import org.espetro.runtime.ServerRuntimeMaintenance;
import org.espetro.config.GameConfig;
import org.espetro.stamina.StaminaManager;
import org.espetro.team.TeamManager;
import org.espetro.team.TeamPackManager;
import org.espetro.logistics.LogisticsConfig;
import org.espetro.logistics.SupplyManager;
import org.espetro.logistics.resupply.ResupplySessionManager;
import org.espetro.team.ClassEquipment;
import org.espetro.team.ClassCountManager;
import org.espetro.team.FactionDataLoader;
import org.espetro.team.FactionDataProvider;
import org.espetro.team.GamePhase;
import org.espetro.team.GameStateManager;
import org.espetro.team.SquadManager;
import org.espetro.team.SpawnPointConfig;

import org.espetro.team.ClassSelectManager;
import org.espetro.tutorial.TutorialManager;
import org.espetro.vehicle.VehicleCommand;
import org.espetro.vehicle.VehCommand;
import org.espetro.vehicle.VehicleManager;
import org.espetro.mapconfig.ExternalConfigBootstrap;
import org.espetro.stats.PlayerMatchStatsManager;
import org.espetro.governance.CommanderGovernanceManager;
import org.espetro.team.MapVoteManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * Espetro模组主类
 * 
 * 战术小队风格的侵攻模式模组
 */
@Mod(Espetro.MOD_ID)
public class Espetro {
    public static final String MOD_ID = "espetro";
    public static final Logger LOGGER = LoggerFactory.getLogger(Espetro.class);

    private static MinecraftServer serverInstance;

    // 快捷键 (客户端专用，使用 Object 类型避免服务端加载问题)
    public static Object KEY_TEAM;   // K - 队伍选择
    public static Object KEY_CLASS;  // J - 职业选择
    public static Object KEY_SKILL;  // Y - 指挥官技能
    public static Object KEY_RADIAL; // 长按战术交互轮盘

    public Espetro() {
        ensureKubeJSDefaultScriptsIfLoaded();

        // 客户端初始化：双重 lambda 确保服务端不加载客户端类
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> {
            try {
                Class.forName("org.espetro.EspetroClient")
                    .getMethod("init")
                    .invoke(null);
            } catch (Exception e) {
                LOGGER.error("Failed to initialize EspetroClient", e);
            }
        });
    }

    public static MinecraftServer getServer() {
        return serverInstance;
    }

    private static void ensureKubeJSDefaultScriptsIfLoaded() {
        if (ModList.get().isLoaded("kubejs")) {
            EspetroKubeJSDefaultScripts.ensureDefaultScripts();
        }
    }

    // ========== 辅助方法 ==========

    /**
     * 热重载入口：外部 EsDimensions / EsWorld/EsConfig / EsFactions 仅启动冻结，
     * 不从 datapack 重读游戏参数。请重启以应用文件修改。
     */
    public static void reloadAllConfigs() {
        LOGGER.warn("Espetro 外部地图、维度、编制及每地图 EsConfig 仅在启动时加载；"
            + "本次 reload 未重新读取这些文件，请重启游戏或服务端。");
        NetworkManager.syncSquadsToTeam("ATTACK");
        NetworkManager.syncSquadsToTeam("DEFEND");
    }

    /**
     * 启动一次性加载：EsDimensions / EsWorld / EsFactions。
     * 地图级 game/spawn/bastion 等仅在 BattlefieldContext 激活时由
     * GameConfigBridge 从该图 EsConfig 快照应用；不从 data/espetro datapack 读取。
     */
    private static void loadStartupConfigs(MinecraftServer server) {
        ExternalConfigBootstrap.bootstrapIfNeeded();
        FactionDataLoader loader = FactionDataProvider.getOrCreateLoader();
        loader.loadExternalFrozen(ExternalConfigBootstrap.getFactionFiles());

        // 代码默认值保留至选图激活；不调用 GameConfig/SpawnPoint/Bastion 等 datapack load。
        LOGGER.info("Espetro 启动配置已冻结：{} 个可用地图，{} 个 EsFactions 编制（地图 EsConfig 将在战场激活时应用）",
            ExternalConfigBootstrap.getUsableMaps().size(), loader.getFactionArray().length);
    }

    /**
     * 向所有玩家广播消息
     */
    public static void broadcastToAll(String message) {
        MinecraftServer server = getServer();
        if (server != null) {
            server.getPlayerList().broadcastSystemMessage(
                net.minecraft.network.chat.Component.literal(message), false);
        }
    }

    /**
     * 向指定队伍广播消息
     */
    public static void broadcastToTeam(String team, String message) {
        MinecraftServer server = getServer();
        if (server == null) return;

        ClassCountManager countManager = ClassCountManager.getInstance();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            String playerTeam = getPlayerTeam(player);
            if (team.equals(playerTeam)) {
                player.sendSystemMessage(net.minecraft.network.chat.Component.literal(message));
            }
        }
    }

    /**
     * 向指定玩家发送消息
     */
    public static void sendToPlayer(ServerPlayer player, String message) {
        if (player != null) {
            player.sendSystemMessage(net.minecraft.network.chat.Component.literal(message));
        }
    }

    /**
     * 获取玩家的队伍
     * 优先使用玩家最初选择的队伍（ATTACK/DEFEND）
     */
    public static String getPlayerTeam(ServerPlayer player) {
        ClassCountManager countManager = ClassCountManager.getInstance();
        // 优先使用存储的原始队伍
        String storedTeam = countManager.getPlayerTeam(player.getUUID());
        if (storedTeam != null) {
            return storedTeam;
        }
        // 回退：通过 factionId 推断
        String factionId = countManager.getPlayerFaction(player.getUUID());
        if (factionId != null) {
            return GameStateManager.getTeamFromFactionStatic(factionId);
        }
        return null;
    }

    /**
     * 向指定队伍广播编制选择消息
     */
    public static void broadcastClassSelection(String team, String classId, String message) {
        MinecraftServer server = getServer();
        if (server == null) return;

        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            String playerTeam = getPlayerTeam(player);
            if (team.equals(playerTeam)) {
                player.sendSystemMessage(Component.literal(message));
            }
        }
    }

    @Mod.EventBusSubscriber(modid = MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD)
    public static class ModEventHandlers {
        @SubscribeEvent
        public static void commonSetup(FMLCommonSetupEvent event) {
            event.enqueueWork(() -> {
                ensureKubeJSDefaultScriptsIfLoaded();
                NetworkManager.registerNetwork();
                // 初始化职业人数管理器
                new ClassCountManager();
                // 初始化游戏状态管理器
                GameStateManager.init();
                // 初始化兵力统计管理器
                org.espetro.team.TroopCountManager.init();
                // 初始化班组小队管理器
                SquadManager.init();
                // 初始化队包管理器
                TeamPackManager.init();
                // 初始化兵站管理器
                BastionManager.getInstance();
                org.espetro.bastion.FortificationConfig.loadDefaults();
                // 初始化前哨基地管理器
                org.espetro.team.OutpostManager.init();
                // 初始化指挥官技能管理器
                org.espetro.team.CommanderSkillManager.init();
                MapVoteManager.init();
                PlayerMatchStatsManager.init();
                CommanderGovernanceManager.init();
                // 初始化组队匹配管理器
                org.espetro.team.PartyManager.getInstance();
                // 启动时预加载易在热路径首次解析的类，避免运行中替换 jar 后懒加载 CNFE 拖垮服务端
                TutorialManager.getInstance();
                preloadCriticalClasses();
            });
        }

        /**
         * 强制解析关键类。SecureJar 在游戏运行中被覆盖后，尚未加载的类会 ClassNotFound；
         * 启动时拉进内存可显著降低热替换 jar 引发的退服/右键崩溃。
         */
        private static void preloadCriticalClasses() {
            try {
                org.espetro.logistics.SupplyType.values();
                org.espetro.logistics.SupplyManager.getInstance();
                Class.forName("org.espetro.logistics.DeploySupplyStationPlacer");
                Class.forName("org.espetro.logistics.LogisticsConfig");
                Class.forName("org.espetro.logistics.SupplySourceBlock");
                Class.forName("org.espetro.logistics.SupplySourceBlockEntity");
                Class.forName("org.espetro.tutorial.TutorialStep");
                LOGGER.info("Espetro 关键类预加载完成");
            } catch (Throwable t) {
                LOGGER.error("Espetro 关键类预加载失败（请确认 jar 完整且勿在游戏运行时覆盖）", t);
            }
        }

    }

    @Mod.EventBusSubscriber(modid = MOD_ID)
    public static class ServerCommandHandler {
        private static final int FIXED_FOOD_LEVEL = 20;
        private static final float FIXED_SATURATION_LEVEL = 20.0F;

        @SubscribeEvent
        public static void onRegisterCommands(RegisterCommandsEvent event) {
            EspetroCommand.register(event.getDispatcher());
            org.espetro.bastion.BastionCommand.register(event.getDispatcher());
            VehicleCommand.register(event.getDispatcher());
            VehCommand.register(event.getDispatcher());
            event.getDispatcher().register(org.espetro.command.OutpostCommand.register());
        }

        @SubscribeEvent
        public static void onPlayerJoin(PlayerEvent.PlayerLoggedInEvent event) {
            MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
            if (server != null) {
                TeamManager.initTeams(server);
            }

            if (event.getEntity() instanceof net.minecraft.server.level.ServerPlayer serverPlayer) {
                // 先强制主城再清背包/阶段逻辑，避免登录瞬间仍停在战场维度
                GameStateManager.getInstance().forcePlayerToHub(serverPlayer);
                StaminaManager.resetPlayer(serverPlayer);
                clearPlayerInventory(serverPlayer);

                GamePhase phase = GameStateManager.getInstance().getCurrentPhase();
                GameStateManager.getInstance().onPlayerJoin(serverPlayer);
                NetworkManager.NET.send(
                    net.minecraftforge.network.PacketDistributor.PLAYER.with(() -> serverPlayer),
                    org.espetro.network.FortificationCatalogPacket.forPlayer(serverPlayer));
                // 指挥官断线重连恢复（仅 BATTLE / DEPLOYING 阶段尝试）
                if (phase == GamePhase.BATTLE || phase == GamePhase.DEPLOYING) {
                    CommanderGovernanceManager.getInstance().tryRestoreCommanderOnRejoin(serverPlayer);
                }
                Espetro.LOGGER.info("玩家 {} 在{}阶段加入", serverPlayer.getName().getString(),
                    phase.getDisplayName());
            }
        }

        @SubscribeEvent
        public static void onPlayerLeave(PlayerEvent.PlayerLoggedOutEvent event) {
            // 玩家离开时清空装备并立即写入 playerdata，避免重进时恢复退出前的职业装备。
            if (event.getEntity() instanceof ServerPlayer serverPlayer) {
                // 退服前强制写回主城坐标与重生点（主城维度不重置）
                GameStateManager.getInstance().forcePlayerToHub(serverPlayer);
                StaminaManager.removePlayer(serverPlayer.getUUID());
                org.espetro.network.VehicleSupplyActionPacket.clearPlayerRateLimit(
                    serverPlayer.getUUID());
                org.espetro.bastion.FortificationManager.getInstance()
                    .clearPlayer(serverPlayer.getUUID());
                ResupplySessionManager.clearPlayer(serverPlayer.getUUID());
                try {
                    TutorialManager.getInstance().onPlayerLeave(serverPlayer.getUUID());
                } catch (Throwable t) {
                    // 教程会话清理失败不得拖垮整个退服流程（含 jar 热替换后懒加载失败）
                    Espetro.LOGGER.error("清理玩家教程会话失败: {}", serverPlayer.getUUID(), t);
                }
                clearPlayerInventory(serverPlayer);
            } else {
                ClassEquipment.clearEquipment(event.getEntity());
            }

            // 玩家离开时减少职业人数
            ClassCountManager countManager = ClassCountManager.getInstance();
            String classCountTeam = countManager.getEffectivePlayerTeam(event.getEntity().getUUID());
            String classCountFaction = countManager.getPlayerFaction(event.getEntity().getUUID());
            boolean wasLeader = SquadManager.getInstance().isSquadLeader(event.getEntity().getUUID());
            if (GameStateManager.getInstance().getCurrentPhase() == GamePhase.BATTLE) {
                // Any battle leaver: commander vacancy, challenger cancel, volunteer/vote scrub.
                CommanderGovernanceManager.getInstance().onPlayerLeft(
                    classCountTeam, event.getEntity().getUUID());
            }
            if (wasLeader) {
                CommanderGovernanceManager.getInstance().onSquadLeaderLost(event.getEntity().getUUID());
            }
            String squadTeam = SquadManager.getInstance().removePlayer(event.getEntity().getUUID());
            countManager.removePlayer(event.getEntity());
            org.espetro.ping.VehicleSeatPingCache.clear(event.getEntity().getUUID());
            org.espetro.vehicle.VehicleSeatAccessPolicy.clear(event.getEntity().getUUID());
            NetworkManager.broadcastClassCounts(classCountTeam, classCountFaction);
            if (squadTeam != null) {
                TeamPackManager.getInstance().reconcileTeam(squadTeam);
                NetworkManager.syncSquadsToTeam(squadTeam);
            }

            // 从游戏状态管理器移除
            if (event.getEntity() instanceof ServerPlayer leavePlayer) {
                GameStateManager.getInstance().onPlayerLeave(leavePlayer);
            } else {
                GameStateManager.getInstance().onPlayerLeave(event.getEntity().getUUID());
            }
        }

        @SubscribeEvent
        public static void onItemToss(ItemTossEvent event) {
            if (event.getPlayer() instanceof ServerPlayer player && !player.hasPermissions(2)) {
                event.setCanceled(true);
                if (ClassEquipment.isEquipmentMutation(player)) {
                    return;
                }
                returnTossedItem(player, event.getEntity().getItem());
                player.sendSystemMessage(Component.literal("§c非管理员无法丢弃物品！"));
            }
        }

        @SubscribeEvent
        public static void onLivingDrops(LivingDropsEvent event) {
            if (event.getEntity() instanceof ServerPlayer player && !player.hasPermissions(2)) {
                event.getDrops().clear();
            }
        }

        /** The overworld is the persistent safe hub in every game phase. */
        @SubscribeEvent
        public static void onHubPlayerHurt(LivingHurtEvent event) {
            if (event.getEntity() instanceof ServerPlayer player
                && net.minecraft.world.level.Level.OVERWORLD.equals(player.serverLevel().dimension())) {
                event.setCanceled(true);
            }
        }

        @SubscribeEvent
        public static void onEntityJoinLevel(EntityJoinLevelEvent event) {
            if (event.getLevel().isClientSide() || event.loadedFromDisk()) return;
            if (!(event.getEntity() instanceof ItemEntity itemEntity)) return;

            Entity owner = itemEntity.getOwner();
            if (owner instanceof ServerPlayer player && !player.hasPermissions(2)) {
                event.setCanceled(true);
                if (ClassEquipment.isEquipmentMutation(player)) {
                    return;
                }
                returnTossedItem(player, itemEntity.getItem());
                player.sendSystemMessage(Component.literal("§c非管理员无法丢弃物品！"));
            }
        }

        private static void returnTossedItem(ServerPlayer player, ItemStack stack) {
            if (stack.isEmpty()) return;

            ItemStack copy = stack.copy();
            player.getInventory().add(copy);
            if (!copy.isEmpty()) {
                ItemStack carried = player.containerMenu.getCarried();
                if (carried.isEmpty()) {
                    player.containerMenu.setCarried(copy.copyAndClear());
                } else if (ItemStack.isSameItemSameTags(carried, copy)) {
                    int room = carried.getMaxStackSize() - carried.getCount();
                    int moved = Math.min(room, copy.getCount());
                    if (moved > 0) {
                        carried.grow(moved);
                        copy.shrink(moved);
                    }
                }
            }
            player.getInventory().setChanged();
            player.inventoryMenu.broadcastChanges();
            player.containerMenu.broadcastChanges();
        }

        @SubscribeEvent
        public static void onServerAboutToStart(ServerAboutToStartEvent event) {
            var prepared = BattlefieldWorldManager.getInstance()
                .prepareAtStartup(event.getServer());
            LOGGER.info("战场地图启动准备: status={} prepared={} warnings={} error={}",
                prepared.status(), prepared.preparedCount(), prepared.warnings().size(),
                prepared.error());
        }

        @SubscribeEvent
        public static void onServerStarting(ServerStartingEvent event) {
            serverInstance = event.getServer();
            disableNaturalRegeneration(event.getServer());
            ServerRuntimeMaintenance.getInstance().reset();
            org.espetro.bastion.FortificationConfig.loadServerConfig();
            org.espetro.bastion.FobSupplyTracker.clearAll();
            // 初始化并冻结所有外部配置（仅此一次）
            loadStartupConfigs(event.getServer());
            // 初始化职业人数记分板
            ClassCountManager.getInstance().initializeAllClassScores();
            // 重置游戏状态（含兵站清空）
            BastionManager.getInstance().reset();
            TeamPackManager.getInstance().reset();
            SupplyManager.getInstance().reset();
            org.espetro.bastion.FobSupplyTracker.clearAll();
            GameStateManager.getInstance().resetGame();
        }

        @SubscribeEvent
        public static void onServerStopping(ServerStoppingEvent event) {
            ResupplySessionManager.clearAll();
            clearAndSaveOnlinePlayerInventories(event.getServer());

            // 世界仍可访问时清理所有临时战局实体与方块。
            int removedBarrierBlocks = GameStateManager.getInstance()
                .cleanupTemporaryBarriers(event.getServer());
            BastionManager.getInstance().reset();
            TeamPackManager.getInstance().reset();
            SupplyManager.getInstance().reset();
            org.espetro.bastion.FobSupplyTracker.clearAll();
            int removedVehicles = VehicleManager.getInstance().removeAllDeployedVehicles(event.getServer());
            LOGGER.info("停服战局清理完成: 已删除{}辆部署载具, 已恢复/删除{}个屏障方块",
                removedVehicles, removedBarrierBlocks);
        }

        @SubscribeEvent
        public static void onServerStopped(ServerStoppedEvent event) {
            // 服务器已停止后只清理内存状态，避免在世界卸载阶段访问区块或实体。
            ResupplySessionManager.clearAll();
            BastionManager.getInstance().clearRuntimeState();
            TeamPackManager.getInstance().clearRuntimeState();
            SupplyManager.getInstance().reset();
            VehicleManager.getInstance().clearRuntimeState();
            ServerRuntimeMaintenance.getInstance().reset();
            BattlefieldWorldManager.getInstance().resetAfterServerStop();
            StaminaManager.clear();
            org.espetro.network.VehicleSupplyActionPacket.clearRateLimits();
            serverInstance = null;
        }

        @SubscribeEvent
        public static void onPlayerRespawn(PlayerEvent.PlayerRespawnEvent event) {
            if (event.getEntity() instanceof ServerPlayer serverPlayer) {
                StaminaManager.resetPlayer(serverPlayer);
                // 主城重生：所有人先冒险；非管理员再强制锁定
                GameStateManager.getInstance().applyHubAdventureOnEnter(serverPlayer);
                GameStateManager.getInstance().enforceHubAdventure(serverPlayer);
                GameStateManager.getInstance().applyBattlefieldMiningRestriction(serverPlayer);
            }
        }

        @SubscribeEvent
        public static void onPlayerChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
            if (event.getEntity() instanceof ServerPlayer serverPlayer) {
                ResupplySessionManager.clearPlayer(serverPlayer.getUUID());
                if (!BattlefieldWorldManager.getInstance().isStartupReady()
                    && "espetro".equals(event.getTo().location().getNamespace())) {
                    serverPlayer.sendSystemMessage(Component.literal(
                        "§c战场启动重置失败，本次会话地图已禁用。"));
                    GameStateManager.getInstance().forcePlayerToHub(serverPlayer);
                    return;
                }
                if (net.minecraft.world.level.Level.OVERWORLD.equals(event.getTo())) {
                    // 离开战场时清掉旧版疲劳残留
                    serverPlayer.removeEffect(net.minecraft.world.effect.MobEffects.DIG_SLOWDOWN);
                    // 进入主城：所有人（含管理员）设为冒险
                    GameStateManager.getInstance().applyHubAdventureOnEnter(serverPlayer);
                } else if (org.espetro.mapconfig.BattlefieldContext
                    .isActiveBattlefield(serverPlayer.serverLevel())) {
                    GameStateManager.getInstance().applyBattlefieldMiningRestriction(serverPlayer);
                } else {
                    serverPlayer.removeEffect(net.minecraft.world.effect.MobEffects.DIG_SLOWDOWN);
                }
            }
        }

        @SubscribeEvent
        public static void onPlayerChangeGameMode(PlayerEvent.PlayerChangeGameModeEvent event) {
            // 非管理员在主城禁止切出冒险模式
            if (event.getEntity() instanceof ServerPlayer serverPlayer
                && GameStateManager.getInstance().shouldForceHubAdventure(serverPlayer)
                && event.getNewGameMode() != net.minecraft.world.level.GameType.ADVENTURE) {
                event.setNewGameMode(net.minecraft.world.level.GameType.ADVENTURE);
            }
        }

        @SubscribeEvent
        public static void onPlayerTick(TickEvent.PlayerTickEvent event) {
            if (event.phase == TickEvent.Phase.END && event.player instanceof ServerPlayer serverPlayer) {
                // 食物每秒最多修正一次；自然恢复 gamerule 仅在启动时设置
                if (serverPlayer.tickCount % 20 == 0) {
                    maintainPlayerFood(serverPlayer);
                    // 低频兜底：非管理员主城被其它途径改模式时拉回冒险
                    GameStateManager.getInstance().enforceHubAdventure(serverPlayer);
                }
                StaminaManager.onPlayerTick(serverPlayer);
            }
        }

        @SubscribeEvent
        public static void onPlayerJump(LivingEvent.LivingJumpEvent event) {
            if (event.getEntity() instanceof ServerPlayer serverPlayer) {
                StaminaManager.onPlayerJump(serverPlayer);
            }
        }

        /**
         * Clear the affected player's transient class equipment only.
         *
         * PlayerList#remove saves this player immediately after Forge fires
         * PlayerLoggedOutEvent. Calling saveAll() here used to synchronously
         * write every online player's data for each join/leave, which becomes
         * a severe disk-I/O spike on a busy server.
         */
        private static void clearPlayerInventory(ServerPlayer player) {
            ClassEquipment.clearEquipment(player);
        }

        private static void clearAndSaveOnlinePlayerInventories(MinecraftServer server) {
            if (server == null) return;

            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                ClassEquipment.clearEquipment(player);
            }
            server.getPlayerList().saveAll();
        }

        private static void maintainPlayerFood(ServerPlayer player) {
            FoodData foodData = player.getFoodData();
            if (foodData.getFoodLevel() != FIXED_FOOD_LEVEL) {
                foodData.setFoodLevel(FIXED_FOOD_LEVEL);
            }
            if (foodData.getSaturationLevel() != FIXED_SATURATION_LEVEL) {
                foodData.setSaturation(FIXED_SATURATION_LEVEL);
            }
            if (foodData.getExhaustionLevel() != 0.0F) {
                foodData.setExhaustion(0.0F);
            }
        }

        private static void disableNaturalRegeneration(MinecraftServer server) {
            if (server == null) return;

            GameRules.BooleanValue naturalRegeneration = server.overworld()
                    .getGameRules()
                    .getRule(GameRules.RULE_NATURAL_REGENERATION);
            if (naturalRegeneration.get()) {
                naturalRegeneration.set(false, server);
            }
        }

        /**
         * 订阅 Minecraft 原版 /reload 命令，实现数据包热更改
         */
        @SubscribeEvent
        public static void onAddReloadListener(AddReloadListenerEvent event) {
            event.addListener(new PreparableReloadListener() {
                @Override
                public CompletableFuture<Void> reload(
                        PreparationBarrier barrier,
                        ResourceManager resourceManager,
                        ProfilerFiller preparationsProfiler,
                        ProfilerFiller reloadProfiler,
                        Executor backgroundExecutor,
                        Executor gameExecutor) {
                    return CompletableFuture.supplyAsync(() -> {
                        // 在后台线程准备阶段不做特别处理
                        return null;
                    }, backgroundExecutor).thenCompose(barrier::wait).thenRunAsync(() -> {
                        // 在主线程执行重载
                        reloadAllConfigs();
                    }, gameExecutor);
                }

                @Override
                public String getName() {
                    return "Espetro Data Reloader";
                }
            });
            LOGGER.info("Espetro 已注册数据包热重载监听器");
        }

        // 服务器Tick事件用于更新各种状态
        @SubscribeEvent
        public static void onServerTick(TickEvent.ServerTickEvent event) {
            if (event.phase == TickEvent.Phase.END) {
                GameStateManager.getInstance().onServerTick();
                ServerRuntimeMaintenance.getInstance().onServerTick();
                BastionManager.getInstance().tickDerivedTacticalState(event.getServer());
                ResupplySessionManager.tick(event.getServer());
            }
        }


    }
}
