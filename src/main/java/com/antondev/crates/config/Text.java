package com.antondev.crates.config;

import java.util.Objects;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;

public final class Text {
    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private Text() {}

    public static Component parse(String input, TagResolver... tags) {
        return MINI.deserialize(Objects.requireNonNullElse(input, ""), tags);
    }

    public static TagResolver value(String name, Object value) {
        return Placeholder.unparsed(name, String.valueOf(value));
    }

    public static TagResolver component(String name, Component value) {
        return Placeholder.component(name, value);
    }
}
