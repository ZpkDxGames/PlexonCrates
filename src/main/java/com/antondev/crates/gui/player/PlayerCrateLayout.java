package com.antondev.crates.gui.player;

import java.util.List;

/** Stable player-facing slot semantics for the Phase 3 crate product. */
public final class PlayerCrateLayout {
    public static final int SIZE = 54;
    public static final int PREVIOUS = 45;
    public static final int CONTEXT = 46;
    public static final int PAYMENT = 47;
    public static final int BACK = 48;
    public static final int PRIMARY = 49;
    public static final int SECONDARY = 50;
    public static final int STATUS = 51;
    public static final int CLOSE = 52;
    public static final int NEXT = 53;

    private static final List<Integer> CONTENT = List.of(
            10, 11, 12, 13, 14, 15, 16,
            19, 20, 21, 22, 23, 24, 25,
            28, 29, 30, 31, 32, 33, 34,
            37, 38, 39, 40, 41, 42, 43);

    private PlayerCrateLayout() {}

    public static List<Integer> contentSlots() {
        return CONTENT;
    }

    public static int pageCount(int items) {
        if (items <= 0) return 1;
        return (items + CONTENT.size() - 1) / CONTENT.size();
    }

    public static int clampPage(int requested, int items) {
        return Math.max(0, Math.min(requested, pageCount(items) - 1));
    }
}
