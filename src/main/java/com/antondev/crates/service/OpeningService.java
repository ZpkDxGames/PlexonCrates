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
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
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
    /** One post-consumption accept-or-reroll decision per player. */
    private final Map<UUID, RerollDecision> rerollDecisions = new HashMap<>();
    private final Map<UUID, Map<String, Long>> cooldowns = new HashMap<>();
    /** Reserved portable requests waiting to be attached to the journal transaction. */
    private final Map<UUID, PortableContext> portableRequests = new HashMap<>();

    public OpeningService(PlexonCrates plugin, OpeningLog log) {
        this.plugin = plugin;
        this.log = log;
        this.economy = new VaultEconomyBridge(plugin);
        this.placeholders = new PlaceholderBridge(plugin);
    }

    public record RerollView(UUID transactionId, Crate crate, CrateReward candidate,
                             int remaining, String cost, long secondsRemaining,
                             boolean canReroll, boolean processing, String state) {
        public RerollView {
            transactionId = java.util.Objects.requireNonNull(transactionId, "transactionId");
            crate = java.util.Objects.requireNonNull(crate, "crate");
            candidate = java.util.Objects.requireNonNull(candidate, "candidate");
            cost = java.util.Objects.requireNonNull(cost, "cost");
            state = java.util.Objects.requireNonNull(state, "state");
        }
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
        if (!rerollDecisions.isEmpty()) {
            plugin.messages().send(player, "already-opening");
            return false;
        }
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
        long plannedAt = System.currentTimeMillis();
        Function<CrateReward, AlternativeRewardResolver.Reason> eligibility = baseIneligibility(player, plannedAt);
        boolean alternativesEnabled = plugin.settings().alternativeRewardsEnabled();
        RewardStateService.Plan rewardPlan = selectedRewardId == null
                ? plugin.rewardStates().planResolved(player.getUniqueId(), crate, requested, source,
                        eligibility, alternativesEnabled, bypassLimits, plannedAt)
                : plugin.rewardStates().planSelectedResolved(player.getUniqueId(), crate, selectedRewardId,
                        requested, source, eligibility, alternativesEnabled, bypassLimits, plannedAt);
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
        PendingOpening opening = new PendingOpening(plan, crate, rewardPlan, payment, milestonePlan,
                portableContext, plannedAt, false, List.of());
        pending.put(transactionId, opening);
        DatabaseService.JournalRecord journal = new DatabaseService.JournalRecord(transactionId,
                player.getUniqueId(), player.getName(), crate.id(), payment.keyId(), payment.total(),
                requested, source.name(), String.join(",", plan.rewardIds()), plan.createdAt());
        plugin.database().prepareJournal(journal, transactionDetail(opening))
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
        int maximum = Math.min(plugin.settings().maximumBulk(), crate.bulkMaximum());
        if (!plugin.settings().massOpeningEnabled() || !crate.bulkEnabled()) return 1;
        if (crate.keyCost() > 0 && player.hasPermission("plexoncrates.bypass.key")) return 1;
        if (crate.keyCost() <= 0) return maximum;
        int available = 0;
        for (String keyId : crate.acceptedKeyIds()) available = Math.max(available, plugin.keys().count(player, keyId));
        return Math.max(1, Math.min(maximum, available / crate.keyCost()));
    }

    /**
     * Computes the largest currently payable batch without touching keys or
     * balances. Physical inventory is snapshotted on the primary thread and
     * optional virtual balances are loaded on the bounded database worker.
     */
    public CompletableFuture<Integer> maximumAvailableAmount(Player player, Crate crate) {
        if (!Bukkit.isPrimaryThread()) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("Mass-opening capacity must begin on the primary thread"));
        }
        int maximum = Math.min(plugin.settings().maximumBulk(), crate.bulkMaximum());
        if (!plugin.settings().massOpeningEnabled() || !crate.bulkEnabled()) {
            return CompletableFuture.completedFuture(Math.min(1, maximum));
        }
        if (crate.keyCost() > 0 && player.hasPermission("plexoncrates.bypass.key")) {
            return CompletableFuture.completedFuture(Math.min(1, maximum));
        }
        if (crate.keyCost() <= 0) return CompletableFuture.completedFuture(maximum);

        boolean virtual = plugin.settings().virtualKeyWalletEnabled()
                && crate.paymentPolicy() != KeyPaymentPolicy.PHYSICAL_ONLY;
        var capacities = new ArrayList<CompletableFuture<Integer>>();
        for (String keyId : crate.acceptedKeyIds()) {
            int physical = plugin.keys().count(player, keyId);
            if (!virtual) {
                capacities.add(CompletableFuture.completedFuture(paymentCapacity(crate, physical, 0)));
                continue;
            }
            capacities.add(plugin.database().loadVirtualKeyBalance(player.getUniqueId(), keyId)
                    .thenApply(balance -> paymentCapacity(crate, physical, balance.balance())));
        }
        if (capacities.isEmpty()) return CompletableFuture.completedFuture(0);
        CompletableFuture<?>[] all = capacities.toArray(CompletableFuture[]::new);
        return CompletableFuture.allOf(all).thenApply(ignored -> {
            int available = capacities.stream().mapToInt(CompletableFuture::join).max().orElse(0);
            return Math.min(maximum, available / crate.keyCost());
        });
    }

    private static int paymentCapacity(Crate crate, int physical, int virtual) {
        return switch (crate.paymentPolicy()) {
            case PHYSICAL_ONLY -> physical;
            case VIRTUAL_ONLY -> virtual;
            case PHYSICAL_FIRST, VIRTUAL_FIRST, PLAYER_CHOICE -> crate.mixedPayment()
                    ? (int) Math.min(Integer.MAX_VALUE, (long) physical + virtual)
                    : Math.max(physical, virtual);
        };
    }

    public boolean isOpening(UUID playerId) { return locks.contains(playerId); }
    public int pendingCount() { return pending.size(); }
    public boolean economyAvailable() { return economy.available(); }
    public String economyDiagnostic() { return economy.diagnostic(); }

    public Optional<RerollView> rerollView(Player player) {
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("Reroll views require the primary thread");
        RerollDecision decision = rerollDecisions.get(player.getUniqueId());
        if (decision == null) return Optional.empty();
        PendingOpening opening = pending.get(decision.transactionId);
        if (opening == null || opening.selected().isEmpty()) return Optional.empty();
        long nowMillis = System.currentTimeMillis();
        Instant now = Instant.ofEpochMilli(nowMillis);
        boolean active = plugin.settings().rerollsEnabled()
                && player.hasPermission("plexoncrates.rerolls")
                && plugin.runtime().crateRevision(opening.plan().crateId()) == opening.plan().runtimeRevision()
                && !decision.offer.timedOut(now)
                && decision.offer.remaining(decision.policy) > 0;
        List<String> eligible = eligibleRerollIds(player, opening, nowMillis);
        boolean hasReplacement = !RerollService.replacementCandidates(
                decision.policy, decision.offer, eligible).isEmpty();
        String state = "<green>Click to request another eligible reward.</green>";
        if (decision.processing) state = "<yellow>Validating and reserving this reroll…</yellow>";
        else if (!active) state = "<red>Reroll is no longer available.</red>";
        else if (!hasReplacement) state = "<red>No different eligible reward remains.</red>";
        else if (!costSourceAvailable(player, opening.crate(), decision.policy)) {
            state = "<red>The configured reroll payment is unavailable.</red>";
        }
        boolean canReroll = active && hasReplacement && !decision.processing
                && costSourceAvailable(player, opening.crate(), decision.policy);
        long seconds = Math.max(0, java.time.Duration.between(now, decision.offer.expiresAt()).toSeconds() + 1);
        return Optional.of(new RerollView(decision.transactionId, opening.crate(),
                opening.selected().getLast(), decision.offer.remaining(decision.policy),
                rerollCostDescription(decision.policy), seconds, canReroll, decision.processing, state));
    }

    /** Requests one journaled reroll while retaining the current candidate until payment succeeds. */
    public boolean requestReroll(Player player) {
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("Rerolls require the primary thread");
        RerollDecision decision = rerollDecisions.get(player.getUniqueId());
        PendingOpening opening = decision == null ? null : pending.get(decision.transactionId);
        RerollView view = rerollView(player).orElse(null);
        if (decision == null || opening == null || view == null || !view.canReroll()) return false;

        long now = System.currentTimeMillis();
        Function<CrateReward, AlternativeRewardResolver.Reason> eligibility = baseIneligibility(player, now);
        Set<String> excluded = decision.policy.excludePrevious()
                ? new LinkedHashSet<>(decision.offer.shownCandidates()) : Set.of(decision.offer.candidate());
        boolean bypassLimits = opening.plan().source() == OpenSource.ADMIN_FORCE
                || player.hasPermission("plexoncrates.bypass.limit");
        int candidateIndex = opening.rewardPlan().rewards().size() - 1;
        RewardStateService.Plan replacement = plugin.rewardStates().planRerollAtResolved(
                player.getUniqueId(), opening.crate(), opening.plan().source(), opening.rewardPlan(),
                candidateIndex, eligibility, plugin.settings().alternativeRewardsEnabled(),
                bypassLimits, now, excluded);
        if (replacement.rewards().size() != opening.rewardPlan().rewards().size()) return false;
        String replacementId = replacement.rewards().get(candidateIndex).id();
        List<String> eligibleIds = eligibleRerollIds(player, opening, now);
        RerollService.Offer<String> nextOffer = RerollService.replace(decision.policy, decision.offer,
                eligibleIds, replacementId, Instant.ofEpochMilli(now)).orElse(null);
        if (nextOffer == null || !replacementValid(player, opening, replacement, now, bypassLimits)) return false;
        ExtraKeyPayment extraKey = decision.policy.costType() == RerollService.CostType.KEY
                ? extraKeyPayment(player, opening.crate(), decision.policy.cost()).orElse(null) : null;
        if (decision.policy.costType() == RerollService.CostType.KEY
                && decision.policy.cost() > 0 && extraKey == null) return false;

        int attempt = decision.offer.rerollsUsed() + 1;
        decision.processing = true;
        String reservation = "attempt=" + attempt + ",status=COST_RESERVED,type="
                + decision.policy.costType() + ",amount=" + decision.policy.cost()
                + ",candidate=" + replacementId;
        plugin.database().updateJournal(decision.transactionId, "REROLL_COST_RESERVED",
                transactionDetail(opening) + ";" + reservation).whenComplete((ignored, error) -> {
                    if (!plugin.isEnabled()) return;
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        RerollDecision current = rerollDecisions.get(player.getUniqueId());
                        if (current != decision || !decision.processing) return;
                        if (error != null) {
                            failReroll(player, decision, "journal reservation failed");
                            return;
                        }
                        consumeRerollCost(player, decision, opening, replacement, nextOffer,
                                now, bypassLimits, extraKey, attempt);
                    });
                });
        plugin.menus().refreshReroll(player);
        return true;
    }

    /** Accepts the current candidate. Close, quit, teleport, death, and timeout all use this path. */
    public boolean acceptReroll(Player player, String reason) {
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("Reroll acceptance requires the primary thread");
        RerollDecision decision = rerollDecisions.remove(player.getUniqueId());
        if (decision == null) return false;
        if (decision.inFlightCharge != null) {
            ConsumedRerollCost charged = decision.inFlightCharge;
            decision.inFlightCharge = null;
            refundRerollCost(player, decision, charged, decision.offer.rerollsUsed() + 1,
                    "decision accepted before replacement was frozen");
        }
        PendingOpening opening = pending.get(decision.transactionId);
        if (opening == null) {
            locks.remove(player.getUniqueId());
            return false;
        }
        String acceptedReason = reason == null || reason.isBlank() ? "ACCEPT" : reason.trim().toUpperCase(Locale.ROOT);
        opening = opening.withAudit("decision=ACCEPT,candidate=" + decision.offer.candidate()
                + ",reason=" + acceptedReason);
        pending.put(decision.transactionId, opening);
        plugin.database().updateJournal(decision.transactionId, "REROLL_ACCEPTED", transactionDetail(opening));
        try {
            boolean bypassLimits = opening.plan().source() == OpenSource.ADMIN_FORCE
                    || player.hasPermission("plexoncrates.bypass.limit");
            finishDelivery(decision.transactionId, opening, player, opening.crate(), bypassLimits);
        } catch (RuntimeException error) {
            plugin.getLogger().log(Level.SEVERE,
                    "Consumed reroll opening " + decision.transactionId + " could not be delivered", error);
            plugin.database().updateJournal(decision.transactionId, "FAILED",
                    transactionDetail(opening) + ";manual-review=" + concise(error));
            plugin.messages().send(player, "opening-failed", Text.value("transaction", decision.transactionId));
        } finally {
            pending.remove(decision.transactionId);
            locks.remove(player.getUniqueId());
        }
        return true;
    }

    private boolean beginRerollDecision(UUID transactionId, PendingOpening opening, Player player,
                                        Crate current, boolean bypassLimits) {
        RerollService.Policy policy = current.rerolls();
        if (!plugin.settings().rerollsEnabled() || !policy.enabled()
                || !player.hasPermission("plexoncrates.rerolls")
                || current.openingMode() != OpeningMode.RANDOM
                || opening.plan().source() == OpenSource.ADMIN_FORCE
                || opening.plan().openingCount() > 1 && !policy.massAllowed()) return false;
        List<String> eligible = eligibleRerollIds(player, opening, opening.stateAt());
        String candidate = opening.selected().getLast().id();
        if (!eligible.contains(candidate) || eligible.stream().distinct().count() < 2) return false;
        RerollService.Offer<String> offer = RerollService.start(policy, candidate, eligible,
                Instant.ofEpochMilli(opening.stateAt()));
        if (RerollService.replacementCandidates(policy, offer, eligible).isEmpty()) return false;

        RerollDecision decision = new RerollDecision(transactionId, policy, offer);
        PendingOpening offered = opening.withAudit("candidate[0]=" + candidate + ",decision=OFFERED");
        pending.put(transactionId, offered);
        rerollDecisions.put(player.getUniqueId(), decision);
        scheduleRerollTimeout(player.getUniqueId(), decision);
        plugin.database().updateJournal(transactionId, "AWAITING_DECISION", transactionDetail(offered))
                .whenComplete((ignored, error) -> {
                    if (!plugin.isEnabled()) return;
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        if (rerollDecisions.get(player.getUniqueId()) != decision) return;
                        if (error != null) {
                            acceptReroll(player, "JOURNAL_FAILURE");
                        } else if (!player.isOnline()) {
                            acceptReroll(player, "DISCONNECT");
                        } else {
                            plugin.menus().openReroll(player);
                        }
                    });
                });
        return true;
    }

    private void consumeRerollCost(Player player, RerollDecision decision, PendingOpening opening,
                                   RewardStateService.Plan replacement,
                                   RerollService.Offer<String> nextOffer, long selectedAt,
                                   boolean bypassLimits, ExtraKeyPayment extraKey, int attempt) {
        if (!replacementValid(player, opening, replacement, selectedAt, bypassLimits)) {
            failReroll(player, decision, "replacement changed before cost consumption");
            return;
        }
        RerollService.Policy policy = decision.policy;
        switch (policy.costType()) {
            case PERMISSION -> {
                if (!player.hasPermission(policy.permission())) {
                    failReroll(player, decision, "required permission is missing");
                } else {
                    journalConsumedReroll(player, decision, opening, replacement, nextOffer,
                            selectedAt, bypassLimits, null, attempt);
                }
            }
            case MONEY -> {
                if (!plugin.settings().vaultEnabled() || !economy.withdraw(player, policy.cost())) {
                    failReroll(player, decision, "Vault payment was rejected");
                } else {
                    journalConsumedReroll(player, decision, opening, replacement, nextOffer,
                            selectedAt, bypassLimits,
                            new ConsumedRerollCost(policy.costType(), policy.cost(), ""), attempt);
                }
            }
            case KEY -> {
                if (policy.cost() > 0 && (extraKey == null
                        || !plugin.keys().consume(player, extraKey.transaction(), extraKey.amount()))) {
                    failReroll(player, decision, "additional key payment was unavailable");
                } else {
                    journalConsumedReroll(player, decision, opening, replacement, nextOffer,
                            selectedAt, bypassLimits,
                            policy.cost() == 0 ? null : new ConsumedRerollCost(
                                    policy.costType(), policy.cost(), extraKey.keyId()), attempt);
                }
            }
            case TOKEN -> {
                if (policy.cost() == 0) {
                    journalConsumedReroll(player, decision, opening, replacement, nextOffer,
                            selectedAt, bypassLimits, null, attempt);
                    return;
                }
                String token = "reroll-cost:" + decision.transactionId + ":" + attempt;
                plugin.database().debitRerolls(player.getUniqueId(), policy.cost(), token,
                        "OPENING_REROLL", decision.transactionId.toString(), null)
                        .whenComplete((mutation, error) -> {
                            if (!plugin.isEnabled()) {
                                if (error == null && mutation != null && mutation.applied()) {
                                    plugin.database().creditRerolls(player.getUniqueId(), policy.cost(),
                                            "reroll-refund:" + decision.transactionId + ":" + attempt,
                                            "OPENING_REROLL_REFUND", decision.transactionId.toString(), null);
                                }
                                return;
                            }
                            Bukkit.getScheduler().runTask(plugin, () -> {
                                if (error != null || mutation == null || !mutation.applied()) {
                                    if (rerollDecisions.get(player.getUniqueId()) == decision) {
                                        failReroll(player, decision, "reroll token balance was insufficient");
                                    }
                                    return;
                                }
                                ConsumedRerollCost charged = new ConsumedRerollCost(
                                        policy.costType(), policy.cost(), "");
                                if (rerollDecisions.get(player.getUniqueId()) != decision
                                        || !decision.processing
                                        || !replacementValid(player, opening, replacement,
                                                selectedAt, bypassLimits)) {
                                    refundRerollCost(player, decision, charged, attempt,
                                            "decision closed while token debit completed");
                                    return;
                                }
                                journalConsumedReroll(player, decision, opening, replacement, nextOffer,
                                        selectedAt, bypassLimits, charged, attempt);
                            });
                        });
            }
        }
    }

    private void journalConsumedReroll(Player player, RerollDecision decision, PendingOpening opening,
                                       RewardStateService.Plan replacement,
                                       RerollService.Offer<String> nextOffer, long selectedAt,
                                       boolean bypassLimits, ConsumedRerollCost charged, int attempt) {
        decision.inFlightCharge = charged;
        String detail = transactionDetail(opening) + ";attempt=" + attempt
                + ",status=COST_CONSUMED,type=" + decision.policy.costType()
                + ",amount=" + decision.policy.cost()
                + ",candidate=" + replacement.rewards().getLast().id();
        plugin.database().updateJournal(decision.transactionId, "REROLL_COST_CONSUMED", detail)
                .whenComplete((ignored, error) -> {
                    if (!plugin.isEnabled()) return;
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        if (error != null || rerollDecisions.get(player.getUniqueId()) != decision
                                || !decision.processing
                                || !replacementValid(player, opening, replacement, selectedAt, bypassLimits)) {
                            if (charged != null && decision.inFlightCharge != null) {
                                refundRerollCost(player, decision, charged, attempt,
                                    error == null ? "candidate invalidated after cost" : "cost journal failed");
                            } else if (charged == null
                                    && rerollDecisions.get(player.getUniqueId()) == decision) {
                                failReroll(player, decision, "cost journal failed");
                            }
                            return;
                        }
                        String candidate = replacement.rewards().getLast().id();
                        PendingOpening updated = opening.withRewardPlan(replacement, selectedAt,
                                "attempt=" + attempt + ",status=REROLLED,type="
                                        + decision.policy.costType() + ",amount=" + decision.policy.cost()
                                        + ",candidate=" + candidate);
                        pending.put(decision.transactionId, updated);
                        decision.inFlightCharge = null;
                        decision.offer = nextOffer;
                        decision.processing = false;
                        Bukkit.getPluginManager().callEvent(new CrateRewardSelectEvent(
                                player, updated.plan(), updated.plan().deliveries().getLast()));
                        plugin.database().updateJournal(decision.transactionId, "AWAITING_DECISION",
                                transactionDetail(updated));
                        scheduleRerollTimeout(player.getUniqueId(), decision);
                        plugin.menus().openReroll(player);
                    });
                });
    }

    private void refundRerollCost(Player player, RerollDecision decision,
                                  ConsumedRerollCost charged, int attempt, String reason) {
        decision.inFlightCharge = null;
        switch (charged.type()) {
            case TOKEN -> plugin.database().creditRerolls(player.getUniqueId(), charged.amount(),
                    "reroll-refund:" + decision.transactionId + ":" + attempt,
                    "OPENING_REROLL_REFUND", decision.transactionId.toString(), null)
                    .whenComplete((ignored, error) -> {
                        if (!plugin.isEnabled()) return;
                        Bukkit.getScheduler().runTask(plugin, () -> {
                            if (error != null) plugin.getLogger().log(Level.SEVERE,
                                    "Reroll-token refund failed for " + decision.transactionId, error);
                            if (rerollDecisions.get(player.getUniqueId()) == decision) {
                                failReroll(player, decision, reason + "; token refunded");
                            }
                        });
                    });
            case MONEY -> {
                if (!economy.deposit(player, charged.amount())) {
                    plugin.getLogger().severe("Vault reroll refund failed for " + decision.transactionId);
                }
                if (rerollDecisions.get(player.getUniqueId()) == decision) {
                    failReroll(player, decision, reason + "; Vault payment refunded");
                }
            }
            case KEY -> {
                plugin.keys().give(player, charged.keyId(), Math.toIntExact(charged.amount()));
                if (rerollDecisions.get(player.getUniqueId()) == decision) {
                    failReroll(player, decision, reason + "; key payment refunded");
                }
            }
            case PERMISSION -> {
                if (rerollDecisions.get(player.getUniqueId()) == decision) failReroll(player, decision, reason);
            }
        }
    }

    private void failReroll(Player player, RerollDecision decision, String reason) {
        if (rerollDecisions.get(player.getUniqueId()) != decision) return;
        decision.processing = false;
        PendingOpening opening = pending.get(decision.transactionId);
        if (opening != null) {
            opening = opening.withAudit("attempt=" + (decision.offer.rerollsUsed() + 1)
                    + ",status=FAILED,reason=" + reason.replace(';', ','));
            pending.put(decision.transactionId, opening);
            plugin.database().updateJournal(decision.transactionId, "AWAITING_DECISION",
                    transactionDetail(opening));
        }
        player.sendActionBar(Text.parse("<red>Reroll failed:</red> <gray>" + reason + ".</gray>"));
        plugin.menus().openReroll(player);
    }

    private boolean replacementValid(Player player, PendingOpening opening,
                                     RewardStateService.Plan replacement, long selectedAt,
                                     boolean bypassLimits) {
        if (!player.isOnline() || plugin.runtime().crateRevision(opening.plan().crateId())
                != opening.plan().runtimeRevision()) return false;
        Function<CrateReward, AlternativeRewardResolver.Reason> eligibility =
                baseIneligibility(player, selectedAt);
        if (!plugin.rewardStates().canApplyResolved(player.getUniqueId(), opening.crate(), replacement,
                opening.plan().source(), eligibility, plugin.settings().alternativeRewardsEnabled(),
                bypassLimits, selectedAt)) return false;
        if (plugin.settings().overflowPolicy() != OverflowPolicy.REJECT) return true;
        List<ItemStack> items = replacement.rewards().stream()
                .flatMap(reward -> reward.itemCopies().stream()).toList();
        return InventoryPlanner.fits(player.getInventory().getStorageContents(), items);
    }

    private List<String> eligibleRerollIds(Player player, PendingOpening opening, long now) {
        boolean bypassLimits = opening.plan().source() == OpenSource.ADMIN_FORCE
                || player.hasPermission("plexoncrates.bypass.limit");
        return plugin.rewardStates().rerollOutcomesAt(player.getUniqueId(), opening.crate(),
                opening.plan().source(), opening.rewardPlan(), opening.rewardPlan().rewards().size() - 1,
                baseIneligibility(player, now), plugin.settings().alternativeRewardsEnabled(),
                bypassLimits, now).stream()
                .map(outcome -> outcome.actual().id()).distinct().toList();
    }

    private boolean costSourceAvailable(Player player, Crate crate, RerollService.Policy policy) {
        return switch (policy.costType()) {
            case TOKEN -> true; // The authoritative balance is checked atomically after journal reservation.
            case PERMISSION -> player.hasPermission(policy.permission());
            case MONEY -> plugin.settings().vaultEnabled() && economy.available();
            case KEY -> policy.cost() == 0 || extraKeyPayment(player, crate, policy.cost()).isPresent();
        };
    }

    private Optional<ExtraKeyPayment> extraKeyPayment(Player player, Crate crate, long rawAmount) {
        if (rawAmount < 0 || rawAmount > Integer.MAX_VALUE) return Optional.empty();
        int amount = (int) rawAmount;
        if (amount == 0) return Optional.empty();
        for (String keyId : crate.acceptedKeyIds()) {
            KeyService.KeyTransaction transaction = plugin.keys().begin(keyId).orElse(null);
            if (transaction != null && plugin.keys().count(player, transaction) >= amount) {
                return Optional.of(new ExtraKeyPayment(transaction, keyId, amount));
            }
        }
        return Optional.empty();
    }

    private void scheduleRerollTimeout(UUID playerId, RerollDecision decision) {
        int generation = ++decision.generation;
        long ticks = Math.max(1L, decision.policy.timeoutSeconds() * 20L);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (rerollDecisions.get(playerId) != decision || decision.generation != generation) return;
            Player player = Bukkit.getPlayer(playerId);
            if (player != null) acceptReroll(player, "TIMEOUT");
            else {
                PendingOpening opening = pending.get(decision.transactionId);
                plugin.database().updateJournal(decision.transactionId, "FAILED",
                        (opening == null ? "" : transactionDetail(opening) + ";")
                                + "consumed opening lost its online player; manual review required");
                rerollDecisions.remove(playerId);
                pending.remove(decision.transactionId);
                locks.remove(playerId);
            }
        }, ticks);
    }

    private static String rerollCostDescription(RerollService.Policy policy) {
        return switch (policy.costType()) {
            case TOKEN -> policy.cost() + " token" + (policy.cost() == 1 ? "" : "s");
            case PERMISSION -> "free with " + policy.permission();
            case MONEY -> policy.cost() + " Vault money";
            case KEY -> policy.cost() + " additional key" + (policy.cost() == 1 ? "" : "s");
        };
    }

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
        for (UUID playerId : List.copyOf(rerollDecisions.keySet())) {
            Player player = Bukkit.getPlayer(playerId);
            if (player != null) acceptReroll(player, "PLUGIN_STOP");
        }
        for (var entry : List.copyOf(pending.entrySet())) {
            PendingOpening opening = entry.getValue();
            if (opening.paymentConsumed()) {
                plugin.database().updateJournal(entry.getKey(), "FAILED",
                        transactionDetail(opening)
                                + ";consumed opening requires manual recovery after plugin stop");
                continue;
            }
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
        rerollDecisions.clear();
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
            if (!rerollDecisions.isEmpty()) {
                abortPrepared(transactionId, "Another consumed opening is awaiting a reroll decision", false);
                plugin.messages().send(player, "already-opening");
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
            long revalidatedAt = System.currentTimeMillis();
            opening = opening.withStateAt(revalidatedAt);
            pending.put(transactionId, opening);
            Function<CrateReward, AlternativeRewardResolver.Reason> eligibility =
                    baseIneligibility(player, revalidatedAt);
            if (!plugin.rewardStates().canApplyResolved(player.getUniqueId(), current, opening.rewardPlan(),
                    plan.source(), eligibility, plugin.settings().alternativeRewardsEnabled(), bypassLimits,
                    revalidatedAt)) {
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
            deferred = afterPaymentConsumed(transactionId, opening, player, current, bypassLimits);
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
                                    Function<CrateReward, AlternativeRewardResolver.Reason> eligibility,
                                    boolean bypassLimits) {
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
                            if (!plugin.rewardStates().canApplyResolved(player.getUniqueId(), current,
                                    opening.rewardPlan(), opening.plan().source(), eligibility,
                                    plugin.settings().alternativeRewardsEnabled(), bypassLimits,
                                    opening.stateAt())) {
                                refundVirtualAndAbort(opening, "Reward state changed after virtual debit");
                                return;
                            }
                            if (!consumePhysical(player, opening)) {
                                refundVirtualAndAbort(opening, "Physical-key consumption failed after virtual debit");
                                return;
                            }
                            boolean awaitingDecision = afterPaymentConsumed(transactionId, opening, player,
                                    current, bypassLimits);
                            if (!awaitingDecision) {
                                pending.remove(transactionId);
                                locks.remove(player.getUniqueId());
                            }
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

    /** Enters the durable post-payment boundary and either opens a decision or delivers directly. */
    private boolean afterPaymentConsumed(UUID transactionId, PendingOpening opening, Player player,
                                         Crate current, boolean bypassLimits) {
        opening = opening.withPaymentConsumed("payment=CONSUMED");
        pending.put(transactionId, opening);
        OpeningPlan plan = opening.plan();
        if (opening.payment().physicalAmount() > 0) {
            Bukkit.getPluginManager().callEvent(new CrateKeyConsumeEvent(player, plan));
        }
        plugin.database().updateJournal(transactionId, "CONSUMED", transactionDetail(opening));
        if (beginRerollDecision(transactionId, opening, player, current, bypassLimits)) return true;
        finishDelivery(transactionId, opening, player, current, bypassLimits);
        return false;
    }

    private void finishDelivery(UUID transactionId, PendingOpening opening, Player player, Crate current,
                                boolean bypassLimits) {
        OpeningPlan plan = opening.plan();
        Function<CrateReward, AlternativeRewardResolver.Reason> eligibility =
                baseIneligibility(player, opening.stateAt());
        DeliveryResult delivered = deliver(player, current, opening.selected(), plan, true);
        DatabaseService.RewardStateCommit rewardState = plugin.rewardStates().applyResolved(player.getUniqueId(),
                current, opening.rewardPlan(), plan.source(), eligibility,
                plugin.settings().alternativeRewardsEnabled(), bypassLimits, opening.stateAt());
        DatabaseService.MilestoneProgressCommit milestoneState = plugin.milestoneProgress().apply(opening.milestones());
        List<DatabaseService.MilestoneItemClaim> milestoneClaims = freezeMilestoneClaims(opening);
        if (!milestoneClaims.isEmpty()) milestoneState = milestoneState.withClaims(milestoneClaims);
        plugin.statistics().record(player.getUniqueId(), current.id(), plan.openingCount());
        setCooldown(player, current);
        DatabaseService.OpeningRecord record = new DatabaseService.OpeningRecord(transactionId,
                player.getUniqueId(), player.getName(), current.id(), plan.keyId(), plan.keyAmount(),
                plan.openingCount(), plan.source().name(), String.join(",", plan.rewardIds()),
                locationText(plan.location()), delivered.overflowCount(), transactionDetail(opening),
                Instant.now());
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
                                     Function<CrateReward, AlternativeRewardResolver.Reason> eligibility,
                                     boolean bypassLimits) {
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
                boolean awaitingDecision = false;
                try {
                    awaitingDecision = afterPaymentConsumed(transactionId, opening, player, current, bypassLimits);
                } catch (RuntimeException failure) {
                    plugin.getLogger().log(Level.SEVERE,
                            "Portable opening " + transactionId + " failed after consumption", failure);
                    plugin.database().updateJournal(transactionId, "FAILED", concise(failure));
                    plugin.messages().send(player, "opening-failed", Text.value("transaction", transactionId));
                } finally {
                    if (!awaitingDecision) {
                        pending.remove(transactionId);
                        locks.remove(player.getUniqueId());
                    }
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

    private Function<CrateReward, AlternativeRewardResolver.Reason> baseIneligibility(Player player, long now) {
        return reward -> {
            if (!reward.enabled() || !reward.hasDelivery()) {
                return AlternativeRewardResolver.Reason.TRANSACTION_FAILURE;
            }
            if ((!reward.requiredPermission().isBlank() && !player.hasPermission(reward.requiredPermission()))
                    || (!reward.blockedPermission().isBlank() && player.hasPermission(reward.blockedPermission()))) {
                return AlternativeRewardResolver.Reason.PERMISSION;
            }
            if (!reward.availableAt(now)) return AlternativeRewardResolver.Reason.DATE_WINDOW;
            if (reward.money() > 0 && (!plugin.settings().vaultEnabled() || !economy.available())) {
                return AlternativeRewardResolver.Reason.MISSING_INTEGRATION;
            }
            return null;
        };
    }

    /** Exact current source-to-actual mapping used by preview and confirmation menus. */
    public Optional<RewardStateService.Outcome> previewOutcome(
            Player player, Crate crate, CrateReward source, long now, boolean bypassLimits) {
        return plugin.rewardStates().resolveOutcome(player.getUniqueId(), crate, source,
                baseIneligibility(player, now), plugin.settings().alternativeRewardsEnabled(), bypassLimits, now);
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

    private static String transactionDetail(PendingOpening opening) {
        return opening.payment().detail() + ";" + opening.rewardPlan().outcomeDetail()
                + ";rerolls[" + String.join(";", opening.decisionAudit()) + "]";
    }

    private record PendingOpening(OpeningPlan plan, Crate crate, RewardStateService.Plan rewardPlan,
                                  PaymentChoice payment, MilestoneProgressService.Plan milestones,
                                  PortableContext portable, long stateAt, boolean paymentConsumed,
                                  List<String> decisionAudit) {
        private PendingOpening {
            rewardPlan = java.util.Objects.requireNonNull(rewardPlan, "rewardPlan");
            payment = java.util.Objects.requireNonNull(payment, "payment");
            milestones = java.util.Objects.requireNonNull(milestones, "milestones");
            decisionAudit = List.copyOf(decisionAudit);
            if (stateAt < 0) throw new IllegalArgumentException("Opening state time cannot be negative");
        }

        private List<CrateReward> selected() { return rewardPlan.rewards(); }

        private PendingOpening withStateAt(long value) {
            return new PendingOpening(plan, crate, rewardPlan, payment, milestones, portable,
                    value, paymentConsumed, decisionAudit);
        }

        private PendingOpening withPaymentConsumed(String audit) {
            return withAudit(audit, true);
        }

        private PendingOpening withAudit(String audit) {
            return withAudit(audit, paymentConsumed);
        }

        private PendingOpening withAudit(String audit, boolean consumed) {
            var next = new ArrayList<>(decisionAudit);
            next.add(audit);
            return new PendingOpening(plan, crate, rewardPlan, payment, milestones, portable,
                    stateAt, consumed, next);
        }

        private PendingOpening withRewardPlan(RewardStateService.Plan replacement, long value,
                                              String audit) {
            List<RewardDelivery> deliveries = replacement.rewards().stream()
                    .map(OpeningService::delivery).toList();
            OpeningPlan updated = new OpeningPlan(plan.transactionId(), plan.playerId(), plan.playerName(),
                    plan.crateId(), plan.keyId(), plan.keyAmount(), plan.openingCount(), plan.source(),
                    plan.location(), plan.runtimeRevision(), deliveries, plan.createdAt());
            var next = new ArrayList<>(decisionAudit);
            next.add(audit);
            return new PendingOpening(updated, crate, replacement, payment, milestones, portable,
                    value, paymentConsumed, next);
        }
    }

    private static final class RerollDecision {
        private final UUID transactionId;
        private final RerollService.Policy policy;
        private RerollService.Offer<String> offer;
        private int generation;
        private boolean processing;
        private ConsumedRerollCost inFlightCharge;

        private RerollDecision(UUID transactionId, RerollService.Policy policy,
                               RerollService.Offer<String> offer) {
            this.transactionId = transactionId;
            this.policy = policy;
            this.offer = offer;
        }
    }

    private record ExtraKeyPayment(KeyService.KeyTransaction transaction, String keyId, int amount) {}
    private record ConsumedRerollCost(RerollService.CostType type, long amount, String keyId) {}

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
