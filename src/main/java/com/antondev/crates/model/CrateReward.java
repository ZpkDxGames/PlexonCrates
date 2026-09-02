package com.antondev.crates.model;

import com.antondev.crates.domain.reward.RewardLimits;
import com.antondev.crates.domain.reward.RewardRarity;
import com.antondev.crates.domain.reward.RewardPresentation;
import java.util.List;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

public record CrateReward(
        String id,
        Component displayName,
        double weight,
        boolean enabled,
        RewardRarity rarity,
        ItemStack displayItem,
        List<ItemStack> items,
        List<String> commands,
        int experiencePoints,
        int experienceLevels,
        double money,
        String requiredPermission,
        String blockedPermission,
        RewardLimits limits,
        RewardPresentation presentation,
        String personalMessage,
        String broadcast) {

    public CrateReward {
        displayItem = displayItem.clone();
        items = items.stream().map(ItemStack::clone).toList();
        commands = List.copyOf(commands);
        presentation = java.util.Objects.requireNonNull(presentation, "presentation");
        if (!Double.isFinite(weight) || weight <= 0) throw new IllegalArgumentException("Reward weight must be positive and finite");
        if (!Double.isFinite(money) || money < 0) throw new IllegalArgumentException("Reward money cannot be negative");
        if (experiencePoints < 0 || experienceLevels < 0) throw new IllegalArgumentException("Reward experience cannot be negative");
    }

    /** Source-compatible constructor retained for the 1.0 public model/tests. */
    public CrateReward(String id, Component displayName, double weight, boolean enabled,
                       ItemStack displayItem, List<ItemStack> items, List<String> commands,
                       String requiredPermission, String blockedPermission, String broadcast) {
        this(id, displayName, weight, enabled, RewardRarity.COMMON, displayItem, items, commands,
                0, 0, 0.0, requiredPermission, blockedPermission, RewardLimits.unlimited(),
                RewardPresentation.none(), "", broadcast);
    }

    @Override public ItemStack displayItem() { return displayItem.clone(); }
    @Override public List<ItemStack> items() { return items.stream().map(ItemStack::clone).toList(); }

    public ItemStack displayCopy() { return displayItem.clone(); }
    public List<ItemStack> itemCopies() { return items.stream().map(ItemStack::clone).toList(); }

    public boolean eligible(Player player) {
        return enabled
                && (requiredPermission.isBlank() || player.hasPermission(requiredPermission))
                && (blockedPermission.isBlank() || !player.hasPermission(blockedPermission));
    }

    public boolean hasDelivery() {
        return !items.isEmpty() || !commands.isEmpty() || experiencePoints > 0 || experienceLevels > 0 || money > 0;
    }
}
