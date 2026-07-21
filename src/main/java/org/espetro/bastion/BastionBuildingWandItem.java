package org.espetro.bastion;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.FishingRodItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.espetro.Espetro;
import org.espetro.logistics.LogisticsConfig;
import org.espetro.logistics.SupplyManager;
import org.espetro.team.GamePhase;
import org.espetro.team.GameStateManager;
import org.espetro.team.SquadManager;
import org.espetro.team.VoteManager;

import javax.annotation.Nullable;
import java.util.List;

/**
 * 兵站建筑指令鱼竿
 * 指挥官/小队长使用此物品右键来部署 Radio（兵站）
 */
public class BastionBuildingWandItem extends FishingRodItem {

    public static final String BASTION_WAND_ID = "bastion_building_wand";

    public BastionBuildingWandItem() {
        super(new Properties()
            .durability(-1)
            .stacksTo(1)
        );
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        if (!(player instanceof ServerPlayer serverPlayer)) {
            return InteractionResultHolder.pass(player.getItemInHand(hand));
        }

        LogisticsConfig.RadioPlacementSettings radio = LogisticsConfig.get().getRadio();
        BastionManager bastionManager = BastionManager.getInstance();
        GamePhase phase = GameStateManager.getInstance().getCurrentPhase();
        String phaseName = phase != null ? phase.name() : "";

        if (!radio.allowsPhase(phaseName)) {
            serverPlayer.sendSystemMessage(Component.literal(
                "§c当前阶段不能部署 Radio！允许阶段: " + String.join(", ", radio.allowedPhases)));
            return InteractionResultHolder.fail(player.getItemInHand(hand));
        }

        boolean commander = isCommander(serverPlayer);
        boolean squadLeader = SquadManager.getInstance().isSquadLeader(serverPlayer.getUUID());
        if (radio.requireCommander && !commander) {
            serverPlayer.sendSystemMessage(Component.literal("§c只有指挥官才能部署 Radio！"));
            return InteractionResultHolder.fail(player.getItemInHand(hand));
        }
        if (!commander && !(radio.allowSquadLeader && squadLeader)) {
            serverPlayer.sendSystemMessage(Component.literal(
                radio.allowSquadLeader
                    ? "§c只有小队长或指挥官才能部署 Radio！"
                    : "§c只有指挥官才能部署 Radio！"));
            return InteractionResultHolder.fail(player.getItemInHand(hand));
        }

        int cooldownSeconds = bastionManager.getEffectiveRadioCooldownSeconds();
        String cooldownMsg = bastionManager.canBuildBastion(serverPlayer.getUUID(), cooldownSeconds);
        if (cooldownMsg != null) {
            serverPlayer.sendSystemMessage(Component.literal(cooldownMsg));
            return InteractionResultHolder.fail(player.getItemInHand(hand));
        }

        String team = Espetro.getPlayerTeam(serverPlayer);
        if (team == null) {
            serverPlayer.sendSystemMessage(Component.literal("§c无法确定你的队伍！"));
            return InteractionResultHolder.fail(player.getItemInHand(hand));
        }

        if (!bastionManager.hasBastionCapacity(team)) {
            serverPlayer.sendSystemMessage(Component.literal("§c本方生效兵站数量已达到上限（"
                + bastionManager.getBastionLimitPerTeam() + "个），无法继续建造！"));
            return InteractionResultHolder.fail(player.getItemInHand(hand));
        }

        int requiredConstruction = bastionManager.getEffectiveRadioRequiredConstruction();
        boolean bypassConstruction = serverPlayer.isCreative() && radio.creativeBypassesPlanks;
        if (!bypassConstruction && requiredConstruction > 0) {
            int have = SupplyManager.getInstance().countConstructionPoints(serverPlayer);
            if (have < requiredConstruction) {
                serverPlayer.sendSystemMessage(Component.literal(
                    "§c部署 Radio 需要 " + requiredConstruction + " 点补给建材！"
                        + " 当前 " + have + " 点（请从补给站领取）。"));
                return InteractionResultHolder.fail(player.getItemInHand(hand));
            }
        }

        BlockPos lookPos = getTargetBlockPos(serverPlayer, radio.requireTargetBlock);
        if (lookPos == null) {
            serverPlayer.sendSystemMessage(Component.literal(
                radio.requireTargetBlock
                    ? "§c请看向一个固体方块来放置 Radio！"
                    : "§c请看向一个有效的位置来放置兵站！"));
            return InteractionResultHolder.fail(player.getItemInHand(hand));
        }
        BlockPos targetPos = new BlockPos(lookPos.getX(), serverPlayer.blockPosition().getY(), lookPos.getZ());

        ServerLevel serverLevel = serverPlayer.serverLevel();
        if (bastionManager.findNearestBastion(
            serverLevel, targetPos, null, radio.exclusionRadius) != null) {
            serverPlayer.sendSystemMessage(Component.literal(
                "§c附近已有 Radio，排斥半径为 " + formatRadius(radio.exclusionRadius) + " 格。"));
            return InteractionResultHolder.fail(player.getItemInHand(hand));
        }
        int nearbyTeammates = countNearbyTeammates(
            serverPlayer, team, targetPos, radio.teammateRadius);
        if (nearbyTeammates < radio.teammateCount) {
            serverPlayer.sendSystemMessage(Component.literal(
                "§c部署 Radio 需要放置点 " + formatRadius(radio.teammateRadius)
                    + " 格内至少 " + radio.teammateCount + " 名队友！当前仅 "
                    + nearbyTeammates + " 名。"));
            return InteractionResultHolder.fail(player.getItemInHand(hand));
        }

        String bastionName = generateBastionName(team);
        BastionData bastion = bastionManager.createBastion(
            serverLevel, targetPos, team, bastionName
        );

        if (bastion != null) {
            if (!bypassConstruction && requiredConstruction > 0) {
                SupplyManager.getInstance().consumeConstructionPoints(serverPlayer, requiredConstruction);
            }

            bastionManager.setBastionCooldown(serverPlayer.getUUID());
            org.espetro.tutorial.TutorialManager.getInstance().tryShow(
                serverPlayer, org.espetro.tutorial.TutorialStep.BASTION);

            buildBastionStructure(serverLevel, targetPos, team);
            bastion.setShulkerPos(new BlockPos(targetPos.getX(), targetPos.getY(), targetPos.getZ() + 1));

            serverLevel.playSound(null, targetPos, SoundEvents.EXPERIENCE_ORB_PICKUP,
                SoundSource.PLAYERS, 1.0f, 1.0f);

            serverPlayer.sendSystemMessage(Component.literal("§aRadio §e" + bastionName
                + " §a已部署！位置: " + targetPos.getX() + ", " + targetPos.getY() + ", " + targetPos.getZ()));
            serverPlayer.sendSystemMessage(Component.literal("§7向 Radio 存入建材后，将依次建成 HAB 与弹药箱。"));
            serverPlayer.sendSystemMessage(Component.literal("§7兵站建造冷却: " + cooldownSeconds + "秒"));
            Espetro.broadcastToTeam(team, "§6[兵站] §a" + serverPlayer.getName().getString()
                + " §a部署了 Radio §b" + bastionName);
        } else {
            serverPlayer.sendSystemMessage(Component.literal("§c兵站创建失败！"));
        }

        return InteractionResultHolder.sidedSuccess(player.getItemInHand(hand), level.isClientSide);
    }

    private boolean isCommander(ServerPlayer player) {
        return VoteManager.getInstance().isCommander(player.getUUID());
    }

    private int countNearbyTeammates(ServerPlayer player, String team, BlockPos center, double radius) {
        double radiusSquared = radius * radius;
        int count = 0;
        for (ServerPlayer other : player.serverLevel().players()) {
            if (other == player || !other.isAlive() || other.isSpectator()) {
                continue;
            }
            if (team.equals(Espetro.getPlayerTeam(other))
                && other.blockPosition().distSqr(center) <= radiusSquared) {
                count++;
            }
        }
        return count;
    }

    private static String formatRadius(double radius) {
        return radius == Math.rint(radius)
            ? Integer.toString((int) radius)
            : Double.toString(radius);
    }

    /**
     * 视线落点。requireTargetBlock 为 true 时未命中实心方块返回 null；否则可回退到脚下。
     */
    @Nullable
    private BlockPos getTargetBlockPos(ServerPlayer player, boolean requireTargetBlock) {
        Vec3 eyePos = player.getEyePosition();
        Vec3 lookVec = player.getLookAngle();
        int maxDist = 10;

        Level level = player.level();
        for (int i = 1; i <= maxDist; i++) {
            Vec3 target = eyePos.add(lookVec.scale(i));
            BlockPos pos = BlockPos.containing(target.x, target.y, target.z);
            BlockState state = level.getBlockState(pos);

            if (!state.isAir() && state.getBlock() != Blocks.WATER
                && state.getBlock() != Blocks.LAVA) {
                return pos.above();
            }
        }

        if (requireTargetBlock) {
            return null;
        }
        return player.blockPosition().above();
    }

    private String generateBastionName(String team) {
        int number = 1;
        for (BastionData bastion : BastionManager.getInstance().getAllBastions()) {
            if (bastion.isActive() && team.equals(bastion.getTeam())) {
                number++;
            }
        }
        return team.equals("ATTACK") ? "进攻FOB-" + number : "防守FOB-" + number;
    }

    private void buildBastionStructure(ServerLevel level, BlockPos center, String team) {
        boolean isAttack = "ATTACK".equals(team);
        BlockState woolBlock = isAttack ? Blocks.RED_WOOL.defaultBlockState() : Blocks.BLUE_WOOL.defaultBlockState();
        BlockState roofBlock = Blocks.SPRUCE_TRAPDOOR.defaultBlockState();

        int x = center.getX();
        int y = center.getY();
        int z = center.getZ();

        for (int xi = x - 1; xi <= x; xi++) {
            for (int yi = y; yi <= y + 1; yi++) {
                level.setBlock(new BlockPos(xi, yi, z - 1), woolBlock, 3);
            }
        }
        for (int zi = z - 1; zi <= z + 2; zi++) {
            for (int yi = y; yi <= y + 1; yi++) {
                level.setBlock(new BlockPos(x + 1, yi, zi), woolBlock, 3);
            }
        }
        for (int xi = x - 1; xi <= x + 1; xi++) {
            for (int yi = y; yi <= y + 1; yi++) {
                level.setBlock(new BlockPos(xi, yi, z + 2), woolBlock, 3);
            }
        }
        for (int yi = y; yi <= y + 1; yi++) {
            level.setBlock(new BlockPos(x - 3, yi, z + 2), woolBlock, 3);
        }
        for (int yi = y; yi <= y + 1; yi++) {
            level.setBlock(new BlockPos(x - 3, yi, z - 1), woolBlock, 3);
        }

        int roofY = y + 2;
        for (int xi = x - 3; xi <= x + 1; xi++) {
            for (int zi = z - 1; zi <= z + 2; zi++) {
                level.setBlock(new BlockPos(xi, roofY, zi), roofBlock, 3);
            }
        }

        level.setBlock(new BlockPos(x, y + 1, z + 1), Blocks.LANTERN.defaultBlockState(), 3);
        BlockPos shulkerPos = new BlockPos(x, y, z + 1);
        BlockState shulkerBox = (isAttack ? Blocks.RED_SHULKER_BOX : Blocks.BLUE_SHULKER_BOX)
            .defaultBlockState().setValue(ShulkerBoxBlock.FACING, Direction.SOUTH);
        level.setBlock(shulkerPos, shulkerBox, 3);
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip, TooltipFlag flag) {
        BastionManager config = BastionManager.getInstance();
        int cooldown = config.getEffectiveRadioCooldownSeconds();
        int health = config.getArmorStandHealth();
        int required = config.getEffectiveRadioRequiredConstruction();

        tooltip.add(Component.literal("§6你总不会是战犯吧"));
        tooltip.add(Component.literal("§e右键在目标位置部署 Radio"));
        tooltip.add(Component.literal("§7放置一个有" + health + "血的盔甲架作为核心"));
        if (required > 0) {
            tooltip.add(Component.literal("§7建造消耗: " + required + " 点补给建材"));
        }
        tooltip.add(Component.literal("§c使用冷却: " + cooldown + "秒"));
    }
}
