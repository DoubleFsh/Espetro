package org.espetro.bastion;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.TagParser;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.projectile.ThrownTrident;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.ProjectileImpactEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.event.entity.player.AttackEntityEvent;
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
import org.espetro.team.SpawnPointConfig;

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
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }

        // 检查是否是对战阶段
        GamePhase phase = GameStateManager.getInstance().getCurrentPhase();
        if (phase != GamePhase.BATTLE) {
            return;
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

        // 重置弹药补给次数（每次死亡后重新计算）
        BastionManager.getInstance().resetResupplyCount(player.getUUID());

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

        // 检查玩家是否在等待兵站选择
        if (BastionManager.getInstance().isWaitingForBastion(player.getUUID())) {
            // 立即设置旁观模式
            player.setGameMode(GameType.SPECTATOR);

            // 施加失明效果
            player.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, Integer.MAX_VALUE, 0, false, false, false));

            // 锁定玩家位置（不可移动）
            Vec3 lockPos = player.position();
            BastionManager.getInstance().lockPlayerPosition(player.getUUID(), lockPos);

            // 延迟发送统一部署界面（等玩家完全重生）
            ServerPlayer finalPlayer = player;
            player.server.execute(() -> {
                // 再次检查状态
                if (BastionManager.getInstance().isWaitingForBastion(finalPlayer.getUUID())) {
                    // 发送统一部署主界面（集成职业选择、复活点选择、载具部署、地图）
                    org.espetro.network.NetworkManager.sendUnifiedDeployScreen(finalPlayer, -1);
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

        // 如果玩家在等待兵站选择状态
        if (BastionManager.getInstance().isWaitingForBastion(player.getUUID())) {
            // 清除等待状态，让玩家正常游戏
            BastionManager.getInstance().clearWaiting(player.getUUID());
            player.sendSystemMessage(net.minecraft.network.chat.Component.literal("§e你之前的复活选择已重置。"));
        }
    }

    @SubscribeEvent
    public static void onBastionCorePlayerAttack(AttackEntityEvent event) {
        if (!(event.getTarget() instanceof ArmorStand armorStand) || !isBastionCore(armorStand)) {
            return;
        }

        event.setCanceled(true);
        if (armorStand.level().isClientSide || !(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }

        BastionData bastion = BastionManager.getInstance().findBastionByArmorStand(armorStand.getUUID());
        if (bastion == null || !bastion.isActive()) {
            return;
        }

        float damage = (float) player.getAttributeValue(Attributes.ATTACK_DAMAGE);
        float attackScale = player.getAttackStrengthScale(0.5F);
        damage *= 0.2F + attackScale * attackScale * 0.8F;
        BastionManager.getInstance().damageBastionCore(bastion, Math.max(1.0F, damage), player);
        player.resetAttackStrengthTicker();
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
    }

    @SubscribeEvent
    public static void onBastionCoreProjectileImpact(ProjectileImpactEvent event) {
        if (event.getRayTraceResult().getType() != HitResult.Type.ENTITY) {
            return;
        }

        Entity target = ((EntityHitResult) event.getRayTraceResult()).getEntity();
        if (!(target instanceof ArmorStand armorStand) || !isBastionCore(armorStand)) {
            return;
        }

        event.setImpactResult(ProjectileImpactEvent.ImpactResult.STOP_AT_CURRENT_NO_DAMAGE);
        if (armorStand.level().isClientSide) {
            return;
        }

        BastionData bastion = BastionManager.getInstance().findBastionByArmorStand(armorStand.getUUID());
        if (bastion == null || !bastion.isActive()) {
            return;
        }

        Entity projectile = event.getProjectile();
        float damage = estimateProjectileDamage(projectile);
        BastionManager.getInstance().damageBastionCore(bastion, damage, event.getProjectile().getOwner());
    }

    /**
     * 兜底处理火焰、爆炸等仍会进入 LivingHurtEvent 的伤害，防止原版盔甲架逻辑绕过核心血量。
     */
    @SubscribeEvent
    public static void onBastionCoreHurt(LivingHurtEvent event) {
        Entity entity = event.getEntity();
        if (!(entity instanceof ArmorStand armorStand) || !isBastionCore(armorStand)) {
            return;
        }

        event.setCanceled(true);
        BastionData bastion = BastionManager.getInstance().findBastionByArmorStand(armorStand.getUUID());
        if (bastion != null && bastion.isActive()) {
            BastionManager.getInstance().damageBastionCore(bastion, event.getAmount(), event.getSource().getEntity());
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

        // 获取玩家职业配置
        String factionId = ClassCountManager.getInstance().getPlayerFaction(player.getUUID());
        String classId = ClassCountManager.getInstance().getPlayerClass(player.getUUID());
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
        if (kit == null || kit.resupply == null || kit.resupply.items == null || kit.resupply.items.length == 0) {
            player.sendSystemMessage(net.minecraft.network.chat.Component.literal("§c该职业没有配置弹药补给！"));
            event.setCanceled(true);
            return;
        }

        String errorMsg = BastionManager.getInstance().tryResupply(player.getUUID(), kit.resupply.maxResupplies);
        if (errorMsg != null) {
            player.sendSystemMessage(net.minecraft.network.chat.Component.literal(errorMsg));
            event.setCanceled(true);
            return;
        }

        // 智能补给：检查背包已有数量，补充到上限
        int givenItems = 0;
        StringBuilder detail = new StringBuilder();
        for (FactionDataLoader.ResupplyItem ri : kit.resupply.items) {
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
        BastionManager.getInstance().recordResupply(player.getUUID());
        player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
            "§a▸ 已补充: §f" + detail + "  §7| 冷却5分钟"));
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

    private static float estimateProjectileDamage(Entity projectile) {
        if (projectile instanceof AbstractArrow arrow) {
            double speed = arrow.getDeltaMovement().length();
            int damage = net.minecraft.util.Mth.ceil(net.minecraft.util.Mth.clamp(
                speed * arrow.getBaseDamage(),
                0.0D,
                (double) Integer.MAX_VALUE
            ));
            if (arrow.isCritArrow()) {
                damage += Math.max(1, damage / 2);
            }
            return Math.max(1.0F, damage);
        }
        if (projectile instanceof ThrownTrident) {
            return 8.0F;
        }
        return 4.0F;
    }

    /**
     * 玩家重生时的状态复制
     * 等待状态由 onPlayerRespawn 处理，这里不做清除
     */
    @SubscribeEvent
    public static void onPlayerClone(PlayerEvent.Clone event) {
        // 不做任何操作，等待状态保留给 onPlayerRespawn 处理
        // 如果玩家在死亡画面断开连接，onPlayerLoggedIn 会清除状态
    }

    /**
     * 每 tick 检查等待复活选择的玩家，锁定其位置
     */
    @SubscribeEvent
    public static void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (!(event.player instanceof ServerPlayer player)) return;

        Vec3 lockPos = BastionManager.getInstance().getPlayerLockPosition(player.getUUID());
        if (lockPos != null) {
            // 如果玩家移动了，将其传送回锁定位置
            if (player.distanceToSqr(lockPos) > 0.01) {
                player.teleportTo(lockPos.x, lockPos.y, lockPos.z);
                player.setDeltaMovement(0, 0, 0);
            }
        }
    }
}
