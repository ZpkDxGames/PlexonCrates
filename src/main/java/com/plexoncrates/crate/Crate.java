package com.plexoncrates.crate;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.bukkit.inventory.ItemStack;

public final class Crate {
    private final String id;
    private boolean enabled;
    private String displayName;
    private List<String> description;
    private ItemStack icon;
    private String keyDisplayName;
    private ItemStack keyItem;
    private String animation;
    private String idleEffect;
    private final Map<String, Reward> rewards;
    private final List<CrateLocation> locations;

    public Crate(String id, boolean enabled, String displayName, List<String> description, ItemStack icon,
                 String keyDisplayName, ItemStack keyItem, String animation, String idleEffect,
                 Collection<Reward> rewards, Collection<CrateLocation> locations) {
        this.id = normalize(id);
        this.enabled = enabled;
        this.displayName = Objects.requireNonNull(displayName);
        this.description = new ArrayList<>(description == null ? List.of() : description);
        this.icon = Objects.requireNonNull(icon).clone();
        this.keyDisplayName = Objects.requireNonNull(keyDisplayName);
        this.keyItem = Objects.requireNonNull(keyItem).clone();
        this.animation = normalizeEnum(animation, "CSGO");
        this.idleEffect = normalizeEnum(idleEffect, "HELIX");
        this.rewards = new LinkedHashMap<>();
        if (rewards != null) rewards.forEach(reward -> this.rewards.put(reward.id(), reward.copy()));
        this.locations = new ArrayList<>(locations == null ? List.of() : locations);
    }

    public String id() { return id; }
    public boolean enabled() { return enabled; }
    public String displayName() { return displayName; }
    public List<String> description() { return List.copyOf(description); }
    public ItemStack icon() { return icon.clone(); }
    public String keyDisplayName() { return keyDisplayName; }
    public ItemStack keyItem() { return keyItem.clone(); }
    public String animation() { return animation; }
    public String idleEffect() { return idleEffect; }
    public Collection<Reward> rewards() { return rewards.values().stream().map(Reward::copy).toList(); }
    public List<CrateLocation> locations() { return List.copyOf(locations); }

    public Optional<Reward> reward(String rewardId) {
        Reward reward = rewards.get(normalize(rewardId));
        return reward == null ? Optional.empty() : Optional.of(reward);
    }

    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public void setDisplayName(String displayName) { this.displayName = Objects.requireNonNull(displayName); }
    public void setDescription(List<String> description) { this.description = new ArrayList<>(description); }
    public void setIcon(ItemStack icon) { this.icon = Objects.requireNonNull(icon).clone(); }
    public void setKeyDisplayName(String keyDisplayName) { this.keyDisplayName = Objects.requireNonNull(keyDisplayName); }
    public void setKeyItem(ItemStack keyItem) { this.keyItem = Objects.requireNonNull(keyItem).clone(); }
    public void setAnimation(String animation) { this.animation = normalizeEnum(animation, "CSGO"); }
    public void setIdleEffect(String idleEffect) { this.idleEffect = normalizeEnum(idleEffect, "HELIX"); }

    public void putReward(Reward reward) {
        rewards.put(normalize(reward.id()), reward);
    }

    public boolean removeReward(String rewardId) {
        return rewards.remove(normalize(rewardId)) != null;
    }

    public void addLocation(CrateLocation location) {
        if (locations.stream().noneMatch(existing -> existing.key().equals(location.key()))) locations.add(location);
    }

    public boolean removeLocation(CrateLocation location) {
        return locations.removeIf(existing -> existing.key().equals(location.key()));
    }

    public Crate copy() {
        return new Crate(id, enabled, displayName, description, icon, keyDisplayName, keyItem,
                animation, idleEffect, rewards.values(), locations);
    }

    private static String normalize(String value) {
        return Objects.requireNonNull(value).trim().toLowerCase(Locale.ROOT);
    }

    private static String normalizeEnum(String value, String fallback) {
        if (value == null || value.isBlank()) return fallback;
        return value.trim().toUpperCase(Locale.ROOT);
    }
}
