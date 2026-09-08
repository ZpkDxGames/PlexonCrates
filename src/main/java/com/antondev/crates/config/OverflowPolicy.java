package com.antondev.crates.config;

/** Exact-item handling when a completed opening cannot fit every stack. */
public enum OverflowPolicy {
    /** Reject the opening before payment/consumption. */
    REJECT,
    /** Drop only the remaining exact stacks at the player's safe location. */
    DROP,
    /** Insert what fits and queue the remaining exact stacks in Claim Inbox. */
    CLAIM,
    /** Queue every exact item/key result in Claim Inbox. */
    CLAIM_ALL
}
