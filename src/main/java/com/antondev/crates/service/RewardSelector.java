package com.antondev.crates.service;

import com.antondev.crates.model.CrateReward;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import org.bukkit.entity.Player;

public final class RewardSelector {
    private RewardSelector() {}

    public static Optional<CrateReward> select(List<CrateReward> rewards, Player player) {
        List<CrateReward> eligible = rewards.stream().filter(reward -> reward.eligible(player)).toList();
        if (eligible.isEmpty()) return Optional.empty();
        return selectAt(eligible, ThreadLocalRandom.current().nextDouble());
    }

    public static Optional<CrateReward> selectAt(List<CrateReward> rewards, double unitRoll) {
        if (!Double.isFinite(unitRoll) || unitRoll < 0 || unitRoll >= 1) {
            throw new IllegalArgumentException("unitRoll must be in [0, 1)");
        }
        double total = rewards.stream().filter(CrateReward::enabled).mapToDouble(CrateReward::weight).sum();
        if (!Double.isFinite(total) || total <= 0) return Optional.empty();
        double roll = unitRoll * total;
        CrateReward last = null;
        for (CrateReward reward : rewards) {
            if (!reward.enabled()) continue;
            last = reward;
            roll -= reward.weight();
            if (roll < 0) return Optional.of(reward);
        }
        return Optional.ofNullable(last);
    }

    public static double chance(CrateReward reward, List<CrateReward> eligible) {
        double total = eligible.stream().mapToDouble(CrateReward::weight).sum();
        return total <= 0 ? 0 : reward.weight() * 100.0 / total;
    }
}
