package org.espetro.team;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.Team;

/**
 * 队伍管理器
 * 使用原版 Minecraft 队伍机制
 */
public class TeamManager {

    public static final String ATTACK_TEAM_ID = "espetro_attack";
    public static final String DEFEND_TEAM_ID = "espetro_defend";

    public static final String ATTACK_DISPLAY_NAME = "§c进攻方";
    public static final String DEFEND_DISPLAY_NAME = "§9防守方";

    /**
     * 初始化队伍
     */
    public static void initTeams(MinecraftServer server) {
        Scoreboard scoreboard = server.getScoreboard();

        // 创建进攻队伍
        PlayerTeam attackTeam = scoreboard.getPlayerTeam(ATTACK_TEAM_ID);
        if (attackTeam == null) {
            attackTeam = scoreboard.addPlayerTeam(ATTACK_TEAM_ID);
            attackTeam.setColor(ChatFormatting.RED);
            attackTeam.setDisplayName(Component.literal(ATTACK_DISPLAY_NAME));
        }
        attackTeam.setNameTagVisibility(Team.Visibility.NEVER);

        // 创建防守队伍
        PlayerTeam defendTeam = scoreboard.getPlayerTeam(DEFEND_TEAM_ID);
        if (defendTeam == null) {
            defendTeam = scoreboard.addPlayerTeam(DEFEND_TEAM_ID);
            defendTeam.setColor(ChatFormatting.BLUE);
            defendTeam.setDisplayName(Component.literal(DEFEND_DISPLAY_NAME));
        }
        defendTeam.setNameTagVisibility(Team.Visibility.NEVER);
    }

    /**
     * 将玩家加入进攻队伍
     */
    public static void joinAttackTeam(MinecraftServer server, String playerName) {
        Scoreboard scoreboard = server.getScoreboard();
        removeFromAllTeams(scoreboard, playerName);
        PlayerTeam team = scoreboard.getPlayerTeam(ATTACK_TEAM_ID);
        if (team != null) {
            scoreboard.addPlayerToTeam(playerName, team);
        }
    }

    /**
     * 将玩家加入防守队伍
     */
    public static void joinDefendTeam(MinecraftServer server, String playerName) {
        Scoreboard scoreboard = server.getScoreboard();
        removeFromAllTeams(scoreboard, playerName);
        PlayerTeam team = scoreboard.getPlayerTeam(DEFEND_TEAM_ID);
        if (team != null) {
            scoreboard.addPlayerToTeam(playerName, team);
        }
    }

    /**
     * 将玩家从所有队伍移除
     */
    public static void removeFromAllTeams(Scoreboard scoreboard, String playerName) {
        scoreboard.removePlayerFromTeam(playerName);
    }

    /**
     * 检查玩家是否在进攻队伍
     */
    public static boolean isInAttackTeam(MinecraftServer server, String playerName) {
        Scoreboard scoreboard = server.getScoreboard();
        PlayerTeam team = scoreboard.getPlayerTeam(ATTACK_TEAM_ID);
        return team != null && team.getPlayers().contains(playerName);
    }

    /**
     * 检查玩家是否在防守队伍
     */
    public static boolean isInDefendTeam(MinecraftServer server, String playerName) {
        Scoreboard scoreboard = server.getScoreboard();
        PlayerTeam team = scoreboard.getPlayerTeam(DEFEND_TEAM_ID);
        return team != null && team.getPlayers().contains(playerName);
    }

    /**
     * 获取玩家当前队伍类型
     * @return "ATTACK", "DEFEND", 或 null
     */
    public static String getPlayerTeam(MinecraftServer server, String playerName) {
        if (isInAttackTeam(server, playerName)) return "ATTACK";
        if (isInDefendTeam(server, playerName)) return "DEFEND";
        return null;
    }

    /**
     * 获取队伍人数
     */
    public static int getTeamSize(MinecraftServer server, String teamId) {
        Scoreboard scoreboard = server.getScoreboard();
        PlayerTeam team = scoreboard.getPlayerTeam(teamId);
        return team != null ? team.getPlayers().size() : 0;
    }
}
