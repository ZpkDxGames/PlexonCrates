package com.plexoncrates.util;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;

public final class ItemCodec {
    private ItemCodec() {}

    public static ItemStack read(ConfigurationSection section) {
        if (section == null) return new ItemStack(Material.STONE);
        String base64 = section.getString("base64", "");
        if (!base64.isBlank()) return decode(base64);

        Material material = Material.matchMaterial(section.getString("material", "STONE"));
        if (material == null || material.isAir()) material = Material.STONE;
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
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
             BukkitObjectOutputStream output = new BukkitObjectOutputStream(bytes)) {
            output.writeObject(item == null ? new ItemStack(Material.AIR) : item.clone());
            output.flush();
            return Base64.getEncoder().encodeToString(bytes.toByteArray());
        } catch (Exception error) {
            throw new IllegalStateException("Unable to serialize ItemStack", error);
        }
    }

    public static ItemStack decode(String encoded) {
        try (ByteArrayInputStream bytes = new ByteArrayInputStream(Base64.getDecoder().decode(encoded));
             BukkitObjectInputStream input = new BukkitObjectInputStream(bytes)) {
            Object object = input.readObject();
            if (!(object instanceof ItemStack item)) {
                throw new IllegalArgumentException("Serialized object is not an ItemStack");
            }
            return item;
        } catch (Exception error) {
            throw new IllegalArgumentException("Unable to deserialize ItemStack", error);
        }
    }

    public static byte[] encodeBytes(ItemStack item) {
        return Base64.getDecoder().decode(encode(item));
    }

    public static ItemStack decodeBytes(byte[] bytes) {
        return decode(Base64.getEncoder().encodeToString(bytes));
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
