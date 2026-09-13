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
