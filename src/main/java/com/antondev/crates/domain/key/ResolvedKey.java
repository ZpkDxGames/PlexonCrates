package com.antondev.crates.domain.key;

import java.time.Instant;
import org.bukkit.inventory.ItemStack;

public record ResolvedKey(String keyId, ItemStack template, ResolutionSource source, Instant resolvedAt) {
    public enum ResolutionSource { LIVE, LAST_KNOWN_GOOD, FALLBACK, OWNED }

    public ResolvedKey {
        template = template.clone();
        template.setAmount(1);
    }

    @Override
    public ItemStack template() {
        return template.clone();
    }
}
