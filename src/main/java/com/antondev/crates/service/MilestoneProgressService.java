package com.antondev.crates.service;

import com.antondev.crates.database.DatabaseService;
import com.antondev.crates.domain.opening.OpenSource;
import com.antondev.crates.model.Crate;
import com.antondev.crates.model.CrateMilestone;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

/** Main-thread milestone progress cache backed by atomic opening finalization. */
public final class MilestoneProgressService {
    public record Earning(MilestoneService.Earned earned, CrateMilestone milestone) {
        public Earning {
            earned = Objects.requireNonNull(earned, "earned");
            milestone = Objects.requireNonNull(milestone, "milestone");
            if (!earned.milestoneId().equals(milestone.id())) {
                throw new IllegalArgumentException("Milestone earning does not match its definition");
            }
        }
    }

    public record Plan(
            UUID playerId,
            String crateId,
            MilestoneService.Evaluation evaluation,
            List<Earning> newlyEarned) {
        public Plan {
            playerId = Objects.requireNonNull(playerId, "playerId");
            crateId = Objects.requireNonNull(crateId, "crateId");
            evaluation = Objects.requireNonNull(evaluation, "evaluation");
            newlyEarned = List.copyOf(newlyEarned);
        }

        public static Plan paused(UUID playerId, String crateId) {
            MilestoneService.Progress current = MilestoneService.Progress.empty();
            return new Plan(playerId, crateId, new MilestoneService.Evaluation(current, current, List.of()), List.of());
        }

        public boolean changed() { return evaluation.changed(); }
    }

    private record Key(UUID playerId, String crateId) {}

    private final Map<Key, MilestoneService.Progress> progress = new LinkedHashMap<>();

    public MilestoneProgressService(List<DatabaseService.MilestoneState> states) {
        for (DatabaseService.MilestoneState state : Objects.requireNonNull(states, "states")) {
            progress.put(new Key(state.playerId(), state.crateId()), new MilestoneService.Progress(
                    state.openings(), state.revision(), decodeEarned(state.earnedPayload())));
        }
    }

    public MilestoneService.Progress progress(UUID playerId, String crateId) {
        return progress.getOrDefault(new Key(playerId, crateId), MilestoneService.Progress.empty());
    }

    public Plan plan(UUID playerId, Crate crate, int openingCount, OpenSource source,
                     Predicate<CrateMilestone> eligibility, boolean enabled) {
        Objects.requireNonNull(crate, "crate");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(eligibility, "eligibility");
        MilestoneService.Progress before = progress(playerId, crate.id());
        if (!enabled || source == OpenSource.ADMIN_FORCE) {
            return new Plan(playerId, crate.id(), new MilestoneService.Evaluation(before, before, List.of()), List.of());
        }
        List<CrateMilestone> eligible = crate.orderedMilestones().stream().filter(eligibility).toList();
        List<MilestoneService.Definition> definitions = eligible.stream().map(CrateMilestone::definition).toList();
        MilestoneService.Evaluation evaluation = MilestoneService.advance(before, openingCount, definitions);
        Map<String, CrateMilestone> byId = new LinkedHashMap<>();
        eligible.forEach(milestone -> byId.put(milestone.id(), milestone));
        List<Earning> earned = evaluation.newlyEarned().stream()
                .filter(value -> byId.containsKey(value.milestoneId()))
                .map(value -> new Earning(value, byId.get(value.milestoneId()))).toList();
        return new Plan(playerId, crate.id(), evaluation, earned);
    }

    public boolean canApply(Plan plan) {
        Objects.requireNonNull(plan, "plan");
        return progress(plan.playerId(), plan.crateId()).equals(plan.evaluation().before());
    }

    public DatabaseService.MilestoneProgressCommit apply(Plan plan) {
        Objects.requireNonNull(plan, "plan");
        if (!canApply(plan)) throw new IllegalStateException("Milestone progress changed before finalization");
        if (!plan.changed()) return DatabaseService.MilestoneProgressCommit.empty();
        MilestoneService.Progress after = plan.evaluation().after();
        progress.put(new Key(plan.playerId(), plan.crateId()), after);
        long lastCycle = after.earnedKeys().stream().mapToLong(MilestoneProgressService::cycle).max().orElse(0);
        DatabaseService.MilestoneState state = new DatabaseService.MilestoneState(plan.playerId(), plan.crateId(),
                after.openings(), lastCycle, after.revision(), encodeEarned(after.earnedKeys()), Instant.now());
        return new DatabaseService.MilestoneProgressCommit(state,
                plan.evaluation().before().revision(), List.of());
    }

    public void restore(Plan plan) {
        Objects.requireNonNull(plan, "plan");
        progress.put(new Key(plan.playerId(), plan.crateId()), plan.evaluation().before());
    }

    public List<CrateMilestone> next(UUID playerId, Crate crate, Predicate<CrateMilestone> eligibility, int limit) {
        if (limit < 1) return List.of();
        MilestoneService.Progress current = progress(playerId, crate.id());
        var result = new ArrayList<CrateMilestone>();
        crate.orderedMilestones().stream().filter(eligibility)
                .sorted(Comparator.comparingInt(value -> value.definition().position()))
                .forEach(milestone -> {
                    if (result.size() >= limit) return;
                    MilestoneService.Definition definition = milestone.definition();
                    long cycle = definition.repeatPolicy() == MilestoneService.RepeatPolicy.ONCE ? 0
                            : current.openings() < definition.threshold() ? 0
                            : Math.floorDiv(current.openings() - definition.threshold(), definition.cycleLength()) + 1;
                    if (!current.earnedKeys().contains(definition.id() + "#" + cycle)) result.add(milestone);
                });
        return List.copyOf(result);
    }

    public static byte[] encodeEarned(Set<String> earned) {
        if (earned == null || earned.isEmpty()) return new byte[0];
        return earned.stream().sorted().collect(java.util.stream.Collectors.joining("\n"))
                .getBytes(StandardCharsets.UTF_8);
    }

    public static Set<String> decodeEarned(byte[] payload) {
        if (payload == null || payload.length == 0) return Set.of();
        var result = new LinkedHashSet<String>();
        for (String value : new String(payload, StandardCharsets.UTF_8).split("\\R")) {
            String key = value.trim();
            if (!key.isEmpty() && key.matches("[a-z0-9][a-z0-9_-]{0,63}#[0-9]+")) result.add(key);
        }
        return Set.copyOf(result);
    }

    private static long cycle(String key) {
        int separator = key.lastIndexOf('#');
        if (separator < 0) return 0;
        try { return Long.parseLong(key.substring(separator + 1)); }
        catch (NumberFormatException ignored) { return 0; }
    }
}
