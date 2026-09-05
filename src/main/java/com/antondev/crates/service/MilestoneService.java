package com.antondev.crates.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Deterministic milestone progression for successful crate openings.
 *
 * <p>The calculator is deliberately independent of Bukkit and persistence. A
 * caller can evaluate an opening batch first, then persist the returned
 * progress and earned entries in the same transaction as the opening journal.
 * Preview/test paths must never call {@link #advance(Progress, int, List)} with
 * their result persisted.</p>
 */
public final class MilestoneService {
    public static final int MAX_EARNINGS_PER_ADVANCE = 10_000;

    private MilestoneService() {}

    public enum RepeatPolicy {
        ONCE, REPEATING
    }

    public enum DeliveryPolicy {
        AUTO_DELIVER, CLAIM
    }

    public record Definition(
            String id,
            int threshold,
            RepeatPolicy repeatPolicy,
            int cycleLength,
            int position,
            DeliveryPolicy deliveryPolicy) {
        public Definition {
            id = normalizeId(id);
            repeatPolicy = Objects.requireNonNull(repeatPolicy, "repeatPolicy");
            deliveryPolicy = Objects.requireNonNull(deliveryPolicy, "deliveryPolicy");
            if (threshold < 1) throw new IllegalArgumentException("Milestone threshold must be positive");
            if (position < 0) throw new IllegalArgumentException("Milestone position cannot be negative");
            if (repeatPolicy == RepeatPolicy.ONCE && cycleLength != 0) {
                throw new IllegalArgumentException("One-time milestones cannot have a cycle length");
            }
            if (repeatPolicy == RepeatPolicy.REPEATING && cycleLength < 1) {
                throw new IllegalArgumentException("Repeating milestones need a positive cycle length");
            }
        }

        /** Convenience constructor for a one-time milestone at the end of a list. */
        public Definition(String id, int threshold) {
            this(id, threshold, RepeatPolicy.ONCE, 0, 0, DeliveryPolicy.CLAIM);
        }

        /** Convenience constructor preserving the normal authored ordering. */
        public Definition(String id, int threshold, RepeatPolicy repeatPolicy, int cycleLength) {
            this(id, threshold, repeatPolicy, cycleLength, 0, DeliveryPolicy.CLAIM);
        }
    }

    public record Earned(String milestoneId, long cycle, DeliveryPolicy deliveryPolicy) {
        public Earned {
            milestoneId = normalizeId(milestoneId);
            if (cycle < 0) throw new IllegalArgumentException("Milestone cycle cannot be negative");
            deliveryPolicy = Objects.requireNonNull(deliveryPolicy, "deliveryPolicy");
        }

        /** Stable key suitable for an idempotency token or earned-state set. */
        public String key() {
            return milestoneId + "#" + cycle;
        }
    }

    public record Progress(long openings, long revision, Set<String> earnedKeys) {
        public Progress {
            if (openings < 0 || revision < 0) {
                throw new IllegalArgumentException("Milestone progress cannot be negative");
            }
            var copy = new LinkedHashSet<String>();
            if (earnedKeys != null) {
                for (String key : earnedKeys) {
                    if (key == null || key.isBlank()) throw new IllegalArgumentException("Earned milestone key cannot be blank");
                    copy.add(key.trim());
                }
            }
            earnedKeys = Set.copyOf(copy);
        }

        public Progress(long openings, Set<String> earnedKeys) {
            this(openings, 0, earnedKeys);
        }

        public static Progress empty() {
            return new Progress(0, 0, Set.of());
        }
    }

    public record Evaluation(Progress before, Progress after, List<Earned> newlyEarned) {
        public Evaluation {
            before = Objects.requireNonNull(before, "before");
            after = Objects.requireNonNull(after, "after");
            newlyEarned = List.copyOf(newlyEarned);
        }

        public boolean changed() {
            return !newlyEarned.isEmpty() || before.openings() != after.openings();
        }
    }

    /**
     * Applies a successfully finalized opening count sequentially. Thresholds
     * crossed by a batch are returned in stable definition/cycle order.
     */
    public static Evaluation advance(Progress progress, int openingCount, List<Definition> definitions) {
        Progress before = Objects.requireNonNull(progress, "progress");
        if (openingCount < 0) throw new IllegalArgumentException("Opening count cannot be negative");
        List<Definition> ordered = new ArrayList<>(Objects.requireNonNull(definitions, "definitions"));
        ordered.sort(Comparator.comparingInt(Definition::position).thenComparing(Definition::id));
        long nextOpenings = Math.addExact(before.openings(), openingCount);
        var earnedKeys = new LinkedHashSet<>(before.earnedKeys());
        var earned = new ArrayList<Earned>();
        for (Definition definition : ordered) {
            if (definition.repeatPolicy() == RepeatPolicy.ONCE) {
                if (before.openings() < definition.threshold()
                        && nextOpenings >= definition.threshold()) {
                    Earned entry = new Earned(definition.id(), 0, definition.deliveryPolicy());
                    if (earnedKeys.add(entry.key())) earned.add(entry);
                }
                continue;
            }
            long firstCycle = before.openings() < definition.threshold()
                    ? 0
                    : Math.addExact(Math.floorDiv(before.openings() - definition.threshold(),
                            definition.cycleLength()), 1);
            long lastCycle = nextOpenings < definition.threshold()
                    ? -1
                    : Math.floorDiv(nextOpenings - definition.threshold(), definition.cycleLength());
            if (lastCycle < firstCycle) continue;
            long span = Math.addExact(lastCycle - firstCycle, 1);
            if (span > MAX_EARNINGS_PER_ADVANCE) {
                throw new IllegalArgumentException("Opening batch crosses too many repeating milestone cycles");
            }
            for (long cycle = firstCycle; cycle <= lastCycle; cycle++) {
                Earned entry = new Earned(definition.id(), cycle, definition.deliveryPolicy());
                if (earnedKeys.add(entry.key())) earned.add(entry);
            }
        }
        Progress after = new Progress(nextOpenings, Math.addExact(before.revision(), 1), earnedKeys);
        return new Evaluation(before, after, earned);
    }

    /** Returns the next unearned threshold for a player's current progress. */
    public static Optional<Definition> next(Progress progress, List<Definition> definitions) {
        Objects.requireNonNull(progress, "progress");
        return definitions.stream()
                .filter(Objects::nonNull)
                .sorted(Comparator.comparingInt(Definition::position).thenComparing(Definition::id))
                .filter(definition -> {
                    if (definition.repeatPolicy() == RepeatPolicy.ONCE) {
                        return !progress.earnedKeys().contains(new Earned(definition.id(), 0,
                                definition.deliveryPolicy()).key());
                    }
                    long cycle = progress.openings() < definition.threshold() ? 0
                            : Math.floorDiv(progress.openings() - definition.threshold(), definition.cycleLength()) + 1;
                    return !progress.earnedKeys().contains(new Earned(definition.id(), cycle,
                            definition.deliveryPolicy()).key());
                })
                .findFirst();
    }

    private static String normalizeId(String raw) {
        String id = Objects.requireNonNull(raw, "id").trim().toLowerCase(java.util.Locale.ROOT);
        if (!id.matches("[a-z0-9][a-z0-9_-]{0,63}")) {
            throw new IllegalArgumentException("Invalid milestone ID: " + raw);
        }
        return id;
    }
}
