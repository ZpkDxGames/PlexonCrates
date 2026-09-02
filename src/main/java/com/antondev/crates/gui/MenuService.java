package com.antondev.crates.gui;

import com.antondev.crates.PlexonCrates;
import com.antondev.crates.config.MenuConfig;
import com.antondev.crates.config.Text;
import com.antondev.crates.model.Crate;
import com.antondev.crates.model.CrateReward;
import com.antondev.crates.service.RewardSelector;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
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
        long now = System.currentTimeMillis();
        boolean bypassLimits = player.hasPermission("plexoncrates.bypass.limit");
        List<CrateReward> eligible = rewards.stream().filter(reward -> previewEligible(player, crate, reward, now, bypassLimits)).toList();
        int start = page * rewardSlots.size();
        for (int slotIndex = 0; slotIndex < rewardSlots.size() && start + slotIndex < rewards.size(); slotIndex++) {
            CrateReward reward = rewards.get(start + slotIndex);
            ItemStack display = reward.displayCopy();
            boolean canWin = eligible.contains(reward);
            double chance = canWin ? RewardSelector.chance(reward, eligible) : 0;
            var lore = new ArrayList<Component>();
            for (String line : menus.strings("preview.reward-lore")) {
                lore.add(Text.parse(line, Text.value("chance", format(chance)), Text.value("weight", format(reward.weight()))));
            }
            if (!canWin) lore.add(Text.parse("<red>This reward is currently unavailable to you.</red>"));
            if (crate.pity().enabled() && (crate.pity().rewardIds().contains(reward.id())
                    || crate.pity().rarity() == reward.rarity())) {
                lore.add(Text.parse("<light_purple>Guaranteed-pool reward</light_purple>"));
            }
            appendLore(display, lore);
            inventory.setItem(rewardSlots.get(slotIndex), display);
        }
        ItemStack open = menus.item("preview.open", Text.component("crate", crate.displayName()),
                Text.value("keys", plugin.keys().count(player, crate.keyId())), Text.component("key", keyName(crate)));
        inventory.setItem(menus.slot("preview.open"), open);
        if (crate.pity().enabled()) {
            int remaining = plugin.rewardStates().pityRemaining(player.getUniqueId(), crate);
            appendLore(open, List.of(Text.parse("<light_purple>Guaranteed in " + remaining + " opening"
                    + (remaining == 1 ? "" : "s") + ".</light_purple>")));
            inventory.setItem(menus.slot("preview.open"), open);
        }
        inventory.setItem(menus.slot("preview.back"), menus.item("preview.back"));
        if (page > 0) inventory.setItem(menus.slot("preview.previous"), menus.item("preview.previous"));
        if (page + 1 < pages) inventory.setItem(menus.slot("preview.next"), menus.item("preview.next"));
        player.openInventory(inventory);
    }

    public void openAdmin(Player player) {
        plugin.adminMenus().openDashboard(player);
    }

    public void openEditor(Player player, Crate crate) {
        plugin.adminMenus().openCrateEditor(player, crate);
    }

    public void openWandSelector(Player player, int page) {
        plugin.adminMenus().openWandSelector(player, page);
    }

    public void openUnlinkConfirmation(Player player, com.antondev.crates.service.LocationStore.Link link) {
        plugin.adminMenus().openUnlinkConfirmation(player, link);
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
                    Text.parse("<aqua>Left-click edit • Right-click test • Shift-left copy</aqua>"),
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

    public void reveal(Player player, Crate crate, CrateReward selected, Runnable completed) {
        MenuConfig menus = plugin.menusConfig();
        MenuHolder holder = new MenuHolder(MenuHolder.Kind.OPENING, crate.id(), selected.id(), 0, false);
        Inventory inventory = create(holder, menus.size("opening"),
                menus.title("opening", Text.component("crate", crate.displayName())));
        fill(inventory);
        inventory.setItem(menus.slot("opening.marker-top-slot"), menus.item("opening.marker"));
        inventory.setItem(menus.slot("opening.marker-bottom-slot"), menus.item("opening.marker"));
        inventory.setItem(menus.slot("opening.center-slot"), new ItemStack(org.bukkit.Material.GRAY_STAINED_GLASS_PANE));
        player.openInventory(inventory);
        player.playSound(player.getLocation(), plugin.settings().openingSound(),
                plugin.settings().soundVolume(), Math.max(0.5f, plugin.settings().soundPitch() - 0.25f));
        long delay = Math.max(10L, Math.min(plugin.settings().animationDuration(), 40));
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline() && player.getOpenInventory().getTopInventory() != null
                    && player.getOpenInventory().getTopInventory().getHolder() == holder) {
                inventory.setItem(menus.slot("opening.center-slot"), selected.displayCopy());
            }
            completed.run();
        }, delay);
    }

    public void openSummary(Player player, Crate crate, List<CrateReward> selected) {
        MenuConfig menus = plugin.menusConfig();
        MenuHolder holder = new MenuHolder(MenuHolder.Kind.SUMMARY, crate.id(), "", 0, false);
        Inventory inventory = create(holder, menus.size("summary"),
                menus.title("summary", Text.component("crate", crate.displayName()), Text.value("amount", selected.size())));
        fill(inventory);
        var grouped = new LinkedHashMap<String, SummaryEntry>();
        for (CrateReward reward : selected) {
            grouped.compute(reward.id(), (ignored, current) -> current == null
                    ? new SummaryEntry(reward, 1) : new SummaryEntry(current.reward(), current.count() + 1));
        }
        List<Integer> slots = menus.slots("summary.reward-slots");
        int index = 0;
        for (SummaryEntry entry : grouped.values()) {
            if (index >= slots.size()) break;
            ItemStack display = entry.reward().displayCopy();
            appendLore(display, List.of(Component.empty(),
                    Text.parse("<gray>Received</gray> <dark_gray>»</dark_gray> <yellow>" + entry.count() + "x</yellow>")));
            inventory.setItem(slots.get(index++), display);
        }
        inventory.setItem(menus.slot("summary.close"), menus.item("summary.close"));
        player.openInventory(inventory);
    }

    public void closeAll() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getOpenInventory().getTopInventory() != null
                    && player.getOpenInventory().getTopInventory().getHolder() instanceof MenuHolder holder
                    && holder.kind() != MenuHolder.Kind.OPENING) player.closeInventory();
        }
    }

    @EventHandler
    public void click(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof MenuHolder holder)) return;
        if (isAdministrative(holder.kind())) {
            plugin.adminMenus().handleClick(event, holder);
            return;
        }
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
            case REWARDS -> rewardsClick(player, holder, slot, event.isRightClick(), event.isShiftClick());
            case CONFIRM_DELETE -> confirmClick(player, holder, slot);
            case OPENING -> { }
            case SUMMARY -> {
                if (slot == menus.slot("summary.close")) player.closeInventory();
            }
            default -> { }
        }
    }

    @EventHandler
    public void drag(InventoryDragEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof MenuHolder holder)) return;
        if (isAdministrative(holder.kind())) plugin.adminMenus().handleDrag(event, holder);
        else event.setCancelled(true);
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

    private void rewardsClick(Player player, MenuHolder holder, int slot, boolean rightClick, boolean shiftClick) {
        if (!player.hasPermission("plexoncrates.admin.rewards")) {
            plugin.messages().send(player, "no-permission");
            return;
        }
        MenuConfig menus = plugin.menusConfig();
        Crate crate = plugin.crates().find(holder.crateId()).orElse(null);
        if (crate == null) return;
        if (slot == menus.slot("preview.back")) openEditor(player, crate);
        else if (slot == menus.slot("preview.previous")) openRewards(player, crate, holder.page() - 1);
        else if (slot == menus.slot("preview.next")) openRewards(player, crate, holder.page() + 1);
        else {
            List<Integer> rewardSlots = menus.slots("preview.reward-slots");
            int index = rewardSlots.indexOf(slot);
            int rewardIndex = holder.page() * rewardSlots.size() + index;
            List<CrateReward> rewards = crate.orderedRewards();
            if (index < 0 || rewardIndex >= rewards.size()) return;
            CrateReward reward = rewards.get(rewardIndex);
            if (rightClick && shiftClick) openConfirmDelete(player, crate, reward, holder.page());
            else if (rightClick) plugin.openings().testDeliver(player, crate, reward);
            else if (shiftClick) copyReward(player, crate, reward);
            else plugin.adminMenus().editReward(player, crate, reward);
        }
    }

    private void copyReward(Player player, Crate source, CrateReward reward) {
        plugin.editSessions().request(player, Text.parse("<aqua>Enter <white>target_crate,new_reward_id</white>:</aqua>"), (target, value) -> {
            String[] parts = value.split(",", -1);
            if (parts.length != 2) throw new IllegalArgumentException("Use target_crate,new_reward_id");
            plugin.crates().copyReward(source.id(), reward.id(), parts[0].trim(), parts[1].trim(), target.getName());
            Crate destination = plugin.crates().find(parts[0].trim()).orElseThrow();
            openRewards(target, destination, 0);
        });
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

    private boolean previewEligible(Player player, Crate crate, CrateReward reward, long now, boolean bypassLimits) {
        return reward.eligible(player) && reward.hasDelivery()
                && (reward.money() <= 0 || (plugin.settings().vaultEnabled() && plugin.openings().economyAvailable()))
                && plugin.rewardStates().eligible(player.getUniqueId(), crate, reward, now, bypassLimits);
    }

    private static boolean isAdministrative(MenuHolder.Kind kind) {
        return switch (kind) {
            case ADMIN, EDITOR, CRATE_LIST, KEY_LIST, KEY_TEMPLATE, KEY_SELECT, REWARD_BUILDER,
                    LOCATIONS, STATISTICS, SYSTEM, GLOBAL_REWARDS, WAND_SELECT,
                    CONFIRM_UNLINK, CONFIRM_CRATE_DELETE, CONFIRM_KEY_DELETE -> true;
            default -> false;
        };
    }

    private record SummaryEntry(CrateReward reward, int count) {}
}
