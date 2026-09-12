package com.antondev.crates;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class DraftMirrorIoBoundaryArchitectureTest {
    private static final Path REGISTRY = Path.of(
            "src/main/java/com/antondev/crates/service/CrateRegistry.java");
    private static final Path MAIN = Path.of(
            "src/main/java/com/antondev/crates/PlexonCrates.java");

    @Test
    void genericEditorMutationQueuesMirrorInsteadOfWritingOnCallerThread() throws Exception {
        String source = Files.readString(REGISTRY);
        String mutate = section(source, "private void mutate(String crateId,", "private void install(");
        assertTrue(mutate.contains("persistEditableMirror(file, serialized)"));
        assertFalse(mutate.contains("AtomicFiles.write("));
        assertTrue(mutate.contains("install(id, file, parsed)"));
        assertTrue(mutate.contains("payloads.put(id"));
        assertTrue(mutate.contains("fireChange(parsed"));
    }

    @Test
    void liveMirrorWriterOrdersEachPathBeforeSubmittingNextWrite() throws Exception {
        String source = Files.readString(REGISTRY);
        String writer = section(source, "private void persistEditableMirror(", "public static Snapshot load(");
        assertTrue(writer.contains("CompletableFuture<Void> previous = mirrorWrites.get(path)"));
        assertTrue(writer.contains("previous.handle((ignored, failure) -> null)"));
        assertTrue(writer.contains("ready.thenCompose(ignored -> io.run(() -> AtomicFiles.write(path, serialized)))"));
        assertTrue(writer.contains("mirrorWrites.put(path, next)"));
        assertTrue(writer.contains("if (mirrorWrites.get(path) == next) mirrorWrites.remove(path)"));
    }

    @Test
    void standaloneRegistryKeepsSynchronousCompatibilityFallback() throws Exception {
        String source = Files.readString(REGISTRY);
        String writer = section(source, "private void persistEditableMirror(", "public static Snapshot load(");
        assertTrue(writer.contains("if (io == null)"));
        assertTrue(writer.contains("AtomicFiles.write(file, serialized)"));
    }

    @Test
    void pluginConfiguresWriterAndDrainsItBeforeClosingIoPool() throws Exception {
        String source = Files.readString(MAIN);
        assertTrue(source.contains("crates.configureMirrorWriter(io, getLogger())"));
        String disable = section(source, "public void onDisable()", "/** Synchronous compatibility path");
        assertTrue(disable.contains("crates.awaitMirrorWrites().get(3"));
        assertTrue(disable.indexOf("crates.awaitMirrorWrites().get(3") < disable.indexOf("io.close()"));
    }

    private static String section(String source, String start, String end) {
        int from = source.indexOf(start);
        int to = source.indexOf(end, from + start.length());
        if (from < 0 || to < 0) throw new AssertionError("Could not isolate source section " + start);
        return source.substring(from, to);
    }
}
