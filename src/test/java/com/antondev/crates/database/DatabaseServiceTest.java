package com.antondev.crates.database;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.antondev.crates.domain.draft.DraftMutation;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
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
