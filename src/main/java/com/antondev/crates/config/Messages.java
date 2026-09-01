package com.antondev.crates.config;

import java.io.File;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;

public final class Messages {
    private final YamlConfiguration yaml;
    private final String prefix;

    private Messages(YamlConfiguration yaml) {
        this.yaml = yaml;
        this.prefix = yaml.getString("prefix", "");
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
