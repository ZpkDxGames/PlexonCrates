package com.antondev.crates;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class DraftCreationDurabilityArchitectureTest {
    private static final Path COORDINATOR = Path.of(
            "src/main/java/com/antondev/crates/service/CrateDraftCreationService.java");
    private static final Path REGISTRY = Path.of(
            "src/main/java/com/antondev/crates/service/CrateRegistry.java");
    private static final Path COMMAND = Path.of(
            "src/main/java/com/antondev/crates/command/CratesAdminCommand.java");
    private static final Path ADMIN = Path.of(
            "src/main/java/com/antondev/crates/gui/AdminMenuService.java");

    @Test
    void liveCreationEstablishesDurableDraftBeforeRegistryActivation() throws Exception {
        String source = Files.readString(COORDINATOR);
        String activate = section(source, "private CompletableFuture<Crate> activate(",
                "private <T> CompletableFuture<T> primary(");
        int open = activate.indexOf("plugin.draftSessions().openCrate(");
        int durablePayload = activate.indexOf("plugin.draftSessions().payload(");
        int install = activate.indexOf("plugin.crates().installPreparedDraft(durable)");
        int mirror = activate.indexOf("plugin.crates().queuePreparedDraftMirror(durable)");
        assertTrue(open >= 0 && durablePayload > open);
        assertTrue(install > durablePayload);
        assertTrue(mirror > install);
        assertTrue(activate.contains("if (!current.writable())"));
        assertTrue(activate.contains("prepareDurableDraft(candidate.crateId(), durablePayload)"));
    }

    @Test
    void durablePayloadIsValidatedAsANewDraftBeforeInstallation() throws Exception {
        String source = Files.readString(REGISTRY);
        String prepare = section(source, "public PreparedDraftActivation prepareDurableDraft(",
                "public Crate installPreparedDraft(");
        assertTrue(prepare.contains("crates.containsKey(id)"));
        assertTrue(prepare.contains("Crate parsed = parse(file, yaml)"));
        assertTrue(prepare.contains("parsed.state() != CrateState.DRAFT"));
        assertFalse(prepare.contains("AtomicFiles.write("));
    }

    @Test
    void liveCommandCreateAndCloneDoNotUseSynchronousCompatibilityApis() throws Exception {
        String source = Files.readString(COMMAND);
        String create = section(source, "private void create(CommandSender sender", "private void edit(");
        String clone = section(source, "private void cloneCrate(CommandSender sender", "private void importCrate(");
        assertTrue(create.contains("plugin.draftCreation().create("));
        assertFalse(create.contains("plugin.crates().createDraft("));
        assertTrue(clone.contains("plugin.draftCreation().cloneDraft("));
        assertFalse(clone.contains("plugin.crates().cloneAsDraft("));
    }

    @Test
    void liveGuiCreateAndCloneUseDurableCoordinator() throws Exception {
        String source = Files.readString(ADMIN);
        String create = section(source, "private void createFor(", "private void renameCrate(");
        assertTrue(create.contains("plugin.draftCreation().createQuick("));
        assertFalse(create.contains("createQuickDraft("));
        assertTrue(source.contains("plugin.draftCreation().cloneDraft("));
        assertFalse(source.contains("plugin.crates().cloneAsDraft("));
    }

    @Test
    void compatibilityCreationApisRemainSynchronousForOfflineCallers() throws Exception {
        String source = Files.readString(REGISTRY);
        String compatibility = section(source, "/** Synchronous compatibility API retained for tests/offline tooling. */\n    public Crate createDraft(",
                "public PreparedDraftImport prepareImportedDraft(");
        assertTrue(compatibility.contains("AtomicFiles.write(prepared.file()"));
        assertTrue(compatibility.contains("installPreparedDraft(prepared)"));
    }

    private static String section(String source, String start, String end) {
        int from = source.indexOf(start);
        int to = source.indexOf(end, from + start.length());
        if (from < 0 || to < 0) throw new AssertionError("Could not isolate source section " + start);
        return source.substring(from, to);
    }
}
