package com.antondev.crates.gui.player;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PlayerCrateLayoutTest {
    @Test void hallAndPreviewExposeTwentyEightContentSlots() {
        assertEquals(28, PlayerCrateLayout.contentSlots().size());
    }

    @Test void contentSlotsNeverOverlapBottomNavigationRow() {
        assertTrue(PlayerCrateLayout.contentSlots().stream().allMatch(slot -> slot < 45));
    }

    @Test void bottomNavigationMatchesSharedPlexonConvention() {
        assertEquals(45, PlayerCrateLayout.PREVIOUS);
        assertEquals(46, PlayerCrateLayout.CONTEXT);
        assertEquals(47, PlayerCrateLayout.PAYMENT);
        assertEquals(48, PlayerCrateLayout.BACK);
        assertEquals(49, PlayerCrateLayout.PRIMARY);
        assertEquals(50, PlayerCrateLayout.SECONDARY);
        assertEquals(51, PlayerCrateLayout.STATUS);
        assertEquals(52, PlayerCrateLayout.CLOSE);
        assertEquals(53, PlayerCrateLayout.NEXT);
    }

    @Test void paginationClampsEmptyAndOverflowPages() {
        assertEquals(1, PlayerCrateLayout.pageCount(0));
        assertEquals(1, PlayerCrateLayout.pageCount(28));
        assertEquals(2, PlayerCrateLayout.pageCount(29));
        assertEquals(0, PlayerCrateLayout.clampPage(-10, 3));
        assertEquals(1, PlayerCrateLayout.clampPage(99, 29));
    }
}
