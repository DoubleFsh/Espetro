package org.espetro.governance;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class CommanderGovernanceManagerTest {

    @Test
    void impeachmentChallengerNeedsStrictMajority() {
        UUID commander = UUID.randomUUID();
        UUID challenger = UUID.randomUUID();
        Map<UUID, UUID> votes = new HashMap<>();
        votes.put(UUID.randomUUID(), challenger);
        votes.put(UUID.randomUUID(), challenger);
        votes.put(UUID.randomUUID(), commander);

        assertEquals(challenger,
            CommanderGovernanceManager.resolveImpeachmentWinner(commander, challenger, votes));
    }

    @Test
    void impeachmentTieKeepsIncumbent() {
        UUID commander = UUID.randomUUID();
        UUID challenger = UUID.randomUUID();
        Map<UUID, UUID> votes = new HashMap<>();
        votes.put(UUID.randomUUID(), challenger);
        votes.put(UUID.randomUUID(), commander);

        assertEquals(commander,
            CommanderGovernanceManager.resolveImpeachmentWinner(commander, challenger, votes));
    }

    @Test
    void impeachmentNoVotesKeepsIncumbent() {
        UUID commander = UUID.randomUUID();
        UUID challenger = UUID.randomUUID();
        assertEquals(commander,
            CommanderGovernanceManager.resolveImpeachmentWinner(commander, challenger, Map.of()));
    }

    @Test
    void vacancyHighestVoteWins() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        Map<UUID, UUID> votes = Map.of(
            UUID.randomUUID(), a,
            UUID.randomUUID(), a,
            UUID.randomUUID(), b
        );
        assertEquals(a, CommanderGovernanceManager.resolveVacancyVoteWinner(
            Set.of(a, b), votes, u -> 0L));
    }

    @Test
    void vacancyTieBreaksByLeaderSinceThenUuid() {
        UUID earlier = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID later = UUID.fromString("00000000-0000-0000-0000-000000000002");
        Map<UUID, UUID> votes = Map.of(
            UUID.randomUUID(), earlier,
            UUID.randomUUID(), later
        );
        Map<UUID, Long> since = Map.of(earlier, 10L, later, 20L);
        assertEquals(earlier, CommanderGovernanceManager.resolveVacancyVoteWinner(
            Set.of(earlier, later), votes, since::get));
    }

    @Test
    void vacancyEmptyVolunteersReturnsNull() {
        assertNull(CommanderGovernanceManager.resolveVacancyVoteWinner(
            Set.of(), Map.of(), u -> 0L));
    }
}
