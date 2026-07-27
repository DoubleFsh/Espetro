package org.espetro.bastion;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.espetro.Espetro;
import org.espetro.logistics.LogisticsConfig;
import org.espetro.team.GamePhase;
import org.espetro.team.GameStateManager;
import org.espetro.team.SquadManager;
import org.espetro.team.VoteManager;

/**
 * Alt 轮盘部署动作（服务端）。
 * 原「兵站建筑指令」鱼竿与「载具部署木棍」的功能已全部并入此处，旧物品已移除。
 */
public final class DeployActions {

    private DeployActions() {
    }

    /** 轮盘「部署 Radio」：发放 1 个 Radio 方块（限 1，含冷却）。 */
    public static void giveRadioItem(ServerPlayer serverPlayer) {
        LogisticsConfig.RadioPlacementSettings radio = LogisticsConfig.get().getRadio();
        GamePhase phase = GameStateManager.getInstance().getCurrentPhase();
        String phaseName = phase != null ? phase.name() : "";

        if (!radio.allowsPhase(phaseName)) {
            serverPlayer.sendSystemMessage(Component.literal(
                "§c当前阶段不能部署 Radio！允许阶段: " + String.join(", ", radio.allowedPhases)));
            return;
        }
        if (!checkCommanderOrLeader(serverPlayer, radio, "Radio")) {
            return;
        }
        String team = Espetro.getPlayerTeam(serverPlayer);
        if (team == null) {
            serverPlayer.sendSystemMessage(Component.literal("§c无法确定你的队伍！"));
            return;
        }

        BastionManager manager = BastionManager.getInstance();
        String cooldownMsg = manager.canBuildBastion(
            serverPlayer.getUUID(), manager.getEffectiveRadioCooldownSeconds());
        if (cooldownMsg != null) {
            serverPlayer.sendSystemMessage(Component.literal(cooldownMsg));
            return;
        }

        if (BastionItems.RADIO_BLOCK_ITEM == null) {
            serverPlayer.sendSystemMessage(Component.literal("§cRadio 物品未注册。"));
            return;
        }
        if (hasRadioItem(serverPlayer)) {
            serverPlayer.sendSystemMessage(Component.literal("§e你已经携带了一台 Radio，先放置它。"));
            return;
        }

        ItemStack stack = new ItemStack(BastionItems.RADIO_BLOCK_ITEM);
        if (!serverPlayer.getInventory().add(stack)) {
            serverPlayer.drop(stack, false);
        }
        serverPlayer.sendSystemMessage(Component.literal(
            "§a已领取 Radio。找到合适位置放置（放置时校验冷却/排斥/上限）。"));
    }

    /** 轮盘「部署兵站」：在脚下发起 10 秒原地读条。 */
    public static void startHabChannel(ServerPlayer serverPlayer) {
        LogisticsConfig.RadioPlacementSettings radio = LogisticsConfig.get().getRadio();
        GamePhase phase = GameStateManager.getInstance().getCurrentPhase();
        String phaseName = phase != null ? phase.name() : "";

        if (!radio.allowsPhase(phaseName)) {
            serverPlayer.sendSystemMessage(Component.literal(
                "§c当前阶段不能部署兵站！允许阶段: " + String.join(", ", radio.allowedPhases)));
            return;
        }
        if (!checkCommanderOrLeader(serverPlayer, radio, "兵站")) {
            return;
        }
        String team = Espetro.getPlayerTeam(serverPlayer);
        if (team == null) {
            serverPlayer.sendSystemMessage(Component.literal("§c无法确定你的队伍！"));
            return;
        }

        HabChannelManager.getInstance().start(serverPlayer, team);
    }

    /** 轮盘「载具部署」：打开载具部署面板（原木棍右键功能，仅指挥官）。 */
    public static void openVehicleDeploy(ServerPlayer player) {
        if (!VoteManager.getInstance().isCommander(player.getUUID())) {
            player.sendSystemMessage(Component.literal("§c只有指挥官可以部署载具！"));
            return;
        }
        String factionId = org.espetro.team.ClassCountManager.getInstance()
            .getPlayerFaction(player.getUUID());
        if (factionId == null) {
            player.sendSystemMessage(Component.literal("§c你还没有选择编制！"));
            return;
        }
        org.espetro.network.NetworkManager.sendVehicleDeployScreen(player, factionId);
    }

    public static boolean hasRadioItem(ServerPlayer player) {
        if (BastionItems.RADIO_BLOCK_ITEM == null) {
            return false;
        }
        for (ItemStack stack : player.getInventory().items) {
            if (stack.getItem() == BastionItems.RADIO_BLOCK_ITEM) {
                return true;
            }
        }
        for (ItemStack stack : player.getInventory().offhand) {
            if (stack.getItem() == BastionItems.RADIO_BLOCK_ITEM) {
                return true;
            }
        }
        return false;
    }

    static boolean checkCommanderOrLeader(ServerPlayer serverPlayer,
                                          LogisticsConfig.RadioPlacementSettings radio,
                                          String what) {
        boolean commander = VoteManager.getInstance().isCommander(serverPlayer.getUUID());
        boolean squadLeader = SquadManager.getInstance().isSquadLeader(serverPlayer.getUUID());
        if (radio.requireCommander && !commander) {
            serverPlayer.sendSystemMessage(Component.literal("§c只有指挥官才能部署" + what + "！"));
            return false;
        }
        if (!commander && !(radio.allowSquadLeader && squadLeader)) {
            serverPlayer.sendSystemMessage(Component.literal(
                radio.allowSquadLeader
                    ? "§c只有小队长或指挥官才能部署" + what + "！"
                    : "§c只有指挥官才能部署" + what + "！"));
            return false;
        }
        return true;
    }

    /** HAB：沿用原 FOB 建筑布局（无弹药箱；弹药在 Radio）。 */
    static void buildHabStructure(ServerLevel level, BlockPos center, String team) {
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
            level.setBlock(new BlockPos(x - 3, yi, z - 1), woolBlock, 3);
        }

        int roofY = y + 2;
        for (int xi = x - 3; xi <= x + 1; xi++) {
            for (int zi = z - 1; zi <= z + 2; zi++) {
                level.setBlock(new BlockPos(xi, roofY, zi), roofBlock, 3);
            }
        }
        level.setBlock(new BlockPos(x, y + 1, z + 1), Blocks.LANTERN.defaultBlockState(), 3);
    }
}
