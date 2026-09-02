package com.antondev.crates.integration.plexonkeys;

import com.antondev.crates.config.ItemCodec;
import com.antondev.crates.domain.key.ExternalKeyDescriptor;
import com.antondev.crates.domain.key.PhysicalKeyProvider;
import com.antondev.crates.domain.key.ProviderStatus;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

/** Optional, zero-linkage adapter for PlexonKeys' public runtime settings surface. */
public final class PlexonKeysKeyProvider implements PhysicalKeyProvider {
    private static final long WARNING_INTERVAL_MILLIS = 60_000L;

    private final JavaPlugin owner;
    private final String pluginName;
    private volatile Handles handles;
    private final Map<Class<?>, Method> categoryIds = new ConcurrentHashMap<>();
    private final Map<Class<?>, Method> itemCopies = new ConcurrentHashMap<>();
    private volatile ProviderStatus lastStatus = ProviderStatus.ABSENT;
    private volatile String diagnostic = "PlexonKeys has not been discovered yet.";
    private volatile long lastWarning;

    public PlexonKeysKeyProvider(JavaPlugin owner, String pluginName) {
        this.owner = owner;
        this.pluginName = pluginName;
    }

    @Override public String id() { return "plexonkeys"; }

    @Override
    public ProviderStatus status() {
        Plugin plugin = owner.getServer().getPluginManager().getPlugin(pluginName);
        if (plugin == null) return ProviderStatus.ABSENT;
        if (!plugin.isEnabled()) return ProviderStatus.DISABLED;
        return lastStatus == ProviderStatus.ABSENT || lastStatus == ProviderStatus.DISABLED
                ? ProviderStatus.READY : lastStatus;
    }

    @Override
    public Map<String, ExternalKeyDescriptor> discover() {
        Plugin plugin = owner.getServer().getPluginManager().getPlugin(pluginName);
        if (plugin == null) return unavailable(ProviderStatus.ABSENT, pluginName + " is not installed.");
        if (!plugin.isEnabled()) return unavailable(ProviderStatus.DISABLED, pluginName + " is installed but disabled.");
        try {
            Object settings = settings(plugin);
            @SuppressWarnings("unchecked")
            Map<Object, Object> categories = (Map<Object, Object>) handles.categories().invoke(settings);
            var result = new LinkedHashMap<String, ExternalKeyDescriptor>();
            for (Map.Entry<Object, Object> entry : categories.entrySet()) {
                Object category = entry.getKey();
                Object definition = entry.getValue();
                Method idMethod = method(categoryIds, category.getClass(), "id");
                Method itemMethod = method(itemCopies, definition.getClass(), "itemCopy");
                String rawId = String.valueOf(idMethod.invoke(category));
                String keyId = rawId.toLowerCase(Locale.ROOT).trim();
                Object rawItem = itemMethod.invoke(definition);
                if (!(rawItem instanceof ItemStack item) || item.getType().isAir()) continue;
                result.put(keyId, new ExternalKeyDescriptor(keyId, id(), ItemCodec.one(item)));
            }
            lastStatus = ProviderStatus.READY;
            diagnostic = "Discovered " + result.size() + " live PlexonKeys categories.";
            return Map.copyOf(result);
        } catch (ReflectiveOperationException | LinkageError | ClassCastException error) {
            lastStatus = ProviderStatus.INCOMPATIBLE;
            diagnostic = "The installed PlexonKeys runtime surface is incompatible: " + concise(error);
            warn(diagnostic, error);
            return Map.of();
        } catch (RuntimeException error) {
            lastStatus = ProviderStatus.ERROR;
            diagnostic = "PlexonKeys discovery failed: " + concise(error);
            warn(diagnostic, error);
            return Map.of();
        }
    }

    @Override
    public Optional<ItemStack> resolve(String externalId) {
        ExternalKeyDescriptor descriptor = discover().get(externalId.toLowerCase(Locale.ROOT));
        return descriptor == null ? Optional.empty() : Optional.of(descriptor.template());
    }

    @Override public String diagnostic() { return diagnostic; }

    public void invalidate() {
        handles = null;
        categoryIds.clear();
        itemCopies.clear();
        lastStatus = ProviderStatus.ABSENT;
        diagnostic = "Provider cache invalidated; discovery will run on the next synchronization.";
    }

    private Object settings(Plugin plugin) throws ReflectiveOperationException {
        Handles current = handles;
        if (current == null || current.pluginClass() != plugin.getClass()) {
            Method settings = plugin.getClass().getMethod("settings");
            Object settingsObject = settings.invoke(plugin);
            Method categories = settingsObject.getClass().getMethod("categories");
            current = new Handles(plugin.getClass(), settings, categories);
            handles = current;
            return settingsObject;
        }
        return current.settings().invoke(plugin);
    }

    private static Method method(Map<Class<?>, Method> cache, Class<?> type, String name)
            throws ReflectiveOperationException {
        Method current = cache.get(type);
        if (current != null) return current;
        Method resolved = type.getMethod(name);
        Method existing = cache.putIfAbsent(type, resolved);
        return existing == null ? resolved : existing;
    }

    private Map<String, ExternalKeyDescriptor> unavailable(ProviderStatus status, String message) {
        lastStatus = status;
        diagnostic = message;
        return Map.of();
    }

    private void warn(String message, Throwable error) {
        long now = System.currentTimeMillis();
        if (now - lastWarning < WARNING_INTERVAL_MILLIS) return;
        lastWarning = now;
        owner.getLogger().log(Level.WARNING, message + " Exact keys will use last-known-good or configured fallbacks.", error);
    }

    private static String concise(Throwable error) {
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }

    private record Handles(Class<?> pluginClass, Method settings, Method categories) {}
}
