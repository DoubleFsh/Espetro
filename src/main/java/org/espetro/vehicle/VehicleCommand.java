package org.espetro.vehicle;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.server.level.ServerPlayer;
import org.espetro.Espetro;
import org.espetro.team.ClassCountManager;
import org.espetro.team.VoteManager;

import java.util.List;

/**
 * 载具部署命令
 * /vehicle spawn <type>  - 部署载具
 * /vehicle list          - 查看载具状态
 */
public class VehicleCommand {

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
            Commands.literal("vehicle")
                .requires(source -> source.hasPermission(0)) // 非作弊玩家也可以用
                .then(Commands.literal("spawn")
                    .then(Commands.argument("type", StringArgumentType.word())
                        .suggests((context, builder) -> {
                            VehicleConfig.getAllVehicleTypeKeys().forEach(builder::suggest);
                            return builder.buildFuture();
                        })
                        .executes(VehicleCommand::spawnVehicle)
                    )
                )
                .then(Commands.literal("list")
                    .executes(VehicleCommand::listVehicles)
                )
        );
    }

    /**
     * 部署载具
     */
    private static int spawnVehicle(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        if (!(source.getEntity() instanceof ServerPlayer player)) {
            source.sendFailure(Component.literal("§c只有玩家可以执行此命令！"));
            return 0;
        }

        // 检查是否是指挥官
        if (!VoteManager.getInstance().isCommander(player.getUUID())) {
            source.sendFailure(Component.literal("§c只有指挥官可以部署载具！"));
            return 0;
        }

        String vehicleType = StringArgumentType.getString(ctx, "type");
        String result = VehicleManager.getInstance().deployVehicle(player, vehicleType);

        if (result != null) {
            source.sendFailure(Component.literal(result));
            return 0;
        }

        return 1;
    }

    /**
     * 列出当前编制的载具状态
     */
    private static int listVehicles(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        if (!(source.getEntity() instanceof ServerPlayer player)) {
            source.sendFailure(Component.literal("§c只有玩家可以执行此命令！"));
            return 0;
        }

        boolean isCommander = VoteManager.getInstance().isCommander(player.getUUID());
        String factionId = ClassCountManager.getInstance().getPlayerFaction(player.getUUID());

        if (factionId == null) {
            source.sendFailure(Component.literal("§c你没有选择编制！"));
            return 0;
        }

        source.sendSystemMessage(Component.literal("§6═══ 载具状态 ═══").withStyle(Style.EMPTY.withColor(TextColor.fromRgb(0xFFAA00)).withBold(true)));
        source.sendSystemMessage(Component.literal("§7编制: " + factionId));

        List<String> status = VehicleManager.getInstance().getFactionVehicleStatus(factionId);
        if (status.isEmpty()) {
            source.sendSystemMessage(Component.literal("§7当前编制没有可部署的载具。"));
        } else {
            for (String line : status) {
                source.sendSystemMessage(Component.literal("  " + line));
            }
        }

        if (isCommander) {
            source.sendSystemMessage(Component.literal("§7使用 §e/vehicle spawn <类型> §7或 §e载具部署指挥书 §7部署载具。"));
        }

        return 1;
    }
}
