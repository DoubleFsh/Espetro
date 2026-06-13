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

    public ClassSelectPacket(String teamOrFaction, String classId) {
        this.teamOrFaction = teamOrFaction;
        this.classId = classId;
    }

    public static ClassSelectPacket read(FriendlyByteBuf buf) {
        String teamOrFaction = buf.readUtf();
        String classId = buf.readUtf();
        return new ClassSelectPacket(teamOrFaction, classId);
    }

    public void write(FriendlyByteBuf buf) {
        buf.writeUtf(teamOrFaction);
        buf.writeUtf(classId);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null) return;

            // 检查是否在编制选择阶段
            if (ClassSelectManager.getInstance().isSelectingActive()) {
                // 如果是指挥官，进行编制选择
                if (VoteManager.getInstance().isCommander(player.getUUID())) {
                    boolean success = ClassSelectManager.getInstance().selectClass(player, classId);
                    if (success) {
                        Espetro.LOGGER.info("指挥官 {} 选择了编制 {}", player.getName().getString(), classId);
                    }
                }
                return;
            }

            // 否则作为职业选择处理（在战斗/部署阶段）
            ClassCountManager countManager = ClassCountManager.getInstance();

            // 检查是否选择了同一个职业
            String currentClass = countManager.getPlayerClass(player.getUUID());
            if (classId.equals(currentClass)) {
                return;
            }

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

            if (!inRange) {
                player.sendSystemMessage(net.minecraft.network.chat.Component.literal("§c你不在部署点或兵站周边6格范围内！无法选择职业。"));
                return;
            }

            // 检查职业是否已满
            if (!countManager.selectClass(player, classId)) {
                ClassCountSyncPacket errorPacket = new ClassCountSyncPacket("§c该职业人数已满！请选择其他职业。", true);
                NetworkManager.NET.send(PacketDistributor.PLAYER.with(() -> player), errorPacket);
                return;
            }

            // 职业未满，给予装备
            ClassEquipment.equipPlayer(player, teamOrFaction, classId);

            // 如果是指挥官，给予兵站建筑指令和载具部署木棍（若背包中没有）
            if (VoteManager.getInstance().isCommander(player.getUUID())) {
                giveBastionWandIfNeeded(player);
                giveVehicleDeployStickIfNeeded(player);
            }

            // 同步人数给玩家
            String team = countManager.getEffectivePlayerTeam(player.getUUID());
            ClassCountSyncPacket syncPacket = new ClassCountSyncPacket(
                countManager.getCountsForFaction(team, teamOrFaction), teamOrFaction);
            NetworkManager.NET.send(PacketDistributor.PLAYER.with(() -> player), syncPacket);
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
