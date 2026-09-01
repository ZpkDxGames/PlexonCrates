package com.antondev.crates.listener;

import com.antondev.crates.PlexonCrates;
import com.antondev.crates.model.Crate;
import java.util.List;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
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
        if (event.getHand() != EquipmentSlot.HAND || event.getClickedBlock() == null) return;
        var link = plugin.locations().at(event.getClickedBlock()).orElse(null);
        if (link == null) return;
        Crate crate = plugin.crates().find(link.crateId()).orElse(null);
        if (crate == null) return;
        switch (event.getAction()) {
            case LEFT_CLICK_BLOCK -> {
                if (!plugin.settings().leftPreview()) return;
                if (plugin.settings().cancelVanillaUse()) event.setCancelled(true);
                plugin.menus().openPreview(event.getPlayer(), crate, 0, false);
            }
            case RIGHT_CLICK_BLOCK -> {
                if (!plugin.settings().rightOpen()) return;
                if (plugin.settings().cancelVanillaUse()) event.setCancelled(true);
                int amount = event.getPlayer().isSneaking() && plugin.settings().sneakBulk()
                        ? plugin.openings().bulkAmount(event.getPlayer(), crate) : 1;
                plugin.openings().open(event.getPlayer(), crate, Math.max(1, amount), false);
            }
            default -> { }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void breakBlock(BlockBreakEvent event) {
        if (plugin.locations().at(event.getBlock()).isEmpty()) return;
        event.setCancelled(true);
        if (event.getPlayer().hasPermission("plexoncrates.admin")) {
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
