package com.antondev.crates.animation;

import java.util.Locale;

/** Built-in physical-crate idle presentation styles. */
public enum IdleAnimationStyle {
    NONE,
    SPARKLE,
    RING,
    DOUBLE_RING,
    ORBIT,
    HELIX,
    PULSE,
    RISING,
    AURA;

    public static IdleAnimationStyle parse(String value) {
        if (value == null || value.isBlank()) return AURA;
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("Unknown idle particle style: " + value, error);
        }
    }
}
