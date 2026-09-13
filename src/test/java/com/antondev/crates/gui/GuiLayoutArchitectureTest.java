package com.antondev.crates.gui;

import static org.junit.jupiter.api.Assertions.*;
import java.util.HashSet;
import org.junit.jupiter.api.Test;

class GuiLayoutArchitectureTest {
    @Test void listGridAndFooterAreCollisionFree() {
        assertEquals(28, GuiLayout.LIST_54.contentSlots().size());
        assertEquals(9, GuiNavigation.FOOTER.size());
        var collision = new HashSet<>(GuiLayout.LIST_54.contentSlots());
        collision.retainAll(GuiLayout.LIST_54.frameSlots());
        assertTrue(collision.isEmpty());
        assertEquals(45, GuiNavigation.PREVIOUS);
        assertEquals(53, GuiNavigation.NEXT);
        assertEquals(48, GuiNavigation.BACK);
        assertEquals(52, GuiNavigation.CLOSE);
    }

    @Test void panelColumnsNeverOverlapDeclaredContent() {
        var collision = new HashSet<>(GuiLayout.PANEL_54.contentSlots());
        collision.retainAll(GuiLayout.PANEL_54.separatorSlots());
        assertTrue(collision.isEmpty());
        assertEquals(54, GuiLayout.PANEL_54.size());
    }

    @Test void dialogsKeepSymmetricDecisionSlots() {
        assertTrue(GuiLayout.DIALOG_27.contentSlots().containsAll(java.util.List.of(11, 13, 15)));
        assertEquals(27, GuiLayout.DIALOG_27.size());
    }
}
