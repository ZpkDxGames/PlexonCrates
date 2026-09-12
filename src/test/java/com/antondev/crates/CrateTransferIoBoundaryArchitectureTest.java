package com.antondev.crates;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class CrateTransferIoBoundaryArchitectureTest {
    private static final Path TRANSFER = Path.of(
            "src/main/java/com/antondev/crates/service/CrateTransferService.java");
    private static final Path REGISTRY = Path.of(
            "src/main/java/com/antondev/crates/service/CrateRegistry.java");
    private static final Path COMMAND = Path.of(
            "src/main/java/com/antondev/crates/command/CratesAdminCommand.java");
    private static final Path ADMIN = Path.of(
            "src/main/java/com/antondev/crates/gui/AdminMenuService.java");

    @Test
    void liveImportReadsFileOffThreadThenUsesDurableFirstActivation() throws Exception {
        String source = Files.readString(TRANSFER);
        String method = section(source, "public CompletableFuture<Crate> importDraft(",
                "public CompletableFuture<Path> export(");
        assertTrue(method.contains("plugin.io().submit(() ->"));
        assertTrue(method.contains("Files.readString(source, StandardCharsets.UTF_8)"));
        assertTrue(method.contains("thenCompose(sourceYaml -> primary(() ->"));
        assertTrue(method.contains("prepareImportedActivation(sourceYaml, newId, actorName)"));
        assertTrue(method.contains("plugin.draftCreation()"));
        assertTrue(method.contains(".activatePrepared(actorId, actorName, prepared)"));
        assertFalse(method.contains("writeImportedDraft("));
        assertFalse(method.contains("installImportedDraft("));
    }

    @Test
    void liveExportSnapshotsPayloadBeforeWorkerFileWrite() throws Exception {
        String source = Files.readString(TRANSFER);
        String method = section(source, "public CompletableFuture<Path> export(",
                "private <T> CompletableFuture<T> primary(");
        int prepare = method.indexOf("plugin.crates().prepareExport(crateId, exportDirectory)");
        int submit = method.indexOf("plugin.io().submit(() -> plugin.crates().writeExport(prepared))");
        assertTrue(method.contains("requirePrimary()"));
        assertTrue(prepare >= 0 && submit > prepare);
    }

    @Test
    void exportPreparationHasNoFilesystemWriteAndWritePhaseHasNoRegistryRead() throws Exception {
        String source = Files.readString(REGISTRY);
        String prepare = section(source, "public PreparedExport prepareExport(",
                "public Path writeExport(");
        String write = section(source, "public Path writeExport(",
                "/** Synchronous compatibility API retained for tests/offline tooling. */\n    public Path exportDefinition(");
        assertTrue(prepare.contains("payloads.get(id)"));
        assertFalse(prepare.contains("AtomicFiles.write("));
        assertFalse(prepare.contains("Files.createDirectories("));
        assertTrue(write.contains("Files.createDirectories(prepared.destination().getParent())"));
        assertTrue(write.contains("AtomicFiles.write(prepared.destination(), prepared.payload())"));
        assertFalse(write.contains("payloads.get("));
        assertFalse(write.contains("files.get("));
    }

    @Test
    void liveCommandAndGuiRoutesUseTransferServiceInsteadOfSynchronousRegistryTransfers() throws Exception {
        String command = Files.readString(COMMAND);
        String commandImport = section(command, "private void importCrate(CommandSender sender", "private void exportCrate(");
        String commandExport = section(command, "private void exportCrate(CommandSender sender", "private void publishCrate(");
        assertTrue(commandImport.contains("plugin.crateTransfers().importDraft("));
        assertFalse(commandImport.contains("plugin.crates().importAsDraft("));
        assertTrue(commandExport.contains("plugin.crateTransfers().export("));
        assertFalse(commandExport.contains("plugin.crates().exportDefinition("));

        String admin = Files.readString(ADMIN);
        String guiImport = section(admin, "private void importCrate(Player player)", "private void exportCrate(");
        String guiExport = section(admin, "private void exportCrate(Player player", "private void duplicateKey(");
        assertTrue(guiImport.contains("plugin.crateTransfers().importDraft("));
        assertFalse(guiImport.contains("plugin.crates().importAsDraft("));
        assertTrue(guiExport.contains("plugin.crateTransfers().export("));
        assertFalse(guiExport.contains("plugin.crates().exportDefinition("));
    }

    @Test
    void synchronousTransferApisRemainOnlyAsCompatibilitySurface() throws Exception {
        String source = Files.readString(REGISTRY);
        assertTrue(source.contains("public Crate importAsDraft(Path sourceFile, String rawNewId, String editor) throws Exception"));
        assertTrue(source.contains("public Path exportDefinition(String crateId, Path exportDirectory) throws Exception"));
    }

    private static String section(String source, String start, String end) {
        int from = source.indexOf(start);
        int to = source.indexOf(end, from + start.length());
        if (from < 0 || to < 0) throw new AssertionError("Could not isolate source section " + start);
        return source.substring(from, to);
    }
}
