package com.antondev.crates;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class RevampPublicationIoArchitectureTest {
    private static final Path PUBLISHER = Path.of("src/main/java/com/antondev/crates/service/DefinitionPublisher.java");
    private static final Path REGISTRY = Path.of("src/main/java/com/antondev/crates/service/CrateRegistry.java");
    private static final Path IO = Path.of("src/main/java/com/antondev/crates/service/AsyncIoService.java");
    private static final Path MAIN = Path.of("src/main/java/com/antondev/crates/PlexonCrates.java");

    @Test
    void authoritativeActivationDoesNotWriteTheYamlMirror() throws Exception {
        String source = Files.readString(PUBLISHER);
        String activate = section(source, "private Publication activate(",
                "private CompletableFuture<Publication> mirror(");
        assertTrue(activate.contains("requirePrimaryThread()"));
        assertTrue(activate.contains("runtime.install("));
        assertTrue(activate.contains("crates.installPublishedInMemory(prepared.publication())"));
        assertFalse(activate.contains("writePublishedMirror"));
        assertFalse(activate.contains("AtomicFiles"));
    }

    @Test
    void optionalMirrorUsesBoundedPluginOwnedIoExecutor() throws Exception {
        String source = Files.readString(PUBLISHER);
        String mirror = section(source, "private CompletableFuture<Publication> mirror(",
                "private static DatabaseService.DefinitionBundle bundle(");
        assertTrue(mirror.contains("plugin.io().submit"));
        assertTrue(mirror.contains("crates.writePublishedMirror(prepared)"));
        assertTrue(mirror.contains("YAML mirror could not be updated"));
        assertTrue(mirror.contains("YAML mirror task could not be scheduled"));
    }

    @Test
    void registrySeparatesMemoryMutationFromAtomicFileWrite() throws Exception {
        String source = Files.readString(REGISTRY);
        String inMemory = section(source, "public void installPublishedInMemory(",
                "public void writePublishedMirror(");
        String mirror = section(source, "public void writePublishedMirror(",
                "public Crate restoreDraftSnapshot(");
        assertTrue(inMemory.contains("install(publication.crateId()"));
        assertTrue(inMemory.contains("fireChange("));
        assertFalse(inMemory.contains("AtomicFiles.write"));
        assertTrue(mirror.contains("AtomicFiles.write"));
        assertFalse(mirror.contains("fireChange("));
    }

    @Test
    void sharedIoPoolIsBoundedAndHasExplicitShutdownOwnership() throws Exception {
        String io = Files.readString(IO);
        String main = Files.readString(MAIN);
        assertTrue(io.contains("new ArrayBlockingQueue<>(MAXIMUM_QUEUED_TASKS)"));
        assertTrue(io.contains("new ThreadPoolExecutor.AbortPolicy()"));
        assertTrue(io.contains("executor.shutdown()"));
        assertFalse(io.contains("org.bukkit"));
        assertTrue(main.contains("io = new AsyncIoService()"));
        assertTrue(main.contains("if (io != null) io.close()"));
    }

    private static String section(String source, String start, String end) {
        int from = source.indexOf(start);
        int to = source.indexOf(end, from + start.length());
        if (from < 0 || to < 0) throw new AssertionError("Could not isolate source section " + start);
        return source.substring(from, to);
    }
}
