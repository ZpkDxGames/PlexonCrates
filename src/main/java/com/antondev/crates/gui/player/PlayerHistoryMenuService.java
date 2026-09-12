package com.antondev.crates.gui.player;

import com.antondev.crates.PlexonCrates;
import com.antondev.crates.config.Text;
import com.antondev.crates.database.DatabaseService;
import com.antondev.crates.gui.GuiSessionService;
import com.antondev.crates.gui.MenuHolder;
import com.antondev.crates.service.KeyPaymentPlanner;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

/**
 * Read-only paginated player opening history. Data loading stays on the
 * DatabaseService query executor and this class never mutates opening state.
 */
public final class PlayerHistoryMenuService {
    private static final int PAGE_SIZE = 28;
    private static final DateTimeFormatter TIME = DateTimeFormatter
            .ofPattern("uuuu-MM-dd HH:mm 'UTC'", Locale.ROOT)
            .withZone(ZoneOffset.UTC);

    private final PlexonCrates plugin;
    private final PlayerCrateMenuService crateMenus;

    public PlayerHistoryMenuService(PlexonCrates plugin, PlayerCrateMenuService crateMenus) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.crateMenus = Objects.requireNonNull(crateMenus, "crateMenus");
    }

    public void decorateHall(Player player) {
        Inventory top = player.getOpenInventory().getTopInventory();
        if (!(top.getHolder() instanceof MenuHolder holder)
                || holder.kind() != MenuHolder.Kind.PLAYER_HALL) return;
        int slot = PlayerCrateLayout.SECONDARY;
        ItemStack current = top.getItem(slot);
        if (current != null && !current.getType().isAir()) return;
        top.setItem(slot, item(Material.CLOCK, "<aqua>Opening History</aqua>",
                "<gray>Review your recent finalized crate openings.</gray>",
                "<aqua>Click to browse.</aqua>"));
        holder.bind(slot, "history");
    }

    public void openHistory(Player player, int requestedPage) {
        int page = Math.max(0, requestedPage);
        long revision = plugin.runtime().snapshot().revision();
        MenuHolder holder = new MenuHolder(MenuHolder.Kind.PLAYER_HISTORY, "", "history", page, false, revision);
        Inventory inventory = inventory(holder,
                Text.parse("<gradient:#CAD5E5:#FFFFFF><bold>Opening History</bold></gradient>"));
        inventory.setItem(22, item(Material.CLOCK, "<aqua>Loading history…</aqua>",
                "<gray>Reading finalized openings off the server thread.</gray>"));
        navigation(holder, inventory, page, false);
        open(player, inventory);

        int offset = page * PAGE_SIZE;
        plugin.database().historyAsync(player.getUniqueId(), PAGE_SIZE + 1, offset)
                .whenComplete((records, error) -> main(() -> {
                    if (!sameView(player, holder)) return;
                    if (error != null || records == null) {
                        clearContent(inventory);
                        inventory.setItem(22, item(Material.BARRIER, "<yellow>History unavailable</yellow>",
                                "<gray>No opening state was changed.</gray>",
                                "<gray>Use Refresh to try again.</gray>"));
                        navigation(holder, inventory, page, false);
                        return;
                    }
                    render(player, holder, inventory, records, page);
                }));
    }

    public boolean routeClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof MenuHolder holder)
                || holder.kind() != MenuHolder.Kind.PLAYER_HISTORY) return false;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)
                || event.getClickedInventory() != event.getView().getTopInventory()) return true;
        if (plugin.guiSessions().validate(player, holder, plugin.draftSessions())
                != GuiSessionService.Validation.CURRENT) return true;
        MenuHolder.Action action = holder.action(event.getRawSlot());
        if (action == null) return true;
        switch (action.id()) {
            case "close" -> player.closeInventory();
            case "hall" -> {
                crateMenus.openHall(player, 0);
                decorateHall(player);
            }
            case "refresh" -> openHistory(player, holder.page());
            case "previous" -> openHistory(player, Math.max(0, holder.page() - 1));
            case "next" -> openHistory(player, holder.page() + 1);
            case "preview" -> plugin.runtime().find(action.value()).ifPresentOrElse(
                    crate -> crateMenus.openPreview(player, crate, 0, 0, KeyPaymentPlanner.Preference.PHYSICAL),
                    () -> openHistory(player, holder.page()));
            default -> { }
        }
        return true;
    }

    private void render(Player player, MenuHolder holder, Inventory inventory,
                        List<DatabaseService.OpeningRecord> loaded, int page) {
        clearContent(inventory);
        boolean hasNext = loaded.size() > PAGE_SIZE;
        List<DatabaseService.OpeningRecord> records = loaded.subList(0, Math.min(PAGE_SIZE, loaded.size()));
        for (int index = 0; index < records.size(); index++) {
            DatabaseService.OpeningRecord record = records.get(index);
            int slot = PlayerCrateLayout.contentSlots().get(index);
            inventory.setItem(slot, historyCard(record));
            if (plugin.runtime().find(record.crateId()).isPresent()) {
                holder.bind(slot, "preview", record.crateId());
            }
        }
        if (records.isEmpty()) {
            inventory.setItem(22, item(Material.PAPER, "<gray>No openings on this page</gray>",
                    page == 0 ? "<gray>Your finalized openings will appear here.</gray>"
                            : "<gray>Return to the previous page.</gray>"));
        }
        navigation(holder, inventory, page, hasNext);
        inventory.setItem(PlayerCrateLayout.STATUS, item(Material.PAPER,
                "<white>History · Page " + (page + 1) + "</white>",
                "<gray>" + records.size() + " finalized opening record" + (records.size() == 1 ? "" : "s") + " shown.</gray>"));
    }

    private ItemStack historyCard(DatabaseService.OpeningRecord record) {
        List<Component> lore = new ArrayList<>();
        lore.add(Component.empty());
        lore.add(Text.parse("<gray>Completed</gray> <dark_gray>»</dark_gray> <white>"
                + TIME.format(record.completedAt()) + "</white>"));
        lore.add(Text.parse("<gray>Crate</gray> <dark_gray>»</dark_gray> <white>" + safe(record.crateId()) + "</white>"));
        lore.add(Text.parse("<gray>Openings</gray> <dark_gray>»</dark_gray> <white>" + record.openingCount() + "</white>"));
        lore.add(Text.parse("<gray>Source</gray> <dark_gray>»</dark_gray> <white>" + human(record.source()) + "</white>"));
        lore.add(Text.parse("<gray>Rewards</gray> <dark_gray>»</dark_gray> <white>" + rewardSummary(record.rewardIds()) + "</white>"));
        if (record.overflowCount() > 0) {
            lore.add(Text.parse("<yellow>Pending/overflow deliveries:</yellow> <white>" + record.overflowCount() + "</white>"));
        }
        if (plugin.runtime().find(record.crateId()).isPresent()) {
            lore.add(Component.empty());
            lore.add(Text.parse("<aqua>Click to preview this crate.</aqua>"));
        } else {
            lore.add(Component.empty());
            lore.add(Text.parse("<dark_gray>This crate is no longer published.</dark_gray>"));
        }
        return item(Material.CHEST, Text.parse("<white>" + safe(record.crateId()) + "</white>"), lore);
    }

    private void navigation(MenuHolder holder, Inventory inventory, int page, boolean hasNext) {
        if (page > 0) bind(holder, inventory, PlayerCrateLayout.PREVIOUS, "previous",
                item(Material.ARROW, "<white>Previous</white>"));
        else inventory.setItem(PlayerCrateLayout.PREVIOUS, null);
        bind(holder, inventory, PlayerCrateLayout.CONTEXT, "refresh",
                item(Material.COMPASS, "<aqua>Refresh</aqua>", "<gray>Reload finalized opening history.</gray>"));
        bind(holder, inventory, PlayerCrateLayout.BACK, "hall",
                item(Material.OAK_DOOR, "<white>Crate Hall</white>"));
        bind(holder, inventory, PlayerCrateLayout.CLOSE, "close",
                item(Material.BARRIER, "<red>Close</red>"));
        if (hasNext) bind(holder, inventory, PlayerCrateLayout.NEXT, "next",
                item(Material.ARROW, "<white>Next</white>"));
        else inventory.setItem(PlayerCrateLayout.NEXT, null);
    }

    private Inventory inventory(MenuHolder holder, Component title) {
        Inventory inventory = Bukkit.createInventory(holder, PlayerCrateLayout.SIZE, title);
        holder.attach(inventory);
        ItemStack filler = item(Material.GRAY_STAINED_GLASS_PANE, Component.empty(), List.of());
        for (int slot = 0; slot < inventory.getSize(); slot++) inventory.setItem(slot, filler);
        clearContent(inventory);
        for (int slot = PlayerCrateLayout.PREVIOUS; slot <= PlayerCrateLayout.NEXT; slot++) inventory.setItem(slot, null);
        return inventory;
    }

    private static void clearContent(Inventory inventory) {
        for (int slot : PlayerCrateLayout.contentSlots()) inventory.setItem(slot, null);
    }

    private void open(Player player, Inventory inventory) {
        player.openInventory(inventory);
        if (inventory.getHolder() instanceof MenuHolder holder
                && player.getOpenInventory().getTopInventory() == inventory) {
            plugin.guiSessions().activate(player.getUniqueId(), holder);
        }
    }

    private boolean sameView(Player player, MenuHolder holder) {
        return player.isOnline()
                && player.getOpenInventory().getTopInventory().getHolder() == holder
                && plugin.guiSessions().validate(player, holder, plugin.draftSessions())
                        == GuiSessionService.Validation.CURRENT;
    }

    private void main(Runnable action) {
        if (!plugin.isEnabled()) return;
        if (Bukkit.isPrimaryThread()) action.run();
        else Bukkit.getScheduler().runTask(plugin, action);
    }

    private static void bind(MenuHolder holder, Inventory inventory, int slot, String action, ItemStack item) {
        inventory.setItem(slot, item);
        holder.bind(slot, action);
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

    private static String rewardSummary(String raw) {
        if (raw == null || raw.isBlank()) return "none";
        String normalized = raw.replace(',', ' ').replaceAll("\\s+", " ").trim();
        return normalized.length() <= 72 ? safe(normalized) : safe(normalized.substring(0, 69)) + "…";
    }

    private static String human(String value) {
        if (value == null || value.isBlank()) return "Unknown";
        String text = value.toLowerCase(Locale.ROOT).replace('_', ' ');
        return Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }

    private static String safe(String value) {
        if (value == null) return "";
        return value.replace('<', '[').replace('>', ']');
    }
}
