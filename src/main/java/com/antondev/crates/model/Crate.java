package com.antondev.crates.model;

import com.antondev.crates.domain.crate.AnimationType;
import com.antondev.crates.domain.crate.CrateState;
import com.antondev.crates.domain.key.KeyPaymentPolicy;
import com.antondev.crates.domain.opening.OpeningMode;
import com.antondev.crates.domain.reward.PityPolicy;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.kyori.adventure.text.Component;
import org.bukkit.World;
import org.bukkit.inventory.ItemStack;

public record Crate(
        String id,
        CrateState state,
        int displayOrder,
        Component displayName,
        List<Component> description,
        ItemStack icon,
        String permission,
        Set<String> worlds,
        Set<String> excludedWorlds,
        List<String> acceptedKeyIds,
        int keyCost,
        KeyPaymentPolicy paymentPolicy,
        boolean mixedPayment,
        int cooldownSeconds,
        boolean bulkEnabled,
        int bulkMaximum,
        OpeningMode openingMode,
        AnimationType animation,
        List<Component> hologramLines,
        String broadcast,
        PityPolicy pity,
        Map<String, CrateMilestone> milestones,
        Map<String, CrateReward> rewards) {

    public Crate {
        description = List.copyOf(description);
        icon = icon.clone();
        worlds = Set.copyOf(worlds);
        excludedWorlds = Set.copyOf(excludedWorlds);
        acceptedKeyIds = List.copyOf(acceptedKeyIds);
        hologramLines = List.copyOf(hologramLines);
        milestones = Collections.unmodifiableMap(new LinkedHashMap<>(milestones));
        rewards = Collections.unmodifiableMap(new LinkedHashMap<>(rewards));
        paymentPolicy = java.util.Objects.requireNonNull(paymentPolicy, "paymentPolicy");
        openingMode = java.util.Objects.requireNonNull(openingMode, "openingMode");
    }

    /** Source-compatible constructor for the 2.x/early-3.x physical random model. */
    public Crate(String id, CrateState state, int displayOrder, Component displayName,
                 List<Component> description, ItemStack icon, String permission,
                 Set<String> worlds, Set<String> excludedWorlds, List<String> acceptedKeyIds,
                 int keyCost, int cooldownSeconds, boolean bulkEnabled, int bulkMaximum,
                 AnimationType animation, List<Component> hologramLines, String broadcast,
                 PityPolicy pity, Map<String, CrateReward> rewards) {
        this(id, state, displayOrder, displayName, description, icon, permission, worlds,
                excludedWorlds, acceptedKeyIds, keyCost, KeyPaymentPolicy.PHYSICAL_ONLY, false,
                cooldownSeconds, bulkEnabled, bulkMaximum, OpeningMode.RANDOM, animation,
                hologramLines, broadcast, pity, Map.of(), rewards);
    }

    @Override public ItemStack icon() { return icon.clone(); }
    public ItemStack iconCopy() { return icon.clone(); }
    public List<CrateReward> orderedRewards() { return List.copyOf(rewards.values()); }
    public List<CrateMilestone> orderedMilestones() { return List.copyOf(milestones.values()); }

    /** Compatibility alias used by the 1.0 API and commands. */
    public boolean enabled() { return state == CrateState.PUBLISHED; }

    /** Compatibility alias: the first accepted key is the primary key. */
    public String keyId() { return acceptedKeyIds.isEmpty() ? "" : acceptedKeyIds.getFirst(); }

    public boolean allows(World world) {
        String name = world.getName().toLowerCase(java.util.Locale.ROOT);
        return (worlds.isEmpty() || worlds.contains(name)) && !excludedWorlds.contains(name);
    }
}
