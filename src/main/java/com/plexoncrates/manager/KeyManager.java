package com.plexoncrates.manager;

import com.plexoncrates.config.ConfigManager;
import com.plexoncrates.crate.Crate;
import com.plexoncrates.database.DatabaseManager;
import com.plexoncrates.util.ItemCodec;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/** Physical and virtual key settlement with exact physical-item identity. */
public final class KeyManager {
    private static final String PHYSICAL_REFUND_REWARD_ID = "__physical_key_refund__";

    private final ConfigManager config;
    private final DatabaseManager database;

    public KeyManager(ConfigManager config, DatabaseManager database) {
        this.config = config;
        this.database = database;
    }

    public boolean matchesPhysical(ItemStack candidate, Crate crate) {
        if (!config.physicalKeysEnabled() || candidate == null || candidate.getType().isAir()) return false;
        ItemStack expected = crate.keyItem();
        if (candidate.getType() != expected.getType()) return false;
        try {
            // Amount is intentionally excluded from key identity; every other native component is exact.
            return ItemCodec.exactBytesEqual(ItemCodec.one(candidate), ItemCodec.one(expected));
        } catch (RuntimeException invalidSnapshot) {
            return false;
        }
    }

    public int countPhysical(Player player, Crate crate) {
        int amount = 0;
        for (ItemStack item : player.getInventory().getStorageContents()) {
            if (matchesPhysical(item, crate)) amount += item.getAmount();
        }
        ItemStack offhand = player.getInventory().getItemInOffHand();
        if (matchesPhysical(offhand, crate)) amount += offhand.getAmount();
        return amount;
    }

    public boolean consumePhysical(Player player, Crate crate) {
        ItemStack[] contents = player.getInventory().getStorageContents();
        for (int slot = 0; slot < contents.length; slot++) {
            ItemStack item = contents[slot];
            if (!matchesPhysical(item, crate)) continue;
            if (item.getAmount() <= 1) contents[slot] = null;
            else item.setAmount(item.getAmount() - 1);
            player.getInventory().setStorageContents(contents);
            return true;
        }
        ItemStack offhand = player.getInventory().getItemInOffHand();
        if (matchesPhysical(offhand, crate)) {
            if (offhand.getAmount() <= 1) player.getInventory().setItemInOffHand(null);
            else offhand.setAmount(offhand.getAmount() - 1);
            return true;
        }
        return false;
    }

    /**
     * Restores one consumed physical key. If the inventory no longer has room, the exact key is
     * persisted as a durable claim instead of being reconstructed or silently dropped.
     */
    public CompletableFuture<Void> refundPhysical(Player player, Crate crate) {
        ItemStack key = ItemCodec.one(crate.keyItem());
        Map<Integer, ItemStack> leftovers = player.getInventory().addItem(key);
        if (leftovers.isEmpty()) return CompletableFuture.completedFuture(null);

        CompletableFuture<?>[] writes = leftovers.values().stream()
                .map(leftover -> database.queueClaim(
                        player.getUniqueId(), crate.id(), PHYSICAL_REFUND_REWARD_ID,
                        ItemCodec.encodeBytes(leftover)))
                .toArray(CompletableFuture[]::new);
        return CompletableFuture.allOf(writes);
    }

    public CompletableFuture<Boolean> consumeVirtual(UUID playerId, Crate crate) {
        if (!config.virtualKeysEnabled()) return CompletableFuture.completedFuture(false);
        return database.tryConsumeVirtualKey(playerId, crate.id());
    }

    public CompletableFuture<Long> grantVirtual(UUID playerId, Crate crate, long amount) {
        if (!config.virtualKeysEnabled()) {
            return CompletableFuture.failedFuture(new IllegalStateException("Virtual keys are disabled"));
        }
        return database.grantVirtualKeys(playerId, crate.id(), amount);
    }
}
