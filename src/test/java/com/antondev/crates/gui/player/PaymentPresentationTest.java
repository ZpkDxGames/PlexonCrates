package com.antondev.crates.gui.player;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.antondev.crates.domain.key.KeyPaymentPolicy;
import com.antondev.crates.service.KeyPaymentPlanner;
import java.util.List;
import org.junit.jupiter.api.Test;

class PaymentPresentationTest {
    private static KeyPaymentPlanner.Availability availability(int physical, long virtual) {
        return new KeyPaymentPlanner.Availability("basic", physical, virtual, 0);
    }

    @Test void physicalOnlyShowsPhysicalAuthority() {
        var view = PaymentPresentation.resolve(KeyPaymentPolicy.PHYSICAL_ONLY, false, 2,
                List.of(availability(5, 99)), KeyPaymentPlanner.Preference.VIRTUAL, false);
        assertTrue(view.sufficient());
        assertFalse(view.choiceVisible());
        assertEquals("Physical key", view.sourceLabel());
        assertEquals(5, view.available());
    }

    @Test void virtualOnlyShowsVirtualAuthority() {
        var view = PaymentPresentation.resolve(KeyPaymentPolicy.VIRTUAL_ONLY, false, 2,
                List.of(availability(99, 7)), KeyPaymentPlanner.Preference.PHYSICAL, false);
        assertTrue(view.sufficient());
        assertEquals("Virtual key", view.sourceLabel());
        assertEquals(7, view.available());
    }

    @Test void playerChoiceAppearsOnlyWhenBothChoicesCanPay() {
        var view = PaymentPresentation.resolve(KeyPaymentPolicy.PLAYER_CHOICE, false, 2,
                List.of(availability(4, 6)), KeyPaymentPlanner.Preference.PHYSICAL, false);
        assertTrue(view.choiceVisible());
        assertEquals(KeyPaymentPlanner.Preference.PHYSICAL, view.preference());
    }

    @Test void playerChoiceAutoUsesOnlyPayableSourceWhenOtherCannotPay() {
        var view = PaymentPresentation.resolve(KeyPaymentPolicy.PLAYER_CHOICE, false, 3,
                List.of(availability(0, 5)), KeyPaymentPlanner.Preference.PHYSICAL, false);
        assertTrue(view.sufficient());
        assertFalse(view.choiceVisible());
        assertEquals(KeyPaymentPlanner.Preference.VIRTUAL, view.preference());
        assertEquals("Virtual key", view.sourceLabel());
    }

    @Test void insufficientPaymentIsExplicit() {
        var view = PaymentPresentation.resolve(KeyPaymentPolicy.PHYSICAL_ONLY, false, 4,
                List.of(availability(2, 0)), KeyPaymentPlanner.Preference.PHYSICAL, false);
        assertFalse(view.sufficient());
        assertEquals(2, view.available());
    }

    @Test void bypassNeverInventsAKeyBalance() {
        var view = PaymentPresentation.resolve(KeyPaymentPolicy.PHYSICAL_ONLY, false, 4,
                List.of(), KeyPaymentPlanner.Preference.PHYSICAL, true);
        assertTrue(view.sufficient());
        assertFalse(view.choiceVisible());
        assertEquals("No key required", view.sourceLabel());
    }
}
