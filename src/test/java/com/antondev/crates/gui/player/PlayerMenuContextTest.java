package com.antondev.crates.gui.player;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.antondev.crates.service.KeyPaymentPlanner;
import org.junit.jupiter.api.Test;

class PlayerMenuContextTest {
    @Test void previewRetainsHallAndRewardPage() {
        PlayerMenuContext context = PlayerMenuContext.preview("basic", 3, 2);
        assertEquals(3, context.hallPage());
        assertEquals(2, context.page());
    }

    @Test void paymentToggleDoesNotLoseNavigationOrigin() {
        PlayerMenuContext context = PlayerMenuContext.preview("basic", 4, 1)
                .withPaymentPreference(KeyPaymentPlanner.Preference.VIRTUAL);
        assertEquals(4, context.hallPage());
        assertEquals(1, context.page());
        assertEquals(KeyPaymentPlanner.Preference.VIRTUAL, context.paymentPreference());
    }

    @Test void quantityAndConfirmationRetainPreviewOrigin() {
        PlayerMenuContext context = PlayerMenuContext.preview("rare", 2, 3).quantity().massConfirm(10);
        assertEquals(PlayerMenuContext.Screen.MASS_CONFIRM, context.screen());
        assertEquals(2, context.hallPage());
        assertEquals(3, context.page());
        assertEquals(10, context.amount());
    }

    @Test void selectiveConfirmationRetainsRewardPageAndHiddenSelection() {
        PlayerMenuContext context = PlayerMenuContext.preview("epic", 1, 2).selectiveConfirm("reward_internal");
        assertEquals(PlayerMenuContext.Screen.SELECTIVE_CONFIRM, context.screen());
        assertEquals(1, context.hallPage());
        assertEquals(2, context.page());
        assertEquals("reward_internal", context.rewardId());
    }
}
