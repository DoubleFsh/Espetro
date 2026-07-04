package org.espetro.team;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import org.espetro.Espetro;
import org.espetro.config.GameConfig;
import org.espetro.network.NetworkManager;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class CommanderSkillManager {

    private static CommanderSkillManager INSTANCE;

    private final Map<UUID, Map<CommanderSkillType, Long>> cooldownEndTicks = new HashMap<>();

    private CommanderSkillManager() {
        INSTANCE = this;
    }

    public static CommanderSkillManager getInstance() {
        if (INSTANCE == null) {
            INSTANCE = new CommanderSkillManager();
        }
        return INSTANCE;
    }

    public static void init() {
        INSTANCE = new CommanderSkillManager();
    }

    public boolean activateSkill(ServerPlayer commander, CommanderSkillType skillType) {
        if (skillType == null) return false;

        if (!VoteManager.getInstance().isCommander(commander.getUUID())) {
            Espetro.sendToPlayer(commander, "\u00a7c你不是指挥官，无法使用技能！");
            return false;
        }

        GamePhase phase = GameStateManager.getInstance().getCurrentPhase();
        if (phase != GamePhase.BATTLE && phase != GamePhase.DEPLOYING) {
            Espetro.sendToPlayer(commander, "\u00a7c当前阶段无法使用指挥官技能！");
            return false;
        }

        if (isOnCooldown(commander.getUUID(), skillType)) {
            int remaining = getRemainingCooldownSeconds(commander.getUUID(), skillType);
            Espetro.sendToPlayer(commander, "\u00a7c技能冷却中，剩余 " + remaining + " 秒");
            return false;
        }

        boolean success = switch (skillType) {
            case DRONE_DETECTION -> activateDroneDetection(commander);
        };

        if (success) {
            long cooldownTicks = getCooldownTicks(skillType);
            cooldownEndTicks
                .computeIfAbsent(commander.getUUID(), k -> new HashMap<>())
                .put(skillType, getServerTick() + cooldownTicks);

            NetworkManager.sendCommanderSkillSync(commander);

            String team = Espetro.getPlayerTeam(commander);
            if (team != null) {
                Espetro.broadcastToTeam(team, "\u00a76\u26a1 指挥官 " + commander.getName().getString() + " 发动了 " + skillType.getDisplayName() + "！");
            }
        }

        return success;
    }

    private boolean activateDroneDetection(ServerPlayer commander) {
        MinecraftServer server = commander.getServer();
        if (server == null) return false;

        String commanderTeam = Espetro.getPlayerTeam(commander);
        if (commanderTeam == null) return false;

        double range = GameConfig.getDroneDetectionRange();
        int durationSeconds = GameConfig.getDroneDetectionDurationSeconds();
        int durationTicks = durationSeconds * 20;

        int detectedCount = 0;
        for (ServerPlayer target : server.getPlayerList().getPlayers()) {
            if (target == commander) continue;

            String targetTeam = Espetro.getPlayerTeam(target);
            if (!commanderTeam.equals(targetTeam)) {
                if (commander.distanceTo(target) <= range) {
                    target.addEffect(new MobEffectInstance(MobEffects.GLOWING, durationTicks, 0, false, false, true));
                    detectedCount++;
                }
            }
        }

        Espetro.LOGGER.info("指挥官 {} 发动无人机侦测，检测到 {} 名敌方玩家",
            commander.getName().getString(), detectedCount);
        return true;
    }

    public boolean isOnCooldown(UUID uuid, CommanderSkillType type) {
        Map<CommanderSkillType, Long> map = cooldownEndTicks.get(uuid);
        if (map == null) return false;
        Long endTick = map.get(type);
        if (endTick == null) return false;
        return getServerTick() < endTick;
    }

    public int getRemainingCooldownSeconds(UUID uuid, CommanderSkillType type) {
        Map<CommanderSkillType, Long> map = cooldownEndTicks.get(uuid);
        if (map == null) return 0;
        Long endTick = map.get(type);
        if (endTick == null) return 0;
        long remaining = endTick - getServerTick();
        return remaining <= 0 ? 0 : (int) Math.ceil(remaining / 20.0);
    }

    public Map<String, Integer> getCooldownData(UUID uuid) {
        Map<String, Integer> data = new HashMap<>();
        for (CommanderSkillType type : CommanderSkillType.values()) {
            int remaining = getRemainingCooldownSeconds(uuid, type);
            data.put(type.getId(), remaining);
        }
        return data;
    }

    private long getCooldownTicks(CommanderSkillType type) {
        return switch (type) {
            case DRONE_DETECTION -> GameConfig.getDroneDetectionCooldownSeconds() * 20L;
        };
    }

    private long getServerTick() {
        MinecraftServer server = Espetro.getServer();
        if (server == null) return 0;
        return server.getTickCount();
    }

    public void onServerTick() {
    }

    public void reset() {
        cooldownEndTicks.clear();
    }
}