package com.antondev.crates.service;

import com.antondev.crates.PlexonCrates;
import com.antondev.crates.api.event.CrateKeyConsumeEvent;
import com.antondev.crates.api.event.CrateMilestoneEarnEvent;
import com.antondev.crates.api.event.CrateOpenEvent;
import com.antondev.crates.api.event.CratePreOpenEvent;
import com.antondev.crates.api.event.CrateRewardSelectEvent;
import com.antondev.crates.api.event.PortableCrateUseEvent;
import com.antondev.crates.config.OverflowPolicy;
import com.antondev.crates.config.Text;
import com.antondev.crates.database.DatabaseService;
import com.antondev.crates.domain.crate.AnimationType;
import com.antondev.crates.domain.crate.CrateState;
import com.antondev.crates.domain.key.KeyPaymentPolicy;
import com.antondev.crates.domain.opening.OpenSource;
import com.antondev.crates.domain.opening.OpeningMode;
import com.antondev.crates.domain.opening.OpeningPlan;
import com.antondev.crates.domain.opening.RewardDelivery;
import com.antondev.crates.domain.reward.RewardPresentation;
import com.antondev.crates.integration.PlaceholderBridge;
import com.antondev.crates.integration.VaultEconomyBridge;
import com.antondev.crates.item.ItemSnapshotCodec;
import com.antondev.crates.model.BlockPosition;
import com.antondev.crates.model.Crate;
import com.antondev.crates.model.CrateMilestone;
import com.antondev.crates.model.CrateReward;
import java.time.Instant;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;
import java.util.logging.Level;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/** Validated, journaled opening coordinator. Selection and delivery precede cosmetics. */
public final class OpeningService {
    private final PlexonCrates plugin;
    private final OpeningLog log;
    private final VaultEconomyBridge economy;
    private final PlaceholderBridge placeholders;
    private final ItemSnapshotCodec itemSnapshots = new ItemSnapshotCodec();
    private final Set<UUID> locks = new HashSet<>();
    private final Map<UUID, PendingOpening> pending = new HashMap<>();
    private final Map<UUID, Map<String, Long>> cooldowns = new HashMap<>();
    /** Reserved portable requests waiting to be attached to the journal transaction. */
    private final Map<UUID, PortableContext> portableRequests = new HashMap<>();

    public OpeningService(PlexonCrates plugin, OpeningLog log) {
        this.plugin = plugin;
        this.log = log;
        this.economy = new VaultEconomyBridge(plugin);
        this.placeholders = new PlaceholderBridge(plugin);
    }

    /**
     * Starts one portable-crate opening after its issuance has been verified.
     * The issuance is reserved asynchronously before the ordinary opening
     * planner is entered; the item is consumed only after all normal
     * preconditions and the journal preparation have succeeded.
     */
    public boolean openPortable(Player player, Crate crate, DatabaseService.PortableIssue issue,
                                ItemStack expectedItem) {
        return openPortable(player, crate, issue, expectedItem, null);
    }

    /** Starts a portable selective opening only after the player confirms one exact reward. */
    public boolean openPortableSelected(Player player, Crate crate, DatabaseService.PortableIssue issue,
                                        ItemStack expectedItem, String rewardId) {
        return openPortable(player, crate, issue, expectedItem, normalizeRewardId(rewardId));
    }

    private boolean openPortable(Player player, Crate crate, DatabaseService.PortableIssue issue,
                                 ItemStack expectedItem, String selectedRewardId) {
        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException("Crate openings must begin on the primary server thread");
        }
        if (!plugin.settings().portableCratesEnabled()) {
            plugin.messages().send(player, "disabled");
            return false;
        }
        var token = expectedItem == null ? null : plugin.portables().decode(expectedItem).orElse(null);
        if (issue == null || token == null
                || !token.payload().issueId().equals(issue.issueId())
                || !token.payload().crateId().equals(issue.crateId())
                || !issue.crateId().equals(crate.id())
                || !token.payload().revisionPolicy().name().equals(issue.revisionPolicy())
                || token.payload().pinnedRevision() != issue.pinnedRevision()
                || !java.util.Objects.equals(token.payload().issuedTo(), issue.issuedTo())
                || issue.signatureVersion() != com.antondev.crates.service.PortableCrateCodec.VERSION) {
            plugin.messages().send(player, "invalid-crate");
            return false;
        }
        if (issue.issuedTo() != null && !issue.issuedTo().equals(player.getUniqueId())) {
            plugin.messages().send(player, "no-permission");
            return false;
        }
        if (!issue.state().equals("UNUSED")) {
            player.sendActionBar(Text.parse(
                    "<yellow>This portable crate has already been used or needs review.</yellow>"));
            return false;
        }
        if (issue.revisionPolicy().equals("PINNED_REVISION")
                && issue.pinnedRevision() != plugin.runtime().crateRevision(issue.crateId())) {
            plugin.messages().send(player, "opening-state-changed");
            return false;
        }
        PortableCrateUseEvent useEvent = new PortableCrateUseEvent(
                player, issue.issueId(), issue.crateId(), issue.revisionPolicy(), issue.pinnedRevision());
        Bukkit.getPluginManager().callEvent(useEvent);
        if (useEvent.isCancelled()) {
            plugin.messages().send(player, "opening-cancelled");
            return false;
        }
        String reservation = UUID.randomUUID().toString();
        plugin.database().reservePortableIssue(issue.issueId(), reservation).whenComplete((reserved, error) -> {
            if (!plugin.isEnabled()) {
                plugin.database().releasePortableIssue(issue.issueId(), reservation,
                        "Plugin disabled while reserving portable issuance");
                return;
            }
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (error != null || reserved == null || reserved.isEmpty()) {
                    if (error != null) plugin.getLogger().log(Level.WARNING,
                            "Portable issuance reservation failed for " + issue.issueId(), error);
                    plugin.messages().send(player, "opening-state-changed");
                    return;
                }
                if (!player.isOnline()) {
                    plugin.database().releasePortableIssue(issue.issueId(), reservation,
                            "Player disconnected before portable opening");
                    return;
                }
                PortableContext context = new PortableContext(issue.issueId(), reservation, expectedItem);
                portableRequests.put(player.getUniqueId(), context);
                boolean accepted = false;
                try {
                    accepted = openInternal(player, crate, 1, OpenSource.PORTABLE, null,
                            KeyPaymentPlanner.Preference.PHYSICAL, selectedRewardId);
                } catch (RuntimeException failure) {
                    plugin.getLogger().log(Level.WARNING,
                            "Portable opening could not enter the opening planner", failure);
                }
                if (!accepted) {
                    portableRequests.remove(player.getUniqueId(), context);
                    plugin.database().releasePortableIssue(issue.issueId(), reservation,
                            "Portable opening preconditions were rejected");
                }
            });
        });
        return true;
    }

    /** Compatibility entry point retained from 1.0. */
    public boolean open(Player player, Crate crate, int amount, boolean forced) {
        return open(player, crate, amount, forced ? OpenSource.ADMIN_FORCE : OpenSource.GUI, null);
    }

    public boolean open(Player player, Crate crate, int amount, OpenSource source, BlockPosition location) {
        return open(player, crate, amount, source, location, KeyPaymentPlanner.Preference.PHYSICAL);
    }

    public boolean open(Player player, Crate crate, int amount, OpenSource source, BlockPosition location,
                        KeyPaymentPlanner.Preference paymentPreference) {
        return openInternal(player, crate, amount, source, location, paymentPreference, null);
    }

    /**
     * Confirms a deliberate selective choice. No lock, payment reservation, or journal is created while the
     * player merely browses; this method is the first mutating boundary after confirmation.
     */
    public boolean openSelected(Player player, Crate crate, String rewardId, int amount, OpenSource source,
                                BlockPosition location, KeyPaymentPlanner.Preference paymentPreference) {
        return openInternal(player, crate, amount, source, location, paymentPreference,
                normalizeRewardId(rewardId));
    }

    public boolean openSelected(Player player, Crate crate, String rewardId, int amount, OpenSource source,
                                BlockPosition location) {
        return openSelected(player, crate, rewardId, amount, source, location,
                KeyPaymentPlanner.Preference.PHYSICAL);
    }

    private boolean openInternal(Player player, Crate crate, int amount, OpenSource source,
                                 BlockPosition location, KeyPaymentPlanner.Preference paymentPreference,
                                 String selectedRewardId) {
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("Crate openings must begin on the primary server thread");
        Crate published = plugin.runtime().find(crate.id()).orElse(null);
        if (published != null) crate = published;
        if (!locks.add(player.getUniqueId())) {
            plugin.messages().send(player, "already-opening");
            return false;
        }
        try {
            boolean forced = source == OpenSource.ADMIN_FORCE;
            boolean portable = source == OpenSource.PORTABLE;
            boolean selective = crate.openingMode() == OpeningMode.SELECTIVE && !forced;
            if (selective && !plugin.settings().selectiveOpeningEnabled()) return reject(player, "disabled");
            if (selective != (selectedRewardId != null)) return reject(player, "opening-state-changed");
            if (portable && !portableRequests.containsKey(player.getUniqueId())) {
                return reject(player, "opening-state-changed");
            }
            if (!forced && !player.hasPermission("plexoncrates.open") && !player.hasPermission("plexoncrates.use")) {
                return reject(player, "no-permission");
            }
            if (!plugin.settings().enabled() || crate.state() != CrateState.PUBLISHED) return reject(player, "disabled");
            if (!plugin.settings().allows(player.getWorld()) || !crate.allows(player.getWorld())) return reject(player, "invalid-world");
            if (!crate.permission().isBlank() && !player.hasPermission(crate.permission())) return reject(player, "no-permission");
            int maximum = Math.min(plugin.settings().maximumBulk(), crate.bulkMaximum());
            if (amount < 1 || amount > maximum || (amount > 1
                    && (!plugin.settings().massOpeningEnabled() || !crate.bulkEnabled()))) {
                plugin.messages().send(player, "invalid-amount", Text.value("maximum", maximum));
                return rejectSilently(player);
            }
            if (!forced && !player.hasPermission("plexoncrates.bypass.cooldown")) {
                long remaining = cooldownRemaining(player, crate);
                if (remaining > 0) {
                    plugin.messages().send(player, "cooldown", Text.value("seconds", Math.max(1, (remaining + 999) / 1000)));
                    return rejectSilently(player);
                }
            }

            boolean bypassingKey = portable || (!forced && crate.keyCost() > 0 && player.hasPermission("plexoncrates.bypass.key"));
            if (bypassingKey && amount > 1) amount = 1;
            boolean consumeKey = !forced && !bypassingKey && crate.keyCost() > 0;
            PaymentChoice free = new PaymentChoice(null,
                    portable ? "PORTABLE" : crate.keyId().isBlank() ? "FREE" : crate.keyId(),
                    0, 0, 0, crate.paymentPolicy());
            if (!consumeKey) return planAndPrepare(player, crate, amount, source, location, free,
                    selectedRewardId);

            int required = Math.multiplyExact(amount, crate.keyCost());
            boolean virtualCandidate = plugin.settings().virtualKeyWalletEnabled()
                    && crate.paymentPolicy() != KeyPaymentPolicy.PHYSICAL_ONLY;
            if (!virtualCandidate) {
                Optional<PaymentChoice> payment = choosePhysicalPayment(player, crate, required, paymentPreference);
                if (payment.isEmpty()) return insufficientPayment(player, crate);
                return planAndPrepare(player, crate, amount, source, location, payment.get(),
                        selectedRewardId);
            }

            Crate frozenCrate = crate;
            int frozenAmount = amount;
            choosePayment(player, frozenCrate, required, paymentPreference).whenComplete((payment, error) -> {
                if (!plugin.isEnabled()) {
                    locks.remove(player.getUniqueId());
                    return;
                }
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (!player.isOnline()) {
                        rejectSilently(player);
                        return;
                    }
                    if (error != null) {
                        plugin.getLogger().log(Level.WARNING, "Could not load virtual-key payment state", error);
                        reject(player, "database-error");
                        return;
                    }
                    if (payment == null || payment.isEmpty()) {
                        insufficientPayment(player, frozenCrate);
                        return;
                    }
                    try {
                        planAndPrepare(player, frozenCrate, frozenAmount, source, location, payment.get(),
                                selectedRewardId);
                    } catch (RuntimeException failure) {
                        locks.remove(player.getUniqueId());
                        plugin.getLogger().log(Level.SEVERE, "Could not create the opening plan", failure);
                        plugin.messages().send(player, "opening-failed", Text.value("transaction", "not-created"));
                    }
                });
            });
            return true;
        } catch (RuntimeException error) {
            locks.remove(player.getUniqueId());
            throw error;
        }
    }

    private boolean planAndPrepare(Player player, Crate crate, int requested, OpenSource source,
                                   BlockPosition location, PaymentChoice payment, String selectedRewardId) {
        boolean forced = source == OpenSource.ADMIN_FORCE;
        boolean portable = source == OpenSource.PORTABLE;
        boolean bypassLimits = forced || player.hasPermission("plexoncrates.bypass.limit");
        Predicate<CrateReward> eligibility = baseEligibility(player, selectedRewardId != null);
        RewardStateService.Plan rewardPlan = selectedRewardId == null
                ? plugin.rewardStates().plan(player.getUniqueId(), crate, requested, source,
                        eligibility, bypassLimits, System.currentTimeMillis())
                : plugin.rewardStates().planSelected(player.getUniqueId(), crate, selectedRewardId, requested,
                        source, eligibility, bypassLimits, System.currentTimeMillis());
        List<CrateReward> selected = rewardPlan.rewards();
        if (selected.size() != requested) return reject(player, "no-eligible-rewards");

        if (plugin.settings().overflowPolicy() == OverflowPolicy.REJECT) {
            List<ItemStack> candidateItems = selected.stream().flatMap(reward -> reward.itemCopies().stream()).toList();
            if (!InventoryPlanner.fits(player.getInventory().getStorageContents(), candidateItems)) {
                return reject(player, "inventory-full");
            }
        }

        List<RewardDelivery> deliveries = selected.stream().map(OpeningService::delivery).toList();
        MilestoneProgressService.Plan milestonePlan = plugin.milestoneProgress().plan(player.getUniqueId(), crate,
                requested, source, milestone -> milestone.reward().eligible(player),
                plugin.settings().milestonesEnabled());
        UUID transactionId = UUID.randomUUID();
        OpeningPlan plan = new OpeningPlan(transactionId, player.getUniqueId(), player.getName(), crate.id(),
                payment.keyId(), payment.total(), requested, source, location,
                plugin.runtime().crateRevision(crate.id()), deliveries, Instant.now());
        for (RewardDelivery reward : deliveries) {
            Bukkit.getPluginManager().callEvent(new CrateRewardSelectEvent(player, plan, reward));
        }
        CratePreOpenEvent event = new CratePreOpenEvent(player, plan);
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled()) {
            plugin.messages().send(player, "opening-cancelled");
            return rejectSilently(player);
        }

        PortableContext portableContext = portable ? portableRequests.remove(player.getUniqueId()) : null;
        if (portable && portableContext == null) return reject(player, "opening-state-changed");
        PendingOpening opening = new PendingOpening(plan, crate, selected, payment, milestonePlan, portableContext);
        pending.put(transactionId, opening);
        DatabaseService.JournalRecord journal = new DatabaseService.JournalRecord(transactionId,
                player.getUniqueId(), player.getName(), crate.id(), payment.keyId(), payment.total(),
                requested, source.name(), String.join(",", plan.rewardIds()), plan.createdAt());
        plugin.database().prepareJournal(journal, payment.detail())
                .whenComplete((ignored, error) -> {
                    if (!plugin.isEnabled()) return;
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        if (error != null) abortPrepared(transactionId, "Database journal preparation failed", true);
                        else commitPrepared(transactionId);
                    });
                });
        return true;
    }

    private Optional<PaymentChoice> choosePhysicalPayment(Player player, Crate crate, int required,
                                                          KeyPaymentPlanner.Preference preference) {
        var options = new ArrayList<PaymentOption>();
        int priority = 0;
        for (String keyId : crate.acceptedKeyIds()) {
            KeyService.KeyTransaction transaction = plugin.keys().begin(keyId).orElse(null);
            int physical = transaction == null ? 0 : plugin.keys().count(player, transaction);
            options.add(new PaymentOption(transaction,
                    new KeyPaymentPlanner.Availability(keyId, physical, 0, priority++), 0));
        }
        return paymentPlan(crate, required, preference, options);
    }

    private CompletableFuture<Optional<PaymentChoice>> choosePayment(
            Player player, Crate crate, int required, KeyPaymentPlanner.Preference preference) {
        var futures = new ArrayList<CompletableFuture<PaymentOption>>();
        int priority = 0;
        for (String keyId : crate.acceptedKeyIds()) {
            KeyService.KeyTransaction transaction = plugin.keys().begin(keyId).orElse(null);
            int physical = transaction == null ? 0 : plugin.keys().count(player, transaction);
            int sourcePriority = priority++;
            futures.add(plugin.database().loadVirtualKeyBalance(player.getUniqueId(), keyId)
                    .thenApply(balance -> new PaymentOption(transaction,
                            new KeyPaymentPlanner.Availability(keyId, physical, balance.balance(), sourcePriority),
                            balance.revision())));
        }
        CompletableFuture<?>[] all = futures.toArray(CompletableFuture[]::new);
        return CompletableFuture.allOf(all).thenApply(ignored -> paymentPlan(crate, required, preference,
                futures.stream().map(CompletableFuture::join).toList()));
    }

    private static Optional<PaymentChoice> paymentPlan(Crate crate, int required,
                                                       KeyPaymentPlanner.Preference preference,
                                                       List<PaymentOption> options) {
        Optional<KeyPaymentPlanner.Plan> planned = KeyPaymentPlanner.plan(crate.paymentPolicy(), preference,
                crate.mixedPayment(), required, options.stream().map(PaymentOption::availability).toList());
        if (planned.isEmpty()) return Optional.empty();
        KeyPaymentPlanner.Plan value = planned.get();
        PaymentOption option = options.stream().filter(candidate ->
                candidate.availability().keyId().equals(value.keyId())).findFirst().orElseThrow();
        if (value.usesPhysical() && option.transaction() == null) return Optional.empty();
        return Optional.of(new PaymentChoice(option.transaction(), value.keyId(), value.physical(),
                value.virtual(), option.virtualRevision(), value.policy()));
    }

    private boolean insufficientPayment(Player player, Crate crate) {
        plugin.messages().send(player, "insufficient-payment", Text.component("key", keyName(crate)));
        return rejectSilently(player);
    }

    public int bulkAmount(Player player, Crate crate) {
        if (player.hasPermission("plexoncrates.bypass.key")) return 1;
        int maximum = Math.min(plugin.settings().maximumBulk(), crate.bulkMaximum());
        if (!plugin.settings().massOpeningEnabled() || !crate.bulkEnabled() || crate.keyCost() <= 0) return 1;
        int available = 0;
        for (String keyId : crate.acceptedKeyIds()) available = Math.max(available, plugin.keys().count(player, keyId));
        return Math.max(1, Math.min(maximum, available / crate.keyCost()));
    }

    public boolean isOpening(UUID playerId) { return locks.contains(playerId); }
    public int pendingCount() { return pending.size(); }
    public boolean economyAvailable() { return economy.available(); }
    public String economyDiagnostic() { return economy.diagnostic(); }

    public boolean testDeliver(Player player, Crate crate, CrateReward reward) {
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("Reward tests must run on the primary server thread");
        if (!player.hasPermission("plexoncrates.admin.rewards")) return reject(player, "no-permission");
        if (!reward.hasDelivery() || (reward.money() > 0 && (!plugin.settings().vaultEnabled() || !economy.available()))) {
            return reject(player, "no-eligible-rewards");
        }
        if (plugin.settings().overflowPolicy() == OverflowPolicy.REJECT
                && !InventoryPlanner.fits(player.getInventory().getStorageContents(), reward.itemCopies())) {
            return reject(player, "inventory-full");
        }
        UUID transaction = UUID.randomUUID();
        OpeningPlan plan = new OpeningPlan(transaction, player.getUniqueId(), player.getName(), crate.id(), "TEST", 0,
                1, OpenSource.ADMIN_FORCE, null, plugin.runtime().crateRevision(crate.id()),
                List.of(delivery(reward)), Instant.now());
        DeliveryResult result = deliver(player, crate, List.of(reward), plan, false);
        plugin.database().audit(new DatabaseService.AuditRecord(player.getUniqueId(), player.getName(), "TEST_DELIVER",
                "REWARD", crate.id() + ":" + reward.id(), "Test-delivered without key, limits, pity, or statistics", Instant.now()));
        player.sendMessage(Text.parse("<green>Test-delivered</green> ").append(reward.displayName())
                .append(Text.parse("<green>; no key, statistics, limit, or pity state changed.</green>")));
        if (result.overflowCount() > 0) notifyOverflow(player);
        return true;
    }

    public void clear() {
        String reason = "Plugin disabled before inventory mutation";
        for (var entry : List.copyOf(pending.entrySet())) {
            PendingOpening opening = entry.getValue();
            if (opening.portable() != null) {
                plugin.database().releasePortableIssue(opening.portable().issueId(),
                        opening.portable().reservationToken(), reason);
            }
            plugin.database().updateJournal(entry.getKey(), "CANCELLED", reason);
        }
        for (PortableContext context : List.copyOf(portableRequests.values())) {
            plugin.database().releasePortableIssue(context.issueId(), context.reservationToken(), reason);
        }
        pending.clear();
        portableRequests.clear();
        locks.clear();
        cooldowns.clear();
    }

    private void commitPrepared(UUID transactionId) {
        PendingOpening opening = pending.get(transactionId);
        if (opening == null) return;
        OpeningPlan plan = opening.plan();
        Player player = Bukkit.getPlayer(plan.playerId());
        boolean deferred = false;
        try {
            if (player == null || !player.isOnline()) {
                abortPrepared(transactionId, "Player disconnected before key consumption", false);
                return;
            }
            Crate active = plan.runtimeRevision() > 0
                    ? plugin.runtime().find(plan.crateId()).orElse(null)
                    : plugin.crates().find(plan.crateId()).orElse(null);
            Crate current = opening.crate();
            if (active == null || active.state() != CrateState.PUBLISHED
                    || plan.runtimeRevision() > 0
                    && plugin.runtime().crateRevision(plan.crateId()) != plan.runtimeRevision()
                    || !plugin.settings().enabled() || !plugin.settings().allows(player.getWorld())
                    || !current.allows(player.getWorld())) {
                abortPrepared(transactionId, "Crate or world state changed before consumption", false);
                plugin.messages().send(player, "opening-state-changed");
                return;
            }
            if (!current.permission().isBlank() && !player.hasPermission(current.permission())) {
                abortPrepared(transactionId, "Permission changed before consumption", false);
                plugin.messages().send(player, "no-permission");
                return;
            }
            boolean bypassLimits = plan.source() == OpenSource.ADMIN_FORCE || player.hasPermission("plexoncrates.bypass.limit");
            Predicate<CrateReward> eligibility = baseEligibility(player,
                    current.openingMode() == OpeningMode.SELECTIVE && plan.source() != OpenSource.ADMIN_FORCE);
            if (!plugin.rewardStates().canApply(player.getUniqueId(), current, opening.selected(), plan.source(),
                    eligibility, bypassLimits, System.currentTimeMillis())) {
                abortPrepared(transactionId, "Reward limits or pity state changed before consumption", false);
                plugin.messages().send(player, "no-eligible-rewards");
                return;
            }
            if (!plugin.milestoneProgress().canApply(opening.milestones())) {
                abortPrepared(transactionId, "Milestone progress changed before consumption", false);
                plugin.messages().send(player, "opening-state-changed");
                return;
            }
            PaymentChoice payment = opening.payment();
            if (payment.physicalAmount() > 0
                    && (payment.physicalTransaction() == null
                    || plugin.keys().count(player, payment.physicalTransaction()) < payment.physicalAmount())) {
                abortPrepared(transactionId, "Exact key count changed before consumption", false);
                plugin.messages().send(player, "insufficient-payment", Text.component("key", keyName(current)));
                return;
            }
            List<ItemStack> items = plan.deliveries().stream().flatMap(delivery -> delivery.items().stream()).toList();
            if (plugin.settings().overflowPolicy() == OverflowPolicy.REJECT
                    && !InventoryPlanner.fits(player.getInventory().getStorageContents(), items)) {
                abortPrepared(transactionId, "Inventory capacity changed before consumption", false);
                plugin.messages().send(player, "inventory-full");
                return;
            }
            if (opening.portable() != null) {
                deferred = true;
                beginPortableCommit(transactionId, opening, player, current, eligibility, bypassLimits);
                return;
            }
            if (payment.virtualAmount() > 0) {
                deferred = true;
                beginVirtualCommit(transactionId, opening, player, current, eligibility, bypassLimits);
                return;
            }
            if (!consumePhysical(player, opening)) {
                abortPrepared(transactionId, "Exact key revalidation failed", false);
                plugin.messages().send(player, "insufficient-payment", Text.component("key", keyName(current)));
                return;
            }
            finishConsumed(transactionId, opening, player, current, eligibility, bypassLimits);
        } catch (RuntimeException error) {
            plugin.getLogger().log(Level.SEVERE, "Opening " + transactionId + " failed during the main-thread commit", error);
            plugin.database().updateJournal(transactionId, "FAILED", concise(error));
            if (player != null) plugin.messages().send(player, "opening-failed", Text.value("transaction", transactionId));
        } finally {
            if (!deferred) {
                pending.remove(transactionId);
                locks.remove(plan.playerId());
            }
        }
    }

    private void beginVirtualCommit(UUID transactionId, PendingOpening opening, Player player, Crate current,
                                    Predicate<CrateReward> eligibility, boolean bypassLimits) {
        PaymentChoice payment = opening.payment();
        String debitToken = "opening-payment:" + transactionId;
        plugin.database().debitVirtualKeys(player.getUniqueId(), payment.keyId(), payment.virtualAmount(),
                debitToken, "OPENING", transactionId.toString(), null, payment.virtualRevision())
                .whenComplete((mutation, error) -> {
                    if (!plugin.isEnabled()) {
                        if (error == null && mutation != null && mutation.applied()) {
                            refundVirtualAfterStop(opening, "Plugin disabled before physical-key consumption");
                        }
                        return;
                    }
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        if (error != null || mutation == null || !mutation.applied()) {
                            if (error != null) plugin.getLogger().log(Level.WARNING,
                                    "Virtual-key debit failed for opening " + transactionId, error);
                            abortPrepared(transactionId, "Virtual-key balance changed before consumption", error != null);
                            plugin.messages().send(player, "insufficient-payment",
                                    Text.component("key", keyName(current)));
                            return;
                        }
                        try {
                            if (!player.isOnline()) {
                                refundVirtualAndAbort(opening, "Player disconnected before physical-key consumption");
                                return;
                            }
                            PaymentChoice activePayment = opening.payment();
                            if (activePayment.physicalAmount() > 0
                                    && (activePayment.physicalTransaction() == null
                                    || plugin.keys().count(player, activePayment.physicalTransaction())
                                    < activePayment.physicalAmount())) {
                                refundVirtualAndAbort(opening, "Exact physical key count changed after virtual debit");
                                return;
                            }
                            List<ItemStack> items = opening.plan().deliveries().stream()
                                    .flatMap(delivery -> delivery.items().stream()).toList();
                            if (plugin.settings().overflowPolicy() == OverflowPolicy.REJECT
                                    && !InventoryPlanner.fits(player.getInventory().getStorageContents(), items)) {
                                refundVirtualAndAbort(opening, "Inventory capacity changed after virtual debit");
                                return;
                            }
                            if (!plugin.rewardStates().canApply(player.getUniqueId(), current, opening.selected(),
                                    opening.plan().source(), eligibility, bypassLimits, System.currentTimeMillis())) {
                                refundVirtualAndAbort(opening, "Reward state changed after virtual debit");
                                return;
                            }
                            if (!consumePhysical(player, opening)) {
                                refundVirtualAndAbort(opening, "Physical-key consumption failed after virtual debit");
                                return;
                            }
                            finishConsumed(transactionId, opening, player, current, eligibility, bypassLimits);
                            pending.remove(transactionId);
                            locks.remove(player.getUniqueId());
                        } catch (RuntimeException failure) {
                            plugin.getLogger().log(Level.SEVERE,
                                    "Opening " + transactionId + " failed after virtual-key consumption", failure);
                            plugin.database().updateJournal(transactionId, "FAILED", concise(failure));
                            plugin.messages().send(player, "opening-failed", Text.value("transaction", transactionId));
                            pending.remove(transactionId);
                            locks.remove(player.getUniqueId());
                        }
                    });
                });
    }

    private boolean consumePhysical(Player player, PendingOpening opening) {
        PaymentChoice payment = opening.payment();
        if (payment.physicalAmount() == 0) return true;
        return payment.physicalTransaction() != null
                && plugin.keys().consume(player, payment.physicalTransaction(), payment.physicalAmount());
    }

    private void finishConsumed(UUID transactionId, PendingOpening opening, Player player, Crate current,
                                Predicate<CrateReward> eligibility, boolean bypassLimits) {
        OpeningPlan plan = opening.plan();
        if (opening.payment().physicalAmount() > 0) {
            Bukkit.getPluginManager().callEvent(new CrateKeyConsumeEvent(player, plan));
        }
        plugin.database().updateJournal(transactionId, "CONSUMED", opening.payment().detail());
        DeliveryResult delivered = deliver(player, current, opening.selected(), plan, true);
        DatabaseService.RewardStateCommit rewardState = plugin.rewardStates().apply(player.getUniqueId(), current,
                opening.selected(), plan.source(), eligibility, bypassLimits, System.currentTimeMillis());
        DatabaseService.MilestoneProgressCommit milestoneState = plugin.milestoneProgress().apply(opening.milestones());
        List<DatabaseService.MilestoneItemClaim> milestoneClaims = freezeMilestoneClaims(opening);
        if (!milestoneClaims.isEmpty()) milestoneState = milestoneState.withClaims(milestoneClaims);
        plugin.statistics().record(player.getUniqueId(), current.id(), plan.openingCount());
        setCooldown(player, current);
        DatabaseService.OpeningRecord record = new DatabaseService.OpeningRecord(transactionId,
                player.getUniqueId(), player.getName(), current.id(), plan.keyId(), plan.keyAmount(),
                plan.openingCount(), plan.source().name(), String.join(",", plan.rewardIds()),
                locationText(plan.location()), delivered.overflowCount(), Instant.now());
        DatabaseService.MilestoneProgressCommit frozenMilestones = milestoneState;
        plugin.database().completeOpening(record, rewardState, frozenMilestones).whenComplete((ignored, error) -> {
            if (!plugin.isEnabled()) return;
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (error != null) {
                    plugin.getLogger().log(Level.SEVERE,
                            "Atomic opening finalization failed for " + transactionId, error);
                    return;
                }
                completeMilestones(player, opening, milestoneClaims);
            });
        });
        Bukkit.getPluginManager().callEvent(new CrateOpenEvent(player, plan, delivered.overflowCount()));
        if (delivered.overflowCount() > 0) notifyOverflow(player);
        showResult(player, current, opening.selected(), plan);
    }

    private List<DatabaseService.MilestoneItemClaim> freezeMilestoneClaims(PendingOpening opening) {
        if (opening.milestones().newlyEarned().isEmpty()) return List.of();
        var claims = new ArrayList<DatabaseService.MilestoneItemClaim>();
        int index = 0;
        for (MilestoneProgressService.Earning earning : opening.milestones().newlyEarned()) {
            CrateMilestone milestone = earning.milestone();
            for (ItemStack item : milestone.reward().itemCopies()) {
                ItemSnapshotCodec.Snapshot snapshot = itemSnapshots.capture(item);
                String token = "milestone:" + opening.plan().transactionId() + ":"
                        + earning.earned().key() + ":" + index++;
                claims.add(new DatabaseService.MilestoneItemClaim(UUID.randomUUID(), token,
                        earning.earned().key(), milestone.reward().id(), snapshot.bytes(),
                        snapshot.capturedAmount(), snapshot.sha256(),
                        milestone.deliveryPolicy() == MilestoneService.DeliveryPolicy.AUTO_DELIVER,
                        opening.plan().createdAt()));
            }
        }
        return List.copyOf(claims);
    }

    private void completeMilestones(Player player, PendingOpening opening,
                                    List<DatabaseService.MilestoneItemClaim> claims) {
        if (opening.milestones().newlyEarned().isEmpty()) return;
        if (player.isOnline()) {
            for (MilestoneProgressService.Earning earning : opening.milestones().newlyEarned()) {
                player.sendMessage(Text.parse("<gold>Milestone earned:</gold> <white><milestone></white>",
                        Text.component("milestone", earning.milestone().displayName())));
                Bukkit.getPluginManager().callEvent(new CrateMilestoneEarnEvent(player, opening.plan(),
                        earning.milestone(), earning.earned()));
            }
            boolean manual = claims.stream().anyMatch(claim -> !claim.autoDeliver());
            if (manual) plugin.messages().send(player, "milestone-claim-pending");
            claims.stream().filter(DatabaseService.MilestoneItemClaim::autoDeliver)
                    .forEach(claim -> plugin.claims().claim(player, claim.claimId()));
        }
    }

    private void refundVirtualAndAbort(PendingOpening opening, String reason) {
        PaymentChoice payment = opening.payment();
        UUID transactionId = opening.plan().transactionId();
        plugin.database().creditVirtualKeys(opening.plan().playerId(), payment.keyId(), payment.virtualAmount(),
                "opening-payment-refund:" + transactionId, "OPENING_REFUND", transactionId.toString(), null)
                .whenComplete((refund, error) -> {
                    if (!plugin.isEnabled()) return;
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        if (error != null || refund == null || !refund.applied()) {
                            plugin.database().updateJournal(transactionId, "FAILED",
                                    reason + "; automatic virtual-key refund failed; manual review required");
                            plugin.getLogger().log(Level.SEVERE,
                                    "Virtual-key refund failed for opening " + transactionId, error);
                            pending.remove(transactionId);
                            locks.remove(opening.plan().playerId());
                            return;
                        }
                        abortPrepared(transactionId, reason + "; virtual payment refunded", false);
                        Player current = Bukkit.getPlayer(opening.plan().playerId());
                        if (current != null) plugin.messages().send(current, "opening-state-changed");
                    });
                });
    }

    private void refundVirtualAfterStop(PendingOpening opening, String reason) {
        PaymentChoice payment = opening.payment();
        UUID transactionId = opening.plan().transactionId();
        plugin.database().creditVirtualKeys(opening.plan().playerId(), payment.keyId(), payment.virtualAmount(),
                "opening-payment-refund:" + transactionId, "OPENING_REFUND", transactionId.toString(), null)
                .whenComplete((refund, error) -> plugin.database().updateJournal(transactionId,
                        error == null && refund != null && refund.applied() ? "CANCELLED" : "FAILED",
                        error == null ? reason + "; virtual payment refunded"
                                : reason + "; refund failed; manual review required"));
    }

    private void beginPortableCommit(UUID transactionId, PendingOpening opening, Player player, Crate current,
                                     Predicate<CrateReward> eligibility, boolean bypassLimits) {
        PortableContext context = opening.portable();
        if (!player.isOnline() || !player.getInventory().getItemInMainHand().isSimilar(context.expectedItem())) {
            abortPrepared(transactionId, "Portable item changed before consumption", false);
            plugin.messages().send(player, "opening-state-changed");
            return;
        }
        plugin.database().consumePortableIssue(context.issueId(), context.reservationToken()).whenComplete((consumed, error) -> {
            if (!plugin.isEnabled()) return;
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (error != null || consumed == null || consumed.isEmpty()) {
                    abortPrepared(transactionId, "Portable issuance could not be consumed", error != null);
                    plugin.messages().send(player, "opening-state-changed");
                    return;
                }
                if (!PortableCrateService.consumeOne(player, context.expectedItem())) {
                    plugin.database().updateJournal(transactionId, "FAILED",
                            "Portable issuance was consumed but the source item changed before removal");
                    plugin.getLogger().severe("Portable issuance " + context.issueId()
                            + " was consumed but its item could not be removed; manual review is required.");
                    pending.remove(transactionId);
                    locks.remove(player.getUniqueId());
                    return;
                }
                try {
                    finishConsumed(transactionId, opening, player, current, eligibility, bypassLimits);
                } catch (RuntimeException failure) {
                    plugin.getLogger().log(Level.SEVERE,
                            "Portable opening " + transactionId + " failed after consumption", failure);
                    plugin.database().updateJournal(transactionId, "FAILED", concise(failure));
                    plugin.messages().send(player, "opening-failed", Text.value("transaction", transactionId));
                } finally {
                    pending.remove(transactionId);
                    locks.remove(player.getUniqueId());
                }
            });
        });
    }

    private DeliveryResult deliver(Player player, Crate crate, List<CrateReward> selected, OpeningPlan plan,
                                   boolean recordOpeningLog) {
        int overflow = 0;
        int itemIndex = 0;
        OverflowPolicy overflowPolicy = plugin.settings().overflowPolicy();
        for (CrateReward reward : selected) {
            for (ItemStack item : reward.itemCopies()) {
                if (overflowPolicy == OverflowPolicy.CLAIM_ALL) {
                    overflow += item.getAmount();
                    queueClaim(player, crate, reward, plan, item, itemIndex++);
                    continue;
                }
                var leftovers = player.getInventory().addItem(item).values();
                for (ItemStack leftover : leftovers) {
                    overflow += leftover.getAmount();
                    switch (overflowPolicy) {
                        case DROP -> player.getWorld().dropItemNaturally(player.getLocation(), leftover);
                        case CLAIM, CLAIM_ALL, REJECT -> queueClaim(player, crate, reward, plan, leftover, itemIndex++);
                    }
                }
            }
            if (reward.experiencePoints() > 0) player.giveExp(reward.experiencePoints(), true);
            if (reward.experienceLevels() > 0) player.giveExpLevels(reward.experienceLevels());
            if (reward.money() > 0 && !economy.deposit(player, reward.money())) {
                plugin.getLogger().severe("Vault rejected money reward " + reward.id() + " in transaction " + plan.transactionId());
                plugin.database().updateJournal(plan.transactionId(), "DELIVERY_WARNING", "Vault rejected money reward " + reward.id());
            }
            for (String command : reward.commands()) dispatchRewardCommand(player, crate, reward, command, plan.location());
            if (!reward.personalMessage().isBlank()) player.sendMessage(plugin.messages().parseRaw(reward.personalMessage(), tags(player, crate, reward)));
            present(player, crate, reward);
            if (recordOpeningLog) log.record(player, crate, reward);
        }
        return new DeliveryResult(overflow);
    }

    private void notifyOverflow(Player player) {
        if (plugin.settings().overflowPolicy() == OverflowPolicy.CLAIM
                || plugin.settings().overflowPolicy() == OverflowPolicy.CLAIM_ALL
                || plugin.settings().overflowPolicy() == OverflowPolicy.REJECT) {
            plugin.messages().send(player, "reward-claim-pending");
        } else {
            plugin.messages().send(player, "reward-overflow");
        }
    }

    private void queueClaim(Player player, Crate crate, CrateReward reward, OpeningPlan plan,
                            ItemStack item, int itemIndex) {
        String token = "opening:" + plan.transactionId() + ":" + reward.id() + ":" + itemIndex;
        try {
            plugin.claims().enqueueItem(player.getUniqueId(), "OPENING", plan.transactionId().toString(),
                    crate.id(), reward.id(), token, item).whenComplete((claim, error) -> {
                if (error == null) return;
                plugin.getLogger().log(Level.SEVERE,
                        "Could not persist exact overflow claim " + token + "; dropping the unchanged stack", error);
                if (plugin.isEnabled()) Bukkit.getScheduler().runTask(plugin,
                        () -> player.getWorld().dropItemNaturally(player.getLocation(), item.clone()));
            });
        } catch (RuntimeException error) {
            plugin.getLogger().log(Level.SEVERE,
                    "Could not queue exact overflow claim " + token + "; dropping the unchanged stack", error);
            player.getWorld().dropItemNaturally(player.getLocation(), item.clone());
        }
    }

    private void dispatchRewardCommand(Player player, Crate crate, CrateReward reward, String command, BlockPosition location) {
        String rendered = command
                .replace("%player%", player.getName())
                .replace("%display_name%", PlainTextComponentSerializer.plainText().serialize(player.displayName()))
                .replace("%uuid%", player.getUniqueId().toString())
                .replace("%crate%", crate.id())
                .replace("%crate_id%", crate.id())
                .replace("%reward%", reward.id())
                .replace("%reward_id%", reward.id())
                .replace("%world%", location == null ? player.getWorld().getName() : location.worldName())
                .replace("%x%", Integer.toString(location == null ? player.getLocation().getBlockX() : location.x()))
                .replace("%y%", Integer.toString(location == null ? player.getLocation().getBlockY() : location.y()))
                .replace("%z%", Integer.toString(location == null ? player.getLocation().getBlockZ() : location.z()));
        if (plugin.settings().placeholderApiEnabled()) rendered = placeholders.expand(player, rendered);
        try {
            if (!Bukkit.dispatchCommand(Bukkit.getConsoleSender(), rendered)) {
                plugin.getLogger().warning("Reward command returned false: " + rendered);
            }
        } catch (RuntimeException error) {
            plugin.getLogger().log(Level.SEVERE, "Reward command failed in a delivered transaction: " + rendered, error);
        }
    }

    private void showResult(Player player, Crate crate, List<CrateReward> selected, OpeningPlan plan) {
        if (plan.openingCount() == 1) {
            CrateReward reward = selected.getFirst();
            if (!plugin.settings().animationEnabled() || crate.animation() == AnimationType.INSTANT) {
                announceSingle(player, crate, reward);
                return;
            }
            try {
                switch (crate.animation()) {
                    case ROULETTE -> plugin.menus().animate(player, crate, reward, () -> announceSingle(player, crate, reward));
                    case REVEAL -> plugin.menus().reveal(player, crate, reward, () -> announceSingle(player, crate, reward));
                    case SUMMARY -> { announceSingle(player, crate, reward); plugin.menus().openSummary(player, crate, selected); }
                    case INSTANT -> announceSingle(player, crate, reward);
                }
            } catch (RuntimeException error) {
                plugin.getLogger().log(Level.WARNING, "Could not show the crate animation; delivery is already complete.", error);
                announceSingle(player, crate, reward);
            }
        } else {
            plugin.messages().send(player, "bulk-opened", Text.value("amount", plan.openingCount()),
                    Text.component("crate", crate.displayName()), Text.value("rewards", selected.size()));
            announceBroadcasts(player, crate, selected);
            if (crate.animation() == AnimationType.SUMMARY
                    || plan.openingCount() > plugin.settings().bulkSummaryThreshold()) {
                plugin.menus().openSummary(player, crate, selected);
            }
        }
    }

    private void announceSingle(Player player, Crate crate, CrateReward reward) {
        plugin.messages().send(player, "opened", Text.component("crate", crate.displayName()),
                Text.component("reward", reward.displayName()));
        announceBroadcasts(player, crate, List.of(reward));
        player.playSound(player.getLocation(), plugin.settings().finishSound(),
                plugin.settings().soundVolume(), plugin.settings().soundPitch());
    }

    private void announceBroadcasts(Player player, Crate crate, List<CrateReward> rewards) {
        plugin.messages().broadcastRaw(crate.broadcast(), tags(player, crate, null));
        for (CrateReward reward : rewards) plugin.messages().broadcastRaw(reward.broadcast(), tags(player, crate, reward));
    }

    private TagResolver[] tags(Player player, Crate crate, CrateReward reward) {
        return new TagResolver[]{Text.value("player", player.getName()), Text.value("uuid", player.getUniqueId()),
                Text.component("crate", crate.displayName()), Text.value("crate_id", crate.id()),
                Text.component("reward", reward == null ? Component.empty() : reward.displayName()),
                Text.value("reward_id", reward == null ? "" : reward.id())};
    }

    private void present(Player player, Crate crate, CrateReward reward) {
        RewardPresentation presentation = reward.presentation();
        try {
            TagResolver[] tags = tags(player, crate, reward);
            if (!presentation.title().isBlank() || !presentation.subtitle().isBlank()) {
                player.showTitle(Title.title(plugin.messages().parseRaw(presentation.title(), tags),
                        plugin.messages().parseRaw(presentation.subtitle(), tags),
                        Title.Times.times(Duration.ofMillis(250), Duration.ofSeconds(2), Duration.ofMillis(500))));
            }
            if (!presentation.sound().isBlank()) {
                player.playSound(player.getLocation(), presentation.sound(), presentation.soundVolume(), presentation.soundPitch());
            }
            if (presentation.firework()) {
                player.getWorld().spawnParticle(org.bukkit.Particle.FIREWORK, player.getLocation().add(0, 1, 0),
                        24, 0.45, 0.65, 0.45, 0.02);
            }
        } catch (RuntimeException error) {
            plugin.getLogger().log(Level.WARNING, "Could not show cosmetic presentation for reward " + reward.id(), error);
        }
    }

    private Predicate<CrateReward> baseEligibility(Player player, boolean ignoreChance) {
        return reward -> (ignoreChance ? selectivePermissionEligible(player, reward) : reward.eligible(player))
                && reward.hasDelivery()
                && (reward.money() <= 0 || (plugin.settings().vaultEnabled() && economy.available()));
    }

    private static boolean selectivePermissionEligible(Player player, CrateReward reward) {
        return reward.enabled()
                && (reward.requiredPermission().isBlank() || player.hasPermission(reward.requiredPermission()))
                && (reward.blockedPermission().isBlank() || !player.hasPermission(reward.blockedPermission()));
    }

    private void abortPrepared(UUID transactionId, String reason, boolean databaseError) {
        PendingOpening opening = pending.remove(transactionId);
        if (opening == null) return;
        locks.remove(opening.plan().playerId());
        if (opening.portable() != null) {
            plugin.database().releasePortableIssue(opening.portable().issueId(),
                    opening.portable().reservationToken(), reason);
        }
        plugin.database().updateJournal(transactionId, "CANCELLED", reason);
        Player player = Bukkit.getPlayer(opening.plan().playerId());
        if (player != null && databaseError) plugin.messages().send(player, "database-error");
    }

    private boolean reject(Player player, String message) {
        plugin.messages().send(player, message);
        return rejectSilently(player);
    }

    private boolean rejectSilently(Player player) {
        locks.remove(player.getUniqueId());
        return false;
    }

    private Component keyName(Crate crate) {
        for (String keyId : crate.acceptedKeyIds()) {
            Optional<Component> name = plugin.keys().definition(keyId).map(definition -> definition.displayName());
            if (name.isPresent()) return name.get();
        }
        return Text.parse("<white>" + (crate.keyId().isBlank() ? "crate" : crate.keyId()) + " key</white>");
    }

    private long cooldownRemaining(Player player, Crate crate) {
        long available = cooldowns.getOrDefault(player.getUniqueId(), Map.of()).getOrDefault(crate.id(), 0L);
        return Math.max(0, available - System.currentTimeMillis());
    }

    private void setCooldown(Player player, Crate crate) {
        if (crate.cooldownSeconds() <= 0) return;
        cooldowns.computeIfAbsent(player.getUniqueId(), ignored -> new HashMap<>())
                .put(crate.id(), System.currentTimeMillis() + crate.cooldownSeconds() * 1000L);
    }

    private static RewardDelivery delivery(CrateReward reward) {
        return new RewardDelivery(reward.id(), reward.displayName(), reward.itemCopies(), reward.commands(),
                reward.experiencePoints(), reward.experienceLevels(), reward.money(),
                reward.presentation(), reward.personalMessage(), reward.broadcast());
    }

    private static String normalizeRewardId(String rewardId) {
        if (rewardId == null) return null;
        String normalized = rewardId.trim().toLowerCase(Locale.ROOT);
        return normalized.isEmpty() ? null : normalized;
    }

    private static String locationText(BlockPosition location) {
        return location == null ? "" : location.worldName() + ":" + location.x() + ":" + location.y() + ":" + location.z();
    }

    private static String concise(Throwable error) {
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }

    private record PendingOpening(OpeningPlan plan, Crate crate, List<CrateReward> selected,
                                  PaymentChoice payment, MilestoneProgressService.Plan milestones,
                                  PortableContext portable) {
        private PendingOpening {
            selected = List.copyOf(selected);
            payment = java.util.Objects.requireNonNull(payment, "payment");
            milestones = java.util.Objects.requireNonNull(milestones, "milestones");
        }
    }

    private record PaymentOption(KeyService.KeyTransaction transaction,
                                 KeyPaymentPlanner.Availability availability,
                                 long virtualRevision) {
        private PaymentOption {
            availability = java.util.Objects.requireNonNull(availability, "availability");
            if (virtualRevision < 0) throw new IllegalArgumentException("Virtual-key revision cannot be negative");
        }
    }

    private record PaymentChoice(KeyService.KeyTransaction physicalTransaction, String keyId,
                                 int physicalAmount, int virtualAmount, long virtualRevision,
                                 KeyPaymentPolicy policy) {
        private PaymentChoice {
            keyId = java.util.Objects.requireNonNull(keyId, "keyId");
            policy = java.util.Objects.requireNonNull(policy, "policy");
            if (keyId.isBlank() || physicalAmount < 0 || virtualAmount < 0 || virtualRevision < 0) {
                throw new IllegalArgumentException("Invalid frozen key payment");
            }
            if (physicalAmount > 0 && physicalTransaction == null) {
                throw new IllegalArgumentException("Physical payment needs a frozen key template");
            }
        }

        private int total() {
            return Math.addExact(physicalAmount, virtualAmount);
        }

        private String detail() {
            return "payment-policy=" + policy + ";key=" + keyId + ";physical=" + physicalAmount
                    + ";virtual=" + virtualAmount + ";virtual-revision=" + virtualRevision;
        }
    }

    private record PortableContext(UUID issueId, String reservationToken, ItemStack expectedItem) {
        private PortableContext {
            issueId = java.util.Objects.requireNonNull(issueId, "issueId");
            reservationToken = java.util.Objects.requireNonNull(reservationToken, "reservationToken");
            expectedItem = java.util.Objects.requireNonNull(expectedItem, "expectedItem").clone();
        }

        @Override public ItemStack expectedItem() { return expectedItem.clone(); }
    }
    private record DeliveryResult(int overflowCount) {}
}
