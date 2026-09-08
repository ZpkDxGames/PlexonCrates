package com.antondev.crates.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.IntFunction;

/** Bounded, all-or-nothing planning helpers for mass opening flows. */
public final class MassOpeningPlanner {
    private MassOpeningPlanner() {}

    public record Limits(
            int requested,
            int physicalAvailable,
            int virtualAvailable,
            int crateMaximum,
            int globalMaximum,
            int eligibleCapacity,
            int deliveryCapacity) {
        public Limits {
            if (requested < 1) throw new IllegalArgumentException("Requested amount must be positive");
            for (int value : new int[]{physicalAvailable, virtualAvailable, crateMaximum,
                    globalMaximum, eligibleCapacity, deliveryCapacity}) {
                if (value < 0) throw new IllegalArgumentException("Mass-opening limits cannot be negative");
            }
        }
    }

    public record Plan(int requested, int executable, boolean complete) {
        public Plan {
            if (requested < 1 || executable < 0 || executable > requested) {
                throw new IllegalArgumentException("Invalid mass-opening plan");
            }
            if (complete != (requested == executable)) {
                throw new IllegalArgumentException("Plan completion does not match executable amount");
            }
        }
    }

    /** Computes Maximum Available as the minimum of every operational bound. */
    public static int maximumAvailable(Limits limits) {
        Objects.requireNonNull(limits, "limits");
        int payment = (int) Math.min(Integer.MAX_VALUE,
                (long) limits.physicalAvailable() + limits.virtualAvailable());
        return Math.min(limits.requested(), Math.min(payment,
                Math.min(limits.crateMaximum(), Math.min(limits.globalMaximum(),
                        Math.min(limits.eligibleCapacity(), limits.deliveryCapacity())))));
    }

    public static Plan plan(Limits limits, boolean requireFull) {
        Objects.requireNonNull(limits, "limits");
        int maximum = maximumAvailable(limits);
        if (requireFull && maximum < limits.requested()) return new Plan(limits.requested(), 0, false);
        return new Plan(limits.requested(), maximum, maximum == limits.requested());
    }

    /**
     * Runs one selector for each planned index. A missing result aborts the
     * entire plan when requireFull is true, preventing partial payment.
     */
    public static <T> Optional<List<T>> selectSequential(
            int amount, IntFunction<Optional<T>> selector, boolean requireFull) {
        if (amount < 1 || amount > 10_000) throw new IllegalArgumentException("Mass opening amount must be 1-10000");
        Objects.requireNonNull(selector, "selector");
        var selected = new ArrayList<T>(amount);
        for (int index = 0; index < amount; index++) {
            Optional<T> value = Objects.requireNonNull(selector.apply(index), "selector result");
            if (value.isEmpty()) {
                if (requireFull) return Optional.empty();
                break;
            }
            selected.add(value.get());
        }
        return selected.isEmpty() ? Optional.empty() : Optional.of(List.copyOf(selected));
    }
}
