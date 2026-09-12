package com.antondev.crates.animation;

import java.util.ArrayList;
import java.util.List;

/** Pure deterministic geometry for physical-crate idle effects. */
public final class IdleAnimationMath {
    private static final double TAU = Math.PI * 2.0;
    private static final double GOLDEN_ANGLE = Math.PI * (3.0 - Math.sqrt(5.0));

    private IdleAnimationMath() {}

    public record Offset(double x, double y, double z) {}

    public static List<Offset> sample(IdleAnimationProfile profile, long elapsedTicks) {
        if (profile == null || !profile.enabled()) return List.of();
        int points = profile.points();
        double phase = elapsedTicks * profile.rotationSpeed();
        double verticalPhase = elapsedTicks * profile.verticalSpeed();
        var result = new ArrayList<Offset>(profile.style() == IdleAnimationStyle.DOUBLE_RING ? points * 2 : points);
        switch (profile.style()) {
            case NONE -> { return List.of(); }
            case RING -> ring(result, points, profile.radius(), profile.height() * 0.5, phase);
            case DOUBLE_RING -> {
                ring(result, points, profile.radius(), profile.height() * 0.25, phase);
                ring(result, points, profile.radius() * 0.82, profile.height() * 0.82, -phase * 0.85);
            }
            case ORBIT -> {
                for (int i = 0; i < points; i++) {
                    double angle = phase + TAU * i / points;
                    double x = Math.cos(angle) * profile.radius();
                    double z = Math.sin(angle) * profile.radius();
                    double y = profile.height() * 0.5 + Math.sin(angle * 2.0) * profile.height() * 0.28;
                    result.add(new Offset(x, y, z));
                }
            }
            case HELIX -> {
                for (int i = 0; i < points; i++) {
                    double progress = i / (double) points;
                    double angle = phase + progress * TAU * 2.0;
                    double y = positiveMod(progress + verticalPhase / TAU, 1.0) * profile.height();
                    result.add(new Offset(Math.cos(angle) * profile.radius(), y, Math.sin(angle) * profile.radius()));
                }
            }
            case PULSE -> {
                double pulse = 0.62 + 0.38 * (0.5 + 0.5 * Math.sin(phase));
                ring(result, points, profile.radius() * pulse, profile.height() * 0.5, phase * 0.35);
            }
            case RISING -> {
                for (int i = 0; i < points; i++) {
                    double progress = i / (double) points;
                    double angle = phase + i * GOLDEN_ANGLE;
                    double y = positiveMod(progress + verticalPhase / TAU, 1.0) * profile.height();
                    double radius = profile.radius() * (0.55 + 0.45 * progress);
                    result.add(new Offset(Math.cos(angle) * radius, y, Math.sin(angle) * radius));
                }
            }
            case SPARKLE -> {
                for (int i = 0; i < points; i++) {
                    double angle = phase * 0.37 + i * GOLDEN_ANGLE;
                    double radial = profile.radius() * (0.35 + 0.65 * pseudoUnit(i, elapsedTicks));
                    double y = profile.height() * pseudoUnit(i * 17 + 3, elapsedTicks / 2 + 11);
                    result.add(new Offset(Math.cos(angle) * radial, y, Math.sin(angle) * radial));
                }
            }
            case AURA -> {
                for (int i = 0; i < points; i++) {
                    double t = (i + 0.5) / points;
                    double yUnit = 1.0 - 2.0 * t;
                    double horizontal = Math.sqrt(Math.max(0.0, 1.0 - yUnit * yUnit));
                    double angle = phase + i * GOLDEN_ANGLE;
                    result.add(new Offset(
                            Math.cos(angle) * horizontal * profile.radius(),
                            profile.height() * (yUnit + 1.0) * 0.5,
                            Math.sin(angle) * horizontal * profile.radius()));
                }
            }
        }
        return List.copyOf(result);
    }

    private static void ring(List<Offset> target, int points, double radius, double y, double phase) {
        for (int i = 0; i < points; i++) {
            double angle = phase + TAU * i / points;
            target.add(new Offset(Math.cos(angle) * radius, y, Math.sin(angle) * radius));
        }
    }

    private static double positiveMod(double value, double divisor) {
        double result = value % divisor;
        return result < 0.0 ? result + divisor : result;
    }

    private static double pseudoUnit(long salt, long tick) {
        long value = salt * 0x9E3779B97F4A7C15L + tick * 0xBF58476D1CE4E5B9L;
        value ^= value >>> 30;
        value *= 0xBF58476D1CE4E5B9L;
        value ^= value >>> 27;
        value *= 0x94D049BB133111EBL;
        value ^= value >>> 31;
        return (value >>> 11) * 0x1.0p-53;
    }
}
