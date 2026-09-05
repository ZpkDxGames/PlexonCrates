package com.antondev.crates.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.antondev.crates.database.DatabaseService;
import com.antondev.crates.domain.crate.AnimationType;
import com.antondev.crates.domain.crate.CrateState;
import com.antondev.crates.domain.opening.OpenSource;
import com.antondev.crates.domain.reward.PityPolicy;
import com.antondev.crates.domain.reward.RewardLimits;
import com.antondev.crates.domain.reward.RewardRarity;
import com.antondev.crates.domain.reward.RewardPresentation;
import com.antondev.crates.model.Crate;
import com.antondev.crates.model.CrateReward;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

class RewardStateServiceTest {
    private static final long NOW = 1_000_000L;

    @BeforeEach
    void setUp() {
        MockBukkit.mock();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void playerLifetimeLimitClampsSequentialBulkSelection() {
        UUID player = UUID.randomUUID();
        CrateReward limited = reward("limited", 1, RewardRarity.COMMON,
                new RewardLimits(2, 0, 0, 0, 0, 0, 0));
        Crate crate = crate(PityPolicy.disabled(), limited);
        RewardStateService state = service(0);

        RewardStateService.Plan plan = state.plan(player, crate, 10, OpenSource.COMMAND,
                ignored -> true, false, NOW);

        assertEquals(List.of("limited", "limited"), plan.rewards().stream().map(CrateReward::id).toList());
        state.apply(player, crate, plan.rewards(), OpenSource.COMMAND, ignored -> true, false, NOW);
        assertTrue(state.plan(player, crate, 1, OpenSource.COMMAND, ignored -> true, false, NOW).rewards().isEmpty());
    }

    @Test
    void globalLimitAppliesAcrossPlayersAndCanBeExplicitlyBypassed() {
        CrateReward limited = reward("server_prize", 1, RewardRarity.RARE,
                new RewardLimits(0, 0, 0, 1, 0, 0, 0));
        Crate crate = crate(PityPolicy.disabled(), limited);
        RewardStateService state = service(0);
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();

        List<CrateReward> selected = state.plan(first, crate, 1, OpenSource.BLOCK,
                ignored -> true, false, NOW).rewards();
        state.apply(first, crate, selected, OpenSource.BLOCK, ignored -> true, false, NOW);

        assertFalse(state.eligible(second, crate, limited, NOW, false));
        assertTrue(state.eligible(second, crate, limited, NOW, true));
    }

    @Test
    void rewardCooldownExpiresAtItsExactBoundary() {
        UUID player = UUID.randomUUID();
        CrateReward reward = reward("cooldown", 1, RewardRarity.COMMON,
                new RewardLimits(0, 0, 0, 0, 0, 0, 10));
        Crate crate = crate(PityPolicy.disabled(), reward);
        RewardStateService state = service(0);
        List<CrateReward> selected = state.plan(player, crate, 1, OpenSource.GUI,
                ignored -> true, false, NOW).rewards();
        state.apply(player, crate, selected, OpenSource.GUI, ignored -> true, false, NOW);

        assertFalse(state.eligible(player, crate, reward, NOW + 9_999, false));
        assertTrue(state.eligible(player, crate, reward, NOW + 10_000, false));
    }

    @Test
    void pityGuaranteesTheThresholdOutcomeAndResets() {
        UUID player = UUID.randomUUID();
        CrateReward common = reward("common", 100, RewardRarity.COMMON, RewardLimits.unlimited());
        CrateReward jackpot = reward("jackpot", 1, RewardRarity.LEGENDARY, RewardLimits.unlimited());
        Crate crate = crate(new PityPolicy(true, 3, Set.of("jackpot"), null, false), common, jackpot);
        RewardStateService state = service(0);

        RewardStateService.Plan plan = state.plan(player, crate, 3, OpenSource.BLOCK,
                ignored -> true, false, NOW);

        assertEquals(List.of("common", "common", "jackpot"),
                plan.rewards().stream().map(CrateReward::id).toList());
        assertTrue(plan.pityTriggered());
        state.apply(player, crate, plan.rewards(), OpenSource.BLOCK, ignored -> true, false, NOW);
        assertEquals(0, state.pityMisses(player, crate.id()));
        assertEquals(3, state.pityRemaining(player, crate));
    }

    @Test
    void administrativeOpeningsDoNotAdvancePityUnlessConfigured() {
        UUID player = UUID.randomUUID();
        CrateReward common = reward("common", 100, RewardRarity.COMMON, RewardLimits.unlimited());
        CrateReward jackpot = reward("jackpot", 1, RewardRarity.LEGENDARY, RewardLimits.unlimited());
        Crate crate = crate(new PityPolicy(true, 2, Set.of("jackpot"), null, false), common, jackpot);
        RewardStateService state = service(0);

        RewardStateService.Plan plan = state.plan(player, crate, 4, OpenSource.ADMIN_FORCE,
                ignored -> true, true, NOW);
        state.apply(player, crate, plan.rewards(), OpenSource.ADMIN_FORCE, ignored -> true, true, NOW);

        assertEquals(List.of("common", "common", "common", "common"),
                plan.rewards().stream().map(CrateReward::id).toList());
        assertFalse(plan.pityTriggered());
        assertEquals(0, state.pityMisses(player, crate.id()));
    }

    @Test
    void frozenSelectionIsRejectedAfterAnotherOpeningExhaustsItsLimit() {
        UUID player = UUID.randomUUID();
        CrateReward reward = reward("unique", 1, RewardRarity.EPIC,
                new RewardLimits(1, 0, 0, 0, 0, 0, 0));
        Crate crate = crate(PityPolicy.disabled(), reward);
        RewardStateService state = service(0);
        List<CrateReward> first = state.plan(player, crate, 1, OpenSource.GUI,
                ignored -> true, false, NOW).rewards();
        List<CrateReward> stale = state.plan(player, crate, 1, OpenSource.GUI,
                ignored -> true, false, NOW).rewards();

        state.apply(player, crate, first, OpenSource.GUI, ignored -> true, false, NOW);

        assertFalse(state.canApply(player, crate, stale, OpenSource.GUI, ignored -> true, false, NOW));
    }

    @Test
    void selectivePlanIgnoresStoredChanceButKeepsSequentialLimitsAllOrNothing() {
        UUID player = UUID.randomUUID();
        CrateReward selected = reward("selected", 1, RewardRarity.EPIC,
                new RewardLimits(2, 0, 0, 0, 0, 0, 0));
        Crate crate = crate(PityPolicy.disabled(), selected);
        RewardStateService state = service(0.75);

        RewardStateService.Plan accepted = state.planSelected(player, crate, "selected", 2, OpenSource.GUI,
                ignored -> true, false, NOW);
        RewardStateService.Plan rejected = state.planSelected(player, crate, "selected", 3, OpenSource.GUI,
                ignored -> true, false, NOW);

        assertEquals(List.of("selected", "selected"),
                accepted.rewards().stream().map(CrateReward::id).toList());
        assertTrue(rejected.rewards().isEmpty());
        assertTrue(state.planSelected(player, crate, "missing", 1, OpenSource.GUI,
                ignored -> true, false, NOW).rewards().isEmpty());
    }

    @Test
    void sourceTicketTransfersToFallbackAndChargesActualSequentialLimits() {
        UUID player = UUID.randomUUID();
        CrateReward fallback = reward("fallback", 0, RewardRarity.COMMON,
                new RewardLimits(2, 0, 0, 0, 0, 0, 0));
        CrateReward primary = alternativeReward("primary", 100,
                new RewardLimits(1, 0, 0, 0, 0, 0, 0), "fallback",
                Set.of(AlternativeRewardResolver.Reason.PLAYER_LIMIT));
        Crate crate = crate(PityPolicy.disabled(), primary, fallback);
        RewardStateService state = service(0.5);

        RewardStateService.Plan plan = state.planResolved(player, crate, 3, OpenSource.GUI,
                ignored -> null, true, false, NOW);

        assertEquals(List.of("primary", "fallback", "fallback"),
                plan.rewards().stream().map(CrateReward::id).toList());
        assertEquals(List.of("primary", "primary", "primary"),
                plan.outcomes().stream().map(outcome -> outcome.source().id()).toList());
        assertEquals(AlternativeRewardResolver.Reason.PLAYER_LIMIT,
                plan.outcomes().get(1).alternativeReason());
        assertTrue(plan.outcomeDetail().contains("source=primary,actual=fallback"));

        DatabaseService.RewardStateCommit mutation = state.applyResolved(player, crate, plan, OpenSource.GUI,
                ignored -> null, true, false, NOW);
        assertEquals(Set.of("primary", "fallback"), mutation.rewards().stream()
                .map(value -> value.player().rewardId()).collect(java.util.stream.Collectors.toSet()));
        assertTrue(state.planResolved(player, crate, 1, OpenSource.GUI,
                ignored -> null, true, false, NOW).rewards().isEmpty());
    }

    @Test
    void safePermissionAndDateReasonsResolveButIntegrationFailureNeverDoes() {
        UUID player = UUID.randomUUID();
        CrateReward fallback = reward("fallback", 0, RewardRarity.COMMON, RewardLimits.unlimited());
        CrateReward primary = alternativeReward("primary", 100, RewardLimits.unlimited(), "fallback",
                Set.of(AlternativeRewardResolver.Reason.PERMISSION,
                        AlternativeRewardResolver.Reason.DATE_WINDOW));
        Crate crate = crate(PityPolicy.disabled(), primary, fallback);
        RewardStateService state = service(0);

        RewardStateService.Plan permission = state.planResolved(player, crate, 1, OpenSource.GUI,
                reward -> reward.id().equals("primary")
                        ? AlternativeRewardResolver.Reason.PERMISSION : null,
                true, false, NOW);
        assertEquals("fallback", permission.rewards().getFirst().id());
        assertEquals(AlternativeRewardResolver.Reason.PERMISSION,
                permission.outcomes().getFirst().alternativeReason());

        RewardStateService.Plan integration = state.planResolved(player, crate, 1, OpenSource.GUI,
                reward -> reward.id().equals("primary")
                        ? AlternativeRewardResolver.Reason.MISSING_INTEGRATION : null,
                true, false, NOW);
        assertTrue(integration.rewards().isEmpty());
    }

    @Test
    void zeroChanceRewardIsNeverSelectableAsItsOwnSource() {
        UUID player = UUID.randomUUID();
        CrateReward zero = reward("prepared", 0, RewardRarity.EPIC, RewardLimits.unlimited());
        Crate crate = crate(PityPolicy.disabled(), zero);
        RewardStateService state = service(0);

        assertTrue(state.planSelectedResolved(player, crate, "prepared", 1, OpenSource.GUI,
                ignored -> null, true, false, NOW).rewards().isEmpty());
    }

    @Test
    void rerollPlannerUsesEligibleWeightedTicketsAndExcludesShownActualReward() {
        UUID player = UUID.randomUUID();
        CrateReward first = reward("first", 75, RewardRarity.COMMON, RewardLimits.unlimited());
        CrateReward second = reward("second", 25, RewardRarity.RARE, RewardLimits.unlimited());
        Crate crate = crate(PityPolicy.disabled(), first, second);
        RewardStateService state = service(0.90);

        assertEquals(Set.of("first", "second"), state.rerollOutcomes(player, crate, OpenSource.GUI,
                ignored -> null, true, false, NOW).stream().map(outcome -> outcome.actual().id())
                .collect(java.util.stream.Collectors.toSet()));
        RewardStateService.Plan replacement = state.planRerollResolved(player, crate, OpenSource.GUI,
                ignored -> null, true, false, NOW, Set.of("first"));

        assertEquals(List.of("second"), replacement.rewards().stream().map(CrateReward::id).toList());
        assertTrue(state.canApplyResolved(player, crate, replacement, OpenSource.GUI,
                ignored -> null, true, false, NOW));
    }

    private static RewardStateService service(double roll) {
        return new RewardStateService(new DatabaseService.RewardStateSnapshot(List.of(), List.of(), List.of()),
                () -> roll);
    }

    private static Crate crate(PityPolicy pity, CrateReward... rewards) {
        Map<String, CrateReward> pool = new LinkedHashMap<>();
        for (CrateReward reward : rewards) pool.put(reward.id(), reward);
        return new Crate("test", CrateState.PUBLISHED, 1, Component.text("Test Crate"), List.of(),
                new ItemStack(Material.CHEST), "", Set.of(), Set.of(), List.of("test"), 1, 0,
                true, 64, AnimationType.INSTANT, List.of(Component.text("Test")), "", pity, pool);
    }

    private static CrateReward reward(String id, double weight, RewardRarity rarity, RewardLimits limits) {
        ItemStack item = new ItemStack(Material.DIAMOND);
        return new CrateReward(id, Component.text(id), weight, true, rarity, item, List.of(item), List.of(),
                0, 0, 0, "", "", limits, RewardPresentation.none(), "", "");
    }

    private static CrateReward alternativeReward(String id, double chance, RewardLimits limits,
                                                  String fallback,
                                                  Set<AlternativeRewardResolver.Reason> reasons) {
        ItemStack item = new ItemStack(Material.DIAMOND);
        return new CrateReward(id, Component.text(id), chance, true, RewardRarity.RARE, item,
                List.of(item), List.of(), 0, 0, 0, "", "", limits, RewardPresentation.none(),
                "", "", fallback, reasons, null, null);
    }
}
