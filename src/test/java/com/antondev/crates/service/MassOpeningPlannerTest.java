package com.antondev.crates.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class MassOpeningPlannerTest {
    @Test
    void maximumAvailableCannotOverflowWhenBothBalancesAreLarge() {
        var limits = new MassOpeningPlanner.Limits(
                Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE,
                Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE);
        assertEquals(Integer.MAX_VALUE, MassOpeningPlanner.maximumAvailable(limits));
    }

    @Test
    void requireFullPlansAreAllOrNothing() {
        var limits = new MassOpeningPlanner.Limits(5, 5, 0, 5, 5, 5, 4);
        var plan = MassOpeningPlanner.plan(limits, true);
        assertEquals(0, plan.executable());
        assertTrue(!plan.complete());
        assertEquals(Optional.empty(), MassOpeningPlanner.selectSequential(
                3, index -> index == 1 ? Optional.empty() : Optional.of(index), true));
    }

    @Test
    void partialSelectionRetainsOnlySuccessfullySelectedEntries() {
        assertEquals(List.of(0), MassOpeningPlanner.selectSequential(
                3, index -> index == 1 ? Optional.empty() : Optional.of(index), false).orElseThrow());
    }
}
