package org.espetro.client.gui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class CurrentMapBackgroundRendererTest {

    @Test
    void cropsWideTextureAtItsHorizontalCenter() {
        assertEquals(new CurrentMapBackgroundRenderer.Crop(80, 0, 160, 180),
            CurrentMapBackgroundRenderer.aspectFillCrop(320, 180, 800, 900));
    }

    @Test
    void cropsTallTextureAtItsVerticalCenter() {
        assertEquals(new CurrentMapBackgroundRenderer.Crop(0, 50, 200, 100),
            CurrentMapBackgroundRenderer.aspectFillCrop(200, 200, 800, 400));
    }

    @Test
    void blurKeepsUniformColorUnchanged() {
        int color = 0xFF315A9C;
        int[] pixels = {color, color, color, color, color, color};
        assertArrayEquals(pixels,
            CurrentMapBackgroundRenderer.boxBlur(pixels, 3, 2, 5));
    }
}
