package com.plexoncrates.listener;

import com.plexoncrates.manager.GUIManager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;

public final class InventoryListener implements Listener {
    private final GUIManager gui;
    public InventoryListener(GUIManager gui){this.gui=gui;}
    @EventHandler(ignoreCancelled=true) public void onClick(InventoryClickEvent event){gui.handleClick(event);}
    @EventHandler(ignoreCancelled=true) public void onDrag(InventoryDragEvent event){gui.handleDrag(event);}
    @EventHandler public void onClose(InventoryCloseEvent event){gui.handleClose(event);}
}
