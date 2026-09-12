package com.antondev.crates.gui;

import com.antondev.crates.gui.player.PlayerCrateCommandRouter;
import java.util.Objects;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

/**
 * Single registered authority for PlexonCrates inventory lifecycle events.
 *
 * <p>The legacy/admin menu service and the player product surface remain
 * separate renderers, but no longer compete as independent Bukkit inventory
 * listeners. Routing is based exclusively on the custom MenuHolder kind.</p>
 */
public final class CrateMenuEventRouter implements Listener {
    private final MenuService menus;
    private final PlayerCrateCommandRouter player;

    public CrateMenuEventRouter(MenuService menus, PlayerCrateCommandRouter player) {
        this.menus = Objects.requireNonNull(menus, "menus");
        this.player = Objects.requireNonNull(player, "player");
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void legacyClick(InventoryClickEvent event) {
        MenuHolder holder = holder(event.getView().getTopInventory().getHolder());
        if (holder == null || playerKind(holder.kind())) return;
        menus.click(event);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void playerClick(InventoryClickEvent event) {
        MenuHolder holder = holder(event.getView().getTopInventory().getHolder());
        if (holder == null || !playerKind(holder.kind())) return;
        player.click(event);
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void legacyDrag(InventoryDragEvent event) {
        MenuHolder holder = holder(event.getView().getTopInventory().getHolder());
        if (holder == null || playerKind(holder.kind())) return;
        menus.drag(event);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void playerDrag(InventoryDragEvent event) {
        MenuHolder holder = holder(event.getView().getTopInventory().getHolder());
        if (holder == null || !playerKind(holder.kind())) return;
        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void closeSession(InventoryCloseEvent event) {
        // MenuService owns canonical GuiSessionService cleanup for every holder.
        menus.close(event);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void closePlayerState(InventoryCloseEvent event) {
        MenuHolder holder = holder(event.getInventory().getHolder());
        if (holder == null || !playerKind(holder.kind())) return;
        player.close(event);
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void quitSession(PlayerQuitEvent event) {
        menus.quit(event);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void quitPlayerState(PlayerQuitEvent event) {
        player.quit(event);
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void death(PlayerDeathEvent event) {
        menus.death(event);
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void teleport(PlayerTeleportEvent event) {
        menus.teleport(event);
    }

    private static MenuHolder holder(Object candidate) {
        return candidate instanceof MenuHolder holder ? holder : null;
    }

    private static boolean playerKind(MenuHolder.Kind kind) {
        return switch (kind) {
            case PLAYER_HALL, PLAYER_PREVIEW, PLAYER_QUANTITY, PLAYER_MASS_CONFIRM,
                    PLAYER_SELECTIVE_CONFIRM, PLAYER_PENDING_REWARDS -> true;
            default -> false;
        };
    }
}
