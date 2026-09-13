package com.antondev.crates.gui;

import java.util.List;

/** Stable 6.5 footer semantics shared by all standard 54-slot inventory views. */
public final class GuiNavigation {
    public static final int PREVIOUS = 45;
    public static final int SEARCH = 46;
    public static final int CONTEXT = 47;
    public static final int BACK = 48;
    public static final int PRIMARY = 49;
    public static final int SECONDARY = 50;
    public static final int STATUS = 51;
    public static final int CLOSE = 52;
    public static final int NEXT = 53;
    public static final List<Integer> FOOTER = List.of(
            PREVIOUS, SEARCH, CONTEXT, BACK, PRIMARY, SECONDARY, STATUS, CLOSE, NEXT);

    private GuiNavigation() {}
}
