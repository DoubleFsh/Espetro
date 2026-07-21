package org.espetro.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;
import org.espetro.Espetro;
import org.espetro.bastion.BastionData;
import org.espetro.bastion.BastionItems;
import org.espetro.bastion.BastionManager;
import org.espetro.team.ClassCountManager;
import org.espetro.team.ClassEquipment;
import org.espetro.team.ClassSelectManager;
import org.espetro.team.GameStateManager;
import org.espetro.team.OutpostManager;
import org.espetro.team.SpawnPointConfig;
import org.espetro.team.VoteManager;
import org.espetro.vehicle.VehicleItems;

import java.util.function.Supplier;

/**
 * 编制/职业选择数据包
 * 在部署阶段用于指挥官选择编制，战斗阶段用于玩家选择职业
 */
public class ClassSelectPacket {

    private final String teamOrFaction; // ATTACK/DEFEND 或 factionId
    private final String classId;
    private final String variantId;

    public ClassSelectPacket(String teamOrFaction, String classId) {
        this(teamOrFaction, classId, "");
    }

    public ClassSelectPacket(String teamOrFaction, String classId, String variantId) {
        this.teamOrFaction = teamOrFaction;
        this.classId = classId;
        this.variantId = variantId != null ? variantId : "";
    }

    public static ClassSelectPacket read(FriendlyByteBuf buf) {
        String teamOrFaction = buf.readUtf();
        String classId = buf.readUtf();
        String variantId = buf.readUtf();
        return new ClassSelectPacket(teamOrFaction, classId, variantId);
    }

    public void write(FriendlyByteBuf buf) {
        buf.writeUtf(teamOrFaction);
        buf.writeUtf(classId);
        buf.writeUtf(variantId);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null) return;

            // 检查是否在编制选择阶段
            if (ClassSelectManager.getInstance().isSelectingActive()) {
                // 当前选择阵营的所有玩家均可投票，服务端负责验证队伍和候选池。
                boolean success = ClassSelectManager.getInstance().selectClass(player, classId);
                if (success) {
                    Espetro.LOGGER.info("玩家 {} 投票编制 {}", player.getName().getString(), classId);
                }
                return;
            }

            // 否则作为职业选择处理（在战斗/部署阶段）
            ClassCountManager countManager = ClassCountManager.getInstance();

            String currentClass = countManager.getPlayerClass(player.getUUID());
            String currentVariant = countManager.getPlayerVariant(player.getUUID());

            // 检查是否在部署点或兵站周边6格范围内
            BlockPos playerPos = player.blockPosition();
            boolean inRange = false;

            // 1) 检查玩家已保存的原部署点（传送/死亡时保存的）
            BastionManager.DeployPoint deployPoint = BastionManager.getInstance().getPlayerDeployPoint(player.getUUID());
            if (deployPoint != null && playerPos.closerThan(deployPoint.pos, 6)) {
                inRange = true;
            }

            // 2) 始终检查 SpawnPointConfig 中该队伍当前配置的部署点
            //    （覆盖 /espetro spawnpoint here 重新设置后旧记录不同步的情况）
            if (!inRange) {
                String team = countManager.getEffectivePlayerTeam(player.getUUID());
                if (team != null) {
                    SpawnPointConfig.SpawnPoint spawn = SpawnPointConfig.getSpawnPoint(team);
                    if (spawn != null) {
                        BlockPos teamSpawnPos = new BlockPos((int) spawn.x, (int) spawn.y, (int) spawn.z);
                        if (playerPos.closerThan(teamSpawnPos, 6)) {
                            inRange = true;
                        }
                    }
                }
            }

            // 3) 检查兵站周边
            if (!inRange) {
                String team = countManager.getEffectivePlayerTeam(player.getUUID());
                for (BastionData bastion : BastionManager.getInstance().getAllBastions()) {
                    BlockPos bastionPos = BastionManager.getInstance().getRecordedArmorStandPosition(bastion);
                    if (team != null
                        && team.equals(bastion.getTeam())
                        && bastion.isActive()
                        && bastionPos != null
                        && playerPos.closerThan(bastionPos, 6)) {
                        inRange = true;
                        break;
                    }
                }
            }

            // 4) 布防期内，防守方在前哨基地周边也可选择职业
            if (!inRange && OutpostManager.getInstance().isPlayerNearAvailableOutpost(player, 6)) {
                inRange = true;
            }

            if (!inRange) {
                player.sendSystemMessage(net.minecraft.network.chat.Component.literal("§c你不在部署点、兵站或可用前哨基地周边6格范围内！无法选择职业。"));
                return;
            }

            ClassCountManager.SelectionResult selection =
                countManager.selectClassVariant(player, classId, variantId);
            if (selection != ClassCountManager.SelectionResult.SUCCESS) {
                String message = switch (selection) {
                    case CLASS_FULL -> "§c该职业人数已满！请选择其他职业。";
                    case VARIANT_FULL -> "§c该装备变体人数已满！请选择其他变体。";
                    case SQUAD_CLASS_FULL -> "§c本小队该职业人数已满！请选择其他职业或小队。";
                    case REQUIRES_SQUAD -> "§c请先加入班组小队后再选择职业！";
                    case INVALID_VARIANT -> "§c无效的职业装备变体。";
                    case INVALID_CLASS -> "§c该职业不属于你当前选择的编制。";
                    default -> "§c当前无法选择该职业装备变体。";
                };
                ClassCountSyncPacket errorPacket = new ClassCountSyncPacket(message, true);
                NetworkManager.NET.send(PacketDistributor.PLAYER.with(() -> player), errorPacket);
                return;
            }

            boolean sameSelection = classId.equals(currentClass)
                && countManager.getPlayerVariant(player.getUUID()).equals(currentVariant);
            String actualFactionId = countManager.getPlayerFaction(player.getUUID());
            String selectedVariantId = countManager.getPlayerVariant(player.getUUID());
            if (!sameSelection) {
                ClassEquipment.equipPlayer(player, actualFactionId, classId, selectedVariantId);
            }

            // 如果是指挥官，给予兵站建筑指令和载具部署木棍（若背包中没有）
            if (VoteManager.getInstance().isCommander(player.getUUID())) {
                giveBastionWandIfNeeded(player);
                giveVehicleDeployStickIfNeeded(player);
                org.espetro.tutorial.TutorialManager.getInstance()
                    .tryShow(player, org.espetro.tutorial.TutorialStep.BASTION);
                org.espetro.tutorial.TutorialManager.getInstance()
                    .tryShow(player, org.espetro.tutorial.TutorialStep.VEHICLE);
            }
            org.espetro.team.TeamPackManager.getInstance().syncTeamPackItem(player);
            org.espetro.tutorial.TutorialManager.getInstance()
                .tryShow(player, org.espetro.tutorial.TutorialStep.TEAM_PACK);

            String team = countManager.getEffectivePlayerTeam(player.getUUID());
            String factionId = countManager.getPlayerFaction(player.getUUID());
            // 不再 syncSquadsToTeam：成员 className 变化曾触发整页 rebuild 闪烁。
            // 给同队玩家发完整部署包以更新小队作用域人数；客户端 updateSquads
            // 在结构未变时不会 rebuild。
            NetworkManager.broadcastClassCounts(team,
                factionId != null ? factionId : teamOrFaction);
            NetworkManager.refreshUnifiedDeployScreensForTeam(team);
        });
        ctx.get().setPacketHandled(true);
    }

    /**
     * 若指挥官背包中没有兵站建筑指令，给予一个
     */
    private static void giveBastionWandIfNeeded(ServerPlayer player) {
        if (BastionItems.BASTION_BUILDING_WAND == null) return;

        for (ItemStack stack : player.getInventory().items) {
            if (stack.getItem() == BastionItems.BASTION_BUILDING_WAND) return;
        }

        ItemStack wand = new ItemStack(BastionItems.BASTION_BUILDING_WAND);
        player.getInventory().add(wand);
        player.sendSystemMessage(net.minecraft.network.chat.Component.literal("§6你获得了 §e兵站建筑指令 §6！右键使用消耗木板建造兵站。"));
    }

    /**
     * 若指挥官背包中没有载具部署木棍，给予一个
     */
    private static void giveVehicleDeployStickIfNeeded(ServerPlayer player) {
        if (VehicleItems.VEHICLE_DEPLOY_STICK == null) return;

        // 检查是否已有
        for (ItemStack stack : player.getInventory().items) {
            if (stack.getItem() == VehicleItems.VEHICLE_DEPLOY_STICK) return;
        }

        ItemStack stick = new ItemStack(VehicleItems.VEHICLE_DEPLOY_STICK);
        stick.setHoverName(net.minecraft.network.chat.Component.literal("§e载具部署指令"));
        player.getInventory().add(stick);
        player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
            "§6你获得了 §e载具部署指令 §6！右键使用发送可点击部署信息。§7（/vehicle list 查看状态）"));
    }
}
