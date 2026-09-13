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
