package com.antondev.crates;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.antondev.crates.database.DatabaseService;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class Phase3RecoveryStateTest {
    @TempDir Path temporary;

    @Test
    void grantBarrierUsesTypedPaymentStateAcrossRerollStage() throws Exception {
        UUID transaction = UUID.randomUUID();
        try (DatabaseService database = new DatabaseService(Logger.getAnonymousLogger(), temporary.resolve("reroll.db"), 64)) {
            database.prepareJournal(new DatabaseService.JournalRecord(transaction, UUID.randomUUID(), "P", "basic",
                    "basic", 1, 1, "GUI", "winner", 4L, "PHYSICAL", "", Instant.now()), "test").join();
            database.markPaymentAttempted(transaction, "PHYSICAL", "", "attempt").join();
            database.markPaymentCommitted(transaction, "committed").join();
            database.markManualReview(transaction, "AWAITING_DECISION", "choice pending").join();
            database.markGrantAttempted(transaction, "grant").join();
            var state = database.journalDiagnostics(5).getFirst();
            assertEquals("GRANT_ATTEMPTED", state.stage());
            assertEquals("COMMITTED", state.paymentState());
            assertEquals("ATTEMPTED", state.grantState());
            assertEquals("MANUAL_REVIEW", state.recoveryClassification());
        }
    }

    @Test
    void freeOpeningMayCrossGrantBarrierOnlyFromNotRequiredPayment() throws Exception {
        UUID transaction = UUID.randomUUID();
        try (DatabaseService database = new DatabaseService(Logger.getAnonymousLogger(), temporary.resolve("free.db"), 64)) {
            database.prepareJournal(new DatabaseService.JournalRecord(transaction, UUID.randomUUID(), "P", "basic",
                    "", 0, 1, "ADMIN_FORCE", "winner", 4L, "NONE", "", Instant.now()), "test").join();
            database.markPaymentCommitted(transaction, "no payment required").join();
            database.markGrantAttempted(transaction, "grant").join();
            var state = database.journalDiagnostics(5).getFirst();
            assertEquals("NOT_REQUIRED", state.paymentState());
            assertEquals("ATTEMPTED", state.grantState());
        }
    }
}
