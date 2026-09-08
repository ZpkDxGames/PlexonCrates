package com.plexoncrates.migration;

import com.plexoncrates.crate.Crate;
import com.plexoncrates.crate.CrateLocation;
import com.plexoncrates.crate.Reward;
import com.plexoncrates.crate.RewardAction;
import com.plexoncrates.manager.CrateManager;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.bukkit.inventory.ItemStack;

/**
 * Detects the narrow recovery case where Phoenix definitions were already persisted by a previous
 * import attempt but the later historical-database stage failed. No data is mutated here.
 */
public final class PhoenixPartialImportRecovery {
    private static final String ID_CONFLICT = "Target crate ID already exists:";
    private static final String LOCATION_CONFLICT = "Target location is already linked in PlexonCrates:";

    private PhoenixPartialImportRecovery() {}

    public static boolean canResume(CrateManager crates, PhoenixMigrationService.Plan plan) {
        if (crates == null || plan == null || plan.ready() || plan.crates().isEmpty() || plan.errors().isEmpty()) {
            return false;
        }
        if (plan.errors().stream().anyMatch(error ->
                !error.startsWith(ID_CONFLICT) && !error.startsWith(LOCATION_CONFLICT))) {
            return false;
        }
        for (Crate planned : plan.crates()) {
            Crate existing = crates.find(planned.id()).orElse(null);
            if (existing == null || !sameCrate(existing, planned)) return false;
        }
        return true;
    }

    static boolean sameCrate(Crate existing, Crate planned) {
        if (!Objects.equals(existing.id(), planned.id())
                || existing.enabled() != planned.enabled()
                || !Objects.equals(existing.displayName(), planned.displayName())
                || !Objects.equals(existing.description(), planned.description())
                || !Objects.equals(existing.keyDisplayName(), planned.keyDisplayName())
                || !Objects.equals(existing.animation(), planned.animation())
                || !Objects.equals(existing.idleEffect(), planned.idleEffect())
                || !sameItem(existing.icon(), planned.icon())
                || !sameItem(existing.keyItem(), planned.keyItem())) {
            return false;
        }

        Set<String> existingLocations = existing.locations().stream()
                .map(CrateLocation::key).collect(Collectors.toSet());
        Set<String> plannedLocations = planned.locations().stream()
                .map(CrateLocation::key).collect(Collectors.toSet());
        if (!existingLocations.equals(plannedLocations)) return false;

        Map<String, Reward> existingRewards = byId(existing);
        Map<String, Reward> plannedRewards = byId(planned);
        if (!existingRewards.keySet().equals(plannedRewards.keySet())) return false;
        for (String id : plannedRewards.keySet()) {
            if (!sameReward(existingRewards.get(id), plannedRewards.get(id))) return false;
        }
        return true;
    }

    private static Map<String, Reward> byId(Crate crate) {
        Map<String, Reward> result = new LinkedHashMap<>();
        for (Reward reward : crate.rewards()) result.put(reward.id(), reward);
        return result;
    }

    private static boolean sameReward(Reward left, Reward right) {
        if (left.enabled() != right.enabled()
                || left.weight() != right.weight()
                || !sameItem(left.displayItem(), right.displayItem())) {
            return false;
        }
        var leftActions = left.actions();
        var rightActions = right.actions();
        if (leftActions.size() != rightActions.size()) return false;
        for (int i = 0; i < leftActions.size(); i++) {
            if (!sameAction(leftActions.get(i), rightActions.get(i))) return false;
        }
        return true;
    }

    private static boolean sameAction(RewardAction left, RewardAction right) {
        return left.type() == right.type()
                && Objects.equals(left.value(), right.value())
                && sameNullableItem(left.item(), right.item());
    }

    private static boolean sameNullableItem(ItemStack left, ItemStack right) {
        if (left == null || right == null) return left == right;
        return sameItem(left, right);
    }

    private static boolean sameItem(ItemStack left, ItemStack right) {
        return left != null && right != null
                && left.getAmount() == right.getAmount()
                && left.isSimilar(right);
    }
}
