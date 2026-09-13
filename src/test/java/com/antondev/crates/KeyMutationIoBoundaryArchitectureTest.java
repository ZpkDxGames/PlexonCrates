package com.antondev.crates;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class KeyMutationIoBoundaryArchitectureTest {
    private static final Path KEYS = Path.of(
            "src/main/java/com/antondev/crates/service/KeyService.java");

    @Test
    void preparedMutationStartsFromInMemoryRegistryWithoutReadingMirror() throws Exception {
        String source = Files.readString(KEYS);
        String begin = section(source, "public PreparedMutation beginPreparedMutation()",
                "public PreparedMutation prepareCreateCaptured(");
        assertTrue(begin.contains("definitions.values()"));
        assertTrue(begin.contains("writeDefinition(yaml"));
        assertFalse(begin.contains("Files."));
        assertFalse(begin.contains("AtomicFiles."));
    }

    @Test
    void preparedCreateAndBindValidateEntireCandidateInMemory() throws Exception {
        String source = Files.readString(KEYS);
        String create = section(source, "public PreparedMutation prepareCreateCaptured(",
                "public PreparedMutation prepareBindExternal(");
        String bind = section(source, "public PreparedMutation prepareBindExternal(",
                "public Optional<KeyDefinition> definition(PreparedMutation");
        assertTrue(create.contains("return prepared(yaml, audits)"));
        assertTrue(bind.contains("return prepared(yaml, audits)"));
        assertFalse(create.contains("Files."));
        assertFalse(create.contains("AtomicFiles."));
        assertFalse(bind.contains("Files."));
        assertFalse(bind.contains("AtomicFiles."));
    }

    @Test
    void mirrorWriteIsTheOnlyPreparedFilesystemStage() throws Exception {
        String source = Files.readString(KEYS);
        String write = section(source, "public void writePreparedMutation(",
                "/** Applies an already-durable candidate");
        assertTrue(write.contains("AtomicFiles.write(file, prepared.mirrorPayload())"));
        assertFalse(write.contains("definitions ="));
        assertFalse(write.contains("syncDiscovery()"));
        assertFalse(write.contains("database.audit("));
    }

    @Test
    void activationContainsNoFileIo() throws Exception {
        String source = Files.readString(KEYS);
        String install = section(source, "public void installPreparedMutation(",
                "private PreparedMutation prepared(");
        assertTrue(install.contains("definitions = prepared.snapshot().definitions()"));
        assertTrue(install.contains("syncDiscovery()"));
        assertTrue(install.contains("database.audit(audit)"));
        assertFalse(install.contains("Files."));
        assertFalse(install.contains("AtomicFiles."));
    }

    @Test
    void yamlParserSupportsFileAndPreparedMemorySources() throws Exception {
        String source = Files.readString(KEYS);
        assertTrue(source.contains("return parseSnapshot(YamlConfiguration.loadConfiguration(file.toFile()))"));
        assertTrue(source.contains("Snapshot parsed = parseSnapshot(yaml)"));
    }

    private static String section(String source, String start, String end) {
        int from = source.indexOf(start);
        int to = source.indexOf(end, from + start.length());
        if (from < 0 || to < 0) throw new AssertionError("Could not isolate source section " + start);
        return source.substring(from, to);
    }
}
