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
