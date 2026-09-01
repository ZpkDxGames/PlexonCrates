package com.antondev.crates.config;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;

public final class MenuConfig {
    private final YamlConfiguration yaml;

    private MenuConfig(YamlConfiguration yaml) {
        this.yaml = yaml;
        validateMenu("browser", List.of("info", "close"));
        validateMenu("preview", List.of("open", "previous", "back", "next"));
        validateMenu("opening", List.of("marker"));
        validateMenu("admin", List.of("status", "reload"));
        validateMenu("editor", List.of("preview", "location", "capture", "rewards", "key", "back"));
        validateMenu("confirm-delete", List.of("confirm", "cancel"));
        item("filler");
        for (String line : yaml.getStringList("preview.reward-lore")) Text.parse(line);
    }

    public static MenuConfig load(File file) {
        return new MenuConfig(YamlConfiguration.loadConfiguration(file));
    }

    public int size(String path) {
        int size = yaml.getInt(path + ".size");
        if (size < 9 || size > 54 || size % 9 != 0) throw new IllegalArgumentException(path + ".size must be 9-54 and a multiple of 9");
        return size;
    }

    public Component title(String path, TagResolver... tags) {
        return Text.parse(required(path + ".title"), tags);
    }

    public ItemStack item(String path, TagResolver... tags) {
        return ItemCodec.configured(yaml, path, tags);
    }

    public int slot(String path) {
        return yaml.getInt(path + ".slot");
    }

    public List<Integer> slots(String path) {
        List<Integer> result = new ArrayList<>();
        for (Object value : yaml.getList(path, List.of())) {
            if (!(value instanceof Number number)) throw new IllegalArgumentException(path + " must contain whole slot numbers");
            result.add(number.intValue());
        }
        if (result.isEmpty()) throw new IllegalArgumentException(path + " cannot be empty");
        return List.copyOf(result);
    }

    public List<String> strings(String path) {
        return yaml.getStringList(path);
    }

    private void validateMenu(String path, List<String> items) {
        int size = size(path);
        title(path);
        var used = new HashSet<Integer>();
        for (String item : items) {
            String itemPath = path + "." + item;
            this.item(itemPath);
            if (yaml.contains(itemPath + ".slot")) validateSlot(itemPath + ".slot", size, used);
        }
        for (String list : List.of("crate-slots", "reward-slots", "rail-slots")) {
            String listPath = path + "." + list;
            if (!yaml.contains(listPath)) continue;
            for (int slot : slots(listPath)) validateSlot(slot, listPath, size, used);
        }
        for (String key : List.of("marker-top-slot", "marker-bottom-slot")) {
            String slotPath = path + "." + key;
            if (yaml.contains(slotPath)) validateSlot(slotPath, size, used);
        }
        String centerPath = path + ".center-slot";
        if (yaml.contains(centerPath)) {
            int center = yaml.getInt(centerPath);
            if (center < 0 || center >= size) throw new IllegalArgumentException(centerPath + " is outside this menu");
            if (yaml.contains(path + ".rail-slots") && !slots(path + ".rail-slots").contains(center)) {
                throw new IllegalArgumentException(centerPath + " must be one of the rail slots");
            }
        }
    }

    private void validateSlot(String path, int size, HashSet<Integer> used) {
        validateSlot(yaml.getInt(path), path, size, used);
    }

    private void validateSlot(int slot, String path, int size, HashSet<Integer> used) {
        if (slot < 0 || slot >= size) throw new IllegalArgumentException(path + " is outside this menu");
        if (!used.add(slot)) throw new IllegalArgumentException("Overlapping menu slot: " + path + " = " + slot);
    }

    private String required(String path) {
        String value = yaml.getString(path);
        if (value == null) throw new IllegalArgumentException("Missing menus.yml entry: " + path);
        return value;
    }
}
