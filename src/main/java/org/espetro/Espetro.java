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
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.server.ServerLifecycleHooks;
import org.espetro.command.EspetroCommand;
import org.espetro.bastion.BastionManager;
import org.espetro.kubejs.EspetroKubeJSDefaultScripts;
import org.espetro.network.NetworkManager;
import org.espetro.runtime.ServerRuntimeMaintenance;
import org.espetro.script.CommanderScriptManager;
import org.espetro.config.GameConfig;
import org.espetro.stamina.StaminaManager;
import org.espetro.team.TeamManager;
import org.espetro.team.TeamPackManager;
import org.espetro.team.ClassEquipment;
import org.espetro.team.ClassCountManager;
import org.espetro.team.FactionDataLoader;
import org.espetro.team.FactionDataProvider;
import org.espetro.team.GamePhase;
import org.espetro.team.GameStateManager;
import org.espetro.team.SquadManager;
import org.espetro.team.SpawnPointConfig;
import org.espetro.team.VoteManager;
import org.espetro.team.ClassSelectManager;
import org.espetro.vehicle.VehicleCommand;
import org.espetro.vehicle.VehicleConfig;
import org.espetro.vehicle.VehicleManager;
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

    public Espetro() {
        EspetroKubeJSDefaultScripts.ensureDefaultScripts();

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

    // ========== 辅助方法 ==========

    /**
     * 热重载所有配置（数据包热更改入口）
     * <ul>
     *   <li>重新加载数据包中的 faction JSON → FactionDataLoader</li>
     *   <li>重新加载 game.json 全局参数</li>
     *   <li>重新加载 spawn_points.json 复活点</li>
     *   <li>重新加载 bastion.json 兵站参数</li>
     *   <li>重新加载 outposts.json 布防期前哨基地</li>
     *   <li>重新加载编制自定义载具配置 → VehicleConfig</li>
     * </ul>
     */
    public static void reloadAllConfigs() {
        MinecraftServer server = getServer();
        if (server == null) {
            LOGGER.warn("无法热重载：服务端实例为空");
            return;
        }

        ResourceManager resourceManager = server.getResourceManager();

        // 1. 热重载阵营/职业数据
        FactionDataLoader loader = FactionDataProvider.getOrCreateLoader();
        loader.reload(resourceManager);

        // 2. 热重载全局游戏配置
        GameConfig.reloadConfig(server);

        // 3. 热重载复活点配置。指挥官技能由 KubeJS startup/server scripts 注册与实现。
        SpawnPointConfig.loadConfig(server);

        // 4. 热重载兵站配置
        BastionManager.getInstance().reloadConfig();
        TeamPackManager.getInstance().reloadConfig();

        // 5. 热重载载具配置（依赖 faction 数据，放在最后）
        VehicleConfig.loadConfig(server);

        // 6. 热重载前哨基地配置
        org.espetro.team.OutpostManager.getInstance().loadConfig(server);

        NetworkManager.syncSquadsToTeam("ATTACK");
        NetworkManager.syncSquadsToTeam("DEFEND");

        LOGGER.info("Espetro 所有配置已热重载完成");
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
                EspetroKubeJSDefaultScripts.ensureDefaultScripts();
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
                // 初始化前哨基地管理器
                org.espetro.team.OutpostManager.init();
                // 初始化指挥官技能管理器
                org.espetro.team.CommanderSkillManager.init();
                // 初始化指挥官技能内部调度器
                CommanderScriptManager.init();
            });
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
            event.getDispatcher().register(org.espetro.command.OutpostCommand.register());
        }

        @SubscribeEvent
        public static void onPlayerJoin(PlayerEvent.PlayerLoggedInEvent event) {
            MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
            if (server != null) {
                TeamManager.initTeams(server);
            }

            if (event.getEntity() instanceof net.minecraft.server.level.ServerPlayer serverPlayer) {
                StaminaManager.resetPlayer(serverPlayer);
                clearAndSavePlayerInventory(serverPlayer);

                GamePhase phase = GameStateManager.getInstance().getCurrentPhase();

                if (phase == GamePhase.WAITING_FOR_PLAYERS) {
                    // 对局未开始，进入等待队列
                    GameStateManager.getInstance().onPlayerJoin(serverPlayer);
                    Espetro.LOGGER.info("玩家 {} 进入等待状态", serverPlayer.getName().getString());
                } else {
                    // 战局中加入：无论是投票、编制选择、部署还是战斗阶段，都进入增援流程
                    GameStateManager.getInstance().onMidGameJoin(serverPlayer);
                    Espetro.LOGGER.info("玩家 {} 在{}阶段增援加入", serverPlayer.getName().getString(), phase.getDisplayName());
                }
            }
        }

        @SubscribeEvent
        public static void onPlayerLeave(PlayerEvent.PlayerLoggedOutEvent event) {
            // 玩家离开时清空装备并立即写入 playerdata，避免重进时恢复退出前的职业装备。
            if (event.getEntity() instanceof ServerPlayer serverPlayer) {
                StaminaManager.removePlayer(serverPlayer.getUUID());
                clearAndSavePlayerInventory(serverPlayer);
            } else {
                ClassEquipment.clearEquipment(event.getEntity());
            }

            // 玩家离开时减少职业人数
            ClassCountManager countManager = ClassCountManager.getInstance();
            String classCountTeam = countManager.getEffectivePlayerTeam(event.getEntity().getUUID());
            String classCountFaction = countManager.getPlayerFaction(event.getEntity().getUUID());
            String squadTeam = SquadManager.getInstance().removePlayer(event.getEntity().getUUID());
            countManager.removePlayer(event.getEntity());
            NetworkManager.broadcastClassCounts(classCountTeam, classCountFaction);
            if (squadTeam != null) {
                TeamPackManager.getInstance().reconcileTeam(squadTeam);
                NetworkManager.syncSquadsToTeam(squadTeam);
            }

            // 从游戏状态管理器移除
            GameStateManager.getInstance().onPlayerLeave(event.getEntity().getUUID());
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
        public static void onServerStarting(ServerStartingEvent event) {
            serverInstance = event.getServer();
            disableNaturalRegeneration(event.getServer());
            ServerRuntimeMaintenance.getInstance().reset();
            CommanderScriptManager.getInstance().reset();
            // 初始化所有配置（首次加载）
            reloadAllConfigs();
            // 初始化职业人数记分板
            ClassCountManager.getInstance().initializeAllClassScores();
            // 重置游戏状态（含兵站清空）
            BastionManager.getInstance().reset();
            TeamPackManager.getInstance().reset();
            GameStateManager.getInstance().resetGame();
        }

        @SubscribeEvent
        public static void onServerStopping(ServerStoppingEvent event) {
            clearAndSaveOnlinePlayerInventories(event.getServer());

            // 停服保存前清理已加载区块中的兵站实体，但不强制加载未加载区块。
            BastionManager.getInstance().reset();
            TeamPackManager.getInstance().reset();
            int removedVehicles = VehicleManager.getInstance().removeAllDeployedVehicles(event.getServer());
            if (removedVehicles > 0) {
                LOGGER.info("停服时已删除 {} 辆已部署载具", removedVehicles);
            }
        }

        @SubscribeEvent
        public static void onServerStopped(ServerStoppedEvent event) {
            // 服务器已停止后只清理内存状态，避免在世界卸载阶段访问区块或实体。
            BastionManager.getInstance().clearRuntimeState();
            TeamPackManager.getInstance().clearRuntimeState();
            VehicleManager.getInstance().clearRuntimeState();
            ServerRuntimeMaintenance.getInstance().reset();
            CommanderScriptManager.getInstance().reset();
            StaminaManager.clear();
            serverInstance = null;
        }

        @SubscribeEvent
        public static void onPlayerRespawn(PlayerEvent.PlayerRespawnEvent event) {
            if (event.getEntity() instanceof ServerPlayer serverPlayer) {
                StaminaManager.resetPlayer(serverPlayer);
            }
        }

        @SubscribeEvent
        public static void onPlayerTick(TickEvent.PlayerTickEvent event) {
            if (event.phase == TickEvent.Phase.END && event.player instanceof ServerPlayer serverPlayer) {
                maintainPlayerFood(serverPlayer);
                StaminaManager.onPlayerTick(serverPlayer);
            }
        }

        @SubscribeEvent
        public static void onPlayerJump(LivingEvent.LivingJumpEvent event) {
            if (event.getEntity() instanceof ServerPlayer serverPlayer) {
                StaminaManager.onPlayerJump(serverPlayer);
            }
        }

        private static void clearAndSavePlayerInventory(ServerPlayer player) {
            ClassEquipment.clearEquipment(player);
            MinecraftServer server = player.getServer();
            if (server != null) {
                server.getPlayerList().saveAll();
            }
        }

        private static void clearAndSaveOnlinePlayerInventories(MinecraftServer server) {
            if (server == null) return;

            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                ClassEquipment.clearEquipment(player);
            }
            server.getPlayerList().saveAll();
        }

        private static void maintainPlayerFood(ServerPlayer player) {
            disableNaturalRegeneration(player.getServer());

            FoodData foodData = player.getFoodData();
            if (foodData.getFoodLevel() != FIXED_FOOD_LEVEL) {
                foodData.setFoodLevel(FIXED_FOOD_LEVEL);
            }
            if (foodData.getSaturationLevel() != FIXED_SATURATION_LEVEL) {
                foodData.setSaturation(FIXED_SATURATION_LEVEL);
            }
            foodData.setExhaustion(0.0F);
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
                CommanderScriptManager.getInstance().onServerTick();
                org.espetro.team.CommanderSkillManager.getInstance().onServerTick();
            }
        }


    }
}
