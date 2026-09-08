package com.antondev.crates.service;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Pure reroll decision state. Payment reservation and journal transitions stay
 * in the opening coordinator; this class only prevents duplicate/ineligible
 * candidates and handles deterministic timeout semantics.
 */
public final class RerollService {
    private RerollService() {}

    public enum CostType {
        TOKEN, PERMISSION, MONEY, KEY
    }

    public enum TimeoutPolicy {
        ACCEPT_CURRENT
    }

    public record Policy(
            boolean enabled,
            int maximum,
            CostType costType,
            long cost,
            String permission,
            boolean excludePrevious,
            int timeoutSeconds,
            TimeoutPolicy timeoutPolicy,
            boolean massAllowed) {
        public Policy {
            costType = Objects.requireNonNull(costType, "costType");
            timeoutPolicy = Objects.requireNonNull(timeoutPolicy, "timeoutPolicy");
            permission = permission == null ? "" : permission.trim();
            if (maximum < 0 || maximum > 64) throw new IllegalArgumentException("Reroll maximum must be 0-64");
            if (cost < 0) throw new IllegalArgumentException("Reroll cost cannot be negative");
            if (timeoutSeconds < 1 || timeoutSeconds > 86_400) {
                throw new IllegalArgumentException("Reroll timeout must be 1-86400 seconds");
            }
            if (!enabled && maximum != 0) {
                throw new IllegalArgumentException("Disabled rerolls must have a zero maximum");
            }
            if (enabled && maximum == 0) {
                throw new IllegalArgumentException("Enabled rerolls need at least one attempt");
            }
            if (costType == CostType.PERMISSION && permission.isBlank()) {
                throw new IllegalArgumentException("Permission-funded rerolls need a permission node");
            }
            if (costType == CostType.PERMISSION && cost != 0) {
                throw new IllegalArgumentException("Permission-funded rerolls must have a zero numeric cost");
            }
            if (costType == CostType.KEY && cost > 64) {
                throw new IllegalArgumentException("Additional-key reroll cost must be 0-64");
            }
        }

        /** Source-compatible policy constructor used by the initial 3.0 domain tests. */
        public Policy(boolean enabled, int maximum, CostType costType, long cost,
                      boolean excludePrevious, int timeoutSeconds, TimeoutPolicy timeoutPolicy) {
            this(enabled, maximum, costType, cost,
                    costType == CostType.PERMISSION ? "plexoncrates.rerolls.free" : "",
                    excludePrevious, timeoutSeconds, timeoutPolicy, false);
        }

        public static Policy disabled() {
            return new Policy(false, 0, CostType.TOKEN, 0, "", true, 15,
                    TimeoutPolicy.ACCEPT_CURRENT, false);
        }

        public static Policy recommended() {
            return new Policy(true, 1, CostType.TOKEN, 1, "", true, 15,
                    TimeoutPolicy.ACCEPT_CURRENT, false);
        }
    }

    public record Offer<T>(
            T candidate,
            int rerollsUsed,
            List<T> shownCandidates,
            Instant expiresAt) {
        public Offer {
            candidate = Objects.requireNonNull(candidate, "candidate");
            if (rerollsUsed < 0) throw new IllegalArgumentException("Rerolls used cannot be negative");
            shownCandidates = List.copyOf(shownCandidates);
            if (shownCandidates.isEmpty() || !shownCandidates.contains(candidate)) {
                throw new IllegalArgumentException("An offer must contain its current candidate");
            }
            expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
        }

        public int remaining(Policy policy) {
            Objects.requireNonNull(policy, "policy");
            return Math.max(0, policy.maximum() - rerollsUsed);
        }

        public boolean timedOut(Instant now) {
            return !Objects.requireNonNull(now, "now").isBefore(expiresAt);
        }
    }

    public record Decision<T>(Offer<T> offer, boolean rerolled, boolean timedOut) {
        public Decision {
            offer = Objects.requireNonNull(offer, "offer");
        }
    }

    public static <T> Offer<T> start(Policy policy, T candidate, List<T> eligible, Instant now) {
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(candidate, "candidate");
        Objects.requireNonNull(now, "now");
        if (!policy.enabled()) throw new IllegalStateException("Rerolls are disabled");
        if (eligible == null || eligible.isEmpty() || !eligible.contains(candidate)) {
            throw new IllegalArgumentException("Candidate is not in the eligible pool");
        }
        Instant expires = now.plusSeconds(policy.timeoutSeconds());
        return new Offer<>(candidate, 0, List.of(candidate), expires);
    }

    /**
     * Requests one replacement using a caller-provided non-negative ticket.
     * Returning empty means the request was stale, out of allowance, or no
     * different eligible candidate exists; the current offer remains valid.
     */
    public static <T> Optional<Offer<T>> reroll(
            Policy policy, Offer<T> offer, List<T> eligible, int ticket, Instant now) {
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(offer, "offer");
        Objects.requireNonNull(now, "now");
        if (!policy.enabled() || offer.timedOut(now) || offer.rerollsUsed() >= policy.maximum()) return Optional.empty();
        if (eligible == null || eligible.isEmpty()) return Optional.empty();
        var candidates = replacementCandidates(policy, offer, eligible);
        if (candidates.isEmpty()) return Optional.empty();
        T replacement = candidates.get(Math.floorMod(ticket, candidates.size()));
        return replace(policy, offer, eligible, replacement, now);
    }

    /**
     * Applies a replacement selected by the weighted reward planner. The current
     * candidate is always excluded; {@code excludePrevious} additionally excludes
     * every earlier candidate in this decision.
     */
    public static <T> Optional<Offer<T>> replace(
            Policy policy, Offer<T> offer, List<T> eligible, T replacement, Instant now) {
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(offer, "offer");
        Objects.requireNonNull(replacement, "replacement");
        Objects.requireNonNull(now, "now");
        if (!policy.enabled() || offer.timedOut(now) || offer.rerollsUsed() >= policy.maximum()) {
            return Optional.empty();
        }
        if (!replacementCandidates(policy, offer, eligible).contains(replacement)) return Optional.empty();
        Set<T> shown = new LinkedHashSet<>(offer.shownCandidates());
        shown.add(replacement);
        return Optional.of(new Offer<>(replacement, offer.rerollsUsed() + 1, List.copyOf(shown),
                now.plusSeconds(policy.timeoutSeconds())));
    }

    public static <T> Decision<T> accept(Policy policy, Offer<T> offer, Instant now) {
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(offer, "offer");
        Objects.requireNonNull(now, "now");
        return new Decision<>(offer, false, offer.timedOut(now));
    }

    /** Candidate values that can be shown by a reroll without charging anything. */
    public static <T> List<T> replacementCandidates(Policy policy, Offer<T> offer, List<T> eligible) {
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(offer, "offer");
        if (eligible == null) return List.of();
        Set<T> shown = new LinkedHashSet<>(offer.shownCandidates());
        return eligible.stream().filter(Objects::nonNull)
                .filter(value -> !value.equals(offer.candidate()))
                .filter(value -> !policy.excludePrevious() || !shown.contains(value))
                .distinct().toList();
    }
}
