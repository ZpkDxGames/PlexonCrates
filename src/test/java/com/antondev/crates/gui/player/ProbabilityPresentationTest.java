package com.antondev.crates.gui.player;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ProbabilityPresentationTest {
    @Test void randomOpeningUsesEffectivePoolProbability() {
        var view = ProbabilityPresentation.random(25.0, 25.0, true, false);
        assertTrue(view.primary().contains("Current pool chance: 25%"));
        assertFalse(view.primary().contains("weight"));
    }

    @Test void adjustedRandomChanceKeepsConfiguredBaseChanceDistinct() {
        var view = ProbabilityPresentation.random(20.0, 30.0, true, false);
        assertTrue(view.primary().contains("20%"));
        assertTrue(view.secondary().contains("Configured base chance: 30%"));
    }

    @Test void unavailableRewardDoesNotManufactureEffectivePercentage() {
        var view = ProbabilityPresentation.random(0.0, 12.5, false, false);
        assertTrue(view.primary().contains("unavailable"));
        assertFalse(view.primary().contains("%"));
    }

    @Test void selectiveOpeningNeverPretendsToBeRandomChance() {
        var view = ProbabilityPresentation.selective(true, false);
        assertTrue(view.primary().contains("Selective choice"));
        assertFalse(view.primary().contains("%"));
        assertTrue(view.secondary().contains("random chance is not used"));
    }

    @Test void pityPoolIsDescribedWithoutInventedAdjustedPercent() {
        var view = ProbabilityPresentation.random(10.0, 10.0, true, true);
        assertTrue(view.secondary().contains("guaranteed pool"));
        assertFalse(view.secondary().contains("pity-adjusted"));
    }
}
