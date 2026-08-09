package org.espetro.client.gui;

/** Integer-only aspect-ratio fitting shared by image-backed MUtil widgets. */
final class AspectFit {
    private AspectFit() {
    }

    static Size within(int sourceWidth, int sourceHeight, int maxWidth, int maxHeight) {
        int safeMaxWidth = Math.max(1, maxWidth);
        int safeMaxHeight = Math.max(1, maxHeight);
        if (sourceWidth <= 0 || sourceHeight <= 0) {
            return new Size(safeMaxWidth, safeMaxHeight);
        }

        if ((long) safeMaxWidth * sourceHeight <= (long) safeMaxHeight * sourceWidth) {
            int height = Math.max(1, (int) ((long) safeMaxWidth * sourceHeight / sourceWidth));
            return new Size(safeMaxWidth, height);
        }

        int width = Math.max(1, (int) ((long) safeMaxHeight * sourceWidth / sourceHeight));
        return new Size(width, safeMaxHeight);
    }

    record Size(int width, int height) {
    }
}
