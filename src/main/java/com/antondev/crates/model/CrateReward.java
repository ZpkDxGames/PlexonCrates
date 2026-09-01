package com.antondev.crates.model;

import java.util.List;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

public record CrateReward(
        String id,
        Component displayName,
        double weight,
        boolean enabled,
        ItemStack displayItem,
        List<ItemStack> items,
        List<String> commands,
        String requiredPermission,
        String blockedPermission,
        String broadcast) {

    public CrateReward {
        items = items.stream().map(ItemStack::clone).toList();
        commands = List.copyOf(commands);
    }

    public ItemStack displayCopy() {
        return displayItem.clone();
    }

    public List<ItemStack> itemCopies() {
        return items.stream().map(ItemStack::clone).toList();
    }

    public boolean eligible(Player player) {
        return enabled
                && (requiredPermission.isBlank() || player.hasPermission(requiredPermission))
                && (blockedPermission.isBlank() || !player.hasPermission(blockedPermission));
    }
}
