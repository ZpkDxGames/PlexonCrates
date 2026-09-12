package com.antondev.crates.animation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.antondev.crates.domain.crate.AnimationType;
import java.util.EnumMap;
import java.util.Map;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.junit.jupiter.api.Test;

class OpeningAnimationProfileTest {
    @Test
    void catalogContainsEveryRequiredSixZeroStyle() {
        assertEquals(8, OpeningAnimationStyle.values().length);
        assertEquals(OpeningAnimationStyle.INSTANT, OpeningAnimationStyle.valueOf("INSTANT"));
        assertEquals(OpeningAnimationStyle.ROULETTE, OpeningAnimationStyle.valueOf("ROULETTE"));
        assertEquals(OpeningAnimationStyle.SPIN, OpeningAnimationStyle.valueOf("SPIN"));
        assertEquals(OpeningAnimationStyle.CHARGE_REVEAL, OpeningAnimationStyle.valueOf("CHARGE_REVEAL"));
        assertEquals(OpeningAnimationStyle.SPIRAL_BURST, OpeningAnimationStyle.valueOf("SPIRAL_BURST"));
        assertEquals(OpeningAnimationStyle.ORB_REVEAL, OpeningAnimationStyle.valueOf("ORB_REVEAL"));
        assertEquals(OpeningAnimationStyle.CASCADE, OpeningAnimationStyle.valueOf("CASCADE"));
        assertEquals(OpeningAnimationStyle.FIREWORK_STYLE, OpeningAnimationStyle.valueOf("FIREWORK_STYLE"));
    }

    @Test
    void defaultsContainAllOrderedStagesAndStayWithinBudgets() {
        for (OpeningAnimationStyle style : OpeningAnimationStyle.values()) {
            OpeningAnimationProfile profile = OpeningAnimationProfile.defaults(style);
            assertEquals(style, profile.style());
            assertEquals(OpeningAnimationStage.values().length, profile.stageTicks().size());
            for (OpeningAnimationStage stage : OpeningAnimationStage.values()) {
                assertTrue(profile.stageTicks().containsKey(stage));
                assertTrue(profile.ticks(stage) >= 0);
            }
            assertTrue(profile.totalTicks() <= 1_200);
            assertTrue(profile.particleBudgetPerTick() <= 2_000);
        }
        assertFalse(OpeningAnimationProfile.defaults(OpeningAnimationStyle.INSTANT).animated());
        assertTrue(OpeningAnimationProfile.defaults(OpeningAnimationStyle.ROULETTE).animated());
    }

    @Test
    void legacyProjectionPreservesSummaryIntentWithoutMutatingLegacyEnum() {
        assertEquals(OpeningAnimationStyle.ROULETTE,
                OpeningAnimationProfile.fromLegacy(AnimationType.ROULETTE).style());
        assertEquals(OpeningAnimationStyle.CHARGE_REVEAL,
                OpeningAnimationProfile.fromLegacy(AnimationType.REVEAL).style());
        assertEquals(OpeningAnimationStyle.INSTANT,
                OpeningAnimationProfile.fromLegacy(AnimationType.INSTANT).style());
        OpeningAnimationProfile summary = OpeningAnimationProfile.fromLegacy(AnimationType.SUMMARY);
        assertEquals(OpeningAnimationStyle.INSTANT, summary.style());
        assertTrue(summary.summaryOnFinish());
    }

    @Test
    void constructorRejectsUnsafeTimingAndPresentationParameters() {
        Map<OpeningAnimationStage, Integer> valid = stages(1);
        assertThrows(IllegalArgumentException.class, () -> new OpeningAnimationProfile(
                OpeningAnimationStyle.ROULETTE, stages(401), Particle.END_ROD, Sound.BLOCK_NOTE_BLOCK_PLING,
                1.0f, 1.0f, 96, 48.0, false));
        assertThrows(IllegalArgumentException.class, () -> new OpeningAnimationProfile(
                OpeningAnimationStyle.ROULETTE, valid, Particle.END_ROD, Sound.BLOCK_NOTE_BLOCK_PLING,
                5.0f, 1.0f, 96, 48.0, false));
        assertThrows(IllegalArgumentException.class, () -> new OpeningAnimationProfile(
                OpeningAnimationStyle.ROULETTE, valid, Particle.END_ROD, Sound.BLOCK_NOTE_BLOCK_PLING,
                1.0f, 2.1f, 96, 48.0, false));
        assertThrows(IllegalArgumentException.class, () -> new OpeningAnimationProfile(
                OpeningAnimationStyle.ROULETTE, valid, Particle.END_ROD, Sound.BLOCK_NOTE_BLOCK_PLING,
                1.0f, 1.0f, 2_001, 48.0, false));
        assertThrows(IllegalArgumentException.class, () -> new OpeningAnimationProfile(
                OpeningAnimationStyle.ROULETTE, valid, Particle.END_ROD, Sound.BLOCK_NOTE_BLOCK_PLING,
                1.0f, 1.0f, 96, 129.0, false));
    }

    @Test
    void instantProfilesCannotSmuggleTimedWork() {
        assertThrows(IllegalArgumentException.class, () -> new OpeningAnimationProfile(
                OpeningAnimationStyle.INSTANT, stages(1), Particle.END_ROD, Sound.BLOCK_NOTE_BLOCK_PLING,
                1.0f, 1.0f, 0, 48.0, false));
    }

    @Test
    void profileDefensivelyCopiesStageMap() {
        EnumMap<OpeningAnimationStage, Integer> mutable = new EnumMap<>(OpeningAnimationStage.class);
        for (OpeningAnimationStage stage : OpeningAnimationStage.values()) mutable.put(stage, 1);
        OpeningAnimationProfile profile = new OpeningAnimationProfile(
                OpeningAnimationStyle.ROULETTE, mutable, Particle.END_ROD, Sound.BLOCK_NOTE_BLOCK_PLING,
                1.0f, 1.0f, 96, 48.0, false);
        mutable.put(OpeningAnimationStage.REVEAL, 99);
        assertEquals(1, profile.ticks(OpeningAnimationStage.REVEAL));
        assertThrows(UnsupportedOperationException.class,
                () -> profile.stageTicks().put(OpeningAnimationStage.REVEAL, 2));
    }

    private static Map<OpeningAnimationStage, Integer> stages(int ticks) {
        EnumMap<OpeningAnimationStage, Integer> values = new EnumMap<>(OpeningAnimationStage.class);
        for (OpeningAnimationStage stage : OpeningAnimationStage.values()) values.put(stage, ticks);
        return values;
    }
}
