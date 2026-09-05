package com.antondev.crates.service;

import com.antondev.crates.database.DatabaseService;
import com.antondev.crates.domain.opening.OpenSource;
import com.antondev.crates.domain.reward.PityPolicy;
import com.antondev.crates.domain.reward.RewardLimits;
import com.antondev.crates.model.Crate;
import com.antondev.crates.model.CrateReward;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.DoubleSupplier;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Main-thread reward availability state. SQLite is the durable copy; this snapshot makes
 * selection and limit checks free of synchronous database work.
 */
public final class RewardStateService {
    /** One source ticket and the exact reward it resolves to for this player. */
    public record Outcome(CrateReward source, CrateReward actual,
                          AlternativeRewardResolver.Reason alternativeReason) {
        public Outcome {
            source = java.util.Objects.requireNonNull(source, "source");
            actual = java.util.Objects.requireNonNull(actual, "actual");
            if (source.id().equals(actual.id()) && alternativeReason != null) {
                throw new IllegalArgumentException("A direct reward cannot have a fallback reason");
            }
            if (!source.id().equals(actual.id()) && alternativeReason == null) {
                throw new IllegalArgumentException("A fallback reward needs its source reason");
            }
        }

        public boolean fallback() {
            return !source.id().equals(actual.id());
        }
    }

    /** Frozen actual deliveries plus their source-ticket audit metadata. */
    public record Plan(List<CrateReward> rewards, boolean pityTriggered, List<Outcome> outcomes) {
        public Plan {
            rewards = List.copyOf(rewards);
            outcomes = List.copyOf(outcomes);
            if (rewards.size() != outcomes.size()) {
                throw new IllegalArgumentException("Every frozen reward needs one outcome entry");
            }
            for (int index = 0; index < rewards.size(); index++) {
                if (!rewards.get(index).id().equals(outcomes.get(index).actual().id())) {
                    throw new IllegalArgumentException("Frozen rewards and outcomes do not match");
                }
            }
        }

        public Plan(List<CrateReward> rewards, boolean pityTriggered) {
            this(rewards, pityTriggered, rewards.stream()
                    .map(reward -> new Outcome(reward, reward, null)).toList());
        }

        public String outcomeDetail() {
            var entries = new ArrayList<String>();
            for (int index = 0; index < outcomes.size(); index++) {
                Outcome outcome = outcomes.get(index);
                entries.add(index + ":source=" + outcome.source().id()
                        + ",actual=" + outcome.actual().id()
                        + ",fallback=" + outcome.fallback()
                        + ",reason=" + (outcome.alternativeReason() == null
                        ? "NONE" : outcome.alternativeReason().name()));
            }
            return "outcomes[" + String.join(";", entries) + "]";
        }
    }

    private record PlayerRewardKey(UUID playerId, String crateId, String rewardId) {}
    private record GlobalRewardKey(String crateId, String rewardId) {}
    private record PlayerCrateKey(UUID playerId, String crateId) {}
    private record PlayerCounter(long total, long window, long windowStartedAt, long lastWonAt) {}
    private record GlobalCounter(long total, long window, long windowStartedAt) {}
    private record Evaluation(Map<PlayerRewardKey, PlayerCounter> players,
                              Map<GlobalRewardKey, GlobalCounter> global, int pityMisses) {}

    private final Map<PlayerRewardKey, PlayerCounter> players = new LinkedHashMap<>();
    private final Map<GlobalRewardKey, GlobalCounter> global = new LinkedHashMap<>();
    private final Map<PlayerCrateKey, Integer> pity = new LinkedHashMap<>();
    private final DoubleSupplier rolls;

    public RewardStateService(DatabaseService.RewardStateSnapshot snapshot) {
        this(snapshot, () -> ThreadLocalRandom.current().nextDouble());
    }

    RewardStateService(DatabaseService.RewardStateSnapshot snapshot, DoubleSupplier rolls) {
        this.rolls = rolls;
        for (DatabaseService.RewardPlayerState state : snapshot.players()) {
            players.put(new PlayerRewardKey(state.playerId(), state.crateId(), state.rewardId()),
                    new PlayerCounter(state.totalWins(), state.windowWins(), state.windowStartedAt(), state.lastWonAt()));
        }
        for (DatabaseService.RewardGlobalState state : snapshot.global()) {
            global.put(new GlobalRewardKey(state.crateId(), state.rewardId()),
                    new GlobalCounter(state.totalWins(), state.windowWins(), state.windowStartedAt()));
        }
        for (DatabaseService.PityState state : snapshot.pity()) {
            pity.put(new PlayerCrateKey(state.playerId(), state.crateId()), state.misses());
        }
    }

    public boolean eligible(UUID playerId, Crate crate, CrateReward reward, long now, boolean bypassLimits) {
        if (bypassLimits) return true;
        return withinLimits(playerId, crate.id(), reward, now,
                players.get(new PlayerRewardKey(playerId, crate.id(), reward.id())),
                global.get(new GlobalRewardKey(crate.id(), reward.id())));
    }

    /** Compatibility planner without alternative resolution. */
    public Plan plan(UUID playerId, Crate crate, int requested, OpenSource source,
                     Predicate<CrateReward> baseEligibility, boolean bypassLimits, long now) {
        return planResolved(playerId, crate, requested, source,
                reward -> baseEligibility.test(reward) ? null
                        : AlternativeRewardResolver.Reason.TRANSACTION_FAILURE,
                false, bypassLimits, now);
    }

    /**
     * Selects source tickets by their configured chances, resolves an allowed one-edge fallback,
     * and advances the actual reward's counters after every result in the batch.
     */
    public Plan planResolved(UUID playerId, Crate crate, int requested, OpenSource source,
                             Function<CrateReward, AlternativeRewardResolver.Reason> baseIneligibility,
                             boolean alternativesEnabled, boolean bypassLimits, long now) {
        Map<PlayerRewardKey, PlayerCounter> workingPlayers = new LinkedHashMap<>(players);
        Map<GlobalRewardKey, GlobalCounter> workingGlobal = new LinkedHashMap<>(global);
        int misses = pity.getOrDefault(new PlayerCrateKey(playerId, crate.id()), 0);
        boolean countPity = countsPity(crate.pity(), source);
        boolean triggered = false;
        var outcomes = new ArrayList<Outcome>();
        for (int index = 0; index < requested; index++) {
            List<Outcome> available = availableResolved(playerId, crate, baseIneligibility,
                    alternativesEnabled, bypassLimits, now, workingPlayers, workingGlobal);
            if (available.isEmpty()) break;
            boolean guarantee = countPity && due(crate.pity(), misses);
            if (guarantee) {
                available = available.stream().filter(outcome -> pityReward(crate.pity(), outcome.source())).toList();
                if (available.isEmpty()) break;
                triggered = true;
            }
            List<CrateReward> sourceTickets = available.stream().map(Outcome::source).toList();
            Optional<CrateReward> ticket = RewardSelector.selectAt(sourceTickets, normalizedRoll());
            if (ticket.isEmpty()) break;
            CrateReward selectedSource = ticket.get();
            Outcome outcome = available.stream()
                    .filter(candidate -> candidate.source().id().equals(selectedSource.id()))
                    .findFirst().orElseThrow();
            outcomes.add(outcome);
            increment(playerId, crate.id(), outcome.actual(), now, workingPlayers, workingGlobal);
            if (countPity) misses = pityReward(crate.pity(), outcome.source()) ? 0 : increment(misses);
        }
        return new Plan(outcomes.stream().map(Outcome::actual).toList(), triggered, outcomes);
    }

    /** Compatibility selective planner without alternative resolution. */
    public Plan planSelected(UUID playerId, Crate crate, String rewardId, int requested, OpenSource source,
                             Predicate<CrateReward> baseEligibility, boolean bypassLimits, long now) {
        return planSelectedResolved(playerId, crate, rewardId, requested, source,
                reward -> baseEligibility.test(reward) ? null
                        : AlternativeRewardResolver.Reason.TRANSACTION_FAILURE,
                false, bypassLimits, now);
    }

    /** Validates a deliberate source reward and freezes an all-or-nothing resolved batch. */
    public Plan planSelectedResolved(UUID playerId, Crate crate, String rewardId, int requested, OpenSource source,
                                     Function<CrateReward, AlternativeRewardResolver.Reason> baseIneligibility,
                                     boolean alternativesEnabled, boolean bypassLimits, long now) {
        if (requested < 1 || rewardId == null) return new Plan(List.of(), false);
        CrateReward sourceReward = crate.rewards().get(rewardId);
        if (sourceReward == null) return new Plan(List.of(), false);
        Map<PlayerRewardKey, PlayerCounter> workingPlayers = new LinkedHashMap<>(players);
        Map<GlobalRewardKey, GlobalCounter> workingGlobal = new LinkedHashMap<>(global);
        var outcomes = new ArrayList<Outcome>();
        for (int index = 0; index < requested; index++) {
            Optional<Outcome> resolved = resolveAgainst(playerId, crate, sourceReward, baseIneligibility,
                    alternativesEnabled, bypassLimits, now, workingPlayers, workingGlobal);
            if (resolved.isEmpty()) return new Plan(List.of(), false);
            Outcome outcome = resolved.get();
            outcomes.add(outcome);
            increment(playerId, crate.id(), outcome.actual(), now, workingPlayers, workingGlobal);
        }
        Plan plan = new Plan(outcomes.stream().map(Outcome::actual).toList(), false, outcomes);
        return evaluateResolved(playerId, crate, plan, source, baseIneligibility,
                alternativesEnabled, bypassLimits, now).isPresent() ? plan : new Plan(List.of(), false);
    }

    /** Resolves a single source against the current snapshot for an accurate preview. */
    public Optional<Outcome> resolveOutcome(UUID playerId, Crate crate, CrateReward source,
                                            Function<CrateReward, AlternativeRewardResolver.Reason> baseIneligibility,
                                            boolean alternativesEnabled, boolean bypassLimits, long now) {
        return resolveAgainst(playerId, crate, source, baseIneligibility, alternativesEnabled, bypassLimits, now,
                players, global);
    }

    /** Compatibility revalidation for a direct frozen selection. */
    public boolean canApply(UUID playerId, Crate crate, List<CrateReward> selected, OpenSource source,
                            Predicate<CrateReward> baseEligibility, boolean bypassLimits, long now) {
        return canApplyResolved(playerId, crate, new Plan(selected, false), source,
                reward -> baseEligibility.test(reward) ? null
                        : AlternativeRewardResolver.Reason.TRANSACTION_FAILURE,
                false, bypassLimits, now);
    }

    /** Revalidates the exact frozen source-to-actual mapping before value is consumed. */
    public boolean canApplyResolved(UUID playerId, Crate crate, Plan plan, OpenSource source,
                                    Function<CrateReward, AlternativeRewardResolver.Reason> baseIneligibility,
                                    boolean alternativesEnabled, boolean bypassLimits, long now) {
        return evaluateResolved(playerId, crate, plan, source, baseIneligibility,
                alternativesEnabled, bypassLimits, now).isPresent();
    }

    /** Compatibility mutation for direct rewards. */
    public DatabaseService.RewardStateCommit apply(UUID playerId, Crate crate, List<CrateReward> selected,
                                                   OpenSource source, Predicate<CrateReward> baseEligibility,
                                                   boolean bypassLimits, long now) {
        return applyResolved(playerId, crate, new Plan(selected, false), source,
                reward -> baseEligibility.test(reward) ? null
                        : AlternativeRewardResolver.Reason.TRANSACTION_FAILURE,
                false, bypassLimits, now);
    }

    /** Applies a resolved frozen plan and returns its exact durable mutation. */
    public DatabaseService.RewardStateCommit applyResolved(UUID playerId, Crate crate, Plan plan,
                                                           OpenSource source,
                                                           Function<CrateReward, AlternativeRewardResolver.Reason> baseIneligibility,
                                                           boolean alternativesEnabled,
                                                           boolean bypassLimits, long now) {
        Evaluation evaluated = evaluateResolved(playerId, crate, plan, source, baseIneligibility,
                alternativesEnabled, bypassLimits, now)
                .orElseThrow(() -> new IllegalStateException("Reward state changed before delivery"));
        players.clear();
        players.putAll(evaluated.players());
        global.clear();
        global.putAll(evaluated.global());

        DatabaseService.PityState pityMutation = null;
        if (countsPity(crate.pity(), source)) {
            PlayerCrateKey key = new PlayerCrateKey(playerId, crate.id());
            pity.put(key, evaluated.pityMisses());
            pityMutation = new DatabaseService.PityState(playerId, crate.id(), evaluated.pityMisses());
        }

        var mutations = new ArrayList<DatabaseService.RewardMutation>();
        for (String rewardId : plan.rewards().stream().map(CrateReward::id).distinct().toList()) {
            PlayerCounter player = players.get(new PlayerRewardKey(playerId, crate.id(), rewardId));
            GlobalCounter server = global.get(new GlobalRewardKey(crate.id(), rewardId));
            mutations.add(new DatabaseService.RewardMutation(
                    new DatabaseService.RewardPlayerState(playerId, crate.id(), rewardId, player.total(),
                            player.window(), player.windowStartedAt(), player.lastWonAt()),
                    new DatabaseService.RewardGlobalState(crate.id(), rewardId, server.total(),
                            server.window(), server.windowStartedAt())));
        }
        return new DatabaseService.RewardStateCommit(mutations, pityMutation);
    }

    public int pityMisses(UUID playerId, String crateId) {
        return pity.getOrDefault(new PlayerCrateKey(playerId, crateId), 0);
    }

    public int pityRemaining(UUID playerId, Crate crate) {
        if (!crate.pity().enabled()) return -1;
        return Math.max(0, crate.pity().threshold() - pityMisses(playerId, crate.id()));
    }

    private Optional<Evaluation> evaluateResolved(
            UUID playerId, Crate crate, Plan plan, OpenSource source,
            Function<CrateReward, AlternativeRewardResolver.Reason> baseIneligibility,
            boolean alternativesEnabled, boolean bypassLimits, long now) {
        Map<PlayerRewardKey, PlayerCounter> workingPlayers = new LinkedHashMap<>(players);
        Map<GlobalRewardKey, GlobalCounter> workingGlobal = new LinkedHashMap<>(global);
        int misses = pity.getOrDefault(new PlayerCrateKey(playerId, crate.id()), 0);
        boolean countPity = countsPity(crate.pity(), source);
        for (Outcome frozen : plan.outcomes()) {
            CrateReward currentSource = crate.rewards().get(frozen.source().id());
            if (currentSource == null) return Optional.empty();
            Optional<Outcome> current = resolveAgainst(playerId, crate, currentSource, baseIneligibility,
                    alternativesEnabled, bypassLimits, now, workingPlayers, workingGlobal);
            if (current.isEmpty() || !sameResolution(frozen, current.get())) return Optional.empty();
            if (countPity && due(crate.pity(), misses) && !pityReward(crate.pity(), currentSource)) {
                return Optional.empty();
            }
            increment(playerId, crate.id(), current.get().actual(), now, workingPlayers, workingGlobal);
            if (countPity) misses = pityReward(crate.pity(), currentSource) ? 0 : increment(misses);
        }
        return Optional.of(new Evaluation(workingPlayers, workingGlobal, misses));
    }

    private static List<Outcome> availableResolved(
            UUID playerId, Crate crate,
            Function<CrateReward, AlternativeRewardResolver.Reason> baseIneligibility,
            boolean alternativesEnabled, boolean bypassLimits, long now,
            Map<PlayerRewardKey, PlayerCounter> workingPlayers,
            Map<GlobalRewardKey, GlobalCounter> workingGlobal) {
        var available = new ArrayList<Outcome>();
        for (CrateReward source : crate.orderedRewards()) {
            resolveAgainst(playerId, crate, source, baseIneligibility, alternativesEnabled, bypassLimits,
                    now, workingPlayers, workingGlobal).ifPresent(available::add);
        }
        return List.copyOf(available);
    }

    private static Optional<Outcome> resolveAgainst(
            UUID playerId, Crate crate, CrateReward source,
            Function<CrateReward, AlternativeRewardResolver.Reason> baseIneligibility,
            boolean alternativesEnabled, boolean bypassLimits, long now,
            Map<PlayerRewardKey, PlayerCounter> workingPlayers,
            Map<GlobalRewardKey, GlobalCounter> workingGlobal) {
        if (source == null || !source.enabled() || source.chanceBasisPoints() <= 0 || !source.hasDelivery()) {
            return Optional.empty();
        }
        AlternativeRewardResolver.Reason sourceReason = baseIneligibility.apply(source);
        if (sourceReason == null && !bypassLimits) {
            sourceReason = limitReason(playerId, crate.id(), source, now, workingPlayers, workingGlobal);
        }
        if (sourceReason == null) return Optional.of(new Outcome(source, source, null));
        if (!alternativesEnabled || !source.hasAlternative()
                || !source.alternativeReasons().contains(sourceReason)
                || !AlternativeRewardResolver.fallbackReasonAllowed(sourceReason)) {
            return Optional.empty();
        }
        CrateReward fallback = crate.rewards().get(source.alternativeRewardId());
        // A zero-chance reward may be a deliberate fallback target; it is never an independent source ticket.
        if (fallback == null || !fallback.enabled() || !fallback.hasDelivery()) return Optional.empty();
        if (baseIneligibility.apply(fallback) != null) return Optional.empty();
        if (!bypassLimits && limitReason(playerId, crate.id(), fallback, now,
                workingPlayers, workingGlobal) != null) return Optional.empty();
        return Optional.of(new Outcome(source, fallback, sourceReason));
    }

    private static AlternativeRewardResolver.Reason limitReason(
            UUID playerId, String crateId, CrateReward reward, long now,
            Map<PlayerRewardKey, PlayerCounter> workingPlayers,
            Map<GlobalRewardKey, GlobalCounter> workingGlobal) {
        RewardLimits limits = reward.limits();
        PlayerCounter player = normalized(workingPlayers.get(
                new PlayerRewardKey(playerId, crateId, reward.id())), limits.playerWindowSeconds(), now);
        GlobalCounter server = normalized(workingGlobal.get(
                new GlobalRewardKey(crateId, reward.id())), limits.globalWindowSeconds(), now);
        if (limits.playerLifetime() > 0 && player.total() >= limits.playerLifetime()) {
            return AlternativeRewardResolver.Reason.PLAYER_LIMIT;
        }
        if (limits.playerWindow() > 0 && player.window() >= limits.playerWindow()) {
            return AlternativeRewardResolver.Reason.PLAYER_LIMIT;
        }
        if (limits.globalLifetime() > 0 && server.total() >= limits.globalLifetime()) {
            return AlternativeRewardResolver.Reason.GLOBAL_LIMIT;
        }
        if (limits.globalWindow() > 0 && server.window() >= limits.globalWindow()) {
            return AlternativeRewardResolver.Reason.GLOBAL_LIMIT;
        }
        if (limits.cooldownSeconds() > 0 && player.lastWonAt() > 0
                && now - player.lastWonAt() < seconds(limits.cooldownSeconds())) {
            return AlternativeRewardResolver.Reason.COOLDOWN;
        }
        return null;
    }

    private static boolean sameResolution(Outcome expected, Outcome actual) {
        return expected.source().id().equals(actual.source().id())
                && expected.actual().id().equals(actual.actual().id())
                && expected.alternativeReason() == actual.alternativeReason();
    }

    private static boolean withinLimits(UUID playerId, String crateId, CrateReward reward, long now,
                                        PlayerCounter rawPlayer, GlobalCounter rawGlobal) {
        var playerState = new LinkedHashMap<PlayerRewardKey, PlayerCounter>();
        var globalState = new LinkedHashMap<GlobalRewardKey, GlobalCounter>();
        if (rawPlayer != null) playerState.put(new PlayerRewardKey(playerId, crateId, reward.id()), rawPlayer);
        if (rawGlobal != null) globalState.put(new GlobalRewardKey(crateId, reward.id()), rawGlobal);
        return limitReason(playerId, crateId, reward, now, playerState, globalState) == null;
    }

    private static void increment(UUID playerId, String crateId, CrateReward reward, long now,
                                  Map<PlayerRewardKey, PlayerCounter> workingPlayers,
                                  Map<GlobalRewardKey, GlobalCounter> workingGlobal) {
        RewardLimits limits = reward.limits();
        PlayerRewardKey playerKey = new PlayerRewardKey(playerId, crateId, reward.id());
        GlobalRewardKey globalKey = new GlobalRewardKey(crateId, reward.id());
        PlayerCounter player = normalized(workingPlayers.get(playerKey), limits.playerWindowSeconds(), now);
        GlobalCounter server = normalized(workingGlobal.get(globalKey), limits.globalWindowSeconds(), now);
        long playerWindowStarted = limits.playerWindowSeconds() > 0 && player.windowStartedAt() == 0
                ? now : player.windowStartedAt();
        long globalWindowStarted = limits.globalWindowSeconds() > 0 && server.windowStartedAt() == 0
                ? now : server.windowStartedAt();
        workingPlayers.put(playerKey, new PlayerCounter(increment(player.total()), increment(player.window()),
                playerWindowStarted, now));
        workingGlobal.put(globalKey, new GlobalCounter(increment(server.total()), increment(server.window()),
                globalWindowStarted));
    }

    private static PlayerCounter normalized(PlayerCounter value, long windowSeconds, long now) {
        PlayerCounter result = value == null ? new PlayerCounter(0, 0, 0, 0) : value;
        if (windowSeconds > 0 && result.windowStartedAt() > 0
                && now - result.windowStartedAt() >= seconds(windowSeconds)) {
            return new PlayerCounter(result.total(), 0, now, result.lastWonAt());
        }
        return result;
    }

    private static GlobalCounter normalized(GlobalCounter value, long windowSeconds, long now) {
        GlobalCounter result = value == null ? new GlobalCounter(0, 0, 0) : value;
        if (windowSeconds > 0 && result.windowStartedAt() > 0
                && now - result.windowStartedAt() >= seconds(windowSeconds)) {
            return new GlobalCounter(result.total(), 0, now);
        }
        return result;
    }

    private static boolean countsPity(PityPolicy policy, OpenSource source) {
        return policy.enabled() && (source != OpenSource.ADMIN_FORCE || policy.administrativeOpeningsCount());
    }

    private static boolean due(PityPolicy policy, int misses) {
        return policy.enabled() && misses + 1 >= policy.threshold();
    }

    private static boolean pityReward(PityPolicy policy, CrateReward reward) {
        return policy.rewardIds().contains(reward.id())
                || (policy.rarity() != null && policy.rarity() == reward.rarity());
    }

    private double normalizedRoll() {
        double value = rolls.getAsDouble();
        if (!Double.isFinite(value)) return 0;
        if (value < 0) return 0;
        return value >= 1 ? Math.nextDown(1.0) : value;
    }

    private static long seconds(long value) {
        try {
            return Math.multiplyExact(value, 1000L);
        } catch (ArithmeticException ignored) {
            return Long.MAX_VALUE;
        }
    }

    private static long increment(long value) {
        return value == Long.MAX_VALUE ? value : value + 1;
    }

    private static int increment(int value) {
        return value == Integer.MAX_VALUE ? value : value + 1;
    }
}
