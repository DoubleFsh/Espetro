package org.espetro.client.aui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuiScreenHostTest {

    @Test
    void hostPathIsOreThemedScreenNotOverlay() {
        assertEquals("screens/host.html", AuiScreen.HOST_PATH);
    }

    @Test
    void widgetTreeWorksWithoutDocumentForHudPaint() {
        GuiElement root = new GuiElement(8, 8, 104, 22);
        assertNull(root.node());
        assertEquals(8, root.getX());
        assertEquals(22, root.getHeight());
        assertTrue(root.isVisible());
        root.setVisible(false);
        assertFalse(root.isVisible());
    }
}
