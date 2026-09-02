package com.antondev.crates.domain.key;

import java.util.Map;
import java.util.Optional;
import org.bukkit.inventory.ItemStack;

public interface PhysicalKeyProvider {
    String id();
    ProviderStatus status();
    Map<String, ExternalKeyDescriptor> discover();
    Optional<ItemStack> resolve(String externalId);
    String diagnostic();
}
