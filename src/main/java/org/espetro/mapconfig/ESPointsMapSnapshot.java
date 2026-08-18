package org.espetro.mapconfig;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 来自 EsConfig/ 目录的 ESPoints 战术地图和据点配置的冻结快照。
 */
public final class ESPointsMapSnapshot {

    public static final String TACTICAL_MAP_FILE = "TacticalMap.json";
    public static final String CAPTURE_POINTS_FILE = "CapturePoints.json";

    /** 允许的最大 BGR 单边尺寸 */
    private static final int MAX_BGR_SIDE = 30_000;
    /** 允许的最大 BGR 像素面积 */
    private static final long MAX_BGR_PIXELS = 100_000_000L;

    public final String tacticalMapJson;
    public final String capturePointsJson;
    public final String backgroundImage;
    public final String backgroundSha256;
    public final int backgroundWidth;
    public final int backgroundHeight;
    public final String objectiveMode;
    public final String objectiveLane;
    public final long objectiveSeed;

    private final byte[] background;
    private final ObjectiveLayout objectiveLayout;

    private ESPointsMapSnapshot(String tacticalMapJson, String capturePointsJson,
                                String backgroundImage, byte[] backgroundBytes,
                                PngMetadata pngMeta, ObjectiveLayout objectiveLayout,
                                String objectiveMode, String objectiveLane,
                                long objectiveSeed) {
        this.tacticalMapJson = tacticalMapJson;
        this.capturePointsJson = capturePointsJson;
        this.backgroundImage = backgroundImage;
        if (backgroundBytes != null && backgroundBytes.length > 0) {
            this.background = new byte[backgroundBytes.length];
            System.arraycopy(backgroundBytes, 0, this.background, 0, backgroundBytes.length);
        } else {
            this.background = new byte[0];
        }
        this.backgroundSha256 = pngMeta != null ? pngMeta.sha256 : "";
        this.backgroundWidth = pngMeta != null ? pngMeta.width : 0;
        this.backgroundHeight = pngMeta != null ? pngMeta.height : 0;
        this.objectiveLayout = objectiveLayout;
        this.objectiveMode = objectiveMode == null ? "" : objectiveMode;
        this.objectiveLane = objectiveLane == null ? "" : objectiveLane;
        this.objectiveSeed = objectiveSeed;
    }

    public static ESPointsMapSnapshot load(Path esConfigDir) throws IOException {
        Path tacticalPath = esConfigDir.resolve(TACTICAL_MAP_FILE);
        if (!Files.isRegularFile(tacticalPath)) {
            throw new IOException("缺少 " + TACTICAL_MAP_FILE);
        }
        String tacticalJson = Files.readString(tacticalPath, StandardCharsets.UTF_8);
        JsonObject tacticalObj = JsonParser.parseString(tacticalJson).getAsJsonObject();

        String bgImage = "";
        if (tacticalObj.has("backgroundImage")) {
            bgImage = tacticalObj.get("backgroundImage").getAsString();
        }

        // 安全校验：背景图路径不得包含 ".." 或绝对路径
        if (!bgImage.isEmpty()) {
            if (bgImage.contains("..") || bgImage.contains("\\") || bgImage.startsWith("/")) {
                throw new IOException("backgroundImage 必须是安全相对路径，禁止 \"..\" 或绝对路径");
            }
        }

        Path capturePath = esConfigDir.resolve(CAPTURE_POINTS_FILE);
        if (!Files.isRegularFile(capturePath)) {
            throw new IOException("缺少 " + CAPTURE_POINTS_FILE);
        }
        String captureJson = Files.readString(capturePath, StandardCharsets.UTF_8);

        JsonObject captureObj = JsonParser.parseString(captureJson).getAsJsonObject();
        ObjectiveLayout objectiveLayout = ObjectiveLayout.parse(captureObj);

        byte[] bgBytes = null;
        PngMetadata pngMeta = null;
        if (!bgImage.isEmpty()) {
            Path bgPath = esConfigDir.resolve(bgImage);
            if (!Files.isRegularFile(bgPath)) {
                throw new IOException("背景图文件不存在: " + bgImage);
            }
            bgBytes = Files.readAllBytes(bgPath);
            pngMeta = validatePng(bgBytes, bgPath.getFileName().toString());
        }

        return new ESPointsMapSnapshot(tacticalJson, captureJson, bgImage, bgBytes,
            pngMeta, objectiveLayout, "", "", 0L);
    }

    /**
     * Builds the immutable ESPoints payload for one round. The source map
     * configuration remains unchanged and can be reused by later matches.
     */
    public ESPointsMapSnapshot forRound(long seed) {
        ObjectiveLayout.Selection selection = objectiveLayout.select(seed);
        PngMetadata pngMeta = hasBackground()
            ? new PngMetadata(backgroundWidth, backgroundHeight, backgroundSha256)
            : null;
        return new ESPointsMapSnapshot(
            tacticalMapJson,
            selection.capturePointsJson(),
            backgroundImage,
            background,
            pngMeta,
            objectiveLayout,
            selection.mode(),
            selection.laneId(),
            selection.seed()
        );
    }

    public boolean hasBackground() {
        return !backgroundImage.isEmpty() && background.length > 0;
    }

    /** 返回背景图字节数据的防御性拷贝。 */
    public byte[] backgroundBytes() {
        byte[] copy = new byte[background.length];
        System.arraycopy(background, 0, copy, 0, background.length);
        return copy;
    }

    private static PngMetadata validatePng(byte[] data, String fileName) throws IOException {
        if (data.length < 24) {
            throw new IOException(fileName + " 不是有效的 PNG 文件（数据太短）");
        }
        // PNG 签名
        byte[] signature = new byte[]{(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a};
        for (int i = 0; i < 8; i++) {
            if (data[i] != signature[i]) {
                throw new IOException(fileName + " 不是有效的 PNG 文件（签名不匹配）");
            }
        }
        // IHDR 在偏移 16 处：宽(4) 高(4)
        int width = readInt(data, 16);
        int height = readInt(data, 20);
        if (width <= 0 || height <= 0) {
            throw new IOException(fileName + " 的 PNG 尺寸无效 (" + width + "x" + height + ")");
        }
        if (width > MAX_BGR_SIDE || height > MAX_BGR_SIDE) {
            throw new IOException(fileName + " 的 PNG 单边最多 " + MAX_BGR_SIDE
                + " 像素，实际 " + Math.max(width, height));
        }
        long pixels = (long) width * (long) height;
        if (pixels > MAX_BGR_PIXELS) {
            throw new IOException(fileName + " 的 PNG 像素数超限 (" + pixels + " > " + MAX_BGR_PIXELS + ")");
        }

        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(data);
            String sha256 = HexFormat.of().formatHex(hash);
            return new PngMetadata(width, height, sha256);
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 不可用", e);
        }
    }

    private static int readInt(byte[] data, int offset) {
        return ((data[offset] & 0xFF) << 24)
            | ((data[offset + 1] & 0xFF) << 16)
            | ((data[offset + 2] & 0xFF) << 8)
            | (data[offset + 3] & 0xFF);
    }

    private record PngMetadata(int width, int height, String sha256) {}
}
