package org.espetro.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraftforge.network.NetworkEvent;
import org.espetro.Espetro;
import org.espetro.bastion.BastionBuildingWandItem;
import org.espetro.bastion.BastionData;
import org.espetro.bastion.BastionManager;
import org.espetro.logistics.LogisticsConfig;
import org.espetro.logistics.SupplyManager;
import org.espetro.team.TeamPackManager;

import java.util.function.Supplier;

public record RadialActionPacket(Action action) {

    public enum Action {
        DEPLOY_RADIO,
        DEPLOY_RALLY,
        DEPOSIT_SUPPLIES,
        FOB_STATUS
    }

    public static void write(RadialActionPacket packet, FriendlyByteBuf buffer) {
        buffer.writeEnum(packet.action);
    }

    public static RadialActionPacket read(FriendlyByteBuf buffer) {
        return new RadialActionPacket(buffer.readEnum(Action.class));
    }

    public static void handle(RadialActionPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        ServerPlayer player = context.getSender();
        if (player != null) {
            context.enqueueWork(() -> execute(player, packet.action));
        }
        context.setPacketHandled(true);
    }

    private static void execute(ServerPlayer player, Action action) {
        switch (action) {
            case DEPLOY_RADIO -> deployRadio(player);
            case DEPLOY_RALLY -> deployRally(player);
            case DEPOSIT_SUPPLIES -> deposit(player);
            case FOB_STATUS -> showStatus(player);
        }
    }

    private static void deployRadio(ServerPlayer player) {
        int selected = player.getInventory().selected;
        for (int slot = 0; slot < player.getInventory().items.size(); slot++) {
            ItemStack stack = player.getInventory().items.get(slot);
            if (!(stack.getItem() instanceof BastionBuildingWandItem wand)) {
                continue;
            }
            try {
                player.getInventory().selected = slot;
                wand.use(player.serverLevel(), player, InteractionHand.MAIN_HAND);
            } finally {
                player.getInventory().selected = selected;
            }
            return;
        }
        player.sendSystemMessage(Component.literal("§c背包中没有 Radio 建筑指令。"));
    }

    private static void deployRally(ServerPlayer player) {
        // 仅 Alt 轮盘 + 小队长权限；不检查/消耗背包 Rally 物品。
        HitResult hit = player.pick(8.0, 0.0f, false);
        BlockPos pos = hit instanceof BlockHitResult blockHit
            ? blockHit.getBlockPos().relative(blockHit.getDirection())
            : player.blockPosition();
        BlockState previous = player.serverLevel().getBlockState(pos);
        if (!previous.canBeReplaced()) {
            player.sendSystemMessage(Component.literal("§c目标位置无法部署 Rally。"));
            return;
        }
        // 先做条件检查再放方块，避免无效放置。
        String precheck = TeamPackManager.getInstance().canPlaceTeamPack(player, player.serverLevel(), pos);
        if (precheck != null) {
            player.sendSystemMessage(Component.literal(precheck));
            return;
        }
        player.serverLevel().setBlock(pos, Blocks.BEACON.defaultBlockState(), 3);
        String error = TeamPackManager.getInstance().placeTeamPack(player, player.serverLevel(), pos);
        if (error != null) {
            player.serverLevel().setBlock(pos, previous, 3);
            player.sendSystemMessage(Component.literal(error));
        }
    }

    private static void deposit(ServerPlayer player) {
        BastionData bastion = nearestFob(player);
        if (bastion == null) {
            player.sendSystemMessage(Component.literal("§c附近没有己方 Radio。"));
            return;
        }
        SupplyManager.DepositResult result = SupplyManager.getInstance().depositAll(player, bastion);
        player.sendSystemMessage(Component.literal(result.success()
            ? "§a已存入 §6" + result.construction() + " 建材 §a与 §b"
                + result.ammunition() + " 弹药。"
            : result.error()));
    }

    private static void showStatus(ServerPlayer player) {
        BastionData bastion = nearestFob(player);
        if (bastion == null) {
            player.sendSystemMessage(Component.literal("§c附近没有己方 Radio。"));
            return;
        }
        player.sendSystemMessage(Component.literal(
            "§6[FOB] §f" + bastion.getName() + " §7| 建材 §6"
                + bastion.getConstructionSupplies() + " §7| 弹药 §b"
                + bastion.getAmmunitionSupplies() + " §7| "
                + BastionManager.getInstance().getFobStatus(bastion)));
    }

    private static BastionData nearestFob(ServerPlayer player) {
        String team = Espetro.getPlayerTeam(player);
        return BastionManager.getInstance().findNearestBastion(
            player.serverLevel(), player.blockPosition(), team,
            LogisticsConfig.get().depositRadius);
    }
}
