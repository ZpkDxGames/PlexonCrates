package com.antondev.crates.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class AlternativeRewardResolverTest {
    @Test
    void resolvesOneSafeFallbackEdge() {
        var nodes = Map.of(
                "primary", new AlternativeRewardResolver.Node<>(
                        "primary", "A", "fallback",
                        Set.of(AlternativeRewardResolver.Reason.PERMISSION)),
                "fallback", new AlternativeRewardResolver.Node<>(
                        "fallback", "B", null, Set.of()));

        assertTrue(AlternativeRewardResolver.validate(nodes).isEmpty());
        var resolved = new AlternativeRewardResolver<String>().resolve(
                nodes, "primary", AlternativeRewardResolver.Reason.PERMISSION).orElseThrow();
        assertEquals("fallback", resolved.selectedId());
        assertEquals("B", resolved.value());
        assertTrue(resolved.fallback());
    }

    @Test
    void rejectsUnsafeAndChainedFallbacks() {
        var chained = Map.of(
                "a", new AlternativeRewardResolver.Node<>(
                        "a", "A", "b", Set.of(AlternativeRewardResolver.Reason.TRANSACTION_FAILURE)),
                "b", new AlternativeRewardResolver.Node<>(
                        "b", "B", "c", Set.of(AlternativeRewardResolver.Reason.PLAYER_LIMIT)),
                "c", new AlternativeRewardResolver.Node<>(
                        "c", "C", null, Set.of()));
        var errors = AlternativeRewardResolver.validate(chained);
        assertTrue(errors.contains("ALTERNATIVE_UNSAFE_REASON:a"));
        assertTrue(errors.contains("ALTERNATIVE_DEPTH:a"));
    }
}
