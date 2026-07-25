package org.espetro.mapconfig;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ESPointsMapSnapshotTest {

    @Test
    void loadsValidFrozenSnapshotAndDefensivelyCopiesBackground(@TempDir Path dir) throws Exception {
        byte[] png = new byte[]{
            (byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 1, 2, 3
        };
        writeValidFiles(dir, "map.png");
        Files.write(dir.resolve("map.png"), png);

        ESPointsMapSnapshot snapshot = ESPointsMapSnapshot.load(dir);
        assertEquals("map.png", snapshot.backgroundImage);
        assertTrue(snapshot.hasBackground());
        assertArrayEquals(png, snapshot.backgroundBytes());

        byte[] exposed = snapshot.backgroundBytes();
        exposed[0] = 0;
        assertArrayEquals(png, snapshot.backgroundBytes());
    }

    @Test
    void rejectsUnsafeOrForgedBackgrounds(@TempDir Path dir) throws Exception {
        writeValidFiles(dir, "../map.png");
        IOException traversal = assertThrows(IOException.class,
            () -> ESPointsMapSnapshot.load(dir));
        assertTrue(traversal.getMessage().contains("安全相对路径"));

        writeValidFiles(dir, "map.png");
        Files.writeString(dir.resolve("map.png"), "not a png", StandardCharsets.UTF_8);
        IOException forged = assertThrows(IOException.class,
            () -> ESPointsMapSnapshot.load(dir));
        assertTrue(forged.getMessage().contains("不是有效的 PNG"));
    }

    @Test
    void rejectsMalformedCapturePlan(@TempDir Path dir) throws Exception {
        writeTactical(dir, "");
        Files.writeString(dir.resolve("CapturePoints.json"), """
            {
              "totalBatches":1,
              "endBehavior":"terminate",
              "teamReinforcements":{"ATTACK":280,"DEFEND":1200},
              "plannedPoints":[
                {"name":"A","batch":1,"pos1":[0,60,0],"pos2":[4,70,4]},
                {"name":"A","batch":1,"pos1":[8,60,8],"pos2":[12,70,12]}
              ]
            }
            """, StandardCharsets.UTF_8);

        IllegalArgumentException duplicate = assertThrows(IllegalArgumentException.class,
            () -> ESPointsMapSnapshot.load(dir));
        assertTrue(duplicate.getMessage().contains("重复据点"));
    }

    @Test
    void supportsMapWithoutBackground(@TempDir Path dir) throws Exception {
        writeValidFiles(dir, "");
        ESPointsMapSnapshot snapshot = ESPointsMapSnapshot.load(dir);
        assertFalse(snapshot.hasBackground());
        assertEquals(0, snapshot.backgroundBytes().length);
    }

    private static void writeValidFiles(Path dir, String background) throws Exception {
        writeTactical(dir, background);
        Files.writeString(dir.resolve("CapturePoints.json"), """
            {
              "totalBatches":2,
              "endBehavior":"terminate",
              "teamReinforcements":{"ATTACK":280,"DEFEND":1200},
              "plannedPoints":[
                {"name":"A","batch":1,"pos1":[0,60,0],"pos2":[4,70,4]},
                {"name":"B","batch":2,"pos1":{"x":8,"y":60,"z":8},
                 "pos2":{"x":12,"y":70,"z":12}}
              ]
            }
            """, StandardCharsets.UTF_8);
    }

    private static void writeTactical(Path dir, String background) throws Exception {
        Files.writeString(dir.resolve("TacticalMap.json"), """
            {
              "topLeftX":-512,
              "topLeftZ":-512,
              "bottomRightX":512,
              "bottomRightZ":512,
              "initialRange":512,
              "minimumRange":64,
              "backgroundImage":"%s",
              "showGrid":true,
              "showLabels":true
            }
            """.formatted(background), StandardCharsets.UTF_8);
    }
}
