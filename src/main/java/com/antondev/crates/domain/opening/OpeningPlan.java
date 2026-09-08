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
        long runtimeRevision,
        List<RewardDelivery> deliveries,
        Instant createdAt) {

    public OpeningPlan {
        deliveries = List.copyOf(deliveries);
        if (keyAmount < 0 || openingCount < 1 || runtimeRevision < 0) {
            throw new IllegalArgumentException("Invalid opening counts or runtime revision");
        }
    }

    public OpeningPlan(UUID transactionId, UUID playerId, String playerName, String crateId, String keyId,
                       int keyAmount, int openingCount, OpenSource source, BlockPosition location,
                       List<RewardDelivery> deliveries, Instant createdAt) {
        this(transactionId, playerId, playerName, crateId, keyId, keyAmount, openingCount, source, location,
                0, deliveries, createdAt);
    }

    public List<String> rewardIds() {
        return deliveries.stream().map(RewardDelivery::rewardId).toList();
    }
}
