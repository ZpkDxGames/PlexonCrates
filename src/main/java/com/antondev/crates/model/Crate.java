package com.antondev.crates.model;

import java.util.List;
import java.util.Map;
import java.util.Collections;
import java.util.LinkedHashMap;
import net.kyori.adventure.text.Component;
import org.bukkit.inventory.ItemStack;

public record Crate(
        String id,
        boolean enabled,
        Component displayName,
        String keyId,
        String permission,
        int cooldownSeconds,
        ItemStack icon,
        List<Component> hologramLines,
        String broadcast,
        Map<String, CrateReward> rewards) {

    public Crate {
        hologramLines = List.copyOf(hologramLines);
        rewards = Collections.unmodifiableMap(new LinkedHashMap<>(rewards));
    }

    public ItemStack iconCopy() {
        return icon.clone();
    }

    public List<CrateReward> orderedRewards() {
        return List.copyOf(rewards.values());
    }
}
