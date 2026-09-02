package com.antondev.crates.domain.reward;

public record RewardLimits(
        long playerLifetime,
        long playerWindow,
        long playerWindowSeconds,
        long globalLifetime,
        long globalWindow,
        long globalWindowSeconds,
        long cooldownSeconds) {

    public RewardLimits {
        if (playerLifetime < 0 || playerWindow < 0 || playerWindowSeconds < 0
                || globalLifetime < 0 || globalWindow < 0 || globalWindowSeconds < 0 || cooldownSeconds < 0) {
            throw new IllegalArgumentException("Reward limits cannot be negative");
        }
        if ((playerWindow > 0) != (playerWindowSeconds > 0)) {
            throw new IllegalArgumentException("A player window limit and duration must be configured together");
        }
        if ((globalWindow > 0) != (globalWindowSeconds > 0)) {
            throw new IllegalArgumentException("A global window limit and duration must be configured together");
        }
    }

    public static RewardLimits unlimited() {
        return new RewardLimits(0, 0, 0, 0, 0, 0, 0);
    }
}
