package com.antondev.crates.listener;

import com.antondev.crates.PlexonCrates;
import com.antondev.crates.model.Crate;
import com.antondev.crates.model.BlockPosition;
import com.antondev.crates.domain.opening.OpenSource;
import java.util.List;
import org.bukkit.block.Block;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockFadeEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.event.server.PluginEnableEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.inventory.EquipmentSlot;

public final class CrateListener implements Listener {
    private final PlexonCrates plugin;

    public CrateListener(PlexonCrates plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void interact(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        if (plugin.portables() != null && plugin.portables().isPortable(event.getItem())) {
            event.setCancelled(true);
            if (event.getAction() == org.bukkit.event.block.Action.RIGHT_CLICK_AIR
                    || event.getAction() == org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK) {
                handlePortable(event.getPlayer(), event.getItem());
            }
            return;
        }
        if (event.getClickedBlock() == null) return;
        var link = plugin.locations().at(event.getClickedBlock()).orElse(null);
        if (link == null) return;
        Crate crate = plugin.runtime().find(link.crateId()).orElse(null);
        if (crate == null) return;
        switch (event.getAction()) {
            case LEFT_CLICK_BLOCK -> {
                if (!plugin.settings().leftPreview()) return;
                if (plugin.settings().cancelVanillaUse()) event.setCancelled(true);
                if (!event.getPlayer().hasPermission("plexoncrates.preview")) {
                    plugin.messages().send(event.getPlayer(), "no-permission");
                    return;
                }
                plugin.menus().openPreview(event.getPlayer(), crate, 0, false);
            }
            case RIGHT_CLICK_BLOCK -> {
                if (!plugin.settings().rightOpen()) return;
                if (plugin.settings().cancelVanillaUse()) event.setCancelled(true);
                if (!event.getPlayer().hasPermission("plexoncrates.open")) {
                    plugin.messages().send(event.getPlayer(), "no-permission");
                    return;
                }
                int amount = event.getPlayer().isSneaking() && plugin.settings().sneakBulk()
                        ? plugin.openings().bulkAmount(event.getPlayer(), crate) : 1;
                plugin.openings().open(event.getPlayer(), crate, Math.max(1, amount), OpenSource.BLOCK,
                        BlockPosition.of(event.getClickedBlock()));
            }
            default -> { }
        }
    }

    private void handlePortable(org.bukkit.entity.Player player, org.bukkit.inventory.ItemStack item) {
        if (plugin.portables() == null || !plugin.portables().ready()) {
            plugin.messages().send(player, "opening-state-changed");
            return;
        }
        org.bukkit.inventory.ItemStack expected = item == null ? null : item.clone();
        plugin.portables().verify(expected).whenComplete((issue, error) -> {
            if (!plugin.isEnabled()) return;
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!player.isOnline()) return;
                if (error != null || issue == null || issue.isEmpty()) {
                    plugin.messages().send(player, "invalid-crate");
                    return;
                }
                var record = issue.get();
                if (record.issuedTo() != null && !record.issuedTo().equals(player.getUniqueId())) {
                    plugin.messages().send(player, "no-permission");
                    return;
                }
                if (!record.state().equals("UNUSED")) {
                    player.sendActionBar(com.antondev.crates.config.Text.parse(
                            "<yellow>This portable crate has already been used or needs review.</yellow>"));
                    return;
                }
                Crate crate = plugin.runtime().find(record.crateId()).orElse(null);
                if (crate == null || crate.state() != com.antondev.crates.domain.crate.CrateState.PUBLISHED) {
                    plugin.messages().send(player, "opening-state-changed");
                    return;
                }
                if (record.revisionPolicy().equals("PINNED_REVISION")
                        && record.pinnedRevision() != plugin.runtime().crateRevision(record.crateId())) {
                    plugin.messages().send(player, "opening-state-changed");
                    return;
                }
                plugin.menus().openPortablePreview(player, crate, record);
            });
        });
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void breakBlock(BlockBreakEvent event) {
        if (plugin.locations().at(event.getBlock()).isEmpty()) return;
        if (event.getPlayer().hasPermission("plexoncrates.admin.protection-bypass")) return;
        event.setCancelled(true);
        if (event.getPlayer().hasPermission("plexoncrates.admin")
                || event.getPlayer().hasPermission("plexoncrates.admin.locations")) {
            plugin.messages().send(event.getPlayer(), "location-protected");
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void blockExplosion(BlockExplodeEvent event) {
        protectExplosion(event.blockList());
    }

    @EventHandler(ignoreCancelled = true)
    public void entityExplosion(EntityExplodeEvent event) {
        protectExplosion(event.blockList());
    }

    @EventHandler(ignoreCancelled = true)
    public void pistonExtend(BlockPistonExtendEvent event) {
        if (movesCrate(event.getBlocks(), event.getDirection())) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void pistonRetract(BlockPistonRetractEvent event) {
        if (movesCrate(event.getBlocks(), event.getDirection())) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void entityChangesBlock(EntityChangeBlockEvent event) {
        if (plugin.locations().at(event.getBlock()).isPresent()) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void blockBurns(BlockBurnEvent event) {
        if (plugin.locations().at(event.getBlock()).isPresent()) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void blockFades(BlockFadeEvent event) {
        if (plugin.locations().at(event.getBlock()).isPresent()) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void bucket(PlayerBucketEmptyEvent event) {
        if (plugin.locations().at(event.getBlock()).isPresent()
                && !event.getPlayer().hasPermission("plexoncrates.admin.protection-bypass")) event.setCancelled(true);
    }

    @EventHandler
    public void providerEnabled(PluginEnableEvent event) {
        if (event.getPlugin().getName().equalsIgnoreCase(plugin.settings().plexonKeysPlugin())) plugin.keys().syncDiscovery();
    }

    @EventHandler
    public void providerDisabled(PluginDisableEvent event) {
        if (event.getPlugin().getName().equalsIgnoreCase(plugin.settings().plexonKeysPlugin())) plugin.keys().syncDiscovery();
    }

    @EventHandler
    public void chunkLoad(ChunkLoadEvent event) {
        plugin.displays().chunkLoaded(event.getChunk());
    }

    @EventHandler
    public void chunkUnload(ChunkUnloadEvent event) {
        plugin.displays().chunkUnloaded(event.getChunk());
    }

    private void protectExplosion(List<Block> blocks) {
        blocks.removeIf(block -> plugin.locations().at(block).isPresent());
    }

    private boolean movesCrate(List<Block> blocks, org.bukkit.block.BlockFace direction) {
        return blocks.stream().anyMatch(block -> plugin.locations().at(block).isPresent()
                || plugin.locations().at(block.getRelative(direction)).isPresent());
    }
}
