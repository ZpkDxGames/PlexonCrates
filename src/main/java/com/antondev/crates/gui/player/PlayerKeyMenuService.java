package com.antondev.crates.gui.player;

import com.antondev.crates.PlexonCrates;
import com.antondev.crates.config.Text;
import com.antondev.crates.domain.key.KeyDefinition;
import com.antondev.crates.gui.GuiSessionService;
import com.antondev.crates.gui.MenuHolder;
import com.antondev.crates.model.Crate;
import com.antondev.crates.service.KeyPaymentPlanner;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

/**
 * Stateless player key browser. It presents balances and compatible crates but
 * never consumes, grants, edits or re-encodes a key template.
 */
public final class PlayerKeyMenuService {
    private final PlexonCrates plugin;
    private final PlayerCrateMenuService crateMenus;

    public PlayerKeyMenuService(PlexonCrates plugin, PlayerCrateMenuService crateMenus) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.crateMenus = Objects.requireNonNull(crateMenus, "crateMenus");
    }

    public void openKeys(Player player, int requestedPage) {
        List<KeyDefinition> definitions = plugin.keys().definitions().stream()
                .filter(KeyDefinition::enabled)
                .toList();
        int page = PlayerCrateLayout.clampPage(requestedPage, definitions.size());
        long revision = plugin.runtime().snapshot().revision();
        MenuHolder holder = new MenuHolder(MenuHolder.Kind.PLAYER_KEYS, "", "keys", page, false, revision);
        Inventory inventory = inventory(holder, Text.parse("<gradient:#FFD98A:#FFFFFF><bold>My Keys</bold></gradient>"));

        int start = page * PlayerCrateLayout.contentSlots().size();
        for (int i = 0; i < PlayerCrateLayout.contentSlots().size() && start + i < definitions.size(); i++) {
            KeyDefinition definition = definitions.get(start + i);
            int slot = PlayerCrateLayout.contentSlots().get(i);
            int physical = plugin.keys().count(player, definition.id());
            List<Crate> compatible = compatibleCrates(definition.id());
            inventory.setItem(slot, keyCard(definition, physical, compatible, null, false));
            holder.bind(slot, "compatible", definition.id());
            loadVirtualBalance(player, definition.id()).whenComplete((balance, error) -> main(() -> {
                if (!sameView(player, holder, revision)) return;
                inventory.setItem(slot, keyCard(definition, physical, compatible,
                        error == null ? balance : null, error != null));
            }));
        }

        if (definitions.isEmpty()) {
            inventory.setItem(22, item(Material.BARRIER, "<yellow>No keys are available</yellow>",
                    "<gray>Published enabled key definitions will appear here automatically.</gray>"));
        }
        if (page > 0) bind(holder, inventory, PlayerCrateLayout.PREVIOUS, "previous",
                item(Material.ARROW, "<white>Previous</white>"));
        bind(holder, inventory, PlayerCrateLayout.CONTEXT, "refresh",
                item(Material.COMPASS, "<aqua>Refresh</aqua>", "<gray>Reload balances and compatible crates.</gray>"));
        bind(holder, inventory, PlayerCrateLayout.BACK, "hall",
                item(Material.OAK_DOOR, "<white>Crate Hall</white>", "<gray>Return to the player crate browser.</gray>"));
        inventory.setItem(PlayerCrateLayout.STATUS, item(Material.PAPER,
                "<white>Page " + (page + 1) + " / " + PlayerCrateLayout.pageCount(definitions.size()) + "</white>",
                "<gray>" + definitions.size() + " enabled key" + (definitions.size() == 1 ? "" : "s") + "</gray>"));
        bind(holder, inventory, PlayerCrateLayout.CLOSE, "close", item(Material.BARRIER, "<red>Close</red>"));
        if (page + 1 < PlayerCrateLayout.pageCount(definitions.size())) bind(holder, inventory, PlayerCrateLayout.NEXT, "next",
                item(Material.ARROW, "<white>Next</white>"));
        open(player, inventory);
    }

    private void openCompatibleCrates(Player player, String keyId, int requestedPage, int keyPage) {
        KeyDefinition definition = plugin.keys().definitions().stream()
                .filter(value -> value.enabled() && value.id().equals(keyId))
                .findFirst().orElse(null);
        if (definition == null) {
            openKeys(player, keyPage);
            return;
        }
        List<Crate> crates = compatibleCrates(keyId);
        int page = PlayerCrateLayout.clampPage(requestedPage, crates.size());
        long revision = plugin.runtime().snapshot().revision();
        MenuHolder holder = new MenuHolder(MenuHolder.Kind.PLAYER_KEY_CRATES, "", keyId, page, false, revision);
        Inventory inventory = inventory(holder, Text.parse("<white><bold>Crates using</bold></white> ").append(definition.displayName()));

        int start = page * PlayerCrateLayout.contentSlots().size();
        for (int i = 0; i < PlayerCrateLayout.contentSlots().size() && start + i < crates.size(); i++) {
            Crate crate = crates.get(start + i);
            int slot = PlayerCrateLayout.contentSlots().get(i);
            inventory.setItem(slot, crateCard(crate, definition));
            holder.bind(slot, "preview", crate.id());
        }
        if (crates.isEmpty()) inventory.setItem(22, item(Material.BARRIER,
                "<yellow>No compatible crates</yellow>",
                "<gray>No currently published crate accepts this key.</gray>"));
        if (page > 0) bind(holder, inventory, PlayerCrateLayout.PREVIOUS, "previous", Integer.toString(keyPage),
                item(Material.ARROW, "<white>Previous</white>"));
        bind(holder, inventory, PlayerCrateLayout.CONTEXT, "refresh", Integer.toString(keyPage),
                item(Material.COMPASS, "<aqua>Refresh</aqua>", "<gray>Reload the compatible crate list.</gray>"));
        bind(holder, inventory, PlayerCrateLayout.BACK, "back-keys", Integer.toString(keyPage),
                item(Material.OAK_DOOR, "<white>My Keys</white>", "<gray>Return to your key overview.</gray>"));
        inventory.setItem(PlayerCrateLayout.STATUS, item(Material.PAPER,
                "<white>Compatible crates · Page " + (page + 1) + " / " + PlayerCrateLayout.pageCount(crates.size()) + "</white>",
                "<gray>" + crates.size() + " crate" + (crates.size() == 1 ? "" : "s") + " accept this key.</gray>"));
        bind(holder, inventory, PlayerCrateLayout.CLOSE, "close", item(Material.BARRIER, "<red>Close</red>"));
        if (page + 1 < PlayerCrateLayout.pageCount(crates.size())) bind(holder, inventory, PlayerCrateLayout.NEXT,
                "next", Integer.toString(keyPage), item(Material.ARROW, "<white>Next</white>"));
        open(player, inventory);
    }

    /**
     * Returns true only when this service owns the clicked surface. The central
     * CrateMenuEventRouter remains the sole registered inventory listener.
     */
    public boolean routeClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof MenuHolder holder)) return false;
        if (holder.kind() != MenuHolder.Kind.PLAYER_KEYS && holder.kind() != MenuHolder.Kind.PLAYER_KEY_CRATES) return false;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)
                || event.getClickedInventory() != event.getView().getTopInventory()) return true;
        if (plugin.guiSessions().validate(player, holder, plugin.draftSessions()) != GuiSessionService.Validation.CURRENT) return true;
        if (holder.revision() != plugin.runtime().snapshot().revision()) {
            player.sendMessage(Text.parse("<yellow>Crate definitions changed while this menu was open. The latest view has been loaded.</yellow>"));
            openKeys(player, 0);
            return true;
        }
        MenuHolder.Action action = holder.action(event.getRawSlot());
        if (action == null) return true;
        if (holder.kind() == MenuHolder.Kind.PLAYER_KEYS) keysClick(player, holder, action);
        else compatibleClick(player, holder, action);
        return true;
    }

    private void keysClick(Player player, MenuHolder holder, MenuHolder.Action action) {
        switch (action.id()) {
            case "close" -> player.closeInventory();
            case "hall" -> crateMenus.openHall(player, 0);
            case "refresh" -> openKeys(player, holder.page());
            case "previous" -> openKeys(player, holder.page() - 1);
            case "next" -> openKeys(player, holder.page() + 1);
            case "compatible" -> openCompatibleCrates(player, action.value(), 0, holder.page());
            default -> { }
        }
    }

    private void compatibleClick(Player player, MenuHolder holder, MenuHolder.Action action) {
        int keyPage = parsePage(action.value());
        switch (action.id()) {
            case "close" -> player.closeInventory();
            case "back-keys" -> openKeys(player, keyPage);
            case "refresh" -> openCompatibleCrates(player, holder.rewardId(), holder.page(), keyPage);
            case "previous" -> openCompatibleCrates(player, holder.rewardId(), holder.page() - 1, keyPage);
            case "next" -> openCompatibleCrates(player, holder.rewardId(), holder.page() + 1, keyPage);
            case "preview" -> plugin.runtime().find(action.value()).ifPresentOrElse(
                    crate -> crateMenus.openPreview(player, crate, 0, 0, KeyPaymentPlanner.Preference.PHYSICAL),
                    () -> openCompatibleCrates(player, holder.rewardId(), holder.page(), keyPage));
            default -> { }
        }
    }

    private CompletableFuture<Long> loadVirtualBalance(Player player, String keyId) {
        if (!plugin.settings().virtualKeyWalletEnabled()) return CompletableFuture.completedFuture(0L);
        if (plugin.keys().usesPlexonKeysWallet(keyId)) {
            try {
                return CompletableFuture.completedFuture(Math.max(0L,
                        plugin.keys().plexonKeysBalance(player.getUniqueId(), keyId)));
            } catch (RuntimeException error) {
                plugin.getLogger().log(Level.WARNING, "Could not read PlexonKeys balance for " + keyId, error);
                return CompletableFuture.failedFuture(error);
            }
        }
        return plugin.database().loadVirtualKeyBalance(player.getUniqueId(), keyId)
                .thenApply(balance -> Math.max(0L, balance.balance()));
    }

    private List<Crate> compatibleCrates(String keyId) {
        return plugin.runtime().ordered().stream()
                .filter(crate -> crate.acceptedKeyIds().contains(keyId))
                .toList();
    }

    private ItemStack keyCard(KeyDefinition definition, int physical, List<Crate> compatible,
                              Long virtualBalance, boolean virtualFailed) {
        Material material = Material.TRIPWIRE_HOOK;
        ItemStack configured = definition.icon();
        if (configured != null && !configured.getType().isAir()) material = configured.getType();
        List<Component> lore = new ArrayList<>();
        lore.add(Component.empty());
        lore.add(Text.parse("<gray>Physical</gray> <dark_gray>»</dark_gray> <white>" + physical + "</white>"));
        if (!plugin.settings().virtualKeyWalletEnabled()) {
            lore.add(Text.parse("<gray>Virtual</gray> <dark_gray>»</dark_gray> <dark_gray>Disabled</dark_gray>"));
        } else if (virtualFailed) {
            lore.add(Text.parse("<gray>Virtual</gray> <dark_gray>»</dark_gray> <yellow>Unavailable</yellow>"));
        } else if (virtualBalance == null) {
            lore.add(Text.parse("<gray>Virtual</gray> <dark_gray>»</dark_gray> <gray>Loading…</gray>"));
        } else {
            String source = plugin.keys().usesPlexonKeysWallet(definition.id()) ? "PlexonKeys" : "Wallet";
            lore.add(Text.parse("<gray>" + source + "</gray> <dark_gray>»</dark_gray> <white>" + virtualBalance + "</white>"));
        }
        lore.add(Text.parse("<gray>Source</gray> <dark_gray>»</dark_gray> <white>" + human(definition.source().name()) + "</white>"));
        lore.add(Text.parse("<gray>Compatible crates</gray> <dark_gray>»</dark_gray> <white>" + compatible.size() + "</white>"));
        compatible.stream().limit(3).forEach(crate -> lore.add(Text.parse("<dark_gray>•</dark_gray> ").append(crate.displayName())));
        if (compatible.size() > 3) lore.add(Text.parse("<dark_gray>…and " + (compatible.size() - 3) + " more</dark_gray>"));
        lore.add(Component.empty());
        lore.add(Text.parse("<aqua>Click to view compatible crates.</aqua>"));
        return item(material, definition.displayName(), lore);
    }

    private ItemStack crateCard(Crate crate, KeyDefinition definition) {
        Material material = Material.CHEST;
        ItemStack configured = crate.iconCopy();
        if (configured != null && !configured.getType().isAir()) material = configured.getType();
        List<Component> lore = new ArrayList<>();
        lore.add(Component.empty());
        lore.add(Text.parse("<gray>Accepts</gray> <dark_gray>»</dark_gray> ").append(definition.displayName()));
        lore.add(Text.parse("<gray>Opening</gray> <dark_gray>»</dark_gray> <white>" + human(crate.openingMode().name()) + "</white>"));
        lore.add(Component.empty());
        lore.add(Text.parse("<aqua>Click to Preview.</aqua>"));
        return item(material, crate.displayName(), lore);
    }

    private Inventory inventory(MenuHolder holder, Component title) {
        Inventory inventory = Bukkit.createInventory(holder, PlayerCrateLayout.SIZE, title);
        holder.attach(inventory);
        ItemStack filler = item(Material.GRAY_STAINED_GLASS_PANE, Component.empty(), List.of());
        for (int slot = 0; slot < inventory.getSize(); slot++) inventory.setItem(slot, filler);
        for (int slot : PlayerCrateLayout.contentSlots()) inventory.setItem(slot, null);
        for (int slot = PlayerCrateLayout.PREVIOUS; slot <= PlayerCrateLayout.NEXT; slot++) inventory.setItem(slot, null);
        return inventory;
    }

    private void open(Player player, Inventory inventory) {
        player.openInventory(inventory);
        if (inventory.getHolder() instanceof MenuHolder holder
                && player.getOpenInventory().getTopInventory() == inventory) {
            plugin.guiSessions().activate(player.getUniqueId(), holder);
        }
    }

    private boolean sameView(Player player, MenuHolder holder, long revision) {
        return player.isOnline()
                && plugin.guiSessions().validate(player, holder, plugin.draftSessions()) == GuiSessionService.Validation.CURRENT
                && holder.revision() == revision
                && plugin.runtime().snapshot().revision() == revision;
    }

    private static void bind(MenuHolder holder, Inventory inventory, int slot, String action, ItemStack item) {
        inventory.setItem(slot, item);
        holder.bind(slot, action);
    }

    private static void bind(MenuHolder holder, Inventory inventory, int slot, String action, String value, ItemStack item) {
        inventory.setItem(slot, item);
        holder.bind(slot, action, value);
    }

    private static ItemStack item(Material material, String name, String... lore) {
        List<Component> lines = new ArrayList<>();
        for (String line : lore) lines.add(Text.parse(line));
        return item(material, Text.parse(name), lines);
    }

    private static ItemStack item(Material material, Component name, List<Component> lore) {
        ItemStack value = new ItemStack(material);
        value.editMeta(meta -> {
            meta.displayName(name.decoration(TextDecoration.ITALIC, false));
            meta.lore(lore.stream().map(line -> line.decoration(TextDecoration.ITALIC, false)).toList());
        });
        return value;
    }

    private static int parsePage(String raw) {
        try {
            return Math.max(0, Integer.parseInt(raw));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private static String human(String value) {
        String text = value.toLowerCase(Locale.ROOT).replace('_', ' ');
        return text.isEmpty() ? text : Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }

    private void main(Runnable task) {
        if (!plugin.isEnabled()) return;
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (plugin.isEnabled()) task.run();
        });
    }
}
