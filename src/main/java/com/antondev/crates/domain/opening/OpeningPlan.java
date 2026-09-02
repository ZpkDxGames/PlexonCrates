package com.antondev.crates.domain.opening;

import com.antondev.crates.model.BlockPosition;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record OpeningPlan(
        UUID transactionId,
        UUID playerId,
        String playerName,
        String crateId,
        String keyId,
        int keyAmount,
        int openingCount,
        OpenSource source,
        BlockPosition location,
        List<RewardDelivery> deliveries,
        Instant createdAt) {

    public OpeningPlan {
        deliveries = List.copyOf(deliveries);
        if (keyAmount < 0 || openingCount < 1) throw new IllegalArgumentException("Invalid opening counts");
    }

    public List<String> rewardIds() {
        return deliveries.stream().map(RewardDelivery::rewardId).toList();
    }
}
