package com.plexoncrates.manager;

import com.plexoncrates.config.ConfigManager;
import com.plexoncrates.crate.Crate;
import com.plexoncrates.database.DatabaseManager;
import com.plexoncrates.util.ItemCodec;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

public final class KeyManager {
    private final ConfigManager config;
    private final DatabaseManager database;

    public KeyManager(ConfigManager config, DatabaseManager database) {
        this.config = config;
        this.database = database;
    }

    public boolean matchesPhysical(ItemStack candidate, Crate crate) {
        if (!config.physicalKeysEnabled() || candidate == null || candidate.getType().isAir()) return false;
        return ItemCodec.one(candidate).isSimilar(ItemCodec.one(crate.keyItem()));
    }

    public int countPhysical(Player player, Crate crate) {
        int amount = 0;
        for (ItemStack item : player.getInventory().getStorageContents()) if (matchesPhysical(item, crate)) amount += item.getAmount();
        ItemStack offhand = player.getInventory().getItemInOffHand();
        if (matchesPhysical(offhand, crate)) amount += offhand.getAmount();
        return amount;
    }

    public boolean consumePhysical(Player player, Crate crate) {
        ItemStack[] contents = player.getInventory().getStorageContents();
        for (int slot = 0; slot < contents.length; slot++) {
            ItemStack item = contents[slot];
            if (!matchesPhysical(item, crate)) continue;
            if (item.getAmount() <= 1) contents[slot] = null; else item.setAmount(item.getAmount() - 1);
            player.getInventory().setStorageContents(contents);
            return true;
        }
        ItemStack offhand = player.getInventory().getItemInOffHand();
        if (matchesPhysical(offhand, crate)) {
            if (offhand.getAmount() <= 1) player.getInventory().setItemInOffHand(null); else offhand.setAmount(offhand.getAmount() - 1);
            return true;
        }
        return false;
    }

    public CompletableFuture<Boolean> consumeVirtual(UUID playerId, Crate crate) {
        if (!config.virtualKeysEnabled()) return CompletableFuture.completedFuture(false);
        return database.tryConsumeVirtualKey(playerId, crate.id());
    }

    public CompletableFuture<Long> grantVirtual(UUID playerId, Crate crate, long amount) {
        if (!config.virtualKeysEnabled()) return CompletableFuture.failedFuture(new IllegalStateException("Virtual keys are disabled"));
        return database.grantVirtualKeys(playerId, crate.id(), amount);
    }
}
