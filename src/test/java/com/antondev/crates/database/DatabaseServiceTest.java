package com.antondev.crates.database;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DatabaseServiceTest {
    @TempDir
    Path temporary;

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
}
