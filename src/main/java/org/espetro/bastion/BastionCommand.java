package org.espetro.bastion;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.chat.Component;
import org.espetro.Espetro;
import org.espetro.network.BastionSelectionPacket;
import org.espetro.team.ClassCountManager;
import org.espetro.team.GameStateManager;

import java.util.UUID;

/**
 * 兵站相关命令
 */
public class BastionCommand {

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        // /bastion 命令组
        dispatcher.register(Commands.literal("bastion")
            .requires(source -> source.hasPermission(0)) // 所有玩家可用
            .then(Commands.literal("select")
                .requires(source -> source.hasPermission(0))
                .then(Commands.argument("bastionId", StringArgumentType.string())
                    .executes(context -> {
                        ServerPlayer player = context.getSource().getPlayer();
                        if (player == null) return 0;

                        String bastionIdStr = StringArgumentType.getString(context, "bastionId");
                        try {
                            UUID bastionId = UUID.fromString(bastionIdStr);
                            return selectBastion(player, bastionId);
                        } catch (IllegalArgumentException e) {
                            player.sendSystemMessage(Component.literal("§c无效的兵站ID！"));
                            return 0;
                        }
                    })
                )
            )
            .then(Commands.literal("deploy")
                .requires(source -> source.hasPermission(0))
                .executes(context -> {
                    ServerPlayer player = context.getSource().getPlayer();
                    if (player == null) return 0;
                    return respawnAtDeployPoint(player);
                })
            )
            .then(Commands.literal("list")
                .requires(source -> source.hasPermission(0))
                .executes(context -> {
                    ServerPlayer player = context.getSource().getPlayer();
                    if (player == null) return 0;
                    return listBastions(player);
                })
            )
            .then(Commands.literal("help")
                .executes(context -> {
                    ServerPlayer player = context.getSource().getPlayer();
                    if (player == null) return 0;
                    player.sendSystemMessage(Component.literal("§6=== 兵站命令帮助 ==="));
                    player.sendSystemMessage(Component.literal("§e/bastion list §7- 查看可用兵站列表"));
                    player.sendSystemMessage(Component.literal("§e/bastion select <id> §7- 在指定兵站复活"));
                    player.sendSystemMessage(Component.literal("§e/bastion deploy §7- 在原部署点复活"));
                    player.sendSystemMessage(Component.literal("§7(点击聊天中的链接可直接选择)"));
                    return 1;
                })
            )
        );
    }

    /**
     * 选择兵站复活
     */
    private static int selectBastion(ServerPlayer player, UUID bastionId) {
        // 检查是否在对战阶段或部署阶段（战局中加入允许部署阶段）
        org.espetro.team.GamePhase phase = GameStateManager.getInstance().getCurrentPhase();
        if (phase != org.espetro.team.GamePhase.BATTLE
            && phase != org.espetro.team.GamePhase.DEPLOYING) {
            player.sendSystemMessage(Component.literal("§c只能在战斗或部署阶段使用此命令！"));
            return 0;
        }

        // 检查玩家是否选择了阵营
        String factionId = ClassCountManager.getInstance().getPlayerFaction(player.getUUID());
        if (factionId == null) {
            player.sendSystemMessage(Component.literal("§c请先选择阵营！"));
            return 0;
        }

        // 执行兵站选择
        boolean success = BastionSelectionPacket.handleBastionSelect(player, bastionId);
        if (success) {
            // 战局中加入：部署完成后触发职业选择
            onDeployComplete(player);
            return 1;
        }
        return 0;
    }

    /**
     * 在原部署点复活
     */
    private static int respawnAtDeployPoint(ServerPlayer player) {
        // 检查是否在对战阶段或部署阶段（战局中加入允许部署阶段）
        org.espetro.team.GamePhase phase = GameStateManager.getInstance().getCurrentPhase();
        if (phase != org.espetro.team.GamePhase.BATTLE
            && phase != org.espetro.team.GamePhase.DEPLOYING) {
            player.sendSystemMessage(Component.literal("§c只能在战斗或部署阶段使用此命令！"));
            return 0;
        }

        // 检查玩家是否选择了阵营
        String factionId = ClassCountManager.getInstance().getPlayerFaction(player.getUUID());
        if (factionId == null) {
            player.sendSystemMessage(Component.literal("§c请先选择阵营！"));
            return 0;
        }

        // 检查是否有原部署点记录
        if (!BastionManager.getInstance().isWaitingForBastion(player.getUUID())) {
            player.sendSystemMessage(Component.literal("§c你不在等待复活状态！"));
            return 0;
        }
        BastionManager.DeployPoint deployPoint = BastionManager.getInstance().getPlayerDeployPoint(player.getUUID());
        if (deployPoint == null) {
            player.sendSystemMessage(Component.literal("§c无法找到原部署点！"));
            return 0;
        }

        // 执行原部署点复活
        if (BastionManager.getInstance().respawnAtDeployPoint(player.server.overworld(), player)) {
            // 战局中加入：部署完成后触发职业选择
            onDeployComplete(player);
            return 1;
        }
        return 0;
    }

    /**
     * 战局中加入：部署点选择完成后触发职业选择
     */
    private static void onDeployComplete(ServerPlayer player) {
        org.espetro.team.GameStateManager.getInstance().onMidGameDeployComplete(player);
    }

    /**
     * 列出可用兵站
     */
    private static int listBastions(ServerPlayer player) {
        // 检查玩家是否选择了阵营
        String factionId = ClassCountManager.getInstance().getPlayerFaction(player.getUUID());
        if (factionId == null) {
            player.sendSystemMessage(Component.literal("§c请先选择阵营！"));
            return 0;
        }

        String team = Espetro.getPlayerTeam(player);
        if (team == null) {
            player.sendSystemMessage(Component.literal("§c无法确定你的队伍！"));
            return 0;
        }
        var bastions = BastionManager.getInstance().getTeamBastions(team);

        if (bastions.isEmpty()) {
            player.sendSystemMessage(Component.literal("§c当前没有可用的兵站！"));
            return 0;
        }

        player.sendSystemMessage(Component.literal("§6=== 可用兵站列表 ==="));
        for (BastionData bastion : bastions) {
            BastionManager manager = BastionManager.getInstance();
            var pos = manager.getRecordedArmorStandPosition(bastion);
            if (pos == null) {
                continue;
            }
            Component clickable = Component.literal("§a- §e" + bastion.getName() + " §7(" + pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + ")")
                .withStyle(style -> style
                    .withClickEvent(new net.minecraft.network.chat.ClickEvent(
                        net.minecraft.network.chat.ClickEvent.Action.SUGGEST_COMMAND,
                        "/bastion select " + bastion.getBastionId().toString()
                    ))
                    .withHoverEvent(new net.minecraft.network.chat.HoverEvent(
                        net.minecraft.network.chat.HoverEvent.Action.SHOW_TEXT,
                        Component.literal("§e点击选择此兵站")
                    ))
                );
            player.sendSystemMessage(clickable);
        }

        return bastions.size();
    }
}
