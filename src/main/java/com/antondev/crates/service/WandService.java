package com.antondev.crates.service;

import com.antondev.crates.PlexonCrates;
import com.antondev.crates.api.event.CrateLinkEvent;
import com.antondev.crates.config.Text;
import com.antondev.crates.database.DatabaseService;
import com.antondev.crates.domain.crate.CrateState;
import com.antondev.crates.model.BlockPosition;
import com.antondev.crates.model.Crate;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

/** PDC-identified protected wand for selecting, linking, inspecting, and unlinking crates. */
public final class WandService implements Listener {
    private final PlexonCrates plugin;
    private final NamespacedKey marker;
    private final NamespacedKey selectedCrate;
    private final NamespacedKey schema;

    public WandService(PlexonCrates plugin) {
        this.plugin = plugin;
        marker = new NamespacedKey(plugin, "link_wand");
        selectedCrate = new NamespacedKey(plugin, "link_wand_crate");
        schema = new NamespacedKey(plugin, "link_wand_schema");
    }

    public ItemStack create(String crateId) {
        ItemStack wand = new ItemStack(Material.BLAZE_ROD);
        wand.editMeta(meta -> {
            meta.displayName(Text.parse("<gradient:#F8D477:#FFF4C4><bold>PlexonCrates Link Wand</bold></gradient>"));
            meta.lore(java.util.List.of(
                    Text.parse("<gray>Left-click</gray> <white>link or inspect a block</white>"),
                    Text.parse("<gray>Right-click</gray> <white>select or edit a crate</white>"),
                    Text.parse("<gray>Sneak + left-click</gray> <red>unlink with confirmation</red>"),
                    Text.parse(""),
                    Text.parse("<dark_gray>Selected:</dark_gray> <white>" + (crateId == null || crateId.isBlank() ? "none" : crateId) + "</white>")));
            meta.setEnchantmentGlintOverride(true);
            meta.getPersistentDataContainer().set(marker, PersistentDataType.BYTE, (byte) 1);
            meta.getPersistentDataContainer().set(schema, PersistentDataType.INTEGER, 2);
            if (crateId != null && !crateId.isBlank()) {
                meta.getPersistentDataContainer().set(selectedCrate, PersistentDataType.STRING, crateId.toLowerCase(Locale.ROOT));
            }
        });
        return wand;
    }

    public void give(Player player, String crateId) {
        ItemStack wand = create(crateId);
        player.getInventory().addItem(wand).values().forEach(leftover ->
                player.getWorld().dropItemNaturally(player.getLocation(), leftover));
    }

    public boolean isWand(ItemStack item) {
        return item != null && !item.getType().isAir()
                && item.getItemMeta().getPersistentDataContainer().has(marker, PersistentDataType.BYTE);
    }

    public Optional<String> selected(ItemStack item) {
        if (!isWand(item)) return Optional.empty();
        return Optional.ofNullable(item.getItemMeta().getPersistentDataContainer()
                .get(selectedCrate, PersistentDataType.STRING));
    }

    public void select(Player player, String crateId) {
        ItemStack held = player.getInventory().getItemInMainHand();
        if (!isWand(held)) {
            give(player, crateId);
            return;
        }
        ItemStack replacement = create(crateId);
        replacement.setAmount(held.getAmount());
        player.getInventory().setItemInMainHand(replacement);
        player.sendActionBar(Text.parse("<green>Link Wand selected:</green> <white>" + crateId + "</white>"));
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void interact(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || !isWand(event.getItem())) return;
        event.setCancelled(true);
        Player player = event.getPlayer();
        if (!player.hasPermission("plexoncrates.admin.locations")) {
            plugin.messages().send(player, "no-permission");
            return;
        }
        Block block = event.getClickedBlock();
        if (block == null || event.getAction() == Action.RIGHT_CLICK_AIR) {
            plugin.menus().openWandSelector(player, 0);
            return;
        }
        LocationStore.Link link = plugin.locations().at(block).orElse(null);
        if (event.getAction() == Action.RIGHT_CLICK_BLOCK) {
            if (link == null) plugin.menus().openWandSelector(player, 0);
            else plugin.crates().find(link.crateId()).ifPresent(crate -> plugin.menus().openEditor(player, crate));
            return;
        }
        if (event.getAction() != Action.LEFT_CLICK_BLOCK) return;
        if (link != null) {
            if (player.isSneaking()) plugin.menus().openUnlinkConfirmation(player, link);
            else player.sendActionBar(Text.parse("<yellow>Already linked:</yellow> <white>" + link.crateId() + "</white>"));
            return;
        }
        String crateId = selected(event.getItem()).orElse("");
        Crate crate = plugin.crates().find(crateId).orElse(null);
        if (crate == null || crate.state() == CrateState.ARCHIVED) {
            player.sendActionBar(Text.parse("<yellow>Select a valid crate with right-click first.</yellow>"));
            plugin.menus().openWandSelector(player, 0);
            return;
        }
        if (!allowed(block)) {
            player.sendActionBar(Text.parse("<red>This block or world cannot be linked.</red>"));
            return;
        }
        link(player, block, crate);
    }

    /** Shared validated link operation used by the wand and command fallback. */
    public boolean link(Player player, Block block, Crate crate) {
        if (!player.hasPermission("plexoncrates.admin.locations")) {
            plugin.messages().send(player, "no-permission");
            return false;
        }
        LocationStore.Link existing = plugin.locations().at(block).orElse(null);
        if (existing != null) {
            player.sendActionBar(Text.parse("<yellow>Already linked:</yellow> <white>" + existing.crateId() + "</white>"));
            return false;
        }
        if (crate == null || crate.state() == CrateState.ARCHIVED || !allowed(block)) {
            player.sendActionBar(Text.parse("<red>This crate, block, or world cannot be linked.</red>"));
            return false;
        }
        BlockPosition position = BlockPosition.of(block);
        CrateLinkEvent linkEvent = new CrateLinkEvent(player, crate.id(), position);
        Bukkit.getPluginManager().callEvent(linkEvent);
        if (linkEvent.isCancelled()) return false;
        plugin.locations().set(block, crate.id());
        plugin.displays().refresh();
        plugin.database().audit(new DatabaseService.AuditRecord(player.getUniqueId(), player.getName(), "LINK",
                "LOCATION", position.key(), "Linked to crate " + crate.id(), Instant.now()));
        plugin.messages().send(player, "location-set", Text.component("crate", crate.displayName()));
        return true;
    }

    private boolean allowed(Block block) {
        if (plugin.settings().deniedLocationMaterials().contains(block.getType())
                || block.getType().isAir() || !block.getType().isSolid()) return false;
        String world = block.getWorld().getName().toLowerCase(Locale.ROOT);
        return plugin.settings().allowedLocationWorlds().isEmpty()
                ? plugin.settings().allows(block.getWorld())
                : plugin.settings().allowedLocationWorlds().contains(world);
    }
}
