from pathlib import Path
import re
import yaml

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "src/main/java/com/antondev/crates"
TEST = ROOT / "src/test/java/com/antondev/crates"


def write(path, text):
    p = ROOT / path
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(text.strip() + "\n", encoding="utf-8")


def replace_once(path, old, new):
    p = ROOT / path
    text = p.read_text(encoding="utf-8")
    if old not in text:
        raise SystemExit(f"expected text not found in {path}: {old[:100]!r}")
    p.write_text(text.replace(old, new, 1), encoding="utf-8")


# Exact release version.
replace_once("pom.xml", "<artifactId>PlexonCrates</artifactId><version>6.0.0-rc.1</version>",
             "<artifactId>PlexonCrates</artifactId><version>6.5.0</version>")

# Versioned menu migration entry point.
replace_once(
    "src/main/java/com/antondev/crates/config/MenuConfig.java",
    "return new MenuConfig(YamlConfiguration.loadConfiguration(file));",
    "return new MenuConfig(GuiConfigMigrator.loadAndMigrate(file));"
)

write("src/main/java/com/antondev/crates/gui/GuiNavigation.java", r'''
package com.antondev.crates.gui;

import java.util.List;

/** Stable 6.5 footer semantics shared by all standard 54-slot inventory views. */
public final class GuiNavigation {
    public static final int PREVIOUS = 45;
    public static final int SEARCH = 46;
    public static final int CONTEXT = 47;
    public static final int BACK = 48;
    public static final int PRIMARY = 49;
    public static final int SECONDARY = 50;
    public static final int STATUS = 51;
    public static final int CLOSE = 52;
    public static final int NEXT = 53;
    public static final List<Integer> FOOTER = List.of(
            PREVIOUS, SEARCH, CONTEXT, BACK, PRIMARY, SECONDARY, STATUS, CLOSE, NEXT);

    private GuiNavigation() {}
}
''')

write("src/main/java/com/antondev/crates/gui/GuiLayout.java", r'''
package com.antondev.crates.gui;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Declarative slot geometry for the PlexonCrates 6.5 inventory design system. */
public record GuiLayout(String id, int size, List<Integer> contentSlots,
                        Set<Integer> frameSlots, Set<Integer> separatorSlots, int headerSlot) {
    private static final List<Integer> LIST_CONTENT = List.of(
            10, 11, 12, 13, 14, 15, 16,
            19, 20, 21, 22, 23, 24, 25,
            28, 29, 30, 31, 32, 33, 34,
            37, 38, 39, 40, 41, 42, 43);

    public static final GuiLayout LIST_54 = new GuiLayout(
            "LIST_54", 54, LIST_CONTENT, frame54(), Set.of(), 4);
    public static final GuiLayout PANEL_54 = new GuiLayout(
            "PANEL_54", 54, panelContent(), frame54(),
            Set.of(12, 14, 21, 23, 30, 32, 39, 41), 4);
    public static final GuiLayout DIALOG_27 = new GuiLayout(
            "DIALOG_27", 27, List.of(11, 13, 15, 22), frame27(), Set.of(), 4);
    public static final GuiLayout OPENING_27 = new GuiLayout(
            "OPENING_27", 27, List.of(10, 11, 12, 13, 14, 15, 16), frame27(), Set.of(), 4);

    public GuiLayout {
        contentSlots = List.copyOf(contentSlots);
        frameSlots = Set.copyOf(frameSlots);
        separatorSlots = Set.copyOf(separatorSlots);
        if (size < 9 || size > 54 || size % 9 != 0) throw new IllegalArgumentException("invalid inventory size");
        for (int slot : contentSlots) check(slot, size);
        for (int slot : frameSlots) check(slot, size);
        for (int slot : separatorSlots) check(slot, size);
        check(headerSlot, size);
        Set<Integer> overlap = new LinkedHashSet<>(contentSlots);
        overlap.retainAll(frameSlots);
        if (!overlap.isEmpty()) throw new IllegalArgumentException("content/frame collision: " + overlap);
        overlap = new LinkedHashSet<>(contentSlots);
        overlap.retainAll(separatorSlots);
        if (!overlap.isEmpty()) throw new IllegalArgumentException("content/separator collision: " + overlap);
    }

    public static GuiLayout forMenu(MenuHolder.Kind kind, int size) {
        if (kind == MenuHolder.Kind.OPENING) return OPENING_27;
        if (isDialog(kind)) return DIALOG_27;
        if (isPanel(kind)) return PANEL_54;
        if (size == 54) return LIST_54;
        return DIALOG_27;
    }

    private static boolean isDialog(MenuHolder.Kind kind) {
        return switch (kind) {
            case MASS_OPEN, SELECTIVE_CONFIRM, PLAYER_QUANTITY, PLAYER_MASS_CONFIRM,
                 PLAYER_SELECTIVE_CONFIRM, REROLL, CONFIRM_DELETE, CONFIRM_MILESTONE_DELETE,
                 CONFIRM_UNLINK, CONFIRM_CRATE_DELETE, CONFIRM_KEY_DELETE, CONFIRM_TAKEOVER -> true;
            default -> false;
        };
    }

    private static boolean isPanel(MenuHolder.Kind kind) {
        return switch (kind) {
            case ADMIN, EDITOR, KEY_TEMPLATE, REWARD_BUILDER, MILESTONE_DETAIL, STATISTICS, SYSTEM -> true;
            default -> false;
        };
    }

    private static Set<Integer> frame54() {
        LinkedHashSet<Integer> slots = new LinkedHashSet<>();
        for (int slot = 0; slot < 9; slot++) slots.add(slot);
        for (int row = 1; row <= 4; row++) {
            slots.add(row * 9);
            slots.add(row * 9 + 8);
        }
        slots.addAll(GuiNavigation.FOOTER);
        return Set.copyOf(slots);
    }

    private static Set<Integer> frame27() {
        LinkedHashSet<Integer> slots = new LinkedHashSet<>();
        for (int slot = 0; slot < 9; slot++) slots.add(slot);
        slots.add(9); slots.add(17); slots.add(18); slots.add(19); slots.add(20);
        slots.add(21); slots.add(23); slots.add(24); slots.add(25); slots.add(26);
        return Set.copyOf(slots);
    }

    private static List<Integer> panelContent() {
        Set<Integer> frame = frame54();
        Set<Integer> separators = Set.of(12, 14, 21, 23, 30, 32, 39, 41);
        List<Integer> slots = new ArrayList<>();
        for (int slot = 9; slot < 45; slot++) {
            if (!frame.contains(slot) && !separators.contains(slot)) slots.add(slot);
        }
        return List.copyOf(slots);
    }

    private static void check(int slot, int size) {
        if (slot < 0 || slot >= size) throw new IllegalArgumentException("slot " + slot + " outside " + size);
    }
}
''')

write("src/main/java/com/antondev/crates/gui/GuiTheme.java", r'''
package com.antondev.crates.gui;

import com.antondev.crates.config.MenuConfig;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

/** Immutable config-backed decorative palette. No transaction state belongs here. */
public record GuiTheme(ItemStack background, ItemStack frame, ItemStack separator,
                       ItemStack section, ItemStack accent, ItemStack success,
                       ItemStack warning, ItemStack danger) {
    public static GuiTheme from(MenuConfig menus) {
        return new GuiTheme(
                item(menus, "theme.background", Material.GRAY_STAINED_GLASS_PANE),
                item(menus, "theme.frame", Material.BLACK_STAINED_GLASS_PANE),
                item(menus, "theme.separator", Material.BLACK_STAINED_GLASS_PANE),
                item(menus, "theme.section", Material.LIGHT_GRAY_STAINED_GLASS_PANE),
                item(menus, "theme.accent", Material.CYAN_STAINED_GLASS_PANE),
                item(menus, "theme.success", Material.LIME_STAINED_GLASS_PANE),
                item(menus, "theme.warning", Material.YELLOW_STAINED_GLASS_PANE),
                item(menus, "theme.danger", Material.RED_STAINED_GLASS_PANE));
    }

    private static ItemStack item(MenuConfig menus, String path, Material fallback) {
        if (menus.contains(path + ".material")) return menus.item(path);
        ItemStack item = new ItemStack(fallback);
        item.editMeta(meta -> {
            meta.displayName(Component.text(" ").decoration(TextDecoration.ITALIC, false));
            meta.lore(List.of());
        });
        return item;
    }

    public ItemStack backgroundCopy() { return background.clone(); }
    public ItemStack frameCopy() { return frame.clone(); }
    public ItemStack separatorCopy() { return separator.clone(); }
    public ItemStack accentCopy() { return accent.clone(); }
}
''')

write("src/main/java/com/antondev/crates/gui/GuiChromeRenderer.java", r'''
package com.antondev.crates.gui;

import com.antondev.crates.config.MenuConfig;
import java.util.LinkedHashSet;
import java.util.Set;
import org.bukkit.inventory.Inventory;

/** Draws inert presentation chrome before functional menu items are bound. */
public final class GuiChromeRenderer {
    private GuiChromeRenderer() {}

    public static void render(Inventory inventory, MenuConfig menus) {
        MenuHolder.Kind kind = inventory.getHolder() instanceof MenuHolder holder ? holder.kind() : null;
        GuiLayout layout = kind == null ? (inventory.getSize() == 54 ? GuiLayout.LIST_54 : GuiLayout.DIALOG_27)
                : GuiLayout.forMenu(kind, inventory.getSize());
        if (layout.size() != inventory.getSize()) {
            layout = inventory.getSize() == 54 ? GuiLayout.LIST_54 : GuiLayout.DIALOG_27;
        }
        GuiTheme theme = GuiTheme.from(menus);
        for (int slot = 0; slot < inventory.getSize(); slot++) inventory.setItem(slot, theme.backgroundCopy());
        for (int slot : layout.frameSlots()) inventory.setItem(slot, theme.frameCopy());
        for (int slot : layout.separatorSlots()) inventory.setItem(slot, theme.separatorCopy());
        inventory.setItem(layout.headerSlot(), theme.accentCopy());

        // Content/input areas are intentionally empty. Chrome must never become canonical input.
        for (int slot : layout.contentSlots()) inventory.setItem(slot, null);
        if (inventory.getHolder() instanceof MenuHolder holder) {
            for (int slot : protectedInputSlots(holder.kind(), menus)) inventory.setItem(slot, null);
        }
    }

    static Set<Integer> protectedInputSlots(MenuHolder.Kind kind, MenuConfig menus) {
        LinkedHashSet<Integer> slots = new LinkedHashSet<>();
        if (kind == MenuHolder.Kind.KEY_TEMPLATE && menus.contains("key-template.input-placeholder.slot")) {
            slots.add(menus.slot("key-template.input-placeholder"));
        }
        if (kind == MenuHolder.Kind.REWARD_BUILDER) {
            if (menus.contains("reward-builder.item-slots")) slots.addAll(menus.slots("reward-builder.item-slots"));
            if (menus.contains("reward-builder.input-placeholder.slot")) slots.add(menus.slot("reward-builder.input-placeholder"));
        }
        if (kind == MenuHolder.Kind.REWARDS && menus.contains("reward-pool.reward-slots")) {
            slots.addAll(menus.slots("reward-pool.reward-slots"));
        }
        return Set.copyOf(slots);
    }
}
''')

write("src/main/java/com/antondev/crates/gui/GuiItemFactory.java", r'''
package com.antondev.crates.gui;

import com.antondev.crates.config.Text;
import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

/** Shared, presentation-only state cards and buttons. */
public final class GuiItemFactory {
    private GuiItemFactory() {}

    public static ItemStack loading(String detail) {
        return item(Material.CLOCK, "<aqua>Loading…</aqua>", List.of("<gray>" + detail + "</gray>"));
    }

    public static ItemStack empty(String reason, String nextAction) {
        List<String> lore = new ArrayList<>();
        lore.add("<gray>" + reason + "</gray>");
        if (nextAction != null && !nextAction.isBlank()) {
            lore.add(""); lore.add("<aqua>" + nextAction + "</aqua>");
        }
        return item(Material.PAPER, "<gray>Nothing here yet</gray>", lore);
    }

    public static ItemStack error(String reason) {
        return item(Material.BARRIER, "<red>Unavailable</red>",
                List.of("<gray>" + reason + "</gray>", "<dark_gray>No state changed.</dark_gray>"));
    }

    public static ItemStack disabled(String reason) {
        return item(Material.GRAY_DYE, "<gray>Disabled</gray>", List.of("<gray>" + reason + "</gray>"));
    }

    public static ItemStack item(Material material, String name, List<String> lore) {
        ItemStack item = new ItemStack(material);
        item.editMeta(meta -> {
            meta.displayName(Text.parse(name).decoration(TextDecoration.ITALIC, false));
            meta.lore(lore.stream().map(Text::parse)
                    .map(line -> line.decoration(TextDecoration.ITALIC, false)).toList());
        });
        return item;
    }
}
''')

write("src/main/java/com/antondev/crates/config/GuiConfigMigrator.java", r'''
package com.antondev.crates.config;

import java.io.File;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.List;
import org.bukkit.configuration.file.YamlConfiguration;

/** One-time structural migration for pre-6.5 menus.yml files. */
public final class GuiConfigMigrator {
    public static final int SCHEMA = 2;
    private static final List<Integer> LIST_CONTENT = List.of(
            10, 11, 12, 13, 14, 15, 16,
            19, 20, 21, 22, 23, 24, 25,
            28, 29, 30, 31, 32, 33, 34,
            37, 38, 39, 40, 41, 42, 43);

    private GuiConfigMigrator() {}

    public static YamlConfiguration loadAndMigrate(File file) {
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        boolean legacy = yaml.getInt("schema-version", 0) < SCHEMA;
        boolean changed = installTheme(yaml);
        if (legacy) {
            backup(file);
            normalizeStructure(yaml);
            yaml.set("schema-version", SCHEMA);
            changed = true;
        }
        if (changed) saveAtomically(file, yaml);
        return yaml;
    }

    private static boolean installTheme(YamlConfiguration yaml) {
        boolean[] changed = {false};
        setDefault(yaml, changed, "theme.background.material", "GRAY_STAINED_GLASS_PANE");
        setDefault(yaml, changed, "theme.frame.material", "BLACK_STAINED_GLASS_PANE");
        setDefault(yaml, changed, "theme.separator.material", "BLACK_STAINED_GLASS_PANE");
        setDefault(yaml, changed, "theme.section.material", "LIGHT_GRAY_STAINED_GLASS_PANE");
        setDefault(yaml, changed, "theme.accent.material", "CYAN_STAINED_GLASS_PANE");
        setDefault(yaml, changed, "theme.success.material", "LIME_STAINED_GLASS_PANE");
        setDefault(yaml, changed, "theme.warning.material", "YELLOW_STAINED_GLASS_PANE");
        setDefault(yaml, changed, "theme.danger.material", "RED_STAINED_GLASS_PANE");
        for (String key : List.of("background", "frame", "separator", "section", "accent", "success", "warning", "danger")) {
            setDefault(yaml, changed, "theme." + key + ".name", " ");
            setDefault(yaml, changed, "theme." + key + ".lore", List.of());
        }
        setDefault(yaml, changed, "layouts.list-54.content-slots", LIST_CONTENT);
        setDefault(yaml, changed, "layouts.list-54.footer-slots", List.of(45,46,47,48,49,50,51,52,53));
        setDefault(yaml, changed, "layouts.dialog-27.confirm-slot", 11);
        setDefault(yaml, changed, "layouts.dialog-27.subject-slot", 13);
        setDefault(yaml, changed, "layouts.dialog-27.cancel-slot", 15);
        return changed[0];
    }

    private static void normalizeStructure(YamlConfiguration yaml) {
        for (String path : List.of("claims.claim-slots", "preview.reward-slots", "crate-list.entry-slots",
                "key-list.key-slots", "key-select.key-slots", "reward-pool.reward-slots",
                "milestone-list.milestone-slots", "milestone-reward-select.reward-slots",
                "locations.location-slots", "global-rewards.reward-slots", "wand-select.crate-slots")) {
            if (yaml.contains(path)) yaml.set(path, LIST_CONTENT);
        }
        for (String root : List.of("claims", "preview", "crate-list", "key-list", "key-select", "reward-pool",
                "milestone-list", "milestone-reward-select", "locations", "global-rewards", "wand-select")) {
            if (yaml.contains(root + ".size")) yaml.set(root + ".size", 54);
        }
        slot(yaml, "claims.previous", 45); slot(yaml, "claims.back", 48); slot(yaml, "claims.guide", 51);
        slot(yaml, "claims.close", 52); slot(yaml, "claims.next", 53);
        slot(yaml, "preview.previous", 45); slot(yaml, "preview.back", 48); slot(yaml, "preview.open", 49);
        slot(yaml, "preview.next", 53);
        slot(yaml, "crate-list.previous", 45); slot(yaml, "crate-list.search", 46); slot(yaml, "crate-list.create", 47);
        slot(yaml, "crate-list.back", 48); slot(yaml, "crate-list.status", 51); slot(yaml, "crate-list.close", 52);
        slot(yaml, "crate-list.next", 53);
        slot(yaml, "key-list.previous", 45); slot(yaml, "key-list.create", 47); slot(yaml, "key-list.back", 48);
        slot(yaml, "key-list.sync", 50); slot(yaml, "key-list.status", 51); slot(yaml, "key-list.close", 52);
        slot(yaml, "key-list.next", 53);
        slot(yaml, "key-select.previous", 45); slot(yaml, "key-select.back", 48); slot(yaml, "key-select.status", 51);
        slot(yaml, "key-select.close", 52); slot(yaml, "key-select.next", 53);
        slot(yaml, "reward-pool.previous", 45); slot(yaml, "reward-pool.search", 46); slot(yaml, "reward-pool.add-special", 47);
        slot(yaml, "reward-pool.back", 48); slot(yaml, "reward-pool.done", 49); slot(yaml, "reward-pool.balance", 50);
        slot(yaml, "reward-pool.status", 51); slot(yaml, "reward-pool.preview", 52); slot(yaml, "reward-pool.next", 53);
        for (String root : List.of("milestone-list", "milestone-reward-select", "locations", "global-rewards", "wand-select")) {
            slot(yaml, root + ".previous", 45); slot(yaml, root + ".back", 48); slot(yaml, root + ".status", 51);
            slot(yaml, root + ".close", 52); slot(yaml, root + ".next", 53);
        }
        slot(yaml, "milestone-list.create", 47); slot(yaml, "locations.wand", 47);
        for (String root : List.of("selective-confirm", "confirm-delete", "confirm-milestone-delete",
                "confirm-unlink", "confirm-crate-delete", "confirm-key-delete", "confirm-takeover")) {
            slot(yaml, root + ".confirm", 11); slot(yaml, root + ".cancel", 15);
        }
        // Crate Studio columns avoid the panel separator lanes (12/14, 21/23, 30/32, 39/41).
        slot(yaml, "editor.rename", 10); slot(yaml, "editor.description", 11); slot(yaml, "editor.order", 19);
        slot(yaml, "editor.display", 20); slot(yaml, "editor.disable", 28);
        slot(yaml, "editor.key", 13); slot(yaml, "editor.opening", 22); slot(yaml, "editor.access", 31);
        slot(yaml, "editor.rerolls", 40); slot(yaml, "editor.rewards", 15); slot(yaml, "editor.create-reward", 16);
        slot(yaml, "editor.wand", 24); slot(yaml, "editor.milestones", 25); slot(yaml, "editor.preview", 33);
        slot(yaml, "editor.draft-status", 45); slot(yaml, "editor.takeover", 46); slot(yaml, "editor.undo", 47);
        slot(yaml, "editor.back", 48); slot(yaml, "editor.publish", 49); slot(yaml, "editor.archive", 50);
        slot(yaml, "editor.clone", 51); slot(yaml, "editor.delete", 53);
        // Reward Builder keeps its seven exact-item capture cells, but lifecycle actions move to a stable footer.
        slot(yaml, "reward-builder.confirm", 49); slot(yaml, "reward-builder.cancel", 52);
    }

    private static void slot(YamlConfiguration yaml, String path, int value) {
        if (yaml.contains(path + ".slot")) yaml.set(path + ".slot", value);
    }

    private static void setDefault(YamlConfiguration yaml, boolean[] changed, String path, Object value) {
        if (!yaml.contains(path)) { yaml.set(path, value); changed[0] = true; }
    }

    private static void backup(File file) {
        if (!file.isFile()) return;
        File backup = new File(file.getParentFile(), "menus.yml.pre-6.5.bak");
        if (backup.exists()) return;
        try {
            Files.copy(file.toPath(), backup.toPath(), StandardCopyOption.COPY_ATTRIBUTES);
        } catch (IOException error) {
            throw new IllegalStateException("Could not back up pre-6.5 menus.yml", error);
        }
    }

    private static void saveAtomically(File file, YamlConfiguration yaml) {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IllegalStateException("Could not create menu configuration directory");
        }
        File temporary = new File(parent, file.getName() + ".6.5.tmp");
        try {
            yaml.save(temporary);
            try {
                Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException error) {
            try { Files.deleteIfExists(temporary.toPath()); } catch (IOException ignored) { }
            throw new IllegalStateException("Could not persist the 6.5 menus.yml migration", error);
        }
    }
}
''')

# Centralize every known legacy filler renderer. Same-package classes need no import.
for path in (SRC / "gui").rglob("*.java"):
    text = path.read_text(encoding="utf-8")
    text = re.sub(
        r'ItemStack filler = plugin\.menusConfig\(\)\.item\("filler"\);\s*for \(int slot = 0; slot < inventory\.getSize\(\); slot\+\+\) inventory\.setItem\(slot, filler\);',
        'GuiChromeRenderer.render(inventory, plugin.menusConfig());', text)
    text = re.sub(
        r'private static void fill\(Inventory inventory\) \{\s*ItemStack filler = item\(Material\.BLACK_STAINED_GLASS_PANE, " ", List\.of\(\)\);\s*for \(int slot = 0; slot < inventory\.getSize\(\); slot\+\+\) inventory\.setItem\(slot, filler\);\s*\}',
        'private void fill(Inventory inventory) {\n        GuiChromeRenderer.render(inventory, plugin.menusConfig());\n    }', text)
    path.write_text(text, encoding="utf-8")

# Player services used an independent gray filler and then punched random footer holes.
for rel in [
    "src/main/java/com/antondev/crates/gui/player/PlayerCrateMenuService.java",
    "src/main/java/com/antondev/crates/gui/player/PlayerKeyMenuService.java",
]:
    p = ROOT / rel
    text = p.read_text(encoding="utf-8")
    pattern = re.compile(r'''ItemStack filler = item\(Material\.GRAY_STAINED_GLASS_PANE, Component\.empty\(\), List\.of\(\)\);\s*for \(int slot = 0; slot < inventory\.getSize\(\); slot\+\+\) inventory\.setItem\(slot, filler\);\s*for \(int slot : PlayerCrateLayout\.contentSlots\(\)\) inventory\.setItem\(slot, null\);\s*for \(int slot = PlayerCrateLayout\.PREVIOUS; slot <= PlayerCrateLayout\.NEXT; slot\+\+\) inventory\.setItem\(slot, null\);''')
    text, count = pattern.subn('com.antondev.crates.gui.GuiChromeRenderer.render(inventory, plugin.menusConfig());', text)
    if count != 1: raise SystemExit(f"player filler transform failed for {rel}: {count}")
    p.write_text(text, encoding="utf-8")

p = ROOT / "src/main/java/com/antondev/crates/gui/player/PlayerHistoryMenuService.java"
text = p.read_text(encoding="utf-8")
pattern = re.compile(r'''ItemStack filler = item\(Material\.GRAY_STAINED_GLASS_PANE, Component\.empty\(\), List\.of\(\)\);\s*for \(int slot = 0; slot < inventory\.getSize\(\); slot\+\+\) inventory\.setItem\(slot, filler\);\s*clearContent\(inventory\);\s*for \(int slot = PlayerCrateLayout\.PREVIOUS; slot <= PlayerCrateLayout\.NEXT; slot\+\+\) inventory\.setItem\(slot, null\);''')
text, count = pattern.subn('com.antondev.crates.gui.GuiChromeRenderer.render(inventory, plugin.menusConfig());\n        clearContent(inventory);', text)
if count != 1: raise SystemExit(f"history filler transform failed: {count}")
p.write_text(text, encoding="utf-8")

# Player-facing product title grammar.
for rel in [
    "src/main/java/com/antondev/crates/gui/player/PlayerCrateMenuService.java",
    "src/main/java/com/antondev/crates/gui/player/PlayerKeyMenuService.java",
    "src/main/java/com/antondev/crates/gui/player/PlayerHistoryMenuService.java",
]:
    p = ROOT / rel
    text = p.read_text(encoding="utf-8")
    text = text.replace("<gradient:#CAD5E5:#FFFFFF><bold>Crate Hall</bold></gradient>",
                        "<gradient:#DDE5F0:#A3BEDF><bold>PLEXON CRATES</bold></gradient> <dark_gray>•</dark_gray> <gray>Crate Hall</gray>")
    text = text.replace("<gradient:#FFD98A:#FFFFFF><bold>My Keys</bold></gradient>",
                        "<gradient:#DDE5F0:#A3BEDF><bold>PLEXON CRATES</bold></gradient> <dark_gray>•</dark_gray> <gray>My Keys</gray>")
    text = text.replace("<gradient:#CAD5E5:#FFFFFF><bold>Opening History</bold></gradient>",
                        "<gradient:#DDE5F0:#A3BEDF><bold>PLEXON CRATES</bold></gradient> <dark_gray>•</dark_gray> <gray>Opening History</gray>")
    p.write_text(text, encoding="utf-8")

# Evolve, rather than duplicate, the established player layout constants.
p = ROOT / "src/main/java/com/antondev/crates/gui/player/PlayerCrateLayout.java"
text = p.read_text(encoding="utf-8")
text = text.replace("public static final int PREVIOUS = 45;\n    public static final int CONTEXT = 46;\n    public static final int PAYMENT = 47;",
                    "public static final int PREVIOUS = 45;\n    public static final int SEARCH = 46;\n    /** Compatibility alias: existing player code uses CONTEXT for refresh/search. */\n    public static final int CONTEXT = SEARCH;\n    public static final int UTILITY = 47;\n    /** Compatibility alias: My Keys/payment context occupies the utility slot. */\n    public static final int PAYMENT = UTILITY;")
p.write_text(text, encoding="utf-8")

# Upgrade the bundled menu resource itself to schema 2 while preserving names/lore/material customizability.
menus_path = ROOT / "src/main/resources/menus.yml"
data = yaml.safe_load(menus_path.read_text(encoding="utf-8")) or {}
LIST = [10,11,12,13,14,15,16,19,20,21,22,23,24,25,28,29,30,31,32,33,34,37,38,39,40,41,42,43]
theme = {
    "background": {"material":"GRAY_STAINED_GLASS_PANE","name":" ","lore":[]},
    "frame": {"material":"BLACK_STAINED_GLASS_PANE","name":" ","lore":[]},
    "separator": {"material":"BLACK_STAINED_GLASS_PANE","name":" ","lore":[]},
    "section": {"material":"LIGHT_GRAY_STAINED_GLASS_PANE","name":" ","lore":[]},
    "accent": {"material":"CYAN_STAINED_GLASS_PANE","name":" ","lore":[]},
    "success": {"material":"LIME_STAINED_GLASS_PANE","name":" ","lore":[]},
    "warning": {"material":"YELLOW_STAINED_GLASS_PANE","name":" ","lore":[]},
    "danger": {"material":"RED_STAINED_GLASS_PANE","name":" ","lore":[]},
}
layouts = {"list-54":{"content-slots":LIST,"footer-slots":[45,46,47,48,49,50,51,52,53]},
           "dialog-27":{"confirm-slot":11,"subject-slot":13,"cancel-slot":15}}
# Reuse the same structural migration logic in source defaults.
def set_slot(root, key, value):
    sec = data.get(root)
    if isinstance(sec, dict) and isinstance(sec.get(key), dict) and "slot" in sec[key]: sec[key]["slot"] = value
for root, content_key in [("claims","claim-slots"),("preview","reward-slots"),("crate-list","entry-slots"),
                          ("key-list","key-slots"),("key-select","key-slots"),("reward-pool","reward-slots"),
                          ("milestone-list","milestone-slots"),("milestone-reward-select","reward-slots"),
                          ("locations","location-slots"),("global-rewards","reward-slots"),("wand-select","crate-slots")]:
    if root in data:
        data[root]["size"] = 54
        if content_key in data[root]: data[root][content_key] = LIST.copy()
for root, mapping in {
    "claims":{"previous":45,"back":48,"guide":51,"close":52,"next":53},
    "preview":{"previous":45,"back":48,"open":49,"next":53},
    "crate-list":{"previous":45,"search":46,"create":47,"back":48,"status":51,"close":52,"next":53},
    "key-list":{"previous":45,"create":47,"back":48,"sync":50,"status":51,"close":52,"next":53},
    "key-select":{"previous":45,"back":48,"status":51,"close":52,"next":53},
    "reward-pool":{"previous":45,"search":46,"add-special":47,"back":48,"done":49,"balance":50,"status":51,"preview":52,"next":53},
    "milestone-list":{"previous":45,"create":47,"back":48,"status":51,"close":52,"next":53},
    "milestone-reward-select":{"previous":45,"back":48,"status":51,"close":52,"next":53},
    "locations":{"previous":45,"wand":47,"back":48,"status":51,"close":52,"next":53},
    "global-rewards":{"previous":45,"back":48,"status":51,"close":52,"next":53},
    "wand-select":{"previous":45,"back":48,"status":51,"close":52,"next":53},
}.items():
    for key, value in mapping.items(): set_slot(root,key,value)
for root in ["selective-confirm","confirm-delete","confirm-milestone-delete","confirm-unlink","confirm-crate-delete","confirm-key-delete","confirm-takeover"]:
    set_slot(root,"confirm",11); set_slot(root,"cancel",15)
for key, value in {"rename":10,"description":11,"order":19,"display":20,"disable":28,"key":13,"opening":22,
                   "access":31,"rerolls":40,"rewards":15,"create-reward":16,"wand":24,"milestones":25,"preview":33,
                   "draft-status":45,"takeover":46,"undo":47,"back":48,"publish":49,"archive":50,"clone":51,"delete":53}.items():
    set_slot("editor",key,value)
set_slot("reward-builder","confirm",49); set_slot("reward-builder","cancel",52)
# Insert schema/theme/layout before existing menu sections.
ordered = {"schema-version":2,"theme":theme,"layouts":layouts}
for key, value in data.items():
    if key not in ordered: ordered[key] = value
menus_path.write_text("# PlexonCrates 6.5 GUI design system. Names/lore/materials remain administrator-customizable.\n" +
                      yaml.safe_dump(ordered, sort_keys=False, allow_unicode=True, width=140), encoding="utf-8")

# Tests for the architecture and migration contract.
write("src/test/java/com/antondev/crates/gui/GuiLayoutArchitectureTest.java", r'''
package com.antondev.crates.gui;

import static org.junit.jupiter.api.Assertions.*;
import java.util.HashSet;
import org.junit.jupiter.api.Test;

class GuiLayoutArchitectureTest {
    @Test void listGridAndFooterAreCollisionFree() {
        assertEquals(28, GuiLayout.LIST_54.contentSlots().size());
        assertEquals(9, GuiNavigation.FOOTER.size());
        var collision = new HashSet<>(GuiLayout.LIST_54.contentSlots());
        collision.retainAll(GuiLayout.LIST_54.frameSlots());
        assertTrue(collision.isEmpty());
        assertEquals(45, GuiNavigation.PREVIOUS);
        assertEquals(53, GuiNavigation.NEXT);
        assertEquals(48, GuiNavigation.BACK);
        assertEquals(52, GuiNavigation.CLOSE);
    }

    @Test void panelColumnsNeverOverlapDeclaredContent() {
        var collision = new HashSet<>(GuiLayout.PANEL_54.contentSlots());
        collision.retainAll(GuiLayout.PANEL_54.separatorSlots());
        assertTrue(collision.isEmpty());
        assertEquals(54, GuiLayout.PANEL_54.size());
    }

    @Test void dialogsKeepSymmetricDecisionSlots() {
        assertTrue(GuiLayout.DIALOG_27.contentSlots().containsAll(java.util.List.of(11, 13, 15)));
        assertEquals(27, GuiLayout.DIALOG_27.size());
    }
}
''')

write("src/test/java/com/antondev/crates/config/GuiConfigMigrationTest.java", r'''
package com.antondev.crates.config;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GuiConfigMigrationTest {
    @TempDir Path temp;

    @Test void pre65ConfigIsBackedUpAndMigratedWithoutTouchingCustomText() throws Exception {
        Path file = temp.resolve("menus.yml");
        Files.writeString(file, "filler:\n  material: BLUE_STAINED_GLASS_PANE\n  name: 'Custom shell'\nclaims:\n  size: 54\n  claim-slots: [10]\n  previous: {slot: 47}\n  back: {slot: 48}\n  guide: {slot: 49}\n  next: {slot: 51}\n  close: {slot: 53}\n");
        YamlConfiguration migrated = GuiConfigMigrator.loadAndMigrate(file.toFile());
        assertEquals(2, migrated.getInt("schema-version"));
        assertTrue(Files.exists(temp.resolve("menus.yml.pre-6.5.bak")));
        assertEquals("Custom shell", migrated.getString("filler.name"));
        assertEquals("GRAY_STAINED_GLASS_PANE", migrated.getString("theme.background.material"));
        assertEquals(45, migrated.getInt("claims.previous.slot"));
        assertEquals(52, migrated.getInt("claims.close.slot"));
        assertEquals(28, migrated.getIntegerList("claims.claim-slots").size());
    }

    @Test void schema2DoesNotCreateAnotherBackup() throws Exception {
        Path file = temp.resolve("menus2.yml");
        Files.writeString(file, "schema-version: 2\ntheme:\n  background:\n    material: GRAY_STAINED_GLASS_PANE\n");
        GuiConfigMigrator.loadAndMigrate(file.toFile());
        assertFalse(Files.exists(temp.resolve("menus.yml.pre-6.5.bak")));
    }
}
''')

write("src/test/java/com/antondev/crates/gui/GuiDesignSystemArchitectureTest.java", r'''
package com.antondev.crates.gui;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class GuiDesignSystemArchitectureTest {
    @Test void sharedDesignSystemExistsAndLegacyPlayerFillersAreGone() throws Exception {
        Path root = Path.of("src/main/java/com/antondev/crates/gui");
        assertTrue(Files.exists(root.resolve("GuiTheme.java")));
        assertTrue(Files.exists(root.resolve("GuiChromeRenderer.java")));
        assertTrue(Files.exists(root.resolve("GuiLayout.java")));
        assertTrue(Files.exists(root.resolve("GuiItemFactory.java")));
        for (String file : java.util.List.of(
                "player/PlayerCrateMenuService.java", "player/PlayerKeyMenuService.java", "player/PlayerHistoryMenuService.java")) {
            String source = Files.readString(root.resolve(file));
            assertFalse(source.contains("Material.GRAY_STAINED_GLASS_PANE"), file + " must use GuiTheme");
            assertTrue(source.contains("GuiChromeRenderer.render"), file + " must use shared chrome");
        }
    }

    @Test void centralRouterRemainsTheOnlyRegisteredMenuAuthority() throws Exception {
        String plugin = Files.readString(Path.of("src/main/java/com/antondev/crates/PlexonCrates.java"));
        assertTrue(plugin.contains("new CrateMenuEventRouter"));
        assertFalse(plugin.contains("registerEvents(menus"));
        assertFalse(plugin.contains("registerEvents(adminMenus"));
    }
}
''')

write("src/test/java/com/antondev/crates/gui/GuiNavigationConsistencyArchitectureTest.java", r'''
package com.antondev.crates.gui;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class GuiNavigationConsistencyArchitectureTest {
    @Test void footerUses65Contract() {
        assertArrayEquals(new Integer[]{45,46,47,48,49,50,51,52,53}, GuiNavigation.FOOTER.toArray(Integer[]::new));
    }
}
''')

write("src/test/java/com/antondev/crates/gui/GuiExactInputSafetyTest.java", r'''
package com.antondev.crates.gui;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class GuiExactInputSafetyTest {
    @Test void chromeDoesNotBindActionsOrCanonicalizeItems() throws Exception {
        String source = Files.readString(Path.of("src/main/java/com/antondev/crates/gui/GuiChromeRenderer.java"));
        assertFalse(source.contains("holder.bind("));
        assertFalse(source.contains("PersistentDataContainer"));
        assertTrue(source.contains("protectedInputSlots"));
        assertTrue(source.contains("inventory.setItem(slot, null)"));
    }
}
''')

# Documentation and release notes.
write("docs/GUI_6_5.md", r'''
# PlexonCrates 6.5 GUI design system

6.5 introduces one shared presentation layer for inventory menus: `GuiTheme`, `GuiLayout`, `GuiChromeRenderer`, `GuiItemFactory`, and `GuiNavigation`.

- LIST_54 keeps the 28-card grid at 10–16, 19–25, 28–34 and 37–43.
- Footer semantics are fixed at 45 Previous, 46 Search/Refresh, 47 Context, 48 Back, 49 Primary, 50 Secondary, 51 Status, 52 Close/Cancel and 53 Next.
- PANEL_54 uses dark frame chrome plus structural separator lanes.
- DIALOG_27 keeps confirm/subject/cancel at 11/13/15.
- Decoration never binds actions and declared content/exact-input regions are cleared after chrome rendering.
- Player, admin, Test Lab, and profile surfaces render through the same theme rather than hard-coded glass fillers.

The design layer is presentation-only. Opening transactions, payment, selection, exact-item bytes, claims, persistence, draft leases and journal recovery remain outside it.
''')

write("docs/MIGRATION_6_5.md", r'''
# PlexonCrates 6.5 menu migration

`menus.yml` schema version is now `2`. On the first load of a pre-6.5 file PlexonCrates:

1. creates `menus.yml.pre-6.5.bak` before writing the migrated file;
2. installs the semantic theme and reusable layout metadata;
3. migrates structural list/footer slots to the 6.5 layout;
4. preserves administrator-customized item names, lore and materials wherever the redesign does not require a structural reset;
5. writes schema version 2 only after backup and migration succeed.

The migration does not read or mutate crate definitions, keys, rewards, SQLite state, exact item snapshots, claims, statistics or opening journals.
''')

write("docs/REVAMP_6_5_IMPLEMENTATION_STATUS.md", r'''
# PlexonCrates 6.5 implementation status

## Design-system foundation
- Shared semantic glass theme: implemented.
- Shared LIST_54 / PANEL_54 / DIALOG_27 / OPENING_27 geometry: implemented.
- Shared chrome renderer: implemented.
- Stable footer/navigation contract: implemented.
- Shared loading/empty/error/disabled factory: implemented.

## Safety
- Central `CrateMenuEventRouter` authority preserved.
- Decorative chrome binds no actions.
- Exact reward/key capture regions are cleared from decoration before functional rendering.
- No transaction, reward-selection, payment, journal, claim, persistence, or exact-byte authority moved into GUI code.
- No GUI repeating scheduler was introduced.

## Migration
- `menus.yml` schema 2 and pre-6.5 backup: implemented.
- Structural footer/list migration: implemented.
- Safe text/material preservation: implemented.

## Verification
Canonical GitHub Actions is authoritative for compile, full regression tests, distribution checks, version parity and artifact provenance.
''')

write("releases/6.5.0.md", r'''
# PlexonCrates 6.5.0

PlexonCrates 6.5.0 is the full GUI design-system release.

## Full GUI redesign
- Unified PlexonCrates dark/gray glass shell with cyan informational accents and semantic success/warning/danger states.
- Shared 54-slot list grid, panel grouping, footer language and 27-slot confirmation geometry.
- Player Hall, preview, My Keys, compatible crates, history and pending rewards inherit one chrome system.
- Admin dashboard/editor/list/reward/key/milestone/system surfaces inherit the same theme and stable navigation structure.
- Test Lab and animation-profile editors no longer own a separate hard-coded glass filler style.
- Loading, empty, error and disabled presentation primitives are centralized.

## Architecture
- Added `GuiTheme`, `GuiLayout`, `GuiChromeRenderer`, `GuiItemFactory`, and `GuiNavigation`.
- `CrateMenuEventRouter` remains the single registered inventory routing authority.
- Opening/payment/reward-selection/persistence/exact-item/claim authority is unchanged.
- No per-menu or per-player repeating GUI task was introduced.

## Migration
- `menus.yml` schema version 2.
- First pre-6.5 load creates `menus.yml.pre-6.5.bak` before structural migration.
- Custom names/lore/materials are preserved where structurally safe.

## Verification
Release artifacts are produced only after Java 25 canonical CI, the full Maven regression suite, distribution verification and exact SHA-256/provenance generation pass. No fresh production-host runtime certification is claimed by this GUI-only release unless separately recorded.
''')

# Changelog entry without rewriting historical release evidence.
p = ROOT / "CHANGELOG.md"
if p.exists():
    old = p.read_text(encoding="utf-8")
    entry = "# Changelog\n\n## 6.5.0\n\n- Unified all inventory presentation behind the 6.5 GUI design system.\n- Added versioned menus.yml schema migration with pre-6.5 backup.\n- Standardized list/footer/dialog geometry and removed duplicated hard-coded player/Test Lab filler rendering.\n- Preserved transaction, exact-item, persistence, claim and central-router boundaries.\n\n"
    if old.startswith("# Changelog\n"):
        old = old[len("# Changelog\n"):].lstrip("\n")
    p.write_text(entry + old, encoding="utf-8")

# Guard against accidental source version drift.
assert "<version>6.5.0</version>" in (ROOT / "pom.xml").read_text(encoding="utf-8")
print("PlexonCrates 6.5 GUI design-system transform applied")
