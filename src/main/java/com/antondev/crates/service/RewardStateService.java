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
import java.util.function.Predicate;

/**
 * Main-thread reward availability state. SQLite is the durable copy; this snapshot makes
 * selection and limit checks free of synchronous database work.
 */
public final class RewardStateService {
    public record Plan(List<CrateReward> rewards, boolean pityTriggered) {
        public Plan {
            rewards = List.copyOf(rewards);
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

    /** Selects as many outcomes as remain deliverable, up to the requested amount. */
    public Plan plan(UUID playerId, Crate crate, int requested, OpenSource source,
                     Predicate<CrateReward> baseEligibility, boolean bypassLimits, long now) {
        Map<PlayerRewardKey, PlayerCounter> workingPlayers = new LinkedHashMap<>(players);
        Map<GlobalRewardKey, GlobalCounter> workingGlobal = new LinkedHashMap<>(global);
        int misses = pity.getOrDefault(new PlayerCrateKey(playerId, crate.id()), 0);
        boolean countPity = countsPity(crate.pity(), source);
        boolean triggered = false;
        var selected = new ArrayList<CrateReward>();
        for (int index = 0; index < requested; index++) {
            List<CrateReward> eligible = available(playerId, crate, baseEligibility, bypassLimits, now,
                    workingPlayers, workingGlobal);
            if (eligible.isEmpty()) break;
            boolean guarantee = countPity && due(crate.pity(), misses);
            if (guarantee) {
                eligible = eligible.stream().filter(reward -> pityReward(crate.pity(), reward)).toList();
                if (eligible.isEmpty()) break;
                triggered = true;
            }
            Optional<CrateReward> choice = RewardSelector.selectAt(eligible, normalizedRoll());
            if (choice.isEmpty()) break;
            CrateReward reward = choice.get();
            selected.add(reward);
            increment(playerId, crate.id(), reward, now, workingPlayers, workingGlobal);
            if (countPity) misses = pityReward(crate.pity(), reward) ? 0 : increment(misses);
        }
        return new Plan(selected, triggered);
    }

    /** Revalidates a frozen selection against state changed by another completed opening. */
    public boolean canApply(UUID playerId, Crate crate, List<CrateReward> selected, OpenSource source,
                            Predicate<CrateReward> baseEligibility, boolean bypassLimits, long now) {
        return evaluate(playerId, crate, selected, source, baseEligibility, bypassLimits, now).isPresent();
    }

    /** Applies a previously validated frozen selection and returns its exact durable mutation. */
    public DatabaseService.RewardStateCommit apply(UUID playerId, Crate crate, List<CrateReward> selected,
                                                   OpenSource source, Predicate<CrateReward> baseEligibility,
                                                   boolean bypassLimits, long now) {
        Evaluation evaluated = evaluate(playerId, crate, selected, source, baseEligibility, bypassLimits, now)
                .orElseThrow(() -> new IllegalStateException("Reward limits or pity state changed before delivery"));
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
        for (String rewardId : selected.stream().map(CrateReward::id).distinct().toList()) {
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

    private Optional<Evaluation> evaluate(UUID playerId, Crate crate, List<CrateReward> selected, OpenSource source,
                                          Predicate<CrateReward> baseEligibility, boolean bypassLimits, long now) {
        Map<PlayerRewardKey, PlayerCounter> workingPlayers = new LinkedHashMap<>(players);
        Map<GlobalRewardKey, GlobalCounter> workingGlobal = new LinkedHashMap<>(global);
        int misses = pity.getOrDefault(new PlayerCrateKey(playerId, crate.id()), 0);
        boolean countPity = countsPity(crate.pity(), source);
        for (CrateReward reward : selected) {
            if (!baseEligibility.test(reward)) return Optional.empty();
            PlayerRewardKey playerKey = new PlayerRewardKey(playerId, crate.id(), reward.id());
            GlobalRewardKey globalKey = new GlobalRewardKey(crate.id(), reward.id());
            if (!bypassLimits && !withinLimits(playerId, crate.id(), reward, now,
                    workingPlayers.get(playerKey), workingGlobal.get(globalKey))) return Optional.empty();
            if (countPity && due(crate.pity(), misses) && !pityReward(crate.pity(), reward)) return Optional.empty();
            increment(playerId, crate.id(), reward, now, workingPlayers, workingGlobal);
            if (countPity) misses = pityReward(crate.pity(), reward) ? 0 : increment(misses);
        }
        return Optional.of(new Evaluation(workingPlayers, workingGlobal, misses));
    }

    private static List<CrateReward> available(UUID playerId, Crate crate,
                                               Predicate<CrateReward> baseEligibility, boolean bypassLimits, long now,
                                               Map<PlayerRewardKey, PlayerCounter> workingPlayers,
                                               Map<GlobalRewardKey, GlobalCounter> workingGlobal) {
        return crate.orderedRewards().stream().filter(baseEligibility).filter(reward -> bypassLimits || withinLimits(
                playerId, crate.id(), reward, now,
                workingPlayers.get(new PlayerRewardKey(playerId, crate.id(), reward.id())),
                workingGlobal.get(new GlobalRewardKey(crate.id(), reward.id())))).toList();
    }

    private static boolean withinLimits(UUID playerId, String crateId, CrateReward reward, long now,
                                        PlayerCounter rawPlayer, GlobalCounter rawGlobal) {
        RewardLimits limits = reward.limits();
        PlayerCounter player = normalized(rawPlayer, limits.playerWindowSeconds(), now);
        GlobalCounter global = normalized(rawGlobal, limits.globalWindowSeconds(), now);
        if (limits.playerLifetime() > 0 && player.total() >= limits.playerLifetime()) return false;
        if (limits.playerWindow() > 0 && player.window() >= limits.playerWindow()) return false;
        if (limits.globalLifetime() > 0 && global.total() >= limits.globalLifetime()) return false;
        if (limits.globalWindow() > 0 && global.window() >= limits.globalWindow()) return false;
        return limits.cooldownSeconds() <= 0 || player.lastWonAt() <= 0
                || now - player.lastWonAt() >= seconds(limits.cooldownSeconds());
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
        return policy.rewardIds().contains(reward.id()) || (policy.rarity() != null && policy.rarity() == reward.rarity());
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
