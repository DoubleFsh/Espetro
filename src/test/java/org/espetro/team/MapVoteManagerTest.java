package org.espetro.team;

import net.minecraft.resources.ResourceLocation;
import org.espetro.mapconfig.ActiveMapConfig;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MapVoteManagerTest {

    @Test
    void highestGlobalVoteWins() {
        ActiveMapConfig a = candidate("a");
        ActiveMapConfig b = candidate("b");
        ActiveMapConfig c = candidate("c");
        Map<UUID, String> votes = Map.of(
            UUID.randomUUID(), "b",
            UUID.randomUUID(), "a",
            UUID.randomUUID(), "b"
        );

        assertEquals("b", MapVoteManager.resolveWinnerForTest(
            List.of(a, b, c), votes, new Random(1)).mapFolder);
    }

    @Test
    void aTieAndNoVotesResolveOnlyInsideCandidatePool() {
        ActiveMapConfig a = candidate("a");
        ActiveMapConfig b = candidate("b");
        List<ActiveMapConfig> candidates = List.of(a, b);

        ActiveMapConfig tied = MapVoteManager.resolveWinnerForTest(
            candidates,
            Map.of(UUID.randomUUID(), "a", UUID.randomUUID(), "b"),
            new Random(7));
        ActiveMapConfig noVotes = MapVoteManager.resolveWinnerForTest(
            candidates, Map.of(), new Random(9));

        assertTrue(candidates.contains(tied));
        assertTrue(candidates.contains(noVotes));
    }

    private static ActiveMapConfig candidate(String id) {
        return ActiveMapConfig.rejected(
            id, id, ResourceLocation.fromNamespaceAndPath("espetro", id), "test candidate");
    }
}
