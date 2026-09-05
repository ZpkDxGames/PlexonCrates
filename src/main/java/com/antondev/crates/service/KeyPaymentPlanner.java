package com.antondev.crates.service;

import com.antondev.crates.domain.key.KeyPaymentPolicy;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Pure, deterministic planner for one accepted key ID and an exact opening cost. */
public final class KeyPaymentPlanner {
    private KeyPaymentPlanner() {}

    public enum Preference {
        PHYSICAL,
        VIRTUAL
    }

    public record Availability(String keyId, int physical, long virtual, int priority) {
        public Availability {
            keyId = Objects.requireNonNull(keyId, "keyId");
            if (keyId.isBlank() || physical < 0 || virtual < 0 || priority < 0) {
                throw new IllegalArgumentException("Invalid key-source availability");
            }
        }
    }

    public record Plan(String keyId, int physical, int virtual, KeyPaymentPolicy policy) {
        public Plan {
            keyId = Objects.requireNonNull(keyId, "keyId");
            policy = Objects.requireNonNull(policy, "policy");
            if (physical < 0 || virtual < 0 || (long) physical + virtual > Integer.MAX_VALUE) {
                throw new IllegalArgumentException("Invalid key payment split");
            }
        }

        public int total() {
            return physical + virtual;
        }

        public boolean usesPhysical() {
            return physical > 0;
        }

        public boolean usesVirtual() {
            return virtual > 0;
        }
    }

    public static Optional<Plan> plan(KeyPaymentPolicy policy, Preference preference,
                                      boolean mixedAllowed, int required,
                                      List<Availability> availability) {
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(preference, "preference");
        if (required < 0) throw new IllegalArgumentException("Required key amount cannot be negative");
        if (required == 0) return Optional.of(new Plan("FREE", 0, 0, policy));
        var ordered = new ArrayList<>(Objects.requireNonNull(availability, "availability"));
        ordered.sort(Comparator.comparingInt(Availability::priority));
        return switch (policy) {
            case PHYSICAL_ONLY -> fullPhysical(required, ordered, policy);
            case VIRTUAL_ONLY -> fullVirtual(required, ordered, policy);
            case PHYSICAL_FIRST -> fullPhysical(required, ordered, policy)
                    .or(() -> fullVirtual(required, ordered, policy))
                    .or(() -> mixedAllowed ? mixed(required, ordered, true, policy) : Optional.empty());
            case VIRTUAL_FIRST -> fullVirtual(required, ordered, policy)
                    .or(() -> fullPhysical(required, ordered, policy))
                    .or(() -> mixedAllowed ? mixed(required, ordered, false, policy) : Optional.empty());
            case PLAYER_CHOICE -> preference == Preference.PHYSICAL
                    ? fullPhysical(required, ordered, policy)
                            .or(() -> mixedAllowed ? mixed(required, ordered, true, policy) : Optional.empty())
                    : fullVirtual(required, ordered, policy)
                            .or(() -> mixedAllowed ? mixed(required, ordered, false, policy) : Optional.empty());
        };
    }

    private static Optional<Plan> fullPhysical(int required, List<Availability> values,
                                               KeyPaymentPolicy policy) {
        return values.stream().filter(value -> value.physical() >= required)
                .findFirst().map(value -> new Plan(value.keyId(), required, 0, policy));
    }

    private static Optional<Plan> fullVirtual(int required, List<Availability> values,
                                              KeyPaymentPolicy policy) {
        return values.stream().filter(value -> value.virtual() >= required)
                .findFirst().map(value -> new Plan(value.keyId(), 0, required, policy));
    }

    private static Optional<Plan> mixed(int required, List<Availability> values,
                                        boolean physicalFirst, KeyPaymentPolicy policy) {
        for (Availability value : values) {
            if ((long) value.physical() + value.virtual() < required) continue;
            int physical = physicalFirst ? Math.min(required, value.physical())
                    : Math.max(0, required - (int) Math.min(Integer.MAX_VALUE, value.virtual()));
            int virtual = required - physical;
            return Optional.of(new Plan(value.keyId(), physical, virtual, policy));
        }
        return Optional.empty();
    }
}
