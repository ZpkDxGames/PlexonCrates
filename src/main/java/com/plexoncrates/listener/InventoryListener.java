package com.plexoncrates.listener;

import com.plexoncrates.core.PlexonCrates;
import com.plexoncrates.manager.GUIManager;
import java.util.logging.Level;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;

/** Defensive GUI event boundary: malformed/stale inventories must never escape into Paper's event loop. */
public final class InventoryListener implements Listener {
    private final PlexonCrates plugin;
    private final GUIManager gui;

    public InventoryListener(PlexonCrates plugin, GUIManager gui) {
        this.plugin = plugin;
        this.gui = gui;
    }

    @EventHandler(ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        try {
            gui.handleClick(event);
        } catch (Throwable error) {
            event.setCancelled(true);
            report("inventory click", event.getWhoClicked() instanceof Player player ? player : null, error);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onDrag(InventoryDragEvent event) {
        try {
            gui.handleDrag(event);
        } catch (Throwable error) {
            event.setCancelled(true);
            report("inventory drag", event.getWhoClicked() instanceof Player player ? player : null, error);
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        try {
            gui.handleClose(event);
        } catch (Throwable error) {
            report("inventory close", event.getPlayer() instanceof Player player ? player : null, error);
        }
    }

    private void report(String operation, Player player, Throwable error) {
        String actor = player == null ? "unknown player" : player.getName();
        plugin.getLogger().log(Level.SEVERE, "PlexonCrates " + operation + " failed for " + actor, error);
        if (player != null && player.isOnline()) {
            player.sendMessage("§8[§6PlexonCrates§8] §cThat menu action failed safely. Check the console diagnostic.");
        }
    }
}
