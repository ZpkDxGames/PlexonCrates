package com.antondev.crates.config;

import java.io.File;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;

public final class Messages {
    private static final List<String> REQUIRED = List.of(
            "prefix", "no-permission", "players-only", "disabled", "invalid-world", "invalid-crate",
            "invalid-amount", "no-key", "no-eligible-rewards", "inventory-full", "already-opening",
            "cooldown", "opened", "bulk-opened", "reward-overflow", "reloaded", "config-error",
            "target-required", "location-set", "location-removed", "location-not-found", "location-protected",
            "reward-added", "command-reward-added", "reward-removed", "chance-updated", "hold-item",
            "key-given", "forced-open", "player-not-found", "statistics-saved", "status");
    private static final List<String> REQUIRED_2 = List.of(
            "opening-cancelled", "opening-state-changed", "opening-failed", "database-error",
            "input-cancelled", "input-timeout", "input-invalid");
    private static final List<String> REQUIRED_SYSTEM = List.of(
            "validation-passed", "validation-failed", "backup-started", "backup-created", "backup-failed");
    private static final List<String> REQUIRED_DRAFTS = List.of(
            "draft-loading", "draft-publishing", "draft-read-only", "draft-save-failed", "draft-save-retried",
            "draft-takeover-complete", "draft-undo-complete", "draft-published",
            "draft-publish-failed", "draft-published-mirror-warning", "key-replacement-drafted",
            "key-replacement-awaiting-publish");
    private final YamlConfiguration yaml;
    private final String prefix;

    private Messages(YamlConfiguration yaml) {
        this.yaml = yaml;
        if (!yaml.contains("chance-updated") && yaml.contains("weight-updated")) {
            yaml.set("chance-updated", yaml.get("weight-updated"));
        }
        if (!yaml.contains("invalid-chance") && yaml.contains("invalid-weight")) {
            yaml.set("invalid-chance", yaml.get("invalid-weight"));
        }
        installDraftFallbacks(yaml);
        this.prefix = yaml.getString("prefix", "");
        for (String key : REQUIRED) {
            if (!(yaml.get(key) instanceof String)) throw new IllegalArgumentException("Missing text in messages.yml: " + key);
        }
        for (String key : REQUIRED_2) {
            if (!(yaml.get(key) instanceof String)) throw new IllegalArgumentException("Missing text in messages.yml: " + key);
        }
        for (String key : REQUIRED_SYSTEM) {
            if (!(yaml.get(key) instanceof String)) throw new IllegalArgumentException("Missing text in messages.yml: " + key);
        }
        for (String key : REQUIRED_DRAFTS) {
            if (!(yaml.get(key) instanceof String)) throw new IllegalArgumentException("Missing text in messages.yml: " + key);
        }
        for (String key : yaml.getKeys(false)) {
            Object value = yaml.get(key);
            if (!(value instanceof String)) throw new IllegalArgumentException("messages.yml entry must be text: " + key);
            Text.parse((String) value);
        }
    }

    public static Messages load(File file) {
        return new Messages(YamlConfiguration.loadConfiguration(file));
    }

    public void send(CommandSender sender, String key, TagResolver... tags) {
        String value = yaml.getString(key);
        if (value == null) throw new IllegalArgumentException("Missing message: " + key);
        sender.sendMessage(Text.parse(prefix + value, tags));
    }

    public Component parseRaw(String value, TagResolver... tags) {
        return Text.parse(value, tags);
    }

    public void broadcastRaw(String value, TagResolver... tags) {
        if (!value.isBlank()) Bukkit.getServer().broadcast(parseRaw(value, tags));
    }

    private static void installDraftFallbacks(YamlConfiguration yaml) {
        fallback(yaml, "draft-loading", "<yellow>This durable draft is still loading. Try again in a moment.</yellow>");
        fallback(yaml, "draft-publishing", "<yellow>This draft is already being published.</yellow>");
        fallback(yaml, "draft-read-only", "<yellow>This draft is read-only because <white><owner></white> holds the writable lease.</yellow>");
        fallback(yaml, "draft-save-failed", "<red>The latest draft save failed:</red> <gray><error></gray> <yellow>Use Retry Save before editing again.</yellow>");
        fallback(yaml, "draft-save-retried", "<green>The latest crate draft snapshot was saved successfully.</green>");
        fallback(yaml, "draft-takeover-complete", "<green>You now hold the writable lease for this crate draft.</green>");
        fallback(yaml, "draft-undo-complete", "<green>Restored the previous durable draft revision.</green>");
        fallback(yaml, "draft-published", "<green>Published <crate><green> as revision <white><revision></white>.</green>");
        fallback(yaml, "draft-publish-failed", "<red>Publication failed:</red> <gray><error></gray> <yellow>The previous runtime revision is still active.</yellow>");
        fallback(yaml, "draft-published-mirror-warning", "<yellow>The SQLite publication is active, but its optional YAML mirror could not be updated. Check the server log.</yellow>");
        fallback(yaml, "key-replacement-drafted", "<green>Saved replacement-key drafts for <white><count></white> crate(s).</green> <yellow>Publish those crates before deleting the active key.</yellow>");
        fallback(yaml, "key-replacement-awaiting-publish", "<yellow>This key is still used by a published crate. Publish its pending replacement draft before deleting the key.</yellow>");
    }

    private static void fallback(YamlConfiguration yaml, String key, String value) {
        if (!yaml.contains(key)) yaml.set(key, value);
    }
}
