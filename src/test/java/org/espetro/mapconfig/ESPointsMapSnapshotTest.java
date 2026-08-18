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
        byte[] png = minimalPngHeader(5904, 6720);
        writeValidFiles(dir, "map.png");
        Files.write(dir.resolve("map.png"), png);

        ESPointsMapSnapshot snapshot = ESPointsMapSnapshot.load(dir);
        assertEquals("map.png", snapshot.backgroundImage);
        assertTrue(snapshot.hasBackground());
        assertEquals(5904, snapshot.backgroundWidth);
        assertEquals(6720, snapshot.backgroundHeight);
        assertEquals(64, snapshot.backgroundSha256.length());
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
    void rejectsPngPixelBombBeforeDecode(@TempDir Path dir) throws Exception {
        writeValidFiles(dir, "map.png");
        Files.write(dir.resolve("map.png"), minimalPngHeader(20_000, 20_000));

        IOException error = assertThrows(IOException.class,
            () -> ESPointsMapSnapshot.load(dir));
        assertTrue(error.getMessage().contains("像素数超限"));
    }

    @Test
    void rejectsPathologicalSingleDimensionBeforeDecode(@TempDir Path dir) throws Exception {
        writeValidFiles(dir, "map.png");
        Files.write(dir.resolve("map.png"), minimalPngHeader(40_000, 1));

        IOException error = assertThrows(IOException.class,
            () -> ESPointsMapSnapshot.load(dir));
        assertTrue(error.getMessage().contains("单边最多"));
    }

    @Test
    void supportsMapWithoutBackground(@TempDir Path dir) throws Exception {
        writeValidFiles(dir, "");
        ESPointsMapSnapshot snapshot = ESPointsMapSnapshot.load(dir);
        assertFalse(snapshot.hasBackground());
        assertEquals(0, snapshot.backgroundBytes().length);
    }

    @Test
    void resolvesRaasOnlyWhenRoundStarts(@TempDir Path dir) throws Exception {
        writeTactical(dir, "");
        Files.writeString(dir.resolve("CapturePoints.json"), """
            {
              "objectiveMode":"RAAS",
              "endBehavior":"terminate",
              "teamReinforcements":{"ATTACK":280,"DEFEND":1200},
              "raas":{
                "points":[
                  {"id":"main","pos1":[0,60,0],"pos2":[4,70,4]},
                  {"id":"north","pos1":[8,60,0],"pos2":[12,70,4]},
                  {"id":"south","pos1":[8,60,8],"pos2":[12,70,12]},
                  {"id":"terminal","pos1":[16,60,0],"pos2":[20,70,4]}
                ],
                "lanes":[
                  {"id":"east","stages":[["main"],["north","south"],["terminal"]]}
                ]
              }
            }
            """, StandardCharsets.UTF_8);

        ESPointsMapSnapshot frozen = ESPointsMapSnapshot.load(dir);
        ESPointsMapSnapshot round = frozen.forRound(17L);
        assertEquals("RAAS", round.objectiveMode);
        assertEquals("east", round.objectiveLane);
        assertEquals(17L, round.objectiveSeed);
        assertEquals(3, com.google.gson.JsonParser.parseString(round.capturePointsJson)
            .getAsJsonObject().get("totalBatches").getAsInt());
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

    private static byte[] minimalPngHeader(int width, int height) {
        byte[] bytes = new byte[24];
        byte[] signature = new byte[]{
            (byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a
        };
        System.arraycopy(signature, 0, bytes, 0, signature.length);
        bytes[11] = 13;
        bytes[12] = 'I';
        bytes[13] = 'H';
        bytes[14] = 'D';
        bytes[15] = 'R';
        writeInt(bytes, 16, width);
        writeInt(bytes, 20, height);
        return bytes;
    }

    private static void writeInt(byte[] bytes, int offset, int value) {
        bytes[offset] = (byte) (value >>> 24);
        bytes[offset + 1] = (byte) (value >>> 16);
        bytes[offset + 2] = (byte) (value >>> 8);
        bytes[offset + 3] = (byte) value;
    }
}
