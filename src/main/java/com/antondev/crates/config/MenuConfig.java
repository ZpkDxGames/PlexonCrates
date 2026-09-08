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
        installSelectiveFallbacks(yaml);
        installMassOpeningFallbacks(yaml);
        installRerollFallbacks(yaml);
        installMilestoneFallbacks(yaml);
        validateMenu("browser", List.of("info", "close"));
        validateMenu("preview", List.of("open", "previous", "back", "next"));
        validateMenu("mass-open", List.of("guide", "one", "five", "ten", "custom", "maximum", "back"));
        validateMenu("selective-confirm", List.of("guide", "confirm", "reward", "cancel"));
        validateMenu("opening", List.of("marker"));
        validateMenu("reroll", List.of("guide", "accept", "candidate", "reroll", "countdown"));
        validateMenu("admin", List.of("crates", "keys", "locations", "rewards", "statistics", "system", "close"));
        validateMenu("editor", List.of("preview", "rename", "key", "rewards", "description", "order",
                "create-reward", "wand", "opening", "display", "access", "rerolls", "milestones", "disable",
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
                "clear", "permissions", "limits", "alternative", "messages", "availability", "effects",
                "enabled", "order", "confirm", "cancel", "input-placeholder"));
        validateMenu("milestone-list", List.of("create", "previous", "back", "next"));
        validateMenu("milestone-detail", List.of("display", "threshold", "repeat", "cycle", "delivery",
                "reward", "preview", "delete", "back"));
        validateMenu("milestone-reward-select", List.of("previous", "back", "next"));
        validateMenu("confirm-milestone-delete", List.of("confirm", "cancel"));
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

    private static void installSelectiveFallbacks(YamlConfiguration yaml) {
        if (yaml.contains("selective-confirm")) return;
        yaml.set("selective-confirm.title", "<crate> <dark_gray>•</dark_gray> <gray>Confirm reward</gray>");
        yaml.set("selective-confirm.size", 27);
        installItem(yaml, "selective-confirm.guide", 4, "BOOK", "<aqua>Selective opening</aqua>",
                List.of("<gray>The chosen reward is revalidated before payment.</gray>",
                        "<gray>Closing this screen consumes nothing.</gray>"));
        installItem(yaml, "selective-confirm.confirm", 11, "LIME_CONCRETE", "<green><bold>Confirm choice</bold></green>",
                List.of("<gray>Amount:</gray> <white><amount></white>",
                        "<gray>Exact key cost:</gray> <white><cost></white>",
                        "<green>Click to confirm.</green>"));
        installItem(yaml, "selective-confirm.reward", 13, "CHEST", "<yellow>Chosen reward</yellow>", List.of());
        installItem(yaml, "selective-confirm.cancel", 15, "RED_CONCRETE", "<red>Back to choices</red>",
                List.of("<gray>No key or state is consumed.</gray>"));
    }

    private static void installMassOpeningFallbacks(YamlConfiguration yaml) {
        if (yaml.contains("mass-open")) return;
        yaml.set("mass-open.title", "<crate> <dark_gray>•</dark_gray> <gray>Choose amount</gray>");
        yaml.set("mass-open.size", 27);
        installItem(yaml, "mass-open.guide", 4, "BOOK", "<aqua>Mass opening</aqua>", List.of(
                "<gray>Maximum available now:</gray> <white><maximum></white>",
                "<gray>Cost per opening:</gray> <white><key_cost></white>",
                "<dark_gray>Left-click uses physical; right-click prefers virtual.</dark_gray>"));
        installItem(yaml, "mass-open.one", 10, "LIME_CONCRETE", "<green>Open 1</green>", List.of(
                "<gray>Exact key cost:</gray> <white><cost></white>"));
        installItem(yaml, "mass-open.five", 11, "LIME_CONCRETE", "<green>Open 5</green>", List.of(
                "<gray>Exact key cost:</gray> <white><cost></white>"));
        installItem(yaml, "mass-open.ten", 12, "LIME_CONCRETE", "<green>Open 10</green>", List.of(
                "<gray>Exact key cost:</gray> <white><cost></white>"));
        installItem(yaml, "mass-open.custom", 14, "WRITABLE_BOOK", "<yellow>Custom amount</yellow>", List.of(
                "<gray>Enter a whole amount from 1 to <maximum>.</gray>"));
        installItem(yaml, "mass-open.maximum", 16, "CHEST", "<gold>Maximum Available: <amount></gold>", List.of(
                "<gray>Exact key cost:</gray> <white><cost></white>",
                "<gray>Capacity is revalidated before anything is consumed.</gray>"));
        installItem(yaml, "mass-open.back", 22, "OAK_DOOR", "<gray>Back to rewards</gray>", List.of(
                "<gray>Closing this screen consumes nothing.</gray>"));
    }

    private static void installRerollFallbacks(YamlConfiguration yaml) {
        if (!yaml.contains("reroll")) {
            yaml.set("reroll.title", "<crate> <dark_gray>•</dark_gray> <gray>Choose your reward</gray>");
            yaml.set("reroll.size", 27);
            installItem(yaml, "reroll.guide", 4, "BOOK", "<aqua>Reward decision</aqua>", List.of(
                    "<gray>Your opening payment has already been consumed.</gray>",
                    "<gray>Close or timeout safely accepts the current reward.</gray>"));
            installItem(yaml, "reroll.accept", 11, "LIME_CONCRETE",
                    "<green><bold>Accept Reward</bold></green>",
                    List.of("<green>Deliver this exact reward now.</green>"));
            installItem(yaml, "reroll.candidate", 13, "CHEST", "<yellow>Current Reward</yellow>", List.of());
            installItem(yaml, "reroll.reroll", 15, "ENDER_EYE",
                    "<light_purple><bold>Reroll</bold></light_purple>", List.of(
                            "<gray>Remaining:</gray> <white><remaining></white>",
                            "<gray>Cost:</gray> <white><cost></white>", "<state>"));
            installItem(yaml, "reroll.countdown", 22, "CLOCK", "<yellow>Auto-accept</yellow>", List.of(
                    "<gray>Seconds remaining:</gray> <white><seconds></white>"));
        }
        if (!yaml.contains("editor.rerolls")) {
            installItem(yaml, "editor.rerolls", 38, "ENDER_EYE", "<light_purple>Rerolls</light_purple>", List.of(
                    "<gray>State:</gray> <white><reroll_state></white>",
                    "<gray>Maximum:</gray> <white><reroll_max></white>",
                    "<gray>Cost:</gray> <white><reroll_cost></white>",
                    "<dark_gray>Left toggle • Right configure</dark_gray>"));
        }
    }

    private static void installMilestoneFallbacks(YamlConfiguration yaml) {
        if (!yaml.contains("editor.milestones")) {
            installItem(yaml, "editor.milestones", 39, "TARGET", "<gold>Milestones</gold>", List.of(
                    "<gray>Configure cumulative opening rewards.</gray>",
                    "<dark_gray>Definitions remain safe when the module is disabled.</dark_gray>"));
        }
        if (!yaml.contains("milestone-list")) {
            yaml.set("milestone-list.title", "<crate> <dark_gray>›</dark_gray> <gold>Milestones</gold> <dark_gray>•</dark_gray> <gray>Page <page></gray>");
            yaml.set("milestone-list.size", 54);
            yaml.set("milestone-list.entry-slots", List.of(
                    10, 11, 12, 13, 14, 15, 16,
                    19, 20, 21, 22, 23, 24, 25,
                    28, 29, 30, 31, 32, 33, 34));
            installItem(yaml, "milestone-list.create", 45, "LIME_CONCRETE", "<green>Create Milestone</green>",
                    List.of("<gray>Choose an existing exact reward, then refine its threshold.</gray>"));
            installItem(yaml, "milestone-list.previous", 47, "ARROW", "<gray>Previous</gray>", List.of());
            installItem(yaml, "milestone-list.back", 49, "OAK_DOOR", "<gray>Back to Crate Studio</gray>", List.of());
            installItem(yaml, "milestone-list.next", 51, "ARROW", "<gray>Next</gray>", List.of());
        }
        if (!yaml.contains("milestone-detail")) {
            yaml.set("milestone-detail.title", "<crate> <dark_gray>›</dark_gray> <gold><milestone></gold>");
            yaml.set("milestone-detail.size", 27);
            installItem(yaml, "milestone-detail.display", 4, "TARGET", "<gold>Milestone Display</gold>",
                    List.of("<gray>Cursor-click or shift-click an exact replacement item.</gray>"));
            installItem(yaml, "milestone-detail.threshold", 10, "CLOCK", "<yellow>Opening Threshold</yellow>",
                    List.of("<gray>Current:</gray> <white><threshold></white>", "<green>Click to enter one whole number.</green>"));
            installItem(yaml, "milestone-detail.repeat", 11, "REPEATER", "<aqua>Repeat Policy</aqua>",
                    List.of("<gray>Current:</gray> <white><repeat></white>", "<green>Click to toggle.</green>"));
            installItem(yaml, "milestone-detail.cycle", 12, "COMPASS", "<aqua>Cycle Length</aqua>",
                    List.of("<gray>Current:</gray> <white><cycle></white>", "<green>Click to edit when repeating.</green>"));
            installItem(yaml, "milestone-detail.delivery", 14, "CHEST", "<yellow>Delivery Policy</yellow>",
                    List.of("<gray>Current:</gray> <white><delivery></white>", "<green>Click to toggle.</green>"));
            installItem(yaml, "milestone-detail.reward", 15, "NETHER_STAR", "<light_purple>Exact Reward</light_purple>",
                    List.of("<gray>Current:</gray> <white><reward></white>", "<green>Click to choose another reward.</green>"));
            installItem(yaml, "milestone-detail.preview", 16, "SPYGLASS", "<green>Player Preview</green>",
                    List.of("<gray>Visible:</gray> <white><visible></white>", "<green>Click to toggle.</green>"));
            installItem(yaml, "milestone-detail.delete", 20, "RED_CONCRETE", "<red>Delete Milestone</red>",
                    List.of("<dark_red>Requires confirmation.</dark_red>"));
            installItem(yaml, "milestone-detail.back", 22, "OAK_DOOR", "<gray>Back to Milestones</gray>", List.of());
        }
        if (!yaml.contains("milestone-reward-select")) {
            yaml.set("milestone-reward-select.title", "<crate> <dark_gray>›</dark_gray> <light_purple>Milestone Reward</light_purple> <dark_gray>•</dark_gray> <gray>Page <page></gray>");
            yaml.set("milestone-reward-select.size", 54);
            yaml.set("milestone-reward-select.reward-slots", List.of(
                    10, 11, 12, 13, 14, 15, 16,
                    19, 20, 21, 22, 23, 24, 25,
                    28, 29, 30, 31, 32, 33, 34));
            installItem(yaml, "milestone-reward-select.previous", 47, "ARROW", "<gray>Previous</gray>", List.of());
            installItem(yaml, "milestone-reward-select.back", 49, "OAK_DOOR", "<gray>Back</gray>", List.of());
            installItem(yaml, "milestone-reward-select.next", 51, "ARROW", "<gray>Next</gray>", List.of());
        }
        if (!yaml.contains("confirm-milestone-delete")) {
            yaml.set("confirm-milestone-delete.title", "<red>Delete milestone <milestone>?</red>");
            yaml.set("confirm-milestone-delete.size", 27);
            installItem(yaml, "confirm-milestone-delete.confirm", 11, "LIME_CONCRETE", "<green>Confirm Delete</green>", List.of());
            installItem(yaml, "confirm-milestone-delete.cancel", 15, "RED_CONCRETE", "<red>Keep Milestone</red>", List.of());
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
