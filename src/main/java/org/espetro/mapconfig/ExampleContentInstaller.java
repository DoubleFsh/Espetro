package org.espetro.mapconfig;

import com.google.gson.Gson;
import org.espetro.Espetro;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Installs the versioned example configuration bundle without overwriting
 * operator-owned files.
 */
public final class ExampleContentInstaller {

    private static final String RESOURCE_ROOT = "/espetro_examples/";
    private static final String MANIFEST = "manifest.json";
    private static final Gson GSON = new Gson();

    private ExampleContentInstaller() {
    }

    public static Result installMissing(Path gameDir) {
        int installed = 0;
        int skipped = 0;
        List<String> errors = new ArrayList<>();
        Path normalizedGameDir = gameDir.toAbsolutePath().normalize();
        try {
            Manifest manifest = readManifest();
            if (manifest.files == null || manifest.files.isEmpty()) {
                return new Result(0, 0, List.of("示例内容清单为空"));
            }
            // 只要运营目录已有任意编制，就把整个 EsFactions 视为服主所有。
            // 不能因升级新增示例文件而改变现有服务器可选编制集合。
            boolean preserveOperatorFactions =
                containsFactionJson(normalizedGameDir.resolve("EsFactions"));
            for (String relative : manifest.files) {
                if (!isSafeRelativePath(relative)) {
                    errors.add("示例清单包含非法路径: " + relative);
                    continue;
                }
                if (preserveOperatorFactions && relative.startsWith("EsFactions/")) {
                    skipped++;
                    continue;
                }
                Path target = normalizedGameDir.resolve(relative).normalize();
                if (!target.startsWith(normalizedGameDir) || target.equals(normalizedGameDir)) {
                    errors.add("示例路径越界: " + relative);
                    continue;
                }
                if (Files.exists(target)) {
                    skipped++;
                    continue;
                }
                Files.createDirectories(target.getParent());
                try (InputStream input = openResource(relative)) {
                    try {
                        Files.copy(input, target);
                        installed++;
                    } catch (FileAlreadyExistsException ignored) {
                        skipped++;
                    }
                } catch (IOException e) {
                    errors.add(relative + ": " + e.getMessage());
                }
            }
            // Empty Anvil directories are required even when the example archive
            // deliberately contains no entities/POI data.
            Path testMap = normalizedGameDir.resolve("EsWorld/test_flat");
            for (String directory : List.of("region", "entities", "poi", "data")) {
                Files.createDirectories(testMap.resolve(directory));
            }
        } catch (Exception e) {
            errors.add(e.getMessage());
        }
        if (installed > 0) {
            Espetro.LOGGER.info("已安全导出 Espetro 示例内容: 新增 {}, 跳过 {}", installed, skipped);
        }
        for (String error : errors) {
            Espetro.LOGGER.error("[示例导出] {}", error);
        }
        return new Result(installed, skipped, List.copyOf(errors));
    }

    private static boolean containsFactionJson(Path factionsDir) throws IOException {
        if (!Files.isDirectory(factionsDir)) {
            return false;
        }
        try (var files = Files.list(factionsDir)) {
            return files.anyMatch(path -> Files.isRegularFile(path)
                && path.getFileName().toString().toLowerCase(java.util.Locale.ROOT)
                    .endsWith(".json"));
        }
    }

    private static Manifest readManifest() throws IOException {
        try (InputStream input = ExampleContentInstaller.class.getResourceAsStream(
            RESOURCE_ROOT + MANIFEST)) {
            if (input == null) {
                throw new IOException("JAR 缺少 " + RESOURCE_ROOT + MANIFEST);
            }
            Manifest manifest = GSON.fromJson(
                new java.io.InputStreamReader(input, java.nio.charset.StandardCharsets.UTF_8),
                Manifest.class);
            if (manifest == null) {
                throw new IOException("示例内容清单无法解析");
            }
            return manifest;
        }
    }

    private static InputStream openResource(String relative) throws IOException {
        InputStream input = ExampleContentInstaller.class.getResourceAsStream(
            RESOURCE_ROOT + relative.replace('\\', '/'));
        if (input == null) {
            throw new IOException("JAR 缺少示例资源");
        }
        return input;
    }

    static boolean isSafeRelativePath(String relative) {
        if (relative == null || relative.isBlank()) return false;
        Path path;
        try {
            path = Path.of(relative);
        } catch (Exception e) {
            return false;
        }
        return !path.isAbsolute()
            && !relative.contains("\\")
            && !relative.contains("\0")
            && path.normalize().equals(path)
            && !relative.startsWith(".");
    }

    private static final class Manifest {
        int version;
        List<String> files;
    }

    public record Result(int installed, int skipped, List<String> errors) {
        public boolean successful() {
            return errors.isEmpty();
        }
    }
}
