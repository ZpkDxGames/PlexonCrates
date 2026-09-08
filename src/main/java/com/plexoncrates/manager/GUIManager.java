package com.plexoncrates.manager;

import com.plexoncrates.config.ConfigManager;
import com.plexoncrates.core.PlexonCrates;
import com.plexoncrates.crate.Crate;
import com.plexoncrates.crate.Reward;
import com.plexoncrates.crate.RewardAction;
import com.plexoncrates.database.DatabaseManager;
import com.plexoncrates.item.ExactItemSnapshot;
import com.plexoncrates.util.ColorUtil;
import com.plexoncrates.util.ItemCodec;
import com.plexoncrates.util.WeightedSelector;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

/**
 * GUI-first PlexonCrates management surface.
 *
 * <p>Administrative screens are backed by a per-admin navigation session. Holders carry the
 * session revision that created them, preventing stale inventories from mutating live state after
 * another editor page has replaced them.</p>
 */
public final class GUIManager {
    private static final int[] GRID = {
            10, 11, 12, 13, 14, 15, 16,
            19, 20, 21, 22, 23, 24, 25,
            28, 29, 30, 31, 32, 33, 34,
            37, 38, 39, 40, 41, 42, 43
    };

    private final PlexonCrates plugin;
    private final ConfigManager config;
    private final CrateManager crates;
    private final DatabaseManager database;
    private final Map<UUID, PlexonHolder> openMenus = new ConcurrentHashMap<>();
    private final Map<UUID, EditorSession> editorSessions = new ConcurrentHashMap<>();

    public GUIManager(PlexonCrates plugin, ConfigManager config, CrateManager crates, DatabaseManager database) {
        this.plugin = plugin;
        this.config = config;
        this.crates = crates;
        this.database = database;
    }

    // -------------------------------------------------------------------------
    // Player surfaces
    // -------------------------------------------------------------------------

    public void openPlayerMenu(Player player) {
        PlayerMenuHolder holder = new PlayerMenuHolder();
        Inventory inventory = create(holder, 54, "&8PlexonCrates");
        decorateFrame(inventory);
        inventory.setItem(49, button(Material.ENDER_CHEST, "&6&lClaim Inbox",
                List.of("&7Collect rewards that did not fit", "&7in your inventory.")));
        if (player.hasPermission("plexoncrates.admin.gui")) {
            inventory.setItem(53, button(Material.COMPARATOR, "&b&lAdministration",
                    List.of("&7Open the GUI-first management center.")));
        }
        renderPlayerCrates(player, holder, inventory, Map.of());
        open(player, holder, inventory);
        database.virtualKeys(player.getUniqueId()).whenComplete((balances, error) -> runSync(() -> {
            if (!player.isOnline() || player.getOpenInventory().getTopInventory().getHolder() != holder) return;
            if (error != null) {
                plugin.getLogger().log(Level.WARNING, "Could not load virtual key balances", error);
                return;
            }
            renderPlayerCrates(player, holder, inventory, balances);
        }));
    }

    public void openPreview(Player player, Crate crate) {
        openPreview(player, crate, 0);
    }

    public void openPreview(Player player, Crate crate, int page) {
        List<Reward> rewards = crate.rewards().stream().sorted(Comparator.comparing(Reward::id)).toList();
        int maxPage = Math.max(0, (rewards.size() - 1) / 45);
        int safePage = Math.max(0, Math.min(page, maxPage));
        PreviewHolder holder = new PreviewHolder(crate.id(), safePage);
        Inventory inventory = create(holder, 54, crate.displayName() + " &8• Rewards");
        int start = safePage * 45;
        for (int index = start; index < Math.min(rewards.size(), start + 45); index++) {
            Reward reward = rewards.get(index);
            int slot = index - start;
            holder.entries.put(slot, reward.id());
            inventory.setItem(slot, rewardIcon(reward, crate.rewards()));
        }
        inventory.setItem(45, button(Material.ARROW, "&ePrevious", List.of("&7Page " + (safePage + 1))));
        inventory.setItem(49, button(Material.BARRIER, "&cBack", List.of("&7Return to crates.")));
        inventory.setItem(53, button(Material.ARROW, "&eNext", List.of("&7Page " + (safePage + 1))));
        open(player, holder, inventory);
    }

    public void openClaims(Player player, int page) {
        int safePage = Math.max(0, page);
        ClaimHolder holder = new ClaimHolder(safePage);
        Inventory inventory = create(holder, 54, "&8Claim Inbox");
        fillBottomRow(inventory);
        inventory.setItem(49, button(Material.CLOCK, "&eLoading...", List.of("&7Reading durable claims.")));
        open(player, holder, inventory);
        int limit = config.claimPageSize();
        database.claims(player.getUniqueId(), limit, safePage * limit).whenComplete((claims, error) -> runSync(() -> {
            if (!player.isOnline() || player.getOpenInventory().getTopInventory().getHolder() != holder) return;
            if (error != null) {
                plugin.getLogger().log(Level.WARNING, "Could not load claims", error);
                inventory.setItem(49, button(Material.BARRIER, "&cClaims unavailable", List.of()));
                return;
            }
            holder.claims.clear();
            for (int slot = 0; slot < Math.min(45, claims.size()); slot++) {
                DatabaseManager.ClaimRecord claim = claims.get(slot);
                try {
                    ItemStack item = ItemCodec.decodeBytes(claim.itemBytes());
                    holder.claims.put(slot, claim);
                    inventory.setItem(slot, icon(item, null, List.of("", "&7Crate: &f" + claim.crateId(),
                            "&7Reward: &f" + claim.rewardId(), "&eClick to claim")));
                } catch (RuntimeException corrupt) {
                    plugin.getLogger().log(Level.SEVERE, "Claim " + claim.id() + " contains an unreadable exact item", corrupt);
                    inventory.setItem(slot, button(Material.BARRIER, "&cUnreadable Claim",
                            List.of("&7Claim ID: &f" + claim.id(), "&cRequires administrator review.")));
                }
            }
            inventory.setItem(45, button(Material.ARROW, "&ePrevious", List.of()));
            inventory.setItem(49, button(Material.CHEST, "&6&lClaims",
                    List.of("&7Page: &f" + (safePage + 1), "&7Loaded: &f" + claims.size())));
            inventory.setItem(53, button(Material.ARROW, "&eNext", List.of()));
        }));
    }

    // -------------------------------------------------------------------------
    // Administrative editor session
    // -------------------------------------------------------------------------

    public void openAdmin(Player player) {
        EditorSession session = editorSessions.computeIfAbsent(player.getUniqueId(), EditorSession::new);
        session.reset(new EditorLocation(EditorPage.HOME, null, 0));
        renderEditor(player, session);
    }

    public void openCrateEditor(Player player, Crate crate) {
        EditorSession session = editorSession(player);
        session.navigate(new EditorLocation(EditorPage.CRATE, crate.id(), 0));
        renderEditor(player, session);
    }

    public void openRewardEditor(Player player, Crate crate) {
        openRewardEditor(player, crate, 0);
    }

    public void openRewardEditor(Player player, Crate crate, int page) {
        EditorSession session = editorSession(player);
        session.navigate(new EditorLocation(EditorPage.REWARDS, crate.id(), Math.max(0, page)));
        renderEditor(player, session);
    }

    private EditorSession editorSession(Player player) {
        return editorSessions.computeIfAbsent(player.getUniqueId(), id -> {
            EditorSession created = new EditorSession(id);
            created.reset(new EditorLocation(EditorPage.HOME, null, 0));
            return created;
        });
    }

    private void renderEditor(Player player, EditorSession session) {
        switch (session.current.page) {
            case HOME -> renderEditorHome(player, session);
            case CRATES -> renderCratesBrowser(player, session);
            case CRATE -> renderCrateOverview(player, session);
            case REWARDS -> renderRewardBrowser(player, session);
            case KEYS -> renderKeysBrowser(player, session);
            case SETTINGS -> renderSettings(player, session);
            case MIGRATION -> renderMigration(player, session);
            case HEALTH -> renderHealth(player, session);
        }
    }

    private void renderEditorHome(Player player, EditorSession session) {
        EditorHolder holder = new EditorHolder(session.current, session.revision);
        Inventory inventory = create(holder, 54, "&8PlexonCrates • Editor");
        decorateFrame(inventory);

        inventory.setItem(20, button(Material.CHEST, "&6&lCrates",
                List.of("&7Create, browse and configure crates.", "&7Loaded: &f" + crates.all().size())));
        inventory.setItem(22, button(Material.TRIPWIRE_HOOK, "&e&lKeys",
                List.of("&7Review exact physical key identities", "&7and crate bindings.")));
        inventory.setItem(24, button(Material.COMPARATOR, "&f&lSettings",
                List.of("&7Runtime safety and behavior overview.")));
        inventory.setItem(29, button(Material.MAP, "&b&lMigration & Diagnostics",
                List.of("&7Phoenix source status, migration tools", "&7and definition diagnostics.")));
        inventory.setItem(31, button(Material.HEART_OF_THE_SEA, "&b&lPlexon Integration Health",
                List.of("&7Exact-item and crate validation health.")));
        inventory.setItem(33, button(Material.SUNFLOWER, "&a&lRefresh View",
                List.of("&7Refresh the editor from current runtime state.", "&7No destructive reload is performed.")));
        inventory.setItem(45, button(Material.ARROW, "&ePlayer Menu", List.of("&7Return to /crates.")));
        inventory.setItem(49, button(Material.BOOK, "&fPlexonCrates " + plugin.getDescription().getVersion(),
                List.of("&7Session-aware 4.0 editor", "&7Revision: &f" + session.revision)));
        open(player, holder, inventory);
    }

    private void renderCratesBrowser(Player player, EditorSession session) {
        List<Crate> values = filteredSortedCrates(session);
        int maxPage = Math.max(0, (values.size() - 1) / GRID.length);
        int page = Math.max(0, Math.min(session.current.pageIndex, maxPage));
        if (page != session.current.pageIndex) session.replaceCurrent(new EditorLocation(EditorPage.CRATES, null, page));

        CratesHolder holder = new CratesHolder(session.current, session.revision);
        Inventory inventory = create(holder, 54, "&8Crates • " + (page + 1) + "/" + (maxPage + 1));
        decorateFrame(inventory);
        int start = page * GRID.length;
        for (int index = start; index < Math.min(values.size(), start + GRID.length); index++) {
            Crate crate = values.get(index);
            int slot = GRID[index - start];
            holder.entries.put(slot, crate.id());
            List<String> lore = new ArrayList<>();
            lore.add("&7ID: &f" + crate.id());
            lore.add("&7Status: " + (crate.enabled() ? "&aEnabled" : "&cDisabled"));
            lore.add("&7Rewards: &f" + crate.rewards().size());
            lore.add("&7Keys: &f1 legacy/native binding");
            lore.add("&7Locations: &f" + crate.locations().size());
            lore.add("&7Validation: " + crateValidationColor(crate));
            lore.add("");
            lore.add("&eClick to open crate overview");
            inventory.setItem(slot, icon(crate.icon(), crate.displayName(), lore));
        }
        inventory.setItem(45, button(Material.ARROW, "&ePrevious", List.of("&7Previous page.")));
        inventory.setItem(46, button(Material.OAK_DOOR, "&eBack", List.of("&7Return to editor home.")));
        inventory.setItem(47, button(Material.HOPPER, "&bFilter: &f" + session.filter.name(),
                List.of("&eClick to cycle crate filter.")));
        inventory.setItem(49, button(Material.LIME_DYE, "&a&lCreate Crate",
                List.of("&7Creates a new editable definition.", "&7Publication safety will be expanded in 4.0.")));
        inventory.setItem(51, button(Material.REPEATER, "&bSort: &f" + session.sort.name(),
                List.of("&eClick to cycle sorting.")));
        inventory.setItem(53, button(Material.ARROW, "&eNext", List.of("&7Next page.")));
        open(player, holder, inventory);
    }

    private void renderCrateOverview(Player player, EditorSession session) {
        Crate crate = crates.find(session.current.crateId).orElse(null);
        if (crate == null) {
            session.navigate(new EditorLocation(EditorPage.CRATES, null, 0));
            renderEditor(player, session);
            return;
        }
        CrateEditorHolder holder = new CrateEditorHolder(session.current, session.revision, crate.id());
        Inventory inventory = create(holder, 54, "&8Crate • " + crate.id());
        decorateFrame(inventory);

        inventory.setItem(4, icon(crate.icon(), crate.displayName(), List.of(
                "&7ID: &f" + crate.id(),
                "&7Validation: " + crateValidationColor(crate),
                "&7Locations: &f" + crate.locations().size())));
        inventory.setItem(10, button(crate.enabled() ? Material.LIME_DYE : Material.GRAY_DYE,
                crate.enabled() ? "&a&lEnabled" : "&7&lDisabled",
                List.of("&7Published runtime status.", "&eClick to toggle.")));
        inventory.setItem(12, button(Material.CHEST, "&6&lRewards",
                List.of("&7Rewards: &f" + crate.rewards().size(), "&7Exact drag-to-add supported.", "&eClick to manage.")));
        inventory.setItem(14, icon(crate.keyItem(), "&e&lKey Binding", List.of(
                "&7Current key identity", "&7Fingerprint: &f" + shortFingerprint(crate.keyItem()), "&eClick to inspect keys.")));
        inventory.setItem(16, button(Material.ENDER_EYE, "&e&lPhysical Locations",
                List.of("&7Linked blocks: &f" + crate.locations().size(), "&7Use /crates link " + crate.id(),
                        "&7while location GUI actions are expanded.")));
        inventory.setItem(19, button(Material.CLOCK, "&b&lOpening Animation",
                List.of("&7Current: &f" + crate.animation(), "&eClick to cycle preset.")));
        inventory.setItem(20, button(Material.BLAZE_POWDER, "&d&lIdle Effect",
                List.of("&7Current: &f" + crate.idleEffect(), "&eClick to cycle preset.")));
        inventory.setItem(21, button(Material.ENDER_CHEST, "&f&lPreview",
                List.of("&7Preview reward presentation.", "&eClick to open.")));
        inventory.setItem(23, button(Material.NAME_TAG, "&fIdentity & Display",
                List.of("&7Display name and exact crate icon", "&7are preserved by the native item codec.")));
        inventory.setItem(25, button(Material.PAPER, "&fAccess / Cost / Cooldown",
                List.of("&7Dedicated editor plumbing is staged", "&7for the 4.0 draft/publish model.")));
        inventory.setItem(29, button(Material.COMPARATOR, "&fValidate",
                validationLore(crate)));
        inventory.setItem(31, button(Material.NETHER_STAR, "&bExact Item Diagnostics",
                List.of("&7Crate icon: &f" + shortFingerprint(crate.icon()),
                        "&7Key item: &f" + shortFingerprint(crate.keyItem()),
                        "&7Reward items are fingerprinted in Rewards.")));
        inventory.setItem(40, button(Material.ARROW, "&eBack", List.of("&7Return to the previous editor page.")));
        open(player, holder, inventory);
    }

    private void renderRewardBrowser(Player player, EditorSession session) {
        Crate crate = crates.find(session.current.crateId).orElse(null);
        if (crate == null) {
            session.back();
            renderEditor(player, session);
            return;
        }
        List<Reward> rewards = crate.rewards().stream().sorted(Comparator.comparing(Reward::id)).toList();
        int maxPage = Math.max(0, (rewards.size() - 1) / 45);
        int page = Math.max(0, Math.min(session.current.pageIndex, maxPage));
        if (page != session.current.pageIndex) {
            session.replaceCurrent(new EditorLocation(EditorPage.REWARDS, crate.id(), page));
        }
        RewardEditorHolder holder = new RewardEditorHolder(session.current, session.revision, crate.id(), page);
        Inventory inventory = create(holder, 54, "&8Rewards • " + crate.id());
        int start = page * 45;
        long totalWeight = crate.rewards().stream().filter(Reward::enabled).mapToLong(Reward::weight).sum();
        for (int index = start; index < Math.min(rewards.size(), start + 45); index++) {
            Reward reward = rewards.get(index);
            int slot = index - start;
            holder.entries.put(slot, reward.id());
            List<String> lore = new ArrayList<>();
            lore.add("&7Reward ID: &f" + reward.id());
            lore.add("&7Weight: &f" + reward.weight());
            lore.add(String.format(java.util.Locale.ROOT, "&7Chance: &e%.2f%%",
                    WeightedSelector.chance(reward, crate.rewards())));
            lore.add("&7Actions: &f" + reward.actions().size());
            lore.add("&7Fingerprint: &f" + shortFingerprint(reward.displayItem()));
            lore.add("");
            lore.add("&aLeft-click: +1 weight");
            lore.add("&aShift-left: +10 weight");
            lore.add("&cRight-click: -1 weight");
            lore.add("&cShift-right: -10 weight");
            lore.add("&8Deletion now requires a dedicated confirmation flow.");
            inventory.setItem(slot, icon(reward.displayItem(), null, lore));
        }
        inventory.setItem(45, button(Material.ARROW, "&ePrevious", List.of("&7Previous reward page.")));
        inventory.setItem(47, button(Material.HOPPER, "&a&lAdd Exact Reward",
                List.of("&7Drag onto an empty reward slot or", "&7shift-click from your inventory.",
                        "&7The source item is never consumed.")));
        inventory.setItem(49, button(Material.ARROW, "&eBack", List.of("&7Return to crate overview.")));
        inventory.setItem(51, button(Material.COMPARATOR, "&bWeight Summary",
                List.of("&7Enabled total: &f" + totalWeight, "&7Selection remains integer-weight based.")));
        inventory.setItem(53, button(Material.ARROW, "&eNext", List.of("&7Next reward page.")));
        open(player, holder, inventory);
    }

    private void renderKeysBrowser(Player player, EditorSession session) {
        KeysHolder holder = new KeysHolder(session.current, session.revision);
        Inventory inventory = create(holder, 54, "&8Keys • Registry");
        decorateFrame(inventory);
        List<Crate> values = crates.all().stream().sorted(Comparator.comparing(Crate::id)).toList();
        for (int index = 0; index < Math.min(values.size(), GRID.length); index++) {
            Crate crate = values.get(index);
            int slot = GRID[index];
            holder.entries.put(slot, crate.id());
            inventory.setItem(slot, icon(crate.keyItem(), crate.keyDisplayName(), List.of(
                    "&7Crate: &f" + crate.id(),
                    "&7Fingerprint: &f" + shortFingerprint(crate.keyItem()),
                    "&7Source: &fLegacy/native crate binding",
                    "", "&eClick to open crate overview")));
        }
        inventory.setItem(45, button(Material.ARROW, "&eBack", List.of("&7Return to editor home.")));
        inventory.setItem(49, button(Material.TRIPWIRE_HOOK, "&eKey Registry",
                List.of("&7PlexonKeys provider mapping will use", "&7exact templates rather than lore reconstruction.")));
        open(player, holder, inventory);
    }

    private void renderSettings(Player player, EditorSession session) {
        EditorHolder holder = new EditorHolder(session.current, session.revision);
        Inventory inventory = create(holder, 45, "&8PlexonCrates • Settings");
        fill(inventory, Material.GRAY_STAINED_GLASS_PANE);
        inventory.setItem(10, statusButton("Virtual Keys", config.virtualKeysEnabled()));
        inventory.setItem(12, statusButton("Physical Keys", config.physicalKeysEnabled()));
        inventory.setItem(14, statusButton("Claims", config.claimsEnabled()));
        inventory.setItem(16, statusButton("Effects", config.effectsEnabled()));
        inventory.setItem(20, button(Material.CLOCK, "&fOpening Duration",
                List.of("&7Ticks: &f" + config.openingDurationTicks())));
        inventory.setItem(22, button(Material.BLAZE_POWDER, "&fIdle Budget",
                List.of("&7Interval: &f" + config.idleIntervalTicks() + " ticks",
                        "&7Max blocks/pass: &f" + config.maxBlocksPerIdlePass())));
        inventory.setItem(24, button(Material.ENDER_CHEST, "&fClaims Page Size",
                List.of("&7Size: &f" + config.claimPageSize())));
        inventory.setItem(40, button(Material.ARROW, "&eBack", List.of("&7Return to editor home.")));
        open(player, holder, inventory);
    }

    private void renderMigration(Player player, EditorSession session) {
        EditorHolder holder = new EditorHolder(session.current, session.revision);
        Inventory inventory = create(holder, 45, "&8Migration & Diagnostics");
        fill(inventory, Material.GRAY_STAINED_GLASS_PANE);
        boolean sourceDetected = Files.isDirectory(plugin.phoenixMigration().sourceDirectory());
        inventory.setItem(11, statusButton("Phoenix Source", sourceDetected));
        inventory.setItem(13, button(Material.MAP, "&bMigration Planning",
                List.of("&7Source: &f" + plugin.phoenixMigration().sourceDirectory().getFileName(),
                        "&7Use: &f/crates migrate phoenix plan",
                        "&7Imports remain confirmation-gated.")));
        inventory.setItem(15, button(Material.ANVIL, "&eDefinition Fidelity Repair",
                List.of("&7History is never re-applied by item repair.",
                        "&7The dedicated repair transaction is the next", "&7migration phase after native snapshots.")));
        inventory.setItem(40, button(Material.ARROW, "&eBack", List.of("&7Return to editor home.")));
        open(player, holder, inventory);
    }

    private void renderHealth(Player player, EditorSession session) {
        HealthSummary health = inspectHealth();
        EditorHolder holder = new EditorHolder(session.current, session.revision);
        Inventory inventory = create(holder, 45, "&8PlexonCrates • Health");
        fill(inventory, Material.GRAY_STAINED_GLASS_PANE);
        inventory.setItem(11, button(health.invalidCrates == 0 ? Material.LIME_DYE : Material.RED_DYE,
                "&fCrate Validation",
                List.of("&7Valid: &a" + health.validCrates, "&7Invalid: &c" + health.invalidCrates)));
        inventory.setItem(13, button(health.itemFailures == 0 ? Material.HEART_OF_THE_SEA : Material.BARRIER,
                "&bExact Item Fidelity",
                List.of("&7Snapshots verified: &f" + health.itemsVerified,
                        "&7Failures: " + (health.itemFailures == 0 ? "&a0" : "&c" + health.itemFailures),
                        "&7Format: &f" + ExactItemSnapshot.FORMAT_NAME)));
        inventory.setItem(15, button(Material.CHEST, "&fRuntime",
                List.of("&7Loaded crates: &f" + crates.all().size(),
                        "&7Database pool: &f" + config.databasePoolSize(),
                        "&7Claims: " + (config.claimsEnabled() ? "&aEnabled" : "&cDisabled"))));
        inventory.setItem(40, button(Material.ARROW, "&eBack", List.of("&7Return to editor home.")));
        open(player, holder, inventory);
    }

    // -------------------------------------------------------------------------
    // Opening animation integration
    // -------------------------------------------------------------------------

    public Inventory createOpeningInventory(Player player, String title, int size) {
        OpeningHolder holder = new OpeningHolder(player.getUniqueId());
        Inventory inventory = create(holder, size, title);
        openMenus.put(player.getUniqueId(), holder);
        return inventory;
    }

    // -------------------------------------------------------------------------
    // Event handling
    // -------------------------------------------------------------------------

    public void handleClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        Inventory top = event.getView().getTopInventory();
        if (!(top.getHolder() instanceof PlexonHolder holder)) return;

        // Protected inventories never permit vanilla transfers/swaps/collections.
        event.setCancelled(true);
        if (holder instanceof OpeningHolder) return;

        if (holder instanceof EditorHolder editorHolder && !isCurrentRevision(player, editorHolder)) {
            player.sendMessage(config.prefix() + "§eThis editor view is stale and has been refreshed.");
            renderEditor(player, editorSession(player));
            return;
        }

        int raw = event.getRawSlot();
        if (holder instanceof PlayerMenuHolder menu) {
            if (raw == 49) {
                openClaims(player, 0);
                return;
            }
            if (raw == 53 && player.hasPermission("plexoncrates.admin.gui")) {
                openAdmin(player);
                return;
            }
            String crateId = menu.entries.get(raw);
            if (crateId == null) return;
            crates.find(crateId).ifPresent(crate -> {
                if (event.isRightClick()) plugin.openings().openVirtual(player, crate);
                else openPreview(player, crate);
            });
            return;
        }

        if (holder instanceof CratesHolder menu) {
            handleCratesBrowserClick(player, event, menu);
            return;
        }
        if (holder instanceof CrateEditorHolder editor) {
            handleCrateOverviewClick(player, raw, editor);
            return;
        }
        if (holder instanceof RewardEditorHolder editor) {
            handleRewardEditorClick(player, event, editor);
            return;
        }
        if (holder instanceof KeysHolder keys) {
            if (raw == 45) editorBack(player);
            else {
                String crateId = keys.entries.get(raw);
                if (crateId != null) crates.find(crateId).ifPresent(crate -> openCrateEditor(player, crate));
            }
            return;
        }
        if (holder instanceof EditorHolder editor) {
            handleGenericEditorClick(player, raw, editor);
            return;
        }
        if (holder instanceof PreviewHolder preview) {
            Crate crate = crates.find(preview.crateId).orElse(null);
            if (crate == null) return;
            if (raw == 45) openPreview(player, crate, preview.page - 1);
            else if (raw == 49) openPlayerMenu(player);
            else if (raw == 53) openPreview(player, crate, preview.page + 1);
            return;
        }
        if (holder instanceof ClaimHolder claims) {
            if (raw == 45) {
                openClaims(player, claims.page - 1);
                return;
            }
            if (raw == 53) {
                openClaims(player, claims.page + 1);
                return;
            }
            DatabaseManager.ClaimRecord claim = claims.claims.get(raw);
            if (claim != null) deliverClaim(player, claim, claims.page);
        }
    }

    public void handleDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        Inventory top = event.getView().getTopInventory();
        if (!(top.getHolder() instanceof PlexonHolder holder)) return;
        event.setCancelled(true);
        if (!(holder instanceof RewardEditorHolder editor) || !isCurrentRevision(player, editor)) return;

        boolean emptyRewardTarget = event.getRawSlots().stream()
                .filter(slot -> slot >= 0 && slot < 45)
                .anyMatch(slot -> !editor.entries.containsKey(slot));
        ItemStack cursor = event.getOldCursor();
        if (emptyRewardTarget && cursor != null && !cursor.getType().isAir()) {
            captureReward(player, editor.crateId, cursor);
        }
    }

    public void handleClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;
        if (!(event.getInventory().getHolder() instanceof PlexonHolder holder)) return;
        if (holder instanceof OpeningHolder && config.lockOpeningInventory()
                && plugin.animations().isOpening(player.getUniqueId()) && player.isOnline()) {
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (player.isOnline() && plugin.animations().isOpening(player.getUniqueId())) {
                    player.openInventory(holder.getInventory());
                }
            });
            return;
        }
        openMenus.remove(player.getUniqueId(), holder);
        // Editor sessions intentionally survive inventory close for rapid resume and stale-view protection.
    }

    private void handleGenericEditorClick(Player player, int raw, EditorHolder holder) {
        EditorSession session = editorSession(player);
        switch (holder.location.page) {
            case HOME -> {
                if (raw == 20) navigate(player, new EditorLocation(EditorPage.CRATES, null, 0));
                else if (raw == 22) navigate(player, new EditorLocation(EditorPage.KEYS, null, 0));
                else if (raw == 24) navigate(player, new EditorLocation(EditorPage.SETTINGS, null, 0));
                else if (raw == 29) navigate(player, new EditorLocation(EditorPage.MIGRATION, null, 0));
                else if (raw == 31) navigate(player, new EditorLocation(EditorPage.HEALTH, null, 0));
                else if (raw == 33) {
                    session.touch();
                    renderEditor(player, session);
                } else if (raw == 45) openPlayerMenu(player);
            }
            case SETTINGS, MIGRATION, HEALTH -> {
                if (raw == 40) editorBack(player);
            }
            default -> {
                // Specialized editor holders handle the remaining pages.
            }
        }
    }

    private void handleCratesBrowserClick(Player player, InventoryClickEvent event, CratesHolder holder) {
        EditorSession session = editorSession(player);
        int raw = event.getRawSlot();
        if (raw == 45) {
            navigateReplace(player, new EditorLocation(EditorPage.CRATES, null, Math.max(0, holder.location.pageIndex - 1)));
            return;
        }
        if (raw == 46) {
            editorBack(player);
            return;
        }
        if (raw == 47) {
            session.filter = session.filter.next();
            session.replaceCurrent(new EditorLocation(EditorPage.CRATES, null, 0));
            renderEditor(player, session);
            return;
        }
        if (raw == 49) {
            Crate created = crates.createGenerated();
            openCrateEditor(player, created);
            return;
        }
        if (raw == 51) {
            session.sort = session.sort.next();
            session.replaceCurrent(new EditorLocation(EditorPage.CRATES, null, 0));
            renderEditor(player, session);
            return;
        }
        if (raw == 53) {
            navigateReplace(player, new EditorLocation(EditorPage.CRATES, null, holder.location.pageIndex + 1));
            return;
        }
        String crateId = holder.entries.get(raw);
        if (crateId != null) crates.find(crateId).ifPresent(crate -> openCrateEditor(player, crate));
    }

    private void handleCrateOverviewClick(Player player, int raw, CrateEditorHolder editor) {
        Crate crate = crates.find(editor.crateId).orElse(null);
        if (crate == null) {
            editorBack(player);
            return;
        }
        if (raw == 10) {
            crates.toggleEnabled(crate.id());
            editorSession(player).touch();
            renderEditor(player, editorSession(player));
        } else if (raw == 12) {
            openRewardEditor(player, crate);
        } else if (raw == 14) {
            navigate(player, new EditorLocation(EditorPage.KEYS, null, 0));
        } else if (raw == 19) {
            crates.cycleAnimation(crate.id());
            editorSession(player).touch();
            renderEditor(player, editorSession(player));
        } else if (raw == 20) {
            crates.cycleIdleEffect(crate.id());
            editorSession(player).touch();
            renderEditor(player, editorSession(player));
        } else if (raw == 21) {
            openPreview(player, crate);
        } else if (raw == 40) {
            editorBack(player);
        }
    }

    private void handleRewardEditorClick(Player player, InventoryClickEvent event, RewardEditorHolder editor) {
        Crate crate = crates.find(editor.crateId).orElse(null);
        if (crate == null) {
            editorBack(player);
            return;
        }
        int raw = event.getRawSlot();
        if (raw == 45) {
            navigateReplace(player, new EditorLocation(EditorPage.REWARDS, crate.id(), Math.max(0, editor.page - 1)));
            return;
        }
        if (raw == 49) {
            editorBack(player);
            return;
        }
        if (raw == 53) {
            navigateReplace(player, new EditorLocation(EditorPage.REWARDS, crate.id(), editor.page + 1));
            return;
        }
        if (raw >= 0 && raw < 45) {
            String rewardId = editor.entries.get(raw);
            if (rewardId != null) {
                ClickType click = event.getClick();
                if (click == ClickType.LEFT) crates.adjustRewardWeight(crate.id(), rewardId, 1);
                else if (click == ClickType.SHIFT_LEFT) crates.adjustRewardWeight(crate.id(), rewardId, 10);
                else if (click == ClickType.RIGHT) crates.adjustRewardWeight(crate.id(), rewardId, -1);
                else if (click == ClickType.SHIFT_RIGHT) crates.adjustRewardWeight(crate.id(), rewardId, -10);
                else return;
                editorSession(player).touch();
                renderEditor(player, editorSession(player));
                return;
            }
            ItemStack cursor = event.getCursor();
            if (cursor != null && !cursor.getType().isAir()) {
                captureReward(player, crate.id(), cursor);
                return;
            }
        }
        if (raw >= event.getView().getTopInventory().getSize() && event.isShiftClick()) {
            ItemStack clicked = event.getCurrentItem();
            if (clicked != null && !clicked.getType().isAir()) captureReward(player, crate.id(), clicked);
        }
    }

    private void captureReward(Player player, String crateId, ItemStack source) {
        try {
            // Release gate: validate a complete native round-trip before mutating the crate draft/runtime model.
            ExactItemSnapshot sourceSnapshot = ItemCodec.snapshot(source);
            Reward added = crates.addCapturedReward(crateId, source.clone(), 10);
            String storedHash = ItemCodec.fingerprint(added.displayItem());
            if (!sourceSnapshot.sha256().equals(storedHash)) {
                crates.removeReward(crateId, added.id());
                throw new IllegalStateException("Captured reward changed during the in-memory round-trip");
            }
            player.sendMessage(config.message("reward-added", "item", source.getType().name(), "crate", crateId));
            player.sendMessage(config.prefix() + "§7Exact fingerprint: §f" + sourceSnapshot.sha256().substring(0, 12));
            EditorSession session = editorSession(player);
            session.touch();
            renderEditor(player, session);
        } catch (Exception error) {
            player.sendMessage(config.prefix() + "§cCould not add exact reward: " + error.getMessage());
            plugin.getLogger().log(Level.WARNING, "Exact reward capture failed for " + crateId, error);
        }
    }

    private void deliverClaim(Player player, DatabaseManager.ClaimRecord claim, int page) {
        final ItemStack item;
        try {
            item = ItemCodec.decodeBytes(claim.itemBytes());
        } catch (RuntimeException corrupt) {
            player.sendMessage(config.prefix() + "§cThis claim is unreadable and was left untouched for administrator review.");
            plugin.getLogger().log(Level.SEVERE, "Refusing to substitute an unreadable claim item " + claim.id(), corrupt);
            return;
        }
        if (!canFit(player.getInventory().getStorageContents(), item)) {
            player.sendMessage(config.message("claim-blocked"));
            return;
        }
        database.deleteClaim(claim.id(), player.getUniqueId()).whenComplete((deleted, error) -> runSync(() -> {
            if (error != null || !Boolean.TRUE.equals(deleted)) {
                player.sendMessage(config.prefix() + "§cCould not finalize this claim.");
                return;
            }
            if (!player.isOnline()) {
                database.queueClaim(player.getUniqueId(), claim.crateId(), claim.rewardId(), claim.itemBytes());
                return;
            }
            Map<Integer, ItemStack> leftovers = player.getInventory().addItem(item.clone());
            if (!leftovers.isEmpty()) {
                for (ItemStack leftover : leftovers.values()) {
                    database.queueClaim(player.getUniqueId(), claim.crateId(), claim.rewardId(), ItemCodec.encodeBytes(leftover));
                }
                player.sendMessage(config.message("claim-blocked"));
            } else {
                player.sendMessage(config.message("claim-delivered"));
            }
            openClaims(player, page);
        }));
    }

    // -------------------------------------------------------------------------
    // Editor helpers
    // -------------------------------------------------------------------------

    private void navigate(Player player, EditorLocation location) {
        EditorSession session = editorSession(player);
        session.navigate(location);
        renderEditor(player, session);
    }

    private void navigateReplace(Player player, EditorLocation location) {
        EditorSession session = editorSession(player);
        session.replaceCurrent(location);
        renderEditor(player, session);
    }

    private void editorBack(Player player) {
        EditorSession session = editorSession(player);
        session.back();
        renderEditor(player, session);
    }

    private boolean isCurrentRevision(Player player, EditorHolder holder) {
        EditorSession session = editorSessions.get(player.getUniqueId());
        return session != null && session.revision == holder.revision && session.current.equals(holder.location);
    }

    private List<Crate> filteredSortedCrates(EditorSession session) {
        java.util.stream.Stream<Crate> stream = crates.all().stream();
        if (session.filter == CrateFilter.ENABLED) stream = stream.filter(Crate::enabled);
        else if (session.filter == CrateFilter.DISABLED) stream = stream.filter(crate -> !crate.enabled());

        Comparator<Crate> comparator = switch (session.sort) {
            case ID -> Comparator.comparing(Crate::id);
            case STATUS -> Comparator.comparing(Crate::enabled).reversed().thenComparing(Crate::id);
            case REWARDS -> Comparator.comparingInt((Crate crate) -> crate.rewards().size()).reversed()
                    .thenComparing(Crate::id);
        };
        return stream.sorted(comparator).toList();
    }

    private HealthSummary inspectHealth() {
        int validCrates = 0;
        int invalidCrates = 0;
        int itemsVerified = 0;
        int itemFailures = 0;
        for (Crate crate : crates.all()) {
            if (crateValid(crate)) validCrates++; else invalidCrates++;
            List<ItemStack> items = new ArrayList<>();
            items.add(crate.icon());
            items.add(crate.keyItem());
            for (Reward reward : crate.rewards()) {
                items.add(reward.displayItem());
                for (RewardAction action : reward.actions()) if (action.item() != null) items.add(action.item());
            }
            for (ItemStack item : items) {
                try {
                    ItemCodec.snapshot(item);
                    itemsVerified++;
                } catch (RuntimeException failure) {
                    itemFailures++;
                }
            }
        }
        return new HealthSummary(validCrates, invalidCrates, itemsVerified, itemFailures);
    }

    private static boolean crateValid(Crate crate) {
        long weight = crate.rewards().stream().filter(Reward::enabled).mapToLong(Reward::weight).sum();
        return crate.rewards().stream().anyMatch(Reward::enabled) && weight > 0;
    }

    private static String crateValidationColor(Crate crate) {
        return crateValid(crate) ? "&aValid" : "&cInvalid";
    }

    private static List<String> validationLore(Crate crate) {
        List<String> lines = new ArrayList<>();
        long enabled = crate.rewards().stream().filter(Reward::enabled).count();
        long weight = crate.rewards().stream().filter(Reward::enabled).mapToLong(Reward::weight).sum();
        lines.add("&7Enabled rewards: &f" + enabled);
        lines.add("&7Positive total weight: " + (weight > 0 ? "&aYes" : "&cNo"));
        lines.add("&7State: " + crateValidationColor(crate));
        return lines;
    }

    private static String shortFingerprint(ItemStack item) {
        try {
            return ItemCodec.fingerprint(item).substring(0, 12);
        } catch (RuntimeException failure) {
            return "§cINVALID";
        }
    }

    private static ItemStack statusButton(String name, boolean enabled) {
        return button(enabled ? Material.LIME_DYE : Material.RED_DYE, "&f" + name,
                List.of("&7State: " + (enabled ? "&aEnabled" : "&cDisabled")));
    }

    // -------------------------------------------------------------------------
    // Generic inventory helpers
    // -------------------------------------------------------------------------

    private void renderPlayerCrates(Player player, PlayerMenuHolder holder, Inventory inventory,
                                    Map<String, Long> virtualBalances) {
        holder.entries.clear();
        for (int slot : GRID) inventory.setItem(slot, null);
        List<Crate> values = crates.all().stream().filter(Crate::enabled).sorted(Comparator.comparing(Crate::id)).toList();
        for (int index = 0; index < Math.min(values.size(), GRID.length); index++) {
            Crate crate = values.get(index);
            int slot = GRID[index];
            holder.entries.put(slot, crate.id());
            List<String> lore = new ArrayList<>(crate.description());
            lore.add("");
            lore.add("&7Virtual keys: &f" + virtualBalances.getOrDefault(crate.id(), 0L));
            lore.add("&7Physical keys: &f" + plugin.keys().countPhysical(player, crate));
            lore.add("&7Animation: &f" + crate.animation());
            lore.add("");
            lore.add("&eLeft-click to preview");
            lore.add("&aRight-click to open with a virtual key");
            inventory.setItem(slot, icon(crate.icon(), crate.displayName(), lore));
        }
    }

    private static boolean canFit(ItemStack[] storage, ItemStack item) {
        int remaining = item.getAmount();
        for (ItemStack slot : storage) {
            if (slot == null || slot.getType().isAir()) remaining -= item.getMaxStackSize();
            else if (slot.isSimilar(item)) remaining -= Math.max(0, slot.getMaxStackSize() - slot.getAmount());
            if (remaining <= 0) return true;
        }
        return remaining <= 0;
    }

    private Inventory create(PlexonHolder holder, int size, String title) {
        Inventory inventory = Bukkit.createInventory(holder, size, ColorUtil.color(title));
        holder.bind(inventory);
        return inventory;
    }

    private void open(Player player, PlexonHolder holder, Inventory inventory) {
        openMenus.put(player.getUniqueId(), holder);
        player.openInventory(inventory);
    }

    private static void decorateFrame(Inventory inventory) {
        ItemStack pane = button(Material.GRAY_STAINED_GLASS_PANE, "&8", List.of());
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            int column = slot % 9;
            if (slot < 9 || slot >= inventory.getSize() - 9 || column == 0 || column == 8) inventory.setItem(slot, pane);
        }
    }

    private static void fillBottomRow(Inventory inventory) {
        ItemStack pane = button(Material.GRAY_STAINED_GLASS_PANE, "&8", List.of());
        for (int slot = 45; slot < 54; slot++) inventory.setItem(slot, pane);
    }

    private static void fill(Inventory inventory, Material material) {
        ItemStack pane = button(material, "&8", List.of());
        for (int slot = 0; slot < inventory.getSize(); slot++) inventory.setItem(slot, pane);
    }

    private static ItemStack rewardIcon(Reward reward, Collection<Reward> all) {
        return icon(reward.displayItem(), null, List.of("", "&7Weight: &f" + reward.weight(),
                String.format(java.util.Locale.ROOT, "&7Chance: &e%.2f%%", WeightedSelector.chance(reward, all)),
                "&7Actions: &f" + reward.actions().size()));
    }

    private static ItemStack button(Material material, String name, List<String> lore) {
        return icon(new ItemStack(material), name, lore);
    }

    private static ItemStack icon(ItemStack source, String overrideName, List<String> extraLore) {
        ItemStack item = source.clone();
        ItemMeta meta = item.getItemMeta();
        if (overrideName != null) meta.setDisplayName(ColorUtil.color(overrideName));
        List<String> lore = meta.hasLore() ? new ArrayList<>(meta.getLore()) : new ArrayList<>();
        if (extraLore != null) for (String line : extraLore) lore.add(ColorUtil.color(line));
        if (!lore.isEmpty()) meta.setLore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private void runSync(Runnable action) {
        if (!plugin.isEnabled()) return;
        if (Bukkit.isPrimaryThread()) action.run();
        else Bukkit.getScheduler().runTask(plugin, action);
    }

    // -------------------------------------------------------------------------
    // Typed holders and session model
    // -------------------------------------------------------------------------

    public abstract static class PlexonHolder implements InventoryHolder {
        private Inventory inventory;
        private void bind(Inventory inventory) { this.inventory = inventory; }
        @Override public Inventory getInventory() { return inventory; }
    }

    public static final class OpeningHolder extends PlexonHolder {
        private final UUID playerId;
        public OpeningHolder(UUID playerId) { this.playerId = playerId; }
        public UUID playerId() { return playerId; }
    }

    private static final class PlayerMenuHolder extends PlexonHolder {
        private final Map<Integer, String> entries = new LinkedHashMap<>();
    }

    private static class EditorHolder extends PlexonHolder {
        private final EditorLocation location;
        private final long revision;
        private EditorHolder(EditorLocation location, long revision) {
            this.location = location;
            this.revision = revision;
        }
    }

    private static final class CratesHolder extends EditorHolder {
        private final Map<Integer, String> entries = new LinkedHashMap<>();
        private CratesHolder(EditorLocation location, long revision) { super(location, revision); }
    }

    private static final class CrateEditorHolder extends EditorHolder {
        private final String crateId;
        private CrateEditorHolder(EditorLocation location, long revision, String crateId) {
            super(location, revision);
            this.crateId = crateId;
        }
    }

    private static final class RewardEditorHolder extends EditorHolder {
        private final String crateId;
        private final int page;
        private final Map<Integer, String> entries = new LinkedHashMap<>();
        private RewardEditorHolder(EditorLocation location, long revision, String crateId, int page) {
            super(location, revision);
            this.crateId = crateId;
            this.page = page;
        }
    }

    private static final class KeysHolder extends EditorHolder {
        private final Map<Integer, String> entries = new LinkedHashMap<>();
        private KeysHolder(EditorLocation location, long revision) { super(location, revision); }
    }

    private static final class PreviewHolder extends PlexonHolder {
        private final String crateId;
        private final int page;
        private final Map<Integer, String> entries = new LinkedHashMap<>();
        private PreviewHolder(String crateId, int page) { this.crateId = crateId; this.page = page; }
    }

    private static final class ClaimHolder extends PlexonHolder {
        private final int page;
        private final Map<Integer, DatabaseManager.ClaimRecord> claims = new LinkedHashMap<>();
        private ClaimHolder(int page) { this.page = page; }
    }

    private enum EditorPage { HOME, CRATES, CRATE, REWARDS, KEYS, SETTINGS, MIGRATION, HEALTH }

    private record EditorLocation(EditorPage page, String crateId, int pageIndex) {}

    private enum CrateFilter {
        ALL, ENABLED, DISABLED;
        private CrateFilter next() { return values()[(ordinal() + 1) % values().length]; }
    }

    private enum CrateSort {
        ID, STATUS, REWARDS;
        private CrateSort next() { return values()[(ordinal() + 1) % values().length]; }
    }

    private static final class EditorSession {
        private final UUID adminId;
        private final Deque<EditorLocation> history = new ArrayDeque<>();
        private EditorLocation current;
        private long revision;
        private CrateFilter filter = CrateFilter.ALL;
        private CrateSort sort = CrateSort.ID;

        private EditorSession(UUID adminId) {
            this.adminId = adminId;
        }

        private void reset(EditorLocation location) {
            history.clear();
            current = location;
            revision++;
        }

        private void navigate(EditorLocation location) {
            if (current != null && !current.equals(location)) history.push(current);
            current = location;
            revision++;
        }

        private void replaceCurrent(EditorLocation location) {
            current = location;
            revision++;
        }

        private void back() {
            current = history.isEmpty() ? new EditorLocation(EditorPage.HOME, null, 0) : history.pop();
            revision++;
        }

        private void touch() {
            revision++;
        }
    }

    private record HealthSummary(int validCrates, int invalidCrates, int itemsVerified, int itemFailures) {}
}
