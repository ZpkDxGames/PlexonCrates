package com.antondev.crates.service;

import com.antondev.crates.model.CrateReward;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import org.bukkit.entity.Player;

public final class RewardSelector {
    private RewardSelector() {}

    public static Optional<CrateReward> select(List<CrateReward> rewards, Player player) {
        List<CrateReward> eligible = rewards.stream().filter(reward -> reward.eligible(player))
                .filter(reward -> reward.chanceBasisPoints() > 0).toList();
        if (eligible.isEmpty()) return Optional.empty();
        return selectTicket(eligible, ThreadLocalRandom.current().nextInt(ChanceAllocator.TOTAL_BASIS_POINTS));
    }

    public static Optional<CrateReward> selectAt(List<CrateReward> rewards, double unitRoll) {
        if (!Double.isFinite(unitRoll) || unitRoll < 0 || unitRoll >= 1) {
            throw new IllegalArgumentException("unitRoll must be in [0, 1)");
        }
        int ticket = Math.min(ChanceAllocator.TOTAL_BASIS_POINTS - 1,
                (int) Math.floor(unitRoll * ChanceAllocator.TOTAL_BASIS_POINTS));
        return selectTicket(rewards, ticket);
    }

    public static Optional<CrateReward> selectTicket(List<CrateReward> rewards, int ticket) {
        if (ticket < 0 || ticket >= ChanceAllocator.TOTAL_BASIS_POINTS) {
            throw new IllegalArgumentException("ticket must be in [0, 10,000)");
        }
        List<CrateReward> eligible = rewards.stream().filter(CrateReward::enabled)
                .filter(reward -> reward.chanceBasisPoints() > 0).toList();
        if (eligible.isEmpty()) return Optional.empty();
        ChanceAllocator.Allocation allocation = ChanceAllocator.normalize(eligible.stream()
                .map(reward -> new ChanceAllocator.Chance(reward.id(), reward.chanceBasisPoints(), false)).toList());
        String selected = ChanceAllocator.selectTicket(allocation.chances(), ticket);
        return eligible.stream().filter(reward -> reward.id().equals(selected)).findFirst();
    }

    public static double chance(CrateReward reward, List<CrateReward> eligible) {
        return chanceBasisPoints(reward, eligible) / 100.0;
    }

    public static int chanceBasisPoints(CrateReward reward, List<CrateReward> eligible) {
        List<CrateReward> positive = eligible.stream().filter(CrateReward::enabled)
                .filter(value -> value.chanceBasisPoints() > 0).toList();
        if (positive.stream().noneMatch(value -> value.id().equals(reward.id()))) return 0;
        ChanceAllocator.Allocation allocation = ChanceAllocator.normalize(positive.stream()
                .map(value -> new ChanceAllocator.Chance(value.id(), value.chanceBasisPoints(), false)).toList());
        return allocation.basisPoints(reward.id());
    }
}
