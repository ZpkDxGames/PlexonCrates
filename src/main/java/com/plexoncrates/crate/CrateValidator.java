package com.plexoncrates.crate;

import com.plexoncrates.util.ItemCodec;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.bukkit.inventory.ItemStack;

/** Central release/publish validation for crate definitions. */
public final class CrateValidator {
    private CrateValidator() {}

    public record Issue(String code, String message) {}

    public record Result(List<Issue> issues) {
        public Result {
            issues = List.copyOf(issues);
        }

        public boolean valid() {
            return issues.isEmpty();
        }

        public String summary() {
            if (issues.isEmpty()) return "valid";
            return issues.get(0).message() + (issues.size() > 1 ? " (and " + (issues.size() - 1) + " more)" : "");
        }
    }

    public static Result validate(Crate crate) {
        List<Issue> issues = new ArrayList<>();
        if (crate == null) {
            issues.add(new Issue("crate.null", "Crate is missing"));
            return new Result(issues);
        }

        if (crate.id() == null || crate.id().isBlank() || crate.id().length() > 64) {
            issues.add(new Issue("crate.id", "Crate ID is invalid"));
        }
        validateItem("crate.icon", crate.icon(), issues);
        validateItem("crate.key", crate.keyItem(), issues);

        List<Reward> enabled = crate.rewards().stream().filter(Reward::enabled).toList();
        if (enabled.isEmpty()) issues.add(new Issue("rewards.empty", "At least one enabled reward is required"));
        long totalWeight = enabled.stream().mapToLong(Reward::weight).sum();
        if (!enabled.isEmpty() && totalWeight <= 0L) {
            issues.add(new Issue("rewards.weight", "Enabled rewards must have positive total weight"));
        }

        for (Reward reward : crate.rewards()) {
            if (reward.id() == null || reward.id().isBlank()) {
                issues.add(new Issue("reward.id", "A reward has an invalid ID"));
            }
            if (reward.enabled() && reward.weight() <= 0) {
                issues.add(new Issue("reward.weight." + reward.id(),
                        "Enabled reward " + reward.id() + " has zero weight"));
            }
            validateItem("reward.display." + reward.id(), reward.displayItem(), issues);
            if (reward.actions().isEmpty()) {
                issues.add(new Issue("reward.actions." + reward.id(),
                        "Reward " + reward.id() + " has no delivery actions"));
            }
            int actionIndex = 0;
            for (RewardAction action : reward.actions()) {
                if (action.type() == RewardActionType.ITEM && action.item() != null) {
                    validateItem("reward.action." + reward.id() + "." + actionIndex, action.item(), issues);
                }
                actionIndex++;
            }
        }

        String animation = crate.animation() == null ? "" : crate.animation().toUpperCase(Locale.ROOT);
        if (!List.of("CSGO", "WHEEL", "INSTANT").contains(animation)) {
            issues.add(new Issue("animation.unknown", "Unknown opening animation: " + crate.animation()));
        }
        String idle = crate.idleEffect() == null ? "" : crate.idleEffect().toUpperCase(Locale.ROOT);
        if (!List.of("HELIX", "CLOUD", "FOUNTAIN", "RING", "PULSE", "NONE").contains(idle)) {
            issues.add(new Issue("idle.unknown", "Unknown idle effect: " + crate.idleEffect()));
        }
        return new Result(issues);
    }

    private static void validateItem(String code, ItemStack item, List<Issue> issues) {
        if (item == null || item.getType().isAir()) {
            issues.add(new Issue(code, "Required item is missing"));
            return;
        }
        try {
            ItemCodec.snapshot(item);
        } catch (RuntimeException error) {
            String detail = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
            issues.add(new Issue(code, "Exact item round-trip failed: " + detail));
        }
    }
}
