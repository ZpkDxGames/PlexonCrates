package com.antondev.crates.gui;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Declarative slot geometry for the PlexonCrates 6.5 inventory design system. */
public record GuiLayout(String id, int size, List<Integer> contentSlots,
                        Set<Integer> frameSlots, Set<Integer> separatorSlots, int headerSlot) {
    private static final List<Integer> LIST_CONTENT = List.of(
            10, 11, 12, 13, 14, 15, 16,
            19, 20, 21, 22, 23, 24, 25,
            28, 29, 30, 31, 32, 33, 34,
            37, 38, 39, 40, 41, 42, 43);

    public static final GuiLayout LIST_54 = new GuiLayout(
            "LIST_54", 54, LIST_CONTENT, frame54(), Set.of(), 4);
    public static final GuiLayout PANEL_54 = new GuiLayout(
            "PANEL_54", 54, panelContent(), frame54(),
            Set.of(12, 14, 21, 23, 30, 32, 39, 41), 4);
    public static final GuiLayout DIALOG_27 = new GuiLayout(
            "DIALOG_27", 27, List.of(11, 13, 15, 22), frame27(), Set.of(), 4);
    public static final GuiLayout OPENING_27 = new GuiLayout(
            "OPENING_27", 27, List.of(10, 11, 12, 13, 14, 15, 16), frame27(), Set.of(), 4);

    public GuiLayout {
        contentSlots = List.copyOf(contentSlots);
        frameSlots = Set.copyOf(frameSlots);
        separatorSlots = Set.copyOf(separatorSlots);
        if (size < 9 || size > 54 || size % 9 != 0) throw new IllegalArgumentException("invalid inventory size");
        for (int slot : contentSlots) check(slot, size);
        for (int slot : frameSlots) check(slot, size);
        for (int slot : separatorSlots) check(slot, size);
        check(headerSlot, size);
        Set<Integer> overlap = new LinkedHashSet<>(contentSlots);
        overlap.retainAll(frameSlots);
        if (!overlap.isEmpty()) throw new IllegalArgumentException("content/frame collision: " + overlap);
        overlap = new LinkedHashSet<>(contentSlots);
        overlap.retainAll(separatorSlots);
        if (!overlap.isEmpty()) throw new IllegalArgumentException("content/separator collision: " + overlap);
    }

    public static GuiLayout forMenu(MenuHolder.Kind kind, int size) {
        if (kind == MenuHolder.Kind.OPENING) return OPENING_27;
        if (isDialog(kind)) return DIALOG_27;
        if (isPanel(kind)) return PANEL_54;
        if (size == 54) return LIST_54;
        return DIALOG_27;
    }

    private static boolean isDialog(MenuHolder.Kind kind) {
        return switch (kind) {
            case MASS_OPEN, SELECTIVE_CONFIRM, PLAYER_QUANTITY, PLAYER_MASS_CONFIRM,
                 PLAYER_SELECTIVE_CONFIRM, REROLL, CONFIRM_DELETE, CONFIRM_MILESTONE_DELETE,
                 CONFIRM_UNLINK, CONFIRM_CRATE_DELETE, CONFIRM_KEY_DELETE, CONFIRM_TAKEOVER -> true;
            default -> false;
        };
    }

    private static boolean isPanel(MenuHolder.Kind kind) {
        return switch (kind) {
            case ADMIN, EDITOR, KEY_TEMPLATE, REWARD_BUILDER, MILESTONE_DETAIL, STATISTICS, SYSTEM -> true;
            default -> false;
        };
    }

    private static Set<Integer> frame54() {
        LinkedHashSet<Integer> slots = new LinkedHashSet<>();
        for (int slot = 0; slot < 9; slot++) slots.add(slot);
        for (int row = 1; row <= 4; row++) {
            slots.add(row * 9);
            slots.add(row * 9 + 8);
        }
        slots.addAll(GuiNavigation.FOOTER);
        return Set.copyOf(slots);
    }

    private static Set<Integer> frame27() {
        LinkedHashSet<Integer> slots = new LinkedHashSet<>();
        for (int slot = 0; slot < 9; slot++) slots.add(slot);
        slots.add(9); slots.add(17); slots.add(18); slots.add(19); slots.add(20);
        slots.add(21); slots.add(23); slots.add(24); slots.add(25); slots.add(26);
        return Set.copyOf(slots);
    }

    private static List<Integer> panelContent() {
        Set<Integer> frame = frame54();
        Set<Integer> separators = Set.of(12, 14, 21, 23, 30, 32, 39, 41);
        List<Integer> slots = new ArrayList<>();
        for (int slot = 9; slot < 45; slot++) {
            if (!frame.contains(slot) && !separators.contains(slot)) slots.add(slot);
        }
        return List.copyOf(slots);
    }

    private static void check(int slot, int size) {
        if (slot < 0 || slot >= size) throw new IllegalArgumentException("slot " + slot + " outside " + size);
    }
}
