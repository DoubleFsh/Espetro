package org.espetro.mapconfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;

/**
 * Path validation for EsWorld / EsFactions map folder names.
 * Rejects absolute paths, parent traversal and out-of-root symlinks.
 */
public final class PathSafety {

    private PathSafety() {
    }

    public static Optional<String> validateMapFolderName(String mapName) {
        if (mapName == null || mapName.isBlank()) {
            return Optional.of("map 名称不能为空");
        }
        String name = mapName.trim();
        if (name.contains("..") || name.contains("/") || name.contains("\\")
            || name.contains(":") || name.contains("\0")) {
            return Optional.of("map 名称非法（禁止路径分隔符、.. 或绝对路径）: " + name);
        }
        if (name.startsWith(".") && !name.equals("_template")) {
            return Optional.of("map 名称不能以 . 开头: " + name);
        }
        return Optional.empty();
    }

    /**
     * Resolve {@code root/child} and ensure the result stays under root after real-path resolution.
     */
    public static Path resolveChildDir(Path root, String childName) throws IOException {
        Optional<String> error = validateMapFolderName(childName);
        if (error.isPresent()) {
            throw new IOException(error.get());
        }
        Path rootReal = root.toAbsolutePath().normalize();
        if (Files.exists(rootReal)) {
            rootReal = rootReal.toRealPath();
        }
        Path child = rootReal.resolve(childName).normalize();
        if (!child.startsWith(rootReal)) {
            throw new IOException("路径越界: " + childName);
        }
        if (Files.exists(child) && Files.isSymbolicLink(child)) {
            Path real = child.toRealPath();
            if (!real.startsWith(rootReal)) {
                throw new IOException("符号链接越界: " + childName + " -> " + real);
            }
            return real;
        }
        return child;
    }

    public static String slugify(String input) {
        if (input == null || input.isBlank()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        String lower = input.trim().toLowerCase(Locale.ROOT);
        boolean lastDash = false;
        for (int i = 0; i < lower.length(); ) {
            int cp = lower.codePointAt(i);
            i += Character.charCount(cp);
            if ((cp >= 'a' && cp <= 'z') || (cp >= '0' && cp <= '9')) {
                sb.appendCodePoint(cp);
                lastDash = false;
            } else if (cp == '_' || cp == '-' || Character.isWhitespace(cp)) {
                if (!lastDash && sb.length() > 0) {
                    sb.append('_');
                    lastDash = true;
                }
            }
        }
        while (sb.length() > 0 && sb.charAt(sb.length() - 1) == '_') {
            sb.setLength(sb.length() - 1);
        }
        return sb.toString();
    }

    public static String stableShortHash(String input) {
        int h = 0x811c9dc5;
        byte[] bytes = input.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        for (byte b : bytes) {
            h ^= (b & 0xff);
            h *= 0x01000193;
        }
        return Integer.toHexString(h);
    }
}
