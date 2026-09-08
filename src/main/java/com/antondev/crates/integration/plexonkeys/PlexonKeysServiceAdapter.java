package com.antondev.crates.integration.plexonkeys;

import com.antondev.crates.config.ItemCodec;
import com.antondev.crates.domain.key.ExternalKeyDescriptor;
import com.antondev.keys.api.PlexonKeysAPI;
import com.antondev.keys.model.KeyTier;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Direct adapter for the stable PlexonKeys 1.2 Bukkit service API.
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
}
