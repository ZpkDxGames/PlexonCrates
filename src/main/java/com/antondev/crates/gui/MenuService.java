package com.antondev.crates.gui;

import com.antondev.crates.PlexonCrates;
import com.antondev.crates.config.MenuConfig;
import com.antondev.crates.config.Text;
import com.antondev.crates.model.Crate;
import com.antondev.crates.model.CrateReward;
import com.antondev.crates.service.RewardSelector;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;

public final class MenuService implements Listener {
    private final PlexonCrates plugin;

    public MenuService(PlexonCrates plugin) {
        this.plugin = plugin;
    }

    public void openBrowser(Player player) {
        MenuConfig menus = plugin.menusConfig();
        MenuHolder holder = new MenuHolder(MenuHolder.Kind.BROWSER, "", "", 0, false);
        Inventory inventory = create(holder, menus.size("browser"), menus.title("browser"));
        fill(inventory);
        List<Integer> slots = menus.slots("browser.crate-slots");
        List<Crate> crates = plugin.crates().ordered();
        for (int index = 0; index < Math.min(slots.size(), crates.size()); index++) {
            Crate crate = crates.get(index);
            ItemStack icon = crate.iconCopy();
            appendLore(icon, List.of(Component.empty(),
                    Text.parse("<gray>Keys available</gray> <dark_gray>»</dark_gray> <white>" + plugin.keys().count(player, crate.keyId()) + "</white>"),
                    Text.parse("<gray>Your openings</gray> <dark_gray>»</dark_gray> <white>" + plugin.statistics().player(player.getUniqueId(), crate.id()) + "</white>")));
            inventory.setItem(slots.get(index), icon);
        }
        inventory.setItem(menus.slot("browser.info"), menus.item("browser.info"));
        inventory.setItem(menus.slot("browser.close"), menus.item("browser.close"));
        player.openInventory(inventory);
    }

    public void openPreview(Player player, Crate crate, int requestedPage, boolean adminOrigin) {
        MenuConfig menus = plugin.menusConfig();
        List<Integer> rewardSlots = menus.slots("preview.reward-slots");
        List<CrateReward> rewards = crate.orderedRewards().stream().filter(CrateReward::enabled).toList();
        int pages = Math.max(1, (rewards.size() + rewardSlots.size() - 1) / rewardSlots.size());
        int page = Math.max(0, Math.min(requestedPage, pages - 1));
        MenuHolder holder = new MenuHolder(MenuHolder.Kind.PREVIEW, crate.id(), "", page, adminOrigin);
        Inventory inventory = create(holder, menus.size("preview"), menus.title("preview", Text.component("crate", crate.displayName())));
        fill(inventory);
        List<CrateReward> eligible = rewards.stream().filter(reward -> reward.eligible(player)).toList();
        int start = page * rewardSlots.size();
        for (int slotIndex = 0; slotIndex < rewardSlots.size() && start + slotIndex < rewards.size(); slotIndex++) {
            CrateReward reward = rewards.get(start + slotIndex);
            ItemStack display = reward.displayCopy();
            double chance = reward.eligible(player) ? RewardSelector.chance(reward, eligible) : 0;
            var lore = new ArrayList<Component>();
            for (String line : menus.strings("preview.reward-lore")) {
                lore.add(Text.parse(line, Text.value("chance", format(chance)), Text.value("weight", format(reward.weight()))));
            }
            if (!reward.eligible(player)) lore.add(Text.parse("<red>You are not eligible for this reward.</red>"));
            appendLore(display, lore);
            inventory.setItem(rewardSlots.get(slotIndex), display);
        }
        ItemStack open = menus.item("preview.open", Text.component("crate", crate.displayName()),
                Text.value("keys", plugin.keys().count(player, crate.keyId())), Text.component("key", keyName(crate)));
        inventory.setItem(menus.slot("preview.open"), open);
        inventory.setItem(menus.slot("preview.back"), menus.item("preview.back"));
        if (page > 0) inventory.setItem(menus.slot("preview.previous"), menus.item("preview.previous"));
        if (page + 1 < pages) inventory.setItem(menus.slot("preview.next"), menus.item("preview.next"));
        player.openInventory(inventory);
    }

    public void openAdmin(Player player) {
        MenuConfig menus = plugin.menusConfig();
        MenuHolder holder = new MenuHolder(MenuHolder.Kind.ADMIN, "", "", 0, true);
        Inventory inventory = create(holder, menus.size("admin"), menus.title("admin"));
        fill(inventory);
        List<Integer> slots = menus.slots("admin.crate-slots");
        List<Crate> crates = plugin.crates().ordered();
        for (int index = 0; index < Math.min(slots.size(), crates.size()); index++) {
            Crate crate = crates.get(index);
            ItemStack icon = crate.iconCopy();
            appendLore(icon, List.of(Component.empty(),
                    Text.parse("<gray>Rewards</gray> <dark_gray>»</dark_gray> <white>" + crate.rewards().size() + "</white>"),
                    Text.parse("<gray>Linked blocks</gray> <dark_gray>»</dark_gray> <white>" + plugin.locations().count(crate.id()) + "</white>"),
                    Text.parse("<gold>Click to configure.</gold>")));
            inventory.setItem(slots.get(index), icon);
        }
        inventory.setItem(menus.slot("admin.status"), menus.item("admin.status",
                Text.value("crates", plugin.crates().all().size()), Text.value("rewards", plugin.crates().rewardCount()),
                Text.value("locations", plugin.locations().all().size()), Text.value("key_source", plugin.keys().sourceLabel())));
        inventory.setItem(menus.slot("admin.reload"), menus.item("admin.reload"));
        player.openInventory(inventory);
    }

    public void openEditor(Player player, Crate crate) {
        MenuConfig menus = plugin.menusConfig();
        MenuHolder holder = new MenuHolder(MenuHolder.Kind.EDITOR, crate.id(), "", 0, true);
        Inventory inventory = create(holder, menus.size("editor"), menus.title("editor", Text.component("crate", crate.displayName())));
        fill(inventory);
        for (String item : List.of("preview", "location", "capture", "rewards", "key", "back")) {
            inventory.setItem(menus.slot("editor." + item), menus.item("editor." + item));
        }
        player.openInventory(inventory);
    }

    public void openRewards(Player player, Crate crate, int requestedPage) {
        MenuConfig menus = plugin.menusConfig();
        List<Integer> slots = menus.slots("preview.reward-slots");
        List<CrateReward> rewards = crate.orderedRewards();
        int pages = Math.max(1, (rewards.size() + slots.size() - 1) / slots.size());
        int page = Math.max(0, Math.min(requestedPage, pages - 1));
        MenuHolder holder = new MenuHolder(MenuHolder.Kind.REWARDS, crate.id(), "", page, true);
        Inventory inventory = create(holder, menus.size("preview"),
                Text.parse("<gold>Rewards</gold> <dark_gray>•</dark_gray> ", Text.component("crate", crate.displayName())).append(crate.displayName()));
        fill(inventory);
        int start = page * slots.size();
        for (int index = 0; index < slots.size() && start + index < rewards.size(); index++) {
            CrateReward reward = rewards.get(start + index);
            ItemStack display = reward.displayCopy();
            appendLore(display, List.of(Component.empty(),
                    Text.parse("<gray>ID</gray> <dark_gray>»</dark_gray> <white>" + reward.id() + "</white>"),
                    Text.parse("<gray>Weight</gray> <dark_gray>»</dark_gray> <yellow>" + format(reward.weight()) + "</yellow>"),
                    Text.parse("<red>Shift-right-click to remove.</red>")));
            inventory.setItem(slots.get(index), display);
        }
        inventory.setItem(menus.slot("preview.back"), menus.item("preview.back"));
        if (page > 0) inventory.setItem(menus.slot("preview.previous"), menus.item("preview.previous"));
        if (page + 1 < pages) inventory.setItem(menus.slot("preview.next"), menus.item("preview.next"));
        player.openInventory(inventory);
    }

    public void animate(Player player, Crate crate, CrateReward selected, Runnable completed) {
        MenuConfig menus = plugin.menusConfig();
        MenuHolder holder = new MenuHolder(MenuHolder.Kind.OPENING, crate.id(), selected.id(), 0, false);
        Inventory inventory = create(holder, menus.size("opening"), menus.title("opening", Text.component("crate", crate.displayName())));
        fill(inventory);
        inventory.setItem(menus.slot("opening.marker-top-slot"), menus.item("opening.marker"));
        inventory.setItem(menus.slot("opening.marker-bottom-slot"), menus.item("opening.marker"));
        List<Integer> rail = menus.slots("opening.rail-slots");
        List<CrateReward> visuals = crate.orderedRewards().stream().filter(reward -> reward.eligible(player)).toList();
        if (visuals.isEmpty()) visuals = List.of(selected);
        for (int slot : rail) inventory.setItem(slot, randomDisplay(visuals));
        player.openInventory(inventory);

        List<CrateReward> finalVisuals = visuals;
        int steps = Math.max(1, plugin.settings().animationDuration() / plugin.settings().animationPeriod());
        new BukkitRunnable() {
            private int step;
            @Override public void run() {
                step++;
                if (player.isOnline() && player.getOpenInventory().getTopInventory().getHolder() == holder) {
                    for (int index = 0; index < rail.size() - 1; index++) {
                        inventory.setItem(rail.get(index), inventory.getItem(rail.get(index + 1)));
                    }
                    inventory.setItem(rail.getLast(), randomDisplay(finalVisuals));
                    if (step % 3 == 0) {
                        float pitch = Math.min(2.0f, 0.65f + step / (float) steps);
                        player.playSound(player.getLocation(), plugin.settings().openingSound(), 0.35f, pitch);
                    }
                }
                if (step < steps) return;
                cancel();
                if (player.isOnline() && player.getOpenInventory().getTopInventory().getHolder() == holder) {
                    inventory.setItem(menus.slot("opening.center-slot"), selected.displayCopy());
                }
                completed.run();
            }
        }.runTaskTimer(plugin, plugin.settings().animationPeriod(), plugin.settings().animationPeriod());
    }

    public void closeAll() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getOpenInventory().getTopInventory().getHolder() instanceof MenuHolder holder
                    && holder.kind() != MenuHolder.Kind.OPENING) player.closeInventory();
        }
    }

    @EventHandler
    public void click(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof MenuHolder holder)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (event.getClickedInventory() != event.getView().getTopInventory()) return;
        int slot = event.getRawSlot();
        MenuConfig menus = plugin.menusConfig();
        switch (holder.kind()) {
            case BROWSER -> browserClick(player, slot, event.isRightClick());
            case PREVIEW -> {
                Crate crate = plugin.crates().find(holder.crateId()).orElse(null);
                if (crate == null) return;
                if (slot == menus.slot("preview.open")) plugin.openings().open(player, crate, 1, false);
                else if (slot == menus.slot("preview.back")) {
                    if (holder.adminOrigin()) openEditor(player, crate); else openBrowser(player);
                } else if (slot == menus.slot("preview.previous")) openPreview(player, crate, holder.page() - 1, holder.adminOrigin());
                else if (slot == menus.slot("preview.next")) openPreview(player, crate, holder.page() + 1, holder.adminOrigin());
            }
            case ADMIN -> adminClick(player, slot);
            case EDITOR -> editorClick(player, holder.crateId(), slot);
            case REWARDS -> rewardsClick(player, holder, slot, event.isShiftClick() && event.isRightClick());
            case CONFIRM_DELETE -> confirmClick(player, holder, slot);
            case OPENING -> { }
        }
    }

    @EventHandler
    public void drag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof MenuHolder) event.setCancelled(true);
    }

    private void browserClick(Player player, int slot, boolean rightClick) {
        MenuConfig menus = plugin.menusConfig();
        if (slot == menus.slot("browser.close")) {
            player.closeInventory();
            return;
        }
        int index = menus.slots("browser.crate-slots").indexOf(slot);
        List<Crate> crates = plugin.crates().ordered();
        if (index < 0 || index >= crates.size()) return;
        Crate crate = crates.get(index);
        if (rightClick) plugin.openings().open(player, crate, 1, false);
        else openPreview(player, crate, 0, false);
    }

    private void adminClick(Player player, int slot) {
        if (!player.hasPermission("plexoncrates.admin")) return;
        MenuConfig menus = plugin.menusConfig();
        if (slot == menus.slot("admin.reload")) {
            plugin.reloadFor(player);
            if (plugin.isEnabled()) openAdmin(player);
            return;
        }
        int index = menus.slots("admin.crate-slots").indexOf(slot);
        List<Crate> crates = plugin.crates().ordered();
        if (index >= 0 && index < crates.size()) openEditor(player, crates.get(index));
    }

    private void editorClick(Player player, String crateId, int slot) {
        if (!player.hasPermission("plexoncrates.admin")) return;
        MenuConfig menus = plugin.menusConfig();
        Crate crate = plugin.crates().find(crateId).orElse(null);
        if (crate == null) return;
        try {
            if (slot == menus.slot("editor.preview")) openPreview(player, crate, 0, true);
            else if (slot == menus.slot("editor.location")) {
                Block block = player.getTargetBlockExact(plugin.settings().targetDistance());
                if (block == null || block.getType().isAir()) {
                    plugin.messages().send(player, "target-required", Text.value("distance", plugin.settings().targetDistance()));
                    return;
                }
                plugin.locations().set(block, crate.id());
                plugin.displays().refresh();
                plugin.messages().send(player, "location-set", Text.component("crate", crate.displayName()));
                openEditor(player, crate);
            } else if (slot == menus.slot("editor.capture")) {
                ItemStack held = player.getInventory().getItemInMainHand();
                if (held.getType().isAir()) {
                    plugin.messages().send(player, "hold-item");
                    return;
                }
                String reward = plugin.crates().addGeneratedCapturedReward(crate.id(), held, 10.0);
                plugin.messages().send(player, "reward-added", Text.value("reward", reward),
                        Text.component("crate", crate.displayName()), Text.value("weight", 10));
                openEditor(player, plugin.crates().find(crate.id()).orElseThrow());
            } else if (slot == menus.slot("editor.rewards")) openRewards(player, crate, 0);
            else if (slot == menus.slot("editor.key")) {
                plugin.keys().give(player, crate.keyId(), 1);
                plugin.messages().send(player, "key-given", Text.value("amount", 1),
                        Text.component("key", keyName(crate)), Text.value("player", player.getName()));
            } else if (slot == menus.slot("editor.back")) openAdmin(player);
        } catch (Exception error) {
            plugin.configError(player, error);
        }
    }

    private void rewardsClick(Player player, MenuHolder holder, int slot, boolean delete) {
        MenuConfig menus = plugin.menusConfig();
        Crate crate = plugin.crates().find(holder.crateId()).orElse(null);
        if (crate == null) return;
        if (slot == menus.slot("preview.back")) openEditor(player, crate);
        else if (slot == menus.slot("preview.previous")) openRewards(player, crate, holder.page() - 1);
        else if (slot == menus.slot("preview.next")) openRewards(player, crate, holder.page() + 1);
        else if (delete) {
            int index = menus.slots("preview.reward-slots").indexOf(slot);
            int rewardIndex = holder.page() * menus.slots("preview.reward-slots").size() + index;
            List<CrateReward> rewards = crate.orderedRewards();
            if (index >= 0 && rewardIndex < rewards.size()) openConfirmDelete(player, crate, rewards.get(rewardIndex), holder.page());
        }
    }

    private void openConfirmDelete(Player player, Crate crate, CrateReward reward, int returnPage) {
        MenuConfig menus = plugin.menusConfig();
        MenuHolder holder = new MenuHolder(MenuHolder.Kind.CONFIRM_DELETE, crate.id(), reward.id(), returnPage, true);
        Inventory inventory = create(holder, menus.size("confirm-delete"), menus.title("confirm-delete"));
        fill(inventory);
        inventory.setItem(13, reward.displayCopy());
        inventory.setItem(menus.slot("confirm-delete.confirm"), menus.item("confirm-delete.confirm"));
        inventory.setItem(menus.slot("confirm-delete.cancel"), menus.item("confirm-delete.cancel"));
        player.openInventory(inventory);
    }

    private void confirmClick(Player player, MenuHolder holder, int slot) {
        MenuConfig menus = plugin.menusConfig();
        Crate crate = plugin.crates().find(holder.crateId()).orElse(null);
        if (crate == null) return;
        if (slot == menus.slot("confirm-delete.cancel")) {
            openRewards(player, crate, holder.page());
            return;
        }
        if (slot != menus.slot("confirm-delete.confirm")) return;
        try {
            plugin.crates().removeReward(crate.id(), holder.rewardId());
            plugin.messages().send(player, "reward-removed", Text.value("reward", holder.rewardId()),
                    Text.component("crate", crate.displayName()));
            openRewards(player, plugin.crates().find(crate.id()).orElseThrow(), holder.page());
        } catch (Exception error) {
            plugin.configError(player, error);
        }
    }

    private Inventory create(MenuHolder holder, int size, Component title) {
        Inventory inventory = Bukkit.createInventory(holder, size, title);
        holder.attach(inventory);
        return inventory;
    }

    private void fill(Inventory inventory) {
        ItemStack filler = plugin.menusConfig().item("filler");
        for (int slot = 0; slot < inventory.getSize(); slot++) inventory.setItem(slot, filler);
    }

    private static void appendLore(ItemStack item, List<Component> additions) {
        item.editMeta(meta -> {
            var lore = new ArrayList<Component>();
            if (meta.lore() != null) lore.addAll(meta.lore());
            additions.stream().map(line -> line.decoration(TextDecoration.ITALIC, false)).forEach(lore::add);
            meta.lore(lore);
        });
    }

    private Component keyName(Crate crate) {
        return plugin.keys().template(crate.keyId()).map(item -> {
            Component name = item.getItemMeta().displayName();
            return name == null ? Text.parse("<white>" + crate.keyId() + " key</white>") : name;
        }).orElseGet(() -> Text.parse("<white>" + crate.keyId() + " key</white>"));
    }

    private static ItemStack randomDisplay(List<CrateReward> rewards) {
        return rewards.get(ThreadLocalRandom.current().nextInt(rewards.size())).displayCopy();
    }

    private static String format(double value) {
        String formatted = String.format(Locale.ROOT, "%.3f", value);
        return formatted.replaceAll("0+$", "").replaceAll("\\.$", "");
    }
}
