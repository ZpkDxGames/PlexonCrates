package com.antondev.crates.model;

import com.antondev.crates.domain.reward.RewardLimits;
import com.antondev.crates.domain.reward.RewardRarity;
import com.antondev.crates.domain.reward.RewardPresentation;
import com.antondev.crates.service.AlternativeRewardResolver;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

public record CrateReward(
        String id,
        Component displayName,
        double baseChancePercent,
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
        String broadcast,
        String alternativeRewardId,
        Set<AlternativeRewardResolver.Reason> alternativeReasons,
        Instant availableFrom,
        Instant availableUntil) {

    public CrateReward {
        displayItem = displayItem.clone();
        items = items.stream().map(ItemStack::clone).toList();
        commands = List.copyOf(commands);
        presentation = java.util.Objects.requireNonNull(presentation, "presentation");
        alternativeRewardId = alternativeRewardId == null || alternativeRewardId.isBlank()
                ? null : alternativeRewardId.trim().toLowerCase(java.util.Locale.ROOT);
        alternativeReasons = alternativeReasons == null ? Set.of() : Set.copyOf(alternativeReasons);
        if (alternativeRewardId == null && !alternativeReasons.isEmpty()) {
            throw new IllegalArgumentException("Alternative reasons require a fallback reward");
        }
        if (alternativeRewardId != null && (!alternativeRewardId.matches("[a-z0-9][a-z0-9_-]{0,63}")
                || alternativeReasons.isEmpty()
                || !alternativeReasons.stream().allMatch(AlternativeRewardResolver::fallbackReasonAllowed))) {
            throw new IllegalArgumentException("Alternative reward policy is incomplete or unsafe");
        }
        if (availableFrom != null && availableUntil != null && !availableFrom.isBefore(availableUntil)) {
            throw new IllegalArgumentException("Reward availability start must precede its end");
        }
        if (!Double.isFinite(baseChancePercent) || baseChancePercent < 0 || baseChancePercent > 100) {
            throw new IllegalArgumentException("Reward chance must be between 0.00% and 100.00%");
        }
        if (!Double.isFinite(money) || money < 0) throw new IllegalArgumentException("Reward money cannot be negative");
        if (experiencePoints < 0 || experienceLevels < 0) throw new IllegalArgumentException("Reward experience cannot be negative");
    }

    /** Source-compatible constructor retained for the 1.0 public model/tests. */
    public CrateReward(String id, Component displayName, double legacyChanceValue, boolean enabled,
                       ItemStack displayItem, List<ItemStack> items, List<String> commands,
                       String requiredPermission, String blockedPermission, String broadcast) {
        this(id, displayName, legacyChanceValue, enabled, RewardRarity.COMMON, displayItem, items, commands,
                0, 0, 0.0, requiredPermission, blockedPermission, RewardLimits.unlimited(),
                RewardPresentation.none(), "", broadcast, null, Set.of(), null, null);
    }

    /** Source-compatible constructor for rewards without 3.0 fallback/availability metadata. */
    public CrateReward(String id, Component displayName, double baseChancePercent, boolean enabled,
                       RewardRarity rarity, ItemStack displayItem, List<ItemStack> items, List<String> commands,
                       int experiencePoints, int experienceLevels, double money, String requiredPermission,
                       String blockedPermission, RewardLimits limits, RewardPresentation presentation,
                       String personalMessage, String broadcast) {
        this(id, displayName, baseChancePercent, enabled, rarity, displayItem, items, commands,
                experiencePoints, experienceLevels, money, requiredPermission, blockedPermission, limits,
                presentation, personalMessage, broadcast, null, Set.of(), null, null);
    }

    @Override public ItemStack displayItem() { return displayItem.clone(); }
    @Override public List<ItemStack> items() { return items.stream().map(ItemStack::clone).toList(); }

    /**
     * Compatibility accessor retained through 3.x. The value is now a base
     * percentage, not an arbitrary relative weight.
     */
    @Deprecated(forRemoval = false)
    public double weight() { return baseChancePercent; }

    public int chanceBasisPoints() { return (int) Math.round(baseChancePercent * 100.0); }

    public CrateReward withChanceBasisPoints(int basisPoints) {
        if (basisPoints < 0 || basisPoints > 10_000) {
            throw new IllegalArgumentException("Reward chance must be between 0 and 10,000 basis points");
        }
        return new CrateReward(id, displayName, basisPoints / 100.0, enabled, rarity, displayItem, items, commands,
                experiencePoints, experienceLevels, money, requiredPermission, blockedPermission, limits,
                presentation, personalMessage, broadcast, alternativeRewardId, alternativeReasons,
                availableFrom, availableUntil);
    }

    public ItemStack displayCopy() { return displayItem.clone(); }
    public List<ItemStack> itemCopies() { return items.stream().map(ItemStack::clone).toList(); }

    public boolean eligible(Player player) {
        return enabled && chanceBasisPoints() > 0
                && (requiredPermission.isBlank() || player.hasPermission(requiredPermission))
                && (blockedPermission.isBlank() || !player.hasPermission(blockedPermission))
                && availableAt(System.currentTimeMillis());
    }

    public boolean availableAt(long epochMillis) {
        Instant now = Instant.ofEpochMilli(epochMillis);
        return (availableFrom == null || !now.isBefore(availableFrom))
                && (availableUntil == null || now.isBefore(availableUntil));
    }

    public boolean hasAlternative() { return alternativeRewardId != null; }

    public boolean hasDelivery() {
        return !items.isEmpty() || !commands.isEmpty() || experiencePoints > 0 || experienceLevels > 0 || money > 0;
    }
}
