package com.antondev.crates;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.antondev.crates.model.CrateReward;
import com.antondev.crates.service.RewardSelector;
import java.util.List;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

class RewardSelectorTest {
    @Test
    void weightedBoundariesAreDeterministic() {
        CrateReward first = reward("first", 1, true);
        CrateReward second = reward("second", 3, true);
        assertEquals("first", RewardSelector.selectAt(List.of(first, second), 0).orElseThrow().id());
        assertEquals("first", RewardSelector.selectAt(List.of(first, second), 0.249999).orElseThrow().id());
        assertEquals("second", RewardSelector.selectAt(List.of(first, second), 0.25).orElseThrow().id());
        assertEquals("second", RewardSelector.selectAt(List.of(first, second), 0.999999).orElseThrow().id());
    }

    @Test
    void disabledRewardsNeverParticipate() {
        CrateReward disabled = reward("disabled", 1_000, false);
        CrateReward enabled = reward("enabled", 1, true);
        assertEquals("enabled", RewardSelector.selectAt(List.of(disabled, enabled), 0.5).orElseThrow().id());
        assertTrue(RewardSelector.selectAt(List.of(disabled), 0.5).isEmpty());
    }

    @Test
    void displayedChanceUsesEligibleWeightPool() {
        CrateReward first = reward("first", 1, true);
        CrateReward second = reward("second", 3, true);
        assertEquals(25.0, RewardSelector.chance(first, List.of(first, second)), 0.00001);
        assertEquals(75.0, RewardSelector.chance(second, List.of(first, second)), 0.00001);
    }

    private static CrateReward reward(String id, double weight, boolean enabled) {
        ItemStack item = new ItemStack(Material.STONE);
        return new CrateReward(id, Component.text(id), weight, enabled, item, List.of(item), List.of(), "", "", "");
    }
}
