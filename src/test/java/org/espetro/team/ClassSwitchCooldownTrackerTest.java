package org.espetro.team;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ClassSwitchCooldownTrackerTest {

    @Test
    void tracksEveryPlayerIndependentlyAndRoundsRemainingTimeUp() {
        ClassSwitchCooldownTracker tracker = new ClassSwitchCooldownTracker();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();

        assertEquals(0, tracker.getRemainingSeconds(first, 1_000L));
        tracker.start(first, 60, 1_000L);

        assertEquals(60, tracker.getRemainingSeconds(first, 1_000L));
        assertEquals(60, tracker.getRemainingSeconds(first, 1_001L));
        assertEquals(59, tracker.getRemainingSeconds(first, 2_000L));
        assertEquals(0, tracker.getRemainingSeconds(second, 2_000L));
        assertEquals(0, tracker.getRemainingSeconds(first, 61_000L));
    }

    @Test
    void zeroSecondsDisablesCooldownAndRoundResetClearsAllPlayers() {
        ClassSwitchCooldownTracker tracker = new ClassSwitchCooldownTracker();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();

        tracker.start(first, 30, 0L);
        tracker.start(first, 0, 1_000L);
        assertEquals(0, tracker.getRemainingSeconds(first, 1_000L));

        tracker.start(first, 30, 2_000L);
        tracker.start(second, 30, 2_000L);
        tracker.clearAll();
        assertEquals(0, tracker.getRemainingSeconds(first, 2_000L));
        assertEquals(0, tracker.getRemainingSeconds(second, 2_000L));
    }
}
