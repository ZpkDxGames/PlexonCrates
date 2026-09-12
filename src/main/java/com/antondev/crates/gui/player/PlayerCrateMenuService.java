package com.antondev.crates.gui.player;

import com.antondev.crates.PlexonCrates;
import com.antondev.crates.config.Text;
import com.antondev.crates.database.DatabaseService;
import com.antondev.crates.domain.key.KeyPaymentPolicy;
import com.antondev.crates.domain.opening.OpenSource;
import com.antondev.crates.domain.opening.OpeningMode;
import com.antondev.crates.gui.GuiSessionService;
import com.antondev.crates.gui.MenuHolder;
import com.antondev.crates.item.ItemSnapshotCodec;
import com.antondev.crates.model.Crate;
import com.antondev.crates.model.CrateReward;
import com.antondev.crates.service.KeyPaymentPlanner;
import com.antondev.crates.service.RewardSelector;
import com.antondev.crates.service.RewardStateService;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

/**
 * Player-facing projection over the accepted crate runtime. This class never
 * owns payment, reward selection, opening journals, grants, or claim delivery.
 */
public final class PlayerCrateMenuService implements Listener {
    private static final int CLAIM_PAGE_SIZE = 20;
    private static final List<Integer> QUANTITY_SLOTS = List.of(20, 22, 24, 31);

    private final PlexonCrates plugin;
    private final ItemSnapshotCodec snapshots = new ItemSnapshotCodec();
    private final Map<UUID, ViewState> views = new ConcurrentHashMap<>();
    private final Map<UUID, UUID> owners = new ConcurrentHashMap<>();
    private final Set<UUID> submitted = ConcurrentHashMap.newKeySet();

    public PlayerCrateMenuService(PlexonCrates plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    /** Replace old player Browser/Preview before they can receive a click. */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void redirectLegacyPlayerSurface(InventoryOpenEvent event) {
        if (!(event.getPlayer() instanceof Player player)
                || !(event.getInventory().getHolder() instanceof MenuHolder holder)
                || holder.adminOrigin()) return;
        if (holder.kind() == MenuHolder.Kind.BROWSER) {
            event.setCancelled(true);
            later(() -> { if (player.isOnline()) openHall(player, 0); });
        } else if (holder.kind() == MenuHolder.Kind.PREVIEW) {
            event.setCancelled(true);
            String crateId = holder.crateId();
            int page = holder.page();
            later(() -> {
                if (!player.isOnline()) return;
                plugin.runtime().find(crateId).ifPresentOrElse(
                        crate -> openPreview(player, crate, 0, page, KeyPaymentPlanner.Preference.PHYSICAL),
                        () -> openHall(player, 0));
            });
        }
    }

    /** /crates claim [page] is the Pending Rewards surface; UUID claims remain compatible. */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void pendingRewardsCommand(PlayerCommandPreprocessEvent event) {
        String[] parts = event.getMessage().trim().substring(1).split("\\s+");
        if (parts.length < 2 || !Set.of("crates", "crate", "plexoncrates").contains(parts[0].toLowerCase(Locale.ROOT))
                || !parts[1].equalsIgnoreCase("claim") || parts.length > 3) return;
        int page = 1;
        if (parts.length == 3) {
            try {
                page = Integer.parseInt(parts[2]);
                if (page < 1) return;
            } catch (NumberFormatException ignored) {
                return;
            }
        }
        Player player = event.getPlayer();
        if (!playerCan(player, "plexoncrates.claim") || !plugin.settings().claimInboxEnabled()) return;
        event.setCancelled(true);
        openPendingRewards(player, page, 0);
    }

    public void openHall(Player player, int requestedPage) {
        List<Crate> crates = plugin.runtime().ordered();
        int page = PlayerCrateLayout.clampPage(requestedPage, crates.size());
        long revision = plugin.runtime().snapshot().revision();
        MenuHolder holder = new MenuHolder(MenuHolder.Kind.PLAYER_HALL, "", "", page, false, revision);
        Inventory inventory = inventory(holder, Text.parse("<gradient:#CAD5E5:#FFFFFF><bold>Crate Hall</bold></gradient>"));
        PlayerMenuContext context = PlayerMenuContext.hall(page);
        remember(holder, new ViewState(context, revision, List.of(), false, -1));

        int start = page * PlayerCrateLayout.contentSlots().size();
        for (int i = 0; i < PlayerCrateLayout.contentSlots().size() && start + i < crates.size(); i++) {
            Crate crate = crates.get(start + i);
            int slot = PlayerCrateLayout.contentSlots().get(i);
            inventory.setItem(slot, crateCard(player, crate));
            holder.bind(slot, "preview", crate.id());
        }
        if (crates.isEmpty()) inventory.setItem(22, item(Material.BARRIER, "<yellow>No crates are available</yellow>",
                "<gray>Published crates will appear here automatically.</gray>"));
        if (page > 0) bind(holder, inventory, PlayerCrateLayout.PREVIOUS, "previous",
                item(Material.ARROW, "<white>Previous</white>"));
        bind(holder, inventory, PlayerCrateLayout.CONTEXT, "refresh",
                item(Material.COMPASS, "<aqua>Refresh</aqua>", "<gray>Load the latest published crate information.</gray>"));
        bind(holder, inventory, PlayerCrateLayout.PRIMARY, "pending",
                item(Material.CHEST, "<green>Pending Rewards</green>", "<gray>Loading your pending reward count…</gray>"));
        inventory.setItem(PlayerCrateLayout.STATUS, item(Material.PAPER,
                "<white>Page " + (page + 1) + " / " + PlayerCrateLayout.pageCount(crates.size()) + "</white>",
                "<gray>" + crates.size() + " crate" + (crates.size() == 1 ? "" : "s") + " available</gray>"));
        bind(holder, inventory, PlayerCrateLayout.CLOSE, "close", item(Material.BARRIER, "<red>Close</red>"));
        if (page + 1 < PlayerCrateLayout.pageCount(crates.size())) bind(holder, inventory, PlayerCrateLayout.NEXT, "next",
                item(Material.ARROW, "<white>Next</white>"));
        open(player, inventory);
        plugin.claims().pendingCount(player.getUniqueId()).whenComplete((count, error) -> main(() -> {
            if (!sameView(player, holder, revision) || error != null || count == null) return;
            inventory.setItem(PlayerCrateLayout.PRIMARY, item(Material.CHEST, "<green>Pending Rewards</green>",
                    count == 0 ? "<gray>No rewards are waiting for delivery.</gray>"
                            : "<white>" + count + " reward" + (count == 1 ? "" : "s") + " ready to claim.</white>"));
        }));
    }

    public void openPreview(Player player, Crate requested, int hallPage, int requestedPage,
                            KeyPaymentPlanner.Preference preference) {
        Crate crate = plugin.runtime().find(requested.id()).orElse(null);
        if (crate == null) { openHall(player, hallPage); return; }
        List<CrateReward> rewards = crate.orderedRewards().stream().filter(CrateReward::enabled).toList();
        int page = PlayerCrateLayout.clampPage(requestedPage, rewards.size());
        long revision = plugin.runtime().crateRevision(crate.id());
        MenuHolder holder = new MenuHolder(MenuHolder.Kind.PLAYER_PREVIEW, crate.id(), "", page, false, revision);
        Inventory inventory = inventory(holder, Text.parse("<white><bold>Preview</bold></white> <dark_gray>•</dark_gray> ").append(crate.displayName()));
        PlayerMenuContext context = PlayerMenuContext.preview(crate.id(), hallPage, page).withPaymentPreference(preference);
        ViewState state = new ViewState(context, revision, List.of(), false, -1);
        remember(holder, state);

        inventory.setItem(4, previewHeader(player, crate));
        renderRewards(player, holder, inventory, crate, rewards, page);
        previewNavigation(holder, inventory, context, rewards.size());
        inventory.setItem(PlayerCrateLayout.PAYMENT, item(Material.CLOCK, "<aqua>Payment</aqua>",
                "<gray>Checking your available payment…</gray>"));
        inventory.setItem(PlayerCrateLayout.PRIMARY, item(Material.GRAY_DYE,
                crate.openingMode() == OpeningMode.SELECTIVE ? "<yellow>Choose a reward above</yellow>" : "<gray>Open 1</gray>",
                "<gray>Payment status is loading.</gray>"));
        if (crate.openingMode() != OpeningMode.SELECTIVE && plugin.settings().massOpeningEnabled() && crate.bulkEnabled()) {
            inventory.setItem(PlayerCrateLayout.SECONDARY, item(Material.GRAY_DYE, "<gray>Open More</gray>",
                    "<gray>Payment status is loading.</gray>"));
        }
        open(player, inventory);
        loadPayment(player, crate).whenComplete((availability, error) -> main(() -> {
            if (!sameView(player, holder, revision)) return;
            if (error != null || availability == null) {
                inventory.setItem(PlayerCrateLayout.PAYMENT, item(Material.BARRIER, "<yellow>Payment status unavailable</yellow>",
                        "<gray>No key has been consumed. Refresh to try again.</gray>"));
                return;
            }
            ViewState updated = view(holder).withPayment(availability);
            remember(holder, updated);
            updatePreviewPayment(player, holder, inventory, crate, updated);
        }));
    }

    private void renderRewards(Player player, MenuHolder holder, Inventory inventory, Crate crate,
                               List<CrateReward> rewards, int page) {
        long now = System.currentTimeMillis();
        boolean bypass = player.hasPermission("plexoncrates.bypass.limit");
        Map<String, RewardStateService.Outcome> outcomes = new java.util.LinkedHashMap<>();
        for (CrateReward reward : rewards) plugin.openings().previewOutcome(player, crate, reward, now, bypass)
                .ifPresent(outcome -> outcomes.put(reward.id(), outcome));
        List<CrateReward> eligible = rewards.stream().filter(reward -> outcomes.containsKey(reward.id())).toList();
        int start = page * PlayerCrateLayout.contentSlots().size();
        for (int i = 0; i < PlayerCrateLayout.contentSlots().size() && start + i < rewards.size(); i++) {
            CrateReward source = rewards.get(start + i);
            RewardStateService.Outcome outcome = outcomes.get(source.id());
            CrateReward actual = outcome == null ? source : outcome.actual();
            int slot = PlayerCrateLayout.contentSlots().get(i);
            boolean pity = crate.pity().enabled() && (crate.pity().rewardIds().contains(source.id()) || crate.pity().rarity() == source.rarity());
            ProbabilityPresentation chance = crate.openingMode() == OpeningMode.SELECTIVE
                    ? ProbabilityPresentation.selective(outcome != null, pity)
                    : ProbabilityPresentation.random(outcome == null ? 0 : RewardSelector.chance(source, eligible),
                            source.baseChancePercent(), outcome != null, pity);
            ItemStack display = actual.displayCopy();
            List<Component> lore = new ArrayList<>();
            lore.add(Component.empty());
            lore.add(Text.parse("<gray>Rarity</gray> <dark_gray>»</dark_gray> <white>" + human(source.rarity().name()) + "</white>"));
            lore.addAll(rewardSummary(actual));
            lore.add(Text.parse("<aqua>" + chance.primary() + "</aqua>"));
            if (!chance.secondary().isBlank()) lore.add(Text.parse("<gray>" + chance.secondary() + "</gray>"));
            if (outcome != null && outcome.fallback()) {
                lore.add(Text.parse("<yellow>A configured alternative would be delivered.</yellow>"));
                lore.add(Text.parse("<gray>Current reward:</gray> ").append(actual.displayName()));
            }
            if (crate.openingMode() == OpeningMode.SELECTIVE && outcome != null && plugin.settings().selectiveOpeningEnabled()) {
                lore.add(Component.empty());
                lore.add(Text.parse("<green>Click to choose this reward.</green>"));
                holder.bind(slot, "select", source.id());
            }
            append(display, lore);
            inventory.setItem(slot, display);
        }
        if (rewards.isEmpty()) inventory.setItem(22, item(Material.BARRIER, "<yellow>No rewards are available</yellow>",
                "<gray>This crate currently has no player-visible rewards.</gray>"));
    }

    private void previewNavigation(MenuHolder holder, Inventory inventory, PlayerMenuContext context, int rewardCount) {
        if (context.page() > 0) bind(holder, inventory, PlayerCrateLayout.PREVIOUS, "previous", item(Material.ARROW, "<white>Previous</white>"));
        bind(holder, inventory, PlayerCrateLayout.CONTEXT, "refresh", item(Material.COMPASS, "<aqua>Refresh</aqua>",
                "<gray>Load the latest crate and payment information.</gray>"));
        bind(holder, inventory, PlayerCrateLayout.BACK, "back", item(Material.OAK_DOOR, "<white>Back</white>",
                "<gray>Return to Crate Hall.</gray>"));
        inventory.setItem(PlayerCrateLayout.STATUS, item(Material.PAPER,
                "<white>Rewards · Page " + (context.page() + 1) + " / " + PlayerCrateLayout.pageCount(rewardCount) + "</white>"));
        bind(holder, inventory, PlayerCrateLayout.CLOSE, "close", item(Material.BARRIER, "<red>Close</red>"));
        if (context.page() + 1 < PlayerCrateLayout.pageCount(rewardCount)) bind(holder, inventory, PlayerCrateLayout.NEXT, "next",
                item(Material.ARROW, "<white>Next</white>"));
    }

    private void updatePreviewPayment(Player player, MenuHolder holder, Inventory inventory, Crate crate, ViewState state) {
        PaymentPresentation payment = payment(player, crate, state, crate.keyCost());
        ViewState normalized = state.withContext(state.context().withPaymentPreference(payment.preference()));
        remember(holder, normalized);
        setPayment(holder, inventory, crate, payment);
        boolean allowed = canOpenHere(player, crate);
        if (crate.openingMode() == OpeningMode.SELECTIVE) {
            inventory.setItem(PlayerCrateLayout.PRIMARY, item(Material.COMPASS,
                    plugin.settings().selectiveOpeningEnabled() ? "<yellow>Choose a reward above</yellow>" : "<red>Selective opening unavailable</red>",
                    "<gray>Browsing and closing consume nothing.</gray>"));
            inventory.setItem(PlayerCrateLayout.SECONDARY, null);
            return;
        }
        if (payment.sufficient() && allowed) bind(holder, inventory, PlayerCrateLayout.PRIMARY, "open-one",
                item(Material.LIME_DYE, "<green>Open 1</green>", paymentLine(payment),
                        "<gray>One accepted click starts at most one opening.</gray>"));
        else inventory.setItem(PlayerCrateLayout.PRIMARY, item(Material.BARRIER, "<red>Open 1 unavailable</red>",
                allowed ? "<gray>You do not currently have enough payment.</gray>" : availabilityReason(player, crate)));
        if (plugin.settings().massOpeningEnabled() && crate.bulkEnabled()) {
            if (payment.sufficient() && allowed) bind(holder, inventory, PlayerCrateLayout.SECONDARY, "open-more",
                    item(Material.CHEST, "<aqua>Open More</aqua>", "<gray>Choose an exact quantity before confirming.</gray>"));
            else inventory.setItem(PlayerCrateLayout.SECONDARY, item(Material.GRAY_DYE, "<gray>Open More unavailable</gray>",
                    "<gray>A payable opening is required before choosing a batch.</gray>"));
        } else inventory.setItem(PlayerCrateLayout.SECONDARY, null);
    }

    private void openQuantity(Player player, Crate crate, ViewState origin, int knownMaximum) {
        PlayerMenuContext context = origin.context().quantity();
        long revision = plugin.runtime().crateRevision(crate.id());
        MenuHolder holder = new MenuHolder(MenuHolder.Kind.PLAYER_QUANTITY, crate.id(), "", context.page(), false, revision);
        Inventory inventory = inventory(holder, Text.parse("<white><bold>Open More</bold></white> <dark_gray>•</dark_gray> ").append(crate.displayName()));
        ViewState state = new ViewState(context, revision, origin.payment(), origin.paymentReady(), knownMaximum);
        remember(holder, state);
        inventory.setItem(13, item(Material.CHEST, "<aqua>Choose quantity</aqua>",
                "<gray>Nothing is consumed until the confirmation screen.</gray>"));
        bind(holder, inventory, PlayerCrateLayout.BACK, "back-preview", item(Material.OAK_DOOR, "<white>Back</white>"));
        bind(holder, inventory, PlayerCrateLayout.CLOSE, "close", item(Material.BARRIER, "<red>Close</red>"));
        open(player, inventory);
        if (knownMaximum >= 0) { renderQuantities(holder, inventory, crate, state, knownMaximum); return; }
        inventory.setItem(22, item(Material.CLOCK, "<aqua>Checking available openings…</aqua>"));
        plugin.openings().maximumAvailableAmount(player, crate).whenComplete((maximum, error) -> main(() -> {
            if (!sameView(player, holder, revision)) return;
            if (error != null || maximum == null) {
                inventory.setItem(22, item(Material.BARRIER, "<yellow>Availability could not be checked</yellow>",
                        "<gray>No payment was consumed.</gray>"));
                return;
            }
            int safe = Math.max(0, Math.min(maximum, Math.min(plugin.settings().maximumBulk(), crate.bulkMaximum())));
            ViewState updated = view(holder).withMaximum(safe);
            remember(holder, updated);
            renderQuantities(holder, inventory, crate, updated, safe);
        }));
    }

    private void renderQuantities(MenuHolder holder, Inventory inventory, Crate crate, ViewState state, int maximum) {
        for (int slot : QUANTITY_SLOTS) inventory.setItem(slot, null);
        if (maximum < 1) {
            inventory.setItem(22, item(Material.BARRIER, "<yellow>No payable openings</yellow>",
                    "<gray>Your current payment cannot cover a complete opening.</gray>"));
            return;
        }
        LinkedHashSet<Integer> choices = new LinkedHashSet<>();
        for (int value : List.of(1, 5, 10)) if (value <= maximum) choices.add(value);
        choices.add(maximum);
        int index = 0;
        for (int amount : choices) {
            int slot = QUANTITY_SLOTS.get(index++);
            long total = (long) amount * crate.keyCost();
            bind(holder, inventory, slot, "choose-quantity", Integer.toString(amount),
                    item(Material.CHEST, "<green>Open " + amount + "</green>",
                            "<gray>Total payment:</gray> <white>" + total + " key" + (total == 1 ? "" : "s") + "</white>",
                            "<gray>Expected openings:</gray> <white>" + amount + "</white>",
                            "<aqua>Click to review before confirming.</aqua>"));
        }
        inventory.setItem(PlayerCrateLayout.STATUS, item(Material.PAPER,
                "<white>Up to " + maximum + " opening" + (maximum == 1 ? "" : "s") + " currently available</white>"));
    }

    private void openMassConfirmation(Player player, Crate crate, ViewState origin, int amount) {
        PlayerMenuContext context = origin.context().massConfirm(amount);
        long revision = plugin.runtime().crateRevision(crate.id());
        MenuHolder holder = new MenuHolder(MenuHolder.Kind.PLAYER_MASS_CONFIRM, crate.id(), "", context.page(), false, revision);
        Inventory inventory = inventory(holder, Text.parse("<gold><bold>Confirm Open More</bold></gold>"));
        ViewState state = new ViewState(context, revision, origin.payment(), origin.paymentReady(), origin.maximum());
        remember(holder, state);
        long total = (long) amount * crate.keyCost();
        inventory.setItem(22, item(Material.CHEST, "<white>Open " + amount + " times</white>",
                "<gray>Quantity:</gray> <white>" + amount + "</white>",
                "<gray>Total payment:</gray> <white>" + total + " key" + (total == 1 ? "" : "s") + "</white>",
                "<yellow>This action commits the selected batch.</yellow>"));
        bind(holder, inventory, PlayerCrateLayout.BACK, "back-quantity", item(Material.OAK_DOOR, "<white>Back</white>"));
        bind(holder, inventory, PlayerCrateLayout.CLOSE, "close", item(Material.BARRIER, "<red>Cancel</red>"));
        open(player, inventory);
        updateMassConfirmation(player, holder, inventory, crate, state);
    }

    private void updateMassConfirmation(Player player, MenuHolder holder, Inventory inventory, Crate crate, ViewState state) {
        long rawRequired = (long) state.context().amount() * crate.keyCost();
        if (rawRequired > Integer.MAX_VALUE) return;
        PaymentPresentation payment = payment(player, crate, state, (int) rawRequired);
        ViewState normalized = state.withContext(state.context().withPaymentPreference(payment.preference()));
        remember(holder, normalized);
        setPayment(holder, inventory, crate, payment);
        if (payment.sufficient() && canOpenHere(player, crate) && state.context().amount() <= state.maximum())
            bind(holder, inventory, PlayerCrateLayout.PRIMARY, "confirm-mass",
                    item(Material.LIME_DYE, "<green>Confirm</green>", "<gray>Open exactly " + state.context().amount() + " times.</gray>"));
        else inventory.setItem(PlayerCrateLayout.PRIMARY, item(Material.BARRIER, "<red>Cannot confirm</red>",
                "<gray>The selected batch is no longer payable or available.</gray>"));
    }

    private void openSelectiveConfirmation(Player player, Crate crate, ViewState origin, CrateReward selected) {
        long revision = plugin.runtime().crateRevision(crate.id());
        PlayerMenuContext context = origin.context().selectiveConfirm(selected.id());
        MenuHolder holder = new MenuHolder(MenuHolder.Kind.PLAYER_SELECTIVE_CONFIRM, crate.id(), selected.id(), context.page(), false, revision);
        Inventory inventory = inventory(holder, Text.parse("<gold><bold>Confirm Selective Opening</bold></gold>"));
        ViewState state = new ViewState(context, revision, origin.payment(), origin.paymentReady(), -1);
        remember(holder, state);
        RewardStateService.Outcome outcome = previewOutcome(player, crate, selected);
        if (outcome == null) inventory.setItem(22, item(Material.BARRIER, "<yellow>This reward is no longer available</yellow>",
                "<gray>Return to Preview to load the latest rewards.</gray>"));
        else {
            ItemStack display = outcome.actual().displayCopy();
            List<Component> lore = new ArrayList<>(rewardSummary(outcome.actual()));
            lore.add(Component.empty());
            lore.add(Text.parse("<yellow>You are choosing this specific reward.</yellow>"));
            lore.add(Text.parse("<gray>Nothing is granted until you confirm.</gray>"));
            append(display, lore);
            inventory.setItem(22, display);
        }
        bind(holder, inventory, PlayerCrateLayout.BACK, "back-preview", item(Material.OAK_DOOR, "<white>Back</white>"));
        bind(holder, inventory, PlayerCrateLayout.CLOSE, "close", item(Material.BARRIER, "<red>Cancel</red>"));
        open(player, inventory);
        if (state.paymentReady()) updateSelectiveConfirmation(player, holder, inventory, crate, selected, state);
        else loadPayment(player, crate).whenComplete((availability, error) -> main(() -> {
            if (!sameView(player, holder, revision) || error != null || availability == null) return;
            ViewState updated = view(holder).withPayment(availability);
            remember(holder, updated);
            updateSelectiveConfirmation(player, holder, inventory, crate, selected, updated);
        }));
    }

    private void updateSelectiveConfirmation(Player player, MenuHolder holder, Inventory inventory,
                                             Crate crate, CrateReward selected, ViewState state) {
        PaymentPresentation payment = payment(player, crate, state, crate.keyCost());
        ViewState normalized = state.withContext(state.context().withPaymentPreference(payment.preference()));
        remember(holder, normalized);
        setPayment(holder, inventory, crate, payment);
        boolean eligible = previewOutcome(player, crate, selected) != null;
        if (payment.sufficient() && eligible && canOpenHere(player, crate))
            bind(holder, inventory, PlayerCrateLayout.PRIMARY, "confirm-selective",
                    item(Material.LIME_DYE, "<green>Confirm</green>", paymentLine(payment),
                            "<gray>Receive the selected reward if it remains eligible.</gray>"));
        else inventory.setItem(PlayerCrateLayout.PRIMARY, item(Material.BARRIER, "<red>Cannot confirm</red>",
                "<gray>Payment or reward eligibility changed.</gray>"));
    }

    public void openPendingRewards(Player player, int requestedPage, int hallPage) {
        if (!plugin.settings().claimInboxEnabled()) { plugin.messages().send(player, "disabled"); return; }
        int page = Math.max(1, requestedPage);
        long revision = plugin.runtime().snapshot().revision();
        MenuHolder holder = new MenuHolder(MenuHolder.Kind.PLAYER_PENDING_REWARDS, "", "", page - 1, false, revision);
        Inventory inventory = inventory(holder, Text.parse("<gradient:#CAD5E5:#FFFFFF><bold>Pending Rewards</bold></gradient>"));
        remember(holder, new ViewState(PlayerMenuContext.pendingRewards(page, hallPage), revision, List.of(), false, -1));
        inventory.setItem(22, item(Material.CLOCK, "<aqua>Loading pending rewards…</aqua>"));
        bind(holder, inventory, PlayerCrateLayout.BACK, "back-hall", item(Material.OAK_DOOR, "<white>Back</white>"));
        bind(holder, inventory, PlayerCrateLayout.CLOSE, "close", item(Material.BARRIER, "<red>Close</red>"));
        open(player, inventory);
        plugin.claims().list(player.getUniqueId(), page).whenComplete((entries, error) -> main(() -> {
            if (!sameView(player, holder, revision)) return;
            if (error != null || entries == null) {
                inventory.setItem(22, item(Material.BARRIER, "<yellow>Pending Rewards are temporarily unavailable</yellow>",
                        "<gray>No reward was changed.</gray>"));
                return;
            }
            renderPending(holder, inventory, entries, page);
        }));
    }

    private void renderPending(MenuHolder holder, Inventory inventory, List<DatabaseService.ClaimEntry> entries, int page) {
        for (int slot : PlayerCrateLayout.contentSlots()) inventory.setItem(slot, null);
        if (entries.isEmpty()) inventory.setItem(22, item(Material.CHEST, "<green>No pending rewards</green>",
                "<gray>Rewards waiting for safe delivery will appear here.</gray>"));
        for (int i = 0; i < Math.min(entries.size(), PlayerCrateLayout.contentSlots().size()); i++) {
            DatabaseService.ClaimEntry entry = entries.get(i);
            int slot = PlayerCrateLayout.contentSlots().get(i);
            inventory.setItem(slot, pendingCard(entry));
            holder.bind(slot, "claim", entry.claimId().toString());
        }
        if (page > 1) bind(holder, inventory, PlayerCrateLayout.PREVIOUS, "previous", item(Material.ARROW, "<white>Previous</white>"));
        bind(holder, inventory, PlayerCrateLayout.CONTEXT, "refresh", item(Material.COMPASS, "<aqua>Refresh</aqua>"));
        inventory.setItem(PlayerCrateLayout.STATUS, item(Material.PAPER, "<white>Pending Rewards · Page " + page + "</white>",
                "<gray>Click a reward to claim it safely.</gray>"));
        if (entries.size() == CLAIM_PAGE_SIZE) bind(holder, inventory, PlayerCrateLayout.NEXT, "next", item(Material.ARROW, "<white>Next</white>"));
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void click(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof MenuHolder holder) || !playerKind(holder.kind())) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || event.getClickedInventory() != event.getView().getTopInventory()) return;
        if (plugin.guiSessions().validate(player, holder, plugin.draftSessions()) != GuiSessionService.Validation.CURRENT) return;
        MenuHolder.Action action = holder.action(event.getRawSlot());
        if (views.get(holder.sessionId()) == null || action == null) return;
        UUID sessionId = holder.sessionId();
        later(() -> {
            if (!player.isOnline()) return;
            if (plugin.guiSessions().validate(player, holder, plugin.draftSessions()) != GuiSessionService.Validation.CURRENT) return;
            ViewState state = views.get(sessionId);
            if (state == null) return;
            switch (holder.kind()) {
                case PLAYER_HALL -> hallClick(player, holder, state, action);
                case PLAYER_PREVIEW -> previewClick(player, holder, state, action);
                case PLAYER_QUANTITY -> quantityClick(player, holder, state, action);
                case PLAYER_MASS_CONFIRM -> massClick(player, holder, state, action);
                case PLAYER_SELECTIVE_CONFIRM -> selectiveClick(player, holder, state, action);
                case PLAYER_PENDING_REWARDS -> pendingClick(player, holder, state, action);
                default -> { }
            }
        });
    }

    private void hallClick(Player player, MenuHolder holder, ViewState state, MenuHolder.Action action) {
        if (holder.revision() != plugin.runtime().snapshot().revision()) { stale(player, state.context()); return; }
        switch (action.id()) {
            case "close" -> player.closeInventory();
            case "refresh" -> openHall(player, state.context().hallPage());
            case "previous" -> openHall(player, state.context().hallPage() - 1);
            case "next" -> openHall(player, state.context().hallPage() + 1);
            case "pending" -> openPendingRewards(player, 1, state.context().hallPage());
            case "preview" -> plugin.runtime().find(action.value()).ifPresentOrElse(
                    crate -> openPreview(player, crate, state.context().hallPage(), 0, KeyPaymentPlanner.Preference.PHYSICAL),
                    () -> stale(player, state.context()));
            default -> { }
        }
    }

    private void previewClick(Player player, MenuHolder holder, ViewState state, MenuHolder.Action action) {
        Crate crate = currentCrate(player, holder, state); if (crate == null) return;
        PlayerMenuContext c = state.context();
        switch (action.id()) {
            case "close" -> player.closeInventory();
            case "back" -> openHall(player, c.hallPage());
            case "refresh" -> openPreview(player, crate, c.hallPage(), c.page(), c.paymentPreference());
            case "previous" -> openPreview(player, crate, c.hallPage(), c.page() - 1, c.paymentPreference());
            case "next" -> openPreview(player, crate, c.hallPage(), c.page() + 1, c.paymentPreference());
            case "toggle-payment" -> { ViewState v = state.withContext(c.withPaymentPreference(opposite(c.paymentPreference()))); remember(holder, v); updatePreviewPayment(player, holder, holder.getInventory(), crate, v); }
            case "open-one" -> submitRandom(player, holder, crate, state, 1);
            case "open-more" -> openQuantity(player, crate, state, -1);
            case "select" -> { CrateReward reward = crate.rewards().get(action.value()); if (reward == null) stale(player, c); else openSelectiveConfirmation(player, crate, state, reward); }
            default -> { }
        }
    }

    private void quantityClick(Player player, MenuHolder holder, ViewState state, MenuHolder.Action action) {
        Crate crate = currentCrate(player, holder, state); if (crate == null) return;
        switch (action.id()) {
            case "close" -> player.closeInventory();
            case "back-preview" -> openPreview(player, crate, state.context().hallPage(), state.context().page(), state.context().paymentPreference());
            case "choose-quantity" -> {
                try { int amount = Integer.parseInt(action.value()); if (amount < 1 || amount > state.maximum()) stale(player, state.context()); else openMassConfirmation(player, crate, state, amount); }
                catch (NumberFormatException ignored) { stale(player, state.context()); }
            }
            default -> { }
        }
    }

    private void massClick(Player player, MenuHolder holder, ViewState state, MenuHolder.Action action) {
        Crate crate = currentCrate(player, holder, state); if (crate == null) return;
        switch (action.id()) {
            case "close" -> player.closeInventory();
            case "back-quantity" -> openQuantity(player, crate, state, state.maximum());
            case "toggle-payment" -> { ViewState v = state.withContext(state.context().withPaymentPreference(opposite(state.context().paymentPreference()))); remember(holder, v); updateMassConfirmation(player, holder, holder.getInventory(), crate, v); }
            case "confirm-mass" -> submitRandom(player, holder, crate, state, state.context().amount());
            default -> { }
        }
    }

    private void selectiveClick(Player player, MenuHolder holder, ViewState state, MenuHolder.Action action) {
        Crate crate = currentCrate(player, holder, state); if (crate == null) return;
        CrateReward reward = crate.rewards().get(state.context().rewardId());
        if (reward == null) { stale(player, state.context()); return; }
        switch (action.id()) {
            case "close" -> player.closeInventory();
            case "back-preview" -> openPreview(player, crate, state.context().hallPage(), state.context().page(), state.context().paymentPreference());
            case "toggle-payment" -> { ViewState v = state.withContext(state.context().withPaymentPreference(opposite(state.context().paymentPreference()))); remember(holder, v); updateSelectiveConfirmation(player, holder, holder.getInventory(), crate, reward, v); }
            case "confirm-selective" -> submitSelective(player, holder, crate, state, reward);
            default -> { }
        }
    }

    private void pendingClick(Player player, MenuHolder holder, ViewState state, MenuHolder.Action action) {
        switch (action.id()) {
            case "close" -> player.closeInventory();
            case "back-hall" -> openHall(player, state.context().hallPage());
            case "refresh" -> openPendingRewards(player, state.context().page(), state.context().hallPage());
            case "previous" -> openPendingRewards(player, Math.max(1, state.context().page() - 1), state.context().hallPage());
            case "next" -> openPendingRewards(player, state.context().page() + 1, state.context().hallPage());
            case "claim" -> {
                if (!submitted.add(holder.sessionId())) return;
                try { UUID id = UUID.fromString(action.value()); player.closeInventory(); plugin.claims().claim(player, id); }
                catch (IllegalArgumentException ignored) { submitted.remove(holder.sessionId()); openPendingRewards(player, state.context().page(), state.context().hallPage()); }
            }
            default -> { }
        }
    }

    private void submitRandom(Player player, MenuHolder holder, Crate crate, ViewState state, int amount) {
        if (!submitted.add(holder.sessionId())) return;
        if (holder.revision() != plugin.runtime().crateRevision(crate.id())) { submitted.remove(holder.sessionId()); stale(player, state.context()); return; }
        player.closeInventory();
        if (!plugin.openings().open(player, crate, amount, OpenSource.GUI, null, state.context().paymentPreference())) submitted.remove(holder.sessionId());
    }

    private void submitSelective(Player player, MenuHolder holder, Crate crate, ViewState state, CrateReward reward) {
        if (!submitted.add(holder.sessionId())) return;
        if (holder.revision() != plugin.runtime().crateRevision(crate.id()) || previewOutcome(player, crate, reward) == null) {
            submitted.remove(holder.sessionId()); stale(player, state.context()); return;
        }
        player.closeInventory();
        if (!plugin.openings().openSelected(player, crate, reward.id(), 1, OpenSource.GUI, null, state.context().paymentPreference())) submitted.remove(holder.sessionId());
    }

    private Crate currentCrate(Player player, MenuHolder holder, ViewState state) {
        Crate crate = plugin.runtime().find(holder.crateId()).orElse(null);
        if (crate == null || holder.revision() != plugin.runtime().crateRevision(holder.crateId())) { stale(player, state.context()); return null; }
        return crate;
    }

    private void stale(Player player, PlayerMenuContext context) {
        player.sendMessage(Text.parse("<yellow>This crate changed while the menu was open. The latest information has been loaded.</yellow>"));
        if (context.crateId().isBlank()) openHall(player, context.hallPage());
        else plugin.runtime().find(context.crateId()).ifPresentOrElse(
                crate -> openPreview(player, crate, context.hallPage(), context.page(), context.paymentPreference()),
                () -> openHall(player, context.hallPage()));
    }

    private CompletableFuture<List<KeyPaymentPlanner.Availability>> loadPayment(Player player, Crate crate) {
        List<String> ids = crate.acceptedKeyIds();
        List<Integer> physical = new ArrayList<>();
        for (String id : ids) physical.add(plugin.keys().count(player, id));
        if (!plugin.settings().virtualKeyWalletEnabled() || crate.paymentPolicy() == KeyPaymentPolicy.PHYSICAL_ONLY) {
            List<KeyPaymentPlanner.Availability> values = new ArrayList<>();
            for (int i = 0; i < ids.size(); i++) values.add(new KeyPaymentPlanner.Availability(ids.get(i), physical.get(i), 0, i));
            return CompletableFuture.completedFuture(List.copyOf(values));
        }
        List<CompletableFuture<DatabaseService.VirtualKeyBalance>> futures = ids.stream()
                .map(id -> plugin.database().loadVirtualKeyBalance(player.getUniqueId(), id)).toList();
        return CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).thenApply(ignored -> {
            List<KeyPaymentPlanner.Availability> values = new ArrayList<>();
            for (int i = 0; i < ids.size(); i++) values.add(new KeyPaymentPlanner.Availability(ids.get(i), physical.get(i), futures.get(i).join().balance(), i));
            return List.copyOf(values);
        });
    }

    private PaymentPresentation payment(Player player, Crate crate, ViewState state, int required) {
        return PaymentPresentation.resolve(crate.paymentPolicy(), crate.mixedPayment(), required, state.payment(),
                state.context().paymentPreference(), player.hasPermission("plexoncrates.bypass.key"));
    }

    private void setPayment(MenuHolder holder, Inventory inventory, Crate crate, PaymentPresentation payment) {
        List<Component> lore = new ArrayList<>();
        lore.add(Text.parse("<gray>Source</gray> <dark_gray>»</dark_gray> <white>" + payment.sourceLabel() + "</white>"));
        if (payment.required() > 0) {
            lore.add(Text.parse("<gray>Required</gray> <dark_gray>»</dark_gray> <white>" + payment.required() + "</white>"));
            lore.add(Text.parse("<gray>Available</gray> <dark_gray>»</dark_gray> <white>" + payment.available() + "</white>"));
        }
        if (!payment.keyId().isBlank()) lore.add(Text.parse("<gray>Key</gray> <dark_gray>»</dark_gray> ").append(keyName(payment.keyId())));
        lore.add(payment.sufficient() ? Text.parse("<green>Ready</green>") : Text.parse("<yellow>Not enough payment</yellow>"));
        if (payment.choiceVisible()) lore.add(Text.parse("<aqua>Click to switch payment source.</aqua>"));
        inventory.setItem(PlayerCrateLayout.PAYMENT, item(Material.TRIPWIRE_HOOK, Text.parse("<aqua><bold>Payment</bold></aqua>"), lore));
        if (payment.choiceVisible()) holder.bind(PlayerCrateLayout.PAYMENT, "toggle-payment");
    }

    private ItemStack crateCard(Player player, Crate crate) {
        ItemStack icon = crate.iconCopy();
        List<Component> lore = new ArrayList<>();
        if (!crate.description().isEmpty()) { lore.add(Component.empty()); crate.description().stream().limit(2).forEach(lore::add); }
        lore.add(Component.empty());
        lore.add(Text.parse("<gray>Payment</gray> <dark_gray>»</dark_gray> ").append(keyRequirement(crate)));
        lore.add(Text.parse("<gray>Opening</gray> <dark_gray>»</dark_gray> <white>" + human(crate.openingMode().name()) + "</white>"));
        if (crate.openingMode() != OpeningMode.SELECTIVE && crate.bulkEnabled() && plugin.settings().massOpeningEnabled())
            lore.add(Text.parse("<gray>Open More</gray> <dark_gray>»</dark_gray> <white>Available</white>"));
        lore.add(Text.parse(canOpenHere(player, crate) ? "<green>Available here</green>" : availabilityReason(player, crate)));
        lore.add(Component.empty());
        lore.add(Text.parse("<aqua>Click to Preview.</aqua>"));
        append(icon, lore);
        return icon;
    }

    private ItemStack previewHeader(Player player, Crate crate) {
        ItemStack icon = crate.iconCopy();
        List<Component> lore = new ArrayList<>(crate.description().stream().limit(3).toList());
        lore.add(Component.empty());
        lore.add(Text.parse("<gray>Opening mode</gray> <dark_gray>»</dark_gray> <white>" + human(crate.openingMode().name()) + "</white>"));
        lore.add(Text.parse("<gray>Payment</gray> <dark_gray>»</dark_gray> ").append(keyRequirement(crate)));
        if (crate.pity().enabled()) {
            int remaining = plugin.rewardStates().pityRemaining(player.getUniqueId(), crate);
            lore.add(Text.parse("<light_purple>Guaranteed pool in " + remaining + " opening" + (remaining == 1 ? "" : "s") + ".</light_purple>"));
        }
        if (plugin.settings().milestonesEnabled() && !crate.orderedMilestones().isEmpty()) {
            var progress = plugin.milestoneProgress().progress(player.getUniqueId(), crate.id());
            lore.add(Text.parse("<gold>Milestones earned:</gold> <white>" + progress.earnedKeys().size() + "</white>"));
        }
        lore.add(Text.parse(canOpenHere(player, crate) ? "<green>Available here</green>" : availabilityReason(player, crate)));
        append(icon, lore);
        return icon;
    }

    private ItemStack pendingCard(DatabaseService.ClaimEntry entry) {
        ItemStack display;
        if (entry.itemBytes() == null) {
            display = new ItemStack(Material.TRIPWIRE_HOOK);
            Component name = entry.virtualKeyId() == null ? Text.parse("<white>Virtual key</white>") : keyName(entry.virtualKeyId());
            display.editMeta(meta -> meta.displayName(name.append(Text.parse(" <aqua>×" + entry.virtualKeyAmount() + "</aqua>"))));
        } else {
            try {
                ItemSnapshotCodec.Snapshot snapshot = new ItemSnapshotCodec.Snapshot(entry.itemBytes(), "unknown", entry.itemAmount(),
                        entry.itemBytes().length, entry.itemSha256().toLowerCase(Locale.ROOT), false, false, entry.createdAt());
                display = snapshots.restoreTemplate(snapshot);
                display.setAmount(Math.min(entry.itemAmount(), Math.max(1, display.getMaxStackSize())));
            } catch (RuntimeException error) {
                display = item(Material.BARRIER, "<yellow>Reward needs support</yellow>", "<gray>This reward cannot be previewed safely.</gray>");
            }
        }
        List<Component> lore = new ArrayList<>();
        lore.add(Component.empty());
        if (entry.itemBytes() != null) lore.add(Text.parse("<gray>Quantity</gray> <dark_gray>»</dark_gray> <white>" + entry.itemAmount() + "</white>"));
        plugin.runtime().find(entry.crateId()).ifPresent(crate -> lore.add(Text.parse("<gray>From</gray> <dark_gray>»</dark_gray> ").append(crate.displayName())));
        lore.add(Text.parse("<green>Ready to claim</green>"));
        lore.add(Text.parse("<aqua>Click to Claim.</aqua>"));
        append(display, lore);
        return display;
    }

    private List<Component> rewardSummary(CrateReward reward) {
        List<Component> lore = new ArrayList<>();
        int items = reward.itemCopies().stream().mapToInt(ItemStack::getAmount).sum();
        if (items > 0) lore.add(Text.parse("<gray>Items</gray> <dark_gray>»</dark_gray> <white>" + items + "</white>"));
        if (reward.money() > 0) lore.add(Text.parse("<gray>Money</gray> <dark_gray>»</dark_gray> <white>" + format(reward.money()) + "</white>"));
        if (reward.experiencePoints() > 0) lore.add(Text.parse("<gray>Experience</gray> <dark_gray>»</dark_gray> <white>" + reward.experiencePoints() + " XP</white>"));
        if (reward.experienceLevels() > 0) lore.add(Text.parse("<gray>Levels</gray> <dark_gray>»</dark_gray> <white>" + reward.experienceLevels() + "</white>"));
        if (!reward.commands().isEmpty()) lore.add(Text.parse("<gray>Includes an additional server-delivered reward.</gray>"));
        return lore;
    }

    private RewardStateService.Outcome previewOutcome(Player player, Crate crate, CrateReward reward) {
        return plugin.openings().previewOutcome(player, crate, reward, System.currentTimeMillis(), player.hasPermission("plexoncrates.bypass.limit")).orElse(null);
    }

    private Component keyRequirement(Crate crate) {
        if (crate.keyCost() <= 0 || crate.acceptedKeyIds().isEmpty()) return Text.parse("<green>Free</green>");
        Component names = Component.empty();
        for (int i = 0; i < crate.acceptedKeyIds().size(); i++) {
            if (i > 0) names = names.append(Text.parse("<dark_gray> / </dark_gray>"));
            names = names.append(keyName(crate.acceptedKeyIds().get(i)));
        }
        return Text.parse("<white>" + crate.keyCost() + "× </white>").append(names);
    }

    private Component keyName(String id) {
        return plugin.keys().template(id).map(item -> {
            Component name = item.getItemMeta().displayName();
            return name == null ? Text.parse("<white>crate key</white>") : name;
        }).orElseGet(() -> Text.parse("<white>crate key</white>"));
    }

    private boolean canOpenHere(Player player, Crate crate) {
        return plugin.settings().enabled() && plugin.settings().allows(player.getWorld()) && crate.allows(player.getWorld())
                && (crate.permission().isBlank() || player.hasPermission(crate.permission())) && playerCan(player, "plexoncrates.open");
    }

    private String availabilityReason(Player player, Crate crate) {
        if (!plugin.settings().enabled()) return "<yellow>Crates are temporarily unavailable.</yellow>";
        if (!plugin.settings().allows(player.getWorld()) || !crate.allows(player.getWorld())) return "<yellow>Not available in this world.</yellow>";
        if (!crate.permission().isBlank() && !player.hasPermission(crate.permission())) return "<yellow>You do not currently have access to this crate.</yellow>";
        return "<yellow>Opening is not available.</yellow>";
    }

    private static boolean playerCan(Player player, String permission) { return player.hasPermission(permission) || player.hasPermission("plexoncrates.use"); }

    private boolean sameView(Player player, MenuHolder holder, long expectedRevision) {
        if (!player.isOnline() || plugin.guiSessions().validate(player, holder, plugin.draftSessions()) != GuiSessionService.Validation.CURRENT) return false;
        if (holder.kind() == MenuHolder.Kind.PLAYER_HALL || holder.kind() == MenuHolder.Kind.PLAYER_PENDING_REWARDS)
            return holder.revision() == expectedRevision && plugin.runtime().snapshot().revision() == expectedRevision;
        return holder.revision() == expectedRevision && plugin.runtime().crateRevision(holder.crateId()) == expectedRevision;
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
        if (inventory.getHolder() instanceof MenuHolder holder && player.getOpenInventory().getTopInventory() == inventory) {
            owners.put(holder.sessionId(), player.getUniqueId());
            plugin.guiSessions().activate(player.getUniqueId(), holder);
        } else if (inventory.getHolder() instanceof MenuHolder holder) {
            views.remove(holder.sessionId());
            submitted.remove(holder.sessionId());
            owners.remove(holder.sessionId());
        }
    }

    private static void bind(MenuHolder holder, Inventory inventory, int slot, String action, ItemStack item) {
        inventory.setItem(slot, item); holder.bind(slot, action);
    }

    private static void bind(MenuHolder holder, Inventory inventory, int slot, String action, String value, ItemStack item) {
        inventory.setItem(slot, item); holder.bind(slot, action, value);
    }

    private static ItemStack item(Material material, String name, String... lore) {
        List<Component> lines = new ArrayList<>();
        for (String line : lore) lines.add(Text.parse(line));
        return item(material, Text.parse(name), lines);
    }

    private static ItemStack item(Material material, Component name, List<Component> lore) {
        ItemStack item = new ItemStack(material);
        item.editMeta(meta -> {
            meta.displayName(name.decoration(TextDecoration.ITALIC, false));
            meta.lore(lore.stream().map(line -> line.decoration(TextDecoration.ITALIC, false)).toList());
        });
        return item;
    }

    private static void append(ItemStack item, List<Component> extra) {
        item.editMeta(meta -> {
            List<Component> lore = new ArrayList<>();
            if (meta.lore() != null) lore.addAll(meta.lore());
            lore.addAll(extra);
            meta.lore(lore.stream().map(line -> line.decoration(TextDecoration.ITALIC, false)).toList());
        });
    }

    private static String paymentLine(PaymentPresentation payment) {
        return payment.required() <= 0 ? "<gray>Payment:</gray> <white>" + payment.sourceLabel() + "</white>"
                : "<gray>Payment:</gray> <white>" + payment.required() + " key" + (payment.required() == 1 ? "" : "s")
                + " via " + payment.sourceLabel() + "</white>";
    }

    private static KeyPaymentPlanner.Preference opposite(KeyPaymentPlanner.Preference value) {
        return value == KeyPaymentPlanner.Preference.PHYSICAL ? KeyPaymentPlanner.Preference.VIRTUAL : KeyPaymentPlanner.Preference.PHYSICAL;
    }

    private static String human(String value) {
        String text = value.toLowerCase(Locale.ROOT).replace('_', ' ');
        return text.isEmpty() ? text : Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }

    private static String format(double value) { return String.format(Locale.ROOT, "%.2f", value).replaceAll("0+$", "").replaceAll("\\.$", ""); }

    private static boolean playerKind(MenuHolder.Kind kind) {
        return switch (kind) {
            case PLAYER_HALL, PLAYER_PREVIEW, PLAYER_QUANTITY, PLAYER_MASS_CONFIRM, PLAYER_SELECTIVE_CONFIRM, PLAYER_PENDING_REWARDS -> true;
            default -> false;
        };
    }

    private void remember(MenuHolder holder, ViewState state) { views.put(holder.sessionId(), state); }
    private ViewState view(MenuHolder holder) { return views.get(holder.sessionId()); }

    private void main(Runnable task) {
        if (!plugin.isEnabled()) return;
        Bukkit.getScheduler().runTask(plugin, () -> { if (plugin.isEnabled()) task.run(); });
    }

    private void later(Runnable task) { Bukkit.getScheduler().runTask(plugin, task); }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void close(InventoryCloseEvent event) {
        if (event.getInventory().getHolder() instanceof MenuHolder holder && playerKind(holder.kind())) {
            views.remove(holder.sessionId());
            submitted.remove(holder.sessionId());
            owners.remove(holder.sessionId());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void quit(PlayerQuitEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        owners.entrySet().removeIf(entry -> {
            if (!entry.getValue().equals(playerId)) return false;
            UUID sessionId = entry.getKey();
            views.remove(sessionId);
            submitted.remove(sessionId);
            return true;
        });
    }

    private record ViewState(PlayerMenuContext context, long revision, List<KeyPaymentPlanner.Availability> payment,
                             boolean paymentReady, int maximum) {
        private ViewState { context = Objects.requireNonNull(context); payment = List.copyOf(payment); }
        private ViewState withPayment(List<KeyPaymentPlanner.Availability> value) { return new ViewState(context, revision, value, true, maximum); }
        private ViewState withContext(PlayerMenuContext value) { return new ViewState(value, revision, payment, paymentReady, maximum); }
        private ViewState withMaximum(int value) { return new ViewState(context, revision, payment, paymentReady, value); }
    }
}
