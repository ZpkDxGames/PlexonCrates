package com.antondev.crates.model;

import com.antondev.crates.service.MilestoneService;
import java.util.Objects;
import net.kyori.adventure.text.Component;
import org.bukkit.inventory.ItemStack;

/** Immutable milestone definition attached to one published crate revision. */
public record CrateMilestone(
        MilestoneService.Definition definition,
        Component displayName,
        ItemStack displayItem,
        CrateReward reward,
        boolean previewVisible) {

    public CrateMilestone {
        definition = Objects.requireNonNull(definition, "definition");
        displayName = Objects.requireNonNull(displayName, "displayName");
        displayItem = Objects.requireNonNull(displayItem, "displayItem").clone();
        reward = Objects.requireNonNull(reward, "reward");
        if (displayItem.getType().isAir()) throw new IllegalArgumentException("Milestone display item cannot be empty");
    }

    @Override public ItemStack displayItem() { return displayItem.clone(); }
    public String id() { return definition.id(); }
    public int threshold() { return definition.threshold(); }
    public MilestoneService.DeliveryPolicy deliveryPolicy() { return definition.deliveryPolicy(); }
}
