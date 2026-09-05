package com.antondev.crates.service;

import com.antondev.crates.PlexonCrates;
import com.antondev.crates.api.event.CrateDraftPublishEvent;
import com.antondev.crates.config.Text;
import com.antondev.crates.database.DatabaseService;
import com.antondev.crates.database.DefinitionRepository;
import com.antondev.crates.domain.crate.CrateState;
import com.antondev.crates.domain.key.KeyDefinition;
import com.antondev.crates.domain.key.KeySource;
import com.antondev.crates.item.ItemSnapshotCodec;
import com.antondev.crates.model.Crate;
import com.antondev.crates.model.CrateReward;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Supplier;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;

/** Validates one frozen draft, commits its complete graph, then swaps the runtime snapshot. */
public final class DefinitionPublisher {
    public record Publication(Crate crate, long crateRevision, long runtimeRevision, boolean yamlMirrorUpdated) {}

    private record Prepared(
            DraftSessionService.FrozenDraft frozen,
            CrateRegistry.PreparedPublication publication,
            DatabaseService.DefinitionBundle bundle,
            String actorName) {}

    private final PlexonCrates plugin;
    private final DefinitionRepository repository;
    private final CrateRegistry crates;
    private final KeyService keys;
    private final RuntimeRegistry runtime;
    private final DraftSessionService drafts;

    public DefinitionPublisher(PlexonCrates plugin, DefinitionRepository repository, CrateRegistry crates,
                               KeyService keys, RuntimeRegistry runtime, DraftSessionService drafts) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.repository = Objects.requireNonNull(repository, "repository");
        this.crates = Objects.requireNonNull(crates, "crates");
        this.keys = Objects.requireNonNull(keys, "keys");
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.drafts = Objects.requireNonNull(drafts, "drafts");
    }

    /** Imports the released YAML graph only when the canonical definition store is empty. */
    public static RuntimeSnapshot bootstrap(
            DefinitionRepository repository, CrateRegistry crates, KeyService keys) throws Exception {
        DatabaseService.PublishedSnapshot stored;
        try {
            stored = repository.loadPublished().join();
            if (stored.definitions().isEmpty()) {
                var initial = new ArrayList<DatabaseService.DefinitionBundle>();
                for (Crate crate : crates.ordered()) {
                    byte[] payload = crates.serialized(crate.id()).getBytes(StandardCharsets.UTF_8);
                    initial.add(bundle(crate, payload, keys));
                }
                stored = repository.bootstrap(initial).join();
            }
        } catch (CompletionException error) {
            throw asException(error);
        }
        var entries = new ArrayList<RuntimeSnapshot.Entry>();
        for (DatabaseService.StoredDefinition definition : stored.definitions().stream()
                .filter(value -> value.lifecycle().equalsIgnoreCase(CrateState.PUBLISHED.name())).toList()) {
            Crate crate = crates.parsePublished(definition.crateId(), definition.payload());
            entries.add(new RuntimeSnapshot.Entry(definition.publishedRevision(), crate, definition.payload()));
        }
        return new RuntimeSnapshot(stored.runtimeRevision(), entries);
    }

    public CompletableFuture<Publication> publish(UUID actorId, String actorName, String crateId) {
        UUID actor = Objects.requireNonNull(actorId, "actorId");
        String name = Objects.requireNonNull(actorName, "actorName").trim();
        if (name.isEmpty()) return CompletableFuture.failedFuture(
                new IllegalArgumentException("Actor name cannot be blank"));

        CompletableFuture<Publication> result = drafts.freezeCrate(actor, crateId)
                .thenCompose(frozen -> primary(() -> prepare(frozen, name)))
                .thenCompose(prepared -> repository.publish(request(prepared))
                        .thenCompose(saved -> primary(() -> activate(prepared, saved))));
        result.whenComplete((ignored, error) -> {
            if (error != null) drafts.releasePublication(actor, crateId);
        });
        return result;
    }

    private Prepared prepare(DraftSessionService.FrozenDraft frozen, String actorName) throws Exception {
        requirePrimaryThread();
        CrateRegistry.PreparedPublication publication = crates.preparePublished(
                frozen.crateId(), frozen.payload(), actorName);
        if (publication.crate().state() == CrateState.PUBLISHED) {
            List<String> issues = crates.publishingIssues(publication.crate(), keys);
            if (!issues.isEmpty()) throw new IllegalStateException(String.join(" ", issues));
        }
        CrateDraftPublishEvent event = new CrateDraftPublishEvent(frozen.actorId(), actorName,
                frozen.draftId(), frozen.revision(), frozen.baseRevision(), publication.crate());
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled()) throw new IllegalStateException("Crate draft publication was cancelled");
        return new Prepared(frozen, publication, bundle(publication.crate(), publication.payload(), keys), actorName);
    }

    private DatabaseService.PublishRequest request(Prepared prepared) {
        DraftSessionService.FrozenDraft frozen = prepared.frozen();
        return new DatabaseService.PublishRequest(frozen.draftId(), frozen.revision(), frozen.leaseToken(),
                frozen.actorId(), prepared.actorName(), frozen.payload(), prepared.bundle(), Instant.now());
    }

    private Publication activate(Prepared prepared, DatabaseService.PublishResult saved) {
        requirePrimaryThread();
        Crate published = prepared.publication().crate();
        if (published.state() == CrateState.PUBLISHED) {
            runtime.install(saved.runtimeRevision(), saved.definition().publishedRevision(), published,
                    prepared.publication().payload());
        } else {
            runtime.remove(saved.runtimeRevision(), saved.definition().publishedRevision(), published.id());
        }
        plugin.recordDefinitionRevision(published.id(), saved.definition().publishedRevision());
        drafts.published(published.id());
        boolean yamlMirrorUpdated = true;
        try {
            crates.installPublished(prepared.publication());
        } catch (Exception error) {
            yamlMirrorUpdated = false;
            plugin.getLogger().log(Level.WARNING, "Published " + published.id()
                    + " from SQLite, but its optional YAML mirror could not be updated", error);
        }
        try {
            plugin.displays().refresh();
        } catch (RuntimeException error) {
            plugin.getLogger().log(Level.WARNING, "Published " + published.id()
                    + ", but display reconciliation needs attention", error);
        }
        return new Publication(published, saved.definition().publishedRevision(), saved.runtimeRevision(),
                yamlMirrorUpdated);
    }

    private static DatabaseService.DefinitionBundle bundle(
            Crate crate, byte[] payload, KeyService keys) {
        if (crate.state() == CrateState.DRAFT) {
            throw new IllegalArgumentException("A draft candidate cannot be encoded");
        }
        ItemSnapshotCodec snapshots = new ItemSnapshotCodec();
        ItemSnapshotCodec.Snapshot icon = snapshots.capture(crate.iconCopy());
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.loadFromString(new String(payload, StandardCharsets.UTF_8));
        } catch (Exception error) {
            throw new IllegalArgumentException("Published definition payload is invalid", error);
        }

        var rewards = new ArrayList<DatabaseService.DefinitionRewardData>();
        int position = 0;
        for (CrateReward reward : crate.orderedRewards()) {
            var items = new ArrayList<DatabaseService.DefinitionItemData>();
            var actions = new ArrayList<DatabaseService.DefinitionActionData>();
            int actionIndex = 0;
            for (ItemStack item : reward.itemCopies()) {
                ItemSnapshotCodec.Snapshot snapshot = snapshots.capture(item);
                items.add(new DatabaseService.DefinitionItemData(actionIndex, snapshot.bytes(),
                        snapshot.capturedAmount(), snapshot.material(), snapshot.serializedSize(),
                        snapshot.sha256(), snapshot.capturedAt()));
                actions.add(new DatabaseService.DefinitionActionData(actionIndex++, "ITEM",
                        snapshot.sha256().getBytes(StandardCharsets.UTF_8)));
            }
            for (String command : reward.commands()) {
                actions.add(action(actionIndex++, "COMMAND", command));
            }
            if (reward.experiencePoints() > 0) {
                actions.add(action(actionIndex++, "EXPERIENCE_POINTS", reward.experiencePoints()));
            }
            if (reward.experienceLevels() > 0) {
                actions.add(action(actionIndex++, "EXPERIENCE_LEVELS", reward.experienceLevels()));
            }
            if (reward.money() > 0) {
                actions.add(action(actionIndex, "MONEY", java.math.BigDecimal.valueOf(reward.money()).toPlainString()));
            }
            rewards.add(new DatabaseService.DefinitionRewardData(reward.id(), position++, reward.enabled(),
                    Text.serialize(reward.displayName()), reward.rarity().name().toLowerCase(Locale.ROOT),
                    reward.chanceBasisPoints(), yaml.getBoolean("rewards." + reward.id() + ".chance-locked", false),
                    rewardSettings(reward), items, actions));
        }

        var definitionKeys = new ArrayList<DatabaseService.DefinitionKeyData>();
        for (String keyId : crate.acceptedKeyIds().stream().distinct().toList()) {
            KeyDefinition definition = keys.definition(keyId)
                    .orElseThrow(() -> new IllegalArgumentException("Unknown key: " + keyId));
            definitionKeys.add(key(definition, keys, snapshots));
        }
        Instant now = Instant.now();
        return new DatabaseService.DefinitionBundle(crate.id(), crate.state().name(), crate.displayOrder(),
                Text.serialize(crate.displayName()), crate.description().stream().map(Text::serialize)
                        .collect(java.util.stream.Collectors.joining("\n")),
                icon.bytes(), payload, rewards, definitionKeys, crate.acceptedKeyIds(), crate.keyCost(), now, now);
    }

    private static DatabaseService.DefinitionKeyData key(
            KeyDefinition definition, KeyService keys, ItemSnapshotCodec snapshots) {
        var templates = new ArrayList<DatabaseService.DefinitionKeyTemplateData>();
        ItemStack current = definition.source() == KeySource.PLEXONKEYS
                ? keys.resolve(definition.id()).map(value -> value.template()).orElse(null)
                : definition.ownedTemplate();
        addTemplate(templates, snapshots, "CURRENT", 0, current);
        addTemplate(templates, snapshots, "FALLBACK", 0, definition.fallbackTemplate());
        int sequence = 0;
        for (ItemStack legacy : definition.legacyTemplates()) {
            addTemplate(templates, snapshots, "LEGACY", sequence++, legacy);
        }
        String settings = "external-id=" + definition.externalId() + "\nmatch-mode=" + definition.matchMode()
                + "\ncache-last-known-good=" + definition.cacheLastKnownGood() + "\n";
        return new DatabaseService.DefinitionKeyData(definition.id(), definition.source().name(),
                Text.serialize(definition.displayName()), current == null ? "UNRESOLVED" : "RESOLVED",
                !definition.enabled(), settings.getBytes(StandardCharsets.UTF_8), templates);
    }

    private static void addTemplate(List<DatabaseService.DefinitionKeyTemplateData> target,
                                    ItemSnapshotCodec snapshots, String kind, int sequence, ItemStack item) {
        if (item == null || item.getType().isAir()) return;
        ItemSnapshotCodec.Snapshot snapshot = snapshots.capture(item);
        target.add(new DatabaseService.DefinitionKeyTemplateData(kind, sequence, snapshot.bytes(),
                snapshot.material(), snapshot.serializedSize(), snapshot.sha256(), snapshot.capturedAt()));
    }

    private static DatabaseService.DefinitionActionData action(int index, String type, Object value) {
        return new DatabaseService.DefinitionActionData(index, type,
                String.valueOf(value).getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] rewardSettings(CrateReward reward) {
        String settings = "required-permission=" + reward.requiredPermission()
                + "\nblocked-permission=" + reward.blockedPermission()
                + "\nplayer-lifetime=" + reward.limits().playerLifetime()
                + "\nplayer-window=" + reward.limits().playerWindow()
                + "\nplayer-window-seconds=" + reward.limits().playerWindowSeconds()
                + "\nglobal-lifetime=" + reward.limits().globalLifetime()
                + "\nglobal-window=" + reward.limits().globalWindow()
                + "\nglobal-window-seconds=" + reward.limits().globalWindowSeconds()
                + "\ncooldown-seconds=" + reward.limits().cooldownSeconds()
                + "\npresentation-title=" + reward.presentation().title()
                + "\npresentation-subtitle=" + reward.presentation().subtitle()
                + "\npresentation-sound=" + reward.presentation().sound()
                + "\npresentation-volume=" + reward.presentation().soundVolume()
                + "\npresentation-pitch=" + reward.presentation().soundPitch()
                + "\npresentation-firework=" + reward.presentation().firework() + "\n";
        return settings.getBytes(StandardCharsets.UTF_8);
    }

    private <T> CompletableFuture<T> primary(CheckedSupplier<T> supplier) {
        var future = new CompletableFuture<T>();
        Runnable task = () -> {
            try {
                if (!plugin.isEnabled()) throw new IllegalStateException("Plugin disabled during publication");
                future.complete(supplier.get());
            } catch (Throwable error) {
                future.completeExceptionally(error);
            }
        };
        if (Bukkit.isPrimaryThread()) task.run();
        else Bukkit.getScheduler().runTask(plugin, task);
        return future;
    }

    private static void requirePrimaryThread() {
        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException("Definition preparation and activation require the primary server thread");
        }
    }

    private static Exception asException(Throwable error) {
        Throwable current = error;
        while ((current instanceof CompletionException
                || current instanceof java.util.concurrent.ExecutionException) && current.getCause() != null) {
            current = current.getCause();
        }
        return current instanceof Exception exception ? exception : new IllegalStateException(current);
    }

    @FunctionalInterface
    private interface CheckedSupplier<T> {
        T get() throws Exception;
    }
}
