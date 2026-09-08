package com.plexoncrates.util;

import com.plexoncrates.item.ExactItemSnapshot;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.util.io.BukkitObjectInputStream;

/**
 * Item persistence codec.
 *
 * <p>All new writes use Paper's native NBT byte serialization. The Bukkit object-stream reader is
 * retained only so existing 4.0 test definitions can be loaded and rewritten in the native format.
 * No new object-stream snapshots are produced.</p>
 */
public final class ItemCodec {
    private ItemCodec() {}

    public static ItemStack read(ConfigurationSection section) {
        if (section == null) throw new IllegalArgumentException("Missing item configuration section");
        String base64 = section.getString("base64", "");
        if (!base64.isBlank()) {
            byte[] bytes;
            try {
                bytes = Base64.getDecoder().decode(base64);
            } catch (IllegalArgumentException invalidBase64) {
                throw new IllegalArgumentException("Invalid item snapshot Base64", invalidBase64);
            }
            String expectedHash = section.getString("sha256", "").trim().toLowerCase(java.util.Locale.ROOT);
            if (!expectedHash.isBlank()) {
                String actualHash = ExactItemSnapshot.sha256Hex(bytes);
                if (!expectedHash.equals(actualHash)) {
                    throw new IllegalArgumentException("Item snapshot checksum mismatch");
                }
            }
            return decodeBytes(bytes);
        }

        // Compatibility path for legacy human-readable Plexon definitions. This is never the
        // authoritative format for newly captured exact items.
        Material material = Material.matchMaterial(section.getString("material", ""));
        if (material == null || material.isAir()) {
            throw new IllegalArgumentException("Missing or invalid legacy item material");
        }
        int amount = Math.max(1, Math.min(material.getMaxStackSize(), section.getInt("amount", 1)));
        ItemStack item = new ItemStack(material, amount);
        ItemMeta meta = item.getItemMeta();

        String name = section.getString("name");
        if (name != null && !name.isBlank()) meta.setDisplayName(ColorUtil.color(name));

        List<String> lore = section.getStringList("lore");
        if (!lore.isEmpty()) meta.setLore(ColorUtil.color(lore));

        if (section.contains("custom-model-data")) {
            meta.setCustomModelData(section.getInt("custom-model-data"));
        }
        item.setItemMeta(meta);
        return item;
    }

    public static String encode(ItemStack item) {
        return Base64.getEncoder().encodeToString(encodeBytes(item));
    }

    public static ItemStack decode(String encoded) {
        if (encoded == null || encoded.isBlank()) {
            throw new IllegalArgumentException("Item snapshot cannot be empty");
        }
        try {
            return decodeBytes(Base64.getDecoder().decode(encoded));
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("Unable to deserialize ItemStack snapshot", error);
        }
    }

    public static byte[] encodeBytes(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            throw new IllegalArgumentException("A real ItemStack is required for exact serialization");
        }
        return ExactItemSnapshot.capture(item).encodedBytes();
    }

    public static ItemStack decodeBytes(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            throw new IllegalArgumentException("Item snapshot bytes cannot be empty");
        }

        try {
            return ExactItemSnapshot.fromBytes(bytes).toItemStack();
        } catch (RuntimeException nativeFailure) {
            // Compatibility only: snapshots written by the pre-fidelity 4.0 test line used
            // BukkitObjectOutputStream. Decode them once so the next save can migrate to native NBT.
            try {
                ItemStack legacy = decodeLegacyObjectStream(bytes);
                if (legacy.getType().isAir()) throw new IllegalArgumentException("Legacy snapshot decoded to air");
                return legacy;
            } catch (RuntimeException legacyFailure) {
                nativeFailure.addSuppressed(legacyFailure);
                throw new IllegalArgumentException("Unable to deserialize native or legacy ItemStack snapshot", nativeFailure);
            }
        }
    }

    public static ExactItemSnapshot snapshot(ItemStack item) {
        return ExactItemSnapshot.capture(item);
    }

    public static String fingerprint(ItemStack item) {
        return snapshot(item).sha256();
    }

    public static String fingerprintBytes(byte[] bytes) {
        return ExactItemSnapshot.sha256Hex(bytes);
    }

    public static boolean exactBytesEqual(ItemStack first, ItemStack second) {
        if (first == null || second == null) return first == second;
        return Arrays.equals(encodeBytes(first), encodeBytes(second));
    }

    private static ItemStack decodeLegacyObjectStream(byte[] bytes) {
        try (ByteArrayInputStream inputBytes = new ByteArrayInputStream(bytes);
             BukkitObjectInputStream input = new BukkitObjectInputStream(inputBytes)) {
            Object object = input.readObject();
            if (!(object instanceof ItemStack item)) {
                throw new IllegalArgumentException("Legacy serialized object is not an ItemStack");
            }
            return item.clone();
        } catch (Exception error) {
            throw new IllegalArgumentException("Unable to deserialize legacy Bukkit ItemStack", error);
        }
    }

    public static ItemStack one(ItemStack item) {
        ItemStack clone = item.clone();
        clone.setAmount(1);
        return clone;
    }

    public static ItemStack withLore(ItemStack source, List<String> extra) {
        ItemStack item = source.clone();
        ItemMeta meta = item.getItemMeta();
        List<String> lore = meta.hasLore() ? new ArrayList<>(meta.getLore()) : new ArrayList<>();
        lore.addAll(extra);
        meta.setLore(lore);
        item.setItemMeta(meta);
        return item;
    }
}
