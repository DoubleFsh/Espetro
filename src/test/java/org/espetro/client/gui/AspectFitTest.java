package org.espetro.client.gui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AspectFitTest {
    @Test
    void wideFactionImageKeepsItsOriginalAspectRatio() {
        AspectFit.Size size = AspectFit.within(2199, 885, 192, 108);

        assertEquals(192, size.width());
        assertEquals(77, size.height());
    }

    @Test
    void tallImageIsLimitedByAvailableHeight() {
        AspectFit.Size size = AspectFit.within(100, 200, 192, 108);

        assertEquals(54, size.width());
        assertEquals(108, size.height());
    }

    @Test
    void invalidDimensionsRemainSafeAndVisible() {
        AspectFit.Size size = AspectFit.within(0, 0, 192, 108);

        assertEquals(192, size.width());
        assertEquals(108, size.height());
    }
}
