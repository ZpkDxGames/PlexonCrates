package com.antondev.crates.service;

import com.antondev.crates.PlexonCrates;
import com.antondev.crates.api.event.CrateKeyConsumeEvent;
import com.antondev.crates.api.event.CrateOpenEvent;
import com.antondev.crates.api.event.CratePreOpenEvent;
import com.antondev.crates.api.event.CrateRewardSelectEvent;
import com.antondev.crates.config.OverflowPolicy;
import com.antondev.crates.config.Text;
import com.antondev.crates.database.DatabaseService;
import com.antondev.crates.domain.crate.AnimationType;
import com.antondev.crates.domain.crate.CrateState;
import com.antondev.crates.domain.opening.OpenSource;
import com.antondev.crates.domain.opening.OpeningPlan;
import com.antondev.crates.domain.opening.RewardDelivery;
import com.antondev.crates.domain.reward.RewardPresentation;
import com.antondev.crates.integration.PlaceholderBridge;
import com.antondev.crates.integration.VaultEconomyBridge;
import com.antondev.crates.item.ItemSnapshotCodec;
import com.antondev.crates.model.BlockPosition;
import com.antondev.crates.model.Crate;
import com.antondev.crates.model.CrateReward;
import java.time.Instant;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
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
        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException("Crate openings must begin on the primary server thread");
        }
        if (issue == null || expectedItem == null
                || plugin.portables().decode(expectedItem).filter(token ->
                        token.payload().issueId().equals(issue.issueId())
                                && token.payload().crateId().equals(crate.id())).isEmpty()) {
            plugin.messages().send(player, "invalid-crate");
            return false;
        }
        String reservation = UUID.randomUUID().toString();
        plugin.database().reservePortableIssue(issue.issueId(), reservation).whenComplete((reserved, error) -> {
            if (!plugin.isEnabled()) return;
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
                    accepted = open(player, crate, 1, OpenSource.PORTABLE, null);
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
            if (amount < 1 || amount > maximum || (amount > 1 && !crate.bulkEnabled())) {
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
            KeyChoice keyChoice = portable
                    ? new KeyChoice(null, "PORTABLE", amount)
                    : chooseKey(player, crate, amount, consumeKey);
            if (consumeKey && (keyChoice.transaction() == null || keyChoice.maximumOpenings() < 1)) {
                plugin.messages().send(player, "no-key", Text.component("key", keyName(crate)));
                return rejectSilently(player);
            }

            int requested = Math.min(amount, keyChoice.maximumOpenings());
            boolean bypassLimits = forced || player.hasPermission("plexoncrates.bypass.limit");
            RewardStateService.Plan rewardPlan = plugin.rewardStates().plan(player.getUniqueId(), crate, requested,
                    source, baseEligibility(player), bypassLimits, System.currentTimeMillis());
            var selected = new ArrayList<>(rewardPlan.rewards());
            if (selected.isEmpty()) return reject(player, "no-eligible-rewards");

            if (plugin.settings().overflowPolicy() == OverflowPolicy.REJECT) {
                while (!selected.isEmpty()) {
                    List<ItemStack> candidateItems = selected.stream().flatMap(reward -> reward.itemCopies().stream()).toList();
                    if (InventoryPlanner.fits(player.getInventory().getStorageContents(), candidateItems)) break;
                    selected.removeLast();
                }
                if (selected.isEmpty()) return reject(player, "inventory-full");
            }

            int openingCount = selected.size();
            int keyAmount = consumeKey ? Math.multiplyExact(openingCount, crate.keyCost()) : 0;
            List<RewardDelivery> deliveries = selected.stream().map(OpeningService::delivery).toList();

            UUID transactionId = UUID.randomUUID();
            OpeningPlan plan = new OpeningPlan(transactionId, player.getUniqueId(), player.getName(), crate.id(),
                    keyChoice.keyId(), keyAmount, openingCount, source, location,
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
            if (portable && portableContext == null) {
                return reject(player, "opening-state-changed");
            }
            PendingOpening opening = new PendingOpening(plan, crate, selected, keyChoice.transaction(), consumeKey,
                    portableContext);
            pending.put(transactionId, opening);
            DatabaseService.JournalRecord journal = new DatabaseService.JournalRecord(transactionId,
                    player.getUniqueId(), player.getName(), crate.id(), keyChoice.keyId(), keyAmount,
                    openingCount, source.name(), String.join(",", plan.rewardIds()), plan.createdAt());
            plugin.database().prepareJournal(journal).whenComplete((ignored, error) -> {
                if (!plugin.isEnabled()) return;
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (error != null) abortPrepared(transactionId, "Database journal preparation failed", true);
                    else commitPrepared(transactionId);
                });
            });
            return true;
        } catch (RuntimeException error) {
            locks.remove(player.getUniqueId());
            throw error;
        }
    }

    public int bulkAmount(Player player, Crate crate) {
        if (player.hasPermission("plexoncrates.bypass.key")) return 1;
        int maximum = Math.min(plugin.settings().maximumBulk(), crate.bulkMaximum());
        if (!crate.bulkEnabled() || crate.keyCost() <= 0) return 1;
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
        for (UUID transaction : List.copyOf(pending.keySet())) {
            plugin.database().updateJournal(transaction, "CANCELLED", "Plugin disabled before inventory mutation");
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
            Predicate<CrateReward> eligibility = baseEligibility(player);
            if (!plugin.rewardStates().canApply(player.getUniqueId(), current, opening.selected(), plan.source(),
                    eligibility, bypassLimits, System.currentTimeMillis())) {
                abortPrepared(transactionId, "Reward limits or pity state changed before consumption", false);
                plugin.messages().send(player, "no-eligible-rewards");
                return;
            }
            if (opening.consumeKey()
                    && (opening.keyTransaction() == null
                    || plugin.keys().count(player, opening.keyTransaction()) < plan.keyAmount())) {
                abortPrepared(transactionId, "Exact key count changed before consumption", false);
                plugin.messages().send(player, "no-key", Text.component("key", keyName(current)));
                return;
            }
            List<ItemStack> items = plan.deliveries().stream().flatMap(delivery -> delivery.items().stream()).toList();
            if (plugin.settings().overflowPolicy() == OverflowPolicy.REJECT
                    && !InventoryPlanner.fits(player.getInventory().getStorageContents(), items)) {
                abortPrepared(transactionId, "Inventory capacity changed before consumption", false);
                plugin.messages().send(player, "inventory-full");
                return;
            }
            if (opening.consumeKey() && !plugin.keys().consume(player, opening.keyTransaction(), plan.keyAmount())) {
                abortPrepared(transactionId, "Exact key revalidation failed", false);
                plugin.messages().send(player, "no-key", Text.component("key", keyName(current)));
                return;
            }
            if (opening.portable() != null) {
                deferred = true;
                beginPortableCommit(transactionId, opening, player, current, eligibility, bypassLimits);
                return;
            }
            if (opening.consumeKey()) Bukkit.getPluginManager().callEvent(new CrateKeyConsumeEvent(player, plan));
            plugin.database().updateJournal(transactionId, "CONSUMED", "");

            DeliveryResult delivered = deliver(player, current, opening.selected(), plan, true);
            DatabaseService.RewardStateCommit rewardState = plugin.rewardStates().apply(player.getUniqueId(), current,
                    opening.selected(), plan.source(), eligibility, bypassLimits, System.currentTimeMillis());
            plugin.statistics().record(player.getUniqueId(), current.id(), plan.openingCount());
            setCooldown(player, current);
            DatabaseService.OpeningRecord record = new DatabaseService.OpeningRecord(transactionId,
                    player.getUniqueId(), player.getName(), current.id(), plan.keyId(), plan.keyAmount(),
                    plan.openingCount(), plan.source().name(), String.join(",", plan.rewardIds()),
                    locationText(plan.location()), delivered.overflowCount(), Instant.now());
            plugin.database().completeOpening(record, rewardState);
            Bukkit.getPluginManager().callEvent(new CrateOpenEvent(player, plan, delivered.overflowCount()));
            if (delivered.overflowCount() > 0) notifyOverflow(player);
            showResult(player, current, opening.selected(), plan);
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
                    OpeningPlan plan = opening.plan();
                    plugin.database().updateJournal(transactionId, "CONSUMED", "");
                    DeliveryResult delivered = deliver(player, current, opening.selected(), plan, true);
                    DatabaseService.RewardStateCommit rewardState = plugin.rewardStates().apply(
                            player.getUniqueId(), current, opening.selected(), plan.source(), eligibility,
                            bypassLimits, System.currentTimeMillis());
                    plugin.statistics().record(player.getUniqueId(), current.id(), plan.openingCount());
                    setCooldown(player, current);
                    DatabaseService.OpeningRecord record = new DatabaseService.OpeningRecord(transactionId,
                            player.getUniqueId(), player.getName(), current.id(), plan.keyId(), plan.keyAmount(),
                            plan.openingCount(), plan.source().name(), String.join(",", plan.rewardIds()),
                            locationText(plan.location()), delivered.overflowCount(), Instant.now());
                    plugin.database().completeOpening(record, rewardState);
                    Bukkit.getPluginManager().callEvent(new CrateOpenEvent(player, plan, delivered.overflowCount()));
                    if (delivered.overflowCount() > 0) notifyOverflow(player);
                    showResult(player, current, opening.selected(), plan);
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

    private KeyChoice chooseKey(Player player, Crate crate, int amount, boolean consumeKey) {
        if (!consumeKey) return new KeyChoice(null, crate.keyId().isBlank() ? "BYPASS" : crate.keyId(), amount);
        KeyService.KeyTransaction best = null;
        String bestId = crate.keyId();
        int bestOpenings = 0;
        for (String keyId : crate.acceptedKeyIds()) {
            Optional<KeyService.KeyTransaction> transaction = plugin.keys().begin(keyId);
            if (transaction.isEmpty()) continue;
            int available = plugin.keys().count(player, transaction.get()) / crate.keyCost();
            if (available > bestOpenings) {
                best = transaction.get();
                bestId = keyId;
                bestOpenings = available;
            }
        }
        return new KeyChoice(best, bestId, Math.min(amount, bestOpenings));
    }

    private Predicate<CrateReward> baseEligibility(Player player) {
        return reward -> reward.eligible(player) && reward.hasDelivery()
                && (reward.money() <= 0 || (plugin.settings().vaultEnabled() && economy.available()));
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

    private static String locationText(BlockPosition location) {
        return location == null ? "" : location.worldName() + ":" + location.x() + ":" + location.y() + ":" + location.z();
    }

    private static String concise(Throwable error) {
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }

    private record PendingOpening(OpeningPlan plan, Crate crate, List<CrateReward> selected,
                                  KeyService.KeyTransaction keyTransaction, boolean consumeKey,
                                  PortableContext portable) {
        private PendingOpening {
            selected = List.copyOf(selected);
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
    private record KeyChoice(KeyService.KeyTransaction transaction, String keyId, int maximumOpenings) {}
    private record DeliveryResult(int overflowCount) {}
}
