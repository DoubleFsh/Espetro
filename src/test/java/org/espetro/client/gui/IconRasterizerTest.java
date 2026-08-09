package org.espetro.client.gui;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IconRasterizerTest {
    @Test
    void transparentPaddingDoesNotShrinkTheVisibleSprite() {
        int[] source = new int[6 * 6];
        for (int y = 2; y < 4; y++) {
            for (int x = 2; x < 4; x++) {
                source[y * 6 + x] = 0xFF0000FF;
            }
        }

        int[] output = IconRasterizer.fitAbgr(source, 6, 6, 4, 4);

        assertTrue(Arrays.stream(output).allMatch(pixel -> pixel == 0xFF0000FF));
    }

    @Test
    void rectangularSpriteKeepsItsAspectRatioAndIsCentered() {
        int[] source = new int[4 * 2];
        Arrays.fill(source, 0xFF00FFFF);

        int[] output = IconRasterizer.fitAbgr(source, 4, 2, 4, 4);

        for (int x = 0; x < 4; x++) {
            assertEquals(0, output[x]);
            assertEquals(0xFF00FFFF, output[4 + x]);
            assertEquals(0xFF00FFFF, output[8 + x]);
            assertEquals(0, output[12 + x]);
        }
    }

    @Test
    void premultipliedFilterKeepsColorAtTranslucentEdges() {
        int[] source = { 0xFF0000FF, 0x010000FF };

        int pixel = IconRasterizer.fitAbgr(source, 2, 1, 1, 1)[0];

        assertEquals(128, (pixel >>> 24) & 0xFF);
        assertEquals(255, pixel & 0xFF);
        assertEquals(0, (pixel >>> 8) & 0xFF);
        assertEquals(0, (pixel >>> 16) & 0xFF);
    }
}
