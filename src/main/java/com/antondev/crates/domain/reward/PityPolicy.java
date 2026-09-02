package com.antondev.crates.domain.reward;

import java.util.Set;

public record PityPolicy(boolean enabled, int threshold, Set<String> rewardIds, RewardRarity rarity,
                         boolean administrativeOpeningsCount) {
    public PityPolicy {
        if (threshold < 0) throw new IllegalArgumentException("Pity threshold cannot be negative");
        rewardIds = Set.copyOf(rewardIds);
        if (enabled && threshold < 1) throw new IllegalArgumentException("Enabled pity requires a positive threshold");
        if (enabled && rewardIds.isEmpty() && rarity == null) {
            throw new IllegalArgumentException("Enabled pity needs reward IDs or a rarity");
        }
    }

    public static PityPolicy disabled() {
        return new PityPolicy(false, 0, Set.of(), null, false);
    }
}
