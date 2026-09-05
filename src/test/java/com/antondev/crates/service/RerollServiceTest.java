package com.antondev.crates.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class RerollServiceTest {
    @Test
    void rerollExcludesShownCandidatesAndStopsAtAllowance() {
        var now = Instant.parse("2026-09-05T00:00:00Z");
        var policy = new RerollService.Policy(
                true, 2, RerollService.CostType.TOKEN, 1, true, 15,
                RerollService.TimeoutPolicy.ACCEPT_CURRENT);
        var offer = RerollService.start(policy, "a", List.of("a", "b", "c"), now);

        var first = RerollService.reroll(policy, offer, List.of("a", "b", "c"), 0, now)
                .orElseThrow();
        assertEquals("b", first.candidate());
        var second = RerollService.reroll(policy, first, List.of("a", "b", "c"), 0, now)
                .orElseThrow();
        assertEquals("c", second.candidate());
        assertTrue(RerollService.reroll(policy, second, List.of("a", "b", "c"), 0,
                now).isEmpty());
        assertEquals(List.of("a"), RerollService.replacementCandidates(
                policy, second, List.of("a", "b", "c", "a")));
    }

    @Test
    void timedOutOffersAreAcceptedWithoutAnotherReroll() {
        var now = Instant.parse("2026-09-05T00:00:00Z");
        var policy = RerollService.recommended();
        var offer = RerollService.start(policy, "a", List.of("a", "b"), now);
        var decision = RerollService.accept(policy, offer, now.plusSeconds(15));
        assertTrue(decision.timedOut());
        assertFalse(decision.rerolled());
    }
}
