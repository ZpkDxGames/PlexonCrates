package com.antondev.crates.database;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.antondev.crates.domain.draft.DraftMutation;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DatabaseServiceTest {
    @TempDir
    Path temporary;

    @Test
    void schemaThreeInstallsNormalizedDefinitionDraftAndValueTables() throws Exception {
        try (DatabaseService database = database()) {
            assertEquals(3, database.schemaVersion());
            for (String table : List.of("crate_definition", "reward_definition", "reward_item",
                    "reward_action", "key_definition_v3", "key_template_v3", "crate_key_link",
                    "effect_profile", "rarity_profile", "milestone_definition", "milestone_state",
                    "reroll_policy", "reroll_balance", "virtual_key_balance", "ledger_entry",
                    "claim_entry", "portable_crate_issue", "plugin_secret", "definition_draft",
                    "draft_revision", "schema_migration")) {
                assertTrue(database.schemaObjectExists("table", table), table);
            }
            for (String index : List.of("crate_collection_page", "reward_collection_page",
                    "key_collection_page", "claim_player_state_page", "draft_revision_undo")) {
                assertTrue(database.schemaObjectExists("index", index), index);
            }
        }
    }

    @Test
    void durableDraftRejectsStaleWritesSupportsTakeoverUndoAndBoundedHistory() throws Exception {
        UUID firstEditor = UUID.randomUUID();
        UUID secondEditor = UUID.randomUUID();
        Instant now = Instant.parse("2026-09-04T12:00:00Z");
        try (DatabaseService database = database()) {
            var draft = database.createOrResumeDefinitionDraft("crate", "basic", firstEditor, "First", 4,
                    bytes("zero")).join();
            assertEquals(0, draft.revision());
            assertEquals(1, draft.leaseToken());
            assertTrue(draft.writableBy(firstEditor, 1));

            var saved = database.saveDefinitionDraft(draft.draftId(), mutation(draft, firstEditor, "one", now)).join();
            assertEquals(1, saved.revision());
            assertEquals("one", text(saved.payload()));
            assertThrows(CompletionException.class, () -> database.saveDefinitionDraft(draft.draftId(),
                    mutation(draft, firstEditor, "stale", now.plusSeconds(1))).join());

            var taken = database.takeoverDefinitionDraft(draft.draftId(), saved.leaseToken(), secondEditor, "Second").join();
            assertEquals(secondEditor, taken.ownerId());
            assertEquals(2, taken.leaseToken());
            assertFalse(taken.writableBy(firstEditor, 1));
            assertThrows(CompletionException.class, () -> database.saveDefinitionDraft(taken.draftId(),
                    new DraftMutation(taken.revision(), 1, firstEditor, "EDIT", "Stale lease", bytes("bad"),
                            "UNVALIDATED", now.plusSeconds(2))).join());

            var current = database.saveDefinitionDraft(taken.draftId(),
                    mutation(taken, secondEditor, "two", now.plusSeconds(3))).join();
            current = database.undoDefinitionDraft(current.draftId(), current.revision(), current.leaseToken(),
                    secondEditor, now.plusSeconds(4)).join();
            assertEquals("one", text(current.payload()));

            for (int index = 0; index < 25; index++) {
                current = database.saveDefinitionDraft(current.draftId(),
                        mutation(current, secondEditor, "value-" + index, now.plusSeconds(10 + index))).join();
            }
            assertEquals(20, database.draftRevisionCount(current.draftId()).join());
            assertEquals(current.draftId(), database.loadDefinitionDraft("CRATE", "basic").join().orElseThrow().draftId());
            database.discardDefinitionDraft(current.draftId(), current.revision(), current.leaseToken(),
                    secondEditor, "Second").join();
            assertTrue(database.loadDefinitionDraft("CRATE", "basic").join().isEmpty());
            assertEquals(0, database.draftRevisionCount(current.draftId()).join());
        }
    }

    @Test
    void durableDraftListingReturnsPayloadsForRestartRecovery() throws Exception {
        UUID firstEditor = UUID.randomUUID();
        UUID secondEditor = UUID.randomUUID();
        try (DatabaseService database = database()) {
            database.createOrResumeDefinitionDraft("CRATE", "first", firstEditor, "First", 0,
                    bytes("first-payload")).join();
            database.createOrResumeDefinitionDraft("crate", "second", secondEditor, "Second", 8,
                    bytes("second-payload")).join();

            var drafts = database.loadDefinitionDrafts().join();

            assertEquals(2, drafts.size());
            assertEquals(java.util.Set.of("first", "second"),
                    drafts.stream().map(com.antondev.crates.domain.draft.DefinitionDraft::targetId)
                            .collect(java.util.stream.Collectors.toSet()));
            assertTrue(drafts.stream().anyMatch(draft -> text(draft.payload()).equals("first-payload")));
            assertTrue(drafts.stream().anyMatch(draft -> text(draft.payload()).equals("second-payload")));
        }
    }

    @Test
    void publicationChecksFrozenDraftAndBaseRevisionThenCommitsNormalizedGraphAtomically() throws Exception {
        UUID editor = UUID.randomUUID();
        Instant now = Instant.parse("2026-09-04T12:00:00Z");
        var action = new DatabaseService.DefinitionActionData(0, "COMMAND", bytes("say published"));
        var reward = new DatabaseService.DefinitionRewardData("winner", 0, true, "Winner", "common",
                10_000, false, bytes("settings"), List.of(), List.of(action));
        var definition = new DatabaseService.DefinitionBundle("atomic", "PUBLISHED", 10, "Atomic",
                "Atomic publication", bytes("icon"), bytes("published"), List.of(reward), List.of(),
                List.of(), 0, now, now);

        try (DatabaseService database = database()) {
            var draft = database.createOrResumeDefinitionDraft("CRATE", "atomic", editor, "Editor", 0,
                    bytes("draft")).join();
            DatabaseService.PublishResult published = database.publishDefinitionDraft(
                    new DatabaseService.PublishRequest(draft.draftId(), draft.revision(), draft.leaseToken(),
                            editor, "Editor", draft.payload(), definition, now)).join();

            assertEquals(1, published.definition().publishedRevision());
            assertEquals(1, published.runtimeRevision());
            assertTrue(database.loadDefinitionDraft("CRATE", "atomic").join().isEmpty());
            var snapshot = database.loadPublishedDefinitions().join();
            assertEquals(1, snapshot.runtimeRevision());
            assertArrayEquals(bytes("published"), snapshot.definitions().getFirst().payload());
            assertEquals(new DatabaseService.DefinitionCounts(1, 0, 1, 0),
                    database.definitionCounts("atomic").join());

            var stale = database.createOrResumeDefinitionDraft("CRATE", "atomic", editor, "Editor", 0,
                    bytes("stale")).join();
            assertThrows(CompletionException.class, () -> database.publishDefinitionDraft(
                    new DatabaseService.PublishRequest(stale.draftId(), stale.revision(), stale.leaseToken(),
                            editor, "Editor", stale.payload(), definition, now.plusSeconds(1))).join());
            var unchanged = database.loadPublishedDefinitions().join();
            assertEquals(1, unchanged.runtimeRevision());
            assertArrayEquals(bytes("published"), unchanged.definitions().getFirst().payload());
        }
    }

    @Test
    void lifecyclePublicationRetainsDisabledDefinitionsButExcludesThemFromActiveSelection() throws Exception {
        UUID editor = UUID.randomUUID();
        Instant now = Instant.parse("2026-09-04T12:00:00Z");
        var disabled = new DatabaseService.DefinitionBundle("disabled", "DISABLED", 10, "Disabled",
                "Temporarily unavailable", bytes("icon"), bytes("disabled"), List.of(), List.of(), List.of(), 0, now, now);

        try (DatabaseService database = database()) {
            var draft = database.createOrResumeDefinitionDraft("CRATE", "disabled", editor, "Editor", 0,
                    bytes("draft")).join();
            var result = database.publishDefinitionDraft(new DatabaseService.PublishRequest(draft.draftId(),
                    draft.revision(), draft.leaseToken(), editor, "Editor", draft.payload(), disabled, now)).join();

            assertEquals("DISABLED", result.definition().lifecycle());
            assertEquals(1, result.runtimeRevision());
            var snapshot = database.loadPublishedDefinitions().join();
            assertEquals(1, snapshot.definitions().size());
            assertEquals("DISABLED", snapshot.definitions().getFirst().lifecycle());
            assertEquals(0, database.loadDefinitionDrafts().join().size());
        }
    }

    @Test
    void canonicalKeyDefinitionsAndTemplatesCanBeReadWithoutYaml() throws Exception {
        UUID editor = UUID.randomUUID();
        Instant now = Instant.parse("2026-09-04T12:00:00Z");
        var template = new DatabaseService.DefinitionKeyTemplateData("FALLBACK", 0,
                bytes("exact-key"), "minecraft:tripwire_hook", 9,
                "0123456789012345678901234567890123456789012345678901234567890123", now);
        var key = new DatabaseService.DefinitionKeyData("basic", "CAPTURED", "Basic Key", "RESOLVED",
                false, bytes("match-mode=EXACT\ncache-last-known-good=false\n"), List.of(template));
        var reward = new DatabaseService.DefinitionRewardData("winner", 0, true, "Winner", "common",
                10_000, false, bytes("settings"), List.of(),
                List.of(new DatabaseService.DefinitionActionData(0, "COMMAND", bytes("say winner"))));
        var definition = new DatabaseService.DefinitionBundle("canonical-key", "PUBLISHED", 10, "Canonical",
                "Canonical key", bytes("icon"), bytes("payload"), List.of(reward), List.of(key),
                List.of("basic"), 1, now, now);

        try (DatabaseService database = database()) {
            var draft = database.createOrResumeDefinitionDraft("CRATE", "canonical-key", editor, "Editor", 0,
                    bytes("draft")).join();
            database.publishDefinitionDraft(new DatabaseService.PublishRequest(draft.draftId(), draft.revision(),
                    draft.leaseToken(), editor, "Editor", draft.payload(), definition, now)).join();

            var loaded = database.loadDefinitionKeys().join();
            assertEquals(1, loaded.size());
            assertEquals("basic", loaded.getFirst().keyId());
            assertEquals("FALLBACK", loaded.getFirst().templates().getFirst().templateKind());
            assertArrayEquals(bytes("exact-key"), loaded.getFirst().templates().getFirst().bytes());
        }
    }

    @Test
    void claimInboxIsIdempotentAndInterruptedReservationsMoveToReview() throws Exception {
        UUID player = UUID.randomUUID();
        Instant now = Instant.parse("2026-09-04T12:00:00Z");
        byte[] payload = bytes("exact-claim");
        String fingerprint = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload));
        try (DatabaseService database = database()) {
            var first = database.createItemClaim(player, "OPENING", "tx-1", "basic", "winner", "claim-token",
                    payload, 4, fingerprint, now).join();
            var duplicate = database.createItemClaim(player, "OPENING", "tx-1", "basic", "winner", "claim-token",
                    payload, 4, fingerprint, now.plusSeconds(1)).join();
            assertEquals(first.claimId(), duplicate.claimId());
            assertEquals(1, database.pendingClaimCount(player).join());

            var reserved = database.reserveClaim(player, first.claimId(), "attempt-1").join().orElseThrow();
            assertEquals("CLAIMING", reserved.state());
            assertEquals(1, database.recoverClaimingClaims().join());
            assertEquals("REVIEW", database.loadClaims(player, 10, 0).join().getFirst().state());
            assertTrue(database.reserveClaim(player, first.claimId(), "attempt-2").join().isEmpty());

            // A separate pending entry demonstrates the normal release/retry path.
            var second = database.createItemClaim(player, "ADMIN", "grant-1", null, null, "claim-token-2",
                    payload, 1, fingerprint, now.plusSeconds(2)).join();
            assertTrue(database.reserveClaim(player, second.claimId(), "attempt-3").join().isPresent());
            assertTrue(database.releaseClaim(second.claimId(), "attempt-3", "manual retry").join());
            assertTrue(database.reserveClaim(player, second.claimId(), "attempt-4").join().isPresent());
            assertTrue(database.completeClaim(second.claimId(), "attempt-4").join().isPresent());
            assertEquals("CLAIMED", database.completeClaim(second.claimId(), "attempt-4").join().orElseThrow().state());
            var counts = database.claimCounts().join();
            assertEquals(0, counts.pending());
            assertEquals(0, counts.claiming());
            assertEquals(1, counts.claimed());
            assertEquals(1, counts.review());
        }
    }

    @Test
    void journalAndOpeningFinalizationAreIdempotent() throws Exception {
        UUID transaction = UUID.randomUUID();
        UUID player = UUID.randomUUID();
        Instant now = Instant.parse("2026-09-04T12:00:00Z");
        var journal = new DatabaseService.JournalRecord(transaction, player, "Player", "basic", "basic",
                1, 1, "COMMAND", "winner", now);
        var opening = new DatabaseService.OpeningRecord(transaction, player, "Player", "basic", "basic",
                1, 1, "COMMAND", "winner", "world:1,2,3", 0,
                "outcomes[0:source=limited,actual=winner,fallback=true,reason=PLAYER_LIMIT]",
                now.plusSeconds(1));
        try (DatabaseService database = database()) {
            database.prepareJournal(journal).join();
            database.prepareJournal(journal).join();
            database.completeOpening(opening).join();
            database.completeOpening(opening).join();

            assertEquals(1, database.history(player, 10, 0).size());
            assertEquals(opening.outcomeDetail(), database.history(player, 10, 0).getFirst().outcomeDetail());
            assertEquals(1, database.loadStatistics().global().get("basic"));
            assertEquals(1, database.loadStatistics().players().get(player).get("basic"));
            assertEquals(0, database.pendingJournalCount());
        }
    }

    @Test
    void virtualKeyLedgerIsAtomicAndIdempotentWithoutOverdrafts() throws Exception {
        UUID player = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        try (DatabaseService database = database()) {
            var grant = database.creditVirtualKeys(player, "basic", 5, "grant-1", "ADMIN", "ticket-1", actor).join();
            assertTrue(grant.applied());
            assertEquals(5, grant.balanceAfter());
            var duplicateGrant = database.creditVirtualKeys(player, "basic", 5, "grant-1", "ADMIN", "ticket-1", actor).join();
            assertEquals(grant.entryId(), duplicateGrant.entryId());
            assertEquals(5, database.loadVirtualKeyBalance(player, "basic").join().balance());

            var insufficient = database.debitVirtualKeys(player, "basic", 6, "debit-too-much", "OPENING", "tx-1", null).join();
            assertFalse(insufficient.applied());
            assertEquals(5, insufficient.balanceAfter());
            var debit = database.debitVirtualKeys(player, "basic", 3, "debit-1", "OPENING", "tx-2", null).join();
            assertTrue(debit.applied());
            assertEquals(2, debit.balanceAfter());
            var duplicateDebit = database.debitVirtualKeys(player, "basic", 3, "debit-1", "OPENING", "tx-2", null).join();
            assertEquals(debit.entryId(), duplicateDebit.entryId());
            assertEquals(2, database.loadVirtualKeyBalance(player, "basic").join().balance());
            assertThrows(CompletionException.class, () -> database.creditVirtualKeys(player, "basic", 1,
                    "debit-1", "OTHER", "different", actor).join());

            var claim = database.createVirtualKeyClaim(player, "MILESTONE", "milestone-1", "basic", null,
                    "claim-virtual-1", "basic", 2, java.time.Instant.now()).join();
            assertEquals("basic", claim.virtualKeyId());
            assertEquals(2, claim.virtualKeyAmount());
            assertEquals("PENDING", claim.state());
        }
    }

    @Test
    void virtualKeyDebitRejectsAChangedFrozenRevision() throws Exception {
        UUID player = UUID.randomUUID();
        try (DatabaseService database = database()) {
            database.creditVirtualKeys(player, "basic", 5, "revision-grant-1",
                    "TEST", "revision", null).join();
            long frozen = database.loadVirtualKeyBalance(player, "basic").join().revision();
            database.creditVirtualKeys(player, "basic", 1, "revision-grant-2",
                    "TEST", "revision", null).join();

            var rejected = database.debitVirtualKeys(player, "basic", 1,
                    "revision-debit", "OPENING", "tx-revision", null, frozen).join();

            assertFalse(rejected.applied());
            assertEquals(6, database.loadVirtualKeyBalance(player, "basic").join().balance());
        }
    }

    @Test
    void portableSecretAndSingleUseIssuanceSurviveRestart() throws Exception {
        UUID issueId = UUID.randomUUID();
        UUID outstandingIssueId = UUID.randomUUID();
        UUID player = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        Instant issuedAt = Instant.parse("2026-09-04T12:00:00Z");
        byte[] secret;

        try (DatabaseService database = database()) {
            assertFalse(database.portableSecretPresent().join());
            secret = database.loadOrCreatePortableSecret().join();
            assertEquals(32, secret.length);
            assertTrue(database.portableSecretPresent().join());
            assertArrayEquals(secret, database.loadOrCreatePortableSecret().join());

            var reward = new DatabaseService.DefinitionRewardData(
                    "winner", 0, true, "Winner", "common", 10_000, false, bytes("settings"),
                    List.of(), List.of(new DatabaseService.DefinitionActionData(
                            0, "COMMAND", bytes("say portable"))));
            var definition = new DatabaseService.DefinitionBundle("portable", "PUBLISHED", 10,
                    "Portable", "Portable restart test", bytes("icon"), bytes("payload"),
                    List.of(reward), List.of(), List.of(), 0, issuedAt, issuedAt);
            var draft = database.createOrResumeDefinitionDraft(
                    "CRATE", "portable", actor, "Admin", 0, bytes("draft")).join();
            database.publishDefinitionDraft(new DatabaseService.PublishRequest(
                    draft.draftId(), draft.revision(), draft.leaseToken(), actor, "Admin",
                    draft.payload(), definition, issuedAt)).join();

            var issue = new DatabaseService.PortableIssue(
                    issueId, "portable", "LATEST_PUBLISHED", 0, player, actor,
                    1, "UNUSED", null, issuedAt, issuedAt);
            assertEquals("UNUSED", database.createPortableIssue(issue).join().state());
            assertEquals("RESERVED", database.reservePortableIssue(issueId, "before-restart")
                    .join().orElseThrow().state());
            assertTrue(database.reservePortableIssue(issueId, "competing-attempt").join().isEmpty());
        }

        try (DatabaseService database = database()) {
            assertArrayEquals(secret, database.loadOrCreatePortableSecret().join());
            assertEquals(1, database.recoverPortableReservations().join());
            assertEquals("UNUSED", database.loadPortableIssue(issueId).join().orElseThrow().state());

            assertEquals("RESERVED", database.reservePortableIssue(issueId, "after-restart")
                    .join().orElseThrow().state());
            assertTrue(database.consumePortableIssue(issueId, "wrong-token").join().isEmpty());
            assertEquals("CONSUMED", database.consumePortableIssue(issueId, "after-restart")
                    .join().orElseThrow().state());
            assertTrue(database.reservePortableIssue(issueId, "replay").join().isEmpty());

            var counts = database.portableIssueCounts().join();
            assertEquals(0, counts.unused());
            assertEquals(0, counts.reserved());
            assertEquals(1, counts.consumed());

            var outstanding = new DatabaseService.PortableIssue(
                    outstandingIssueId, "portable", "LATEST_PUBLISHED", 0, player, actor,
                    1, "UNUSED", null, issuedAt.plusSeconds(1), issuedAt.plusSeconds(1));
            database.createPortableIssue(outstanding).join();
        }

        try (var connection = java.sql.DriverManager.getConnection(
                "jdbc:sqlite:" + temporary.resolve("data/test.db"))) {
            assertEquals(1, connection.createStatement()
                    .executeUpdate("DELETE FROM plugin_secret WHERE secret_id='portable-hmac-v1'"));
        }
        try (DatabaseService database = database()) {
            assertFalse(database.portableSecretPresent().join());
            assertThrows(CompletionException.class,
                    () -> database.loadOrCreatePortableSecret().join());
            assertEquals("UNUSED", database.loadPortableIssue(outstandingIssueId)
                    .join().orElseThrow().state());
        }
    }

    @Test
    void archivedCanonicalDefinitionCanBeDeletedTransactionally() throws Exception {
        UUID editor = UUID.randomUUID();
        Instant now = Instant.parse("2026-09-04T12:00:00Z");
        var archived = new DatabaseService.DefinitionBundle("archived", "ARCHIVED", 10, "Archived",
                "Retained for history", bytes("icon"), bytes("archived"), List.of(), List.of(), List.of(), 0, now, now);

        try (DatabaseService database = database()) {
            var draft = database.createOrResumeDefinitionDraft("CRATE", "archived", editor, "Editor", 0,
                    bytes("draft")).join();
            database.publishDefinitionDraft(new DatabaseService.PublishRequest(draft.draftId(), draft.revision(),
                    draft.leaseToken(), editor, "Editor", draft.payload(), archived, now)).join();

            var deleted = database.deleteDefinition("archived", editor, "Editor").join();
            assertTrue(deleted.removed());
            assertEquals(1, deleted.definitionRevision());
            assertEquals(2, deleted.runtimeRevision());
            assertTrue(database.loadPublishedDefinitions().join().definitions().isEmpty());
        }
    }

    @Test
    void journalCompletionAtomicallyPersistsHistoryStatisticsLimitsAndPity() throws Exception {
        UUID player = UUID.randomUUID();
        UUID transaction = UUID.randomUUID();
        Instant created = Instant.parse("2026-09-01T12:00:00Z");
        try (DatabaseService database = database()) {
            database.prepareJournal(new DatabaseService.JournalRecord(transaction, player, "Tonim", "basic",
                    "basic", 1, 1, "COMMAND", "diamond", created)).join();
            assertEquals(1, database.pendingJournalCount());

            DatabaseService.RewardPlayerState playerState = new DatabaseService.RewardPlayerState(
                    player, "basic", "diamond", 1, 1, created.toEpochMilli(), created.toEpochMilli());
            DatabaseService.RewardGlobalState globalState = new DatabaseService.RewardGlobalState(
                    "basic", "diamond", 1, 1, created.toEpochMilli());
            DatabaseService.RewardStateCommit state = new DatabaseService.RewardStateCommit(
                    List.of(new DatabaseService.RewardMutation(playerState, globalState)),
                    new DatabaseService.PityState(player, "basic", 2));
            DatabaseService.OpeningRecord opening = new DatabaseService.OpeningRecord(transaction, player,
                    "Tonim", "basic", "basic", 1, 1, "COMMAND", "diamond", "world:1,2,3", 0,
                    created.plusSeconds(1));

            database.completeOpening(opening, state).join();

            assertEquals(List.of(opening), database.history(player, 10, 0));
            assertEquals(1, database.loadStatistics().global().get("basic"));
            assertEquals(1, database.loadStatistics().players().get(player).get("basic"));
            assertEquals(List.of(playerState), database.loadRewardStates().players());
            assertEquals(List.of(globalState), database.loadRewardStates().global());
            assertEquals(List.of(new DatabaseService.PityState(player, "basic", 2)),
                    database.loadRewardStates().pity());
            assertEquals(0, database.pendingJournalCount());
        }
    }

    @Test
    void openingFinalizationAtomicallyPersistsMilestoneProgressAndClaimOnce() throws Exception {
        UUID player = UUID.randomUUID();
        UUID transaction = UUID.randomUUID();
        Instant created = Instant.parse("2026-09-05T12:00:00Z");
        byte[] item = bytes("exact-milestone-item");
        String fingerprint = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(item));
        try (DatabaseService database = database()) {
            database.prepareJournal(new DatabaseService.JournalRecord(transaction, player, "Tonim", "basic",
                    "basic", 1, 1, "COMMAND", "diamond", created)).join();
            var milestoneState = new DatabaseService.MilestoneState(player, "basic", 1, 0, 1,
                    bytes("first_open#0"), created.plusSeconds(1));
            var milestoneClaim = new DatabaseService.MilestoneItemClaim(UUID.randomUUID(),
                    "milestone:" + transaction + ":first_open#0:0", "first_open#0", "diamond",
                    item, 1, fingerprint, false, created.plusSeconds(1));
            var milestones = new DatabaseService.MilestoneProgressCommit(
                    milestoneState, 0, List.of(milestoneClaim));
            var opening = new DatabaseService.OpeningRecord(transaction, player, "Tonim", "basic", "basic",
                    1, 1, "COMMAND", "diamond", "", 0, created.plusSeconds(1));

            database.completeOpening(opening, DatabaseService.RewardStateCommit.empty(), milestones).join();
            database.completeOpening(opening, DatabaseService.RewardStateCommit.empty(), milestones).join();

            assertEquals(1, database.history(player, 10, 0).size());
            assertEquals(1, database.loadMilestoneState(player, "basic").join().openings());
            assertArrayEquals(bytes("first_open#0"),
                    database.loadMilestoneState(player, "basic").join().earnedPayload());
            assertEquals(1, database.loadClaims(player, 10, 0).join().size());
            assertEquals(1, database.pendingClaimCount(player).join());
        }
    }

    @Test
    void legacyImportMarkerMakesRetryIdempotent() throws Exception {
        UUID player = UUID.randomUUID();
        UUID world = UUID.randomUUID();
        DatabaseService.StoredLocation location = new DatabaseService.StoredLocation(world, "Survival_World",
                4, 70, -9, "basic", Instant.parse("2026-09-01T12:00:00Z"));
        DatabaseService.StatsSnapshot statistics = new DatabaseService.StatsSnapshot(
                Map.of("basic", 12L), Map.of(player, Map.of("basic", 7L)));
        try (DatabaseService database = database()) {
            database.importLegacy("test-marker", List.of(location), statistics);
            database.importLegacy("test-marker", List.of(location), statistics);

            assertEquals(List.of(location), database.loadLocations());
            assertEquals(12L, database.loadStatistics().global().get("basic"));
            assertEquals(7L, database.loadStatistics().players().get(player).get("basic"));
        }
    }

    @Test
    void failedLegacyFileCommitRollsBackDatabaseAndMarker() throws Exception {
        DatabaseService.StoredLocation location = new DatabaseService.StoredLocation(null, "Survival_World",
                8, 64, 8, "rare", Instant.parse("2026-09-01T12:00:00Z"));
        DatabaseService.StatsSnapshot statistics = new DatabaseService.StatsSnapshot(
                Map.of("rare", 4L), Map.of());
        try (DatabaseService database = database()) {
            assertThrows(IOException.class, () -> database.importLegacy("rollback-marker", List.of(location),
                    statistics, () -> { throw new IOException("simulated YAML failure"); }));
            assertTrue(database.loadLocations().isEmpty());
            assertTrue(database.loadStatistics().global().isEmpty());

            database.importLegacy("rollback-marker", List.of(location), statistics);
            assertEquals(List.of(location), database.loadLocations());
            assertEquals(4L, database.loadStatistics().global().get("rare"));
        }
    }

    @Test
    void historyPaginationIsBoundedAndNewestFirst() throws Exception {
        UUID player = UUID.randomUUID();
        try (DatabaseService database = database()) {
            for (int index = 0; index < 3; index++) {
                UUID transaction = UUID.randomUUID();
                Instant completed = Instant.ofEpochMilli(1_000L + index);
                database.prepareJournal(new DatabaseService.JournalRecord(transaction, player, "Player", "basic",
                        "basic", 1, 1, "GUI", "reward_" + index, completed)).join();
                database.completeOpening(new DatabaseService.OpeningRecord(transaction, player, "Player", "basic",
                        "basic", 1, 1, "GUI", "reward_" + index, "", 0, completed)).join();
            }

            List<DatabaseService.OpeningRecord> page = database.historyAsync(player, 2, 1).join();

            assertEquals(2, page.size());
            assertEquals(List.of("reward_1", "reward_0"), page.stream().map(DatabaseService.OpeningRecord::rewardIds).toList());
            assertTrue(database.history(player, 10_000, 0).size() <= 100);
        }
    }

    private DatabaseService database() throws Exception {
        return new DatabaseService(Logger.getLogger("DatabaseServiceTest"), temporary.resolve("data/test.db"), 64);
    }

    private static DraftMutation mutation(
            com.antondev.crates.domain.draft.DefinitionDraft draft, UUID actor, String payload, Instant now) {
        return new DraftMutation(draft.revision(), draft.leaseToken(), actor, "EDIT", "Edit " + payload,
                bytes(payload), "UNVALIDATED", now);
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static String text(byte[] value) {
        return new String(value, StandardCharsets.UTF_8);
    }
}
