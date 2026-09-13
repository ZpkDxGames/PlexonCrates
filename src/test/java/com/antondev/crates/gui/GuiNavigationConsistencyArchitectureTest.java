package com.antondev.crates.gui;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class GuiNavigationConsistencyArchitectureTest {
    @Test void footerUses65Contract() {
        assertArrayEquals(new Integer[]{45,46,47,48,49,50,51,52,53}, GuiNavigation.FOOTER.toArray(Integer[]::new));
    }
}
