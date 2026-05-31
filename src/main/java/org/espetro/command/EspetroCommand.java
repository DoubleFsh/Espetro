package org.espetro.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.FloatArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import org.espetro.Espetro;
import org.espetro.team.ClassEquipment;
import org.espetro.team.ClassCountManager;
import org.espetro.team.FactionDataLoader;
import org.espetro.team.FactionDataProvider;
import org.espetro.team.GameStateManager;
import org.espetro.team.TeamManager;
import org.espetro.team.SpawnPointConfig;
import org.espetro.team.TroopCountManager;

import static org.espetro.Espetro.MOD_ID;

/**
 * Espetro指令
 */
public class EspetroCommand {

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("espetro")
            .requires(source -> source.hasPermission(2)) // 需要管理员权限
            .then(Commands.literal("reload")
                .executes(ctx -> {
                    Espetro.reloadAllConfigs();
                    ctx.getSource().sendSystemMessage(Component.literal("§a[Espetro] 所有配置已热重载！"));
                    return 1;
                })
            )
            .then(Commands.literal("reset")
                .executes(ctx -> {
                    var server = ctx.getSource().getServer();

                    // 重置职业人数
                    ClassCountManager.getInstance().resetAll();

                    // 清空所有在线玩家的装备
                    for (var player : server.getPlayerList().getPlayers()) {
                        ClassEquipment.clearEquipment(player);
                    }

                    // 重置游戏状态
                    GameStateManager.getInstance().resetGame();

                    ctx.getSource().sendSystemMessage(Component.literal("§6[Espetro] 已重置所有玩家状态和职业人数"));
                    return 1;
                })
            )
            .then(Commands.literal("start")
                .executes(ctx -> {
                    var server = ctx.getSource().getServer();

                    if (GameStateManager.getInstance().isGameStarted()) {
                        ctx.getSource().sendSystemMessage(Component.literal("§c对局已经开始！"));
                        return 0;
                    }

                    // 强制开始对局
                    GameStateManager.getInstance().forceStartGame();

                    ctx.getSource().sendSystemMessage(Component.literal("§6[Espetro] 管理员强制开始对局！"));
                    return 1;
                })
            )
            .then(Commands.literal("status")
                .executes(ctx -> {
                    var server = ctx.getSource().getServer();
                    var gameState = GameStateManager.getInstance();

                    ctx.getSource().sendSystemMessage(Component.literal("§6========== 对局状态 =========="));
                    ctx.getSource().sendSystemMessage(Component.literal("§e状态: §f" + (gameState.isGameStarted() ? "§a已开始" : "§e等待中")));
                    ctx.getSource().sendSystemMessage(Component.literal("§e在线玩家: §f" + server.getPlayerCount() + " 人"));
                    ctx.getSource().sendSystemMessage(Component.literal("§e已选择职业: §f" + gameState.getReadyCount() + " 人"));
                    ctx.getSource().sendSystemMessage(Component.literal("§e等待选择: §f" + gameState.getWaitingCount() + " 人"));
                    ctx.getSource().sendSystemMessage(Component.literal("§e开始要求: §f20 人"));
                    ctx.getSource().sendSystemMessage(Component.literal("§6================================"));
                    return 1;
                })
            )
            .then(Commands.literal("teams")
                .executes(ctx -> {
                    var server = ctx.getSource().getServer();
                    int attackCount = TeamManager.getTeamSize(server, TeamManager.ATTACK_TEAM_ID);
                    int defendCount = TeamManager.getTeamSize(server, TeamManager.DEFEND_TEAM_ID);
                    ctx.getSource().sendSystemMessage(Component.literal("§6========== 队伍信息 =========="));
                    ctx.getSource().sendSystemMessage(Component.literal("§c进攻方: §f" + attackCount + " 人"));
                    ctx.getSource().sendSystemMessage(Component.literal("§9防守方: §f" + defendCount + " 人"));
                    ctx.getSource().sendSystemMessage(Component.literal("§6=============================="));
                    return 1;
                })
            )
            .then(Commands.literal("factions")
                .executes(ctx -> {
                    FactionDataLoader loader = FactionDataProvider.getOrCreateLoader();
                    ctx.getSource().sendSystemMessage(Component.literal("§6========== 可用阵营 =========="));
                    for (FactionDataLoader.FactionData faction : loader.getAllFactions()) {
                        ctx.getSource().sendSystemMessage(Component.literal(faction.icon + " §e" + faction.name + " §7- " + faction.description));
                    }
                    ctx.getSource().sendSystemMessage(Component.literal("§6================================"));
                    return 1;
                })
            )
            .then(Commands.literal("spawnpoint")
                .executes(ctx -> {
                    var gameState = GameStateManager.getInstance();
                    var spawnPoints = gameState.getAllSpawnPoints();
                    ctx.getSource().sendSystemMessage(Component.literal("§6========== 复活点配置 =========="));
                    for (var entry : spawnPoints.entrySet()) {
                        SpawnPointConfig.SpawnPoint sp = entry.getValue();
                        ctx.getSource().sendSystemMessage(Component.literal("§e" + entry.getKey() + ": §7x=" + sp.x + " y=" + sp.y + " z=" + sp.z + " yaw=" + sp.yaw));
                    }
                    ctx.getSource().sendSystemMessage(Component.literal("§6================================"));
                    return 1;
                })
                .then(Commands.literal("set")
                    .then(Commands.argument("team", StringArgumentType.string())
                        .then(Commands.argument("x", IntegerArgumentType.integer())
                            .then(Commands.argument("y", IntegerArgumentType.integer())
                                .then(Commands.argument("z", IntegerArgumentType.integer())
                                    .executes(ctx -> {
                                        String team = StringArgumentType.getString(ctx, "team").toUpperCase();
                                        if (!team.equals("ATTACK") && !team.equals("DEFEND")) {
                                            ctx.getSource().sendSystemMessage(Component.literal("§c队伍必须是 ATTACK 或 DEFEND"));
                                            return 0;
                                        }
                                        int x = IntegerArgumentType.getInteger(ctx, "x");
                                        int y = IntegerArgumentType.getInteger(ctx, "y");
                                        int z = IntegerArgumentType.getInteger(ctx, "z");
                                        float yaw = team.equals("ATTACK") ? 0f : 180f;
                                        
                                        GameStateManager.getInstance().setTeamSpawnPoint(team, x, y, z, yaw);
                                        ctx.getSource().sendSystemMessage(Component.literal("§a已设置 " + team + " 复活点: (" + x + ", " + y + ", " + z + ")"));
                                        return 1;
                                    })
                                    .then(Commands.argument("yaw", FloatArgumentType.floatArg())
                                        .executes(ctx -> {
                                            String team = StringArgumentType.getString(ctx, "team").toUpperCase();
                                            if (!team.equals("ATTACK") && !team.equals("DEFEND")) {
                                                ctx.getSource().sendSystemMessage(Component.literal("§c队伍必须是 ATTACK 或 DEFEND"));
                                                return 0;
                                            }
                                            int x = IntegerArgumentType.getInteger(ctx, "x");
                                            int y = IntegerArgumentType.getInteger(ctx, "y");
                                            int z = IntegerArgumentType.getInteger(ctx, "z");
                                            float yaw = FloatArgumentType.getFloat(ctx, "yaw");
                                            
                                            GameStateManager.getInstance().setTeamSpawnPoint(team, x, y, z, yaw);
                                            ctx.getSource().sendSystemMessage(Component.literal("§a已设置 " + team + " 复活点: (" + x + ", " + y + ", " + z + ") yaw=" + yaw));
                                            return 1;
                                        })
                                    )
                                )
                            )
                        )
                    )
                )
                .then(Commands.literal("here")
                    .then(Commands.argument("team", StringArgumentType.string())
                        .executes(ctx -> {
                            String team = StringArgumentType.getString(ctx, "team").toUpperCase();
                            if (!team.equals("ATTACK") && !team.equals("DEFEND")) {
                                ctx.getSource().sendSystemMessage(Component.literal("§c队伍必须是 ATTACK 或 DEFEND"));
                                return 0;
                            }
                            
                            if (!(ctx.getSource().getEntity() instanceof ServerPlayer player)) {
                                ctx.getSource().sendSystemMessage(Component.literal("§c此命令需要在游戏中执行"));
                                return 0;
                            }
                            
                            int x = (int) player.getX();
                            int y = (int) player.getY();
                            int z = (int) player.getZ();
                            float yaw = player.getYRot();
                            float pitch = player.getXRot();
                            
                            GameStateManager.getInstance().setTeamSpawnPoint(team, x, y, z, yaw);
                            ctx.getSource().sendSystemMessage(Component.literal("§a已设置 " + team + " 复活点为当前位置: (" + x + ", " + y + ", " + z + ") yaw=" + yaw));
                            return 1;
                        })
                    )
                )
            )
            .then(Commands.literal("setclass")
                .then(Commands.argument("faction", StringArgumentType.string())
                    .then(Commands.argument("class", StringArgumentType.string())
                        .then(Commands.argument("player", StringArgumentType.string())
                            .executes(ctx -> {
                                String factionId = StringArgumentType.getString(ctx, "faction");
                                String classId = StringArgumentType.getString(ctx, "class");
                                String playerName = StringArgumentType.getString(ctx, "player");

                                FactionDataLoader loader = FactionDataProvider.getOrCreateLoader();
                                FactionDataLoader.ClassKitData kit = loader.getClassKit(classId);

                                if (kit == null) {
                                    ctx.getSource().sendSystemMessage(Component.literal("§c无效的职业: " + classId));
                                    // 显示可用职业
                                    ctx.getSource().sendSystemMessage(Component.literal("§7可用职业: " + String.join(", ", loader.getClassIdsForFaction(factionId))));
                                    return 0;
                                }

                                var server = ctx.getSource().getServer();
                                var player = server.getPlayerList().getPlayerByName(playerName);
                                if (player == null) {
                                    ctx.getSource().sendSystemMessage(Component.literal("§c玩家不在线: " + playerName));
                                    return 0;
                                }

                                ClassEquipment.equipPlayer(player, factionId, classId);
                                ctx.getSource().sendSystemMessage(Component.literal("§a已为 " + playerName + " 装备: " + kit.name));
                                return 1;
                            })
                        )
                    )
                )
            )
            // 兵力管理命令
            .then(Commands.literal("troops")
                .executes(ctx -> {
                    // 显示当前兵力
                    int attack = TroopCountManager.getInstance().getAttackTroops();
                    int defend = TroopCountManager.getInstance().getDefendTroops();
                    ctx.getSource().sendSystemMessage(Component.literal("§6========== 当前兵力 =========="));
                    ctx.getSource().sendSystemMessage(Component.literal("§c■ 进攻方: §f" + attack));
                    ctx.getSource().sendSystemMessage(Component.literal("§9■ 防守方: §f" + defend));
                    ctx.getSource().sendSystemMessage(Component.literal("§6================================"));
                    return 1;
                })
                .then(Commands.literal("set")
                    .then(Commands.argument("team", StringArgumentType.string())
                        .then(Commands.argument("value", IntegerArgumentType.integer())
                            .executes(ctx -> {
                                String team = StringArgumentType.getString(ctx, "team").toUpperCase();
                                int value = IntegerArgumentType.getInteger(ctx, "value");
                                if (!team.equals("ATTACK") && !team.equals("DEFEND")) {
                                    ctx.getSource().sendSystemMessage(Component.literal("§c队伍必须是 ATTACK 或 DEFEND"));
                                    return 0;
                                }
                                if (value < 0) {
                                    ctx.getSource().sendSystemMessage(Component.literal("§c兵力值不能为负数"));
                                    return 0;
                                }
                                if ("ATTACK".equals(team)) {
                                    TroopCountManager.getInstance().setAttackTroops(value);
                                    Espetro.broadcastToAll("§6[管理] 攻方兵力已设置为: §c" + value);
                                } else {
                                    TroopCountManager.getInstance().setDefendTroops(value);
                                    Espetro.broadcastToAll("§6[管理] 守方兵力已设置为: §9" + value);
                                }
                                return 1;
                            })
                        )
                    )
                )
                .then(Commands.literal("add")
                    .then(Commands.argument("team", StringArgumentType.string())
                        .then(Commands.argument("value", IntegerArgumentType.integer())
                            .executes(ctx -> {
                                String team = StringArgumentType.getString(ctx, "team").toUpperCase();
                                int value = IntegerArgumentType.getInteger(ctx, "value");
                                if (!team.equals("ATTACK") && !team.equals("DEFEND")) {
                                    ctx.getSource().sendSystemMessage(Component.literal("§c队伍必须是 ATTACK 或 DEFEND"));
                                    return 0;
                                }
                                if ("ATTACK".equals(team)) {
                                    int current = TroopCountManager.getInstance().getAttackTroops();
                                    TroopCountManager.getInstance().setAttackTroops(current + value);
                                    Espetro.broadcastToAll("§6[管理] 攻方兵力增加了 " + value + " (当前: §c" + (current + value) + "§6)");
                                } else {
                                    int current = TroopCountManager.getInstance().getDefendTroops();
                                    TroopCountManager.getInstance().setDefendTroops(current + value);
                                    Espetro.broadcastToAll("§6[管理] 守方兵力增加了 " + value + " (当前: §9" + (current + value) + "§6)");
                                }
                                return 1;
                            })
                        )
                    )
                )
                .then(Commands.literal("reset")
                    .executes(ctx -> {
                        TroopCountManager.getInstance().initializeTroops();
                        ctx.getSource().sendSystemMessage(Component.literal("§a兵力已重置为初始值: 攻方280 | 守方1200"));
                        return 1;
                    })
                )
            )
        );
        
        // 简短别名
        dispatcher.register(Commands.literal(MOD_ID)
            .requires(source -> source.hasPermission(2))
            .executes(ctx -> {
                ctx.getSource().sendSystemMessage(Component.literal("§6[Espetro] 战术小队模组 v1.0"));
                ctx.getSource().sendSystemMessage(Component.literal("§7使用 /espetro factions 查看可用阵营"));
                ctx.getSource().sendSystemMessage(Component.literal("§7使用 /espetro troops 查看/管理兵力"));
                return 1;
            })
        );
    }
}
