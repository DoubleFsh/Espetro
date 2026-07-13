package org.espetro.script;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.espetro.Espetro;

import javax.annotation.Nullable;
import java.util.UUID;

public class CommanderScriptEvent {
    private final String skillId;
    private final ServerPlayer commander;
    private final String team;
    private final ServerLevel level;
    private final double x;
    private final double y;
    private final double z;
    private final BlockPos blockPos;
    private final boolean hasTarget;

    public CommanderScriptEvent(String skillId, ServerPlayer commander, String team) {
        this(skillId, commander, team, commander.serverLevel(), commander.getX(), commander.getY(), commander.getZ(),
            commander.blockPosition(), false);
    }

    public CommanderScriptEvent(String skillId, ServerPlayer commander, String team,
                                ServerLevel level, double x, double y, double z,
                                BlockPos blockPos, boolean hasTarget) {
        this.skillId = skillId;
        this.commander = commander;
        this.team = team;
        this.level = level;
        this.x = x;
        this.y = y;
        this.z = z;
        this.blockPos = blockPos;
        this.hasTarget = hasTarget;
    }

    public String getSkillId() {
        return skillId;
    }

    public String skillId() {
        return skillId;
    }

    public ServerPlayer getCommander() {
        return commander;
    }

    public ServerPlayer commander() {
        return commander;
    }

    public UUID getCommanderId() {
        return commander.getUUID();
    }

    public UUID commanderId() {
        return getCommanderId();
    }

    public String getCommanderName() {
        return commander.getName().getString();
    }

    public String commanderName() {
        return getCommanderName();
    }

    public String getTeam() {
        return team;
    }

    public String team() {
        return team;
    }

    public ServerLevel getLevel() {
        return level;
    }

    public ServerLevel level() {
        return level;
    }

    public MinecraftServer getServer() {
        return level.getServer();
    }

    public MinecraftServer server() {
        return getServer();
    }

    public String getDimensionId() {
        return level.dimension().location().toString();
    }

    public String dimensionId() {
        return getDimensionId();
    }

    public boolean hasTarget() {
        return hasTarget;
    }

    public double getX() {
        return x;
    }

    public double x() {
        return x;
    }

    public double getY() {
        return y;
    }

    public double y() {
        return y;
    }

    public double getZ() {
        return z;
    }

    public double z() {
        return z;
    }

    public BlockPos getBlockPos() {
        return blockPos;
    }

    public BlockPos blockPos() {
        return blockPos;
    }

    public int getBlockX() {
        return blockPos.getX();
    }

    public int blockX() {
        return getBlockX();
    }

    public int getBlockY() {
        return blockPos.getY();
    }

    public int blockY() {
        return getBlockY();
    }

    public int getBlockZ() {
        return blockPos.getZ();
    }

    public int blockZ() {
        return getBlockZ();
    }

    public void tell(String message) {
        Espetro.sendToPlayer(commander, message);
    }

    public void broadcastTeam(String message) {
        if (team != null) {
            Espetro.broadcastToTeam(team, message);
        }
    }

    public void broadcastAll(String message) {
        Espetro.broadcastToAll(message);
    }

    @Nullable
    public ServerPlayer getOnlineCommander() {
        MinecraftServer server = Espetro.getServer();
        return server == null ? null : server.getPlayerList().getPlayer(commander.getUUID());
    }
}
