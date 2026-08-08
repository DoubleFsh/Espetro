package org.espetro.kubejs;

import net.minecraftforge.fml.loading.FMLPaths;
import org.espetro.Espetro;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Installs the editable KubeJS commander-skill examples on first launch.
 *
 * <p>The Java implementation deliberately does not overwrite existing files:
 * server owners may customize the examples. The scripts live as normal jar
 * resources so the installed examples and the files used during development
 * have a single source of truth.</p>
 */
public final class EspetroKubeJSDefaultScripts {
    private static final String RESOURCE_ROOT = "/espetro_kubejs/";

    private EspetroKubeJSDefaultScripts() {
    }

    public static void ensureDefaultScripts() {
        install("startup_scripts/00_espetro_drone_detection.js");
        install("startup_scripts/00_espetro_artillery_155.js");
        install("server_scripts/00_espetro_drone_detection.js");
        install("server_scripts/00_espetro_artillery_155.js");
    }

    private static void install(String relativePath) {
        Path target = FMLPaths.GAMEDIR.get().resolve("kubejs").resolve(relativePath);
        try {
            if (Files.exists(target)) {
                return;
            }

            String source = readBundledScript(relativePath);
            Files.createDirectories(target.getParent());
            Files.writeString(target, source, StandardCharsets.UTF_8);
            Espetro.LOGGER.info("已写入默认 KubeJS 指挥官技能脚本: {}", target);
        } catch (IOException e) {
            Espetro.LOGGER.warn("无法写入默认 KubeJS 指挥官技能脚本: {}", target, e);
        }
    }

    private static String readBundledScript(String relativePath) throws IOException {
        String resourcePath = RESOURCE_ROOT + relativePath;
        try (InputStream stream = EspetroKubeJSDefaultScripts.class.getResourceAsStream(resourcePath)) {
            if (stream == null) {
                throw new IOException("缺少内置脚本资源: " + resourcePath);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
