package com.plexoncrates.util;

import com.plexoncrates.crate.Reward;
import java.util.Collection;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.ThreadLocalRandom;

public final class WeightedSelector {
    private WeightedSelector() {}

    public static Optional<Reward> select(Collection<Reward> rewards) {
        TreeMap<Integer, Reward> cumulative = new TreeMap<>();
        int total = 0;
        for (Reward reward : rewards) {
            if (!reward.enabled() || reward.weight() <= 0) continue;
            total = Math.addExact(total, reward.weight());
            cumulative.put(total, reward);
        }
        if (total <= 0) return Optional.empty();
        int roll = ThreadLocalRandom.current().nextInt(total) + 1;
        return Optional.ofNullable(cumulative.ceilingEntry(roll)).map(java.util.Map.Entry::getValue);
    }

    public static double chance(Reward reward, Collection<Reward> rewards) {
        long total = rewards.stream()
                .filter(Reward::enabled)
                .filter(value -> value.weight() > 0)
                .mapToLong(Reward::weight)
                .sum();
        if (total <= 0 || !reward.enabled() || reward.weight() <= 0) return 0.0D;
        return reward.weight() * 100.0D / total;
    }
}
