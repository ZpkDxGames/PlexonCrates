package com.antondev.crates.integration;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Level;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.inventory.ItemStack;

/**
 * Zero-dependency bridge to PlexonKeys' live, already-validated item templates.
 * Reflection keeps PlexonCrates loadable when PlexonKeys is not installed.
 */
public final class PlexonKeysBridge {
    private final JavaPlugin owner;
    private final String pluginName;
    private boolean warned;

    public PlexonKeysBridge(JavaPlugin owner, String pluginName) {
        this.owner = owner;
        this.pluginName = pluginName;
    }

    public Optional<ItemStack> template(String keyId) {
        Plugin plugin = owner.getServer().getPluginManager().getPlugin(pluginName);
        if (plugin == null || !plugin.isEnabled()) return Optional.empty();
        try {
            Method settingsMethod = plugin.getClass().getMethod("settings");
            Object settings = settingsMethod.invoke(plugin);
            @SuppressWarnings("unchecked")
            Map<Object, Object> categories = (Map<Object, Object>) settings.getClass().getMethod("categories").invoke(settings);
            for (Map.Entry<Object, Object> entry : categories.entrySet()) {
                String id = String.valueOf(entry.getKey().getClass().getMethod("id").invoke(entry.getKey()));
                if (!id.equalsIgnoreCase(keyId)) continue;
                Object item = entry.getValue().getClass().getMethod("itemCopy").invoke(entry.getValue());
                if (item instanceof ItemStack stack && !stack.getType().isAir()) {
                    warned = false;
                    return Optional.of(stack.clone());
                }
            }
        } catch (ReflectiveOperationException | LinkageError | ClassCastException error) {
            if (!warned) {
                owner.getLogger().log(Level.WARNING, "PlexonKeys is installed but its live key API could not be read. Falling back to keys.yml.", error);
                warned = true;
            }
        }
        return Optional.empty();
    }

    public boolean available() {
        Plugin plugin = owner.getServer().getPluginManager().getPlugin(pluginName);
        return plugin != null && plugin.isEnabled();
    }
}
