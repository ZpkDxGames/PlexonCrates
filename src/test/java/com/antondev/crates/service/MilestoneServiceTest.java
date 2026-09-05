package com.antondev.crates.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class MilestoneServiceTest {
    @Test
    void oneTimeThresholdIsEarnedOnlyWhenCrossed() {
        var definition = new MilestoneService.Definition("first", 3);
        var before = MilestoneService.Progress.empty();

        var partial = MilestoneService.advance(before, 2, List.of(definition));
        assertTrue(partial.newlyEarned().isEmpty());
        assertEquals(2, partial.after().openings());

        var crossed = MilestoneService.advance(partial.after(), 1, List.of(definition));
        assertEquals(List.of("first#0"), crossed.newlyEarned().stream()
                .map(MilestoneService.Earned::key).toList());
        assertTrue(MilestoneService.next(crossed.after(), List.of(definition)).isEmpty());
    }

    @Test
    void repeatingThresholdsEmitEveryCrossedCycleInOrder() {
        var definition = new MilestoneService.Definition(
                "repeat", 2, MilestoneService.RepeatPolicy.REPEATING, 2);
        var evaluation = MilestoneService.advance(
                MilestoneService.Progress.empty(), 7, List.of(definition));

        assertEquals(List.of("repeat#0", "repeat#1", "repeat#2"),
                evaluation.newlyEarned().stream().map(MilestoneService.Earned::key).toList());
        assertEquals(7, evaluation.after().openings());
        assertEquals("repeat#3",
                MilestoneService.next(evaluation.after(), List.of(definition)).orElseThrow().id()
                        + "#3");
    }
}
