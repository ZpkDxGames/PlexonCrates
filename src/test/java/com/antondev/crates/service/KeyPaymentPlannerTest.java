package com.antondev.crates.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.antondev.crates.domain.key.KeyPaymentPolicy;
import java.util.List;
import org.junit.jupiter.api.Test;

class KeyPaymentPlannerTest {
    private static final List<KeyPaymentPlanner.Availability> SOURCES = List.of(
            new KeyPaymentPlanner.Availability("basic", 2, 8, 0),
            new KeyPaymentPlanner.Availability("rare", 10, 1, 1));

    @Test
    void priorityPoliciesChooseOneCompleteSourceBeforeMixing() {
        var physical = KeyPaymentPlanner.plan(KeyPaymentPolicy.PHYSICAL_FIRST,
                KeyPaymentPlanner.Preference.PHYSICAL, true, 5, SOURCES).orElseThrow();
        assertEquals("rare", physical.keyId());
        assertEquals(5, physical.physical());
        assertEquals(0, physical.virtual());

        var virtual = KeyPaymentPlanner.plan(KeyPaymentPolicy.VIRTUAL_FIRST,
                KeyPaymentPlanner.Preference.PHYSICAL, true, 5, SOURCES).orElseThrow();
        assertEquals("basic", virtual.keyId());
        assertEquals(0, virtual.physical());
        assertEquals(5, virtual.virtual());
    }

    @Test
    void playerChoiceNeverSilentlyUsesTheUnselectedSource() {
        var physicalOnly = List.of(new KeyPaymentPlanner.Availability("basic", 1, 10, 0));
        assertTrue(KeyPaymentPlanner.plan(KeyPaymentPolicy.PLAYER_CHOICE,
                KeyPaymentPlanner.Preference.PHYSICAL, false, 3, physicalOnly).isEmpty());
        var selected = KeyPaymentPlanner.plan(KeyPaymentPolicy.PLAYER_CHOICE,
                KeyPaymentPlanner.Preference.VIRTUAL, false, 3, physicalOnly).orElseThrow();
        assertEquals(0, selected.physical());
        assertEquals(3, selected.virtual());
    }

    @Test
    void mixedPaymentUsesOneKeyIdAndAnExactDisplayedSplit() {
        var sources = List.of(new KeyPaymentPlanner.Availability("basic", 2, 3, 0));
        assertTrue(KeyPaymentPlanner.plan(KeyPaymentPolicy.PHYSICAL_FIRST,
                KeyPaymentPlanner.Preference.PHYSICAL, false, 5, sources).isEmpty());
        var mixed = KeyPaymentPlanner.plan(KeyPaymentPolicy.PHYSICAL_FIRST,
                KeyPaymentPlanner.Preference.PHYSICAL, true, 5, sources).orElseThrow();
        assertEquals(2, mixed.physical());
        assertEquals(3, mixed.virtual());
        assertEquals(5, mixed.total());
    }
}
