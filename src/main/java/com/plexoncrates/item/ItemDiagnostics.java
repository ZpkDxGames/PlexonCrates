package com.plexoncrates.item;

import com.plexoncrates.util.ItemCodec;
import java.util.Comparator;
import java.util.List;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

/** Human-readable exact-item diagnostics without exposing foreign PDC values. */
public final class ItemDiagnostics {
    private ItemDiagnostics() {}

    public record SnapshotInfo(
            String material,
            int amount,
            String format,
            int byteLength,
            String sha256,
            List<String> pdcKeys,
            int minecraftDataVersion) {
        public String shortHash() {
            return sha256.length() <= 12 ? sha256 : sha256.substring(0, 12);
        }
    }

    public record Comparison(
            SnapshotInfo held,
            SnapshotInfo target,
            boolean exact,
            boolean exactIgnoringAmount) {}

    public static SnapshotInfo inspect(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            throw new IllegalArgumentException("A real ItemStack is required");
        }
        ExactItemSnapshot snapshot = ItemCodec.snapshot(item);
        // toItemStack performs a second integrity + canonical round-trip verification.
        snapshot.toItemStack();
        ItemMeta meta = item.getItemMeta();
        List<String> pdc = meta == null ? List.of() : meta.getPersistentDataContainer().getKeys().stream()
                .sorted(Comparator.comparing(NamespacedKey::toString))
                .map(NamespacedKey::toString)
                .toList();
        return new SnapshotInfo(
                item.getType().getKey().toString(),
                item.getAmount(),
                snapshot.formatName(),
                snapshot.encodedBytes().length,
                snapshot.sha256(),
                pdc,
                snapshot.minecraftDataVersion());
    }

    public static Comparison compare(ItemStack held, ItemStack target) {
        SnapshotInfo heldInfo = inspect(held);
        SnapshotInfo targetInfo = inspect(target);
        boolean exact = ItemCodec.exactBytesEqual(held, target);
        boolean ignoringAmount = ItemCodec.exactBytesEqual(ItemCodec.one(held), ItemCodec.one(target));
        return new Comparison(heldInfo, targetInfo, exact, ignoringAmount);
    }
}
