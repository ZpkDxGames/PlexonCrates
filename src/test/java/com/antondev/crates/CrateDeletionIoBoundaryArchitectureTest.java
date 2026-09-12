package com.antondev.crates;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class CrateDeletionIoBoundaryArchitectureTest {
    private static final Path SERVICE = Path.of(
            "src/main/java/com/antondev/crates/service/CrateDeletionService.java");
    private static final Path REGISTRY = Path.of(
            "src/main/java/com/antondev/crates/service/CrateRegistry.java");
    private static final Path ADMIN = Path.of(
            "src/main/java/com/antondev/crates/gui/AdminMenuService.java");

    @Test
    void confirmedDeletionOrdersDraftMirrorCanonicalAndPrimaryActivation() throws Exception {
        String source = Files.readString(SERVICE);
        String method = section(source, "public CompletableFuture<DatabaseService.DeleteResult> delete(",
                "private <T> CompletableFuture<T> primary(");
        int discard = method.indexOf("plugin.draftSessions().discardCrate(");
        int mirror = method.indexOf("plugin.io().run(() -> plugin.crates().deleteMirror(prepared))");
        int canonical = method.indexOf("plugin.definitionRepository()\n                        .delete(");
        int primary = method.indexOf("thenCompose(deleted -> primary(() -> {");
        int install = method.indexOf("plugin.crates().installDeletion(prepared)");
        assertTrue(discard >= 0 && mirror > discard && canonical > mirror && primary > canonical && install > primary);
    }

    @Test
    void registryDeletionSeparatesFilesystemAndMemoryStages() throws Exception {
        String source = Files.readString(REGISTRY);
        String prepare = section(source, "public PreparedDeletion prepareDeletion(",
                "/** Filesystem-only stage.");
        String mirror = section(source, "public void deleteMirror(",
                "/** Applies a deletion after its mirror/canonical persistence stages have completed. */");
        String install = section(source, "public void installDeletion(",
                "/** Synchronous compatibility API retained for tests/offline tooling. */\n    public void delete(");
        assertFalse(prepare.contains("Files.delete"));
        assertFalse(prepare.contains("Files.write"));
        assertTrue(mirror.contains("Files.deleteIfExists(prepared.file())"));
        assertFalse(install.contains("Files.delete"));
        assertFalse(install.contains("Files.write"));
        assertFalse(install.contains("AtomicFiles."));
        assertTrue(install.contains("fireChange(current, CrateDefinitionChangeEvent.ChangeType.DELETED)"));
    }

    @Test
    void liveAdminDeletionUsesCoordinatorInsteadOfSynchronousRegistryDelete() throws Exception {
        String source = Files.readString(ADMIN);
        String method = section(source, "private void confirmCrate(", "private boolean captureKeyClick(");
        assertTrue(method.contains("plugin.crateDeletions().delete("));
        assertFalse(method.contains("plugin.crates().delete(crateId)"));
        assertFalse(method.contains("plugin.definitionRepository().delete("));
        assertFalse(method.contains("plugin.draftSessions().discardCrate("));
    }

    @Test
    void compatibilityDeleteStillPerformsMirrorThenMemoryRemoval() throws Exception {
        String source = Files.readString(REGISTRY);
        String method = section(source,
                "/** Synchronous compatibility API retained for tests/offline tooling. */\n    public void delete(",
                "public void addCapturedReward(");
        int prepare = method.indexOf("prepareDeletion(crateId)");
        int mirror = method.indexOf("deleteMirror(prepared)");
        int install = method.indexOf("installDeletion(prepared)");
        assertTrue(prepare >= 0 && mirror > prepare && install > mirror);
    }

    private static String section(String source, String start, String end) {
        int from = source.indexOf(start);
        int to = source.indexOf(end, from + start.length());
        if (from < 0 || to < 0) throw new AssertionError("Could not isolate source section " + start);
        return source.substring(from, to);
    }
}
