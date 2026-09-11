from pathlib import Path


def patch(path, edits):
    p = Path(path)
    text = p.read_text(encoding='utf-8')
    for label, old, new, expected in edits:
        count = text.count(old)
        if count != expected:
            raise SystemExit(f'{path} / {label}: expected {expected} matches, found {count}')
        text = text.replace(old, new)
    p.write_text(text, encoding='utf-8')

patch('src/main/java/com/antondev/crates/database/DatabaseService.java', [
    ('typed grant gate',
     """                    WHERE transaction_id=? AND journal_version >= 2 AND stage='CONSUMED'\n""",
     """                    WHERE transaction_id=? AND journal_version >= 2\n                      AND payment_state IN ('COMMITTED','NOT_REQUIRED')\n                      AND grant_state='NOT_STARTED'\n                      AND stage NOT IN ('COMPLETED','CANCELLED')\n""", 1),
])

patch('src/main/java/com/antondev/crates/integration/plexonkeys/PlexonKeysServiceAdapter.java', [
    ('adapter docs', 'Direct adapter for the stable PlexonKeys 1.2 Bukkit service API.',
     'Direct adapter for the stable PlexonKeys 2.0 Bukkit service API.', 1),
    ('availability helper',
     """    /** Reads the authoritative PlexonKeys wallet on the provider's required primary thread. */\n""",
     """    /** True only when the runtime service needed for authoritative wallet operations is registered. */\n    public static boolean available(JavaPlugin owner) {\n        return owner.getServer().getServicesManager().getRegistration(PlexonKeysAPI.class) != null;\n    }\n\n    /** Reads the authoritative PlexonKeys wallet on the provider's required primary thread. */\n""", 1),
])

patch('src/main/java/com/antondev/crates/service/KeyService.java', [
    ('wallet requires live service',
     """                && definition.source() == KeySource.PLEXONKEYS\n                && plugin.settings().plexonKeysEnabled();\n""",
     """                && definition.source() == KeySource.PLEXONKEYS\n                && plugin.settings().plexonKeysEnabled()\n                && PlexonKeysServiceAdapter.available(plugin);\n""", 1),
])

# Reroll/stop intermediate states have payment committed but grant certainty or user choice is not
# reconstructible. Preserve them as explicit MANUAL_REVIEW across restart rather than inheriting a
# misleading PAYMENT_COMMITTED classification.
opening_path = Path('src/main/java/com/antondev/crates/service/OpeningService.java')
opening = opening_path.read_text(encoding='utf-8')
for stage in ('AWAITING_DECISION', 'REROLL_COST_RESERVED', 'REROLL_COST_CONSUMED', 'REROLL_ACCEPTED'):
    opening = opening.replace(f'plugin.database().updateJournal(transactionId, "{stage}"',
                              f'plugin.database().markManualReview(transactionId, "{stage}"')
    opening = opening.replace(f'plugin.database().updateJournal(decision.transactionId, "{stage}"',
                              f'plugin.database().markManualReview(decision.transactionId, "{stage}"')
opening = opening.replace('plugin.database().updateJournal(decision.transactionId, "FAILED",',
                          'plugin.database().markManualReview(decision.transactionId, "FAILED",')
opening = opening.replace('plugin.database().updateJournal(entry.getKey(), "FAILED",',
                          'plugin.database().markManualReview(entry.getKey(), "FAILED",')
opening_path.write_text(opening, encoding='utf-8')

# Safe diagnose output includes timestamps but still excludes player, serialized reward and inventory data.
plugin_path = Path('src/main/java/com/antondev/crates/PlexonCrates.java')
plugin = plugin_path.read_text(encoding='utf-8')
old = '''                    + "</white> <gray>grant=</gray><white>" + entry.grantState()\n                    + "</white> <gray>recovery=</gray><white>" + entry.recoveryClassification() + "</white>"));'''
new = '''                    + "</white> <gray>grant=</gray><white>" + entry.grantState()\n                    + "</white> <gray>recovery=</gray><white>" + entry.recoveryClassification()\n                    + "</white> <gray>created=</gray><white>" + entry.createdAt()\n                    + "</white> <gray>updated=</gray><white>" + entry.updatedAt() + "</white>"));'''
if plugin.count(old) != 1:
    raise SystemExit('diagnostic timestamp insertion did not match exactly once')
plugin_path.write_text(plugin.replace(old, new, 1), encoding='utf-8')

# Intentional schema/release boundary updates in legacy contract tests.
patch('src/test/java/com/antondev/crates/database/DatabaseServiceTest.java', [
    ('schema expectation', 'assertEquals(3, database.schemaVersion());',
     'assertEquals(4, database.schemaVersion());', 1),
])
patch('src/test/java/com/antondev/crates/Phase2SimulationContractTest.java', [
    ('candidate workflow expectation', "PLUGIN_VERSION: '5.0.0-rc.1'",
     "PLUGIN_VERSION: '5.0.0-rc.2'", 1),
])

Path('src/test/java/com/antondev/crates/Phase3RecoveryStateTest.java').write_text(r'''package com.antondev.crates;

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
''', encoding='utf-8')

print('Applied typed recovery, provider availability, diagnostics and contract-test corrections.')
