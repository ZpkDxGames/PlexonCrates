package com.antondev.crates.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.SplittableRandom;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Pure analytical probability simulator for PlexonCrates 5.0.
 *
 * <p>This service intentionally knows nothing about Bukkit, ItemStacks,
 * opening journals, payments, rewards, commands, economy, claims, milestones,
 * rerolls, or persistence. Callers must snapshot immutable scalar probability
 * data on the primary thread before submitting work here.</p>
 */
public final class CrateSimulationService implements AutoCloseable {
    public static final int DEFAULT_SAMPLES = 10_000;
    public static final int MAX_SAMPLES = 100_000;
    public static final int MIN_SAMPLES = 1;
    private static final int MAX_QUEUED_REQUESTS = 8;

    public enum Mode {
        CONFIGURED,
        PLAYER_CONTEXT
    }

    public record Probability(String id, int baseBasisPoints, boolean enabled, boolean contextEligible) {
        public Probability {
            id = requireId(id);
            if (baseBasisPoints < 0 || baseBasisPoints > ChanceAllocator.TOTAL_BASIS_POINTS) {
                throw new IllegalArgumentException("baseBasisPoints must be between 0 and 10,000");
            }
        }
    }

    public record Snapshot(
            String crateId,
            long revision,
            Mode mode,
            List<Probability> rewards,
            List<String> publicationIssues,
            String contextNote) {
        public Snapshot {
            crateId = requireId(crateId);
            if (revision < 0) throw new IllegalArgumentException("revision cannot be negative");
            mode = Objects.requireNonNull(mode, "mode");
            rewards = List.copyOf(Objects.requireNonNull(rewards, "rewards"));
            publicationIssues = List.copyOf(Objects.requireNonNull(publicationIssues, "publicationIssues"));
            contextNote = Objects.requireNonNullElse(contextNote, "");
        }

        public int enabledRewardCount() {
            return (int) rewards.stream().filter(Probability::enabled).count();
        }

        public int disabledRewardCount() {
            return rewards.size() - enabledRewardCount();
        }

        public int configuredBasisPointTotal() {
            return rewards.stream().filter(Probability::enabled)
                    .mapToInt(Probability::baseBasisPoints).sum();
        }

        public int eligibleBasisPointTotal() {
            return rewards.stream().filter(value -> included(value, mode))
                    .mapToInt(Probability::baseBasisPoints).sum();
        }

        public boolean publicationReady() {
            return publicationIssues.isEmpty();
        }
    }

    public record Outcome(
            String id,
            int baseBasisPoints,
            int expectedBasisPoints,
            int observedCount,
            double observedPercent,
            double deviationPercentagePoints) {
        public Outcome {
            id = requireId(id);
            if (baseBasisPoints < 0 || expectedBasisPoints < 0 || observedCount < 0) {
                throw new IllegalArgumentException("Outcome values cannot be negative");
            }
        }
    }

    public record Report(
            Snapshot snapshot,
            int samples,
            long seed,
            String drySelection,
            List<Outcome> outcomes,
            long elapsedNanos) {
        public Report {
            snapshot = Objects.requireNonNull(snapshot, "snapshot");
            validateSamples(samples);
            drySelection = requireId(drySelection);
            outcomes = List.copyOf(Objects.requireNonNull(outcomes, "outcomes"));
            if (elapsedNanos < 0) throw new IllegalArgumentException("elapsedNanos cannot be negative");
        }
    }

    private final ThreadPoolExecutor executor;

    public CrateSimulationService() {
        AtomicInteger threadIndex = new AtomicInteger();
        ThreadFactory factory = task -> {
            Thread thread = new Thread(task, "PlexonCrates-Simulation-" + threadIndex.incrementAndGet());
            thread.setDaemon(true);
            thread.setUncaughtExceptionHandler((ignored, error) -> error.printStackTrace());
            return thread;
        };
        executor = new ThreadPoolExecutor(
                1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(MAX_QUEUED_REQUESTS), factory,
                new ThreadPoolExecutor.AbortPolicy());
    }

    public CompletableFuture<Report> simulateAsync(Snapshot snapshot, int samples, long seed) {
        Objects.requireNonNull(snapshot, "snapshot");
        validateSamples(samples);
        try {
            return CompletableFuture.supplyAsync(() -> simulate(snapshot, samples, seed), executor);
        } catch (RejectedExecutionException error) {
            return CompletableFuture.failedFuture(new IllegalStateException(
                    "Simulation queue is full; finish an existing request before starting another", error));
        }
    }

    public Report simulate(Snapshot snapshot, int samples, long seed) {
        Objects.requireNonNull(snapshot, "snapshot");
        validateSamples(samples);
        long started = System.nanoTime();
        List<ChanceAllocator.Chance> plan = normalizedPlan(snapshot);
        SplittableRandom random = new SplittableRandom(seed);
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Probability reward : snapshot.rewards()) counts.put(reward.id(), 0);
        String drySelection = select(plan, new SplittableRandom(seed ^ 0x5DEECE66DL));
        for (int index = 0; index < samples; index++) {
            String selected = select(plan, random);
            counts.compute(selected, (ignored, current) -> current == null ? 1 : current + 1);
        }
        Map<String, Integer> expected = new LinkedHashMap<>();
        for (ChanceAllocator.Chance chance : plan) expected.put(chance.id(), chance.basisPoints());
        List<Outcome> outcomes = new ArrayList<>(snapshot.rewards().size());
        for (Probability reward : snapshot.rewards()) {
            int expectedBasisPoints = expected.getOrDefault(reward.id(), 0);
            int count = counts.getOrDefault(reward.id(), 0);
            double observed = count * 100.0 / samples;
            double expectedPercent = expectedBasisPoints / 100.0;
            outcomes.add(new Outcome(
                    reward.id(), reward.baseBasisPoints(), expectedBasisPoints, count,
                    observed, Math.abs(observed - expectedPercent)));
        }
        return new Report(snapshot, samples, seed, drySelection, outcomes, System.nanoTime() - started);
    }

    public String dryRun(Snapshot snapshot, long seed) {
        Objects.requireNonNull(snapshot, "snapshot");
        return select(normalizedPlan(snapshot), new SplittableRandom(seed));
    }

    public int queuedRequests() {
        return executor.getQueue().size();
    }

    public int activeRequests() {
        return executor.getActiveCount();
    }

    public static boolean isCurrent(Snapshot snapshot, long currentRevision) {
        return snapshot != null && snapshot.revision() == currentRevision;
    }

    private static List<ChanceAllocator.Chance> normalizedPlan(Snapshot snapshot) {
        List<ChanceAllocator.Chance> configured = snapshot.rewards().stream()
                .filter(value -> included(value, snapshot.mode()))
                .filter(value -> value.baseBasisPoints() > 0)
                .map(value -> new ChanceAllocator.Chance(value.id(), value.baseBasisPoints(), false))
                .toList();
        if (configured.isEmpty()) {
            throw new IllegalArgumentException("No positive eligible reward exists for this simulation mode");
        }
        return ChanceAllocator.normalize(configured).chances();
    }

    private static boolean included(Probability value, Mode mode) {
        if (!value.enabled()) return false;
        return mode == Mode.CONFIGURED || value.contextEligible();
    }

    private static String select(List<ChanceAllocator.Chance> plan, SplittableRandom random) {
        return ChanceAllocator.selectTicket(plan, random.nextInt(ChanceAllocator.TOTAL_BASIS_POINTS));
    }

    private static void validateSamples(int samples) {
        if (samples < MIN_SAMPLES || samples > MAX_SAMPLES) {
            throw new IllegalArgumentException("samples must be between 1 and " + MAX_SAMPLES);
        }
    }

    private static String requireId(String value) {
        String normalized = Objects.requireNonNull(value, "value").trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException("value cannot be blank");
        return normalized;
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }
}
