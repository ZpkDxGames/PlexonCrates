package com.antondev.crates;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.antondev.crates.model.CrateReward;
import com.antondev.crates.service.RewardSelector;
import java.util.List;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

class RewardSelectorTest {
    @BeforeEach
    void setUp() {
        MockBukkit.mock();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void exactTicketBoundariesAreDeterministic() {
        CrateReward first = reward("first", 25, true);
        CrateReward second = reward("second", 75, true);
        assertEquals("first", RewardSelector.selectAt(List.of(first, second), 0).orElseThrow().id());
        assertEquals("first", RewardSelector.selectAt(List.of(first, second), 0.249999).orElseThrow().id());
        assertEquals("second", RewardSelector.selectAt(List.of(first, second), 0.25).orElseThrow().id());
        assertEquals("second", RewardSelector.selectAt(List.of(first, second), 0.999999).orElseThrow().id());
    }

    @Test
    void disabledRewardsNeverParticipate() {
        CrateReward disabled = reward("disabled", 100, false);
        CrateReward enabled = reward("enabled", 100, true);
        assertEquals("enabled", RewardSelector.selectAt(List.of(disabled, enabled), 0.5).orElseThrow().id());
        assertTrue(RewardSelector.selectAt(List.of(disabled), 0.5).isEmpty());
    }

    @Test
    void displayedChanceUsesExactEligiblePool() {
        CrateReward first = reward("first", 25, true);
        CrateReward second = reward("second", 75, true);
        assertEquals(25.0, RewardSelector.chance(first, List.of(first, second)), 0.00001);
        assertEquals(75.0, RewardSelector.chance(second, List.of(first, second)), 0.00001);
    }

    @Test
    void oneBasisPointRemainsReachableAtTheFinalBoundary() {
        CrateReward common = reward("common", 99.99, true);
        CrateReward rare = reward("rare", 0.01, true);

        assertEquals("common", RewardSelector.selectTicket(List.of(common, rare), 9_998).orElseThrow().id());
        assertEquals("rare", RewardSelector.selectTicket(List.of(common, rare), 9_999).orElseThrow().id());
    }

    private static CrateReward reward(String id, double chancePercent, boolean enabled) {
        ItemStack item = new ItemStack(Material.STONE);
        return new CrateReward(id, Component.text(id), chancePercent, enabled, item, List.of(item), List.of(), "", "", "");
    }
}
