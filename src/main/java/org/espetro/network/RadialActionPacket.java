package org.espetro.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import org.espetro.Espetro;
import org.espetro.bastion.BastionData;
import org.espetro.bastion.BastionManager;
import org.espetro.bastion.DeployActions;
import org.espetro.logistics.LogisticsConfig;
import org.espetro.team.TeamPackManager;

import java.util.function.Supplier;

public record RadialActionPacket(Action action) {

    public enum Action {
        DEPLOY_RADIO,
        DEPLOY_RALLY,
        FOB_STATUS,
        /** 在己方 Radio 建造半径内部署 HAB；追加在末尾以保持旧 ordinal。 */
        DEPLOY_HAB,
        /** 打开载具部署面板（原载具部署木棍功能）。 */
        DEPLOY_VEHICLE
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
            case DEPLOY_RADIO -> DeployActions.giveRadioItem(player);
            case DEPLOY_HAB -> DeployActions.startHabChannel(player);
            case DEPLOY_VEHICLE -> DeployActions.openVehicleDeploy(player);
            case DEPLOY_RALLY -> deployRally(player);
            case FOB_STATUS -> showStatus(player);
        }
    }

    private static void deployRally(ServerPlayer player) {
        // 改为发放 Rally 部署包（信标物品，限 1 个），玩家自行放置。
        String error = TeamPackManager.getInstance().giveRallyItem(player);
        player.sendSystemMessage(Component.literal(error != null
            ? error
            : "§a已领取 Rally 部署包，找到合适位置放置。"));
    }

    private static void showStatus(ServerPlayer player) {
        BastionData bastion = nearestFob(player);
        if (bastion == null) {
            player.sendSystemMessage(Component.literal("§c附近没有己方 Radio。"));
            return;
        }
        player.sendSystemMessage(Component.literal(
            "§6[Radio] §f" + bastion.getName() + " §7| 建材 §6"
                + bastion.getConstructionSupplies() + " §7| 弹药 §b"
                + bastion.getAmmunitionSupplies() + " §7| "
                + BastionManager.getInstance().getFobStatus(bastion)));
    }

    private static BastionData nearestFob(ServerPlayer player) {
        String team = Espetro.getPlayerTeam(player);
        return BastionManager.getInstance().findNearestRadio(
            player.serverLevel(), player.blockPosition(), team,
            LogisticsConfig.get().depositRadius);
    }
}
