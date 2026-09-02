package com.antondev.crates.integration;

import java.lang.reflect.Method;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

/** Optional PlaceholderAPI expansion for trusted administrator text/commands. */
public final class PlaceholderBridge {
    private final JavaPlugin owner;
    private volatile Method method;

    public PlaceholderBridge(JavaPlugin owner) {
        this.owner = owner;
    }

    public String expand(Player player, String value) {
        Plugin plugin = owner.getServer().getPluginManager().getPlugin("PlaceholderAPI");
        if (plugin == null || !plugin.isEnabled()) return value;
        try {
            Method current = method;
            if (current == null) {
                Class<?> api = Class.forName("me.clip.placeholderapi.PlaceholderAPI", true, plugin.getClass().getClassLoader());
                current = api.getMethod("setPlaceholders", org.bukkit.OfflinePlayer.class, String.class);
                method = current;
            }
            Object expanded = current.invoke(null, player, value);
            return expanded instanceof String text ? text : value;
        } catch (ReflectiveOperationException | LinkageError | RuntimeException ignored) {
            method = null;
            return value;
        }
    }
}
