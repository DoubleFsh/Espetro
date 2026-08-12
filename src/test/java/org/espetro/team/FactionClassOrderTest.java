package org.espetro.team;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FactionClassOrderTest {

    @Test
    void preservesJsonDeclarationOrderAcrossAllDisplayRows(@TempDir Path temp) throws Exception {
        Path factionFile = temp.resolve("ordered.json");
        Files.writeString(factionFile, """
            {
              "VehTypes": [],
              "faction": {
                "name": "Order Test",
                "faction_id": "ORDER_SIDE",
                "team": "ATTACK"
              },
              "vehicles": {},
              "classes": {
                "zulu":    {"name":"Zulu",    "maxPlayers":1, "row":2},
                "alpha":   {"name":"Alpha",   "maxPlayers":1, "row":1},
                "mike":    {"name":"Mike",    "maxPlayers":1, "row":2},
                "bravo":   {"name":"Bravo",   "maxPlayers":1, "row":1},
                "yankee":  {"name":"Yankee",  "maxPlayers":1, "row":3},
                "charlie": {"name":"Charlie", "maxPlayers":1, "row":2}
              }
            }
            """, StandardCharsets.UTF_8);

        FactionDataLoader loader = new FactionDataLoader();
        loader.loadExternalFrozen(Map.of("ordered", factionFile));

        assertEquals(
            List.of("zulu", "alpha", "mike", "bravo", "yankee", "charlie"),
            Arrays.asList(loader.getClassIdsForFaction("ordered")));
        assertEquals(List.of("alpha", "bravo"), classIdsInRow(loader, 1));
        assertEquals(List.of("zulu", "mike", "charlie"), classIdsInRow(loader, 2));
        assertEquals(List.of("yankee"), classIdsInRow(loader, 3));
    }

    private static List<String> classIdsInRow(FactionDataLoader loader, int row) {
        return Arrays.stream(loader.getClassesForFaction("ordered"))
            .filter(kit -> kit.row == row)
            .map(kit -> kit.id)
            .toList();
    }
}
