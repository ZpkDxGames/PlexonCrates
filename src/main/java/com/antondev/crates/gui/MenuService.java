package com.antondev.crates.gui;

import com.antondev.crates.PlexonCrates;
import com.antondev.crates.config.MenuConfig;
import com.antondev.crates.config.Text;
import com.antondev.crates.item.ItemSnapshotCodec;
import com.antondev.crates.model.Crate;
import com.antondev.crates.model.CrateReward;
import com.antondev.crates.service.ChanceAllocator;
import com.antondev.crates.service.CrateRegistry;
import com.antondev.crates.service.DraftSessionService;
import com.antondev.crates.service.RewardSelector;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitRunnable;

public final class MenuService implements Listener {
    private final PlexonCrates plugin;
    private final ItemSnapshotCodec itemSnapshots = new ItemSnapshotCodec();
    private final NamespacedKey editorItem;
    private final Map<UUID, String> rewardSearch = new ConcurrentHashMap<>();

    public MenuService(PlexonCrates plugin) {
        this.plugin = plugin;
        this.editorItem = new NamespacedKey(plugin, "editor_item");
    }

    public void openBrowser(Player player) {
        MenuConfig menus = plugin.menusConfig();
        MenuHolder holder = new MenuHolder(MenuHolder.Kind.BROWSER, "", "", 0, false,
                plugin.runtime().snapshot().revision());
        Inventory inventory = create(holder, menus.size("browser"), menus.title("browser"));
        fill(inventory);
        List<Integer> slots = menus.slots("browser.crate-slots");
        List<Crate> crates = plugin.runtime().ordered();
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
        open(player, inventory);
    }

    public void openPreview(Player player, Crate crate, int requestedPage, boolean adminOrigin) {
        MenuConfig menus = plugin.menusConfig();
        List<Integer> rewardSlots = menus.slots("preview.reward-slots");
        List<CrateReward> rewards = crate.orderedRewards().stream().filter(CrateReward::enabled).toList();
        int pages = Math.max(1, (rewards.size() + rewardSlots.size() - 1) / rewardSlots.size());
        int page = Math.max(0, Math.min(requestedPage, pages - 1));
        MenuHolder holder = new MenuHolder(MenuHolder.Kind.PREVIEW, crate.id(), "", page, adminOrigin,
                adminOrigin ? 0 : plugin.runtime().crateRevision(crate.id()));
        if (adminOrigin) holder.bindDraft(plugin.adminMenus().ensureDraft(player, crate.id()));
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
                lore.add(Text.parse(line,
                        Text.value("eligible_chance", format(chance)),
                        Text.value("base_chance", format(reward.baseChancePercent())),
                        Text.value("chance", format(chance)),
                        Text.value("weight", format(reward.baseChancePercent()))));
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
        open(player, inventory);
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
        DraftSessionService.View draft = plugin.adminMenus().ensureDraft(player, crate.id());
        MenuConfig menus = plugin.menusConfig();
        List<Integer> slots = menus.slots("reward-pool.reward-slots");
        String query = rewardSearch.getOrDefault(player.getUniqueId(), "");
        List<CrateReward> rewards = crate.orderedRewards().stream().filter(reward -> query.isBlank()
                || reward.id().contains(query)
                || Text.serialize(reward.displayName()).toLowerCase(Locale.ROOT).contains(query)).toList();
        int pages = Math.max(1, (rewards.size() + slots.size() - 1) / slots.size());
        int page = Math.max(0, Math.min(requestedPage, pages - 1));
        MenuHolder holder = new MenuHolder(MenuHolder.Kind.REWARDS, crate.id(), "", page, true);
        holder.bindDraft(draft);
        Inventory inventory = create(holder, menus.size("reward-pool"),
                menus.title("reward-pool", Text.component("crate", crate.displayName())));
        fill(inventory);
        int start = page * slots.size();
        for (int index = 0; index < slots.size(); index++) {
            int rewardIndex = start + index;
            inventory.setItem(slots.get(index), rewardIndex < rewards.size()
                    ? rewards.get(rewardIndex).displayCopy()
                    : control("reward-pool.empty"));
        }
        int totalBasisPoints = crate.rewards().values().stream().filter(CrateReward::enabled)
                .mapToInt(CrateReward::chanceBasisPoints).sum();
        Component health = totalBasisPoints == ChanceAllocator.TOTAL_BASIS_POINTS
                ? Text.parse("<green>Healthy</green>") : Text.parse("<yellow>Needs balance</yellow>");
        inventory.setItem(menus.slot("reward-pool.add-special"), control("reward-pool.add-special"));
        inventory.setItem(menus.slot("reward-pool.search"), control("reward-pool.search"));
        inventory.setItem(menus.slot("reward-pool.previous"), control("reward-pool.previous"));
        inventory.setItem(menus.slot("reward-pool.back"), control("reward-pool.back"));
        ItemStack status = control("reward-pool.status", Text.value("count", crate.rewards().size()),
                Text.value("total", format(totalBasisPoints / 100.0)), Text.component("state", health));
        appendLore(status, draftLore(draft));
        inventory.setItem(menus.slot("reward-pool.status"), status);
        inventory.setItem(menus.slot("reward-pool.preview"), control("reward-pool.preview"));
        inventory.setItem(menus.slot("reward-pool.next"), control("reward-pool.next"));
        inventory.setItem(menus.slot("reward-pool.balance"), control("reward-pool.balance"));
        inventory.setItem(menus.slot("reward-pool.done"), control("reward-pool.done"));
        open(player, inventory);
    }

    public void refreshDraftState(Player player, String crateId) {
        Inventory top = player.getOpenInventory().getTopInventory();
        if (top == null || !(top.getHolder() instanceof MenuHolder holder)
                || !holder.crateId().equals(crateId)) return;
        if (holder.kind() == MenuHolder.Kind.EDITOR) {
            plugin.adminMenus().refreshDraftState(player, holder);
        } else if (holder.kind() == MenuHolder.Kind.REWARDS) {
            Crate crate = plugin.crates().find(crateId).orElse(null);
            DraftSessionService.View draft = plugin.draftSessions().view(player.getUniqueId(), crateId).orElse(null);
            if (crate != null && draft != null) updateRewardPoolStatus(holder, crate, draft);
        }
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
        open(player, inventory);

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
        open(player, inventory);
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
        open(player, inventory);
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
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (!accept(player, holder)) return;
        if (holder.kind() == MenuHolder.Kind.REWARDS) {
            rewardPoolClick(event, holder);
            return;
        }
        if (isAdministrative(holder.kind())) {
            plugin.adminMenus().handleClick(event, holder);
            return;
        }
        if (event.getClickedInventory() != event.getView().getTopInventory()) return;
        int slot = event.getRawSlot();
        MenuConfig menus = plugin.menusConfig();
        switch (holder.kind()) {
            case BROWSER -> browserClick(player, holder, slot, event.isRightClick());
            case PREVIEW -> {
                Crate crate = (holder.adminOrigin() ? plugin.crates().find(holder.crateId())
                        : plugin.runtime().find(holder.crateId())).orElse(null);
                if (crate == null) return;
                if (!holder.adminOrigin() && holder.revision() != plugin.runtime().crateRevision(crate.id())) {
                    plugin.messages().send(player, "opening-state-changed");
                    openPreview(player, crate, 0, false);
                    return;
                }
                if (slot == menus.slot("preview.open")) plugin.openings().open(player, crate, 1, false);
                else if (slot == menus.slot("preview.back")) {
                    if (holder.adminOrigin()) openEditor(player, crate); else openBrowser(player);
                } else if (slot == menus.slot("preview.previous")) openPreview(player, crate, holder.page() - 1, holder.adminOrigin());
                else if (slot == menus.slot("preview.next")) openPreview(player, crate, holder.page() + 1, holder.adminOrigin());
            }
            case ADMIN -> adminClick(player, slot);
            case EDITOR -> editorClick(player, holder.crateId(), slot);
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
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || !accept(player, holder)) return;
        if (holder.kind() == MenuHolder.Kind.REWARDS) rewardPoolDrag(event, holder);
        else if (isAdministrative(holder.kind())) plugin.adminMenus().handleDrag(event, holder);
    }

    @EventHandler
    public void close(InventoryCloseEvent event) {
        if (event.getInventory().getHolder() instanceof MenuHolder holder) {
            plugin.guiSessions().close(event.getPlayer().getUniqueId(), holder.sessionId());
        }
    }

    @EventHandler
    public void quit(PlayerQuitEvent event) {
        rewardSearch.remove(event.getPlayer().getUniqueId());
        plugin.guiSessions().clear(event.getPlayer().getUniqueId());
    }

    private void browserClick(Player player, MenuHolder holder, int slot, boolean rightClick) {
        MenuConfig menus = plugin.menusConfig();
        if (slot == menus.slot("browser.close")) {
            player.closeInventory();
            return;
        }
        if (holder.revision() != plugin.runtime().snapshot().revision()) {
            plugin.messages().send(player, "opening-state-changed");
            openBrowser(player);
            return;
        }
        int index = menus.slots("browser.crate-slots").indexOf(slot);
        List<Crate> crates = plugin.runtime().ordered();
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
                if (!plugin.adminMenus().requireWritableDraft(player, crate.id())) return;
                String rewardId = plugin.crates().addGeneratedCapturedReward(crate.id(), held, player.getName());
                plugin.adminMenus().saveDraftRevision(player, crate.id(), "REWARD", "Captured reward " + rewardId);
                CrateReward reward = plugin.crates().find(crate.id()).orElseThrow().rewards().get(rewardId);
                plugin.messages().send(player, "reward-added", Text.value("reward", rewardId),
                        Text.component("crate", crate.displayName()),
                        Text.value("chance", format(reward.baseChancePercent())),
                        Text.value("weight", format(reward.baseChancePercent())));
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

    private void rewardPoolClick(InventoryClickEvent event, MenuHolder holder) {
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (!player.hasPermission("plexoncrates.admin.rewards")) {
            plugin.messages().send(player, "no-permission");
            return;
        }
        MenuConfig menus = plugin.menusConfig();
        Crate crate = plugin.crates().find(holder.crateId()).orElse(null);
        if (crate == null) return;
        int topSize = event.getView().getTopInventory().getSize();
        if (event.isShiftClick() && event.getRawSlot() >= topSize
                && event.getRawSlot() < event.getView().countSlots()) {
            if (event.getAction() == InventoryAction.MOVE_TO_OTHER_INVENTORY) {
                ItemStack source = event.getCurrentItem();
                if (source == null || source.getType().isAir()) {
                    source = event.getView().getBottomInventory().getItem(event.getView().convertSlot(event.getRawSlot()));
                }
                capturePoolReward(player, crate, source);
            }
            return;
        }
        if (event.getClickedInventory() != event.getView().getTopInventory()) return;
        int slot = event.getRawSlot();
        List<Integer> rewardSlots = menus.slots("reward-pool.reward-slots");
        int visibleIndex = rewardSlots.indexOf(slot);
        if (visibleIndex >= 0) {
            List<CrateReward> rewards = filteredRewards(player, crate);
            int rewardIndex = holder.page() * rewardSlots.size() + visibleIndex;
            ItemStack cursor = event.getCursor();
            boolean placingCursor = cursor != null && !cursor.getType().isAir() && cursorPlacement(event.getAction());
            if (rewardIndex >= rewards.size()) {
                if (placingCursor) capturePoolReward(player, crate, cursor);
                return;
            }
            CrateReward reward = rewards.get(rewardIndex);
            if (placingCursor) {
                player.sendMessage(Text.parse("<yellow>That slot already contains a reward. Use an empty slot, or left-click it to edit.</yellow>"));
            } else if (event.isShiftClick() && event.isRightClick()) {
                openConfirmDelete(player, crate, reward, holder.page());
            } else if (event.isShiftClick()) {
                player.sendMessage(Text.parse("<gray>Reorder mode is not active. Left-click the reward for Quick Details.</gray>"));
            } else if (event.isRightClick()) {
                inspectReward(player, reward);
            } else if (event.isLeftClick()) {
                plugin.adminMenus().editReward(player, crate, reward);
            }
            return;
        }
        try {
            if (slot == menus.slot("reward-pool.add-special")) {
                plugin.adminMenus().beginSpecialReward(player, crate.id());
            } else if (slot == menus.slot("reward-pool.search")) {
                searchRewards(player, crate.id());
            } else if (slot == menus.slot("reward-pool.previous")) {
                openRewards(player, crate, holder.page() - 1);
            } else if (slot == menus.slot("reward-pool.back") || slot == menus.slot("reward-pool.done")) {
                openEditor(player, crate);
            } else if (slot == menus.slot("reward-pool.preview")) {
                openPreview(player, crate, 0, true);
            } else if (slot == menus.slot("reward-pool.next")) {
                openRewards(player, crate, holder.page() + 1);
            } else if (slot == menus.slot("reward-pool.balance")) {
                balanceRewardPool(player, crate, event);
            } else if (slot == menus.slot("reward-pool.status")) {
                DraftSessionService.View draft = plugin.adminMenus().ensureDraft(player, crate.id());
                if (draft.state() == DraftSessionService.State.SAVE_FAILED) {
                    plugin.adminMenus().retryDraft(player, crate.id());
                } else if (draft.state() == DraftSessionService.State.READ_ONLY
                        && player.hasPermission("plexoncrates.admin.takeover")) {
                    plugin.adminMenus().openTakeoverConfirmation(player, crate.id(), "rewards", holder.page());
                }
            }
        } catch (Exception error) {
            plugin.configError(player, error);
        }
    }

    private void rewardPoolDrag(InventoryDragEvent event, MenuHolder holder) {
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)
                || !player.hasPermission("plexoncrates.admin.rewards")) return;
        Crate crate = plugin.crates().find(holder.crateId()).orElse(null);
        ItemStack source = event.getOldCursor();
        if (crate == null || source == null || source.getType().isAir()) return;
        List<Integer> body = plugin.menusConfig().slots("reward-pool.reward-slots");
        List<CrateReward> rewards = filteredRewards(player, crate);
        boolean touchedOccupied = false;
        for (int rawSlot : event.getRawSlots().stream().sorted().toList()) {
            int visibleIndex = body.indexOf(rawSlot);
            if (visibleIndex < 0) continue;
            int rewardIndex = holder.page() * body.size() + visibleIndex;
            if (rewardIndex >= rewards.size()) {
                capturePoolReward(player, crate, source);
                return;
            }
            touchedOccupied = true;
        }
        if (touchedOccupied) {
            player.sendMessage(Text.parse("<yellow>That slot already contains a reward. Drag across an empty slot to add this item.</yellow>"));
        }
    }

    private void capturePoolReward(Player player, Crate crate, ItemStack source) {
        if (source == null || source.getType().isAir() || protectedInput(source)) return;
        if (!plugin.adminMenus().requireWritableDraft(player, crate.id())) return;
        try {
            itemSnapshots.capture(source);
            String rewardId = plugin.crates().addGeneratedCapturedReward(crate.id(), source, player.getName());
            plugin.adminMenus().saveDraftRevision(player, crate.id(), "REWARD", "Captured reward " + rewardId);
            rewardSearch.remove(player.getUniqueId());
            Crate updated = plugin.crates().find(crate.id()).orElseThrow();
            CrateReward reward = updated.rewards().get(rewardId);
            plugin.messages().send(player, "reward-added", Text.value("reward", rewardId),
                    Text.component("crate", updated.displayName()),
                    Text.value("chance", format(reward.baseChancePercent())),
                    Text.value("weight", format(reward.baseChancePercent())));
            int index = updated.orderedRewards().indexOf(reward);
            openRewards(player, updated, Math.max(0, index / plugin.menusConfig().slots("reward-pool.reward-slots").size()));
        } catch (Exception error) {
            plugin.configError(player, error);
        }
    }

    private void searchRewards(Player player, String crateId) {
        plugin.editSessions().request(player,
                Text.parse("<aqua>Enter a reward name/ID search, or <white>-</white> to clear:</aqua>"),
                (target, value) -> {
                    String query = value.equals("-") ? "" : value.toLowerCase(Locale.ROOT).trim();
                    if (query.length() > 64) throw new IllegalArgumentException("Search text is too long");
                    rewardSearch.put(target.getUniqueId(), query);
                    Crate current = plugin.crates().find(crateId)
                            .orElseThrow(() -> new IllegalArgumentException("Crate no longer exists"));
                    openRewards(target, current, 0);
                });
    }

    private void balanceRewardPool(Player player, Crate crate, InventoryClickEvent event) throws Exception {
        if (!plugin.adminMenus().requireWritableDraft(player, crate.id())) return;
        CrateRegistry.ChanceBalanceMode mode;
        if (event.isShiftClick() && event.isRightClick()) {
            mode = CrateRegistry.ChanceBalanceMode.NORMALIZE_UNLOCKED;
        } else if (event.isShiftClick()) {
            mode = CrateRegistry.ChanceBalanceMode.RARITY_CURVE;
        } else if (event.isRightClick()) {
            mode = CrateRegistry.ChanceBalanceMode.EQUAL;
        } else {
            mode = CrateRegistry.ChanceBalanceMode.PRESERVE_RELATIVE;
        }
        plugin.crates().balanceChances(crate.id(), mode, player.getName());
        plugin.adminMenus().saveDraftRevision(player, crate.id(), "CHANCE",
                "Balanced reward chances using " + mode.name());
        player.sendMessage(Text.parse("<green>Balanced reward chances:</green> <white>" + mode.name()
                .toLowerCase(Locale.ROOT).replace('_', ' ') + "</white><green>.</green>"));
        openRewards(player, plugin.crates().find(crate.id()).orElseThrow(), 0);
    }

    private List<CrateReward> filteredRewards(Player player, Crate crate) {
        String query = rewardSearch.getOrDefault(player.getUniqueId(), "");
        return crate.orderedRewards().stream().filter(reward -> query.isBlank()
                || reward.id().contains(query)
                || Text.serialize(reward.displayName()).toLowerCase(Locale.ROOT).contains(query)).toList();
    }

    private boolean protectedInput(ItemStack item) {
        return plugin.wand().isWand(item) || (item.hasItemMeta() && item.getItemMeta().getPersistentDataContainer()
                .has(editorItem, PersistentDataType.BYTE));
    }

    private static boolean cursorPlacement(InventoryAction action) {
        return action == InventoryAction.PLACE_ALL || action == InventoryAction.PLACE_ONE
                || action == InventoryAction.PLACE_SOME || action == InventoryAction.SWAP_WITH_CURSOR;
    }

    private void inspectReward(Player player, CrateReward reward) {
        String fingerprint = "not an item reward";
        int size = 0;
        if (!reward.itemCopies().isEmpty()) {
            ItemSnapshotCodec.Snapshot snapshot = itemSnapshots.capture(reward.itemCopies().getFirst());
            fingerprint = snapshot.shortFingerprint();
            size = snapshot.serializedSize();
        }
        player.sendMessage(Text.parse("<aqua>Reward inspector:</aqua> <white>" + reward.id()
                + "</white> <dark_gray>•</dark_gray> <gray>base chance</gray> <yellow>"
                + format(reward.baseChancePercent()) + "%</yellow> <dark_gray>•</dark_gray> <gray>fingerprint</gray> <white>"
                + fingerprint + "</white> <dark_gray>•</dark_gray> <gray>bytes</gray> <white>" + size + "</white>"));
    }

    private void openConfirmDelete(Player player, Crate crate, CrateReward reward, int returnPage) {
        MenuConfig menus = plugin.menusConfig();
        MenuHolder holder = new MenuHolder(MenuHolder.Kind.CONFIRM_DELETE, crate.id(), reward.id(), returnPage, true);
        plugin.draftSessions().view(player.getUniqueId(), crate.id()).ifPresent(holder::bindDraft);
        Inventory inventory = create(holder, menus.size("confirm-delete"), menus.title("confirm-delete"));
        fill(inventory);
        inventory.setItem(13, reward.displayCopy());
        inventory.setItem(menus.slot("confirm-delete.confirm"), menus.item("confirm-delete.confirm"));
        inventory.setItem(menus.slot("confirm-delete.cancel"), menus.item("confirm-delete.cancel"));
        open(player, inventory);
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
            if (!plugin.adminMenus().requireWritableDraft(player, crate.id())) return;
            plugin.crates().removeReward(crate.id(), holder.rewardId(), player.getName());
            plugin.adminMenus().saveDraftRevision(player, crate.id(), "REWARD",
                    "Removed reward " + holder.rewardId());
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

    private void open(Player player, Inventory inventory) {
        player.openInventory(inventory);
        if (inventory.getHolder() instanceof MenuHolder holder
                && player.getOpenInventory().getTopInventory() == inventory) {
            plugin.guiSessions().activate(player.getUniqueId(), holder);
        }
    }

    private boolean accept(Player player, MenuHolder holder) {
        GuiSessionService.Validation validation = plugin.guiSessions()
                .validate(player, holder, plugin.draftSessions());
        if (validation == GuiSessionService.Validation.CURRENT) return true;
        plugin.messages().send(player, "gui-stale");
        return false;
    }

    private void fill(Inventory inventory) {
        ItemStack filler = plugin.menusConfig().item("filler");
        for (int slot = 0; slot < inventory.getSize(); slot++) inventory.setItem(slot, filler);
    }

    private ItemStack control(String path,
                              net.kyori.adventure.text.minimessage.tag.resolver.TagResolver... tags) {
        ItemStack item = plugin.menusConfig().item(path, tags);
        item.editMeta(meta -> meta.getPersistentDataContainer()
                .set(editorItem, PersistentDataType.BYTE, (byte) 1));
        return item;
    }

    private void updateRewardPoolStatus(MenuHolder holder, Crate crate, DraftSessionService.View draft) {
        holder.advanceDraft(draft);
        Inventory inventory = holder.getInventory();
        int totalBasisPoints = crate.rewards().values().stream().filter(CrateReward::enabled)
                .mapToInt(CrateReward::chanceBasisPoints).sum();
        Component health = totalBasisPoints == ChanceAllocator.TOTAL_BASIS_POINTS
                ? Text.parse("<green>Healthy</green>") : Text.parse("<yellow>Needs balance</yellow>");
        ItemStack status = control("reward-pool.status", Text.value("count", crate.rewards().size()),
                Text.value("total", format(totalBasisPoints / 100.0)), Text.component("state", health));
        appendLore(status, draftLore(draft));
        inventory.setItem(plugin.menusConfig().slot("reward-pool.status"), status);
    }

    private static List<Component> draftLore(DraftSessionService.View draft) {
        Component state = switch (draft.state()) {
            case LOADING -> Text.parse("<yellow>Loading</yellow>");
            case SAVING -> Text.parse("<yellow>Saving</yellow>");
            case PUBLISHING -> Text.parse("<aqua>Publishing</aqua>");
            case SAVED -> Text.parse("<green>Saved</green>");
            case SAVE_FAILED -> Text.parse("<red>Save failed</red>");
            case READ_ONLY -> Text.parse("<gold>Read only</gold>");
        };
        var lore = new ArrayList<Component>();
        lore.add(Component.empty());
        lore.add(Text.parse("<gray>Draft:</gray> <state>", Text.component("state", state)));
        lore.add(Text.parse("<gray>Editor:</gray> <white><owner></white>",
                Text.value("owner", draft.ownerName().isBlank() ? "loading" : draft.ownerName())));
        lore.add(Text.parse("<gray>Revision:</gray> <white><revision></white>",
                Text.value("revision", draft.revision())));
        if (draft.state() == DraftSessionService.State.SAVE_FAILED) {
            lore.add(Text.parse("<yellow>Click to retry the latest snapshot.</yellow>"));
        } else if (draft.state() == DraftSessionService.State.READ_ONLY) {
            lore.add(Text.parse("<yellow>Click to request a confirmed takeover.</yellow>"));
        }
        return List.copyOf(lore);
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
                    CONFIRM_UNLINK, CONFIRM_CRATE_DELETE, CONFIRM_KEY_DELETE, CONFIRM_TAKEOVER -> true;
            default -> false;
        };
    }

    private record SummaryEntry(CrateReward reward, int count) {}
}
