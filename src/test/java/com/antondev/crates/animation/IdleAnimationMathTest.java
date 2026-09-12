package com.antondev.crates.animation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.bukkit.Particle;
import org.junit.jupiter.api.Test;

class IdleAnimationMathTest {
    @Test
    void everyEnabledStyleProducesFiniteDeterministicGeometry() {
        for (IdleAnimationStyle style : IdleAnimationStyle.values()) {
            IdleAnimationProfile profile = profile(style);
            List<IdleAnimationMath.Offset> first = IdleAnimationMath.sample(profile, 40L);
            List<IdleAnimationMath.Offset> second = IdleAnimationMath.sample(profile, 40L);
            assertEquals(first, second, style.name());
            if (style == IdleAnimationStyle.NONE) {
                assertTrue(first.isEmpty());
                continue;
            }
            assertFalse(first.isEmpty(), style.name());
            for (IdleAnimationMath.Offset offset : first) {
                assertTrue(Double.isFinite(offset.x()));
                assertTrue(Double.isFinite(offset.y()));
                assertTrue(Double.isFinite(offset.z()));
            }
        }
    }

    @Test
    void doubleRingUsesTwoBoundedPointSets() {
        IdleAnimationProfile profile = profile(IdleAnimationStyle.DOUBLE_RING);
        assertEquals(profile.points() * 2, IdleAnimationMath.sample(profile, 0L).size());
    }

    @Test
    void invalidProfileBudgetsFailClosed() {
        assertThrows(IllegalArgumentException.class, () -> new IdleAnimationProfile(
                IdleAnimationStyle.AURA, Particle.END_ROD, 1.0, 1.0, 12,
                0.2, 0.1, 1, 32.0, 0, 200));
        assertThrows(IllegalArgumentException.class, () -> new IdleAnimationProfile(
                IdleAnimationStyle.AURA, Particle.END_ROD, 1.0, 1.0, 12,
                0.2, 0.1, 1, 32.0, 80, 0));
    }

    private static IdleAnimationProfile profile(IdleAnimationStyle style) {
        return new IdleAnimationProfile(style, Particle.END_ROD, 0.7, 1.2, 12,
                0.18, 0.08, 1, 32.0, 80, 200);
    }
}
