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
            "reward-added", "command-reward-added", "reward-removed", "weight-updated", "hold-item",
            "key-given", "forced-open", "player-not-found", "statistics-saved", "status");
    private static final List<String> REQUIRED_2 = List.of(
            "opening-cancelled", "opening-state-changed", "opening-failed", "database-error",
            "input-cancelled", "input-timeout", "input-invalid");
    private static final List<String> REQUIRED_SYSTEM = List.of(
            "validation-passed", "validation-failed", "backup-started", "backup-created", "backup-failed");
    private final YamlConfiguration yaml;
    private final String prefix;

    private Messages(YamlConfiguration yaml) {
        this.yaml = yaml;
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
}
