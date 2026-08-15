package org.espetro.dimension;

import javax.annotation.Nullable;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Safe, exact reset of {@code <current-save>/dimensions/espetro}. */
final class BattlefieldNamespaceReset {

    static final String NAMESPACE = "espetro";
    static final String TRASH = ".espetro-reset-trash";
    static final String TOMBSTONE_PREFIX = "startup-";

    private BattlefieldNamespaceReset() {
    }

    static ResetResult reset(Path worldRoot, long generation) {
        return reset(worldRoot, generation, NioMover.INSTANCE, BattlefieldNamespaceReset::deleteTree);
    }

    static ResetResult reset(Path worldRoot, long generation, Mover mover, Deleter deleter) {
        List<String> warnings = new ArrayList<>();
        try {
            if (worldRoot == null) throw new IllegalStateException("worldRoot 为空");
            Path lexicalWorld = worldRoot.toAbsolutePath().normalize();
            if (!Files.isDirectory(lexicalWorld, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(lexicalWorld)) {
                throw new IllegalStateException("worldRoot 不是安全的真实目录: " + lexicalWorld);
            }
            Path realWorld = lexicalWorld.toRealPath();
            Path dimensions = lexicalWorld.resolve("dimensions").normalize();
            Path namespace = dimensions.resolve(NAMESPACE).normalize();
            Path trash = lexicalWorld.resolve(TRASH).normalize();
            requireChild(lexicalWorld, dimensions, "dimensionsRoot");
            requireChild(dimensions, namespace, "namespaceRoot");
            requireChild(lexicalWorld, trash, "trashRoot");
            if (namespace.equals(lexicalWorld) || namespace.equals(dimensions)
                || namespace.getParent() == null) {
                throw new IllegalStateException("拒绝危险 namespace 目标: " + namespace);
            }

            if (Files.exists(dimensions, LinkOption.NOFOLLOW_LINKS)) {
                if (Files.isSymbolicLink(dimensions)
                    || !Files.isDirectory(dimensions, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IllegalStateException("dimensionsRoot 必须是非符号链接目录: " + dimensions);
                }
                Path realDimensions = dimensions.toRealPath();
                if (!realDimensions.startsWith(realWorld) || realDimensions.equals(realWorld)) {
                    throw new IllegalStateException("dimensionsRoot 越出当前存档: " + realDimensions);
                }
            }
            if (Files.exists(trash, LinkOption.NOFOLLOW_LINKS)
                && (Files.isSymbolicLink(trash)
                    || !Files.isDirectory(trash, LinkOption.NOFOLLOW_LINKS))) {
                throw new IllegalStateException("trashRoot 必须是非符号链接目录: " + trash);
            }

            // Namespace absence is idempotent, but old self-owned tombstones are still retried.
            if (!Files.exists(namespace, LinkOption.NOFOLLOW_LINKS)) {
                cleanupExistingTrash(lexicalWorld, trash, deleter, warnings);
                return ResetResult.ready(null, warnings);
            }

            if (Files.exists(trash, LinkOption.NOFOLLOW_LINKS)) {
                if (Files.isSymbolicLink(trash)
                    || !Files.isDirectory(trash, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IllegalStateException("trashRoot 必须是非符号链接目录: " + trash);
                }
            } else {
                Files.createDirectory(trash);
            }
            if (Files.isSymbolicLink(trash)) {
                throw new IllegalStateException("trashRoot 创建后变成符号链接: " + trash);
            }
            Path realTrash = trash.toRealPath();
            if (!realTrash.startsWith(realWorld) || realTrash.equals(realWorld)) {
                throw new IllegalStateException("trashRoot 越出当前存档: " + realTrash);
            }

            Path tombstone = trash.resolve(TOMBSTONE_PREFIX + generation + "-"
                + UUID.randomUUID()).normalize();
            requireChild(trash, tombstone, "tombstone");
            try {
                mover.atomic(namespace, tombstone);
            } catch (AtomicMoveNotSupportedException unsupported) {
                mover.regular(namespace, tombstone);
            }
            if (Files.exists(namespace, LinkOption.NOFOLLOW_LINKS)
                || !Files.exists(tombstone, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("namespace 隔离后状态不一致");
            }

            try {
                deleter.delete(tombstone);
            } catch (Exception e) {
                warnings.add("live namespace 已隔离，但 tombstone 删除失败: " + e.getMessage());
            }
            cleanupExistingTrash(lexicalWorld, trash, deleter, warnings);
            return ResetResult.ready(tombstone, warnings);
        } catch (Exception e) {
            return ResetResult.failed(e.getMessage() == null ? e.toString() : e.getMessage(), warnings);
        }
    }

    private static void cleanupExistingTrash(Path world, Path trash, Deleter deleter,
                                             List<String> warnings) {
        try {
            if (!Files.exists(trash, LinkOption.NOFOLLOW_LINKS)) return;
            if (Files.isSymbolicLink(trash)
                || !Files.isDirectory(trash, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("不安全的 trashRoot: " + trash);
            }
            try (var entries = Files.list(trash)) {
                for (Path entry : entries.toList()) {
                    String name = entry.getFileName().toString();
                    if (!name.startsWith(TOMBSTONE_PREFIX)) continue;
                    Path normalized = entry.toAbsolutePath().normalize();
                    requireChild(trash.toAbsolutePath().normalize(), normalized, "old tombstone");
                    try {
                        deleter.delete(normalized);
                    } catch (Exception e) {
                        warnings.add("旧 tombstone 删除失败 " + name + ": " + e.getMessage());
                    }
                }
            }
        } catch (Exception e) {
            warnings.add("旧 tombstone 清理跳过: " + e.getMessage());
        }
    }

    private static void requireChild(Path parent, Path child, String label) {
        Path normalizedParent = parent.toAbsolutePath().normalize();
        Path normalizedChild = child.toAbsolutePath().normalize();
        if (!normalizedChild.startsWith(normalizedParent) || normalizedChild.equals(normalizedParent)) {
            throw new IllegalStateException(label + " 不在预期父目录内: " + normalizedChild);
        }
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return;
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.deleteIfExists(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, @Nullable IOException error)
                throws IOException {
                if (error != null) throw error;
                Files.deleteIfExists(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    interface Mover {
        void atomic(Path source, Path target) throws IOException;

        void regular(Path source, Path target) throws IOException;
    }

    @FunctionalInterface
    interface Deleter {
        void delete(Path root) throws IOException;
    }

    private enum NioMover implements Mover {
        INSTANCE;

        @Override
        public void atomic(Path source, Path target) throws IOException {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        }

        @Override
        public void regular(Path source, Path target) throws IOException {
            Files.move(source, target);
        }
    }

    record ResetResult(boolean isolated, @Nullable Path tombstone, List<String> warnings,
                       @Nullable String error) {
        static ResetResult ready(@Nullable Path tombstone, List<String> warnings) {
            return new ResetResult(true, tombstone, List.copyOf(warnings), null);
        }

        static ResetResult failed(String error, List<String> warnings) {
            return new ResetResult(false, null, List.copyOf(warnings), error);
        }
    }
}
