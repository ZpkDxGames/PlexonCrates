package com.antondev.crates.gui.player;

import java.util.Locale;

/** Formats chance concepts without treating selective choice or unavailable outcomes as random percentages. */
public record ProbabilityPresentation(String primary, String secondary) {
    public ProbabilityPresentation {
        primary = primary == null ? "" : primary;
        secondary = secondary == null ? "" : secondary;
    }

    public static ProbabilityPresentation random(double effectivePercent, double configuredPercent,
                                                  boolean available, boolean pityPool) {
        if (!available) {
            return new ProbabilityPresentation("Currently unavailable",
                    configuredPercent > 0 ? "Configured base chance: " + percent(configuredPercent) : "Not in the active pool");
        }
        String secondary = Math.abs(effectivePercent - configuredPercent) >= 0.0005
                ? "Configured base chance: " + percent(configuredPercent) : "";
        if (pityPool) secondary = secondary.isBlank() ? "Included in the guaranteed pool"
                : secondary + " · Included in the guaranteed pool";
        return new ProbabilityPresentation("Current pool chance: " + percent(effectivePercent), secondary);
    }

    public static ProbabilityPresentation selective(boolean available, boolean pityPool) {
        if (!available) return new ProbabilityPresentation("Currently unavailable", "Cannot be selected right now");
        return new ProbabilityPresentation("Selective choice", pityPool
                ? "You choose this reward · Included in the guaranteed pool"
                : "You choose this reward; random chance is not used");
    }

    static String percent(double value) {
        double clamped = Math.max(0.0, Math.min(100.0, value));
        String formatted = String.format(Locale.ROOT, "%.3f", clamped)
                .replaceAll("0+$", "").replaceAll("\\.$", "");
        return formatted + "%";
    }
}
