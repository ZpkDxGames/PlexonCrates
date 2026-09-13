package com.antondev.crates.animation;

import com.antondev.crates.domain.crate.AnimationType;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import org.bukkit.Particle;
import org.bukkit.Sound;

/**
 * Immutable presentation profile for one opening animation.
 *
 * <p>This model intentionally contains no reward, payment, journal, statistics,
 * pity, limit or delivery state. It can therefore be previewed independently
 * from the transactional opening pipeline.</p>
 */
public record OpeningAnimationProfile(
        OpeningAnimationStyle style,
        Map<OpeningAnimationStage, Integer> stageTicks,
        Particle particle,
        Sound sound,
        float soundVolume,
        float soundPitch,
        int particleBudgetPerTick,
        double receiverRange,
        boolean summaryOnFinish) {

    private static final int MAX_STAGE_TICKS = 400;
    private static final int MAX_TOTAL_TICKS = 1_200;

    public OpeningAnimationProfile {
        style = Objects.requireNonNull(style, "style");
        particle = Objects.requireNonNull(particle, "particle");
        sound = Objects.requireNonNull(sound, "sound");
        stageTicks = validatedStages(stageTicks);
        finite(soundVolume, "soundVolume");
        finite(soundPitch, "soundPitch");
        finite(receiverRange, "receiverRange");
        if (soundVolume < 0.0f || soundVolume > 4.0f) {
            throw new IllegalArgumentException("soundVolume must be between 0 and 4");
        }
        if (soundPitch < 0.5f || soundPitch > 2.0f) {
            throw new IllegalArgumentException("soundPitch must be between 0.5 and 2");
        }
        if (particleBudgetPerTick < 0 || particleBudgetPerTick > 2_000) {
            throw new IllegalArgumentException("particleBudgetPerTick must be between 0 and 2000");
        }
        if (receiverRange < 1.0 || receiverRange > 128.0) {
            throw new IllegalArgumentException("receiverRange must be between 1 and 128");
        }
        int total = stageTicks.values().stream().mapToInt(Integer::intValue).sum();
        if (total > MAX_TOTAL_TICKS) {
            throw new IllegalArgumentException("animation duration must be <= " + MAX_TOTAL_TICKS + " ticks");
        }
        if (style == OpeningAnimationStyle.INSTANT && total != 0) {
            throw new IllegalArgumentException("INSTANT profiles cannot have timed stages");
        }
    }

    @Override
    public Map<OpeningAnimationStage, Integer> stageTicks() {
        return stageTicks;
    }

    public int ticks(OpeningAnimationStage stage) {
        return stageTicks.get(Objects.requireNonNull(stage, "stage"));
    }

    public int totalTicks() {
        return stageTicks.values().stream().mapToInt(Integer::intValue).sum();
    }

    public boolean animated() {
        return style != OpeningAnimationStyle.INSTANT && totalTicks() > 0;
    }

    public OpeningAnimationProfile withStyle(OpeningAnimationStyle next) {
        OpeningAnimationProfile defaults = defaults(next);
        return new OpeningAnimationProfile(next, defaults.stageTicks, particle, sound,
                soundVolume, soundPitch, particleBudgetPerTick, receiverRange, summaryOnFinish);
    }

    /**
     * Compatibility projection for existing crate files. This does not change
     * persisted legacy values; it provides a safe profile while 6.0 migration
     * and editor surfaces are introduced.
     */
    public static OpeningAnimationProfile fromLegacy(AnimationType type) {
        return switch (Objects.requireNonNull(type, "type")) {
            case INSTANT -> defaults(OpeningAnimationStyle.INSTANT);
            case ROULETTE -> defaults(OpeningAnimationStyle.ROULETTE);
            case REVEAL -> defaults(OpeningAnimationStyle.CHARGE_REVEAL);
            case SUMMARY -> {
                OpeningAnimationProfile base = defaults(OpeningAnimationStyle.INSTANT);
                yield new OpeningAnimationProfile(base.style, base.stageTicks, base.particle, base.sound,
                        base.soundVolume, base.soundPitch, base.particleBudgetPerTick, base.receiverRange, true);
            }
        };
    }

    public static OpeningAnimationProfile defaults(OpeningAnimationStyle style) {
        Objects.requireNonNull(style, "style");
        Map<OpeningAnimationStage, Integer> stages = switch (style) {
            case INSTANT -> stages(0, 0, 0, 0, 0, 0);
            case ROULETTE -> stages(4, 6, 40, 10, 10, 4);
            case SPIN -> stages(4, 10, 36, 10, 12, 4);
            case CHARGE_REVEAL -> stages(4, 30, 0, 12, 12, 4);
            case SPIRAL_BURST -> stages(4, 20, 20, 10, 16, 4);
            case ORB_REVEAL -> stages(4, 24, 12, 12, 14, 4);
            case CASCADE -> stages(4, 12, 24, 12, 16, 4);
            case FIREWORK_STYLE -> stages(4, 12, 12, 10, 24, 4);
        };
        return new OpeningAnimationProfile(style, stages, Particle.END_ROD,
                Sound.BLOCK_NOTE_BLOCK_PLING, 1.0f, 1.0f, style == OpeningAnimationStyle.INSTANT ? 0 : 96,
                48.0, false);
    }

    private static Map<OpeningAnimationStage, Integer> validatedStages(
            Map<OpeningAnimationStage, Integer> source) {
        Objects.requireNonNull(source, "stageTicks");
        EnumMap<OpeningAnimationStage, Integer> copy = new EnumMap<>(OpeningAnimationStage.class);
        for (OpeningAnimationStage stage : OpeningAnimationStage.values()) {
            Integer ticks = source.get(stage);
            if (ticks == null) throw new IllegalArgumentException("Missing stage duration: " + stage);
            if (ticks < 0 || ticks > MAX_STAGE_TICKS) {
                throw new IllegalArgumentException(stage + " ticks must be between 0 and " + MAX_STAGE_TICKS);
            }
            copy.put(stage, ticks);
        }
        if (source.size() != copy.size()) throw new IllegalArgumentException("Unknown animation stage entries");
        return Map.copyOf(copy);
    }

    private static Map<OpeningAnimationStage, Integer> stages(
            int start, int charge, int selection, int reveal, int celebration, int finish) {
        EnumMap<OpeningAnimationStage, Integer> values = new EnumMap<>(OpeningAnimationStage.class);
        values.put(OpeningAnimationStage.START, start);
        values.put(OpeningAnimationStage.CHARGE, charge);
        values.put(OpeningAnimationStage.SELECTION, selection);
        values.put(OpeningAnimationStage.REVEAL, reveal);
        values.put(OpeningAnimationStage.CELEBRATION, celebration);
        values.put(OpeningAnimationStage.FINISH, finish);
        return values;
    }

    private static void finite(double value, String name) {
        if (!Double.isFinite(value)) throw new IllegalArgumentException(name + " must be finite");
    }
}
