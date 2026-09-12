package com.antondev.crates;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class LiveKeyMutationIoBoundaryArchitectureTest {
    private static final Path COORDINATOR = Path.of(
            "src/main/java/com/antondev/crates/service/KeyMutationService.java");
    private static final Path KEYS = Path.of(
            "src/main/java/com/antondev/crates/service/KeyService.java");
    private static final Path ADMIN = Path.of(
            "src/main/java/com/antondev/crates/gui/AdminMenuService.java");

    @Test
    void liveMutationsAreSingleFlight() throws Exception {
        String source = Files.readString(COORDINATOR);
        assertTrue(source.contains("private final AtomicBoolean busy = new AtomicBoolean()"));
        assertTrue(source.contains("if (!busy.compareAndSet(false, true)) return busyFailure()"));
        assertTrue(source.contains("whenComplete((ignored, error) -> busy.set(false))"));
        assertTrue(source.contains("Another key-registry mutation is already in progress"));
    }

    @Test
    void ordinaryMutationPersistsMirrorBeforePrimaryActivation() throws Exception {
        String source = Files.readString(COORDINATOR);
        String commit = section(source, "private CompletableFuture<Void> commit(",
                "private <T> CompletableFuture<T> primary(");
        int write = commit.indexOf("plugin.io().run(() -> plugin.keys().writePreparedMutation(prepared))");
        int primary = commit.indexOf("thenCompose(ignored -> primary(() -> {");
        int install = commit.indexOf("plugin.keys().installPreparedMutation(prepared)");
        assertTrue(write >= 0 && primary > write && install > primary);
    }

    @Test
    void keyImportReadsSourceAndWritesMirrorOffThreadButParsesAndInstallsOnPrimary() throws Exception {
        String source = Files.readString(COORDINATOR);
        String method = section(source, "public CompletableFuture<List<String>> importDefinitions(",
                "private CompletableFuture<Void> commit(");
        int read = method.indexOf("plugin.io().submit(() -> {");
        int parse = method.indexOf("thenCompose(payload -> primary(() -> plugin.keys().prepareImport(");
        int write = method.indexOf("plugin.io().run(() -> plugin.keys().writePreparedMutation(prepared.mutation()))");
        int install = method.indexOf("plugin.keys().installPreparedMutation(prepared.mutation())");
        assertTrue(read >= 0 && parse > read && write > parse && install > write);
        assertTrue(method.contains("Files.readString(source, StandardCharsets.UTF_8)"));
    }

    @Test
    void preparedKeyOperationsContainNoFilesystemIo() throws Exception {
        String source = Files.readString(KEYS);
        String prepared = section(source, "public PreparedMutation prepareCreateCaptured(",
                "public Optional<KeyDefinition> definition(PreparedMutation prepared");
        assertTrue(prepared.contains("prepareBindExternal("));
        assertTrue(prepared.contains("prepareReplaceCaptured("));
        assertTrue(prepared.contains("prepareDelete("));
        assertTrue(prepared.contains("prepareImport("));
        assertFalse(prepared.contains("Files."));
        assertFalse(prepared.contains("AtomicFiles."));
    }

    @Test
    void liveAdminKeyWritesUseCoordinatorRatherThanSynchronousCompatibilityMethods() throws Exception {
        String source = Files.readString(ADMIN);
        String importKeys = section(source, "private void importKeys(Player player)", "private void createReward(");
        String keyEntry = section(source, "private void keyEntry(Player player", "private void replaceKeyReferences(");
        String delete = section(source, "private void confirmKeyDelete(", "private long publishedKeyReferences(");
        String select = section(source, "private void selectKey(", "private void editDraftName(");
        String confirm = section(source, "private void confirmDraft(", "private void cancelDraft(");

        assertTrue(importKeys.contains("plugin.keyMutations().importDefinitions("));
        assertFalse(importKeys.contains("plugin.keys().importDefinitions("));
        assertTrue(keyEntry.contains("plugin.keyMutations().bindExternal("));
        assertFalse(keyEntry.contains("plugin.keys().bindExternal("));
        assertTrue(delete.contains("plugin.keyMutations().delete("));
        assertFalse(delete.contains("plugin.keys().delete("));
        assertTrue(select.contains("plugin.keyMutations().bindExternal("));
        assertFalse(select.contains("plugin.keys().bindExternal("));
        assertTrue(confirm.contains("plugin.keyMutations().replaceCaptured("));
        assertTrue(confirm.contains("plugin.keyMutations().createCaptured("));
        assertFalse(confirm.contains("plugin.keys().replaceCaptured("));
        assertFalse(confirm.contains("plugin.keys().createCaptured("));
    }

    private static String section(String source, String start, String end) {
        int from = source.indexOf(start);
        int to = source.indexOf(end, from + start.length());
        if (from < 0 || to < 0) throw new AssertionError("Could not isolate source section " + start);
        return source.substring(from, to);
    }
}
