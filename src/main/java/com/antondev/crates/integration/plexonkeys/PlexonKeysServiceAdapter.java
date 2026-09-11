package com.antondev.crates.integration.plexonkeys;

import com.antondev.crates.config.ItemCodec;
import com.antondev.crates.domain.key.ExternalKeyDescriptor;
import com.antondev.keys.api.KeyConsumeResult;
import com.antondev.keys.api.PlexonKeysAPI;
import java.util.UUID;
import org.bukkit.Bukkit;
import com.antondev.keys.model.KeyTier;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Direct adapter for the stable PlexonKeys 2.0 Bukkit service API.
 * Loaded reflectively by {@link PlexonKeysKeyProvider} so PlexonCrates remains
 * linkage-safe when PlexonKeys is absent or an older release is installed.
 */
public final class PlexonKeysServiceAdapter {
    private PlexonKeysServiceAdapter() {}

    public static Map<String, ExternalKeyDescriptor> discover(JavaPlugin owner) {
        RegisteredServiceProvider<PlexonKeysAPI> registration =
                owner.getServer().getServicesManager().getRegistration(PlexonKeysAPI.class);
        if (registration == null) return null;

        PlexonKeysAPI api = registration.getProvider();
        Map<String, ExternalKeyDescriptor> result = new LinkedHashMap<>();
        for (KeyTier tier : KeyTier.values()) {
            if (!api.isTierEnabled(tier)) continue;
            Optional<ItemStack> template = api.keyTemplate(tier);
            if (template.isEmpty() || template.get().getType().isAir()) continue;
            result.put(tier.id(), new ExternalKeyDescriptor(tier.id(), "plexonkeys", ItemCodec.one(template.get())));
        }
        return Map.copyOf(result);
    }

    /** True only when the runtime service needed for authoritative wallet operations is registered. */
    public static boolean available(JavaPlugin owner) {
        return owner.getServer().getServicesManager().getRegistration(PlexonKeysAPI.class) != null;
    }

    /** Reads the authoritative PlexonKeys wallet on the provider's required primary thread. */
    public static long balance(JavaPlugin owner, UUID playerId, String keyId) {
        requirePrimaryThread();
        PlexonKeysAPI api = api(owner);
        return api.balance(playerId, KeyTier.parse(keyId));
    }

    /**
     * Invokes PlexonKeys' crash-durable consume boundary. RC2 requires the primary thread, so callers
     * must treat this as a measured low-frequency durability boundary rather than moving it illegally
     * to an async worker.
     */
    public static KeyConsumeResult consume(JavaPlugin owner, UUID playerId, String keyId,
                                           long amount, String transactionId) {
        requirePrimaryThread();
        return api(owner).consumeKey(playerId, keyId, amount, transactionId);
    }

    private static PlexonKeysAPI api(JavaPlugin owner) {
        RegisteredServiceProvider<PlexonKeysAPI> registration =
                owner.getServer().getServicesManager().getRegistration(PlexonKeysAPI.class);
        if (registration == null) throw new IllegalStateException("PlexonKeysAPI service is not registered");
        return registration.getProvider();
    }

    private static void requirePrimaryThread() {
        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException("PlexonKeysAPI requires the primary server thread");
        }
    }
}
