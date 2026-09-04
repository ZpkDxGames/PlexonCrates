package com.antondev.crates.gui;

import com.antondev.crates.PlexonCrates;
import com.antondev.crates.api.event.CrateUnlinkEvent;
import com.antondev.crates.config.MenuConfig;
import com.antondev.crates.config.Text;
import com.antondev.crates.database.DatabaseService;
import com.antondev.crates.domain.crate.CrateState;
import com.antondev.crates.domain.crate.AnimationType;
import com.antondev.crates.domain.key.ExternalKeyDescriptor;
import com.antondev.crates.domain.key.KeyDefinition;
import com.antondev.crates.domain.reward.RewardRarity;
import com.antondev.crates.domain.reward.RewardLimits;
import com.antondev.crates.domain.reward.RewardPresentation;
import com.antondev.crates.model.BlockPosition;
import com.antondev.crates.model.Crate;
import com.antondev.crates.model.CrateReward;
import com.antondev.crates.service.CrateRegistry;
import com.antondev.crates.service.DraftSessionService;
import com.antondev.crates.service.LocationStore;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

/** Administrative menu router. Functional actions live in holders, never display text. */
public final class AdminMenuService {
    private final PlexonCrates plugin;
    private final NamespacedKey editorItem;
    private final Map<UUID, String> crateSearch = new ConcurrentHashMap<>();

    public AdminMenuService(PlexonCrates plugin) {
        this.plugin = plugin;
        this.editorItem = new NamespacedKey(plugin, "editor_item");
    }

    public void openDashboard(Player player) {
        MenuConfig menus = plugin.menusConfig();
        MenuHolder holder = new MenuHolder(MenuHolder.Kind.ADMIN, "", "", 0, true);
        Inventory inventory = create(holder, menus.size("admin"), menus.title("admin"));
        fill(inventory);
        for (String action : List.of("crates", "keys", "locations", "rewards", "statistics", "system", "close")) {
            int slot = menus.slot("admin." + action);
            inventory.setItem(slot, menus.item("admin." + action));
            holder.bind(slot, action);
        }
        open(player, inventory);
    }

    public void openCrates(Player player, int requestedPage) {
        MenuConfig menus = plugin.menusConfig();
        List<Integer> slots = menus.slots("crate-list.entry-slots");
        String query = crateSearch.getOrDefault(player.getUniqueId(), "");
        List<Crate> crates = plugin.crates().orderedAdmin().stream().filter(crate -> query.isBlank()
                || crate.id().contains(query)
                || Text.serialize(crate.displayName()).toLowerCase(Locale.ROOT).contains(query)).toList();
        int page = page(requestedPage, crates.size(), slots.size());
        MenuHolder holder = new MenuHolder(MenuHolder.Kind.CRATE_LIST, "", "", page, true);
        Inventory inventory = create(holder, menus.size("crate-list"), menus.title("crate-list", Text.value("page", page + 1)));
        fill(inventory);
        int start = page * slots.size();
        for (int index = 0; index < slots.size() && start + index < crates.size(); index++) {
            Crate crate = crates.get(start + index);
            ItemStack icon = crate.iconCopy();
            List<String> issues = plugin.crates().publishingIssues(crate.id(), plugin.keys());
            appendLore(icon, List.of(Component.empty(),
                    Text.parse("<gray>ID</gray> <dark_gray>»</dark_gray> <white>" + crate.id() + "</white>"),
                    Text.parse("<gray>State</gray> <dark_gray>»</dark_gray> <yellow>" + crate.state() + "</yellow>"),
                    Text.parse("<gray>Rewards</gray> <dark_gray>»</dark_gray> <white>" + crate.rewards().size() + "</white>"),
                    Text.parse("<gray>World links</gray> <dark_gray>»</dark_gray> <white>" + plugin.locations().count(crate.id()) + "</white>"),
                    issues.isEmpty() ? Text.parse("<green>Ready for publication.</green>")
                            : Text.parse("<yellow>Setup warnings:</yellow> <white>" + issues.size() + "</white>"),
                    Text.parse("<dark_gray>Shift-left exports this definition.</dark_gray>")));
            int slot = slots.get(index);
            inventory.setItem(slot, icon);
            holder.bind(slot, "edit-crate", crate.id());
        }
        addNavigation(inventory, holder, "crate-list", page, crates.size(), slots.size(), "create", "search", "import");
        open(player, inventory);
    }

    public void openCrateEditor(Player player, Crate crate) {
        DraftSessionService.View draft = ensureDraft(player, crate.id());
        MenuConfig menus = plugin.menusConfig();
        MenuHolder holder = new MenuHolder(MenuHolder.Kind.EDITOR, crate.id(), "", 0, true);
        Inventory inventory = create(holder, menus.size("editor"), menus.title("editor", Text.component("crate", crate.displayName())));
        fill(inventory);
        ItemStack icon = crate.iconCopy();
        appendLore(icon, List.of(Component.empty(), Text.parse("<yellow>Drag or cursor-click an exact replacement icon here.</yellow>")));
        inventory.setItem(4, icon);
        holder.bind(4, "capture-icon", crate.id());
        var tags = new net.kyori.adventure.text.minimessage.tag.resolver.TagResolver[]{
                Text.value("order", crate.displayOrder()), Text.value("animation", crate.animation()),
                Text.value("cooldown", crate.cooldownSeconds()), Text.value("bulk", crate.bulkEnabled()),
                Text.value("bulk_max", crate.bulkMaximum()),
                Text.value("permission", crate.permission().isBlank() ? "none" : crate.permission())};
        for (String action : List.of("preview", "rename", "key", "rewards", "description", "order",
                "create-reward", "wand", "opening", "display", "access", "disable",
                "publish", "archive", "clone", "back", "delete")) {
            int slot = menus.slot("editor." + action);
            inventory.setItem(slot, menus.item("editor." + action, tags));
            holder.bind(slot, action, crate.id());
        }
        installDraftControls(player, inventory, holder, draft);
        open(player, inventory);
    }

    public void refreshDraftState(Player player, MenuHolder holder) {
        if (holder.kind() != MenuHolder.Kind.EDITOR
                || player.getOpenInventory().getTopInventory().getHolder() != holder) return;
        plugin.draftSessions().view(player.getUniqueId(), holder.crateId())
                .ifPresent(view -> installDraftControls(player, holder.getInventory(), holder, view));
    }

    public void openTakeoverConfirmation(Player player, String crateId, String returnScreen, int returnPage) {
        MenuConfig menus = plugin.menusConfig();
        MenuHolder holder = new MenuHolder(MenuHolder.Kind.CONFIRM_TAKEOVER, crateId, returnScreen, returnPage, true);
        Inventory inventory = create(holder, menus.size("confirm-takeover"),
                menus.title("confirm-takeover", Text.value("crate_id", crateId)));
        fill(inventory);
        put(inventory, holder, "confirm-takeover", "confirm", "confirm-takeover", returnScreen);
        put(inventory, holder, "confirm-takeover", "cancel", "cancel-takeover", returnScreen);
        open(player, inventory);
    }

    public void openKeys(Player player, int requestedPage) {
        MenuConfig menus = plugin.menusConfig();
        List<KeyEntry> entries = keyEntries();
        List<Integer> slots = menus.slots("key-list.key-slots");
        int page = page(requestedPage, entries.size(), slots.size());
        MenuHolder holder = new MenuHolder(MenuHolder.Kind.KEY_LIST, "", "", page, true);
        Inventory inventory = create(holder, menus.size("key-list"), menus.title("key-list", Text.value("page", page + 1)));
        fill(inventory);
        int start = page * slots.size();
        for (int index = 0; index < slots.size() && start + index < entries.size(); index++) {
            KeyEntry entry = entries.get(start + index);
            ItemStack icon = entry.icon();
            appendLore(icon, List.of(Component.empty(),
                    Text.parse("<gray>ID</gray> <dark_gray>»</dark_gray> <white>" + entry.id() + "</white>"),
                    Text.parse("<gray>Source</gray> <dark_gray>»</dark_gray> <aqua>" + entry.source() + "</aqua>"),
                    Text.parse("<gray>Used by crates</gray> <dark_gray>»</dark_gray> <white>" + plugin.crates().referencesToKey(entry.id()) + "</white>"),
                    entry.resolved() ? Text.parse("<green>Exact template resolved.</green>") : Text.parse("<red>Template unresolved.</red>"),
                    Text.parse("<dark_gray>Right-click test • Shift-left rotate • Shift-right delete</dark_gray>")));
            int slot = slots.get(index);
            inventory.setItem(slot, icon);
            holder.bind(slot, "key-entry", entry.id());
        }
        addNavigation(inventory, holder, "key-list", page, entries.size(), slots.size(),
                "create", "sync", "duplicate-key", "import-keys");
        open(player, inventory);
    }

    public void openKeyTemplate(Player player) {
        EditSessionService.KeyDraft draft = plugin.editSessions().key(player);
        if (draft == null) { openKeys(player, 0); return; }
        MenuConfig menus = plugin.menusConfig();
        MenuHolder holder = new MenuHolder(MenuHolder.Kind.KEY_TEMPLATE, "", draft.id(), 0, true);
        Inventory inventory = create(holder, menus.size("key-template"),
                menus.title("key-template", Text.value("key_id", draft.id())));
        fill(inventory);
        int input = menus.slot("key-template.input-placeholder");
        inventory.setItem(input, draft.template() == null ? menus.item("key-template.input-placeholder") : draft.template());
        holder.bind(input, "key-input", draft.id());
        int previousSlot = menus.slot("key-template.previous");
        inventory.setItem(previousSlot, draft.previous() == null ? menus.item("key-template.previous") : draft.previous());
        holder.bind(previousSlot, "noop", draft.id());
        int legacySlot = menus.slot("key-template.legacy");
        inventory.setItem(legacySlot, menus.item("key-template.legacy", Text.value("legacy", draft.keepPreviousAsLegacy())));
        holder.bind(legacySlot, draft.rotation() ? "legacy" : "noop", draft.id());
        for (String action : List.of("name", "confirm", "cancel")) {
            int slot = menus.slot("key-template." + action);
            inventory.setItem(slot, menus.item("key-template." + action));
            holder.bind(slot, action, draft.id());
        }
        open(player, inventory);
    }

    public void openKeySelect(Player player, Crate crate, int requestedPage) {
        MenuConfig menus = plugin.menusConfig();
        List<KeyEntry> entries = keyEntries();
        List<Integer> slots = menus.slots("key-select.key-slots");
        int page = page(requestedPage, entries.size(), slots.size());
        MenuHolder holder = new MenuHolder(MenuHolder.Kind.KEY_SELECT, crate.id(), "", page, true);
        Inventory inventory = create(holder, menus.size("key-select"), menus.title("key-select", Text.component("crate", crate.displayName())));
        fill(inventory);
        int start = page * slots.size();
        for (int index = 0; index < slots.size() && start + index < entries.size(); index++) {
            KeyEntry entry = entries.get(start + index);
            ItemStack icon = entry.icon();
            appendLore(icon, List.of(Component.empty(), Text.parse("<green>Click to accept this exact physical key.</green>")));
            int slot = slots.get(index);
            inventory.setItem(slot, icon);
            holder.bind(slot, "select-key", entry.id());
        }
        addNavigation(inventory, holder, "key-select", page, entries.size(), slots.size());
        open(player, inventory);
    }

    public void openRewardBuilder(Player player) {
        EditSessionService.RewardDraft draft = plugin.editSessions().reward(player);
        if (draft == null) { openDashboard(player); return; }
        MenuConfig menus = plugin.menusConfig();
        MenuHolder holder = new MenuHolder(MenuHolder.Kind.REWARD_BUILDER, draft.crateId(), draft.id(), 0, true);
        Inventory inventory = create(holder, menus.size("reward-builder"),
                menus.title("reward-builder", Text.value("reward_id", draft.id())));
        fill(inventory);
        int placeholder = menus.slot("reward-builder.input-placeholder");
        inventory.setItem(placeholder, menus.item("reward-builder.input-placeholder"));
        List<Integer> itemSlots = menus.slots("reward-builder.item-slots");
        List<ItemStack> items = draft.items();
        for (int index = 0; index < itemSlots.size() && index < items.size(); index++) {
            inventory.setItem(itemSlots.get(index), items.get(index));
            holder.bind(itemSlots.get(index), "reward-input", draft.id());
        }
        for (int slot : itemSlots) holder.bind(slot, "reward-input", draft.id());
        put(inventory, holder, "reward-builder", "name", "name");
        put(inventory, holder, "reward-builder", "chance", "chance",
                Text.value("chance", format(draft.baseChancePercent())));
        put(inventory, holder, "reward-builder", "command", "command", Text.value("commands", draft.commands().size()));
        put(inventory, holder, "reward-builder", "experience", "experience", Text.value("points", draft.experiencePoints()), Text.value("levels", draft.experienceLevels()));
        put(inventory, holder, "reward-builder", "money", "money", Text.value("money", format(draft.money())));
        put(inventory, holder, "reward-builder", "rarity", "rarity", Text.value("rarity", draft.rarity()));
        put(inventory, holder, "reward-builder", "permissions", "permissions",
                Text.value("required", draft.requiredPermission().isBlank() ? "none" : draft.requiredPermission()),
                Text.value("blocked", draft.blockedPermission().isBlank() ? "none" : draft.blockedPermission()));
        put(inventory, holder, "reward-builder", "limits", "limits");
        put(inventory, holder, "reward-builder", "messages", "messages");
        put(inventory, holder, "reward-builder", "effects", "effects",
                Text.value("title", draft.presentation().title().isBlank() ? "none" : "configured"),
                Text.value("sound", draft.presentation().sound().isBlank() ? "none" : draft.presentation().sound()),
                Text.value("firework", draft.presentation().firework()));
        put(inventory, holder, "reward-builder", "enabled", "enabled", Text.value("enabled", draft.enabled()));
        put(inventory, holder, "reward-builder", "order", draft.editing() ? "reward-order" : "noop",
                Text.value("position", draft.editing() ? draft.orderIndex() + 1 : "after save"));
        for (String action : List.of("clear", "confirm", "cancel")) put(inventory, holder, "reward-builder", action, action);
        open(player, inventory);
    }

    public void editReward(Player player, Crate crate, CrateReward reward) {
        if (!requireWritableDraft(player, crate.id())) return;
        int index = crate.orderedRewards().indexOf(reward);
        plugin.editSessions().beginReward(player, crate.id(), reward, Math.max(0, index));
        openRewardBuilder(player);
    }

    public void beginSpecialReward(Player player, String crateId) {
        if (!requireWritableDraft(player, crateId)) return;
        Crate crate = plugin.crates().find(crateId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown crate"));
        String id;
        do {
            id = "special_reward_" + UUID.randomUUID().toString().replace("-", "").substring(0, 4);
        } while (crate.rewards().containsKey(id));
        EditSessionService.RewardDraft draft = plugin.editSessions().beginReward(player, crate.id(), id);
        draft.displayName(Text.parse("<gold><bold>Special Reward</bold></gold>"));
        openRewardBuilder(player);
    }

    public void openLocations(Player player, int requestedPage) {
        MenuConfig menus = plugin.menusConfig();
        List<LocationStore.Link> links = plugin.locations().all().stream()
                .sorted(Comparator.comparing(link -> link.position().key())).toList();
        List<Integer> slots = menus.slots("locations.location-slots");
        int page = page(requestedPage, links.size(), slots.size());
        MenuHolder holder = new MenuHolder(MenuHolder.Kind.LOCATIONS, "", "", page, true);
        Inventory inventory = create(holder, menus.size("locations"), menus.title("locations", Text.value("page", page + 1)));
        fill(inventory);
        int start = page * slots.size();
        for (int index = 0; index < slots.size() && start + index < links.size(); index++) {
            LocationStore.Link link = links.get(start + index);
            Material material = link.position().loadedBlock() == null ? Material.BARRIER : link.position().loadedBlock().getType();
            if (!material.isItem()) material = Material.BARRIER;
            ItemStack icon = new ItemStack(material);
            icon.editMeta(meta -> {
                meta.displayName(Text.parse("<green><bold>" + link.crateId() + "</bold></green>"));
                meta.lore(List.of(Text.parse("<gray>" + locationText(link.position()) + "</gray>"),
                        Text.parse("<dark_gray>Click to teleport • Shift-right-click to unlink</dark_gray>")));
            });
            int slot = slots.get(index);
            inventory.setItem(slot, icon);
            holder.bind(slot, "location", link.position().key());
        }
        addNavigation(inventory, holder, "locations", page, links.size(), slots.size(), "wand");
        open(player, inventory);
    }

    public void openStatistics(Player player) {
        MenuConfig menus = plugin.menusConfig();
        MenuHolder holder = new MenuHolder(MenuHolder.Kind.STATISTICS, "", "", 0, true);
        Inventory inventory = create(holder, menus.size("statistics"), menus.title("statistics"));
        fill(inventory);
        long openings = plugin.crates().all().stream().mapToLong(crate -> plugin.statistics().global(crate.id())).sum();
        put(inventory, holder, "statistics", "summary", "noop", Text.value("openings", openings),
                Text.value("crates", plugin.crates().all().size()));
        put(inventory, holder, "statistics", "back", "dashboard");
        open(player, inventory);
    }

    public void openSystem(Player player) {
        MenuConfig menus = plugin.menusConfig();
        MenuHolder holder = new MenuHolder(MenuHolder.Kind.SYSTEM, "", "", 0, true);
        Inventory inventory = create(holder, menus.size("system"), menus.title("system"));
        fill(inventory);
        for (String action : List.of("validate", "reload", "backup", "diagnose", "back")) {
            put(inventory, holder, "system", action, action.equals("back") ? "dashboard" : action);
        }
        open(player, inventory);
    }

    public void openGlobalRewards(Player player, int requestedPage) {
        MenuConfig menus = plugin.menusConfig();
        List<GlobalReward> rewards = plugin.crates().orderedAdmin().stream().flatMap(crate ->
                crate.orderedRewards().stream().map(reward -> new GlobalReward(crate, reward))).toList();
        List<Integer> slots = menus.slots("global-rewards.reward-slots");
        int page = page(requestedPage, rewards.size(), slots.size());
        MenuHolder holder = new MenuHolder(MenuHolder.Kind.GLOBAL_REWARDS, "", "", page, true);
        Inventory inventory = create(holder, menus.size("global-rewards"), menus.title("global-rewards", Text.value("page", page + 1)));
        fill(inventory);
        int start = page * slots.size();
        for (int index = 0; index < slots.size() && start + index < rewards.size(); index++) {
            GlobalReward entry = rewards.get(start + index);
            ItemStack icon = entry.reward().displayCopy();
            appendLore(icon, List.of(Component.empty(),
                    Text.parse("<gray>Crate</gray> <dark_gray>»</dark_gray> <white>" + entry.crate().id() + "</white>"),
                    Text.parse("<gray>Base chance</gray> <dark_gray>»</dark_gray> <yellow>"
                            + format(entry.reward().baseChancePercent()) + "%</yellow>")));
            inventory.setItem(slots.get(index), icon);
        }
        addNavigation(inventory, holder, "global-rewards", page, rewards.size(), slots.size());
        open(player, inventory);
    }

    public void openWandSelector(Player player, int requestedPage) {
        MenuConfig menus = plugin.menusConfig();
        List<Crate> crates = plugin.crates().orderedAdmin().stream().filter(crate -> crate.state() != CrateState.ARCHIVED).toList();
        List<Integer> slots = menus.slots("wand-select.entry-slots");
        int page = page(requestedPage, crates.size(), slots.size());
        MenuHolder holder = new MenuHolder(MenuHolder.Kind.WAND_SELECT, "", "", page, true);
        Inventory inventory = create(holder, menus.size("wand-select"), menus.title("wand-select"));
        fill(inventory);
        int start = page * slots.size();
        for (int index = 0; index < slots.size() && start + index < crates.size(); index++) {
            Crate crate = crates.get(start + index);
            int slot = slots.get(index);
            inventory.setItem(slot, crate.iconCopy());
            holder.bind(slot, "wand-select", crate.id());
        }
        addNavigation(inventory, holder, "wand-select", page, crates.size(), slots.size());
        open(player, inventory);
    }

    public void openUnlinkConfirmation(Player player, LocationStore.Link link) {
        MenuConfig menus = plugin.menusConfig();
        MenuHolder holder = new MenuHolder(MenuHolder.Kind.CONFIRM_UNLINK, link.crateId(), link.position().key(), 0, true);
        Inventory inventory = create(holder, menus.size("confirm-unlink"), menus.title("confirm-unlink"));
        fill(inventory);
        put(inventory, holder, "confirm-unlink", "confirm", "confirm-unlink", link.position().key());
        put(inventory, holder, "confirm-unlink", "cancel", "locations");
        open(player, inventory);
    }

    public void openCrateDeleteConfirmation(Player player, String crateId) {
        openCrateConfirmation(player, crateId, "delete");
    }

    public void handleClick(InventoryClickEvent event, MenuHolder holder) {
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (holder.kind() == MenuHolder.Kind.KEY_TEMPLATE && allowed(player, "plexoncrates.admin.keys")
                && captureKeyClick(event, player)) return;
        if (holder.kind() == MenuHolder.Kind.REWARD_BUILDER && allowed(player, "plexoncrates.admin.rewards")
                && captureRewardClick(event, player)) return;
        if (holder.kind() == MenuHolder.Kind.EDITOR && allowed(player, "plexoncrates.admin.crates")
                && captureIconClick(event, player, holder)) return;
        if (event.getClickedInventory() != event.getView().getTopInventory()) return;
        MenuHolder.Action action = holder.action(event.getRawSlot());
        if (action == null) return;
        if (!allowed(player, permission(holder.kind(), action.id()))) {
            plugin.messages().send(player, "no-permission");
            return;
        }
        try {
            route(player, holder, action, event);
        } catch (Exception error) {
            plugin.configError(player, error);
        }
    }

    public void handleDrag(InventoryDragEvent event, MenuHolder holder) {
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (!allowed(player, permission(holder.kind(), "capture"))) return;
        ItemStack item = event.getOldCursor();
        if (item == null || item.getType().isAir()) return;
        if (holder.kind() == MenuHolder.Kind.KEY_TEMPLATE
                && event.getRawSlots().size() == 1
                && event.getRawSlots().contains(plugin.menusConfig().slot("key-template.input-placeholder"))) {
            captureKey(player, item);
        } else if (holder.kind() == MenuHolder.Kind.REWARD_BUILDER
                && event.getRawSlots().size() == 1
                && event.getRawSlots().stream().anyMatch(plugin.menusConfig().slots("reward-builder.item-slots")::contains)) {
            captureReward(player, item);
        } else if (holder.kind() == MenuHolder.Kind.EDITOR && event.getRawSlots().size() == 1
                && event.getRawSlots().contains(4)) {
            captureIcon(player, holder.crateId(), item);
        }
    }

    private void route(Player player, MenuHolder holder, MenuHolder.Action action, InventoryClickEvent event) throws Exception {
        switch (action.id()) {
            case "close" -> player.closeInventory();
            case "dashboard" -> openDashboard(player);
            case "back" -> { if (holder.kind() == MenuHolder.Kind.EDITOR) openCrates(player, 0); else openDashboard(player); }
            case "crates" -> openCrates(player, 0);
            case "keys" -> openKeys(player, 0);
            case "locations" -> openLocations(player, 0);
            case "rewards" -> {
                if (holder.kind() == MenuHolder.Kind.EDITOR) {
                    plugin.crates().find(holder.crateId()).ifPresent(crate -> plugin.menus().openRewards(player, crate, 0));
                } else openGlobalRewards(player, 0);
            }
            case "statistics" -> openStatistics(player);
            case "system" -> openSystem(player);
            case "edit-crate" -> {
                if (event.isShiftClick() && event.isLeftClick()) exportCrate(player, action.value(), holder.page());
                else plugin.crates().find(action.value()).ifPresent(crate -> openCrateEditor(player, crate));
            }
            case "create" -> createFor(holder.kind(), player);
            case "search" -> searchCrates(player);
            case "import" -> importCrate(player);
            case "duplicate-key" -> duplicateKey(player);
            case "import-keys" -> importKeys(player);
            case "previous" -> reopenPage(holder, player, holder.page() - 1);
            case "next" -> reopenPage(holder, player, holder.page() + 1);
            case "preview" -> plugin.crates().find(action.value()).ifPresent(crate -> plugin.menus().openPreview(player, crate, 0, true));
            case "rename" -> renameCrate(player, action.value());
            case "description" -> editDescription(player, action.value());
            case "order" -> editOrder(player, action.value());
            case "key" -> plugin.crates().find(action.value()).ifPresent(crate -> openKeySelect(player, crate, 0));
            case "create-reward" -> createReward(player, action.value());
            case "wand" -> { plugin.wand().give(player, action.value()); player.closeInventory(); }
            case "opening" -> editOpening(player, action.value(), event);
            case "display" -> editDisplay(player, action.value());
            case "access" -> editAccess(player, action.value());
            case "disable" -> {
                if (requireWritableDraft(player, action.value())) {
                    plugin.crates().setState(action.value(), CrateState.DISABLED, player.getName());
                    saveDraftRevision(player, action.value(), "STATE", "Disabled crate");
                    refreshCrate(player, action.value());
                }
            }
            case "publish" -> {
                if (requireWritableDraft(player, action.value())) {
                    plugin.crates().publish(action.value(), plugin.keys(), player.getName());
                    saveDraftRevision(player, action.value(), "STATE", "Published crate");
                    refreshCrate(player, action.value());
                }
            }
            case "archive" -> openCrateConfirmation(player, action.value(), "archive");
            case "clone" -> cloneCrate(player, action.value());
            case "delete" -> openCrateConfirmation(player, action.value(), "delete");
            case "key-entry" -> keyEntry(player, action.value(), event);
            case "sync" -> { plugin.keys().syncDiscovery(); openKeys(player, holder.page()); }
            case "name" -> editDraftName(player, holder.kind());
            case "legacy" -> { plugin.editSessions().key(player).toggleLegacy(); openKeyTemplate(player); }
            case "confirm" -> confirmDraft(player, holder.kind());
            case "cancel" -> cancelDraft(player, holder.kind(), holder.crateId());
            case "select-key" -> selectKey(player, holder.crateId(), action.value());
            case "chance" -> editChance(player, event);
            case "command" -> editCommand(player, event);
            case "experience" -> editExperience(player, event.isRightClick());
            case "money" -> editMoney(player);
            case "rarity" -> cycleRarity(player);
            case "permissions" -> editRewardPermissions(player);
            case "limits" -> editRewardLimits(player);
            case "messages" -> editRewardMessages(player);
            case "effects" -> editRewardEffects(player);
            case "enabled" -> { plugin.editSessions().reward(player).toggleEnabled(); openRewardBuilder(player); }
            case "reward-order" -> editRewardOrder(player);
            case "clear" -> { plugin.editSessions().reward(player).clearItems(); openRewardBuilder(player); }
            case "location" -> location(player, action.value(), event.isShiftClick() && event.isRightClick());
            case "validate" -> plugin.validateFor(player);
            case "reload" -> { plugin.reloadFor(player); if (plugin.isEnabled()) openSystem(player); }
            case "backup" -> plugin.backupFor(player);
            case "diagnose" -> plugin.diagnoseFor(player);
            case "wand-select" -> { plugin.wand().select(player, action.value()); player.closeInventory(); }
            case "confirm-unlink" -> confirmUnlink(player, action.value());
            case "confirm-crate" -> confirmCrate(player, holder.crateId(), action.value());
            case "confirm-key-delete" -> confirmKeyDelete(player, action.value());
            case "retry-draft" -> retryDraft(player, holder.crateId());
            case "undo-draft" -> undoDraft(player, holder.crateId());
            case "takeover-draft" -> openTakeoverConfirmation(player, holder.crateId(), "editor", holder.page());
            case "confirm-takeover" -> confirmTakeover(player, holder.crateId(), action.value(), holder.page());
            case "cancel-takeover" -> reopenAfterTakeover(player, holder.crateId(), action.value(), holder.page());
            case "noop", "capture-icon", "key-input", "reward-input" -> { }
            default -> throw new IllegalArgumentException("Unknown GUI action: " + action.id());
        }
    }

    private void createFor(MenuHolder.Kind kind, Player player) {
        if (kind == MenuHolder.Kind.CRATE_LIST) {
            plugin.editSessions().request(player, Text.parse("<gold>Enter the new crate ID:</gold>"), (target, value) -> {
                if (!CrateRegistry.validId(value) || !value.equals(value.toLowerCase(Locale.ROOT))) throw new IllegalArgumentException("Use lowercase letters, numbers, _ or -");
                Crate crate = plugin.crates().createDraft(value, target.getName());
                openCrateEditor(target, crate);
            });
        } else if (kind == MenuHolder.Kind.KEY_LIST) {
            plugin.editSessions().request(player, Text.parse("<aqua>Enter the new custom key ID:</aqua>"), (target, value) -> {
                if (!CrateRegistry.validId(value) || !value.equals(value.toLowerCase(Locale.ROOT))) throw new IllegalArgumentException("Use a unique lowercase ID");
                plugin.editSessions().beginKey(target, value);
                openKeyTemplate(target);
            });
        }
    }

    private void renameCrate(Player player, String crateId) {
        if (!requireWritableDraft(player, crateId)) return;
        plugin.editSessions().request(player, Text.parse("<gold>Enter the new MiniMessage crate name:</gold>"), (target, value) -> {
            if (!requireWritableDraft(target, crateId)) return;
            Component parsed = Text.parse(value);
            plugin.crates().setDisplayName(crateId, parsed, target.getName());
            saveDraftRevision(target, crateId, "IDENTITY", "Changed crate display name");
            refreshCrate(target, crateId);
        });
    }

    private void editDescription(Player player, String crateId) {
        if (!requireWritableDraft(player, crateId)) return;
        plugin.editSessions().request(player, Text.parse("<white>Enter MiniMessage description lines separated by |:</white>"), (target, value) -> {
            if (!requireWritableDraft(target, crateId)) return;
            List<Component> lines = componentLines(value);
            plugin.crates().setDescription(crateId, lines, target.getName());
            saveDraftRevision(target, crateId, "IDENTITY", "Changed crate description");
            refreshCrate(target, crateId);
        });
    }

    private void editOrder(Player player, String crateId) {
        if (!requireWritableDraft(player, crateId)) return;
        plugin.editSessions().request(player, Text.parse("<yellow>Enter display order (0-1000000):</yellow>"), (target, value) -> {
            if (!requireWritableDraft(target, crateId)) return;
            plugin.crates().setDisplayOrder(crateId, Integer.parseInt(value), target.getName());
            saveDraftRevision(target, crateId, "ORDER", "Changed crate display order");
            refreshCrate(target, crateId);
        });
    }

    private void editOpening(Player player, String crateId, InventoryClickEvent event) throws Exception {
        if (!requireWritableDraft(player, crateId)) return;
        Crate crate = plugin.crates().find(crateId).orElseThrow();
        if (event.isShiftClick() && event.isLeftClick()) {
            plugin.crates().setOpening(crateId, crate.cooldownSeconds(), !crate.bulkEnabled(), crate.bulkMaximum(),
                    crate.animation(), player.getName());
            saveDraftRevision(player, crateId, "OPENING", "Toggled bulk opening");
            refreshCrate(player, crateId);
            return;
        }
        if (event.isRightClick()) {
            plugin.editSessions().request(player, Text.parse("<aqua>Enter cooldown seconds and bulk maximum as <white>cooldown,maximum</white>:</aqua>"), (target, value) -> {
                if (!requireWritableDraft(target, crateId)) return;
                String[] parts = value.split(",", -1);
                if (parts.length != 2) throw new IllegalArgumentException("Use cooldown,maximum (for example 1,64)");
                Crate current = plugin.crates().find(crateId).orElseThrow();
                plugin.crates().setOpening(crateId, Integer.parseInt(parts[0].trim()), current.bulkEnabled(),
                        Integer.parseInt(parts[1].trim()), current.animation(), target.getName());
                saveDraftRevision(target, crateId, "OPENING", "Changed cooldown and bulk maximum");
                refreshCrate(target, crateId);
            });
            return;
        }
        AnimationType[] values = AnimationType.values();
        AnimationType next = values[(crate.animation().ordinal() + 1) % values.length];
        plugin.crates().setOpening(crateId, crate.cooldownSeconds(), crate.bulkEnabled(), crate.bulkMaximum(), next,
                player.getName());
        saveDraftRevision(player, crateId, "OPENING", "Changed opening animation");
        refreshCrate(player, crateId);
    }

    private void editDisplay(Player player, String crateId) {
        if (!requireWritableDraft(player, crateId)) return;
        plugin.editSessions().request(player, Text.parse("<light_purple>Enter MiniMessage hologram lines separated by |:</light_purple>"), (target, value) -> {
            if (!requireWritableDraft(target, crateId)) return;
            plugin.crates().setHologramLines(crateId, componentLines(value), target.getName());
            saveDraftRevision(target, crateId, "DISPLAY", "Changed world display lines");
            plugin.displays().refresh();
            refreshCrate(target, crateId);
        });
    }

    private void editAccess(Player player, String crateId) {
        if (!requireWritableDraft(player, crateId)) return;
        plugin.editSessions().request(player, Text.parse("<gold>Enter <white>permission | allowed worlds CSV | excluded worlds CSV</white>. Use - for none:</gold>"), (target, value) -> {
            if (!requireWritableDraft(target, crateId)) return;
            String[] parts = value.split("\\|", -1);
            if (parts.length != 3) throw new IllegalArgumentException("Use exactly three | separated fields");
            String permission = parts[0].trim().equals("-") ? "" : parts[0].trim();
            plugin.crates().setAccess(crateId, permission, csv(parts[1]), csv(parts[2]), target.getName());
            saveDraftRevision(target, crateId, "ACCESS", "Changed crate access rules");
            refreshCrate(target, crateId);
        });
    }

    private void cloneCrate(Player player, String crateId) {
        plugin.editSessions().request(player, Text.parse("<aqua>Enter the clone's new ID:</aqua>"), (target, value) -> {
            Crate clone = plugin.crates().cloneAsDraft(crateId, value, target.getName());
            openCrateEditor(target, clone);
        });
    }

    private void searchCrates(Player player) {
        plugin.editSessions().request(player, Text.parse("<aqua>Enter an ID/name search, or <white>-</white> to clear:</aqua>"), (target, value) -> {
            String normalized = value.equals("-") ? "" : value.toLowerCase(Locale.ROOT).trim();
            if (normalized.length() > 64) throw new IllegalArgumentException("Search text is too long");
            crateSearch.put(target.getUniqueId(), normalized);
            openCrates(target, 0);
        });
    }

    private void importCrate(Player player) {
        plugin.editSessions().request(player, Text.parse("<aqua>Enter <white>file.yml,new-crate-id</white> from the imports folder:</aqua>"), (target, value) -> {
            String[] parts = value.split(",", -1);
            if (parts.length != 2) throw new IllegalArgumentException("Use file.yml,new-crate-id");
            String fileName = parts[0].trim();
            if (!fileName.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}\\.yml")) {
                throw new IllegalArgumentException("Use a simple .yml filename without folders");
            }
            Path root = plugin.getDataFolder().toPath().resolve("imports").toAbsolutePath().normalize();
            Path source = root.resolve(fileName).normalize();
            if (!source.getParent().equals(root)) throw new IllegalArgumentException("Import path leaves the imports folder");
            Crate imported = plugin.crates().importAsDraft(source, parts[1].trim(), target.getName());
            openCrateEditor(target, imported);
        });
    }

    private void exportCrate(Player player, String crateId, int page) throws Exception {
        Path destination = plugin.crates().exportDefinition(crateId,
                plugin.getDataFolder().toPath().resolve("exports"));
        player.sendMessage(Text.parse("<green>Exported</green> <white>" + crateId
                + "</white> <green>to</green> <white>exports/" + destination.getFileName() + "</white><green>.</green>"));
        openCrates(player, page);
    }

    private void duplicateKey(Player player) {
        plugin.editSessions().request(player, Text.parse("<aqua>Enter <white>source-key,new-key-id</white>:</aqua>"), (target, value) -> {
            String[] parts = value.split(",", -1);
            if (parts.length != 2) throw new IllegalArgumentException("Use source-key,new-key-id");
            String sourceId = parts[0].trim().toLowerCase(Locale.ROOT);
            String newId = parts[1].trim().toLowerCase(Locale.ROOT);
            KeyDefinition source = plugin.keys().definition(sourceId)
                    .orElseThrow(() -> new IllegalArgumentException("Unknown source key"));
            if (!CrateRegistry.validId(newId) || plugin.keys().definition(newId).isPresent()) {
                throw new IllegalArgumentException("Use a unique lowercase key ID");
            }
            ItemStack template = plugin.keys().template(sourceId)
                    .orElseThrow(() -> new IllegalArgumentException("The source key is unresolved"));
            EditSessionService.KeyDraft draft = plugin.editSessions().beginKey(target, newId);
            draft.displayName(source.displayName());
            draft.template(template);
            openKeyTemplate(target);
        });
    }

    private void importKeys(Player player) {
        plugin.editSessions().request(player, Text.parse("<aqua>Enter a version 2 key-registry filename from the <white>imports</white> folder:</aqua>"), (target, value) -> {
            String fileName = value.trim();
            if (!fileName.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}\\.yml")) {
                throw new IllegalArgumentException("Use a simple .yml filename without folders");
            }
            Path root = plugin.getDataFolder().toPath().resolve("imports").toAbsolutePath().normalize();
            Path source = root.resolve(fileName).normalize();
            if (!source.getParent().equals(root)) throw new IllegalArgumentException("Import path leaves the imports folder");
            List<String> imported = plugin.keys().importDefinitions(source, target.getName());
            target.sendMessage(Text.parse("<green>Imported exact key definitions:</green> <white>"
                    + String.join(", ", imported) + "</white>"));
            openKeys(target, 0);
        });
    }

    private void createReward(Player player, String crateId) {
        if (!requireWritableDraft(player, crateId)) return;
        plugin.editSessions().request(player, Text.parse("<light_purple>Enter a unique reward ID:</light_purple>"), (target, value) -> {
            if (!requireWritableDraft(target, crateId)) return;
            if (!CrateRegistry.validId(value) || !value.equals(value.toLowerCase(Locale.ROOT))) throw new IllegalArgumentException("Use a lowercase reward ID");
            Crate crate = plugin.crates().find(crateId).orElseThrow();
            if (crate.rewards().containsKey(value)) throw new IllegalArgumentException("That reward already exists");
            plugin.editSessions().beginReward(target, crateId, value);
            openRewardBuilder(target);
        });
    }

    private void keyEntry(Player player, String keyId, InventoryClickEvent event) throws Exception {
        boolean bound = plugin.keys().definition(keyId).isPresent();
        if (!bound && event.isShiftClick() && event.isRightClick()) {
            player.sendMessage(Text.parse("<gray>That live category is not bound, so there is no PlexonCrates key definition to delete.</gray>"));
            return;
        }
        if (!bound) plugin.keys().bindExternal(keyId, player.getName());
        KeyDefinition definition = plugin.keys().definition(keyId).orElseThrow();
        if (event.isShiftClick() && event.isLeftClick()) {
            if (definition.source() == com.antondev.crates.domain.key.KeySource.PLEXONKEYS) {
                throw new IllegalStateException("Live PlexonKeys templates are read-only in PlexonCrates");
            }
            ItemStack current = plugin.keys().template(keyId).orElseThrow(() -> new IllegalStateException("This key is unresolved"));
            plugin.editSessions().beginKeyRotation(player, definition, current);
            openKeyTemplate(player);
        } else if (event.isShiftClick() && event.isRightClick()) {
            long references = plugin.crates().referencesToKey(keyId);
            if (references > 0) {
                plugin.editSessions().request(player, Text.parse("<yellow>This key is used by " + references
                        + " crate(s). Enter a replacement key ID, or /cancel:</yellow>"), (target, value) -> {
                    if (plugin.keys().definition(value).isEmpty() || plugin.keys().resolve(value).isEmpty()) {
                        throw new IllegalArgumentException("The replacement key must exist and resolve exactly");
                    }
                    plugin.crates().replaceKeyReferences(keyId, value, target.getName());
                    openKeyDeleteConfirmation(target, keyId);
                });
            } else openKeyDeleteConfirmation(player, keyId);
        } else if (event.isRightClick()) {
            plugin.keys().give(player, keyId, 1);
            plugin.messages().send(player, "key-given", Text.value("amount", 1),
                    Text.component("key", plugin.keys().definition(keyId).orElseThrow().displayName()), Text.value("player", player.getName()));
        } else {
            player.sendMessage(Text.parse("<aqua>" + keyId + "</aqua> <dark_gray>•</dark_gray> <gray>" + definition.source()
                    + " • " + (plugin.keys().resolve(keyId).isPresent() ? "resolved" : "unresolved") + "</gray>"));
        }
    }

    private void openKeyDeleteConfirmation(Player player, String keyId) {
        MenuConfig menus = plugin.menusConfig();
        MenuHolder holder = new MenuHolder(MenuHolder.Kind.CONFIRM_KEY_DELETE, "", keyId, 0, true);
        Inventory inventory = create(holder, menus.size("confirm-key-delete"),
                menus.title("confirm-key-delete", Text.value("key_id", keyId)));
        fill(inventory);
        put(inventory, holder, "confirm-key-delete", "confirm", "confirm-key-delete", keyId);
        put(inventory, holder, "confirm-key-delete", "cancel", "keys");
        open(player, inventory);
    }

    private void confirmKeyDelete(Player player, String keyId) throws Exception {
        if (plugin.crates().referencesToKey(keyId) > 0) {
            throw new IllegalStateException("Replace every crate reference before deleting this key");
        }
        plugin.keys().delete(keyId, player.getName());
        openKeys(player, 0);
    }

    private void selectKey(Player player, String crateId, String keyId) throws Exception {
        if (!requireWritableDraft(player, crateId)) return;
        if (plugin.keys().definition(keyId).isEmpty()) plugin.keys().bindExternal(keyId, player.getName());
        Crate crate = plugin.crates().find(crateId).orElseThrow();
        plugin.crates().setAcceptedKeys(crate.id(), List.of(keyId), Math.max(1, crate.keyCost()), player.getName());
        saveDraftRevision(player, crateId, "KEY", "Replaced accepted physical key");
        refreshCrate(player, crateId);
    }

    private void editDraftName(Player player, MenuHolder.Kind kind) {
        plugin.editSessions().request(player, Text.parse("<white>Enter a MiniMessage display name:</white>"), (target, value) -> {
            if (kind == MenuHolder.Kind.KEY_TEMPLATE) {
                plugin.editSessions().key(target).displayName(Text.parse(value));
                openKeyTemplate(target);
            } else {
                plugin.editSessions().reward(target).displayName(Text.parse(value));
                openRewardBuilder(target);
            }
        });
    }

    private void confirmDraft(Player player, MenuHolder.Kind kind) throws Exception {
        if (kind == MenuHolder.Kind.KEY_TEMPLATE) {
            EditSessionService.KeyDraft draft = plugin.editSessions().key(player);
            if (draft == null || draft.template() == null) throw new IllegalArgumentException("Capture an exact key item first");
            if (draft.rotation()) {
                plugin.keys().replaceCaptured(draft.id(), draft.displayName(), draft.template(),
                        draft.keepPreviousAsLegacy(), player.getName());
            } else plugin.keys().createCaptured(draft.id(), draft.displayName(), draft.template(), player.getName());
            plugin.editSessions().clearKey(player);
            openKeys(player, 0);
        } else if (kind == MenuHolder.Kind.REWARD_BUILDER) {
            EditSessionService.RewardDraft draft = plugin.editSessions().reward(player);
            if (draft == null || !draft.deliverable()) throw new IllegalArgumentException("Add an item, command, XP, or money first");
            if (!requireWritableDraft(player, draft.crateId())) return;
            if (draft.editing()) {
                plugin.crates().updateBundleReward(draft.crateId(), draft.id(), draft.displayName(), draft.baseChancePercent(),
                        draft.enabled(), draft.rarity(), draft.displayItem(), draft.items(), draft.commands(),
                        draft.experiencePoints(), draft.experienceLevels(), draft.money(), draft.limits(),
                        draft.requiredPermission(), draft.blockedPermission(), draft.presentation(),
                        draft.personalMessage(), draft.broadcast(), player.getName());
                if (draft.orderChanged()) plugin.crates().moveReward(draft.crateId(), draft.id(), draft.orderIndex(), player.getName());
            } else {
                plugin.crates().addBundleReward(draft.crateId(), draft.id(), draft.displayName(), draft.baseChancePercent(), draft.rarity(),
                        draft.items(), draft.commands(), draft.experiencePoints(), draft.experienceLevels(), draft.money(),
                        draft.limits(), draft.requiredPermission(), draft.blockedPermission(), draft.presentation(),
                        draft.personalMessage(), draft.broadcast(), player.getName());
            }
            String crateId = draft.crateId();
            plugin.editSessions().clearReward(player);
            saveDraftRevision(player, crateId, "REWARD", draft.editing()
                    ? "Updated reward " + draft.id() : "Added reward " + draft.id());
            refreshCrate(player, crateId);
        }
    }

    private void cancelDraft(Player player, MenuHolder.Kind kind, String crateId) {
        if (kind == MenuHolder.Kind.KEY_TEMPLATE) {
            plugin.editSessions().clearKey(player);
            openKeys(player, 0);
        } else {
            plugin.editSessions().clearReward(player);
            refreshCrate(player, crateId);
        }
    }

    private void editChance(Player player, InventoryClickEvent event) {
        EditSessionService.RewardDraft draft = plugin.editSessions().reward(player);
        if (!event.isShiftClick()) {
            double delta = event.isRightClick() ? -1.0 : 1.0;
            double next = Math.max(0.0, Math.min(100.0, draft.baseChancePercent() + delta));
            draft.baseChancePercent(next);
            openRewardBuilder(player);
            return;
        }
        plugin.editSessions().request(player, Text.parse("<yellow>Enter the exact base chance from 0.00% to 100.00%:</yellow>"), (target, value) -> {
            plugin.editSessions().reward(target).baseChancePercent(Double.parseDouble(value.replace("%", "").trim()));
            openRewardBuilder(target);
        });
    }

    private void editCommand(Player player, InventoryClickEvent event) {
        if (event.isShiftClick() && event.isRightClick()) {
            plugin.editSessions().request(player, Text.parse("<gold>Enter command positions as <white>from,to</white>:</gold>"), (target, value) -> {
                String[] parts = value.split(",", -1);
                if (parts.length != 2) throw new IllegalArgumentException("Use from,to");
                plugin.editSessions().reward(target).moveCommand(Integer.parseInt(parts[0].trim()), Integer.parseInt(parts[1].trim()));
                openRewardBuilder(target);
            });
        } else if (event.isShiftClick()) {
            plugin.editSessions().request(player, Text.parse("<gold>Enter <white>number | replacement command</white>:</gold>"), (target, value) -> {
                String[] parts = value.split("\\|", 2);
                if (parts.length != 2) throw new IllegalArgumentException("Use number | replacement command");
                plugin.editSessions().reward(target).editCommand(Integer.parseInt(parts[0].trim()), parts[1].trim());
                openRewardBuilder(target);
            });
        } else if (event.isRightClick()) {
            plugin.editSessions().request(player, Text.parse("<red>Enter the command number to remove:</red>"), (target, value) -> {
                plugin.editSessions().reward(target).removeCommand(Integer.parseInt(value));
                openRewardBuilder(target);
            });
        } else {
            plugin.editSessions().request(player, Text.parse("<gold>Enter the console command without a leading /:</gold>"), (target, value) -> {
                plugin.editSessions().reward(target).addCommand(value);
                openRewardBuilder(target);
            });
        }
    }

    private void editRewardOrder(Player player) {
        EditSessionService.RewardDraft draft = plugin.editSessions().reward(player);
        int maximum = plugin.crates().find(draft.crateId()).orElseThrow().rewards().size();
        plugin.editSessions().request(player, Text.parse("<yellow>Enter reward position (1-" + maximum + "):</yellow>"), (target, value) -> {
            int position = Integer.parseInt(value);
            if (position < 1 || position > maximum) throw new IllegalArgumentException("Position must be 1-" + maximum);
            plugin.editSessions().reward(target).orderIndex(position - 1);
            openRewardBuilder(target);
        });
    }

    private void editExperience(Player player, boolean levels) {
        plugin.editSessions().request(player, Text.parse(levels ? "<green>Enter XP levels:</green>" : "<green>Enter XP points:</green>"), (target, value) -> {
            int amount = Integer.parseInt(value);
            if (levels) plugin.editSessions().reward(target).experienceLevels(amount);
            else plugin.editSessions().reward(target).experiencePoints(amount);
            openRewardBuilder(target);
        });
    }

    private void editMoney(Player player) {
        plugin.editSessions().request(player, Text.parse("<gold>Enter the Vault money amount (0 disables it):</gold>"), (target, value) -> {
            plugin.editSessions().reward(target).money(Double.parseDouble(value));
            openRewardBuilder(target);
        });
    }

    private void cycleRarity(Player player) {
        EditSessionService.RewardDraft draft = plugin.editSessions().reward(player);
        RewardRarity[] values = RewardRarity.values();
        draft.rarity(values[(draft.rarity().ordinal() + 1) % values.length]);
        openRewardBuilder(player);
    }

    private void editRewardPermissions(Player player) {
        plugin.editSessions().request(player, Text.parse("<gold>Enter <white>required permission | blocked permission</white>. Use - for none:</gold>"), (target, value) -> {
            String[] parts = value.split("\\|", -1);
            if (parts.length != 2) throw new IllegalArgumentException("Use exactly two | separated fields");
            plugin.editSessions().reward(target).permissions(dash(parts[0]), dash(parts[1]));
            openRewardBuilder(target);
        });
    }

    private void editRewardLimits(Player player) {
        plugin.editSessions().request(player, Text.parse("<yellow>Enter 7 comma values: player lifetime, player window, window seconds, global lifetime, global window, window seconds, reward cooldown.</yellow>"), (target, value) -> {
            String[] parts = value.split(",", -1);
            if (parts.length != 7) throw new IllegalArgumentException("Exactly 7 comma-separated whole numbers are required");
            long[] numbers = new long[7];
            for (int index = 0; index < parts.length; index++) numbers[index] = Long.parseLong(parts[index].trim());
            plugin.editSessions().reward(target).limits(new RewardLimits(numbers[0], numbers[1], numbers[2],
                    numbers[3], numbers[4], numbers[5], numbers[6]));
            openRewardBuilder(target);
        });
    }

    private void editRewardMessages(Player player) {
        plugin.editSessions().request(player, Text.parse("<aqua>Enter <white>personal MiniMessage | server broadcast MiniMessage</white>. Use - for none:</aqua>"), (target, value) -> {
            String[] parts = value.split("\\|", -1);
            if (parts.length != 2) throw new IllegalArgumentException("Use exactly two | separated fields");
            plugin.editSessions().reward(target).messages(dash(parts[0]), dash(parts[1]));
            openRewardBuilder(target);
        });
    }

    private void editRewardEffects(Player player) {
        plugin.editSessions().request(player, Text.parse("<light_purple>Enter <white>title | subtitle | sound | volume | pitch | firework</white>. Use - for blank text/sound:</light_purple>"), (target, value) -> {
            String[] parts = value.split("\\|", -1);
            if (parts.length != 6) throw new IllegalArgumentException("Use exactly six | separated fields");
            String firework = parts[5].trim().toLowerCase(Locale.ROOT);
            if (!firework.equals("true") && !firework.equals("false")) {
                throw new IllegalArgumentException("Firework must be true or false");
            }
            plugin.editSessions().reward(target).presentation(new RewardPresentation(
                    dash(parts[0]), dash(parts[1]), dash(parts[2]), Float.parseFloat(parts[3].trim()),
                    Float.parseFloat(parts[4].trim()), Boolean.parseBoolean(firework)));
            openRewardBuilder(target);
        });
    }

    private void location(Player player, String positionKey, boolean unlink) {
        LocationStore.Link link = link(positionKey);
        if (link == null) { openLocations(player, 0); return; }
        if (unlink) { openUnlinkConfirmation(player, link); return; }
        Location target = link.position().center(1.0);
        if (target == null) player.sendMessage(Text.parse("<yellow>That world is currently offline.</yellow>"));
        else { player.closeInventory(); player.teleportAsync(target); }
    }

    private void confirmUnlink(Player player, String positionKey) {
        LocationStore.Link link = link(positionKey);
        if (link == null) { openLocations(player, 0); return; }
        CrateUnlinkEvent event = new CrateUnlinkEvent(player, link.crateId(), link.position());
        Bukkit.getPluginManager().callEvent(event);
        if (!event.isCancelled() && plugin.locations().remove(link.position())) {
            plugin.displays().refresh();
            plugin.database().audit(new DatabaseService.AuditRecord(player.getUniqueId(), player.getName(), "UNLINK",
                    "LOCATION", positionKey, "Unlinked from crate " + link.crateId(), Instant.now()));
            plugin.messages().send(player, "location-removed");
        }
        openLocations(player, 0);
    }

    private void openCrateConfirmation(Player player, String crateId, String action) {
        MenuConfig menus = plugin.menusConfig();
        MenuHolder holder = new MenuHolder(MenuHolder.Kind.CONFIRM_CRATE_DELETE, crateId, action, 0, true);
        Inventory inventory = create(holder, menus.size("confirm-crate-delete"),
                menus.title("confirm-crate-delete", Text.value("action", action)));
        fill(inventory);
        put(inventory, holder, "confirm-crate-delete", "confirm", "confirm-crate", action);
        put(inventory, holder, "confirm-crate-delete", "cancel", "edit-crate", crateId);
        open(player, inventory);
    }

    public void retryDraft(Player player, String crateId) {
        try {
            byte[] payload = plugin.crates().serialized(crateId).getBytes(StandardCharsets.UTF_8);
            plugin.draftSessions().retryCrate(player.getUniqueId(), crateId, payload)
                    .whenComplete((view, error) -> runFor(player.getUniqueId(), target -> {
                        if (error != null) plugin.configError(target, asException(error));
                        else {
                            plugin.messages().send(target, "draft-save-retried");
                            plugin.menus().refreshDraftState(target, crateId);
                        }
                    }));
        } catch (Exception error) {
            plugin.configError(player, error);
        }
    }

    private void undoDraft(Player player, String crateId) {
        if (!requireWritableDraft(player, crateId)) return;
        plugin.draftSessions().undoCrate(player.getUniqueId(), crateId)
                .whenComplete((view, error) -> runFor(player.getUniqueId(), target -> {
                    if (error != null) {
                        plugin.configError(target, asException(error));
                        return;
                    }
                    try {
                        byte[] payload = plugin.draftSessions().payload(target.getUniqueId(), crateId)
                                .orElseThrow(() -> new IllegalStateException("The restored draft payload is unavailable"));
                        Crate restored = plugin.crates().restoreDraftSnapshot(crateId, payload);
                        plugin.displays().refresh();
                        plugin.messages().send(target, "draft-undo-complete");
                        openCrateEditor(target, restored);
                    } catch (Exception restoreError) {
                        plugin.configError(target, restoreError);
                    }
                }));
    }

    private void confirmTakeover(Player player, String crateId, String returnScreen, int returnPage) {
        if (!player.hasPermission("plexoncrates.admin.takeover")) {
            plugin.messages().send(player, "no-permission");
            return;
        }
        plugin.draftSessions().takeoverCrate(player.getUniqueId(), crateId)
                .whenComplete((view, error) -> runFor(player.getUniqueId(), target -> {
                    if (error != null) plugin.configError(target, asException(error));
                    else {
                        plugin.messages().send(target, "draft-takeover-complete");
                        reopenAfterTakeover(target, crateId, returnScreen, returnPage);
                    }
                }));
    }

    private void reopenAfterTakeover(Player player, String crateId, String returnScreen, int returnPage) {
        Crate crate = plugin.crates().find(crateId).orElse(null);
        if (crate == null) {
            openCrates(player, 0);
        } else if (returnScreen.equals("rewards")) {
            plugin.menus().openRewards(player, crate, returnPage);
        } else {
            openCrateEditor(player, crate);
        }
    }

    private void runFor(UUID playerId, java.util.function.Consumer<Player> action) {
        if (!plugin.isEnabled()) return;
        Bukkit.getScheduler().runTask(plugin, () -> {
            Player target = Bukkit.getPlayer(playerId);
            if (target != null && target.isOnline()) action.accept(target);
        });
    }

    private static Exception asException(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && (current instanceof java.util.concurrent.CompletionException
                || current instanceof java.util.concurrent.ExecutionException)) current = current.getCause();
        return current instanceof Exception exception ? exception : new IllegalStateException(current);
    }

    private void confirmCrate(Player player, String crateId, String action) throws Exception {
        if (!requireWritableDraft(player, crateId)) return;
        Crate crate = plugin.crates().find(crateId).orElseThrow();
        if (action.equals("archive")) {
            plugin.crates().setState(crateId, CrateState.ARCHIVED, player.getName());
            saveDraftRevision(player, crateId, "STATE", "Archived crate");
            openCrates(player, 0);
            return;
        }
        if (!action.equals("delete")) return;
        if (plugin.locations().count(crateId) > 0) throw new IllegalStateException("Unlink every world block before deleting this crate");
        if (crate.state() != CrateState.ARCHIVED && crate.state() != CrateState.DRAFT) {
            throw new IllegalStateException("Archive this crate before deleting it");
        }
        plugin.draftSessions().discardCrate(player.getUniqueId(), crateId)
                .whenComplete((ignored, error) -> runFor(player.getUniqueId(), target -> {
                    if (error != null) {
                        plugin.configError(target, asException(error));
                        return;
                    }
                    try {
                        plugin.crates().delete(crateId);
                        plugin.database().audit(new DatabaseService.AuditRecord(target.getUniqueId(), target.getName(),
                                "DELETE", "CRATE", crateId, "Deleted confirmed crate definition", Instant.now()));
                        openCrates(target, 0);
                    } catch (Exception deleteError) {
                        plugin.configError(target, deleteError);
                    }
                }));
    }

    private boolean captureKeyClick(InventoryClickEvent event, Player player) {
        int input = plugin.menusConfig().slot("key-template.input-placeholder");
        if (event.getClickedInventory() == event.getView().getTopInventory() && event.getRawSlot() == input) {
            ItemStack cursor = event.getCursor();
            if (cursor != null && !cursor.getType().isAir()) captureKey(player, cursor);
            return true;
        }
        if (event.isShiftClick() && event.getClickedInventory() == event.getView().getBottomInventory()) {
            captureKey(player, event.getCurrentItem());
            return true;
        }
        return false;
    }

    private boolean captureRewardClick(InventoryClickEvent event, Player player) {
        List<Integer> slots = plugin.menusConfig().slots("reward-builder.item-slots");
        if (event.getClickedInventory() == event.getView().getTopInventory() && slots.contains(event.getRawSlot())) {
            ItemStack cursor = event.getCursor();
            if (cursor != null && !cursor.getType().isAir()) captureReward(player, cursor);
            return true;
        }
        if (event.isShiftClick() && event.getClickedInventory() == event.getView().getBottomInventory()) {
            captureReward(player, event.getCurrentItem());
            return true;
        }
        return false;
    }

    private boolean captureIconClick(InventoryClickEvent event, Player player, MenuHolder holder) {
        if (event.getClickedInventory() == event.getView().getTopInventory() && event.getRawSlot() == 4) {
            ItemStack cursor = event.getCursor();
            if (cursor != null && !cursor.getType().isAir()) captureIcon(player, holder.crateId(), cursor);
            return true;
        }
        if (event.isShiftClick() && event.getClickedInventory() == event.getView().getBottomInventory()) {
            captureIcon(player, holder.crateId(), event.getCurrentItem());
            return true;
        }
        return false;
    }

    private void captureKey(Player player, ItemStack item) {
        if (isEditorItem(item) || plugin.wand().isWand(item)) return;
        EditSessionService.KeyDraft draft = plugin.editSessions().key(player);
        if (draft == null) return;
        draft.template(item);
        openKeyTemplate(player);
    }

    private void captureReward(Player player, ItemStack item) {
        if (isEditorItem(item) || plugin.wand().isWand(item)) return;
        EditSessionService.RewardDraft draft = plugin.editSessions().reward(player);
        if (draft == null) return;
        draft.addItem(item);
        openRewardBuilder(player);
    }

    private void captureIcon(Player player, String crateId, ItemStack item) {
        if (isEditorItem(item) || plugin.wand().isWand(item)) return;
        if (!requireWritableDraft(player, crateId)) return;
        try {
            plugin.crates().setIcon(crateId, item, player.getName());
            saveDraftRevision(player, crateId, "IDENTITY", "Replaced exact crate icon");
            refreshCrate(player, crateId);
        } catch (Exception error) {
            plugin.configError(player, error);
        }
    }

    private void reopenPage(MenuHolder holder, Player player, int page) {
        switch (holder.kind()) {
            case CRATE_LIST -> openCrates(player, page);
            case KEY_LIST -> openKeys(player, page);
            case KEY_SELECT -> plugin.crates().find(holder.crateId()).ifPresent(crate -> openKeySelect(player, crate, page));
            case LOCATIONS -> openLocations(player, page);
            case GLOBAL_REWARDS -> openGlobalRewards(player, page);
            case WAND_SELECT -> openWandSelector(player, page);
            default -> { }
        }
    }

    private void refreshCrate(Player player, String crateId) {
        plugin.crates().find(crateId).ifPresentOrElse(crate -> openCrateEditor(player, crate), () -> openCrates(player, 0));
    }

    public boolean requireWritableDraft(Player player, String crateId) {
        DraftSessionService.View view = ensureDraft(player, crateId);
        if (view.writable()) return true;
        if (view.state() == DraftSessionService.State.LOADING) {
            plugin.messages().send(player, "draft-loading");
        } else if (view.state() == DraftSessionService.State.READ_ONLY) {
            plugin.messages().send(player, "draft-read-only", Text.value("owner",
                    view.ownerName().isBlank() ? "another administrator" : view.ownerName()));
        } else if (view.state() == DraftSessionService.State.SAVE_FAILED) {
            plugin.messages().send(player, "draft-save-failed", Text.value("error",
                    view.failure().isBlank() ? "unknown database error" : view.failure()));
        }
        return false;
    }

    public void saveDraftRevision(Player player, String crateId, String actionType, String summary) {
        try {
            byte[] payload = plugin.crates().serialized(crateId).getBytes(StandardCharsets.UTF_8);
            plugin.draftSessions().saveCrate(player.getUniqueId(), crateId, actionType, summary, payload)
                    .exceptionally(error -> null);
        } catch (Exception error) {
            plugin.getLogger().log(java.util.logging.Level.WARNING, "Could not queue the crate draft snapshot", error);
        }
    }

    public DraftSessionService.View ensureDraft(Player player, String crateId) {
        Optional<DraftSessionService.View> current = plugin.draftSessions().view(player.getUniqueId(), crateId);
        if (current.isPresent()) return current.get();
        try {
            byte[] payload = plugin.crates().serialized(crateId).getBytes(StandardCharsets.UTF_8);
            plugin.draftSessions().openCrate(player.getUniqueId(), player.getName(), crateId, 0, payload)
                    .exceptionally(error -> null);
            return plugin.draftSessions().view(player.getUniqueId(), crateId).orElseThrow();
        } catch (Exception error) {
            throw new IllegalStateException("Could not open the durable crate draft", error);
        }
    }

    private void installDraftControls(Player player, Inventory inventory, MenuHolder holder,
                                      DraftSessionService.View draft) {
        MenuConfig menus = plugin.menusConfig();
        var tags = new net.kyori.adventure.text.minimessage.tag.resolver.TagResolver[]{
                Text.value("draft_state", draftState(draft.state())),
                Text.value("draft_owner", draft.ownerName().isBlank() ? "loading" : draft.ownerName()),
                Text.value("draft_revision", draft.revision())};
        int statusSlot = menus.slot("editor.draft-status");
        ItemStack status = menus.item("editor.draft-status", tags);
        status.setType(switch (draft.state()) {
            case LOADING, SAVING -> Material.CLOCK;
            case SAVED -> Material.PAPER;
            case SAVE_FAILED -> Material.REDSTONE;
            case READ_ONLY -> Material.IRON_DOOR;
        });
        inventory.setItem(statusSlot, markEditorItem(status));
        holder.bind(statusSlot, draft.state() == DraftSessionService.State.SAVE_FAILED ? "retry-draft" : "noop",
                holder.crateId());

        int undoSlot = menus.slot("editor.undo");
        inventory.setItem(undoSlot, markEditorItem(menus.item("editor.undo")));
        holder.bind(undoSlot, draft.writable() ? "undo-draft" : "noop", holder.crateId());

        int takeoverSlot = menus.slot("editor.takeover");
        if (draft.state() == DraftSessionService.State.READ_ONLY
                && player.hasPermission("plexoncrates.admin.takeover")) {
            inventory.setItem(takeoverSlot, markEditorItem(menus.item("editor.takeover")));
            holder.bind(takeoverSlot, "takeover-draft", holder.crateId());
        } else {
            inventory.setItem(takeoverSlot, markEditorItem(menus.item("filler")));
            holder.bind(takeoverSlot, "noop", holder.crateId());
        }
    }

    private ItemStack markEditorItem(ItemStack source) {
        ItemStack item = source.clone();
        item.editMeta(meta -> meta.getPersistentDataContainer()
                .set(editorItem, PersistentDataType.BYTE, (byte) 1));
        return item;
    }

    private static String draftState(DraftSessionService.State state) {
        return switch (state) {
            case LOADING -> "Loading";
            case SAVING -> "Saving";
            case SAVED -> "Saved";
            case SAVE_FAILED -> "Save failed";
            case READ_ONLY -> "Read only";
        };
    }

    private List<KeyEntry> keyEntries() {
        var result = new LinkedHashMap<String, KeyEntry>();
        for (KeyDefinition definition : plugin.keys().definitions()) {
            result.put(definition.id(), new KeyEntry(definition.id(), definition.source().name(), definition.icon(),
                    plugin.keys().resolve(definition.id()).isPresent()));
        }
        for (Map.Entry<String, ExternalKeyDescriptor> external : plugin.keys().discovered().entrySet()) {
            result.putIfAbsent(external.getKey(), new KeyEntry(external.getKey(), "PLEXONKEYS (UNBOUND)",
                    external.getValue().template(), true));
        }
        return result.values().stream().sorted(Comparator.comparing(KeyEntry::id)).toList();
    }

    private LocationStore.Link link(String positionKey) {
        return plugin.locations().all().stream().filter(value -> value.position().key().equals(positionKey)).findFirst().orElse(null);
    }

    private static boolean allowed(Player player, String permission) {
        return permission.isBlank() || player.hasPermission("plexoncrates.admin") || player.hasPermission(permission);
    }

    private static String permission(MenuHolder.Kind kind, String action) {
        return switch (action) {
            case "close", "noop" -> "";
            case "crates" -> "plexoncrates.admin.crates";
            case "keys", "sync", "key-entry", "duplicate-key", "import-keys", "confirm-key-delete" ->
                    "plexoncrates.admin.keys";
            case "locations", "location", "wand", "wand-select", "confirm-unlink" -> "plexoncrates.admin.locations";
            case "rewards", "create-reward", "chance", "command", "experience", "money", "rarity",
                    "permissions", "limits", "messages", "effects", "enabled", "reward-order", "clear" ->
                    "plexoncrates.admin.rewards";
            case "validate", "reload" -> "plexoncrates.admin.reload";
            case "backup" -> "plexoncrates.admin.backup";
            case "diagnose" -> "plexoncrates.admin.diagnose";
            case "takeover-draft", "confirm-takeover" -> "plexoncrates.admin.takeover";
            case "cancel-takeover" -> "";
            default -> sectionPermission(kind);
        };
    }

    private static String sectionPermission(MenuHolder.Kind kind) {
        return switch (kind) {
            case EDITOR, CRATE_LIST, CONFIRM_CRATE_DELETE -> "plexoncrates.admin.crates";
            case KEY_LIST, KEY_TEMPLATE, CONFIRM_KEY_DELETE -> "plexoncrates.admin.keys";
            case KEY_SELECT -> "plexoncrates.admin.crates";
            case REWARDS, REWARD_BUILDER, GLOBAL_REWARDS, CONFIRM_DELETE -> "plexoncrates.admin.rewards";
            case CONFIRM_TAKEOVER -> "plexoncrates.admin.takeover";
            case LOCATIONS, WAND_SELECT, CONFIRM_UNLINK -> "plexoncrates.admin.locations";
            default -> "plexoncrates.admin.gui";
        };
    }

    private void addNavigation(Inventory inventory, MenuHolder holder, String path, int page, int total, int pageSize, String... extras) {
        MenuConfig menus = plugin.menusConfig();
        for (String action : extras) put(inventory, holder, path, action, action);
        if (menus.strings(path + ".unused").isEmpty()) { // no-op: keeps navigation fully config driven
            if (page > 0) put(inventory, holder, path, "previous", "previous");
            put(inventory, holder, path, "back", "dashboard");
            if ((page + 1) * pageSize < total) put(inventory, holder, path, "next", "next");
        }
    }

    private void put(Inventory inventory, MenuHolder holder, String path, String item, String action,
                     net.kyori.adventure.text.minimessage.tag.resolver.TagResolver... tags) {
        int slot = plugin.menusConfig().slot(path + "." + item);
        inventory.setItem(slot, plugin.menusConfig().item(path + "." + item, tags));
        holder.bind(slot, action);
    }

    private void put(Inventory inventory, MenuHolder holder, String path, String item, String action, String value) {
        int slot = plugin.menusConfig().slot(path + "." + item);
        inventory.setItem(slot, plugin.menusConfig().item(path + "." + item));
        holder.bind(slot, action, value);
    }

    private Inventory create(MenuHolder holder, int size, Component title) {
        Inventory inventory = Bukkit.createInventory(holder, size, title);
        holder.attach(inventory);
        return inventory;
    }

    private void open(Player player, Inventory inventory) {
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            ItemStack item = inventory.getItem(slot);
            if (item == null || item.getType().isAir()) continue;
            ItemStack display = item.clone();
            display.editMeta(meta -> meta.getPersistentDataContainer()
                    .set(editorItem, PersistentDataType.BYTE, (byte) 1));
            inventory.setItem(slot, display);
        }
        player.openInventory(inventory);
    }

    private boolean isEditorItem(ItemStack item) {
        return item == null || item.getType().isAir() || item.hasItemMeta()
                && item.getItemMeta().getPersistentDataContainer().has(editorItem, PersistentDataType.BYTE);
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

    private static int page(int requested, int total, int size) {
        int pages = Math.max(1, (total + size - 1) / size);
        return Math.max(0, Math.min(requested, pages - 1));
    }

    private static String format(double value) {
        String formatted = String.format(Locale.ROOT, "%.3f", value);
        return formatted.replaceAll("0+$", "").replaceAll("\\.$", "");
    }

    private static String locationText(BlockPosition position) {
        return position.worldName() + " • " + position.x() + ", " + position.y() + ", " + position.z();
    }

    private static List<Component> componentLines(String value) {
        List<Component> lines = java.util.Arrays.stream(value.split("\\|", -1)).map(String::trim)
                .filter(line -> !line.isEmpty()).map(Text::parse).toList();
        if (lines.isEmpty() || lines.size() > 12) throw new IllegalArgumentException("Provide 1-12 non-empty lines");
        return lines;
    }

    private static java.util.Set<String> csv(String value) {
        if (value.trim().equals("-") || value.isBlank()) return java.util.Set.of();
        return java.util.Arrays.stream(value.split(",")).map(String::trim).filter(part -> !part.isEmpty())
                .map(part -> part.toLowerCase(Locale.ROOT)).collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private static String dash(String value) {
        String trimmed = value.trim();
        return trimmed.equals("-") ? "" : trimmed;
    }

    private record KeyEntry(String id, String source, ItemStack icon, boolean resolved) {
        private KeyEntry { icon = icon.clone(); }
        @Override public ItemStack icon() { return icon.clone(); }
    }
    private record GlobalReward(Crate crate, CrateReward reward) {}
}
