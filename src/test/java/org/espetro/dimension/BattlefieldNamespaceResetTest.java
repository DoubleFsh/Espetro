package org.espetro.dimension;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BattlefieldNamespaceResetTest {

    @Test
    void isolatesEntireExactNamespaceAndTouchesNothingElse(@TempDir Path temp) throws Exception {
        Path world = Files.createDirectories(temp.resolve("saves/新的世界"));
        write(world.resolve("dimensions/espetro/server_battlefield/region/r.0.0.mca"), "old");
        write(world.resolve("dimensions/espetro/test_flat.importing/stale"), "old");
        write(world.resolve("dimensions/espetro/unknown/file"), "old");
        Path other = write(world.resolve("dimensions/other/map/keep"), "keep");
        Path overworld = write(world.resolve("region/r.0.0.mca"), "overworld");
        Path esWorld = write(temp.resolve("EsWorld/map/region/r.0.0.mca"), "template");

        var result = BattlefieldNamespaceReset.reset(world, 7L);

        assertTrue(result.isolated(), result.error());
        assertFalse(Files.exists(world.resolve("dimensions/espetro")));
        assertEquals("keep", Files.readString(other));
        assertEquals("overworld", Files.readString(overworld));
        assertEquals("template", Files.readString(esWorld));
    }

    @Test
    void absenceIsIdempotentEvenWhenMapListWouldBeInvalid(@TempDir Path temp) throws Exception {
        Path world = Files.createDirectories(temp.resolve("world"));
        write(temp.resolve("EsDimensions.json"), "not-json");
        var first = BattlefieldNamespaceReset.reset(world, 1L);
        var second = BattlefieldNamespaceReset.reset(world, 2L);
        assertTrue(first.isolated(), first.error());
        assertTrue(second.isolated(), second.error());
    }

    @Test
    void namespaceSymlinkIsMovedWithoutFollowingTarget(@TempDir Path temp) throws Exception {
        Path world = Files.createDirectories(temp.resolve("world"));
        Path dimensions = Files.createDirectories(world.resolve("dimensions"));
        Path external = Files.createDirectories(temp.resolve("outside"));
        Path sentinel = write(external.resolve("sentinel"), "safe");
        Files.createSymbolicLink(dimensions.resolve("espetro"), external);

        var result = BattlefieldNamespaceReset.reset(world, 3L);

        assertTrue(result.isolated(), result.error());
        assertFalse(Files.exists(dimensions.resolve("espetro"),
            java.nio.file.LinkOption.NOFOLLOW_LINKS));
        assertEquals("safe", Files.readString(sentinel));
    }

    @Test
    void parentSymlinkFailsAndExternalTargetIsUntouched(@TempDir Path temp) throws Exception {
        Path world = Files.createDirectories(temp.resolve("world"));
        Path outside = Files.createDirectories(temp.resolve("outside"));
        Path sentinel = write(outside.resolve("espetro/sentinel"), "safe");
        Files.createSymbolicLink(world.resolve("dimensions"), outside);

        var result = BattlefieldNamespaceReset.reset(world, 4L);

        assertFalse(result.isolated());
        assertNotNull(result.error());
        assertEquals("safe", Files.readString(sentinel));
    }

    @Test
    void fallsBackOnlyWhenAtomicMoveIsUnsupported(@TempDir Path temp) throws Exception {
        Path world = Files.createDirectories(temp.resolve("world"));
        write(world.resolve("dimensions/espetro/map/file"), "old");
        var mover = new BattlefieldNamespaceReset.Mover() {
            @Override
            public void atomic(Path source, Path target) throws IOException {
                throw new AtomicMoveNotSupportedException(source.toString(), target.toString(),
                    "fixture");
            }

            @Override
            public void regular(Path source, Path target) throws IOException {
                Files.move(source, target);
            }
        };

        var result = BattlefieldNamespaceReset.reset(world, 5L, mover,
            BattlefieldNamespaceResetTest::deleteTree);

        assertTrue(result.isolated(), result.error());
        assertFalse(Files.exists(world.resolve("dimensions/espetro")));
    }

    @Test
    void failedMovesLeaveLiveNamespaceWholeAndFailGateResult(@TempDir Path temp) throws Exception {
        Path world = Files.createDirectories(temp.resolve("world"));
        Path sentinel = write(world.resolve("dimensions/espetro/map/file"), "old");
        var mover = new BattlefieldNamespaceReset.Mover() {
            @Override
            public void atomic(Path source, Path target) throws IOException {
                throw new AtomicMoveNotSupportedException(source.toString(), target.toString(),
                    "fixture");
            }

            @Override
            public void regular(Path source, Path target) throws IOException {
                throw new IOException("regular denied");
            }
        };

        var result = BattlefieldNamespaceReset.reset(world, 6L, mover,
            BattlefieldNamespaceResetTest::deleteTree);

        assertFalse(result.isolated());
        assertEquals("old", Files.readString(sentinel));
    }

    @Test
    void deleteFailureKeepsReadyWithWarningAndQuarantinedTombstone(@TempDir Path temp)
        throws Exception {
        Path world = Files.createDirectories(temp.resolve("world"));
        write(world.resolve("dimensions/espetro/map/file"), "old");

        var result = BattlefieldNamespaceReset.reset(world, 8L,
            new BattlefieldNamespaceReset.Mover() {
                @Override
                public void atomic(Path source, Path target) throws IOException {
                    Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
                }

                @Override
                public void regular(Path source, Path target) throws IOException {
                    Files.move(source, target);
                }
            }, ignored -> { throw new IOException("fixture delete denied"); });

        assertTrue(result.isolated(), result.error());
        assertFalse(result.warnings().isEmpty());
        assertNotNull(result.tombstone());
        assertTrue(Files.exists(result.tombstone(), java.nio.file.LinkOption.NOFOLLOW_LINKS));
    }

    private static Path write(Path path, String content) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
        return path;
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return;
        Files.walkFileTree(root, new java.nio.file.SimpleFileVisitor<>() {
            @Override
            public java.nio.file.FileVisitResult visitFile(Path file,
                java.nio.file.attribute.BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return java.nio.file.FileVisitResult.CONTINUE;
            }

            @Override
            public java.nio.file.FileVisitResult postVisitDirectory(Path dir, IOException exc)
                throws IOException {
                if (exc != null) throw exc;
                Files.delete(dir);
                return java.nio.file.FileVisitResult.CONTINUE;
            }
        });
    }
}
