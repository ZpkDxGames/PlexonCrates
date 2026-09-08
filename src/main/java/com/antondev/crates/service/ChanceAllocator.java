package com.antondev.crates.service;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Exact percentage allocation for PlexonCrates 3.0.
 *
 * <p>All public values use integer basis points: {@code 10_000 == 100.00%}.
 * Allocation uses the stable largest-remainder method and never feeds rounded
 * display values back into selection.</p>
 */
public final class ChanceAllocator {
    public static final int TOTAL_BASIS_POINTS = 10_000;
    public static final int ONE_PERCENT = 100;

    private ChanceAllocator() {}

    public record Chance(String id, int basisPoints, boolean locked) {
        public Chance {
            id = requireId(id);
            if (basisPoints < 0 || basisPoints > TOTAL_BASIS_POINTS) {
                throw new IllegalArgumentException("Chance must be between 0 and 10,000 basis points");
            }
        }

        public Chance withBasisPoints(int value) {
            return new Chance(id, value, locked);
        }

        public Chance withLocked(boolean value) {
            return new Chance(id, basisPoints, value);
        }
    }

    public record WeightedChance(String id, BigDecimal weight) {
        public WeightedChance {
            id = requireId(id);
            weight = Objects.requireNonNull(weight, "weight").stripTrailingZeros();
            if (weight.signum() <= 0 || weight.scale() > 18) {
                throw new IllegalArgumentException("Weight must be positive with at most 18 decimal places");
            }
        }

        public WeightedChance(String id, long weight) {
            this(id, BigDecimal.valueOf(weight));
        }
    }

    public record Allocation(List<Chance> chances) {
        public Allocation {
            chances = List.copyOf(chances);
            requireUnique(chances);
        }

        public int total() {
            return chances.stream().mapToInt(Chance::basisPoints).sum();
        }

        public int basisPoints(String id) {
            return chances.stream().filter(chance -> chance.id().equals(id)).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Unknown chance ID: " + id)).basisPoints();
        }
    }

    /** Converts legacy positive weights into an exact 10,000-ticket pool. */
    public static Allocation fromWeights(List<WeightedChance> weighted) {
        Objects.requireNonNull(weighted, "weighted");
        if (weighted.isEmpty()) throw new IllegalArgumentException("At least one weight is required");
        requireUniqueIds(weighted.stream().map(WeightedChance::id).toList());
        int[] values = apportion(TOTAL_BASIS_POINTS, weighted.stream().map(WeightedChance::weight).toList(),
                weighted.stream().map(WeightedChance::id).toList());
        var result = new ArrayList<Chance>(weighted.size());
        for (int index = 0; index < weighted.size(); index++) {
            result.add(new Chance(weighted.get(index).id(), values[index], false));
        }
        return complete(result);
    }

    /** Renormalizes an eligible subset into an exact runtime ticket pool. */
    public static Allocation normalize(List<Chance> chances) {
        List<Chance> input = copyAndValidate(chances, false);
        List<Integer> positive = indexes(input, chance -> chance.basisPoints() > 0);
        if (positive.isEmpty()) throw new IllegalArgumentException("The chance pool has no positive entry");
        int[] allocated = apportion(TOTAL_BASIS_POINTS,
                positive.stream().map(index -> BigDecimal.valueOf(input.get(index).basisPoints())).toList(),
                positive.stream().map(index -> input.get(index).id()).toList());
        var output = zeroed(input);
        for (int index = 0; index < positive.size(); index++) {
            int sourceIndex = positive.get(index);
            output.set(sourceIndex, input.get(sourceIndex).withBasisPoints(allocated[index]));
        }
        return complete(output);
    }

    /** Adds a reward and proportionally scales the existing positive pool. */
    public static Allocation addReward(List<Chance> chances, String newId) {
        String id = requireId(newId);
        List<Chance> input = copyAndValidate(chances, true);
        if (input.stream().anyMatch(chance -> chance.id().equals(id))) {
            throw new IllegalArgumentException("Chance ID already exists: " + id);
        }
        if (input.isEmpty()) return complete(List.of(new Chance(id, TOTAL_BASIS_POINTS, false)));

        List<Integer> positive = indexes(input, chance -> chance.basisPoints() > 0);
        if (positive.isEmpty()) {
            var output = zeroed(input);
            output.add(new Chance(id, TOTAL_BASIS_POINTS, false));
            return complete(output);
        }

        int lockedTotal = input.stream().filter(Chance::locked).mapToInt(Chance::basisPoints).sum();
        if (lockedTotal >= TOTAL_BASIS_POINTS) {
            throw new IllegalArgumentException("Unlock an existing reward before adding another positive chance");
        }
        List<Integer> adjustable = indexes(input,
                chance -> !chance.locked() && chance.basisPoints() > 0);
        int newChance = adjustable.isEmpty()
                ? TOTAL_BASIS_POINTS - lockedTotal
                : Math.min(defaultNewRewardBasisPoints(input.size() + 1), TOTAL_BASIS_POINTS - lockedTotal);
        int remaining = TOTAL_BASIS_POINTS - lockedTotal - newChance;
        var output = zeroed(input);
        for (int index = 0; index < input.size(); index++) {
            if (input.get(index).locked()) output.set(index, input.get(index));
        }
        if (!adjustable.isEmpty()) {
            int[] allocated = apportion(remaining,
                    adjustable.stream().map(index -> BigDecimal.valueOf(input.get(index).basisPoints())).toList(),
                    adjustable.stream().map(index -> input.get(index).id()).toList());
            for (int index = 0; index < adjustable.size(); index++) {
                int sourceIndex = adjustable.get(index);
                output.set(sourceIndex, input.get(sourceIndex).withBasisPoints(allocated[index]));
            }
        }
        output.add(new Chance(id, newChance, false));
        return complete(output);
    }

    /**
     * Sets one exact chance and redistributes the difference over other
     * unlocked positive entries. Locked entries are preserved byte-for-byte.
     */
    public static Allocation setChance(List<Chance> chances, String targetId, int requestedBasisPoints) {
        List<Chance> input = copyAndValidate(chances, false);
        if (requestedBasisPoints < 0 || requestedBasisPoints > TOTAL_BASIS_POINTS) {
            throw new IllegalArgumentException("Requested chance must be between 0.00% and 100.00%");
        }
        int targetIndex = indexOf(input, targetId);
        int lockedTotal = 0;
        var adjustable = new ArrayList<Integer>();
        for (int index = 0; index < input.size(); index++) {
            if (index == targetIndex) continue;
            Chance chance = input.get(index);
            if (chance.locked()) lockedTotal = Math.addExact(lockedTotal, chance.basisPoints());
            else if (chance.basisPoints() > 0) adjustable.add(index);
        }
        int remaining = TOTAL_BASIS_POINTS - requestedBasisPoints - lockedTotal;
        if (remaining < 0) {
            throw new IllegalArgumentException("Locked chances leave only "
                    + formatBasisPoints(TOTAL_BASIS_POINTS - lockedTotal) + " available");
        }
        if (remaining > 0 && adjustable.isEmpty()) {
            throw new IllegalArgumentException("Unlock or give a positive chance to another reward before setting this value");
        }

        var output = new ArrayList<>(input);
        output.set(targetIndex, input.get(targetIndex).withBasisPoints(requestedBasisPoints));
        for (int index = 0; index < output.size(); index++) {
            if (index != targetIndex && !output.get(index).locked()) {
                output.set(index, output.get(index).withBasisPoints(0));
            }
        }
        if (!adjustable.isEmpty()) {
            int[] allocated = apportion(remaining,
                    adjustable.stream().map(index -> BigDecimal.valueOf(input.get(index).basisPoints())).toList(),
                    adjustable.stream().map(index -> input.get(index).id()).toList());
            for (int index = 0; index < adjustable.size(); index++) {
                int sourceIndex = adjustable.get(index);
                output.set(sourceIndex, input.get(sourceIndex).withBasisPoints(allocated[index]));
            }
        }
        return complete(output);
    }

    /** Gives every entry an equal share; order resolves indivisible remainders. */
    public static Allocation equalize(List<Chance> chances) {
        List<Chance> input = copyAndValidate(chances, false);
        int[] allocated = apportion(TOTAL_BASIS_POINTS,
                input.stream().map(ignored -> BigDecimal.ONE).toList(), input.stream().map(Chance::id).toList());
        var output = new ArrayList<Chance>(input.size());
        for (int index = 0; index < input.size(); index++) {
            output.add(input.get(index).withBasisPoints(allocated[index]));
        }
        return complete(output);
    }

    /** Keeps locked values and distributes the exact remainder over unlocked entries. */
    public static Allocation normalizeUnlocked(List<Chance> chances) {
        List<Chance> input = copyAndValidate(chances, false);
        int lockedTotal = input.stream().filter(Chance::locked).mapToInt(Chance::basisPoints).sum();
        if (lockedTotal > TOTAL_BASIS_POINTS) {
            throw new IllegalArgumentException("Locked chances exceed 100.00%");
        }
        List<Integer> unlocked = indexes(input, chance -> !chance.locked());
        int remaining = TOTAL_BASIS_POINTS - lockedTotal;
        if (unlocked.isEmpty()) {
            if (remaining == 0) return complete(input);
            throw new IllegalArgumentException("Unlock at least one reward to allocate the remaining chance");
        }
        List<BigDecimal> weights = unlocked.stream()
                .map(index -> BigDecimal.valueOf(input.get(index).basisPoints())).toList();
        if (weights.stream().allMatch(weight -> weight.signum() == 0)) {
            weights = unlocked.stream().map(ignored -> BigDecimal.ONE).toList();
        }
        int[] allocated = apportion(remaining, weights,
                unlocked.stream().map(index -> input.get(index).id()).toList());
        var output = new ArrayList<>(input);
        for (int index = 0; index < unlocked.size(); index++) {
            int sourceIndex = unlocked.get(index);
            output.set(sourceIndex, input.get(sourceIndex).withBasisPoints(allocated[index]));
        }
        return complete(output);
    }

    /** Resolves one exact integer ticket in the half-open range [0, 10,000). */
    public static String selectTicket(List<Chance> chances, int ticket) {
        List<Chance> input = copyAndValidate(chances, false);
        if (ticket < 0 || ticket >= TOTAL_BASIS_POINTS) {
            throw new IllegalArgumentException("Ticket must be in [0, 10,000)");
        }
        requireComplete(input);
        int boundary = 0;
        for (Chance chance : input) {
            boundary += chance.basisPoints();
            if (ticket < boundary) return chance.id();
        }
        throw new IllegalStateException("Complete pool did not contain ticket " + ticket);
    }

    public static int defaultNewRewardBasisPoints(int newRewardCount) {
        if (newRewardCount < 1) throw new IllegalArgumentException("Reward count must be positive");
        return Math.min(1_000, TOTAL_BASIS_POINTS / newRewardCount);
    }

    public static String formatBasisPoints(int basisPoints) {
        if (basisPoints < 0 || basisPoints > TOTAL_BASIS_POINTS) {
            throw new IllegalArgumentException("Basis points must be between 0 and 10,000");
        }
        return String.format(Locale.ROOT, "%.2f%%", basisPoints / 100.0);
    }

    private static Allocation complete(List<Chance> chances) {
        requireComplete(chances);
        return new Allocation(chances);
    }

    private static void requireComplete(List<Chance> chances) {
        int total = chances.stream().mapToInt(Chance::basisPoints).sum();
        if (total != TOTAL_BASIS_POINTS) {
            throw new IllegalArgumentException("Chance pool totals " + formatRaw(total) + "; expected 100.00%");
        }
    }

    private static String formatRaw(int basisPoints) {
        return String.format(Locale.ROOT, "%.2f%%", basisPoints / 100.0);
    }

    private static List<Chance> copyAndValidate(List<Chance> chances, boolean allowEmpty) {
        Objects.requireNonNull(chances, "chances");
        List<Chance> copy = List.copyOf(chances);
        if (!allowEmpty && copy.isEmpty()) throw new IllegalArgumentException("At least one chance is required");
        requireUnique(copy);
        return copy;
    }

    private static void requireUnique(List<Chance> chances) {
        requireUniqueIds(chances.stream().map(Chance::id).toList());
    }

    private static void requireUniqueIds(List<String> ids) {
        var unique = new LinkedHashSet<String>();
        for (String id : ids) {
            if (!unique.add(requireId(id))) throw new IllegalArgumentException("Duplicate chance ID: " + id);
        }
    }

    private static String requireId(String id) {
        String value = Objects.requireNonNull(id, "id").trim();
        if (value.isEmpty()) throw new IllegalArgumentException("Chance ID cannot be blank");
        return value;
    }

    private static int indexOf(List<Chance> chances, String id) {
        String target = requireId(id);
        for (int index = 0; index < chances.size(); index++) {
            if (chances.get(index).id().equals(target)) return index;
        }
        throw new IllegalArgumentException("Unknown chance ID: " + target);
    }

    private static ArrayList<Chance> zeroed(List<Chance> input) {
        var output = new ArrayList<Chance>(input.size());
        for (Chance chance : input) output.add(chance.withBasisPoints(0));
        return output;
    }

    private static List<Integer> indexes(List<Chance> input, java.util.function.Predicate<Chance> predicate) {
        var result = new ArrayList<Integer>();
        for (int index = 0; index < input.size(); index++) if (predicate.test(input.get(index))) result.add(index);
        return List.copyOf(result);
    }

    private record Remainder(int index, String id, BigInteger value) {}

    private static int[] apportion(int total, List<BigDecimal> rawWeights, List<String> ids) {
        if (total < 0 || total > TOTAL_BASIS_POINTS) throw new IllegalArgumentException("Invalid allocation total");
        if (rawWeights.isEmpty() || rawWeights.size() != ids.size()) {
            throw new IllegalArgumentException("Weights and IDs must be non-empty and have the same size");
        }
        int scale = 0;
        var weights = new ArrayList<BigDecimal>(rawWeights.size());
        for (BigDecimal raw : rawWeights) {
            BigDecimal weight = Objects.requireNonNull(raw, "weight").stripTrailingZeros();
            if (weight.signum() < 0 || weight.scale() > 18) {
                throw new IllegalArgumentException("Allocation weights must be non-negative with at most 18 decimal places");
            }
            weights.add(weight);
            scale = Math.max(scale, Math.max(0, weight.scale()));
        }

        var integers = new ArrayList<BigInteger>(weights.size());
        BigInteger sum = BigInteger.ZERO;
        for (BigDecimal weight : weights) {
            BigInteger value = weight.movePointRight(scale).toBigIntegerExact();
            integers.add(value);
            sum = sum.add(value);
        }
        if (sum.signum() <= 0) throw new IllegalArgumentException("Allocation has no positive weight");

        int[] result = new int[weights.size()];
        var remainders = new ArrayList<Remainder>(weights.size());
        int assigned = 0;
        for (int index = 0; index < integers.size(); index++) {
            BigInteger[] division = integers.get(index).multiply(BigInteger.valueOf(total)).divideAndRemainder(sum);
            result[index] = division[0].intValueExact();
            assigned += result[index];
            remainders.add(new Remainder(index, ids.get(index), division[1]));
        }
        remainders.sort(Comparator.comparing(Remainder::value).reversed()
                .thenComparingInt(Remainder::index).thenComparing(Remainder::id));
        int leftover = total - assigned;
        for (int index = 0; index < leftover; index++) result[remainders.get(index).index()]++;
        return result;
    }
}
