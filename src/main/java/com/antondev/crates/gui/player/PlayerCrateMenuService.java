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
 * Phase 3 player-product projection. The authoritative opening, payment, reward,
 * journal and claim services remain outside this class.
 */
public final class PlayerCrateMenuService implements Listener {
    private static final int CLAIM_PAGE_SIZE = 20;
    private static final List<Integer> QUANTITY_SLOTS = List.of(20, 22, 24, 31);

    private final PlexonCrates plugin;
    private final ItemSnapshotCodec itemSnapshots = new ItemSnapshotCodec();
    private final Map<UUID, SessionState> views = new ConcurrentHashMap<>();
    private final Set<UUID> submitted = ConcurrentHashMap.newKeySet();

    public PlayerCrateMenuService(PlexonCrates plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    /**
     * Redirects the legacy command/browser presentation before it becomes an
     * interactive inventory. Physical block opening itself remains untouched.
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void redirectLegacyPlayerSurface(InventoryOpenEvent event) {
        if (!(event.getPlayer() instanceof Player player)
                || !(event.getInventory().getHolder() instanceof MenuHolder holder)
                || holder.adminOrigin()) return;
        if (holder.kind() == MenuHolder.Kind.BROWSER) {
            event.setCancelled(true);
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (player.isOnline()) openHall(player, 0);
            });
        } else if (holder.kind() == MenuHolder.Kind.PREVIEW) {
            event.setCancelled(true);
            String crateId = holder.crateId();
            int rewardPage = holder.page();
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!player.isOnline()) return;
                Crate crate = plugin.runtime().find(crateId).orElse(null);
                if (crate == null) openHall(player, 0);
                else openPreview(player, crate, 0, rewardPage, KeyPaymentPlanner.Preference.PHYSICAL);
            });
        }
    }

    /** Keeps the compatibility command while making /crates claim a GUI recovery surface. */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void pendingRewardsCommand(PlayerCommandPreprocessEvent event) {
        String raw = event.getMessage().trim();
        if (raw.length() < 2) return;
        String[] parts = raw.substring(1).split("\\s+");
        String root = parts[0].toLowerCase(Locale.ROOT);
        if (!Set.of("crates", "crate", "plexoncrates").contains(root)
                || parts.length < 2 || !parts[1].equalsIgnoreCase("claim")) return;
        if (parts.length > 3) return;
        int page = 1;
        if (parts.length == 3) {
            try {
                page = Integer.parseInt(parts[2]);
                if (page < 1) return;
            } catch (NumberFormatException ignored) {
                // UUID claim commands keep using ClaimService directly for compatibility.
                return;
            }
        }
        if (!playerCan(event.getPlayer(), "plexoncrates.claim") || !plugin.settings().claimInboxEnabled()) return;
        event.setCancelled(true);
        openPendingRewards(event.getPlayer(), page, 0);
    }

    public void openHall(Player player, int requestedPage) {
        List<Crate> crates = plugin.runtime().ordered();
        int page = PlayerCrateLayout.clampPage(requestedPage, crates.size());
        long runtimeRevision = plugin.runtime().snapshot().revision();
        MenuHolder holder = new MenuHolder(MenuHolder.Kind.PLAYER_HALL, "", "", page, false, runtimeRevision);
        Inventory inventory = create(holder, Text.parse("<gradient:#CAD5E5:#FFFFFF><bold>Crate Hall</bold></gradient>"));
        fill(inventory);
        PlayerMenuContext context = PlayerMenuContext.hall(page);
        views.put(holder.sessionId(), new SessionState(context, runtimeRevision, List.of(), false, -1));

        int start = page * PlayerCrateLayout.contentSlots().size();
        for (int index = 0; index < PlayerCrateLayout.contentSlots().size() && start + index < crates.size(); index++) {
            Crate crate = crates.get(start + index);
            int slot = PlayerCrateLayout.contentSlots().get(index);
            inventory.setItem(slot, crateCard(player, crate));
            holder.bind(slot, "preview", crate.id());
        }
        if (crates.isEmpty()) {
            inventory.setItem(22, button(Material.BARRIER, "<yellow>No crates are available</yellow>",
                    "<gray>Published crates will appear here automatically.</gray>"));
        }
        if (page > 0) {
            inventory.setItem(PlayerCrateLayout.PREVIOUS, button(Material.ARROW, "<white>Previous</white>"));
            holder.bind(PlayerCrateLayout.PREVIOUS, "previous");
        }
        inventory.setItem(PlayerCrateLayout.CONTEXT, button(Material.COMPASS, "<aqua>Refresh</aqua>",
                "<gray>Load the latest published crate information.</gray>"));
        holder.bind(PlayerCrateLayout.CONTEXT, "refresh");
        inventory.setItem(PlayerCrateLayout.PRIMARY, button(Material.CHEST, "<green>Pending Rewards</green>",
                "<gray>Loading your pending reward count…</gray>"));
        holder.bind(PlayerCrateLayout.PRIMARY, "pending");
        inventory.setItem(PlayerCrateLayout.STATUS, button(Material.PAPER,
                "<white>Page " + (page + 1) + " / " + PlayerCrateLayout.pageCount(crates.size()) + "</white>",
                "<gray>" + crates.size() + " crate" + (crates.size() == 1 ? "" : "s") + " available</gray>"));
        inventory.setItem(PlayerCrateLayout.CLOSE, button(Material.BARRIER, "<red>Close</red>"));
        holder.bind(PlayerCrateLayout.CLOSE, "close");
        if (page + 1 < PlayerCrateLayout.pageCount(crates.size())) {
            inventory.setItem(PlayerCrateLayout.NEXT, button(Material.ARROW, "<white>Next</white>"));
            holder.bind(PlayerCrateLayout.NEXT, "next");
        }
        open(player, inventory);
        loadPendingCount(player, holder, inventory);
    }

    public void openPreview(Player player, Crate requested, int hallPage, int requestedRewardPage,
                            KeyPaymentPlanner.Preference preference) {
        Crate crate = plugin.runtime().find(requested.id()).orElse(null);
        if (crate == null) {
            openHall(player, hallPage);
            return;
        }
        List<CrateReward> rewards = crate.orderedRewards().stream().filter(CrateReward::enabled).toList();
        int page = PlayerCrateLayout.clampPage(requestedRewardPage, rewards.size());
        long revision = plugin.runtime().crateRevision(crate.id());
        MenuHolder holder = new MenuHolder(MenuHolder.Kind.PLAYER_PREVIEW, crate.id(), "", page, false, revision);
        Inventory inventory = create(holder, Text.parse("<white><bold>Preview</bold></white> <dark_gray>•</dark_gray> ")
                .append(crate.displayName()));
        fill(inventory);
        PlayerMenuContext context = PlayerMenuContext.preview(crate.id(), hallPage, page)
                .withPaymentPreference(preference);
        views.put(holder.sessionId(), new SessionState(context, revision, List.of(), false, -1));

        inventory.setItem(4, previewHeader(player, crate));
        renderRewardPage(player, holder, inventory, crate, rewards, page);
        navigation(holder, inventory, context, rewards.size());
        inventory.setItem(PlayerCrateLayout.PAYMENT, button(Material.CLOCK, "<aqua>Payment</aqua>",
                "<gray>Checking your available payment…</gray>"));
        inventory.setItem(PlayerCrateLayout.PRIMARY, button(Material.GRAY_DYE,
                crate.openingMode() == OpeningMode.SELECTIVE ? "<yellow>Choose a reward above</yellow>" : "<gray>Open 1</gray>",
                "<gray>Payment status is loading.</gray>"));
        if (crate.openingMode() != OpeningMode.SELECTIVE && plugin.settings().massOpeningEnabled() && crate.bulkEnabled()) {
            inventory.setItem(PlayerCrateLayout.SECONDARY, button(Material.GRAY_DYE, "<gray>Open More</gray>",
                    "<gray>Payment status is loading.</gray>"));
        }
        open(player, inventory);
        loadPaymentSnapshot(player, crate).whenComplete((availability, error) -> onMain(() -> {
            if (!sameView(player, holder, revision)) return;
            SessionState state = views.get(holder.sessionId());
            if (state == null) return;
            if (error != null || availability == null) {
                inventory.setItem(PlayerCrateLayout.PAYMENT, button(Material.BARRIER, "<yellow>Payment status unavailable</yellow>",
                        "<gray>No key has been consumed.</gray>", "<gray>Refresh this preview to try again.</gray>"));
                return;
            }
            SessionState updated = state.withPayment(availability);
            views.put(holder.sessionId(), updated);
            updatePreviewPayment(player, holder, inventory, crate, updated);
        }));
    }

    private void renderRewardPage(Player player, MenuHolder holder, Inventory inventory, Crate crate,
                                  List<CrateReward> rewards, int page) {
        long now = System.currentTimeMillis();
        boolean bypassLimits = player.hasPermission("plexoncrates.bypass.limit");
        Map<String, RewardStateService.Outcome> outcomes = new java.util.LinkedHashMap<>();
        for (CrateReward reward : rewards) {
            plugin.openings().previewOutcome(player, crate, reward, now, bypassLimits)
                    .ifPresent(outcome -> outcomes.put(reward.id(), outcome));
        }
        List<CrateReward> eligible = rewards.stream().filter(reward -> outcomes.containsKey(reward.id())).toList();
        int start = page * PlayerCrateLayout.contentSlots().size();
        for (int index = 0; index < PlayerCrateLayout.contentSlots().size() && start + index < rewards.size(); index++) {
            CrateReward source = rewards.get(start + index);
            RewardStateService.Outcome outcome = outcomes.get(source.id());
            CrateReward actual = outcome == null ? source : outcome.actual();
            int slot = PlayerCrateLayout.contentSlots().get(index);
            ItemStack display = actual.displayCopy();
            boolean pityPool = crate.pity().enabled() && (crate.pity().rewardIds().contains(source.id())
                    || crate.pity().rarity() == source.rarity());
            ProbabilityPresentation probability;
            if (crate.openingMode() == OpeningMode.SELECTIVE) {
                probability = ProbabilityPresentation.selective(outcome != null, pityPool);
            } else {
                double effective = outcome == null ? 0.0 : RewardSelector.chance(source, eligible);
                probability = ProbabilityPresentation.random(effective, source.baseChancePercent(), outcome != null, pityPool);
            }
            List<Component> lore = new ArrayList<>();
            lore.add(Component.empty());
            lore.add(Text.parse("<gray>Rarity</gray> <dark_gray>»</dark_gray> <white>" + human(source.rarity().name()) + "</white>"));
            lore.addAll(rewardSummary(actual));
            lore.add(Text.parse("<aqua>" + probability.primary() + "</aqua>"));
            if (!probability.secondary().isBlank()) lore.add(Text.parse("<gray>" + probability.secondary() + "</gray>"));
            if (outcome != null && outcome.fallback()) {
                lore.add(Text.parse("<yellow>A configured alternative would be delivered.</yellow>"));
                lore.add(Text.parse("<gray>Current reward:</gray> ").append(actual.displayName()));
            }
            if (crate.openingMode() == OpeningMode.SELECTIVE && outcome != null
                    && plugin.settings().selectiveOpeningEnabled()) {
                lore.add(Component.empty());
                lore.add(Text.parse("<green>Click to choose this reward.</green>"));
                holder.bind(slot, "select", source.id());
            }
            appendLore(display, lore);
            inventory.setItem(slot, display);
        }
        if (rewards.isEmpty()) {
            inventory.setItem(22, button(Material.BARRIER, "<yellow>No rewards are available</yellow>",
                    "<gray>This crate currently has no player-visible rewards.</gray>"));
        }
    }

    private void navigation(MenuHolder holder, Inventory inventory, PlayerMenuContext context, int rewardCount) {
        if (context.page() > 0) {
            inventory.setItem(PlayerCrateLayout.PREVIOUS, button(Material.ARROW, "<white>Previous</white>"));
            holder.bind(PlayerCrateLayout.PREVIOUS, "previous");
        }
        inventory.setItem(PlayerCrateLayout.CONTEXT, button(Material.COMPASS, "<aqua>Refresh</aqua>",
                "<gray>Load the latest crate and payment information.</gray>"));
        holder.bind(PlayerCrateLayout.CONTEXT, "refresh");
        inventory.setItem(PlayerCrateLayout.BACK, button(Material.OAK_DOOR, "<white>Back</white>",
                "<gray>Return to Crate Hall.</gray>"));
        holder.bind(PlayerCrateLayout.BACK, "back");
        inventory.setItem(PlayerCrateLayout.STATUS, button(Material.PAPER,
                "<white>Rewards · Page " + (context.page() + 1) + " / " + PlayerCrateLayout.pageCount(rewardCount) + "</white>"));
        inventory.setItem(PlayerCrateLayout.CLOSE, button(Material.BARRIER, "<red>Close</red>"));
        holder.bind(PlayerCrateLayout.CLOSE, "close");
        if (context.page() + 1 < PlayerCrateLayout.pageCount(rewardCount)) {
            inventory.setItem(PlayerCrateLayout.NEXT, button(Material.ARROW, "<white>Next</white>"));
            holder.bind(PlayerCrateLayout.NEXT, "next");
        }
    }

    private void updatePreviewPayment(Player player, MenuHolder holder, Inventory inventory,
                                      Crate crate, SessionState state) {
        int required = Math.max(0, crate.keyCost());
        PaymentPresentation payment = PaymentPresentation.resolve(crate.paymentPolicy(), crate.mixedPayment(), required,
                state.payment(), state.context().paymentPreference(), player.hasPermission("plexoncrates.bypass.key"));
        PlayerMenuContext context = state.context().withPaymentPreference(payment.preference());
        SessionState normalized = state.withContext(context);
        views.put(holder.sessionId(), normalized);
        inventory.setItem(PlayerCrateLayout.PAYMENT, paymentButton(crate, payment));
        if (payment.choiceVisible()) holder.bind(PlayerCrateLayout.PAYMENT, "toggle-payment");

        boolean allowed = canOpenHere(player, crate);
        if (crate.openingMode() == OpeningMode.SELECTIVE) {
            inventory.setItem(PlayerCrateLayout.PRIMARY, button(Material.COMPASS,
                    plugin.settings().selectiveOpeningEnabled() ? "<yellow>Choose a reward above</yellow>" : "<red>Selective opening unavailable</red>",
                    "<gray>Browsing and closing consume nothing.</gray>"));
            inventory.setItem(PlayerCrateLayout.SECONDARY, null);
            return;
        }
        if (payment.sufficient() && allowed) {
            inventory.setItem(PlayerCrateLayout.PRIMARY, button(Material.LIME_DYE, "<green>Open 1</green>",
                    paymentLine(payment), "<gray>One accepted click starts at most one opening.</gray>"));
            holder.bind(PlayerCrateLayout.PRIMARY, "open-one");
        } else {
            inventory.setItem(PlayerCrateLayout.PRIMARY, button(Material.BARRIER, "<red>Open 1 unavailable</red>",
                    allowed ? "<gray>You do not currently have enough payment.</gray>" : availabilityReason(player, crate)));
        }
        if (plugin.settings().massOpeningEnabled() && crate.bulkEnabled()) {
            if (payment.sufficient() && allowed) {
                inventory.setItem(PlayerCrateLayout.SECONDARY, button(Material.CHEST, "<aqua>Open More</aqua>",
                        "<gray>Choose an exact opening quantity before confirming.</gray>"));
                holder.bind(PlayerCrateLayout.SECONDARY, "open-more");
            } else {
                inventory.setItem(PlayerCrateLayout.SECONDARY, button(Material.GRAY_DYE, "<gray>Open More unavailable</gray>",
                        "<gray>A payable opening is required before choosing a batch.</gray>"));
            }
        } else inventory.setItem(PlayerCrateLayout.SECONDARY, null);
    }

    private ItemStack paymentButton(Crate crate, PaymentPresentation payment) {
        List<Component> lore = new ArrayList<>();
        lore.add(Text.parse("<gray>Source</gray> <dark_gray>»</dark_gray> <white>" + payment.sourceLabel() + "</white>"));
        if (payment.required() > 0) {
            lore.add(Text.parse("<gray>Required</gray> <dark_gray>»</dark_gray> <white>" + payment.required() + " key"
                    + (payment.required() == 1 ? "" : "s") + "</white>"));
            lore.add(Text.parse("<gray>Available</gray> <dark_gray>»</dark_gray> <white>" + payment.available() + "</white>"));
        }
        if (!payment.keyId().isBlank()) lore.add(Text.parse("<gray>Key</gray> <dark_gray>»</dark_gray> ").append(keyName(payment.keyId())));
        lore.add(payment.sufficient() ? Text.parse("<green>Ready</green>") : Text.parse("<yellow>Not enough payment</yellow>"));
        if (payment.choiceVisible()) lore.add(Text.parse("<aqua>Click to switch payment source.</aqua>"));
        return button(Material.TRIPWIRE_HOOK, Text.parse("<aqua><bold>Payment</bold></aqua>"), lore);
    }

    private void openQuantity(Player player, Crate crate, SessionState origin, int knownMaximum) {
        PlayerMenuContext context = origin.context().quantity();
        long revision = plugin.runtime().crateRevision(crate.id());
        MenuHolder holder = new MenuHolder(MenuHolder.Kind.PLAYER_QUANTITY, crate.id(), "", context.page(), false, revision);
        Inventory inventory = create(holder, Text.parse("<white><bold>Open More</bold></white> <dark_gray>•</dark_gray> ").append(crate.displayName()));
        fill(inventory);
        SessionState state = new SessionState(context, revision, origin.payment(), origin.paymentReady(), knownMaximum);
        views.put(holder.sessionId(), state);
        inventory.setItem(13, button(Material.CHEST, "<aqua>Choose quantity</aqua>",
                "<gray>Select a batch size. Nothing is consumed until confirmation.</gray>"));
        inventory.setItem(PlayerCrateLayout.BACK, button(Material.OAK_DOOR, "<white>Back</white>"));
        holder.bind(PlayerCrateLayout.BACK, "back-preview");
        inventory.setItem(PlayerCrateLayout.CLOSE, button(Material.BARRIER, "<red>Close</red>"));
        holder.bind(PlayerCrateLayout.CLOSE, "close");
        open(player, inventory);
        if (knownMaximum >= 0) {
            renderQuantities(holder, inventory, crate, state, knownMaximum);
            return;
        }
        inventory.setItem(22, button(Material.CLOCK, "<aqua>Checking available openings…</aqua>"));
        plugin.openings().maximumAvailableAmount(player, crate).whenComplete((maximum, error) -> onMain(() -> {
            if (!sameView(player, holder, revision)) return;
            if (error != null || maximum == null) {
                inventory.setItem(22, button(Material.BARRIER, "<yellow>Availability could not be checked</yellow>",
                        "<gray>No payment was consumed.</gray>"));
                return;
            }
            int safeMaximum = Math.max(0, Math.min(maximum,
                    Math.min(plugin.settings().maximumBulk(), crate.bulkMaximum())));
            SessionState updated = state.withMaximum(safeMaximum);
            views.put(holder.sessionId(), updated);
            renderQuantities(holder, inventory, crate, updated, safeMaximum);
        }));
    }

    private void renderQuantities(MenuHolder holder, Inventory inventory, Crate crate,
                                  SessionState state, int maximum) {
        for (int slot : QUANTITY_SLOTS) inventory.setItem(slot, null);
        if (maximum < 1) {
            inventory.setItem(22, button(Material.BARRIER, "<yellow>No payable openings</yellow>",
                    "<gray>Your current payment cannot cover a complete opening.</gray>"));
            inventory.setItem(PlayerCrateLayout.STATUS, button(Material.PAPER, "<gray>Available openings: 0</gray>"));
            return;
        }
        LinkedHashSet<Integer> choices = new LinkedHashSet<>();
        for (int fixed : List.of(1, 5, 10)) if (fixed <= maximum) choices.add(fixed);
        choices.add(maximum);
        int index = 0;
        for (int amount : choices) {
            int slot = QUANTITY_SLOTS.get(index++);
            long total = (long) amount * crate.keyCost();
            inventory.setItem(slot, button(Material.CHEST, "<green>Open " + amount + "</green>",
                    "<gray>Total payment:</gray> <white>" + total + " key" + (total == 1 ? "" : "s") + "</white>",
                    "<gray>Expected openings:</gray> <white>" + amount + "</white>",
                    "<aqua>Click to review before confirming.</aqua>"));
            holder.bind(slot, "choose-quantity", Integer.toString(amount));
        }
        inventory.setItem(PlayerCrateLayout.STATUS, button(Material.PAPER,
                "<white>Up to " + maximum + " opening" + (maximum == 1 ? "" : "s") + " currently available</white>"));
    }

    private void openMassConfirmation(Player player, Crate crate, SessionState origin, int amount) {
        PlayerMenuContext context = origin.context().massConfirm(amount);
        long revision = plugin.runtime().crateRevision(crate.id());
        MenuHolder holder = new MenuHolder(MenuHolder.Kind.PLAYER_MASS_CONFIRM, crate.id(), "", context.page(), false, revision);
        Inventory inventory = create(holder, Text.parse("<gold><bold>Confirm Open More</bold></gold>"));
        fill(inventory);
        SessionState state = new SessionState(context, revision, origin.payment(), origin.paymentReady(), origin.maximum());
        views.put(holder.sessionId(), state);
        inventory.setItem(22, button(Material.CHEST, "<white>Open " + amount + " times</white>",
                "<gray>Quantity:</gray> <white>" + amount + "</white>",
                "<gray>Total payment:</gray> <white>" + ((long) amount * crate.keyCost()) + " key"
                        + ((long) amount * crate.keyCost() == 1 ? "" : "s") + "</white>",
                "<yellow>This action commits the selected batch.</yellow>"));
        inventory.setItem(PlayerCrateLayout.BACK, button(Material.OAK_DOOR, "<white>Back</white>"));
        holder.bind(PlayerCrateLayout.BACK, "back-quantity");
        inventory.setItem(PlayerCrateLayout.CLOSE, button(Material.BARRIER, "<red>Cancel</red>"));
        holder.bind(PlayerCrateLayout.CLOSE, "close");
        open(player, inventory);
        updateMassConfirmation(player, holder, inventory, crate, state);
    }

    private void updateMassConfirmation(Player player, MenuHolder holder, Inventory inventory,
                                        Crate crate, SessionState state) {
        int required = Math.toIntExact(Math.min(Integer.MAX_VALUE, (long) state.context().amount() * crate.keyCost()));
        PaymentPresentation payment = PaymentPresentation.resolve(crate.paymentPolicy(), crate.mixedPayment(), required,
                state.payment(), state.context().paymentPreference(), player.hasPermission("plexoncrates.bypass.key"));
        SessionState updated = state.withContext(state.context().withPaymentPreference(payment.preference()));
        views.put(holder.sessionId(), updated);
        inventory.setItem(PlayerCrateLayout.PAYMENT, paymentButton(crate, payment));
        if (payment.choiceVisible()) holder.bind(PlayerCrateLayout.PAYMENT, "toggle-payment");
        if (payment.sufficient() && canOpenHere(player, crate)
                && state.context().amount() <= Math.max(0, state.maximum())) {
            inventory.setItem(PlayerCrateLayout.PRIMARY, button(Material.LIME_DYE, "<green>Confirm</green>",
                    "<gray>Open exactly " + state.context().amount() + " times.</gray>"));
            holder.bind(PlayerCrateLayout.PRIMARY, "confirm-mass");
        } else {
            inventory.setItem(PlayerCrateLayout.PRIMARY, button(Material.BARRIER, "<red>Cannot confirm</red>",
                    "<gray>The selected batch is no longer payable or available.</gray>"));
        }
    }

    private void openSelectiveConfirmation(Player player, Crate crate, SessionState origin, CrateReward selected) {
        long revision = plugin.runtime().crateRevision(crate.id());
        PlayerMenuContext context = origin.context().selectiveConfirm(selected.id());
        MenuHolder holder = new MenuHolder(MenuHolder.Kind.PLAYER_SELECTIVE_CONFIRM, crate.id(), selected.id(),
                context.page(), false, revision);
        Inventory inventory = create(holder, Text.parse("<gold><bold>Confirm Selective Opening</bold></gold>"));
        fill(inventory);
        SessionState state = new SessionState(context, revision, origin.payment(), origin.paymentReady(), -1);
        views.put(holder.sessionId(), state);
        OptionalOutcome outcome = outcome(player, crate, selected);
        if (outcome.reward() == null) {
            inventory.setItem(22, button(Material.BARRIER, "<yellow>This reward is no longer available</yellow>",
                    "<gray>Return to Preview to load the latest rewards.</gray>"));
        } else {
            ItemStack display = outcome.reward().displayCopy();
            List<Component> lore = new ArrayList<>(rewardSummary(outcome.reward()));
            lore.add(Component.empty());
            lore.add(Text.parse("<yellow>You are choosing this specific reward.</yellow>"));
            lore.add(Text.parse("<gray>Nothing is granted until you confirm.</gray>"));
            appendLore(display, lore);
            inventory.setItem(22, display);
        }
        inventory.setItem(PlayerCrateLayout.BACK, button(Material.OAK_DOOR, "<white>Back</white>"));
        holder.bind(PlayerCrateLayout.BACK, "back-preview");
        inventory.setItem(PlayerCrateLayout.CLOSE, button(Material.BARRIER, "<red>Cancel</red>"));
        holder.bind(PlayerCrateLayout.CLOSE, "close");
        open(player, inventory);
        if (state.paymentReady()) updateSelectiveConfirmation(player, holder, inventory, crate, selected, state);
        else loadPaymentSnapshot(player, crate).whenComplete((availability, error) -> onMain(() -> {
            if (!sameView(player, holder, revision)) return;
            if (error != null || availability == null) {
                inventory.setItem(PlayerCrateLayout.PAYMENT, button(Material.BARRIER, "<yellow>Payment status unavailable</yellow>"));
                return;
            }
            SessionState updated = state.withPayment(availability);
            views.put(holder.sessionId(), updated);
            updateSelectiveConfirmation(player, holder, inventory, crate, selected, updated);
        }));
    }

    private void updateSelectiveConfirmation(Player player, MenuHolder holder, Inventory inventory,
                                             Crate crate, CrateReward selected, SessionState state) {
        PaymentPresentation payment = PaymentPresentation.resolve(crate.paymentPolicy(), crate.mixedPayment(), crate.keyCost(),
                state.payment(), state.context().paymentPreference(), player.hasPermission("plexoncrates.bypass.key"));
        SessionState updated = state.withContext(state.context().withPaymentPreference(payment.preference()));
        views.put(holder.sessionId(), updated);
        inventory.setItem(PlayerCrateLayout.PAYMENT, paymentButton(crate, payment));
        if (payment.choiceVisible()) holder.bind(PlayerCrateLayout.PAYMENT, "toggle-payment");
        boolean eligible = outcome(player, crate, selected).reward() != null;
        if (payment.sufficient() && eligible && canOpenHere(player, crate)) {
            inventory.setItem(PlayerCrateLayout.PRIMARY, button(Material.LIME_DYE, "<green>Confirm</green>",
                    paymentLine(payment), "<gray>Receive the selected reward if it remains eligible.</gray>"));
            holder.bind(PlayerCrateLayout.PRIMARY, "confirm-selective");
        } else {
            inventory.setItem(PlayerCrateLayout.PRIMARY, button(Material.BARRIER, "<red>Cannot confirm</red>",
                    "<gray>Payment or reward eligibility changed.</gray>"));
        }
    }

    public void openPendingRewards(Player player, int requestedPage, int hallPage) {
        if (!plugin.settings().claimInboxEnabled()) {
            plugin.messages().send(player, "disabled");
            return;
        }
        int page = Math.max(1, requestedPage);
        MenuHolder holder = new MenuHolder(MenuHolder.Kind.PLAYER_PENDING_REWARDS, "", "", page - 1, false,
                plugin.runtime().snapshot().revision());
        Inventory inventory = create(holder, Text.parse("<gradient:#CAD5E5:#FFFFFF><bold>Pending Rewards</bold></gradient>"));
        fill(inventory);
        PlayerMenuContext context = PlayerMenuContext.pendingRewards(page, hallPage);
        views.put(holder.sessionId(), new SessionState(context, holder.revision(), List.of(), false, -1));
        inventory.setItem(22, button(Material.CLOCK, "<aqua>Loading pending rewards…</aqua>"));
        inventory.setItem(PlayerCrateLayout.BACK, button(Material.OAK_DOOR, "<white>Back</white>"));
        holder.bind(PlayerCrateLayout.BACK, "back-hall");
        inventory.setItem(PlayerCrateLayout.CLOSE, button(Material.BARRIER, "<red>Close</red>"));
        holder.bind(PlayerCrateLayout.CLOSE, "close");
        open(player, inventory);
        plugin.claims().list(player.getUniqueId(), page).whenComplete((entries, error) -> onMain(() -> {
            if (!sameView(player, holder, holder.revision())) return;
            if (error != null || entries == null) {
                inventory.setItem(22, button(Material.BARRIER, "<yellow>Pending Rewards are temporarily unavailable</yellow>",
                        "<gray>No reward was changed.</gray>"));
                return;
            }
            renderPendingRewards(holder, inventory, entries, page);
        }));
    }

    private void renderPendingRewards(MenuHolder holder, Inventory inventory,
                                      List<DatabaseService.ClaimEntry> entries, int page) {
        for (int slot : PlayerCrateLayout.contentSlots()) inventory.setItem(slot, null);
        if (entries.isEmpty()) {
            inventory.setItem(22, button(Material.CHEST, "<green>No pending rewards</green>",
                    "<gray>Rewards waiting for safe delivery will appear here.</gray>"));
        } else {
            for (int index = 0; index < Math.min(entries.size(), PlayerCrateLayout.contentSlots().size()); index++) {
                DatabaseService.ClaimEntry entry = entries.get(index);
                int slot = PlayerCrateLayout.contentSlots().get(index);
                inventory.setItem(slot, pendingRewardCard(entry));
                holder.bind(slot, "claim", entry.claimId().toString());
            }
        }
        if (page > 1) {
            inventory.setItem(PlayerCrateLayout.PREVIOUS, button(Material.ARROW, "<white>Previous</white>"));
            holder.bind(PlayerCrateLayout.PREVIOUS, "previous");
        }
        inventory.setItem(PlayerCrateLayout.CONTEXT, button(Material.COMPASS, "<aqua>Refresh</aqua>"));
        holder.bind(PlayerCrateLayout.CONTEXT, "refresh");
        inventory.setItem(PlayerCrateLayout.STATUS, button(Material.PAPER, "<white>Pending Rewards · Page " + page + "</white>",
                "<gray>Click a reward to claim it safely.</gray>"));
        if (entries.size() == CLAIM_PAGE_SIZE) {
            inventory.setItem(PlayerCrateLayout.NEXT, button(Material.ARROW, "<white>Next</white>"));
            holder.bind(PlayerCrateLayout.NEXT, "next");
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void click(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof MenuHolder holder)
                || !playerKind(holder.kind())) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)
                || event.getClickedInventory() != event.getView().getTopInventory()) return;
        if (plugin.guiSessions().validate(player, holder, plugin.draftSessions()) != GuiSessionService.Validation.CURRENT) return;
        SessionState state = views.get(holder.sessionId());
        if (state == null) return;
        MenuHolder.Action action = holder.action(event.getRawSlot());
        if (action == null) return;
        switch (holder.kind()) {
            case PLAYER_HALL -> hallClick(player, holder, state, action);
            case PLAYER_PREVIEW -> previewClick(player, holder, state, action);
            case PLAYER_QUANTITY -> quantityClick(player, holder, state, action);
            case PLAYER_MASS_CONFIRM -> massConfirmClick(player, holder, state, action);
            case PLAYER_SELECTIVE_CONFIRM -> selectiveConfirmClick(player, holder, state, action);
            case PLAYER_PENDING_REWARDS -> pendingClick(player, holder, state, action);
            default -> { }
        }
    }

    private void hallClick(Player player, MenuHolder holder, SessionState state, MenuHolder.Action action) {
        if (holder.revision() != plugin.runtime().snapshot().revision()) {
            stale(player, state.context());
            return;
        }
        switch (action.id()) {
            case "close" -> player.closeInventory();
            case "refresh" -> openHall(player, state.context().hallPage());
            case "previous" -> openHall(player, state.context().hallPage() - 1);
            case "next" -> openHall(player, state.context().hallPage() + 1);
            case "pending" -> openPendingRewards(player, 1, state.context().hallPage());
            case "preview" -> plugin.runtime().find(action.value())
                    .ifPresentOrElse(crate -> openPreview(player, crate, state.context().hallPage(), 0,
                                    KeyPaymentPlanner.Preference.PHYSICAL),
                            () -> stale(player, state.context()));
            default -> { }
        }
    }

    private void previewClick(Player player, MenuHolder holder, SessionState state, MenuHolder.Action action) {
        Crate crate = currentCrate(player, holder, state);
        if (crate == null) return;
        PlayerMenuContext context = state.context();
        switch (action.id()) {
            case "close" -> player.closeInventory();
            case "back" -> openHall(player, context.hallPage());
            case "refresh" -> openPreview(player, crate, context.hallPage(), context.page(), context.paymentPreference());
            case "previous" -> openPreview(player, crate, context.hallPage(), context.page() - 1, context.paymentPreference());
            case "next" -> openPreview(player, crate, context.hallPage(), context.page() + 1, context.paymentPreference());
            case "toggle-payment" -> togglePreviewPayment(player, holder, crate, state);
            case "open-one" -> submitRandom(player, holder, crate, state, 1);
            case "open-more" -> openQuantity(player, crate, state, -1);
            case "select" -> {
                CrateReward reward = crate.rewards().get(action.value());
                if (reward == null) stale(player, context);
                else openSelectiveConfirmation(player, crate, state, reward);
            }
            default -> { }
        }
    }

    private void quantityClick(Player player, MenuHolder holder, SessionState state, MenuHolder.Action action) {
        Crate crate = currentCrate(player, holder, state);
        if (crate == null) return;
        switch (action.id()) {
            case "close" -> player.closeInventory();
            case "back-preview" -> openPreview(player, crate, state.context().hallPage(), state.context().page(),
                    state.context().paymentPreference());
            case "choose-quantity" -> {
                try {
                    int amount = Integer.parseInt(action.value());
                    if (amount < 1 || amount > state.maximum()) stale(player, state.context());
                    else openMassConfirmation(player, crate, state, amount);
                } catch (NumberFormatException invalid) {
                    stale(player, state.context());
                }
            }
            default -> { }
        }
    }

    private void massConfirmClick(Player player, MenuHolder holder, SessionState state, MenuHolder.Action action) {
        Crate crate = currentCrate(player, holder, state);
        if (crate == null) return;
        switch (action.id()) {
            case "close" -> player.closeInventory();
            case "back-quantity" -> openQuantity(player, crate, state, state.maximum());
            case "toggle-payment" -> {
                SessionState toggled = state.withContext(state.context().withPaymentPreference(opposite(state.context().paymentPreference())));
                views.put(holder.sessionId(), toggled);
                updateMassConfirmation(player, holder, holder.getInventory(), crate, toggled);
            }
            case "confirm-mass" -> submitRandom(player, holder, crate, state, state.context().amount());
            default -> { }
        }
    }

    private void selectiveConfirmClick(Player player, MenuHolder holder, SessionState state, MenuHolder.Action action) {
        Crate crate = currentCrate(player, holder, state);
        if (crate == null) return;
        CrateReward reward = crate.rewards().get(state.context().rewardId());
        if (reward == null) {
            stale(player, state.context());
            return;
        }
        switch (action.id()) {
            case "close" -> player.closeInventory();
            case "back-preview" -> openPreview(player, crate, state.context().hallPage(), state.context().page(),
                    state.context().paymentPreference());
            case "toggle-payment" -> {
                SessionState toggled = state.withContext(state.context().withPaymentPreference(opposite(state.context().paymentPreference())));
                views.put(holder.sessionId(), toggled);
                updateSelectiveConfirmation(player, holder, holder.getInventory(), crate, reward, toggled);
            }
            case "confirm-selective" -> submitSelective(player, holder, crate, state, reward);
            default -> { }
        }
    }

    private void pendingClick(Player player, MenuHolder holder, SessionState state, MenuHolder.Action action) {
        switch (action.id()) {
            case "close" -> player.closeInventory();
            case "back-hall" -> openHall(player, state.context().hallPage());
            case "refresh" -> openPendingRewards(player, state.context().page(), state.context().hallPage());
            case "previous" -> openPendingRewards(player, Math.max(1, state.context().page() - 1), state.context().hallPage());
            case "next" -> openPendingRewards(player, state.context().page() + 1, state.context().hallPage());
            case "claim" -> {
                if (!submitted.add(holder.sessionId())) return;
                try {
                    UUID claimId = UUID.fromString(action.value());
                    player.closeInventory();
                    plugin.claims().claim(player, claimId);
                } catch (IllegalArgumentException invalid) {
                    submitted.remove(holder.sessionId());
                    openPendingRewards(player, state.context().page(), state.context().hallPage());
                }
            }
            default -> { }
        }
    }

    private void togglePreviewPayment(Player player, MenuHolder holder, Crate crate, SessionState state) {
        SessionState toggled = state.withContext(state.context().withPaymentPreference(opposite(state.context().paymentPreference())));
        views.put(holder.sessionId(), toggled);
        updatePreviewPayment(player, holder, holder.getInventory(), crate, toggled);
    }

    private void submitRandom(Player player, MenuHolder holder, Crate crate, SessionState state, int amount) {
        if (!submitted.add(holder.sessionId())) return;
        if (holder.revision() != plugin.runtime().crateRevision(crate.id())) {
            submitted.remove(holder.sessionId());
            stale(player, state.context());
            return;
        }
        player.closeInventory();
        boolean accepted = plugin.openings().open(player, crate, amount, OpenSource.GUI, null,
                state.context().paymentPreference());
        if (!accepted) submitted.remove(holder.sessionId());
    }

    private void submitSelective(Player player, MenuHolder holder, Crate crate,
                                 SessionState state, CrateReward reward) {
        if (!submitted.add(holder.sessionId())) return;
        if (holder.revision() != plugin.runtime().crateRevision(crate.id()) || outcome(player, crate, reward).reward() == null) {
            submitted.remove(holder.sessionId());
            stale(player, state.context());
            return;
        }
        player.closeInventory();
        boolean accepted = plugin.openings().openSelected(player, crate, reward.id(), 1, OpenSource.GUI, null,
                state.context().paymentPreference());
        if (!accepted) submitted.remove(holder.sessionId());
    }

    private Crate currentCrate(Player player, MenuHolder holder, SessionState state) {
        Crate crate = plugin.runtime().find(holder.crateId()).orElse(null);
        if (crate == null || holder.revision() != plugin.runtime().crateRevision(holder.crateId())) {
            stale(player, state.context());
            return null;
        }
        return crate;
    }

    private void stale(Player player, PlayerMenuContext context) {
        player.sendMessage(Text.parse("<yellow>This crate changed while the menu was open. The latest information has been loaded.</yellow>"));
        if (context.crateId().isBlank()) openHall(player, context.hallPage());
        else plugin.runtime().find(context.crateId()).ifPresentOrElse(
                crate -> openPreview(player, crate, context.hallPage(), context.page(), context.paymentPreference()),
                () -> openHall(player, context.hallPage()));
    }

    private CompletableFuture<List<KeyPaymentPlanner.Availability>> loadPaymentSnapshot(Player player, Crate crate) {
        List<String> keyIds = crate.acceptedKeyIds();
        List<Integer> physical = new ArrayList<>(keyIds.size());
        for (String keyId : keyIds) physical.add(plugin.keys().count(player, keyId));
        if (!plugin.settings().virtualKeyWalletEnabled() || crate.paymentPolicy() == KeyPaymentPolicy.PHYSICAL_ONLY) {
            List<KeyPaymentPlanner.Availability> result = new ArrayList<>();
            for (int index = 0; index < keyIds.size(); index++) {
                result.add(new KeyPaymentPlanner.Availability(keyIds.get(index), physical.get(index), 0, index));
            }
            return CompletableFuture.completedFuture(List.copyOf(result));
        }
        List<CompletableFuture<DatabaseService.VirtualKeyBalance>> futures = keyIds.stream()
                .map(keyId -> plugin.database().loadVirtualKeyBalance(player.getUniqueId(), keyId)).toList();
        return CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).thenApply(ignored -> {
            List<KeyPaymentPlanner.Availability> result = new ArrayList<>();
            for (int index = 0; index < keyIds.size(); index++) {
                result.add(new KeyPaymentPlanner.Availability(keyIds.get(index), physical.get(index),
                        futures.get(index).join().balance(), index));
            }
            return List.copyOf(result);
        });
    }

    private void loadPendingCount(Player player, MenuHolder holder, Inventory inventory) {
        if (!plugin.settings().claimInboxEnabled()) {
            inventory.setItem(PlayerCrateLayout.PRIMARY, null);
            return;
        }
        plugin.claims().pendingCount(player.getUniqueId()).whenComplete((count, error) -> onMain(() -> {
            if (!sameView(player, holder, holder.revision())) return;
            if (error != null || count == null) return;
            inventory.setItem(PlayerCrateLayout.PRIMARY, button(Material.CHEST, "<green>Pending Rewards</green>",
                    count == 0 ? "<gray>No rewards are waiting for delivery.</gray>"
                            : "<white>" + count + " reward" + (count == 1 ? "" : "s") + " ready to claim.</white>"));
        }));
    }

    private ItemStack crateCard(Player player, Crate crate) {
        ItemStack icon = crate.iconCopy();
        List<Component> lore = new ArrayList<>();
        if (!crate.description().isEmpty()) {
            lore.add(Component.empty());
            crate.description().stream().limit(2).forEach(lore::add);
        }
        lore.add(Component.empty());
        lore.add(Text.parse("<gray>Payment</gray> <dark_gray>»</dark_gray> ").append(keyRequirement(crate)));
        lore.add(Text.parse("<gray>Opening</gray> <dark_gray>»</dark_gray> <white>" + human(crate.openingMode().name()) + "</white>"));
        if (crate.openingMode() != OpeningMode.SELECTIVE && crate.bulkEnabled() && plugin.settings().massOpeningEnabled()) {
            lore.add(Text.parse("<gray>Open More</gray> <dark_gray>»</dark_gray> <white>Available</white>"));
        }
        lore.add(canOpenHere(player, crate) ? Text.parse("<green>Available here</green>") : availabilityReason(player, crate));
        lore.add(Component.empty());
        lore.add(Text.parse("<aqua>Click to Preview.</aqua>"));
        appendLore(icon, lore);
        return icon;
    }

    private ItemStack previewHeader(Player player, Crate crate) {
        ItemStack icon = crate.iconCopy();
        List<Component> lore = new ArrayList<>();
        lore.addAll(crate.description().stream().limit(3).toList());
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
        lore.add(canOpenHere(player, crate) ? Text.parse("<green>Available here</green>") : availabilityReason(player, crate));
        appendLore(icon, lore);
        return icon;
    }

    private ItemStack pendingRewardCard(DatabaseService.ClaimEntry entry) {
        ItemStack display;
        if (entry.itemBytes() == null) {
            display = new ItemStack(Material.TRIPWIRE_HOOK);
            Component key = entry.virtualKeyId() == null ? Text.parse("<white>Virtual key</white>") : keyName(entry.virtualKeyId());
            display.editMeta(meta -> meta.displayName(key.append(Text.parse(" <aqua>×" + entry.virtualKeyAmount() + "</aqua>"))));
        } else {
            try {
                ItemSnapshotCodec.Snapshot snapshot = new ItemSnapshotCodec.Snapshot(entry.itemBytes(), "unknown",
                        entry.itemAmount(), entry.itemBytes().length, entry.itemSha256().toLowerCase(Locale.ROOT),
                        false, false, entry.createdAt());
                display = itemSnapshots.restoreTemplate(snapshot);
                display.setAmount(Math.min(entry.itemAmount(), Math.max(1, display.getMaxStackSize())));
            } catch (RuntimeException invalid) {
                display = button(Material.BARRIER, "<yellow>Reward needs support</yellow>",
                        "<gray>This reward cannot be previewed safely.</gray>");
            }
        }
        List<Component> lore = new ArrayList<>();
        lore.add(Component.empty());
        if (entry.itemBytes() != null) lore.add(Text.parse("<gray>Quantity</gray> <dark_gray>»</dark_gray> <white>" + entry.itemAmount() + "</white>"));
        Crate source = plugin.runtime().find(entry.crateId()).orElse(null);
        if (source != null) lore.add(Text.parse("<gray>From</gray> <dark_gray>»</dark_gray> ").append(source.displayName()));
        lore.add(Text.parse("<green>Ready to claim</green>"));
        lore.add(Text.parse("<aqua>Click to Claim.</aqua>"));
        appendLore(display, lore);
        return display;
    }

    private List<Component> rewardSummary(CrateReward reward) {
        List<Component> lore = new ArrayList<>();
        int itemCount = reward.itemCopies().stream().mapToInt(ItemStack::getAmount).sum();
        if (itemCount > 0) lore.add(Text.parse("<gray>Items</gray> <dark_gray>»</dark_gray> <white>" + itemCount + "</white>"));
        if (reward.money() > 0) lore.add(Text.parse("<gray>Money</gray> <dark_gray>»</dark_gray> <white>" + format(reward.money()) + "</white>"));
        if (reward.experiencePoints() > 0) lore.add(Text.parse("<gray>Experience</gray> <dark_gray>»</dark_gray> <white>" + reward.experiencePoints() + " XP</white>"));
        if (reward.experienceLevels() > 0) lore.add(Text.parse("<gray>Levels</gray> <dark_gray>»</dark_gray> <white>" + reward.experienceLevels() + "</white>"));
        if (!reward.commands().isEmpty()) lore.add(Text.parse("<gray>Includes an additional server-delivered reward.</gray>"));
        return lore;
    }

    private OptionalOutcome outcome(Player player, Crate crate, CrateReward reward) {
        return plugin.openings().previewOutcome(player, crate, reward, System.currentTimeMillis(),
                        player.hasPermission("plexoncrates.bypass.limit"))
                .map(value -> new OptionalOutcome(value.actual()))
                .orElseGet(() -> new OptionalOutcome(null));
    }

    private Component keyRequirement(Crate crate) {
        if (crate.keyCost() <= 0 || crate.acceptedKeyIds().isEmpty()) return Text.parse("<green>Free</green>");
        Component names = Component.empty();
        for (int index = 0; index < crate.acceptedKeyIds().size(); index++) {
            if (index > 0) names = names.append(Text.parse("<dark_gray> / </dark_gray>"));
            names = names.append(keyName(crate.acceptedKeyIds().get(index)));
        }
        return Text.parse("<white>" + crate.keyCost() + "× </white>").append(names);
    }

    private Component keyName(String keyId) {
        return plugin.keys().template(keyId).map(item -> {
            Component name = item.getItemMeta().displayName();
            return name == null ? Text.parse("<white>crate key</white>") : name;
        }).orElseGet(() -> Text.parse("<white>crate key</white>"));
    }

    private boolean canOpenHere(Player player, Crate crate) {
        return plugin.settings().enabled() && plugin.settings().allows(player.getWorld()) && crate.allows(player.getWorld())
                && (crate.permission().isBlank() || player.hasPermission(crate.permission()))
                && playerCan(player, "plexoncrates.open");
    }

    private Component availabilityReason(Player player, Crate crate) {
        if (!plugin.settings().enabled()) return Text.parse("<yellow>Crates are temporarily unavailable.</yellow>");
        if (!plugin.settings().allows(player.getWorld()) || !crate.allows(player.getWorld())) {
            return Text.parse("<yellow>Not available in this world.</yellow>");
        }
        if (!crate.permission().isBlank() && !player.hasPermission(crate.permission())) {
            return Text.parse("<yellow>You do not currently have access to this crate.</yellow>");
        }
        return Text.parse("<yellow>Opening is not available.</yellow>");
    }

    private static boolean playerCan(Player player, String permission) {
        return player.hasPermission(permission) || player.hasPermission("plexoncrates.use");
    }

    private boolean sameView(Player player, MenuHolder holder, long expectedRevision) {
        if (!player.isOnline()
                || plugin.guiSessions().validate(player, holder, plugin.draftSessions()) != GuiSessionService.Validation.CURRENT) {
            return false;
        }
        if (holder.kind() == MenuHolder.Kind.PLAYER_HALL || holder.kind() == MenuHolder.Kind.PLAYER_PENDING_REWARDS) {
            return holder.revision() == expectedRevision;
        }
        return holder.revision() == expectedRevision
                && plugin.runtime().crateRevision(holder.crateId()) == expectedRevision;
    }

    private void open(Player player, Inventory inventory) {
        player.openInventory(inventory);
        if (inventory.getHolder() instanceof MenuHolder holder
                && player.getOpenInventory().getTopInventory() == inventory) {
            plugin.guiSessions().activate(player.getUniqueId(), holder);
        }
    }

    private static Inventory create(MenuHolder holder, Component title) {
        Inventory inventory = Bukkit.createInventory(holder, PlayerCrateLayout.SIZE, title);
        holder.attach(inventory);
        return inventory;
    }

    private static void fill(Inventory inventory) {
        ItemStack pane = button(Material.GRAY_STAINED_GLASS_PANE, Component.empty(), List.of());
        for (int slot = 0; slot < inventory.getSize(); slot++) inventory.setItem(slot, pane);
        for (int slot : PlayerCrateLayout.contentSlots()) inventory.setItem(slot, null);
        for (int slot = PlayerCrateLayout.PREVIOUS; slot <= PlayerCrateLayout.NEXT; slot++) inventory.setItem(slot, null);
    }

    private static ItemStack button(Material material, String name, String... lore) {
        List<Component> components = new ArrayList<>();
        for (String line : lore) components.add(Text.parse(line));
        return button(material, Text.parse(name), components);
    }

    private static ItemStack button(Material material, Component name, List<Component> lore) {
        ItemStack item = new ItemStack(material);
        item.editMeta(meta -> {
            meta.displayName(name.decoration(TextDecoration.ITALIC, false));
            meta.lore(lore.stream().map(line -> line.decoration(TextDecoration.ITALIC, false)).toList());
        });
        return item;
    }

    private static void appendLore(ItemStack item, List<Component> extra) {
        item.editMeta(meta -> {
            List<Component> lore = new ArrayList<>();
            if (meta.lore() != null) lore.addAll(meta.lore());
            lore.addAll(extra);
            meta.lore(lore.stream().map(line -> line.decoration(TextDecoration.ITALIC, false)).toList());
        });
    }

    private static String paymentLine(PaymentPresentation payment) {
        if (payment.required() <= 0) return "<gray>Payment:</gray> <white>" + payment.sourceLabel() + "</white>";
        return "<gray>Payment:</gray> <white>" + payment.required() + " key"
                + (payment.required() == 1 ? "" : "s") + " via " + payment.sourceLabel() + "</white>";
    }

    private static KeyPaymentPlanner.Preference opposite(KeyPaymentPlanner.Preference value) {
        return value == KeyPaymentPlanner.Preference.PHYSICAL
                ? KeyPaymentPlanner.Preference.VIRTUAL : KeyPaymentPlanner.Preference.PHYSICAL;
    }

    private static String human(String value) {
        String text = value.toLowerCase(Locale.ROOT).replace('_', ' ');
        return text.isEmpty() ? text : Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }

    private static String format(double value) {
        return String.format(Locale.ROOT, "%.2f", value).replaceAll("0+$", "").replaceAll("\\.$", "");
    }

    private static boolean playerKind(MenuHolder.Kind kind) {
        return switch (kind) {
            case PLAYER_HALL, PLAYER_PREVIEW, PLAYER_QUANTITY, PLAYER_MASS_CONFIRM,
                    PLAYER_SELECTIVE_CONFIRM, PLAYER_PENDING_REWARDS -> true;
            default -> false;
        };
    }

    private void onMain(Runnable action) {
        if (!plugin.isEnabled()) return;
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (plugin.isEnabled()) action.run();
        });
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void close(InventoryCloseEvent event) {
        if (!(event.getInventory().getHolder() instanceof MenuHolder holder) || !playerKind(holder.kind())) return;
        views.remove(holder.sessionId());
        submitted.remove(holder.sessionId());
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void quit(PlayerQuitEvent event) {
        views.entrySet().removeIf(entry -> {
            MenuHolder holder = holder(entry.getKey());
            return holder == null;
        });
        submitted.removeIf(id -> !views.containsKey(id));
    }

    private MenuHolder holder(UUID sessionId) {
        for (Player player : Bukkit.getOnlinePlayers()) {
            Inventory top = player.getOpenInventory().getTopInventory();
            if (top != null && top.getHolder() instanceof MenuHolder holder && holder.sessionId().equals(sessionId)) return holder;
        }
        return null;
    }

    private record OptionalOutcome(CrateReward reward) {}

    private record SessionState(
            PlayerMenuContext context,
            long crateRevision,
            List<KeyPaymentPlanner.Availability> payment,
            boolean paymentReady,
            int maximum) {
        private SessionState {
            context = Objects.requireNonNull(context, "context");
            payment = List.copyOf(payment);
        }

        private SessionState withPayment(List<KeyPaymentPlanner.Availability> value) {
            return new SessionState(context, crateRevision, value, true, maximum);
        }

        private SessionState withContext(PlayerMenuContext value) {
            return new SessionState(value, crateRevision, payment, paymentReady, maximum);
        }

        private SessionState withMaximum(int value) {
            return new SessionState(context, crateRevision, payment, paymentReady, value);
        }
    }
}
