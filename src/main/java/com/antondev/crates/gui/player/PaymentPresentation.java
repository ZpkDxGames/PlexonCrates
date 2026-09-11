package com.antondev.crates.gui.player;

import com.antondev.crates.domain.key.KeyPaymentPolicy;
import com.antondev.crates.service.KeyPaymentPlanner;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Player-facing projection of the existing key payment planner. */
public record PaymentPresentation(
        int required,
        long available,
        boolean sufficient,
        boolean choiceVisible,
        KeyPaymentPlanner.Preference preference,
        String sourceLabel,
        String keyId) {

    public PaymentPresentation {
        if (required < 0 || available < 0) throw new IllegalArgumentException("Payment values cannot be negative");
        preference = Objects.requireNonNull(preference, "preference");
        sourceLabel = Objects.requireNonNull(sourceLabel, "sourceLabel");
        keyId = Objects.requireNonNullElse(keyId, "");
    }

    public static PaymentPresentation resolve(
            KeyPaymentPolicy policy,
            boolean mixedAllowed,
            int required,
            List<KeyPaymentPlanner.Availability> availability,
            KeyPaymentPlanner.Preference requested,
            boolean bypass) {
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(availability, "availability");
        Objects.requireNonNull(requested, "requested");
        if (required < 0) throw new IllegalArgumentException("Required payment cannot be negative");
        if (bypass || required == 0) {
            return new PaymentPresentation(required, required, true, false, requested,
                    bypass ? "No key required" : "Free", "");
        }

        Optional<KeyPaymentPlanner.Plan> physical = KeyPaymentPlanner.plan(
                policy, KeyPaymentPlanner.Preference.PHYSICAL, mixedAllowed, required, availability);
        Optional<KeyPaymentPlanner.Plan> virtual = KeyPaymentPlanner.plan(
                policy, KeyPaymentPlanner.Preference.VIRTUAL, mixedAllowed, required, availability);

        boolean actualChoice = policy == KeyPaymentPolicy.PLAYER_CHOICE
                && physical.isPresent() && virtual.isPresent()
                && !sameSplit(physical.get(), virtual.get());

        KeyPaymentPlanner.Preference effective = requested;
        Optional<KeyPaymentPlanner.Plan> selected = requested == KeyPaymentPlanner.Preference.PHYSICAL
                ? physical : virtual;
        if (selected.isEmpty() && policy == KeyPaymentPolicy.PLAYER_CHOICE) {
            Optional<KeyPaymentPlanner.Plan> alternate = requested == KeyPaymentPlanner.Preference.PHYSICAL
                    ? virtual : physical;
            if (alternate.isPresent()) {
                selected = alternate;
                effective = requested == KeyPaymentPlanner.Preference.PHYSICAL
                        ? KeyPaymentPlanner.Preference.VIRTUAL : KeyPaymentPlanner.Preference.PHYSICAL;
            }
        }

        if (selected.isEmpty()) {
            return new PaymentPresentation(required, maximumCompatible(availability), false,
                    actualChoice, effective, policyLabel(policy), "");
        }
        KeyPaymentPlanner.Plan plan = selected.get();
        KeyPaymentPlanner.Availability source = availability.stream()
                .filter(value -> value.keyId().equals(plan.keyId())).findFirst().orElse(null);
        long available = source == null ? plan.total()
                : plan.usesPhysical() && plan.usesVirtual() ? (long) source.physical() + source.virtual()
                : plan.usesVirtual() ? source.virtual() : source.physical();
        String label = plan.usesPhysical() && plan.usesVirtual() ? "Physical + virtual keys"
                : plan.usesVirtual() ? "Virtual key" : "Physical key";
        return new PaymentPresentation(required, available, true, actualChoice, effective, label, plan.keyId());
    }

    private static boolean sameSplit(KeyPaymentPlanner.Plan left, KeyPaymentPlanner.Plan right) {
        return left.keyId().equals(right.keyId())
                && left.physical() == right.physical()
                && left.virtual() == right.virtual();
    }

    private static long maximumCompatible(List<KeyPaymentPlanner.Availability> availability) {
        return availability.stream().mapToLong(value -> (long) value.physical() + value.virtual()).max().orElse(0L);
    }

    private static String policyLabel(KeyPaymentPolicy policy) {
        return switch (policy) {
            case PHYSICAL_ONLY -> "Physical key";
            case VIRTUAL_ONLY -> "Virtual key";
            case PHYSICAL_FIRST -> "Physical first";
            case VIRTUAL_FIRST -> "Virtual first";
            case PLAYER_CHOICE -> "Choose payment source";
        };
    }
}
