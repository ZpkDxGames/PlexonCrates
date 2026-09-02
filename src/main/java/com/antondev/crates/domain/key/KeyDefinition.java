package com.antondev.crates.domain.key;

import java.time.Instant;
import java.util.List;
import net.kyori.adventure.text.Component;
import org.bukkit.inventory.ItemStack;

public record KeyDefinition(
        String id,
        boolean enabled,
        Component displayName,
        ItemStack icon,
        KeySource source,
        String externalId,
        KeyMatchMode matchMode,
        boolean cacheLastKnownGood,
        ItemStack ownedTemplate,
        ItemStack fallbackTemplate,
        List<ItemStack> legacyTemplates,
        Instant createdAt,
        Instant updatedAt) {

    public KeyDefinition {
        icon = copy(icon);
        ownedTemplate = copy(ownedTemplate);
        fallbackTemplate = copy(fallbackTemplate);
        legacyTemplates = legacyTemplates.stream().map(KeyDefinition::copy).toList();
    }

    @Override public ItemStack icon() { return copy(icon); }
    @Override public ItemStack ownedTemplate() { return copy(ownedTemplate); }
    @Override public ItemStack fallbackTemplate() { return copy(fallbackTemplate); }
    @Override public List<ItemStack> legacyTemplates() { return legacyTemplates.stream().map(KeyDefinition::copy).toList(); }

    private static ItemStack copy(ItemStack value) {
        if (value == null) return null;
        ItemStack copy = value.clone();
        copy.setAmount(1);
        return copy;
    }
}
