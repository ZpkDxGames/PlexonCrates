package com.plexoncrates.crate;

import java.util.Objects;
import org.bukkit.inventory.ItemStack;

public final class RewardAction {
    private final RewardActionType type;
    private final String value;
    private final ItemStack item;

    public RewardAction(RewardActionType type, String value, ItemStack item) {
        this.type = Objects.requireNonNull(type, "type");
        this.value = value == null ? "" : value;
        this.item = item == null ? null : item.clone();
    }

    public RewardActionType type() {
        return type;
    }

    public String value() {
        return value;
    }

    public ItemStack item() {
        return item == null ? null : item.clone();
    }

    public RewardAction copy() {
        return new RewardAction(type, value, item);
    }
}
