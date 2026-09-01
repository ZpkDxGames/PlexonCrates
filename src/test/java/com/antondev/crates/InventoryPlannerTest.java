package com.antondev.crates;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.antondev.crates.service.InventoryPlanner;
import java.util.Arrays;
import java.util.List;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

class InventoryPlannerTest {
    @Test
    void mergesAndUsesEmptySlotsWithoutMutatingOriginal() {
        ItemStack[] storage = new ItemStack[36];
        storage[0] = new ItemStack(Material.DIAMOND, 63);
        assertTrue(InventoryPlanner.fits(storage, List.of(new ItemStack(Material.DIAMOND, 2))));
        assertTrue(storage[1] == null);
        assertTrue(storage[0].getAmount() == 63);
    }

    @Test
    void rejectsAnOverflowWhenEverySlotIsFull() {
        ItemStack[] storage = new ItemStack[36];
        Arrays.setAll(storage, ignored -> new ItemStack(Material.COBBLESTONE, 64));
        assertFalse(InventoryPlanner.fits(storage, List.of(new ItemStack(Material.DIAMOND, 1))));
    }
}
