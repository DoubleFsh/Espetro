package org.espetro.bastion;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.TagParser;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.event.level.ExplosionEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.espetro.Espetro;
import org.espetro.team.ClassCountManager;
import org.espetro.team.FactionDataLoader;
import org.espetro.team.FactionDataProvider;
import org.espetro.team.GamePhase;
import org.espetro.team.GameStateManager;
import org.espetro.team.SquadManager;
import org.espetro.team.SpawnPointConfig;
import org.espetro.team.TeamPackManager;
import org.espetro.logistics.LogisticsConfig;
import org.espetro.logistics.SupplyManager;

import javax.annotation.Nullable;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import java.util.UUID;

/**
 * 兵站相关事件处理器
 */
@Mod.EventBusSubscriber(modid = Espetro.MOD_ID)
public class BastionEventHandler {

    /**
     * 玩家死亡时触发
     */
    @SubscribeEvent
    public static void onPlayerDeath(LivingDeathEvent event) {
        if (event.getEntity() instanceof ArmorStand armorStand && isBastionCore(armorStand)) {
            if (!armorStand.level().isClientSide) {
                BastionData bastion = BastionManager.getInstance().findBastionByArmorStand(armorStand.getUUID());
                if (bastion != null && bastion.isActive()) {
                    BastionManager.getInstance().onCoreArmorStandDestroyed(
                        bastion, event.getSource().getEntity());
                }
            }
            return;
        }

        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }

        // 对战阶段正常复活；布防阶段阵亡/重新部署也允许重选部署点。
        GamePhase phase = GameStateManager.getInstance().getCurrentPhase();
        if (phase != GamePhase.BATTLE && phase != GamePhase.DEPLOYING) {
            return;
        }

        if (phase == GamePhase.DEPLOYING) {
            org.espetro.team.OutpostManager.getInstance()
                .prepareDeployTargets(player.server.overworld());
        }

        // 检查玩家是否选择了职业
        String factionId = ClassCountManager.getInstance().getPlayerFaction(player.getUUID());
        if (factionId == null) {
            return;
        }

        // 保存玩家的原部署点位置（使用队伍配置的部署点）
        // 使用 Espetro.getPlayerTeam() 而非 getTeamFromFactionStatic()，
        // 防止战局中加入者因 factionId 解析出错误的队伍，导致部署点坐标错乱
        String team = Espetro.getPlayerTeam(player);
        if (team != null) {
            SpawnPointConfig.SpawnPoint spawnPoint = SpawnPointConfig.getSpawnPoint(team);
            BastionManager bastionManager = BastionManager.getInstance();
            bastionManager.savePlayerDeployPoint(player,
                new BlockPos((int) spawnPoint.x, (int) spawnPoint.y, (int) spawnPoint.z),
                player.server.overworld());
        }

        // 记录死亡状态
        BastionManager.getInstance().onPlayerDeath(player.server.overworld(), player.getUUID());
        TeamPackManager.getInstance().onPlayerDeath(player.getUUID());

        Espetro.LOGGER.info("玩家 {} 死亡，进入兵站选择状态", player.getName().getString());
    }

    /**
     * 玩家复活时触发
     */
    @SubscribeEvent
    public static void onPlayerRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }

        TeamPackManager.getInstance().syncTeamPackItem(player);

        // 检查玩家是否在等待兵站选择
        if (BastionManager.getInstance().isWaitingForBastion(player.getUUID())) {
            applyWaitingDeployState(player);

            // 延迟发送统一部署界面（等玩家完全重生）
            ServerPlayer finalPlayer = player;
            player.server.execute(() -> {
                // 再次检查状态
                if (BastionManager.getInstance().isWaitingForBastion(finalPlayer.getUUID())) {
                    // 发送统一部署主界面（集成职业选择、复活点选择、载具部署、地图）
                    int remaining = GameStateManager.getInstance().getCurrentPhase() == GamePhase.DEPLOYING
                        ? GameStateManager.getInstance().getDeployTimeRemainingSeconds()
                        : -1;
                    org.espetro.network.NetworkManager.sendUnifiedDeployScreen(finalPlayer, remaining);
                }
            });
        }
    }

    /**
     * 玩家登录时检查是否在等待复活状态
     */
    @SubscribeEvent
    public static void onPlayerJoin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }

        TeamPackManager.getInstance().syncTeamPackItem(player);

        // 如果玩家在等待兵站选择状态
        if (BastionManager.getInstance().isWaitingForBastion(player.getUUID())) {
            // 重新登录不能绕过部署选择：继续保持统一等待状态。
            applyWaitingDeployState(player);
            player.server.execute(() -> {
                if (BastionManager.getInstance().isWaitingForBastion(player.getUUID())) {
                    int remaining = GameStateManager.getInstance().getCurrentPhase() == GamePhase.DEPLOYING
                        ? GameStateManager.getInstance().getDeployTimeRemainingSeconds()
                        : -1;
                    org.espetro.network.NetworkManager.sendUnifiedDeployScreen(player, remaining);
                }
            });
        }
    }

    @SubscribeEvent
    public static void onTeamPackPlace(BlockEvent.EntityPlaceEvent event) {
        if (event.getLevel().isClientSide() || !(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        if (!(event.getLevel() instanceof ServerLevel level) || !event.getPlacedBlock().is(Blocks.BEACON)) {
            return;
        }
        if (!SquadManager.getInstance().isSquadLeader(player.getUUID())) {
            return;
        }

        String error = TeamPackManager.getInstance().placeTeamPack(player, level, event.getPos());
        if (error != null) {
            event.setCanceled(true);
            player.sendSystemMessage(net.minecraft.network.chat.Component.literal(error));
        }
    }

    @SubscribeEvent
    public static void onTeamPackBreak(BlockEvent.BreakEvent event) {
        if (event.getLevel().isClientSide()) {
            return;
        }

        TeamPackManager.TeamPackData teamPack = TeamPackManager.getInstance().findByPos(event.getPos());
        if (teamPack == null) {
            return;
        }

        event.setCanceled(true);

        if (!(event.getPlayer() instanceof ServerPlayer player)) {
            return;
        }

        String playerTeam = Espetro.getPlayerTeam(player);
        boolean isEnemy = playerTeam != null && !playerTeam.equals(teamPack.team);
        if (isEnemy) {
            TeamPackManager.getInstance().damageTeamPack(teamPack, player, 1, true);
            return;
        }

        boolean isLeaderOfThisSquad = SquadManager.getInstance().isSquadLeader(player.getUUID())
            && SquadManager.getInstance().getPlayerSquadId(player.getUUID()) == teamPack.squadId;
        if (!isLeaderOfThisSquad) {
            player.sendSystemMessage(net.minecraft.network.chat.Component.literal("§c只有本小队队长才能拆除队包！"));
            return;
        }

        TeamPackManager.getInstance().destroyTeamPack(teamPack, player, true, false);
    }

    @SubscribeEvent
    public static void onTeamPackEnemyLeftClick(PlayerInteractEvent.LeftClickBlock event) {
        if (event.getLevel().isClientSide() || !(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }

        TeamPackManager.TeamPackData teamPack = TeamPackManager.getInstance().findByPos(event.getPos());
        if (teamPack == null) {
            return;
        }

        String playerTeam = Espetro.getPlayerTeam(player);
        boolean isEnemy = playerTeam != null && !playerTeam.equals(teamPack.team);
        if (isEnemy) {
            event.setCanceled(true);
            TeamPackManager.getInstance().damageTeamPack(teamPack, player, 1, true);
        }
    }

    @SubscribeEvent
    public static void onTeamPackRightClick(PlayerInteractEvent.RightClickBlock event) {
        if (event.getLevel().isClientSide() || !(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }

        TeamPackManager.TeamPackData teamPack = TeamPackManager.getInstance().findByPos(event.getPos());
        if (teamPack == null) {
            return;
        }

        event.setCanceled(true);

        String playerTeam = Espetro.getPlayerTeam(player);
        boolean isEnemy = playerTeam != null && !playerTeam.equals(teamPack.team);
        if (isEnemy) {
            TeamPackManager.getInstance().damageTeamPack(teamPack, player, 1, true);
        }
    }

    @SubscribeEvent
    public static void onTeamPackBreakSpeed(PlayerEvent.BreakSpeed event) {
        if (event.getEntity().level().isClientSide()) {
            return;
        }

        BlockPos pos = event.getPosition().orElse(null);
        if (pos == null || TeamPackManager.getInstance().findByPos(pos) == null) {
            return;
        }

        float multiplier = TeamPackManager.getInstance().getBreakSpeedMultiplier();
        event.setNewSpeed(event.getNewSpeed() * multiplier);
    }

    @SubscribeEvent
    public static void onExplosionDetonateTeamPack(ExplosionEvent.Detonate event) {
        if (event.getLevel().isClientSide()) {
            return;
        }
        for (BlockPos pos : event.getAffectedBlocks()) {
            TeamPackManager.TeamPackData teamPack = TeamPackManager.getInstance().findByPos(pos);
            if (teamPack != null) {
                TeamPackManager.getInstance().destroyTeamPackByExplosion(teamPack);
            }
        }
    }

    @SubscribeEvent
    public static void onBastionCoreInteract(PlayerInteractEvent.EntityInteract event) {
        cancelBastionCoreInteract(event, event.getTarget());
    }

    @SubscribeEvent
    public static void onBastionCoreInteractSpecific(PlayerInteractEvent.EntityInteractSpecific event) {
        cancelBastionCoreInteract(event, event.getTarget());
    }

    private static void cancelBastionCoreInteract(PlayerInteractEvent event, Entity target) {
        if (!(target instanceof ArmorStand armorStand) || !isBastionCore(armorStand)) {
            return;
        }
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.SUCCESS);
        if (!(event.getEntity() instanceof ServerPlayer player) || player.level().isClientSide) {
            return;
        }
        BastionData bastion = BastionManager.getInstance().findBastionByArmorStand(armorStand.getUUID());
        if (bastion == null) {
            return;
        }
        SupplyManager.DepositResult result = SupplyManager.getInstance().depositAll(player, bastion);
        if (result.success()) {
            player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                "§a已存入 FOB §7| §6建材 +" + result.construction()
                    + " §7| §b弹药 +" + result.ammunition()
                    + " §7| 库存 §6" + bastion.getConstructionSupplies()
                    + "§7/§b" + bastion.getAmmunitionSupplies()
                    + " §7| " + BastionManager.getInstance().getFobStatus(bastion)));
        } else {
            player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                result.error() + " §7| §6" + bastion.getConstructionSupplies()
                    + " 建材 §7| §b" + bastion.getAmmunitionSupplies()
                    + " 弹药 §7| " + BastionManager.getInstance().getFobStatus(bastion)));
        }
    }

    @SubscribeEvent
    public static void onSupplySourceInteract(PlayerInteractEvent.RightClickBlock event) {
        if (event.getLevel().isClientSide() || !(event.getEntity() instanceof ServerPlayer player)
            || !(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        if (SupplyManager.getInstance().handleSourceInteraction(player, level, event.getPos())) {
            event.setCanceled(true);
            event.setCancellationResult(InteractionResult.SUCCESS);
        }
    }

    /**
     * 玩家右击兵站潜影盒 - 弹药补给
     */
    @SubscribeEvent
    public static void onShulkerBoxInteract(PlayerInteractEvent.RightClickBlock event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        ServerLevel level = (ServerLevel) event.getLevel();
        BlockPos clickedPos = event.getPos();
        BlockState state = level.getBlockState(clickedPos);

        // 检查是否是潜影盒
        if (!state.is(Blocks.RED_SHULKER_BOX) && !state.is(Blocks.BLUE_SHULKER_BOX)) return;

        // 根据潜影盒位置查找兵站
        BastionData bastion = BastionManager.getInstance().findBastionByShulkerPos(clickedPos);
        if (bastion == null) return;

        // 检查是否是同一个队伍
        String team = Espetro.getPlayerTeam(player);
        if (team == null || !team.equals(bastion.getTeam())) {
            event.setCanceled(true);
            return; // 静默，不给敌方提示
        }

        if (player.isShiftKeyDown()) {
            SupplyManager.DepositResult deposit = SupplyManager.getInstance().depositAll(player, bastion);
            player.sendSystemMessage(net.minecraft.network.chat.Component.literal(deposit.success()
                ? "§a已向 FOB 存入 §6" + deposit.construction() + " 建材 §a与 §b"
                    + deposit.ammunition() + " 弹药。"
                : deposit.error()));
            event.setCanceled(true);
            return;
        }

        if (!bastion.isAmmoCrateBuilt()) {
            player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                "§c该 FOB 尚未建成弹药箱，需要继续存入建材。"));
            event.setCanceled(true);
            return;
        }

        // 获取玩家职业配置
        String classId = ClassCountManager.getInstance().getPlayerClass(player.getUUID());
        String variantId = ClassCountManager.getInstance().getPlayerVariant(player.getUUID());
        if (classId == null) {
            player.sendSystemMessage(net.minecraft.network.chat.Component.literal("§c你没有选择职业，无法补给弹药！"));
            event.setCanceled(true);
            return;
        }

        // 加载补给配置
        FactionDataLoader loader = FactionDataProvider.getOrCreateLoader();
        MinecraftServer server = player.getServer();
        if (server != null) loader.ensureLoaded(server.getResourceManager());
        FactionDataLoader.ClassKitData kit = loader.getClassKit(classId);
        FactionDataLoader.ClassVariantData variant = kit != null ? kit.getVariant(variantId) : null;
        FactionDataLoader.ResupplyData resupply = variant != null ? variant.resupply : null;
        if (resupply == null || resupply.items == null || resupply.items.length == 0) {
            player.sendSystemMessage(net.minecraft.network.chat.Component.literal("§c该职业装备变体没有配置弹药补给！"));
            event.setCanceled(true);
            return;
        }

        int ammoCost = resupply.ammoCost != null
            ? Math.max(0, resupply.ammoCost)
            : LogisticsConfig.get().defaultResupplyAmmoCost;
        if (bastion.getAmmunitionSupplies() <= 0) {
            player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                "§cFOB 弹药库存为 0，无法补给。"));
            event.setCanceled(true);
            return;
        }

        String errorMsg = BastionManager.getInstance().tryResupply(player.getUUID());
        if (errorMsg != null) {
            player.sendSystemMessage(net.minecraft.network.chat.Component.literal(errorMsg));
            event.setCanceled(true);
            return;
        }

        org.espetro.tutorial.TutorialManager.getInstance()
            .tryShow(player, org.espetro.tutorial.TutorialStep.RESUPPLY);

        // 智能补给：检查背包已有数量，补充到上限
        int givenItems = 0;
        StringBuilder detail = new StringBuilder();
        for (FactionDataLoader.ResupplyItem ri : resupply.items) {
            if (ri.id == null || ri.id.isBlank()) continue;
            ItemStack template = createResupplyStack(ri);
            if (template.isEmpty()) {
                continue;
            }
            Item item = template.getItem();
            if (item == net.minecraft.world.item.Items.AIR) {
                Espetro.LOGGER.warn("补给物品不存在: {}", ri.id);
                continue;
            }
            int maxCap = ri.max > 0 ? ri.max : 64;
            int giveCount = ri.count > 0 ? ri.count : 16;

            // 统计背包中已有数量
            int current = 0;
            for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
                ItemStack stack = player.getInventory().getItem(i);
                if (matchesResupplyItem(stack, template)) current += stack.getCount();
            }

            // 计算可补充数量（不超过上限）
            int canGive = Math.min(giveCount, maxCap - current);
            if (canGive > 0) {
                ItemStack giveStack = template.copy();
                giveStack.setCount(canGive);
                if (!player.getInventory().add(giveStack)) {
                    player.drop(giveStack, false);
                }
                givenItems++;
                if (!detail.isEmpty()) detail.append(", ");
                detail.append(giveStack.getHoverName().getString()).append(" ×").append(canGive);
            }
        }

        if (givenItems == 0) {
            player.sendSystemMessage(net.minecraft.network.chat.Component.literal("§e你的弹药已满，无需补给！"));
            event.setCanceled(true);
            return;
        }

        // 记录补给
        int chargedAmmo = 0;
        int availableAmmo = Math.min(ammoCost, bastion.getAmmunitionSupplies());
        if (availableAmmo > 0
            && BastionManager.getInstance().tryConsumeFobAmmunition(bastion, availableAmmo)) {
            chargedAmmo = availableAmmo;
        }
        BastionManager.getInstance().recordResupply(player.getUUID());
        String chargeDetail = chargedAmmo == ammoCost
            ? "消耗 §b" + chargedAmmo + " FOB 弹药"
            : "FOB 弹药不足 §7(需要 §b" + ammoCost + "§7)，已扣除剩余 §b"
                + chargedAmmo + " §7并归零";
        player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
            "§a▸ 已补充: §f" + detail + "  §7| " + chargeDetail + " §7| 冷却5分钟"));
        event.setCanceled(true);
    }

    private static ItemStack createResupplyStack(FactionDataLoader.ResupplyItem ri) {
        String id = ri.id.trim();
        String nbt = ri.nbt;
        int tagStart = id.indexOf('{');
        if (tagStart >= 0) {
            if (nbt == null || nbt.isBlank()) {
                nbt = id.substring(tagStart);
            }
            id = id.substring(0, tagStart);
        }

        Item item = BuiltInRegistries.ITEM.get(ResourceLocation.parse(id));
        if (item == net.minecraft.world.item.Items.AIR) {
            Espetro.LOGGER.warn("补给物品不存在: {}", id);
            return ItemStack.EMPTY;
        }

        ItemStack stack = new ItemStack(item);
        if (nbt != null && !nbt.isBlank()) {
            try {
                CompoundTag tag = TagParser.parseTag(nbt);
                stack.setTag(tag);
            } catch (CommandSyntaxException e) {
                Espetro.LOGGER.warn("补给物品 NBT 格式错误: id={}, nbt={}, error={}", id, nbt, e.getMessage());
                return ItemStack.EMPTY;
            }
        }
        return stack;
    }

    private static boolean matchesResupplyItem(ItemStack stack, ItemStack template) {
        if (template.hasTag()) {
            return ItemStack.isSameItemSameTags(stack, template);
        }
        return stack.is(template.getItem());
    }

    private static boolean isBastionCore(ArmorStand armorStand) {
        return armorStand.getTags().contains("bastion_armor_stand");
    }

    /**
     * 玩家重生时的状态复制
     * 等待状态由 onPlayerRespawn 处理，这里不做清除
     */
    @SubscribeEvent
    public static void onPlayerClone(PlayerEvent.Clone event) {
        // 不做任何操作，等待状态保留给 onPlayerRespawn 处理
        // 重新登录也不会清除等待状态，避免绕过部署点选择
    }

    /**
     * 每 tick 检查等待复活选择的玩家，锁定其位置
     */
    @SubscribeEvent
    public static void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (!(event.player instanceof ServerPlayer player)) return;

        if (!BastionManager.getInstance().isWaitingForBastion(player.getUUID())) {
            return;
        }

        applyWaitingDeployState(player);
    }

    private static void applyWaitingDeployState(ServerPlayer player) {
        GameStateManager.getInstance().applyDeploymentWaitingState(player);
    }
}
