package com.antondev.crates.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class ChanceAllocatorTest {
    @Test
    void migratesLegacyWeightsWithStableLargestRemainder() {
        var result = ChanceAllocator.fromWeights(List.of(
                new ChanceAllocator.WeightedChance("first", BigDecimal.ONE),
                new ChanceAllocator.WeightedChance("second", BigDecimal.ONE),
                new ChanceAllocator.WeightedChance("third", BigDecimal.ONE)));

        assertEquals(10_000, result.total());
        assertEquals(3_334, result.basisPoints("first"));
        assertEquals(3_333, result.basisPoints("second"));
        assertEquals(3_333, result.basisPoints("third"));
    }

    @Test
    void newRewardGetsTenPercentAndExistingRatioIsPreserved() {
        var result = ChanceAllocator.addReward(List.of(
                chance("common", 7_000), chance("rare", 3_000)), "new_reward");

        assertEquals(6_300, result.basisPoints("common"));
        assertEquals(2_700, result.basisPoints("rare"));
        assertEquals(1_000, result.basisPoints("new_reward"));
        assertEquals(10_000, result.total());
    }

    @Test
    void newRewardNeverChangesLockedChances() {
        var result = ChanceAllocator.addReward(List.of(
                new ChanceAllocator.Chance("locked", 2_000, true),
                chance("common", 5_000), chance("rare", 3_000)), "new_reward");

        assertEquals(2_000, result.basisPoints("locked"));
        assertEquals(4_375, result.basisPoints("common"));
        assertEquals(2_625, result.basisPoints("rare"));
        assertEquals(1_000, result.basisPoints("new_reward"));
    }

    @Test
    void firstRewardGetsTheCompletePool() {
        var result = ChanceAllocator.addReward(List.of(), "first");
        assertEquals(10_000, result.basisPoints("first"));
    }

    @Test
    void firstPositiveRewardLeavesPreparedZeroEntriesAtZero() {
        var result = ChanceAllocator.addReward(List.of(chance("disabled", 0)), "first_live");

        assertEquals(0, result.basisPoints("disabled"));
        assertEquals(10_000, result.basisPoints("first_live"));
    }

    @Test
    void exactEditPreservesLockedValuesAndScalesUnlockedValues() {
        var result = ChanceAllocator.setChance(List.of(
                chance("edited", 5_000),
                new ChanceAllocator.Chance("locked", 2_000, true),
                chance("large", 2_000),
                chance("small", 1_000)), "edited", 4_000);

        assertEquals(4_000, result.basisPoints("edited"));
        assertEquals(2_000, result.basisPoints("locked"));
        assertEquals(2_667, result.basisPoints("large"));
        assertEquals(1_333, result.basisPoints("small"));
        assertEquals(10_000, result.total());
    }

    @Test
    void impossibleExactEditExplainsLockedPoolConflict() {
        assertThrows(IllegalArgumentException.class, () -> ChanceAllocator.setChance(List.of(
                chance("edited", 5_000),
                new ChanceAllocator.Chance("locked", 6_000, true),
                chance("other", 0)), "edited", 5_000));
    }

    @Test
    void eligibleNormalizationProducesExactTicketBoundaries() {
        var eligible = ChanceAllocator.normalize(List.of(chance("stone", 7_000), chance("diamond", 2_000)));

        assertEquals(7_778, eligible.basisPoints("stone"));
        assertEquals(2_222, eligible.basisPoints("diamond"));
        assertEquals("stone", ChanceAllocator.selectTicket(eligible.chances(), 0));
        assertEquals("stone", ChanceAllocator.selectTicket(eligible.chances(), 7_777));
        assertEquals("diamond", ChanceAllocator.selectTicket(eligible.chances(), 7_778));
        assertEquals("diamond", ChanceAllocator.selectTicket(eligible.chances(), 9_999));
    }

    @Test
    void zeroChanceEntryRemainsUnreachable() {
        var eligible = ChanceAllocator.normalize(List.of(
                chance("live", 10_000), chance("prepared", 0)));

        assertEquals(0, eligible.basisPoints("prepared"));
        assertEquals("live", ChanceAllocator.selectTicket(eligible.chances(), 9_999));
    }

    @Test
    void equalAndNormalizeUnlockedAlwaysFinishAtOneHundredPercent() {
        var equal = ChanceAllocator.equalize(List.of(
                chance("one", 9_000), chance("two", 900), chance("three", 100)));
        assertEquals(List.of(3_334, 3_333, 3_333),
                equal.chances().stream().map(ChanceAllocator.Chance::basisPoints).toList());

        var unlocked = ChanceAllocator.normalizeUnlocked(List.of(
                new ChanceAllocator.Chance("fixed", 2_500, true),
                chance("one", 3_000), chance("two", 1_000)));
        assertEquals(2_500, unlocked.basisPoints("fixed"));
        assertEquals(5_625, unlocked.basisPoints("one"));
        assertEquals(1_875, unlocked.basisPoints("two"));
        assertEquals(10_000, unlocked.total());
    }

    @Test
    void displayFormattingNeverControlsTheAllocator() {
        assertEquals("0.01%", ChanceAllocator.formatBasisPoints(1));
        assertEquals("100.00%", ChanceAllocator.formatBasisPoints(10_000));
    }

    private static ChanceAllocator.Chance chance(String id, int basisPoints) {
        return new ChanceAllocator.Chance(id, basisPoints, false);
    }
}
