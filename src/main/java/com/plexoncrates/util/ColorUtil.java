package com.plexoncrates.util;

import java.util.ArrayList;
import java.util.List;
import org.bukkit.ChatColor;

public final class ColorUtil {
    private ColorUtil() {}

    public static String color(String input) {
        return ChatColor.translateAlternateColorCodes('&', input == null ? "" : input);
    }

    public static List<String> color(List<String> lines) {
        List<String> result = new ArrayList<>();
        if (lines != null) {
            for (String line : lines) result.add(color(line));
        }
        return result;
    }

    public static String strip(String input) {
        return ChatColor.stripColor(color(input));
    }
}
