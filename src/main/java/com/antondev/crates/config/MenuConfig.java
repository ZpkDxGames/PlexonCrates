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
        if (!yaml.contains("reward-builder.chance") && yaml.contains("reward-builder.weight")) {
            yaml.set("reward-builder.chance", yaml.get("reward-builder.weight"));
        }
        installRewardPoolFallback(yaml);
        installDraftFallbacks(yaml);
        installClaimFallbacks(yaml);
        validateMenu("browser", List.of("info", "close"));
        validateMenu("preview", List.of("open", "previous", "back", "next"));
        validateMenu("opening", List.of("marker"));
        validateMenu("admin", List.of("crates", "keys", "locations", "rewards", "statistics", "system", "close"));
        validateMenu("editor", List.of("preview", "rename", "key", "rewards", "description", "order",
                "create-reward", "wand", "opening", "display", "access", "disable",
                "draft-status", "publish", "archive", "undo", "clone", "back", "takeover", "delete"));
        validateMenu("confirm-delete", List.of("confirm", "cancel"));
        validateMenu("summary", List.of("close"));
        validateMenu("claims", List.of("previous", "back", "guide", "next", "close"));
        validateMenu("crate-list", List.of("create", "previous", "back", "next"));
        validateMenu("key-list", List.of("create", "sync", "previous", "back", "next"));
        validateMenu("key-template", List.of("name", "previous", "legacy", "confirm", "cancel", "input-placeholder"));
        validateMenu("key-select", List.of("back", "previous", "next"));
        validateMenu("reward-pool", List.of("empty", "add-special", "search", "previous", "back",
                "status", "preview", "next", "balance", "done"));
        validateMenu("reward-builder", List.of("name", "chance", "command", "experience", "money", "rarity",
                "clear", "permissions", "limits", "messages", "effects", "enabled", "order", "confirm", "cancel", "input-placeholder"));
        validateMenu("locations", List.of("wand", "previous", "back", "next"));
        validateMenu("statistics", List.of("summary", "back"));
        validateMenu("system", List.of("validate", "reload", "backup", "diagnose", "back"));
        validateMenu("global-rewards", List.of("previous", "back", "next"));
        validateMenu("wand-select", List.of("previous", "back", "next"));
        validateMenu("confirm-unlink", List.of("confirm", "cancel"));
        validateMenu("confirm-crate-delete", List.of("confirm", "cancel"));
        validateMenu("confirm-key-delete", List.of("confirm", "cancel"));
        validateMenu("confirm-takeover", List.of("confirm", "cancel"));
        item("filler");
        for (String line : yaml.getStringList("preview.reward-lore")) Text.parse(line);
    }

    public static MenuConfig load(File file) {
        return new MenuConfig(YamlConfiguration.loadConfiguration(file));
    }

    public boolean contains(String path) {
        return yaml.contains(path);
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

    private static void installRewardPoolFallback(YamlConfiguration yaml) {
        if (yaml.contains("reward-pool")) return;
        yaml.set("reward-pool.title", "<crate> <dark_gray>›</dark_gray> <yellow>Rewards</yellow>");
        yaml.set("reward-pool.size", 54);
        yaml.set("reward-pool.reward-slots", List.of(
                10, 11, 12, 13, 14, 15, 16,
                19, 20, 21, 22, 23, 24, 25,
                28, 29, 30, 31, 32, 33, 34,
                37, 38, 39, 40, 41, 42, 43));
        installItem(yaml, "reward-pool.empty", null, "HOPPER", "<gray>Drop an item here</gray>",
                List.of("<dark_gray>Your item is copied, never moved.</dark_gray>"));
        installItem(yaml, "reward-pool.add-special", 45, "COMMAND_BLOCK", "<gold>Add Special Reward</gold>",
                List.of("<gray>Create a command, XP, level, money, or mixed reward.</gray>"));
        installItem(yaml, "reward-pool.search", 46, "COMPASS", "<aqua>Search / Filter</aqua>", List.of());
        installItem(yaml, "reward-pool.previous", 47, "ARROW", "<gray>Previous Page</gray>", List.of());
        installItem(yaml, "reward-pool.back", 48, "OAK_DOOR", "<gray>Back to Crate Studio</gray>", List.of());
        installItem(yaml, "reward-pool.status", 49, "FILLED_MAP", "<yellow>Pool Health</yellow>", List.of(
                "<gray>Rewards:</gray> <white><count></white>",
                "<gray>Base total:</gray> <white><total>%</white>",
                "<gray>State:</gray> <state>",
                "<dark_gray>Drag or shift-click to copy an exact item.</dark_gray>"));
        installItem(yaml, "reward-pool.preview", 50, "ENDER_EYE", "<aqua>Preview Pool</aqua>", List.of());
        installItem(yaml, "reward-pool.next", 51, "ARROW", "<gray>Next Page</gray>", List.of());
        installItem(yaml, "reward-pool.balance", 52, "COMPARATOR", "<light_purple>Balance Chances</light_purple>",
                List.of("<gray>Left relative • Right equal</gray>",
                        "<gray>Shift-left rarity • Shift-right unlocked</gray>"));
        installItem(yaml, "reward-pool.done", 53, "LIME_CONCRETE", "<green>Done</green>", List.of());
    }

    private static void installClaimFallbacks(YamlConfiguration yaml) {
        if (!yaml.contains("claims")) {
            yaml.set("claims.title", "<gradient:#DDE5F0:#A3BEDF><bold>Claim Inbox</bold></gradient> <dark_gray>•</dark_gray> <gray>Page <page></gray>");
            yaml.set("claims.size", 54);
            yaml.set("claims.claim-slots", List.of(
                    10, 11, 12, 13, 14, 15, 16,
                    19, 20, 21, 22, 23, 24, 25,
                    28, 29, 30, 31, 32, 33, 34,
                    37, 38, 39, 40, 41, 42, 43));
            installItem(yaml, "claims.previous", 47, "ARROW", "<gray>Previous page</gray>", List.of());
            installItem(yaml, "claims.back", 48, "OAK_DOOR", "<gray>Back to crates</gray>", List.of());
            installItem(yaml, "claims.guide", 49, "BOOK", "<yellow>Claim Inbox</yellow>",
                    List.of("<gray>Pending:</gray> <white><count></white>",
                            "<gray>Click an exact item to deliver it safely.</gray>",
                            "<dark_gray>Capacity is checked before anything changes.</dark_gray>"));
            installItem(yaml, "claims.next", 51, "ARROW", "<gray>Next page</gray>", List.of());
            installItem(yaml, "claims.close", 53, "BARRIER", "<red>Close</red>", List.of());
        }
        if (!yaml.contains("browser.claims")) {
            installItem(yaml, "browser.claims", 20, "CHEST",
                    "<yellow>Claim Inbox</yellow>",
                    List.of("<gray>Deliver exact overflow and recovery items.</gray>",
                            "<dark_gray>Pending claims: <count></dark_gray>"));
        }
    }

    private static void installDraftFallbacks(YamlConfiguration yaml) {
        if (!yaml.contains("editor.draft-status")) {
            installItem(yaml, "editor.draft-status", 40, "PAPER", "<white>Draft <draft_state></white>", List.of(
                    "<gray>Editor:</gray> <white><draft_owner></white>",
                    "<gray>Revision:</gray> <white><draft_revision></white>",
                    "<dark_gray>Failed saves can be retried here.</dark_gray>"));
        }
        if (!yaml.contains("editor.undo")) {
            installItem(yaml, "editor.undo", 47, "CLOCK", "<yellow>Undo Last Change</yellow>", List.of(
                    "<gray>Creates a new forward revision from the previous snapshot.</gray>"));
        }
        if (!yaml.contains("editor.takeover")) {
            installItem(yaml, "editor.takeover", 51, "IRON_DOOR", "<gold>Take Over Draft</gold>", List.of(
                    "<gray>Requires confirmation and invalidates the old editor lease.</gray>"));
        }
        if (!yaml.contains("confirm-takeover")) {
            yaml.set("confirm-takeover.title", "<red>Take over <crate_id> draft?</red>");
            yaml.set("confirm-takeover.size", 27);
            installItem(yaml, "confirm-takeover.confirm", 11, "LIME_CONCRETE", "<green>Confirm Takeover</green>",
                    List.of("<gray>The current editor becomes read-only immediately.</gray>"));
            installItem(yaml, "confirm-takeover.cancel", 15, "RED_CONCRETE", "<red>Cancel</red>", List.of());
        }
    }

    private static void installItem(YamlConfiguration yaml, String path, Integer slot, String material,
                                    String name, List<String> lore) {
        if (slot != null) yaml.set(path + ".slot", slot);
        yaml.set(path + ".material", material);
        yaml.set(path + ".name", name);
        yaml.set(path + ".lore", lore);
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
        for (String list : List.of("crate-slots", "reward-slots", "rail-slots", "entry-slots", "key-slots",
                "item-slots", "location-slots", "claim-slots")) {
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
