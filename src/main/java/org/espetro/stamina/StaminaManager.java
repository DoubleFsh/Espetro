package org.espetro.stamina;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import org.espetro.config.GameConfig;
import org.espetro.network.NetworkManager;
import org.espetro.network.StaminaSyncPacket;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 服务端体力管理器。体力只在当前会话内保留，重生或重新进入时恢复为配置上限。
 */
public final class StaminaManager {

    private static final int TICKS_PER_SECOND = 20;
    private static final Map<UUID, PlayerStamina> PLAYER_STAMINA = new HashMap<>();
    private static final Set<UUID> DISABLED_STATE_SYNCED = new HashSet<>();

    private StaminaManager() {
    }

    public static void onPlayerTick(ServerPlayer player) {
        UUID playerId = player.getUUID();
        if (!GameConfig.isStaminaEnabled()) {
            PLAYER_STAMINA.remove(playerId);
            if (DISABLED_STATE_SYNCED.add(playerId)) {
                syncDisabled(player);
            }
            return;
        }

        DISABLED_STATE_SYNCED.remove(playerId);
        PlayerStamina state = getOrCreate(player);
        int maxStamina = GameConfig.getPlayerStamina();
        long currentTick = player.serverLevel().getGameTime();
        boolean changed = false;
        boolean staminaUseActive = false;

        if (state.stamina > maxStamina) {
            state.stamina = maxStamina;
            changed = true;
        }

        if (player.isSprinting()) {
            if (state.stamina <= 0) {
                player.setSprinting(false);
            } else {
                int cost = GameConfig.getSprintStaminaCostPerSecond();
                if (cost > 0) {
                    staminaUseActive = true;
                    scheduleRegeneration(player, state);
                    if (currentTick >= state.nextSprintCostTick) {
                        state.stamina = Math.max(0, state.stamina - cost);
                        state.nextSprintCostTick = currentTick + TICKS_PER_SECOND;
                        changed = true;
                    }
                }
                if (state.stamina == 0) {
                    player.setSprinting(false);
                }
            }
        }

        if (!staminaUseActive && state.stamina < maxStamina
                && currentTick >= state.regenAtTick) {
            int restored = Math.min(maxStamina,
                state.stamina + GameConfig.getStaminaRegenPerSecond());
            if (restored != state.stamina) {
                state.stamina = restored;
                state.regenAtTick = currentTick + TICKS_PER_SECOND;
                changed = true;
            }
        }

        if (changed) {
            sync(player, state);
        }
    }

    /**
     * LivingJumpEvent 不可取消，所以在体力耗尽时移除它刚施加的竖直速度。
     */
    public static void onPlayerJump(ServerPlayer player) {
        if (!GameConfig.isStaminaEnabled()) return;

        PlayerStamina state = getOrCreate(player);
        long currentTick = player.serverLevel().getGameTime();
        if (state.lastJumpTick == currentTick) return;
        state.lastJumpTick = currentTick;

        if (state.stamina <= 0) {
            stopJump(player);
            sync(player, state);
            return;
        }

        int cost = GameConfig.getJumpStaminaCost();
        if (cost <= 0) return;

        state.stamina = Math.max(0, state.stamina - cost);
        scheduleRegeneration(player, state);
        sync(player, state);
    }

    public static void resetPlayer(ServerPlayer player) {
        UUID playerId = player.getUUID();
        PLAYER_STAMINA.remove(playerId);
        DISABLED_STATE_SYNCED.remove(playerId);
        if (GameConfig.isStaminaEnabled()) {
            getOrCreate(player);
        } else {
            DISABLED_STATE_SYNCED.add(playerId);
            syncDisabled(player);
        }
    }

    public static void removePlayer(UUID playerId) {
        PLAYER_STAMINA.remove(playerId);
        DISABLED_STATE_SYNCED.remove(playerId);
    }

    public static void clear() {
        PLAYER_STAMINA.clear();
        DISABLED_STATE_SYNCED.clear();
    }

    private static PlayerStamina getOrCreate(ServerPlayer player) {
        return PLAYER_STAMINA.computeIfAbsent(player.getUUID(), ignored -> {
            PlayerStamina state = new PlayerStamina(GameConfig.getPlayerStamina());
            sync(player, state);
            return state;
        });
    }

    private static void stopJump(ServerPlayer player) {
        Vec3 movement = player.getDeltaMovement();
        if (movement.y > 0) {
            player.setDeltaMovement(movement.x, 0, movement.z);
            player.hasImpulse = true;
        }
    }

    private static void scheduleRegeneration(ServerPlayer player, PlayerStamina state) {
        state.regenAtTick = player.serverLevel().getGameTime()
            + GameConfig.getStaminaRegenDelaySeconds() * (long) TICKS_PER_SECOND;
    }

    private static void sync(ServerPlayer player, PlayerStamina state) {
        NetworkManager.sendToPlayer(player,
            new StaminaSyncPacket(true, state.stamina, GameConfig.getPlayerStamina()));
    }

    private static void syncDisabled(ServerPlayer player) {
        NetworkManager.sendToPlayer(player, new StaminaSyncPacket(false, 0, 0));
    }

    private static final class PlayerStamina {
        private int stamina;
        private long regenAtTick;
        private long nextSprintCostTick;
        private long lastJumpTick = Long.MIN_VALUE;

        private PlayerStamina(int stamina) {
            this.stamina = stamina;
        }
    }
}
