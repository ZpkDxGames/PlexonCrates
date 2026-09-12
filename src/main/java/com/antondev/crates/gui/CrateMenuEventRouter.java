package com.antondev.crates.gui;

import com.antondev.crates.PlexonCrates;
import com.antondev.crates.gui.player.PlayerCrateCommandRouter;
import java.util.Objects;
import org.bukkit.entity.Player;
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
 * <p>The legacy/admin, simulation and player product surfaces remain separate
 * renderers, but no longer compete as independent Bukkit inventory listeners.
 * Routing is based exclusively on custom inventory holders/markers.</p>
 */
public final class CrateMenuEventRouter implements Listener {
    private final PlexonCrates plugin;
    private final MenuService menus;
    private final PlayerCrateCommandRouter player;
    private final SimulationAdminListener simulation;

    public CrateMenuEventRouter(PlexonCrates plugin, MenuService menus, PlayerCrateCommandRouter player,
                                SimulationAdminListener simulation) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.menus = Objects.requireNonNull(menus, "menus");
        this.player = Objects.requireNonNull(player, "player");
        this.simulation = Objects.requireNonNull(simulation, "simulation");
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void legacyClick(InventoryClickEvent event) {
        if (simulation.routeClick(event)) return;
        MenuHolder holder = holder(event.getView().getTopInventory().getHolder());
        if (holder == null || playerKind(holder.kind())) return;
        if (routeLiveReload(event, holder)) return;
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
        if (simulation.routeDrag(event)) return;
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
        // MenuService owns canonical GuiSessionService cleanup for every MenuHolder.
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

    private boolean routeLiveReload(InventoryClickEvent event, MenuHolder holder) {
        if (!(event.getWhoClicked() instanceof Player player)
                || event.getClickedInventory() != event.getView().getTopInventory()) return false;
        MenuHolder.Action action = holder.action(event.getRawSlot());
        boolean modernSystemReload = action != null && action.id().equals("reload");
        boolean legacyAdminReload = holder.kind() == MenuHolder.Kind.ADMIN
                && event.getRawSlot() == plugin.menusConfig().slot("admin.reload");
        if (!modernSystemReload && !legacyAdminReload) return false;
        event.setCancelled(true);
        plugin.requestReload(player);
        return true;
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
