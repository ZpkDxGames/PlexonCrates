package com.antondev.crates.gui;

import com.antondev.crates.PlexonCrates;
import com.antondev.crates.database.DatabaseService;
import com.antondev.crates.config.MenuConfig;
import com.antondev.crates.config.Text;
import com.antondev.crates.domain.key.KeyPaymentPolicy;
import com.antondev.crates.domain.opening.OpenSource;
import com.antondev.crates.domain.opening.OpeningMode;
import com.antondev.crates.item.ItemSnapshotCodec;
import com.antondev.crates.model.BlockPosition;
import com.antondev.crates.model.Crate;
import com.antondev.crates.model.CrateMilestone;
import com.antondev.crates.model.CrateReward;
import com.antondev.crates.service.ChanceAllocator;
import com.antondev.crates.service.CrateRegistry;
import com.antondev.crates.service.DraftSessionService;
import com.antondev.crates.service.KeyPaymentPlanner;
import com.antondev.crates.service.MilestoneProgressService;
import com.antondev.crates.service.RewardSelector;
import com.antondev.crates.service.RewardStateService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
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
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitRunnable;

public final class MenuService implements Listener {
    private final PlexonCrates plugin;
    private final ItemSnapshotCodec itemSnapshots = new ItemSnapshotCodec();
    private final NamespacedKey editorItem;
    private final Map<UUID, String> rewardSearch = new ConcurrentHashMap<>();
    private final Map<UUID, MassContext> massContexts = new ConcurrentHashMap<>();

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
                    Text.parse("<gray>Accepted keys available</gray> <dark_gray>»</dark_gray> <white>" + physicalKeyCount(player, crate) + "</white>"),
                    Text.parse("<gray>Your openings</gray> <dark_gray>»</dark_gray> <white>" + plugin.statistics().player(player.getUniqueId(), crate.id()) + "</white>")));
            inventory.setItem(slots.get(index), icon);
        }
        inventory.setItem(menus.slot("browser.info"), menus.item("browser.info"));
        inventory.setItem(menus.slot("browser.close"), menus.item("browser.close"));
        if (plugin.settings().claimInboxEnabled() && menus.contains("browser.claims")) {
            ItemStack claims = menus.item("browser.claims", Text.value("count", plugin.claims().pendingCount(player.getUniqueId()).getNow(0)));
            inventory.setItem(menus.slot("browser.claims"), claims);
        }
        open(player, inventory);
    }

    /** Opens the durable exact-item Claim Inbox without blocking the primary thread. */
    public void openClaims(Player player, int requestedPage) {
        if (!plugin.settings().claimInboxEnabled()) {
            plugin.messages().send(player, "disabled");
            return;
        }
        int page = Math.max(1, requestedPage);
        plugin.claims().list(player.getUniqueId(), page).whenComplete((entries, error) -> {
            if (!plugin.isEnabled()) return;
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!player.isOnline()) return;
                if (!plugin.settings().claimInboxEnabled()) {
                    plugin.messages().send(player, "disabled");
                    return;
                }
                if (error != null || entries == null) {
                    plugin.messages().send(player, "database-error");
                    return;
                }
                MenuConfig menus = plugin.menusConfig();
                MenuHolder holder = new MenuHolder(MenuHolder.Kind.CLAIMS, "", "", page - 1, false);
                Inventory inventory = create(holder, menus.size("claims"),
                        menus.title("claims", Text.value("page", page)));
                fill(inventory);
                List<Integer> slots = menus.slots("claims.claim-slots");
                for (int index = 0; index < Math.min(slots.size(), entries.size()); index++) {
                    DatabaseService.ClaimEntry entry = entries.get(index);
                    inventory.setItem(slots.get(index), claimDisplay(entry));
                    holder.bind(slots.get(index), "claim", entry.claimId().toString());
                }
                inventory.setItem(menus.slot("claims.previous"), menus.item("claims.previous"));
                inventory.setItem(menus.slot("claims.back"), menus.item("claims.back"));
                inventory.setItem(menus.slot("claims.guide"),
                        menus.item("claims.guide", Text.value("count", entries.size())));
                inventory.setItem(menus.slot("claims.next"), menus.item("claims.next"));
                inventory.setItem(menus.slot("claims.close"), menus.item("claims.close"));
                open(player, inventory);
            });
        });
    }

    public void openPreview(Player player, Crate crate, int requestedPage, boolean adminOrigin) {
        renderPreview(player, crate, requestedPage, adminOrigin, null);
    }

    /** Opens a non-consuming amount chooser for one published mass-opening request. */
    public void openMassOpening(Player player, Crate crate, OpenSource source,
                                BlockPosition location, int returnPage) {
        if (!plugin.settings().massOpeningEnabled() || !crate.bulkEnabled()) {
            plugin.messages().send(player, "disabled");
            return;
        }
        long revision = plugin.runtime().crateRevision(crate.id());
        plugin.openings().maximumAvailableAmount(player, crate).whenComplete((maximum, error) -> {
            if (!plugin.isEnabled()) return;
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!player.isOnline()) return;
                Crate current = plugin.runtime().find(crate.id()).orElse(null);
                if (error != null) {
                    plugin.messages().send(player, "database-error");
                    return;
                }
                if (current == null || revision != plugin.runtime().crateRevision(crate.id())) {
                    plugin.messages().send(player, "opening-state-changed");
                    return;
                }
                renderMassOpening(player, current, source, location, returnPage,
                        Math.max(0, maximum == null ? 0 : maximum));
            });
        });
    }

    private void renderMassOpening(Player player, Crate crate, OpenSource source,
                                   BlockPosition location, int returnPage, int maximum) {
        MenuConfig menus = plugin.menusConfig();
        MenuHolder holder = new MenuHolder(MenuHolder.Kind.MASS_OPEN, crate.id(), "",
                Math.max(0, returnPage), false, plugin.runtime().crateRevision(crate.id()));
        Inventory inventory = create(holder, menus.size("mass-open"),
                menus.title("mass-open", Text.component("crate", crate.displayName())));
        fill(inventory);
        inventory.setItem(menus.slot("mass-open.guide"), menus.item("mass-open.guide",
                Text.value("maximum", maximum), Text.value("key_cost", crate.keyCost())));
        massChoice(inventory, holder, menus, "one", 1, maximum, crate.keyCost());
        massChoice(inventory, holder, menus, "five", 5, maximum, crate.keyCost());
        massChoice(inventory, holder, menus, "ten", 10, maximum, crate.keyCost());
        if (maximum > 0) {
            int customSlot = menus.slot("mass-open.custom");
            inventory.setItem(customSlot, menus.item("mass-open.custom", Text.value("maximum", maximum)));
            holder.bind(customSlot, "custom-mass", Integer.toString(maximum));
        }
        int maximumSlot = menus.slot("mass-open.maximum");
        ItemStack maximumItem = menus.item("mass-open.maximum", Text.value("amount", maximum),
                Text.value("cost", (long) maximum * crate.keyCost()));
        if (maximum < 1) {
            maximumItem.setType(org.bukkit.Material.BARRIER);
            appendLore(maximumItem, List.of(Text.parse("<red>No complete opening is currently payable.</red>")));
        } else {
            holder.bind(maximumSlot, "open-mass", Integer.toString(maximum));
        }
        inventory.setItem(maximumSlot, maximumItem);
        inventory.setItem(menus.slot("mass-open.back"), menus.item("mass-open.back"));
        massContexts.put(holder.sessionId(), new MassContext(player.getUniqueId(), source, location,
                Math.max(0, returnPage), maximum));
        open(player, inventory);
    }

    private static void massChoice(Inventory inventory, MenuHolder holder, MenuConfig menus,
                                   String key, int amount, int maximum, int keyCost) {
        if (amount > maximum) return;
        int slot = menus.slot("mass-open." + key);
        inventory.setItem(slot, menus.item("mass-open." + key,
                Text.value("cost", (long) amount * keyCost)));
        holder.bind(slot, "open-mass", Integer.toString(amount));
    }

    /** Opens a non-consuming confirmation preview for one verified portable issuance. */
    public void openPortablePreview(Player player, Crate crate, DatabaseService.PortableIssue issue) {
        if (issue == null || !issue.crateId().equals(crate.id())) {
            plugin.messages().send(player, "invalid-crate");
            return;
        }
        if (issue.issuedTo() != null && !issue.issuedTo().equals(player.getUniqueId())) {
            plugin.messages().send(player, "no-permission");
            return;
        }
        if (!issue.state().equals("UNUSED")) {
            player.sendActionBar(Text.parse(
                    "<yellow>This portable crate has already been used or needs review.</yellow>"));
            return;
        }
        long activeRevision = plugin.runtime().crateRevision(crate.id());
        if (issue.revisionPolicy().equals("PINNED_REVISION")
                && issue.pinnedRevision() != activeRevision) {
            plugin.messages().send(player, "opening-state-changed");
            return;
        }
        renderPreview(player, crate, 0, false, issue.issueId());
    }

    private void renderPreview(Player player, Crate crate, int requestedPage,
                               boolean adminOrigin, UUID portableIssueId) {
        MenuConfig menus = plugin.menusConfig();
        List<Integer> rewardSlots = menus.slots("preview.reward-slots");
        List<CrateReward> rewards = crate.orderedRewards().stream().filter(CrateReward::enabled).toList();
        int pages = Math.max(1, (rewards.size() + rewardSlots.size() - 1) / rewardSlots.size());
        int page = Math.max(0, Math.min(requestedPage, pages - 1));
        boolean portable = portableIssueId != null;
        boolean selective = !adminOrigin && crate.openingMode() == OpeningMode.SELECTIVE;
        boolean selectable = selective && plugin.settings().selectiveOpeningEnabled();
        MenuHolder holder = new MenuHolder(
                portable ? MenuHolder.Kind.PORTABLE_PREVIEW : MenuHolder.Kind.PREVIEW,
                crate.id(), portable ? portableIssueId.toString() : "", page, adminOrigin,
                adminOrigin ? 0 : plugin.runtime().crateRevision(crate.id()));
        if (adminOrigin) holder.bindDraft(plugin.adminMenus().ensureDraft(player, crate.id()));
        Inventory inventory = create(holder, menus.size("preview"),
                menus.title("preview", Text.component("crate", crate.displayName())));
        fill(inventory);
        long now = System.currentTimeMillis();
        boolean bypassLimits = player.hasPermission("plexoncrates.bypass.limit");
        var outcomeBySource = new LinkedHashMap<String, RewardStateService.Outcome>();
        for (CrateReward reward : rewards) {
            plugin.openings().previewOutcome(player, crate, reward, now, bypassLimits)
                    .ifPresent(outcome -> outcomeBySource.put(reward.id(), outcome));
        }
        List<CrateReward> eligible = rewards.stream()
                .filter(reward -> outcomeBySource.containsKey(reward.id())).toList();
        int start = page * rewardSlots.size();
        for (int slotIndex = 0; slotIndex < rewardSlots.size() && start + slotIndex < rewards.size(); slotIndex++) {
            CrateReward reward = rewards.get(start + slotIndex);
            RewardStateService.Outcome outcome = outcomeBySource.get(reward.id());
            ItemStack display = outcome == null ? reward.displayCopy() : outcome.actual().displayCopy();
            boolean canWin = outcome != null;
            double chance = canWin ? RewardSelector.chance(reward, eligible) : 0;
            var lore = new ArrayList<Component>();
            for (String line : menus.strings("preview.reward-lore")) {
                lore.add(Text.parse(line,
                        Text.value("eligible_chance", format(chance)),
                        Text.value("base_chance", format(reward.baseChancePercent())),
                        Text.value("chance", format(chance)),
                        Text.value("weight", format(reward.baseChancePercent()))));
            }
            if (reward.chanceBasisPoints() <= 0) {
                lore.add(Text.parse("<red>Not in pool (0.00%).</red>"));
            } else if (!canWin) {
                lore.add(Text.parse("<red>This source and its allowed alternative are unavailable.</red>"));
            }
            if (plugin.settings().alternativeRewardsEnabled() && reward.hasAlternative()) {
                lore.add(Text.parse("<gold>Alternative:</gold> <white>" + reward.alternativeRewardId()
                        + "</white> <gray>for " + reward.alternativeReasons().stream().map(Enum::name).sorted()
                        .collect(java.util.stream.Collectors.joining(", ")) + "</gray>"));
            }
            if (outcome != null && outcome.fallback()) {
                lore.add(Text.parse("<yellow>Current outcome:</yellow> ").append(outcome.actual().displayName())
                        .append(Text.parse(" <dark_gray>(" + outcome.alternativeReason().name() + ")</dark_gray>")));
                lore.add(Text.parse("<gray>The source ticket's configured chance is retained.</gray>"));
            }
            if (selective) {
                lore.add(Text.parse("<gray>Base chance is retained but ignored in selective mode.</gray>"));
                if (!selectable) lore.add(Text.parse("<red>Selective opening is disabled by the server.</red>"));
                else if (canWin) {
                    lore.add(Text.parse("<green>Click to choose this exact reward.</green>"));
                    holder.bind(rewardSlots.get(slotIndex), "select-reward", reward.id());
                }
            }
            if (crate.pity().enabled() && (crate.pity().rewardIds().contains(reward.id())
                    || crate.pity().rarity() == reward.rarity())) {
                lore.add(Text.parse("<light_purple>Guaranteed-pool reward</light_purple>"));
            }
            appendLore(display, lore);
            inventory.setItem(rewardSlots.get(slotIndex), display);
        }
        ItemStack open = menus.item("preview.open", Text.component("crate", crate.displayName()),
                Text.value("keys", physicalKeyCount(player, crate)),
                Text.component("key", keyName(crate)),
                Text.value("key_cost", crate.keyCost()));
        if (portable) {
            open.editMeta(meta -> meta.lore(List.of(
                    Component.empty(),
                    Text.parse("<gray>Cost</gray> <dark_gray>»</dark_gray> <white>1 portable crate item</white>"),
                    Text.parse("<gray>No physical key is required.</gray>"),
                    Component.empty(),
                    Text.parse("<green>Click to confirm and open one.</green>"))));
        } else {
            appendPhysicalPaymentSummary(open, player, crate);
            if (!adminOrigin && crate.openingMode() != OpeningMode.SELECTIVE
                    && plugin.settings().massOpeningEnabled() && crate.bulkEnabled()) {
                appendLore(open, List.of(Component.empty(),
                        Text.parse("<aqua>Shift-click to choose 1, 5, 10, Custom, or Maximum Available.</aqua>")));
            }
        }
        if (selective) {
            open.setType(org.bukkit.Material.COMPASS);
            open.editMeta(meta -> {
                meta.displayName(Text.parse("<yellow><bold>Choose a reward above</bold></yellow>"));
                meta.lore(List.of(Component.empty(),
                        selectable
                                ? Text.parse("<gray>Select an eligible reward, then confirm its exact cost.</gray>")
                                : Text.parse("<red>Selective opening is disabled by the server.</red>"),
                        Text.parse("<gray>Browsing and closing consume nothing.</gray>")));
            });
        }
        appendMilestonePreview(open, player, crate);
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
        if (!portable && !selective && plugin.settings().virtualKeyWalletEnabled()
                && crate.paymentPolicy() != com.antondev.crates.domain.key.KeyPaymentPolicy.PHYSICAL_ONLY) {
            appendVirtualPaymentSummary(player, crate, holder, inventory);
        }
    }

    private void appendMilestonePreview(ItemStack control, Player player, Crate crate) {
        if (!plugin.settings().milestonesEnabled()) return;
        List<CrateMilestone> visible = crate.orderedMilestones().stream()
                .filter(CrateMilestone::previewVisible).toList();
        if (visible.isEmpty()) return;
        var progress = plugin.milestoneProgress().progress(player.getUniqueId(), crate.id());
        List<CrateMilestone> next = plugin.milestoneProgress().next(player.getUniqueId(), crate,
                milestone -> milestone.previewVisible() && milestone.reward().eligible(player), 2);
        var lore = new ArrayList<Component>();
        lore.add(Component.empty());
        lore.add(Text.parse("<gold><bold>Milestone progress</bold></gold>"));
        lore.add(Text.parse("<gray>Successful openings:</gray> <white>" + progress.openings()
                + "</white> <dark_gray>•</dark_gray> <gray>Earned:</gray> <white>"
                + progress.earnedKeys().size() + "</white>"));
        if (next.isEmpty()) {
            lore.add(Text.parse("<green>Every visible milestone is currently earned.</green>"));
        } else {
            for (CrateMilestone milestone : next) {
                long required = MilestoneProgressService.requiredOpenings(progress, milestone.definition());
                lore.add(Text.parse("<gray>Next:</gray> ").append(milestone.displayName())
                        .append(Text.parse(" <dark_gray>•</dark_gray> <white>" + progress.openings()
                                + " / " + required + "</white>")));
            }
        }
        appendLore(control, lore);
    }

    private void openSelectiveConfirmation(Player player, Crate crate, CrateReward reward,
                                           int returnPage, UUID portableIssueId) {
        if (!plugin.settings().selectiveOpeningEnabled() || crate.openingMode() != OpeningMode.SELECTIVE) {
            plugin.messages().send(player, "disabled");
            return;
        }
        boolean bypassLimits = player.hasPermission("plexoncrates.bypass.limit");
        long now = System.currentTimeMillis();
        Optional<RewardStateService.Outcome> resolved = plugin.openings()
                .previewOutcome(player, crate, reward, now, bypassLimits);
        if (resolved.isEmpty()) {
            plugin.messages().send(player, "no-eligible-rewards");
            return;
        }
        RewardStateService.Outcome outcome = resolved.get();
        CrateReward actual = outcome.actual();
        MenuConfig menus = plugin.menusConfig();
        MenuHolder holder = new MenuHolder(MenuHolder.Kind.SELECTIVE_CONFIRM, crate.id(), reward.id(),
                returnPage, false, plugin.runtime().crateRevision(crate.id()));
        Inventory inventory = create(holder, menus.size("selective-confirm"),
                menus.title("selective-confirm", Text.component("crate", crate.displayName())));
        fill(inventory);
        inventory.setItem(menus.slot("selective-confirm.guide"), menus.item("selective-confirm.guide"));

        ItemStack display = actual.displayCopy();
        var delivery = new ArrayList<Component>();
        delivery.add(Component.empty());
        delivery.add(Text.parse("<gray>Source reward</gray> <dark_gray>»</dark_gray> <white>" + reward.id() + "</white>"));
        delivery.add(Text.parse("<gray>Actual reward</gray> <dark_gray>»</dark_gray> <white>" + actual.id() + "</white>"));
        if (outcome.fallback()) delivery.add(Text.parse("<yellow>Alternative applies:</yellow> <white>"
                + outcome.alternativeReason().name() + "</white>"));
        delivery.add(Text.parse("<gray>Amount</gray> <dark_gray>»</dark_gray> <white>1 opening</white>"));
        int itemCount = actual.itemCopies().stream().mapToInt(ItemStack::getAmount).sum();
        delivery.add(Text.parse("<gray>Items</gray> <dark_gray>»</dark_gray> <white>" + itemCount
                + " across " + actual.itemCopies().size() + " exact stack(s)</white>"));
        if (!actual.commands().isEmpty()) delivery.add(Text.parse("<gray>Commands</gray> <dark_gray>»</dark_gray> <white>"
                + actual.commands().size() + " configured action(s)</white>"));
        if (actual.experiencePoints() > 0) delivery.add(Text.parse("<gray>Experience points</gray> <dark_gray>»</dark_gray> <white>"
                + actual.experiencePoints() + "</white>"));
        if (actual.experienceLevels() > 0) delivery.add(Text.parse("<gray>Experience levels</gray> <dark_gray>»</dark_gray> <white>"
                + actual.experienceLevels() + "</white>"));
        if (actual.money() > 0) delivery.add(Text.parse("<gray>Money</gray> <dark_gray>»</dark_gray> <white>"
                + format(actual.money()) + "</white>"));
        String restriction = actual.requiredPermission().isBlank() ? "none" : actual.requiredPermission();
        delivery.add(Text.parse("<gray>Required permission</gray> <dark_gray>»</dark_gray> <white>" + restriction + "</white>"));
        delivery.add(Text.parse("<green>Eligible now; eligibility is checked again on confirm.</green>"));
        appendLore(display, delivery);
        inventory.setItem(menus.slot("selective-confirm.reward"), display);

        int cost = portableIssueId == null ? crate.keyCost() : 1;
        ItemStack confirm = menus.item("selective-confirm.confirm", Text.value("amount", 1), Text.value("cost", cost));
        if (portableIssueId == null) appendPhysicalPaymentSummary(confirm, player, crate);
        else appendLore(confirm, List.of(Component.empty(),
                Text.parse("<gray>Payment source</gray> <dark_gray>»</dark_gray> <white>1 portable crate item</white>"),
                Text.parse("<gray>No physical key is required.</gray>")));
        int confirmSlot = menus.slot("selective-confirm.confirm");
        inventory.setItem(confirmSlot, confirm);
        holder.bind(confirmSlot, "confirm-selective", portableIssueId == null ? "" : portableIssueId.toString());
        inventory.setItem(menus.slot("selective-confirm.cancel"), menus.item("selective-confirm.cancel"));
        open(player, inventory);
        if (portableIssueId == null && plugin.settings().virtualKeyWalletEnabled()
                && crate.paymentPolicy() != KeyPaymentPolicy.PHYSICAL_ONLY) {
            appendVirtualPaymentSummary(player, crate, holder, inventory, confirmSlot);
        }
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

    /** Opens or refreshes the non-movable post-consumption reroll decision. */
    public void openReroll(Player player) {
        Optional<com.antondev.crates.service.OpeningService.RerollView> optional =
                plugin.openings().rerollView(player);
        if (optional.isEmpty()) return;
        var view = optional.get();
        Inventory current = player.getOpenInventory().getTopInventory();
        if (current != null && current.getHolder() instanceof MenuHolder holder
                && holder.kind() == MenuHolder.Kind.REROLL
                && holder.rewardId().equals(view.transactionId().toString())) {
            renderReroll(current, holder, view);
            return;
        }
        MenuConfig menus = plugin.menusConfig();
        MenuHolder holder = new MenuHolder(MenuHolder.Kind.REROLL, view.crate().id(),
                view.transactionId().toString(), 0, false,
                plugin.runtime().crateRevision(view.crate().id()));
        Inventory inventory = create(holder, menus.size("reroll"),
                menus.title("reroll", Text.component("crate", view.crate().displayName())));
        renderReroll(inventory, holder, view);
        open(player, inventory);
    }

    public void refreshReroll(Player player) {
        Inventory current = player.getOpenInventory().getTopInventory();
        if (current == null || !(current.getHolder() instanceof MenuHolder holder)
                || holder.kind() != MenuHolder.Kind.REROLL) return;
        plugin.openings().rerollView(player).ifPresent(view -> renderReroll(current, holder, view));
    }

    private void renderReroll(Inventory inventory, MenuHolder holder,
                              com.antondev.crates.service.OpeningService.RerollView view) {
        MenuConfig menus = plugin.menusConfig();
        fill(inventory);
        inventory.setItem(menus.slot("reroll.guide"), menus.item("reroll.guide"));
        inventory.setItem(menus.slot("reroll.accept"), menus.item("reroll.accept"));
        inventory.setItem(menus.slot("reroll.candidate"), view.candidate().displayCopy());
        inventory.setItem(menus.slot("reroll.reroll"), menus.item("reroll.reroll",
                Text.value("remaining", view.remaining()), Text.value("cost", view.cost()),
                Text.component("state", Text.parse(view.state()))));
        inventory.setItem(menus.slot("reroll.countdown"), menus.item("reroll.countdown",
                Text.value("seconds", view.secondsRemaining())));
        holder.bind(menus.slot("reroll.accept"), "accept-reroll", view.transactionId().toString());
        holder.bind(menus.slot("reroll.reroll"), view.canReroll() ? "request-reroll" : "noop",
                view.transactionId().toString());
    }

    public void closeAll() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getOpenInventory().getTopInventory() != null
                    && player.getOpenInventory().getTopInventory().getHolder() instanceof MenuHolder holder
                    && holder.kind() != MenuHolder.Kind.OPENING) player.closeInventory();
        }
        massContexts.clear();
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
            case CLAIMS -> claimsClick(player, holder, slot);
            case PREVIEW -> {
                Crate crate = (holder.adminOrigin() ? plugin.crates().find(holder.crateId())
                        : plugin.runtime().find(holder.crateId())).orElse(null);
                if (crate == null) return;
                if (!holder.adminOrigin() && holder.revision() != plugin.runtime().crateRevision(crate.id())) {
                    plugin.messages().send(player, "opening-state-changed");
                    openPreview(player, crate, 0, false);
                    return;
                }
                MenuHolder.Action choice = holder.action(slot);
                if (choice != null && choice.id().equals("select-reward")) {
                    CrateReward reward = crate.rewards().get(choice.value());
                    if (reward != null) openSelectiveConfirmation(player, crate, reward, holder.page(), null);
                    return;
                }
                if (slot == menus.slot("preview.open")) {
                    if (crate.openingMode() == OpeningMode.SELECTIVE) return;
                    if (!holder.adminOrigin() && event.isShiftClick()
                            && plugin.settings().massOpeningEnabled() && crate.bulkEnabled()) {
                        openMassOpening(player, crate, OpenSource.GUI, null, holder.page());
                        return;
                    }
                    KeyPaymentPlanner.Preference preference = event.isRightClick()
                            ? KeyPaymentPlanner.Preference.VIRTUAL : KeyPaymentPlanner.Preference.PHYSICAL;
                    plugin.openings().open(player, crate, 1,
                            OpenSource.GUI, null, preference);
                }
                else if (slot == menus.slot("preview.back")) {
                    if (holder.adminOrigin()) openEditor(player, crate); else openBrowser(player);
                } else if (slot == menus.slot("preview.previous")) openPreview(player, crate, holder.page() - 1, holder.adminOrigin());
                else if (slot == menus.slot("preview.next")) openPreview(player, crate, holder.page() + 1, holder.adminOrigin());
            }
            case MASS_OPEN -> massOpenClick(player, holder, slot, event.isRightClick());
            case PORTABLE_PREVIEW -> portablePreviewClick(player, holder, slot);
            case SELECTIVE_CONFIRM -> selectiveConfirmClick(player, holder, slot, event.isRightClick());
            case ADMIN -> adminClick(player, slot);
            case EDITOR -> editorClick(player, holder.crateId(), slot);
            case CONFIRM_DELETE -> confirmClick(player, holder, slot);
            case OPENING -> { }
            case REROLL -> {
                MenuHolder.Action action = holder.action(slot);
                if (action == null) return;
                if (action.id().equals("accept-reroll")) plugin.openings().acceptReroll(player, "ACCEPT");
                else if (action.id().equals("request-reroll")) plugin.openings().requestReroll(player);
            }
            case SUMMARY -> {
                if (slot == menus.slot("summary.close")) player.closeInventory();
            }
            default -> { }
        }
    }

    private void massOpenClick(Player player, MenuHolder holder, int slot, boolean rightClick) {
        MassContext context = massContexts.get(holder.sessionId());
        Crate crate = plugin.runtime().find(holder.crateId()).orElse(null);
        if (context == null || !context.playerId().equals(player.getUniqueId()) || crate == null
                || holder.revision() != plugin.runtime().crateRevision(holder.crateId())
                || !plugin.settings().massOpeningEnabled() || !crate.bulkEnabled()) {
            massContexts.remove(holder.sessionId());
            player.closeInventory();
            plugin.messages().send(player, "opening-state-changed");
            return;
        }
        MenuConfig menus = plugin.menusConfig();
        if (slot == menus.slot("mass-open.back")) {
            massContexts.remove(holder.sessionId());
            openPreview(player, crate, context.returnPage(), false);
            return;
        }
        MenuHolder.Action action = holder.action(slot);
        if (action == null) return;
        KeyPaymentPlanner.Preference preference = rightClick
                ? KeyPaymentPlanner.Preference.VIRTUAL : KeyPaymentPlanner.Preference.PHYSICAL;
        if (action.id().equals("custom-mass")) {
            massContexts.remove(holder.sessionId());
            plugin.editSessions().request(player, Text.parse(
                    "<aqua>Enter a whole opening amount from <white>1</white> to <white>"
                            + context.maximum() + "</white>:</aqua>"), (target, value) -> {
                int amount = Integer.parseInt(value.trim());
                if (amount < 1 || amount > context.maximum()) {
                    throw new IllegalArgumentException("Amount must be between 1 and " + context.maximum());
                }
                Crate current = plugin.runtime().find(holder.crateId()).orElseThrow(
                        () -> new IllegalStateException("Crate is no longer published"));
                if (holder.revision() != plugin.runtime().crateRevision(holder.crateId())
                        || !plugin.settings().massOpeningEnabled() || !current.bulkEnabled()) {
                    throw new IllegalStateException("The crate changed; reopen its preview");
                }
                plugin.openings().open(target, current, amount, context.source(), context.location(), preference);
            });
            return;
        }
        if (!action.id().equals("open-mass")) return;
        int amount;
        try {
            amount = Integer.parseInt(action.value());
        } catch (NumberFormatException invalid) {
            plugin.messages().send(player, "opening-state-changed");
            return;
        }
        if (amount < 1 || amount > context.maximum()) return;
        massContexts.remove(holder.sessionId());
        player.closeInventory();
        plugin.openings().open(player, crate, amount, context.source(), context.location(), preference);
    }

    private void portablePreviewClick(Player player, MenuHolder holder, int slot) {
        MenuConfig menus = plugin.menusConfig();
        Crate crate = plugin.runtime().find(holder.crateId()).orElse(null);
        UUID issueId;
        try {
            issueId = UUID.fromString(holder.rewardId());
        } catch (IllegalArgumentException invalid) {
            player.closeInventory();
            plugin.messages().send(player, "invalid-crate");
            return;
        }
        if (crate == null || holder.revision() != plugin.runtime().crateRevision(holder.crateId())) {
            player.closeInventory();
            plugin.messages().send(player, "opening-state-changed");
            return;
        }
        MenuHolder.Action choice = holder.action(slot);
        if (choice != null && choice.id().equals("select-reward")) {
            CrateReward reward = crate.rewards().get(choice.value());
            if (reward != null) openSelectiveConfirmation(player, crate, reward, holder.page(), issueId);
            return;
        }
        if (slot == menus.slot("preview.back")) {
            player.closeInventory();
            return;
        }
        if (slot == menus.slot("preview.previous")) {
            renderPreview(player, crate, holder.page() - 1, false, issueId);
            return;
        }
        if (slot == menus.slot("preview.next")) {
            renderPreview(player, crate, holder.page() + 1, false, issueId);
            return;
        }
        if (slot != menus.slot("preview.open")) return;
        if (crate.openingMode() == OpeningMode.SELECTIVE) return;

        ItemStack expected = player.getInventory().getItemInMainHand().clone();
        player.closeInventory();
        plugin.portables().verify(expected).whenComplete((verified, error) -> {
            if (!plugin.isEnabled()) return;
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!player.isOnline()) return;
                if (error != null) {
                    plugin.getLogger().log(java.util.logging.Level.WARNING,
                            "Portable issuance verification failed for " + issueId, error);
                    plugin.messages().send(player, "opening-state-changed");
                    return;
                }
                if (verified == null || verified.isEmpty()
                        || !verified.get().issueId().equals(issueId)) {
                    plugin.messages().send(player, "invalid-crate");
                    return;
                }
                DatabaseService.PortableIssue issue = verified.get();
                if (issue.issuedTo() != null && !issue.issuedTo().equals(player.getUniqueId())) {
                    plugin.messages().send(player, "no-permission");
                    return;
                }
                if (!issue.state().equals("UNUSED")) {
                    player.sendActionBar(Text.parse(
                            "<yellow>This portable crate has already been used or needs review.</yellow>"));
                    return;
                }
                Crate active = plugin.runtime().find(issue.crateId()).orElse(null);
                long activeRevision = plugin.runtime().crateRevision(issue.crateId());
                if (active == null || activeRevision != holder.revision()
                        || issue.revisionPolicy().equals("PINNED_REVISION")
                        && issue.pinnedRevision() != activeRevision) {
                    plugin.messages().send(player, "opening-state-changed");
                    return;
                }
                plugin.openings().openPortable(player, active, issue, expected);
            });
        });
    }

    private void selectiveConfirmClick(Player player, MenuHolder holder, int slot, boolean rightClick) {
        MenuConfig menus = plugin.menusConfig();
        int confirmSlot = menus.slot("selective-confirm.confirm");
        MenuHolder.Action confirmation = holder.action(confirmSlot);
        UUID portableIssueId = null;
        if (confirmation != null && !confirmation.value().isBlank()) {
            try {
                portableIssueId = UUID.fromString(confirmation.value());
            } catch (IllegalArgumentException invalid) {
                player.closeInventory();
                plugin.messages().send(player, "opening-state-changed");
                return;
            }
        }
        Crate crate = plugin.runtime().find(holder.crateId()).orElse(null);
        if (crate == null || crate.openingMode() != OpeningMode.SELECTIVE
                || !plugin.settings().selectiveOpeningEnabled()
                || holder.revision() != plugin.runtime().crateRevision(holder.crateId())) {
            player.closeInventory();
            plugin.messages().send(player, "opening-state-changed");
            return;
        }
        if (slot == menus.slot("selective-confirm.cancel")) {
            renderPreview(player, crate, holder.page(), false, portableIssueId);
            return;
        }
        if (slot != confirmSlot || confirmation == null) return;
        CrateReward reward = crate.rewards().get(holder.rewardId());
        boolean bypassLimits = player.hasPermission("plexoncrates.bypass.limit");
        if (reward == null || !previewEligible(player, crate, reward, System.currentTimeMillis(), bypassLimits)) {
            plugin.messages().send(player, "no-eligible-rewards");
            renderPreview(player, crate, holder.page(), false, portableIssueId);
            return;
        }
        KeyPaymentPlanner.Preference preference = rightClick
                ? KeyPaymentPlanner.Preference.VIRTUAL : KeyPaymentPlanner.Preference.PHYSICAL;
        player.closeInventory();
        if (portableIssueId == null) {
            plugin.openings().openSelected(player, crate, reward.id(), 1, OpenSource.GUI, null, preference);
        } else {
            confirmPortableSelection(player, crate, reward.id(), portableIssueId, holder.revision());
        }
    }

    private void confirmPortableSelection(Player player, Crate crate, String rewardId,
                                          UUID issueId, long expectedRevision) {
        ItemStack expected = player.getInventory().getItemInMainHand().clone();
        plugin.portables().verify(expected).whenComplete((verified, error) -> {
            if (!plugin.isEnabled()) return;
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!player.isOnline()) return;
                if (error != null || verified == null || verified.isEmpty()
                        || !verified.get().issueId().equals(issueId)) {
                    plugin.messages().send(player, error == null ? "invalid-crate" : "opening-state-changed");
                    return;
                }
                DatabaseService.PortableIssue issue = verified.get();
                Crate active = plugin.runtime().find(issue.crateId()).orElse(null);
                long activeRevision = plugin.runtime().crateRevision(issue.crateId());
                if (issue.issuedTo() != null && !issue.issuedTo().equals(player.getUniqueId())) {
                    plugin.messages().send(player, "no-permission");
                } else if (!issue.state().equals("UNUSED") || active == null
                        || active.openingMode() != OpeningMode.SELECTIVE
                        || activeRevision != expectedRevision
                        || issue.revisionPolicy().equals("PINNED_REVISION")
                        && issue.pinnedRevision() != activeRevision) {
                    plugin.messages().send(player, "opening-state-changed");
                } else {
                    plugin.openings().openPortableSelected(player, active, issue, expected, rewardId);
                }
            });
        });
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
        Inventory closed = event.getInventory();
        if (closed == null && event.getView() != null) closed = event.getView().getTopInventory();
        if (closed != null && closed.getHolder() instanceof MenuHolder holder) {
            plugin.guiSessions().close(event.getPlayer().getUniqueId(), holder.sessionId());
            massContexts.remove(holder.sessionId());
            if (holder.kind() == MenuHolder.Kind.REROLL && event.getPlayer() instanceof Player player) {
                plugin.openings().acceptReroll(player, "CLOSE");
            }
        }
    }

    @EventHandler
    public void quit(PlayerQuitEvent event) {
        plugin.openings().acceptReroll(event.getPlayer(), "DISCONNECT");
        rewardSearch.remove(event.getPlayer().getUniqueId());
        massContexts.values().removeIf(context -> context.playerId().equals(event.getPlayer().getUniqueId()));
        plugin.guiSessions().clear(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void death(PlayerDeathEvent event) {
        plugin.openings().acceptReroll(event.getEntity(), "DEATH");
    }

    @EventHandler(ignoreCancelled = true)
    public void teleport(PlayerTeleportEvent event) {
        plugin.openings().acceptReroll(event.getPlayer(), "TELEPORT");
    }

    private void claimsClick(Player player, MenuHolder holder, int slot) {
        if (!plugin.settings().claimInboxEnabled()) {
            player.closeInventory();
            plugin.messages().send(player, "disabled");
            return;
        }
        MenuConfig menus = plugin.menusConfig();
        if (slot == menus.slot("claims.close")) {
            player.closeInventory();
            return;
        }
        if (slot == menus.slot("claims.back")) {
            openBrowser(player);
            return;
        }
        if (slot == menus.slot("claims.previous")) {
            if (holder.page() > 0) openClaims(player, holder.page());
            return;
        }
        if (slot == menus.slot("claims.next")) {
            openClaims(player, holder.page() + 2);
            return;
        }
        List<Integer> slots = menus.slots("claims.claim-slots");
        int index = slots.indexOf(slot);
        if (index < 0) return;
        MenuHolder.Action action = holder.action(slot);
        if (action == null || !"claim".equals(action.id())) return;
        try {
            plugin.claims().claim(player, UUID.fromString(action.value()));
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (player.isOnline()) openClaims(player, holder.page() + 1);
            }, 2L);
        } catch (IllegalArgumentException error) {
            plugin.messages().send(player, "database-error");
        }
    }

    private ItemStack claimDisplay(DatabaseService.ClaimEntry entry) {
        if (entry.itemBytes() == null) {
            ItemStack display = new ItemStack(org.bukkit.Material.TRIPWIRE_HOOK);
            display.editMeta(meta -> meta.displayName(Text.parse("<aqua>Virtual key ×" + entry.virtualKeyAmount() + "</aqua>")));
            return display;
        }
        try {
            ItemSnapshotCodec.Snapshot snapshot = new ItemSnapshotCodec.Snapshot(
                    entry.itemBytes(), "unknown", entry.itemAmount(), entry.itemBytes().length,
                    entry.itemSha256().toLowerCase(Locale.ROOT), false, false, entry.createdAt());
            ItemStack display = itemSnapshots.restoreTemplate(snapshot);
            display.setAmount(Math.min(entry.itemAmount(), Math.max(1, display.getMaxStackSize())));
            return display;
        } catch (RuntimeException error) {
            ItemStack display = new ItemStack(org.bukkit.Material.BARRIER);
            display.editMeta(meta -> meta.displayName(Text.parse("<red>Exact item needs review</red>")));
            return display;
        }
    }

    private void browserClick(Player player, MenuHolder holder, int slot, boolean rightClick) {
        MenuConfig menus = plugin.menusConfig();
        if (slot == menus.slot("browser.close")) {
            player.closeInventory();
            return;
        }
        if (plugin.settings().claimInboxEnabled() && menus.contains("browser.claims")
                && slot == menus.slot("browser.claims")) {
            openClaims(player, 1);
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
        if (rightClick && crate.openingMode() != OpeningMode.SELECTIVE
                && crate.paymentPolicy() != KeyPaymentPolicy.PLAYER_CHOICE) {
            plugin.openings().open(player, crate, 1, false);
        }
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

    private long physicalKeyCount(Player player, Crate crate) {
        long count = 0;
        for (String keyId : crate.acceptedKeyIds()) {
            count = Math.min(Integer.MAX_VALUE, count + plugin.keys().count(player, keyId));
        }
        return count;
    }

    private void appendPhysicalPaymentSummary(ItemStack item, Player player, Crate crate) {
        var lore = new ArrayList<Component>();
        lore.add(Component.empty());
        lore.add(Text.parse("<gray>Accepted physical sources</gray>"));
        for (String keyId : crate.acceptedKeyIds()) {
            lore.add(Text.parse("<dark_gray>•</dark_gray> <key> <dark_gray>»</dark_gray> <white><count> available</white>",
                    Text.component("key", keyName(keyId)),
                    Text.value("count", plugin.keys().count(player, keyId))));
        }
        lore.add(Text.parse("<gray>Cost per opening</gray> <dark_gray>»</dark_gray> <white>" + crate.keyCost() + "</white>"));
        lore.add(Text.parse("<gray>Payment policy</gray> <dark_gray>»</dark_gray> <white>"
                + crate.paymentPolicy().name().toLowerCase(Locale.ROOT).replace('_', ' ') + "</white>"));
        if (crate.paymentPolicy() == com.antondev.crates.domain.key.KeyPaymentPolicy.PLAYER_CHOICE) {
            lore.add(Text.parse("<yellow>Left-click physical • Right-click virtual</yellow>"));
        } else if (crate.paymentPolicy() != com.antondev.crates.domain.key.KeyPaymentPolicy.PHYSICAL_ONLY
                && !plugin.settings().virtualKeyWalletEnabled()) {
            lore.add(Text.parse("<red>The virtual-key wallet is disabled.</red>"));
        }
        appendLore(item, lore);
    }

    private void appendVirtualPaymentSummary(Player player, Crate crate, MenuHolder holder,
                                             Inventory inventory) {
        appendVirtualPaymentSummary(player, crate, holder, inventory,
                plugin.menusConfig().slot("preview.open"));
    }

    private void appendVirtualPaymentSummary(Player player, Crate crate, MenuHolder holder,
                                             Inventory inventory, int targetSlot) {
        var futures = crate.acceptedKeyIds().stream()
                .map(keyId -> plugin.database().loadVirtualKeyBalance(player.getUniqueId(), keyId))
                .toList();
        CompletableFuture<?>[] all = futures.toArray(CompletableFuture[]::new);
        CompletableFuture.allOf(all).whenComplete((ignored, error) -> {
            if (!plugin.isEnabled()) return;
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!player.isOnline() || player.getOpenInventory().getTopInventory().getHolder() != holder) return;
                ItemStack current = inventory.getItem(targetSlot);
                if (current == null) return;
                if (error != null) {
                    appendLore(current, List.of(Text.parse("<red>Virtual balances are temporarily unavailable.</red>")));
                } else {
                    var lore = new ArrayList<Component>();
                    lore.add(Component.empty());
                    lore.add(Text.parse("<gray>Virtual balances</gray>"));
                    for (int index = 0; index < crate.acceptedKeyIds().size(); index++) {
                        String keyId = crate.acceptedKeyIds().get(index);
                        DatabaseService.VirtualKeyBalance balance = futures.get(index).join();
                        lore.add(Text.parse("<dark_gray>•</dark_gray> <key> <dark_gray>»</dark_gray> <white><count> available</white>",
                                Text.component("key", keyName(keyId)), Text.value("count", balance.balance())));
                    }
                    if (crate.mixedPayment()) {
                        lore.add(Text.parse("<aqua>Mixed physical/virtual payment is enabled.</aqua>"));
                    }
                    appendLore(current, lore);
                }
                inventory.setItem(targetSlot, current);
            });
        });
    }

    private Component keyName(Crate crate) {
        Component names = Component.empty();
        for (int index = 0; index < crate.acceptedKeyIds().size(); index++) {
            if (index > 0) names = names.append(Text.parse("<dark_gray> / </dark_gray>"));
            names = names.append(keyName(crate.acceptedKeyIds().get(index)));
        }
        return crate.acceptedKeyIds().isEmpty() ? Text.parse("<white>crate key</white>") : names;
    }

    private Component keyName(String keyId) {
        return plugin.keys().template(keyId).map(item -> {
            Component name = item.getItemMeta().displayName();
            return name == null ? Text.parse("<white>" + keyId + " key</white>") : name;
        }).orElseGet(() -> Text.parse("<white>" + keyId + " key</white>"));
    }

    private static ItemStack randomDisplay(List<CrateReward> rewards) {
        return rewards.get(ThreadLocalRandom.current().nextInt(rewards.size())).displayCopy();
    }

    private static String format(double value) {
        String formatted = String.format(Locale.ROOT, "%.3f", value);
        return formatted.replaceAll("0+$", "").replaceAll("\\.$", "");
    }

    private boolean previewEligible(Player player, Crate crate, CrateReward reward, long now, boolean bypassLimits) {
        return plugin.openings().previewOutcome(player, crate, reward, now, bypassLimits).isPresent();
    }

    private static boolean isAdministrative(MenuHolder.Kind kind) {
        return switch (kind) {
            case ADMIN, EDITOR, CRATE_LIST, KEY_LIST, KEY_TEMPLATE, KEY_SELECT, REWARD_BUILDER,
                    MILESTONES, MILESTONE_DETAIL, MILESTONE_REWARD_SELECT, CONFIRM_MILESTONE_DELETE,
                    LOCATIONS, STATISTICS, SYSTEM, GLOBAL_REWARDS, WAND_SELECT,
                    CONFIRM_UNLINK, CONFIRM_CRATE_DELETE, CONFIRM_KEY_DELETE, CONFIRM_TAKEOVER -> true;
            default -> false;
        };
    }

    private record SummaryEntry(CrateReward reward, int count) {}
    private record MassContext(UUID playerId, OpenSource source, BlockPosition location,
                               int returnPage, int maximum) {}
}
