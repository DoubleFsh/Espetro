package org.espetro.bastion;

import net.minecraft.core.BlockPos;
import net.minecraft.tags.ItemTags;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.FishingRodItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.core.Direction;
import org.espetro.Espetro;
import org.espetro.team.ClassCountManager;
import org.espetro.team.GameStateManager;
import org.espetro.team.VoteManager;

import javax.annotation.Nullable;
import java.util.*;

/**
 * 兵站建筑指令鱼竿
 * 指挥官使用此物品右键来建造兵站
 */
public class BastionBuildingWandItem extends FishingRodItem {

    // 物品标识符
    public static final String BASTION_WAND_ID = "bastion_building_wand";

    public BastionBuildingWandItem() {
        super(new Properties()
            .durability(-1) // 无耐久
            .stacksTo(1)
        );
    }

    /**
     * 右键使用时触发
     */
    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        if (!(player instanceof ServerPlayer serverPlayer)) {
            return InteractionResultHolder.pass(player.getItemInHand(hand));
        }

        // 检查是否是对战阶段
        if (GameStateManager.getInstance().getCurrentPhase() != org.espetro.team.GamePhase.BATTLE) {
            serverPlayer.sendSystemMessage(Component.literal("§c只能在战斗阶段建造兵站！"));
            return InteractionResultHolder.fail(player.getItemInHand(hand));
        }

        // 检查玩家是否是指挥官
        if (!isCommander(serverPlayer)) {
            serverPlayer.sendSystemMessage(Component.literal("§c只有指挥官才能使用此物品！"));
            return InteractionResultHolder.fail(player.getItemInHand(hand));
        }

        // 检查冷却（800秒）
        String cooldownMsg = BastionManager.getInstance().canBuildBastion(serverPlayer.getUUID());
        if (cooldownMsg != null) {
            serverPlayer.sendSystemMessage(Component.literal(cooldownMsg));
            return InteractionResultHolder.fail(player.getItemInHand(hand));
        }

        // 获取玩家所属队伍
        String team = Espetro.getPlayerTeam(serverPlayer);
        if (team == null) {
            serverPlayer.sendSystemMessage(Component.literal("§c无法确定你的队伍！"));
            return InteractionResultHolder.fail(player.getItemInHand(hand));
        }

        // 检查全局兵站数量上限（最多4个）
        if (!BastionManager.getInstance().hasBastionCapacity()) {
            serverPlayer.sendSystemMessage(Component.literal("§c兵站数量已达到上限（" + BastionManager.MAX_BASTIONS + "个），无法继续建造！"));
            return InteractionResultHolder.fail(player.getItemInHand(hand));
        }

        int requiredPlanks = BastionManager.getInstance().getRequiredPlanks();
        if (!serverPlayer.isCreative() && requiredPlanks > 0 && countPlanks(serverPlayer) < requiredPlanks) {
            serverPlayer.sendSystemMessage(Component.literal("§c建造兵站需要 " + requiredPlanks + " 个木板！"));
            return InteractionResultHolder.fail(player.getItemInHand(hand));
        }

        // 获取玩家视线方向的方块位置，Y高度与指挥官一致
        BlockPos lookPos = getTargetBlockPos(serverPlayer);
        if (lookPos == null) {
            serverPlayer.sendSystemMessage(Component.literal("§c请看向一个有效的位置来放置兵站！"));
            return InteractionResultHolder.fail(player.getItemInHand(hand));
        }
        BlockPos targetPos = new BlockPos(lookPos.getX(), serverPlayer.blockPosition().getY(), lookPos.getZ());

        // 生成默认名称
        String bastionName = generateBastionName(team);

        // 在服务端线程创建兵站
        ServerLevel serverLevel = serverPlayer.server.overworld();
        BastionData bastion = BastionManager.getInstance().createBastion(
            serverLevel, targetPos, team, bastionName
        );

        int cooldownSeconds = BastionManager.getInstance().getCooldownSeconds();

        if (bastion != null) {
            if (!serverPlayer.isCreative() && requiredPlanks > 0) {
                consumePlanks(serverPlayer, requiredPlanks);
            }

            // 设置建造冷却
            BastionManager.getInstance().setBastionCooldown(serverPlayer.getUUID());

            // 放置小房子
            buildBastionStructure(serverLevel, targetPos, team);

            // 记录弹药补给潜影盒位置
            bastion.setShulkerPos(new BlockPos(targetPos.getX(), targetPos.getY(), targetPos.getZ() + 1));

            // 播放建造音效
            serverLevel.playSound(null, targetPos, SoundEvents.EXPERIENCE_ORB_PICKUP,
                SoundSource.PLAYERS, 1.0f, 1.0f);

            serverPlayer.sendSystemMessage(Component.literal("§a兵站 §e" + bastionName + " §a已创建！位置: " + targetPos.getX() + ", " + targetPos.getY() + ", " + targetPos.getZ()));
            serverPlayer.sendSystemMessage(Component.literal("§7兵站建造冷却: " + cooldownSeconds + "秒"));
            Espetro.broadcastToTeam(team, "§6[兵站] §a指挥官 §e" + serverPlayer.getName().getString() + " §a建造了兵站 §b" + bastionName);
        } else {
            serverPlayer.sendSystemMessage(Component.literal("§c兵站创建失败！"));
        }

        return InteractionResultHolder.sidedSuccess(player.getItemInHand(hand), level.isClientSide);
    }

    /**
     * 检查玩家是否是指挥官
     */
    private boolean isCommander(ServerPlayer player) {
        return VoteManager.getInstance().isCommander(player.getUUID());
    }

    private int countPlanks(ServerPlayer player) {
        int total = 0;
        for (ItemStack stack : player.getInventory().items) {
            if (stack.is(ItemTags.PLANKS)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    private void consumePlanks(ServerPlayer player, int amount) {
        int remaining = amount;
        for (ItemStack stack : player.getInventory().items) {
            if (remaining <= 0) break;
            if (!stack.is(ItemTags.PLANKS)) continue;

            int consume = Math.min(stack.getCount(), remaining);
            stack.shrink(consume);
            remaining -= consume;
        }
    }

    /**
     * 获取玩家视线方向的方块位置
     */
    @Nullable
    private BlockPos getTargetBlockPos(ServerPlayer player) {
        Vec3 eyePos = player.getEyePosition();
        Vec3 lookVec = player.getLookAngle();
        int maxDist = 10;

        Level level = player.level();
        for (int i = 1; i <= maxDist; i++) {
            Vec3 target = eyePos.add(lookVec.scale(i));
            BlockPos pos = BlockPos.containing(target.x, target.y, target.z);
            BlockState state = level.getBlockState(pos);

            // 找到非空气方块，返回它上面的位置
            if (!state.isAir() && state.getBlock() != Blocks.WATER &&
                state.getBlock() != Blocks.LAVA) {
                return pos.above();
            }
        }

        // 如果没找到，返回脚下上方的位置
        return player.blockPosition().above();
    }

    /**
     * 生成兵站名称
     */
    private String generateBastionName(String team) {
        List<BastionData> teamBastions = BastionManager.getInstance().getTeamBastions(team);
        int number = teamBastions.size() + 1;
        return team.equals("ATTACK") ? "进攻点-" + number : "防守点-" + number;
    }

    /**
     * 建造兵站结构
     * 干草块墙壁按指挥官坐标精确放置，屋顶+队伍颜色地毯铺顶
     * 攻方红地毯/红头盔，守方蓝地毯/蓝头盔
     * 无地板
     */
    private void buildBastionStructure(ServerLevel level, BlockPos center, String team) {
        boolean isAttack = "ATTACK".equals(team);
        BlockState woolBlock = isAttack ? Blocks.RED_WOOL.defaultBlockState() : Blocks.BLUE_WOOL.defaultBlockState();
        BlockState roofBlock = Blocks.SPRUCE_TRAPDOOR.defaultBlockState();
        BlockState carpet = isAttack ? Blocks.RED_CARPET.defaultBlockState() : Blocks.BLUE_CARPET.defaultBlockState();

        int x = center.getX();
        int y = center.getY();
        int z = center.getZ();

        // === 羊毛墙壁（y, y+1 两层） ===
        // (x, y+1, z-1) 到 (x-1, y, z-1)
        for (int xi = x - 1; xi <= x; xi++) {
            for (int yi = y; yi <= y + 1; yi++) {
                level.setBlock(new BlockPos(xi, yi, z - 1), woolBlock, 3);
            }
        }
        // (x+1, y+1, z-1) 到 (x+1, y, z+2)
        for (int zi = z - 1; zi <= z + 2; zi++) {
            for (int yi = y; yi <= y + 1; yi++) {
                level.setBlock(new BlockPos(x + 1, yi, zi), woolBlock, 3);
            }
        }
        // (x+1, y, z+2) 到 (x-1, y+1, z+2)
        for (int xi = x - 1; xi <= x + 1; xi++) {
            for (int yi = y; yi <= y + 1; yi++) {
                level.setBlock(new BlockPos(xi, yi, z + 2), woolBlock, 3);
            }
        }
        // (x-3, y, z+2) 到 (x-3, y+1, z+2)
        for (int yi = y; yi <= y + 1; yi++) {
            level.setBlock(new BlockPos(x - 3, yi, z + 2), woolBlock, 3);
        }
        // (x-3, y, z-1) 到 (x-3, y+1, z-1)
        for (int yi = y; yi <= y + 1; yi++) {
            level.setBlock(new BlockPos(x - 3, yi, z - 1), woolBlock, 3);
        }

        // === 屋顶（y+2）：橡木木板铺满 x-3~x+1, z-1~z+2 ===
        int roofY = y + 2;
        for (int xi = x - 3; xi <= x + 1; xi++) {
            for (int zi = z - 1; zi <= z + 2; zi++) {
                level.setBlock(new BlockPos(xi, roofY, zi), roofBlock, 3);
            }
        }

        // === 内部装饰 ===
        // 灯笼
        level.setBlock(new BlockPos(x, y + 1, z + 1), Blocks.LANTERN.defaultBlockState(), 3);
        // 弹药补给潜影盒（灯笼正下方地面，队伍颜色）
        BlockPos shulkerPos = new BlockPos(x, y, z + 1);
        BlockState shulkerBox = (isAttack ? Blocks.RED_SHULKER_BOX : Blocks.BLUE_SHULKER_BOX)
            .defaultBlockState().setValue(ShulkerBoxBlock.FACING, Direction.SOUTH);
        level.setBlock(shulkerPos, shulkerBox, 3);
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip, TooltipFlag flag) {
        BastionManager config = BastionManager.getInstance();
        int cooldown = config.getCooldownSeconds();
        int health = config.getArmorStandHealth();

        tooltip.add(Component.literal("§6你总不会是战犯吧"));
        tooltip.add(Component.literal("§e右键在目标位置建造兵站"));
        tooltip.add(Component.literal("§7放置一个有" + health + "血的盔甲架作为核心"));
        if (config.getRequiredPlanks() > 0) {
            tooltip.add(Component.literal("§7建造消耗: " + config.getRequiredPlanks() + " 木板"));
        }
        tooltip.add(Component.literal("§c使用冷却: " + cooldown + "秒"));
    }
}
