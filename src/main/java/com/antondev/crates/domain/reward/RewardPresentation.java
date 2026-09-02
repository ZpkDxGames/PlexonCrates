package com.antondev.crates.domain.reward;

import java.util.regex.Pattern;

/** Optional cosmetic feedback applied only after a reward has been safely delivered. */
public record RewardPresentation(
        String title,
        String subtitle,
        String sound,
        float soundVolume,
        float soundPitch,
        boolean firework) {

    private static final Pattern SOUND = Pattern.compile("(?:[a-z0-9._-]+:)?[a-z0-9_./-]+");

    public RewardPresentation {
        title = title == null ? "" : title.trim();
        subtitle = subtitle == null ? "" : subtitle.trim();
        sound = sound == null ? "" : sound.trim().toLowerCase(java.util.Locale.ROOT);
        if (!sound.isEmpty() && !SOUND.matcher(sound).matches()) {
            throw new IllegalArgumentException("Reward sound must be a valid namespaced sound ID");
        }
        if (!Float.isFinite(soundVolume) || soundVolume < 0 || soundVolume > 10) {
            throw new IllegalArgumentException("Reward sound volume must be between 0 and 10");
        }
        if (!Float.isFinite(soundPitch) || soundPitch <= 0 || soundPitch > 2) {
            throw new IllegalArgumentException("Reward sound pitch must be greater than 0 and at most 2");
        }
    }

    public static RewardPresentation none() {
        return new RewardPresentation("", "", "", 1.0f, 1.0f, false);
    }
}
