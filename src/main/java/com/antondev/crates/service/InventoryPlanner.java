package com.antondev.crates.service;

import java.util.Arrays;
import java.util.List;
import org.bukkit.inventory.ItemStack;

public final class InventoryPlanner {
    private InventoryPlanner() {}

    public static boolean fits(ItemStack[] storage, List<ItemStack> additions) {
        ItemStack[] working = Arrays.stream(storage).map(item -> item == null ? null : item.clone()).toArray(ItemStack[]::new);
        for (ItemStack original : additions) {
            ItemStack addition = original.clone();
            int remaining = addition.getAmount();
            for (ItemStack slot : working) {
                if (slot == null || !slot.isSimilar(addition) || slot.getAmount() >= slot.getMaxStackSize()) continue;
                int moved = Math.min(remaining, slot.getMaxStackSize() - slot.getAmount());
                slot.setAmount(slot.getAmount() + moved);
                remaining -= moved;
                if (remaining == 0) break;
            }
            for (int index = 0; index < working.length && remaining > 0; index++) {
                if (working[index] != null && !working[index].getType().isAir()) continue;
                ItemStack placed = addition.clone();
                placed.setAmount(Math.min(remaining, placed.getMaxStackSize()));
                remaining -= placed.getAmount();
                working[index] = placed;
            }
            if (remaining > 0) return false;
        }
        return true;
    }
}
