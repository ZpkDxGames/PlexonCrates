package com.antondev.crates.config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Locale;
import java.util.Map;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;

/**
 * Item codec used by editable YAML mirrors and legacy/import surfaces.
 *
 * <p>When {@code base64} is present, those Paper-native bytes are authoritative. Visible YAML
 * overlays are deliberately not applied to exact captures because mutating name/lore/glint,
 * unbreakable state or enchantments after decoding can invalidate foreign-plugin identity.</p>
 */
public final class ItemCodec {
    private static final int MAX_CAPTURE_LENGTH = 4_000_000;

    private ItemCodec() {}

    public static Material material(String input) {
        Material material = Material.matchMaterial(input == null ? "" : input);
        if (material == null || material.isAir() || !material.isItem()) {
            throw new IllegalArgumentException("Invalid item material: " + input);
        }
        return material;
    }

    public static ItemStack read(ConfigurationSection section, TagResolver... tags) {
        return read(section, null, tags);
    }

    public static java.util.List<ItemStack> readMany(ConfigurationSection section, TagResolver... tags) {
        if (section == null) throw new IllegalArgumentException("Missing item section");
        if (!section.contains("amount")) return java.util.List.of(read(section, tags));
        int total = section.getInt("amount");
        if (total < 1 || total > 6_400) {
            throw new IllegalArgumentException("Reward item amount must be between 1 and 6400");
        }
        ItemStack template = read(section, 1, tags);
        var stacks = new ArrayList<ItemStack>();
        int remaining = total;
        while (remaining > 0) {
            ItemStack stack = template.clone();
            stack.setAmount(Math.min(remaining, stack.getMaxStackSize()));
            remaining -= stack.getAmount();
            stacks.add(stack);
        }
        return java.util.List.copyOf(stacks);
    }

    private static ItemStack read(ConfigurationSection section, Integer amountOverride, TagResolver... tags) {
        if (section == null) throw new IllegalArgumentException("Missing item section");
        String encoded = section.getString("base64", "");
        boolean exactCapture = !encoded.isBlank();
        ItemStack item;
        if (exactCapture) {
            if (encoded.length() > MAX_CAPTURE_LENGTH) {
                throw new IllegalArgumentException("Captured item data is too large");
            }
            try {
                item = ItemStack.deserializeBytes(Base64.getDecoder().decode(encoded));
            } catch (RuntimeException error) {
                throw new IllegalArgumentException("Invalid captured item data", error);
            }
            if (item == null || item.getType().isAir()) {
                throw new IllegalArgumentException("Captured item is empty");
            }
        } else {
            item = new ItemStack(material(section.getString("material", "PAPER")));
        }

        int amount = amountOverride != null
                ? amountOverride
                : section.contains("amount") ? section.getInt("amount") : item.getAmount();
        if (amount < 1 || amount > item.getMaxStackSize()) {
            throw new IllegalArgumentException("Item amount must be between 1 and " + item.getMaxStackSize());
        }
        item.setAmount(amount);

        // Exact captures must remain byte-derived. These overlays are only valid for buildable items.
        if (!exactCapture && (section.contains("name") || section.contains("lore")
                || section.contains("glow") || section.contains("unbreakable"))) {
            item.editMeta(meta -> {
                if (section.contains("name")) {
                    meta.displayName(Text.parse(section.getString("name", ""), tags)
                            .decoration(TextDecoration.ITALIC, false));
                }
                if (section.contains("lore")) {
                    var lore = new ArrayList<net.kyori.adventure.text.Component>();
                    for (String line : section.getStringList("lore")) {
                        lore.add(Text.parse(line, tags).decoration(TextDecoration.ITALIC, false));
                    }
                    meta.lore(lore);
                }
                if (section.contains("glow")) meta.setEnchantmentGlintOverride(section.getBoolean("glow"));
                if (section.contains("unbreakable")) meta.setUnbreakable(section.getBoolean("unbreakable"));
            });
        }

        ConfigurationSection enchantments = section.getConfigurationSection("enchantments");
        if (!exactCapture && enchantments != null) {
            for (Map.Entry<String, Object> entry : enchantments.getValues(false).entrySet()) {
                if (!(entry.getValue() instanceof Number number)) {
                    throw new IllegalArgumentException("Enchantment level must be numeric: " + entry.getKey());
                }
                int level = number.intValue();
                if (level < 1 || level > 255) {
                    throw new IllegalArgumentException("Enchantment level must be between 1 and 255");
                }
                NamespacedKey key = NamespacedKey.fromString(entry.getKey().contains(":")
                        ? entry.getKey().toLowerCase(Locale.ROOT)
                        : "minecraft:" + entry.getKey().toLowerCase(Locale.ROOT));
                Enchantment enchantment = key == null ? null : Registry.ENCHANTMENT.get(key);
                if (enchantment == null) {
                    throw new IllegalArgumentException("Unknown enchantment: " + entry.getKey());
                }
                item.addUnsafeEnchantment(enchantment, level);
            }
        }
        return item;
    }

    public static ItemStack configured(ConfigurationSection root, String path, TagResolver... tags) {
        return read(root.getConfigurationSection(path), tags);
    }

    public static String capture(ItemStack item, boolean normalizeAmount) {
        if (item == null || item.getType().isAir()) throw new IllegalArgumentException("Hold an item first");
        ItemStack copy = item.clone();
        if (normalizeAmount) copy.setAmount(1);
        byte[] bytes;
        try {
            bytes = copy.serializeAsBytes();
            verifyCurrentServerRoundTrip(bytes);
        } catch (RuntimeException error) {
            throw new IllegalArgumentException("Paper could not round-trip this exact item", error);
        }
        String encoded = Base64.getEncoder().encodeToString(bytes);
        if (encoded.length() > MAX_CAPTURE_LENGTH) throw new IllegalArgumentException("This item is too large to capture");
        return encoded;
    }

    public static ItemStack decode(String encoded, boolean normalizeAmount) {
        if (encoded == null || encoded.isBlank() || encoded.length() > MAX_CAPTURE_LENGTH) {
            throw new IllegalArgumentException("Invalid captured item data");
        }
        try {
            ItemStack item = ItemStack.deserializeBytes(Base64.getDecoder().decode(encoded));
            if (item == null || item.getType().isAir()) throw new IllegalArgumentException("Captured item is empty");
            if (normalizeAmount) item.setAmount(1);
            return item;
        } catch (RuntimeException error) {
            throw new IllegalArgumentException("Invalid captured item data", error);
        }
    }

    public static ItemStack one(ItemStack source) {
        ItemStack copy = source.clone();
        copy.setAmount(1);
        return copy;
    }

    private static void verifyCurrentServerRoundTrip(byte[] bytes) {
        ItemStack restored = ItemStack.deserializeBytes(bytes.clone());
        if (restored == null || restored.getType().isAir()) {
            throw new IllegalArgumentException("Paper decoded the exact item to an empty item");
        }
        byte[] roundTrip = restored.serializeAsBytes();
        if (!Arrays.equals(bytes, roundTrip)) {
            throw new IllegalArgumentException("Paper native item bytes are not stable on this server build");
        }
    }
}
