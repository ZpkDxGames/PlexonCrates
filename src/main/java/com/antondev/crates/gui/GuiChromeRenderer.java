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
