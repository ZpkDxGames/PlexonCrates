package com.antondev.crates.domain.key;

import org.bukkit.inventory.ItemStack;

public record ExternalKeyDescriptor(String id, String providerId, ItemStack template) {
    public ExternalKeyDescriptor {
        template = template.clone();
        template.setAmount(1);
    }

    @Override
    public ItemStack template() {
        return template.clone();
    }
}
