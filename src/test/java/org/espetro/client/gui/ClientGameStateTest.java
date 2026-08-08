package org.espetro.client.gui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ClientGameStateTest {

    @Test
    void battleTimerUsesServerAnchorAndDecrementsLocally() {
        assertEquals(90, ClientGameState.calculateAnchoredRemaining(90, 1_000L, 1_000L));
        assertEquals(89, ClientGameState.calculateAnchoredRemaining(90, 1_000L, 2_999L));
        assertEquals(0, ClientGameState.calculateAnchoredRemaining(90, 1_000L, 100_000L));
    }

    @Test
    void missingTimerRemainsHiddenAndClockSkewCannotAddTime() {
        assertEquals(-1, ClientGameState.calculateAnchoredRemaining(-1, 1_000L, 5_000L));
        assertEquals(90, ClientGameState.calculateAnchoredRemaining(90, 5_000L, 1_000L));
    }
}
