package com.antondev.crates.animation;

import java.util.Objects;
import org.bukkit.Particle;

/** Immutable, validated particle profile consumed by the shared physical-crate coordinator. */
public record IdleAnimationProfile(
        IdleAnimationStyle style,
        Particle particle,
        double radius,
        double height,
        int points,
        double rotationSpeed,
        double verticalSpeed,
        int particlesPerPoint,
        double receiverRange,
        int maxPerCratePerTick,
        int maxPerViewerPerTick) {

    public IdleAnimationProfile {
        style = Objects.requireNonNull(style, "style");
        particle = Objects.requireNonNull(particle, "particle");
        finite(radius, "radius");
        finite(height, "height");
        finite(rotationSpeed, "rotationSpeed");
        finite(verticalSpeed, "verticalSpeed");
        finite(receiverRange, "receiverRange");
        if (radius < 0.0 || radius > 8.0) throw new IllegalArgumentException("radius must be between 0 and 8");
        if (height < 0.0 || height > 8.0) throw new IllegalArgumentException("height must be between 0 and 8");
        if (points < 1 || points > 128) throw new IllegalArgumentException("points must be between 1 and 128");
        if (Math.abs(rotationSpeed) > 4.0) throw new IllegalArgumentException("rotationSpeed magnitude must be <= 4");
        if (Math.abs(verticalSpeed) > 4.0) throw new IllegalArgumentException("verticalSpeed magnitude must be <= 4");
        if (particlesPerPoint < 1 || particlesPerPoint > 32) {
            throw new IllegalArgumentException("particlesPerPoint must be between 1 and 32");
        }
        if (receiverRange < 1.0 || receiverRange > 256.0) {
            throw new IllegalArgumentException("receiverRange must be between 1 and 256");
        }
        if (maxPerCratePerTick < 1 || maxPerCratePerTick > 10_000) {
            throw new IllegalArgumentException("maxPerCratePerTick must be between 1 and 10000");
        }
        if (maxPerViewerPerTick < 1 || maxPerViewerPerTick > 10_000) {
            throw new IllegalArgumentException("maxPerViewerPerTick must be between 1 and 10000");
        }
    }

    public boolean enabled() {
        return style != IdleAnimationStyle.NONE;
    }

    private static void finite(double value, String name) {
        if (!Double.isFinite(value)) throw new IllegalArgumentException(name + " must be finite");
    }
}
