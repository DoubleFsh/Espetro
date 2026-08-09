package org.espetro.client.gui;

/**
 * Converts an ABGR sprite into a small, aspect-fitted bitmap without allowing
 * transparent padding to consume the available HUD pixels.
 */
final class IconRasterizer {
    private IconRasterizer() {
    }

    static int[] fitAbgr(int[] source, int sourceWidth, int sourceHeight,
                         int targetWidth, int targetHeight) {
        int safeTargetWidth = Math.max(1, targetWidth);
        int safeTargetHeight = Math.max(1, targetHeight);
        int[] output = new int[safeTargetWidth * safeTargetHeight];
        if (source == null || sourceWidth <= 0 || sourceHeight <= 0
            || (long) source.length < (long) sourceWidth * sourceHeight) {
            return output;
        }

        Bounds bounds = opaqueBounds(source, sourceWidth, sourceHeight);
        if (bounds == null) return output;

        AspectFit.Size fitted = AspectFit.within(
            bounds.width(), bounds.height(), safeTargetWidth, safeTargetHeight);
        int offsetX = (safeTargetWidth - fitted.width()) / 2;
        int offsetY = (safeTargetHeight - fitted.height()) / 2;

        for (int targetY = 0; targetY < fitted.height(); targetY++) {
            double sourceTop = bounds.minY
                + targetY * bounds.height() / (double) fitted.height();
            double sourceBottom = bounds.minY
                + (targetY + 1) * bounds.height() / (double) fitted.height();
            for (int targetX = 0; targetX < fitted.width(); targetX++) {
                double sourceLeft = bounds.minX
                    + targetX * bounds.width() / (double) fitted.width();
                double sourceRight = bounds.minX
                    + (targetX + 1) * bounds.width() / (double) fitted.width();
                output[(offsetY + targetY) * safeTargetWidth + offsetX + targetX] =
                    sampleArea(source, sourceWidth, sourceHeight,
                        sourceLeft, sourceTop, sourceRight, sourceBottom);
            }
        }
        return output;
    }

    private static Bounds opaqueBounds(int[] source, int width, int height) {
        int minX = width;
        int minY = height;
        int maxX = -1;
        int maxY = -1;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                if ((source[y * width + x] >>> 24) == 0) continue;
                minX = Math.min(minX, x);
                minY = Math.min(minY, y);
                maxX = Math.max(maxX, x);
                maxY = Math.max(maxY, y);
            }
        }
        return maxX < minX ? null : new Bounds(minX, minY, maxX + 1, maxY + 1);
    }

    /** Box filter with premultiplied alpha, preserving color at translucent edges. */
    private static int sampleArea(int[] source, int width, int height,
                                  double left, double top, double right, double bottom) {
        int firstX = Math.max(0, (int) Math.floor(left));
        int lastX = Math.min(width - 1, (int) Math.ceil(right) - 1);
        int firstY = Math.max(0, (int) Math.floor(top));
        int lastY = Math.min(height - 1, (int) Math.ceil(bottom) - 1);
        double totalWeight = 0.0D;
        double alphaWeight = 0.0D;
        double channel0 = 0.0D;
        double channel1 = 0.0D;
        double channel2 = 0.0D;

        for (int y = firstY; y <= lastY; y++) {
            double yWeight = Math.max(0.0D,
                Math.min(bottom, y + 1.0D) - Math.max(top, y));
            for (int x = firstX; x <= lastX; x++) {
                double xWeight = Math.max(0.0D,
                    Math.min(right, x + 1.0D) - Math.max(left, x));
                double weight = xWeight * yWeight;
                if (weight <= 0.0D) continue;

                int pixel = source[y * width + x];
                int alpha = (pixel >>> 24) & 0xFF;
                double weightedAlpha = alpha * weight;
                totalWeight += weight;
                alphaWeight += weightedAlpha;
                channel0 += (pixel & 0xFF) * weightedAlpha;
                channel1 += ((pixel >>> 8) & 0xFF) * weightedAlpha;
                channel2 += ((pixel >>> 16) & 0xFF) * weightedAlpha;
            }
        }

        if (totalWeight <= 0.0D || alphaWeight <= 0.0D) return 0;
        int alpha = clampColor((int) Math.round(alphaWeight / totalWeight));
        int c0 = clampColor((int) Math.round(channel0 / alphaWeight));
        int c1 = clampColor((int) Math.round(channel1 / alphaWeight));
        int c2 = clampColor((int) Math.round(channel2 / alphaWeight));
        return (alpha << 24) | (c2 << 16) | (c1 << 8) | c0;
    }

    private static int clampColor(int value) {
        return Math.max(0, Math.min(255, value));
    }

    private record Bounds(int minX, int minY, int maxX, int maxY) {
        int width() {
            return maxX - minX;
        }

        int height() {
            return maxY - minY;
        }
    }
}
