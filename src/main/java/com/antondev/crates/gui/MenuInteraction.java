package com.antondev.crates.gui;

import java.util.Set;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.ItemStack;

/**
 * Immutable inventory-event snapshots used after Bukkit finishes dispatching the
 * original click/drag event. Mutable ItemStacks are cloned at capture and access.
 */
final class MenuInteraction {
    private MenuInteraction() { }

    static final class Click {
        private final int rawSlot;
        private final boolean clickedTop;
        private final boolean clickedBottom;
        private final boolean shiftClick;
        private final boolean leftClick;
        private final boolean rightClick;
        private final InventoryAction action;
        private final ItemStack cursor;
        private final ItemStack currentItem;

        private Click(int rawSlot, boolean clickedTop, boolean clickedBottom,
                      boolean shiftClick, boolean leftClick, boolean rightClick,
                      InventoryAction action, ItemStack cursor, ItemStack currentItem) {
            this.rawSlot = rawSlot;
            this.clickedTop = clickedTop;
            this.clickedBottom = clickedBottom;
            this.shiftClick = shiftClick;
            this.leftClick = leftClick;
            this.rightClick = rightClick;
            this.action = action;
            this.cursor = copy(cursor);
            this.currentItem = copy(currentItem);
        }

        static Click capture(InventoryClickEvent event) {
            boolean top = event.getClickedInventory() == event.getView().getTopInventory();
            boolean bottom = event.getClickedInventory() == event.getView().getBottomInventory();
            ItemStack current = event.getCurrentItem();
            if (current == null && bottom && event.getRawSlot() >= 0) {
                try {
                    current = event.getView().getBottomInventory()
                            .getItem(event.getView().convertSlot(event.getRawSlot()));
                } catch (RuntimeException ignored) {
                    // A cancelled click outside a valid bottom slot has no item to capture.
                }
            }
            return new Click(event.getRawSlot(), top, bottom, event.isShiftClick(),
                    event.isLeftClick(), event.isRightClick(), event.getAction(),
                    event.getCursor(), current);
        }

        int getRawSlot() { return rawSlot; }
        boolean clickedTop() { return clickedTop; }
        boolean clickedBottom() { return clickedBottom; }
        boolean isShiftClick() { return shiftClick; }
        boolean isLeftClick() { return leftClick; }
        boolean isRightClick() { return rightClick; }
        InventoryAction getAction() { return action; }
        ItemStack getCursor() { return copy(cursor); }
        ItemStack getCurrentItem() { return copy(currentItem); }

        /**
         * Legacy-shaped accessor for one old reward-pool fallback. This is not a
         * Bukkit InventoryView; it exposes only the item captured during the event.
         */
        CapturedView getView() { return new CapturedView(currentItem); }
    }

    static final class Drag {
        private final Set<Integer> rawSlots;
        private final ItemStack oldCursor;

        private Drag(Set<Integer> rawSlots, ItemStack oldCursor) {
            this.rawSlots = Set.copyOf(rawSlots);
            this.oldCursor = copy(oldCursor);
        }

        static Drag capture(InventoryDragEvent event) {
            return new Drag(event.getRawSlots(), event.getOldCursor());
        }

        Set<Integer> getRawSlots() { return rawSlots; }
        ItemStack getOldCursor() { return copy(oldCursor); }
    }

    static final class CapturedView {
        private final ItemStack currentItem;

        private CapturedView(ItemStack currentItem) {
            this.currentItem = copy(currentItem);
        }

        CapturedInventory getBottomInventory() { return new CapturedInventory(currentItem); }
        int convertSlot(int rawSlot) { return rawSlot; }
    }

    static final class CapturedInventory {
        private final ItemStack currentItem;

        private CapturedInventory(ItemStack currentItem) {
            this.currentItem = copy(currentItem);
        }

        ItemStack getItem(int ignoredSlot) { return copy(currentItem); }
    }

    private static ItemStack copy(ItemStack item) {
        return item == null ? null : item.clone();
    }
}
