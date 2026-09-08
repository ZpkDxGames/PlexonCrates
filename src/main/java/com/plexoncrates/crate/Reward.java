package com.plexoncrates.crate;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.bukkit.inventory.ItemStack;

public final class Reward {
    private final String id;
    private boolean enabled;
    private int weight;
    private ItemStack displayItem;
    private final List<RewardAction> actions;

    public Reward(String id, boolean enabled, int weight, ItemStack displayItem, List<RewardAction> actions) {
        this.id = Objects.requireNonNull(id, "id");
        this.enabled = enabled;
        this.weight = Math.max(0, weight);
        this.displayItem = Objects.requireNonNull(displayItem, "displayItem").clone();
        this.actions = new ArrayList<>();
        if (actions != null) actions.forEach(action -> this.actions.add(action.copy()));
    }

    public String id() { return id; }
    public boolean enabled() { return enabled; }
    public int weight() { return weight; }
    public ItemStack displayItem() { return displayItem.clone(); }
    public List<RewardAction> actions() { return actions.stream().map(RewardAction::copy).toList(); }

    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public void setWeight(int weight) { this.weight = Math.max(0, weight); }
    public void setDisplayItem(ItemStack item) { this.displayItem = Objects.requireNonNull(item).clone(); }

    public void addAction(RewardAction action) {
        actions.add(Objects.requireNonNull(action).copy());
    }

    public void clearActions() {
        actions.clear();
    }

    public Reward copy() {
        return new Reward(id, enabled, weight, displayItem, actions);
    }
}
