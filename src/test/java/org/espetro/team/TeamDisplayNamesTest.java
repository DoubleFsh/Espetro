package org.espetro.team;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TeamDisplayNamesTest {

    @Test
    void aasKeepsAttackDefendLabels() {
        assertFalse(TeamDisplayNames.isSymmetricMode("AAS"));
        assertFalse(TeamDisplayNames.isSymmetricMode(""));
        assertFalse(TeamDisplayNames.isSymmetricMode(null));
        assertEquals("进攻方", TeamDisplayNames.displayName("ATTACK", false));
        assertEquals("防守方", TeamDisplayNames.displayName("DEFEND", false));
        assertEquals("进攻", TeamDisplayNames.shortLabel("ATTACK", false));
        assertEquals("防守", TeamDisplayNames.shortLabel("DEFEND", false));
        assertEquals("\u00a7c", TeamDisplayNames.prefix("ATTACK"));
        assertEquals("\u00a79", TeamDisplayNames.prefix("DEFEND"));
    }

    @Test
    void raasUsesSymmetricCampLabels() {
        assertTrue(TeamDisplayNames.isSymmetricMode("RAAS"));
        assertTrue(TeamDisplayNames.isSymmetricMode("raas"));
        assertEquals("阵营A", TeamDisplayNames.displayName("ATTACK", true));
        assertEquals("阵营B", TeamDisplayNames.displayName("DEFEND", true));
        assertEquals("A", TeamDisplayNames.shortLabel("ATTACK", true));
        assertEquals("B", TeamDisplayNames.shortLabel("DEFEND", true));
    }
}
