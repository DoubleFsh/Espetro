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

import javax.annotation.Nullable;
import java.util.List;

/**
 * Alt 轮盘部署动作（服务端）。
 * 原「兵站建筑指令」鱼竿与「载具部署木棍」的功能已全部并入此处，旧物品已移除。
 */
public final class DeployActions {

    private DeployActions() {
    }

    /** Legacy API: now enters the same server-approved construction preview. */
    public static void giveRadioItem(ServerPlayer serverPlayer) {
        beginConstructionPreview(serverPlayer, FortificationManager.BUILTIN_RADIO);
    }

    /** Legacy API: the former stationary HAB channel now enters placement preview. */
    public static void startHabChannel(ServerPlayer serverPlayer) {
        beginConstructionPreview(serverPlayer, FortificationManager.BUILTIN_HAB);
    }

    private static void beginConstructionPreview(ServerPlayer player, String id) {
        String error = FortificationManager.getInstance().beginPreview(player, id);
        player.sendSystemMessage(Component.literal(error == null
            ? "§e左键确认施工范围，右键取消。" : error));
    }

    /** 检查玩家脚点覆盖 Radio 的 HAB 数量是否已达编制上限。 */
    @Nullable
    static String checkHabPerRadioLimit(ServerPlayer player, String team) {
        if (!(player.level() instanceof ServerLevel level)) return null;
        List<BastionData> radios = BastionManager.getInstance()
            .findCoveringRadios(level, player.blockPosition(), team);
        if (radios.isEmpty()) {
            return "§c必须在己方 Radio 作用范围内部署兵站。";
        }
        BastionData radio = radios.get(0);
        int max = getMaxHabsPerRadio(player);
        int count = countHabsCoveredBy(radio, team);
        if (count >= max) {
            return "§c该 Radio 范围内兵站已达上限 (" + count + "/" + max + ")。";
        }
        return null;
    }

    private static int getMaxHabsPerRadio(ServerPlayer player) {
        String factionId = org.espetro.team.ClassCountManager.getInstance()
            .getPlayerFaction(player.getUUID());
        if (factionId == null) return 2;
        var loader = org.espetro.team.FactionDataProvider.getOrCreateLoader();
        var faction = loader.getFaction(factionId);
        if (faction == null) return 2;
        return Math.max(0, faction.maxHabsPerRadio);
    }

    private static int countHabsCoveredBy(BastionData radio, String team) {
        if (radio == null) return 0;
        int n = 0;
        for (BastionData b : BastionManager.getInstance().getAllBastions()) {
            if (!b.isActive() || !b.isHab() || !team.equals(b.getTeam())) continue;
            if (BastionManager.getInstance().isCoveredByFriendlyRadio(b)
                && isWithinRadioBuildRadius(radio, b.getPosition())) {
                n++;
            }
        }
        return n;
    }

    private static boolean isWithinRadioBuildRadius(BastionData radio, BlockPos pos) {
        if (radio == null || pos == null) return false;
        double r = LogisticsConfig.get().radioBuildRadius;
        double dx = radio.getPosition().getX() - pos.getX();
        double dy = radio.getPosition().getY() - pos.getY();
        double dz = radio.getPosition().getZ() - pos.getZ();
        return dx * dx + dy * dy + dz * dz <= r * r;
    }

    /** 轮盘「载具信息」：打开本队载具状态/冷却面板（只读）。 */
    public static void openVehicleDeploy(ServerPlayer player) {
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
