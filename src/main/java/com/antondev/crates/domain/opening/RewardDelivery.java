package com.antondev.crates.domain.opening;

import com.antondev.crates.domain.reward.RewardPresentation;
import java.util.List;
import net.kyori.adventure.text.Component;
import org.bukkit.inventory.ItemStack;

public record RewardDelivery(
        String rewardId,
        Component displayName,
        List<ItemStack> items,
        List<String> commands,
        int experiencePoints,
        int experienceLevels,
        double money,
        RewardPresentation presentation,
        String personalMessage,
        String broadcast) {

    public RewardDelivery {
        items = items.stream().map(ItemStack::clone).toList();
        commands = List.copyOf(commands);
        presentation = java.util.Objects.requireNonNull(presentation, "presentation");
    }

    @Override public List<ItemStack> items() { return items.stream().map(ItemStack::clone).toList(); }
}
