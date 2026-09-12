package com.antondev.crates.gui.player;

import com.antondev.crates.PlexonCrates;
import com.antondev.crates.service.KeyPaymentPlanner;
import java.util.Locale;
import java.util.Set;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * Owns only the ordinary player command surface for the Phase 3 product UI.
 * Inventory lifecycle is centralized by CrateMenuEventRouter.
 */
public final class PlayerCrateCommandRouter implements Listener {
    private static final Set<String> ROOTS = Set.of("crates", "crate", "plexoncrates");

    private final PlexonCrates plugin;
    private final PlayerCrateMenuService menus;

    public PlayerCrateCommandRouter(PlexonCrates plugin) {
        this.plugin = plugin;
        this.menus = new PlayerCrateMenuService(plugin);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void route(PlayerCommandPreprocessEvent event) {
        String raw = event.getMessage().trim();
        if (raw.length() < 2 || raw.charAt(0) != '/') return;
        String[] parts = raw.substring(1).split("\\s+");
        if (parts.length == 0 || !ROOTS.contains(parts[0].toLowerCase(Locale.ROOT))) return;

        Player player = event.getPlayer();
        if (parts.length == 1) {
            if (!canPreview(player)) return;
            event.setCancelled(true);
            menus.openHall(player, 0);
            return;
        }

        String action = parts[1].toLowerCase(Locale.ROOT);
        if (action.equals("claim")) {
            if (!player.hasPermission("plexoncrates.claim") && !player.hasPermission("plexoncrates.use")) return;
            if (!plugin.settings().claimInboxEnabled()) return;
            int page = 1;
            if (parts.length == 3) {
                try {
                    page = Integer.parseInt(parts[2]);
                    if (page < 1) return;
                } catch (NumberFormatException ignored) {
                    // UUID claims remain on the accepted direct ClaimService command path.
                    return;
                }
            } else if (parts.length > 3) {
                return;
            }
            event.setCancelled(true);
            menus.openPendingRewards(player, page, 0);
            return;
        }

        if (action.equals("preview")) {
            if (!canPreview(player)) return;
            if (parts.length == 2) {
                event.setCancelled(true);
                menus.openHall(player, 0);
                return;
            }
            if (parts.length == 3) {
                plugin.runtime().find(parts[2]).ifPresent(crate -> {
                    event.setCancelled(true);
                    menus.openPreview(player, crate, 0, 0, KeyPaymentPlanner.Preference.PHYSICAL);
                });
            }
            return;
        }

        // /crates <crate> remains a convenient direct preview route. Explicit
        // opening/admin/history/key commands continue through CratesCommand.
        if (parts.length == 2 && canPreview(player)) {
            plugin.runtime().find(parts[1]).ifPresent(crate -> {
                event.setCancelled(true);
                menus.openPreview(player, crate, 0, 0, KeyPaymentPlanner.Preference.PHYSICAL);
            });
        }
    }

    /** Routed by the single registered crate inventory listener. */
    public void click(InventoryClickEvent event) {
        menus.click(event);
    }

    /** Routed by the single registered crate inventory listener. */
    public void close(InventoryCloseEvent event) {
        menus.close(event);
    }

    /** Routed by the single registered crate inventory listener. */
    public void quit(PlayerQuitEvent event) {
        menus.quit(event);
    }

    private static boolean canPreview(Player player) {
        return player.hasPermission("plexoncrates.preview") || player.hasPermission("plexoncrates.use");
    }
}
