package com.antondev.crates.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.antondev.crates.database.DatabaseService;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DraftSessionServiceTest {
    @TempDir
    Path temporary;

    @Test
    void ordersRapidSavesAndInvalidatesThePreviousOwnerAfterTakeover() throws Exception {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        try (DatabaseService database = database()) {
            DraftSessionService sessions = new DraftSessionService(database, (actor, crate, view) -> { });
            var firstView = sessions.openCrate(first, "First", "basic", 0, bytes("zero")).join();
            var secondView = sessions.openCrate(second, "Second", "basic", 0, bytes("ignored")).join();

            assertEquals(DraftSessionService.State.SAVED, firstView.state());
            assertTrue(firstView.writable());
            assertEquals(DraftSessionService.State.READ_ONLY, secondView.state());
            assertFalse(secondView.writable());

            var one = sessions.saveCrate(first, "basic", "EDIT", "First edit", bytes("one"));
            var two = sessions.saveCrate(first, "basic", "EDIT", "Second edit", bytes("two"));
            assertEquals(DraftSessionService.State.SAVING, sessions.view(first, "basic").orElseThrow().state());
            one.join();
            var saved = two.join();
            assertEquals(2, saved.revision());
            assertEquals("two", text(database.loadDefinitionDraft("CRATE", "basic").join().orElseThrow().payload()));

            var taken = sessions.takeoverCrate(second, "basic").join();
            assertTrue(taken.writable());
            assertEquals(2, taken.leaseToken());
            assertEquals(DraftSessionService.State.READ_ONLY,
                    sessions.view(first, "basic").orElseThrow().state());
            assertThrows(CompletionException.class, () -> sessions.saveCrate(first, "basic", "EDIT",
                    "Stale owner edit", bytes("bad")).join());

            sessions.saveCrate(second, "basic", "EDIT", "New owner edit", bytes("three")).join();
            var undone = sessions.undoCrate(second, "basic").join();
            assertEquals("two", text(database.loadDefinitionDraft("CRATE", "basic").join().orElseThrow().payload()));
            assertEquals(DraftSessionService.State.SAVED, undone.state());
            assertEquals(4, undone.revision());
        }
    }

    private DatabaseService database() throws Exception {
        return new DatabaseService(Logger.getLogger("DraftSessionServiceTest"), temporary.resolve("data/test.db"), 64);
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static String text(byte[] value) {
        return new String(value, StandardCharsets.UTF_8);
    }
}
