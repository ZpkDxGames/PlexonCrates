package com.antondev.crates;

import static org.junit.jupiter.api.Assertions.*;

import com.antondev.crates.database.DatabaseService;
import com.antondev.crates.service.OpeningService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Instant;
import java.util.UUID;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class Phase3OpeningReliabilityTest {
    @TempDir Path temp;

    @Test
    void stablePlexonKeysTransactionIdentityIsDeterministic() {
        UUID opening = UUID.randomUUID();
        String first = OpeningService.plexonKeysPaymentTransactionId(opening);
        String second = OpeningService.plexonKeysPaymentTransactionId(opening);
        assertEquals(first, second);
        assertEquals("plexoncrates:opening:" + opening, first);
        assertTrue(first.length() <= 128);
    }

    @Test
    void schemaThreeMigrationPreservesLegacyUnresolvedAsManualReviewAndCreatesBackup() throws Exception {
        Path db = temp.resolve("legacy.db");
        Class.forName("org.sqlite.JDBC");
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db); Statement s = c.createStatement()) {
            s.executeUpdate("CREATE TABLE schema_meta(key TEXT PRIMARY KEY, value TEXT NOT NULL)");
            s.executeUpdate("INSERT INTO schema_meta VALUES('schema_version','3')");
            s.executeUpdate("CREATE TABLE opening_journal(transaction_id TEXT PRIMARY KEY, player_uuid TEXT NOT NULL, player_name TEXT NOT NULL, crate_id TEXT NOT NULL, key_id TEXT NOT NULL, key_amount INTEGER NOT NULL, opening_count INTEGER NOT NULL, source TEXT NOT NULL, reward_ids TEXT NOT NULL, stage TEXT NOT NULL, detail TEXT NOT NULL DEFAULT '', created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL)");
            String id = UUID.randomUUID().toString();
            s.executeUpdate("INSERT INTO opening_journal VALUES('" + id + "','" + UUID.randomUUID() + "','P','basic','basic',1,1,'GUI','winner','PREPARED','legacy',1,1)");
        }
        try (DatabaseService database = new DatabaseService(Logger.getAnonymousLogger(), db, 64)) {
            assertEquals(4, database.schemaVersion());
            assertEquals(1, database.pendingJournalCount());
            var row = database.journalDiagnostics(5).getFirst();
            assertEquals("LEGACY_UNKNOWN", row.paymentState());
            assertEquals("LEGACY_UNKNOWN", row.grantState());
            assertEquals("MANUAL_REVIEW", row.recoveryClassification());
        }
        assertTrue(Files.isRegularFile(temp.resolve("legacy.db.pre-v4.bak")));
    }

    @Test
    void newPreparedOpeningIsSafelyCancelledOnRestartBeforePayment() throws Exception {
        Path db = temp.resolve("new.db");
        UUID tx = UUID.randomUUID();
        UUID player = UUID.randomUUID();
        try (DatabaseService database = new DatabaseService(Logger.getAnonymousLogger(), db, 64)) {
            database.prepareJournal(new DatabaseService.JournalRecord(tx, player, "P", "basic", "basic", 1, 1,
                    "GUI", "winner", 7L, "PHYSICAL", "", Instant.now()), "test").join();
            assertEquals(1, database.pendingJournalCount());
        }
        try (DatabaseService database = new DatabaseService(Logger.getAnonymousLogger(), db, 64)) {
            assertEquals(0, database.pendingJournalCount());
            assertEquals(1, database.startupRecoveredJournals());
        }
    }

    @Test
    void paymentAndGrantTransitionsRemainManualUntilTerminalCommit() throws Exception {
        Path db = temp.resolve("state.db");
        UUID tx = UUID.randomUUID();
        UUID player = UUID.randomUUID();
        try (DatabaseService database = new DatabaseService(Logger.getAnonymousLogger(), db, 64)) {
            database.prepareJournal(new DatabaseService.JournalRecord(tx, player, "P", "basic", "basic", 1, 1,
                    "GUI", "winner", 9L, "PLEXONKEYS", "plexoncrates:opening:" + tx, Instant.now()), "test").join();
            database.markPaymentAttempted(tx, "PLEXONKEYS", "plexoncrates:opening:" + tx, "attempt").join();
            var attempted = database.journalDiagnostics(5).getFirst();
            assertEquals("ATTEMPTED", attempted.paymentState());
            assertEquals("MANUAL_REVIEW", attempted.recoveryClassification());
            database.markPaymentCommitted(tx, "committed").join();
            var committed = database.journalDiagnostics(5).getFirst();
            assertEquals("COMMITTED", committed.paymentState());
            assertEquals("PAYMENT_COMMITTED", committed.recoveryClassification());
            database.markGrantAttempted(tx, "grant").join();
            var grant = database.journalDiagnostics(5).getFirst();
            assertEquals("ATTEMPTED", grant.grantState());
            assertEquals("MANUAL_REVIEW", grant.recoveryClassification());
        }
    }

    @Test
    void terminalCompactionNeverDeletesUnresolvedEvidence() throws Exception {
        Path db = temp.resolve("retention.db");
        UUID tx = UUID.randomUUID();
        try (DatabaseService database = new DatabaseService(Logger.getAnonymousLogger(), db, 64)) {
            database.prepareJournal(new DatabaseService.JournalRecord(tx, UUID.randomUUID(), "P", "basic", "basic",
                    1, 1, "GUI", "winner", 1L, "PHYSICAL", "", Instant.EPOCH), "old unresolved").join();
            database.markPaymentAttempted(tx, "PHYSICAL", "", "uncertain").join();
        }
        try (DatabaseService database = new DatabaseService(Logger.getAnonymousLogger(), db, 64)) {
            assertEquals(1, database.pendingJournalCount());
            assertEquals("MANUAL_REVIEW", database.journalDiagnostics(5).getFirst().recoveryClassification());
        }
    }

    @Test
    void sourceContainsNoWorldDropFallbackForClaimPersistenceFailure() throws Exception {
        String source = Files.readString(Path.of("src/main/java/com/antondev/crates/service/OpeningService.java"));
        assertFalse(source.contains("dropping the unchanged stack"));
        assertTrue(source.contains("no world-drop recovery was attempted"));
    }

    @Test
    void sourcePersistsGrantAttemptBeforeCallingFinishDelivery() throws Exception {
        String source = Files.readString(Path.of("src/main/java/com/antondev/crates/service/OpeningService.java"));
        int barrier = source.indexOf("markGrantAttempted(transactionId");
        int delivery = source.indexOf("finishDelivery(transactionId, opening, player, current, bypassLimits,");
        assertTrue(barrier >= 0 && delivery > barrier);
    }

    @Test
    void creditedVirtualKeyClaimMovesToReviewWhenFinalizationIsUncertain() throws Exception {
        String source = Files.readString(Path.of("src/main/java/com/antondev/crates/service/ClaimService.java"));
        int completion = source.indexOf("completeClaim(claim.claimId(), attempt)");
        int uncertainty = source.indexOf("Virtual-key credit completed but claim finalization was uncertain");
        int review = source.lastIndexOf("markClaimReview(claim.claimId(), attempt", uncertainty);
        assertTrue(completion >= 0);
        assertTrue(uncertainty > completion);
        assertTrue(review > completion && review < uncertainty,
                "A credited virtual-key claim must be moved to REVIEW before warning the player");
    }
}
