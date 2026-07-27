package org.espetro.team;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Per-round, per-player class selection cooldowns.
 *
 * <p>The tracker is intentionally independent from the player's current class.
 * Leaving a squad or reconnecting therefore cannot bypass an active cooldown.
 */
final class ClassSwitchCooldownTracker {
    private final Map<UUID, Long> readyAtMillis = new HashMap<>();

    int getRemainingSeconds(UUID playerId, long nowMillis) {
        Long readyAt = readyAtMillis.get(playerId);
        if (readyAt == null) {
            return 0;
        }
        long remainingMillis = readyAt - nowMillis;
        if (remainingMillis <= 0L) {
            readyAtMillis.remove(playerId);
            return 0;
        }
        return (int) Math.min(Integer.MAX_VALUE,
            (remainingMillis + 999L) / 1000L);
    }

    void start(UUID playerId, int cooldownSeconds, long nowMillis) {
        if (cooldownSeconds <= 0) {
            readyAtMillis.remove(playerId);
            return;
        }
        readyAtMillis.put(playerId, nowMillis + cooldownSeconds * 1000L);
    }

    void clearAll() {
        readyAtMillis.clear();
    }
}
