package org.espetro.audio;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AudioCuePolicyTest {

    @Test
    void mapsLogicalAndScoreboardTeamIds() {
        assertEquals("ATTACK", AudioCuePolicy.normalizeTeam("attack"));
        assertEquals("ATTACK", AudioCuePolicy.normalizeTeam("espetro_attack"));
        assertEquals("DEFEND", AudioCuePolicy.normalizeTeam("ESPETRO_DEFEND"));
        assertNull(AudioCuePolicy.normalizeTeam("spectator"));
    }

    @Test
    void easterEggBoundaryIsExactlyTenPercent() {
        assertTrue(AudioCuePolicy.useEasterEgg(0.0D));
        assertTrue(AudioCuePolicy.useEasterEgg(0.099999D));
        assertFalse(AudioCuePolicy.useEasterEgg(0.10D));
        assertFalse(AudioCuePolicy.useEasterEgg(-0.01D));
        assertFalse(AudioCuePolicy.useEasterEgg(Double.NaN));
    }

    @Test
    void routesNeutralizationToTheOldOwnersOpposingSide() {
        assertEquals("ATTACK", AudioCuePolicy.resolveNeutralizingTeam(
            "espetro_defend", "espetro_attack"));
        assertEquals("DEFEND", AudioCuePolicy.resolveNeutralizingTeam(
            "ATTACK", null));
        assertEquals("ATTACK", AudioCuePolicy.resolveNeutralizingTeam(
            "DEFEND", "espetro_defend"));
        assertNull(AudioCuePolicy.resolveNeutralizingTeam("spectator", "ATTACK"));
    }
}
