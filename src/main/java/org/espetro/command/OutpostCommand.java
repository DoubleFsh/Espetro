package org.espetro.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import org.espetro.team.OutpostManager;
import org.espetro.Espetro;

/**
 * 前哨基地命令
 * /outpost deploy <index> - 传送到指定前哨基地
 * /outpost list - 列出所有可用前哨基地
 * /outpost redeploy - 布防期内不扣兵力地重新部署
 */
public class OutpostCommand {

    public static LiteralArgumentBuilder<CommandSourceStack> register() {
        return Commands.literal("outpost")
            .then(Commands.literal("deploy")
                .then(Commands.argument("index", IntegerArgumentType.integer(1))
                    .executes(ctx -> deployOutpost(
                        ctx.getSource().getPlayer(),
                        IntegerArgumentType.getInteger(ctx, "index") - 1
                    ))
                )
            )
            .then(Commands.literal("list")
                .executes(ctx -> listOutposts(ctx.getSource().getPlayer()))
            )
            .then(Commands.literal("redeploy")
                .executes(ctx -> startRedeploy(ctx.getSource().getPlayer()))
            );
    }

    private static int deployOutpost(ServerPlayer player, int index) {
        if (player == null) return 0;

        String error = OutpostManager.getInstance().tryDeploy(player, index);
        if (error != null) {
            player.sendSystemMessage(Component.literal(error));
            return 0;
        }
        org.espetro.team.GameStateManager.getInstance().onMidGameDeployComplete(player);
        return 1;
    }

    private static int startRedeploy(ServerPlayer player) {
        if (player == null) return 0;

        String error = OutpostManager.getInstance().tryStartRedeploy(player);
        if (error != null) {
            player.sendSystemMessage(Component.literal(error));
            return 0;
        }
        return 1;
    }

    private static int listOutposts(ServerPlayer player) {
        if (player == null) return 0;

        if (!"DEFEND".equals(Espetro.getPlayerTeam(player))) {
            player.sendSystemMessage(Component.literal("§c只有防守方可以查看前哨基地！"));
            return 0;
        }

        var outposts = OutpostManager.getInstance().getOutposts();
        if (outposts.isEmpty()) {
            player.sendSystemMessage(Component.literal("§7当前没有配置前哨基地"));
            return 0;
        }

        if (!OutpostManager.getInstance().isAvailable()) {
            player.sendSystemMessage(Component.literal("§c前哨基地当前不可用（仅部署阶段可用）"));
            return 0;
        }

        player.sendSystemMessage(Component.literal("§6=== 可用前哨基地 ==="));
        for (int i = 0; i < outposts.size(); i++) {
            var op = outposts.get(i);
            player.sendSystemMessage(Component.literal(
                "§e" + (i + 1) + ". §f" + op.name + " §7(" + op.getPosString() + ")"));
        }
        return 1;
    }
}
