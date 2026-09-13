package com.antondev.crates.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class OpeningLogArchitectureTest {
    private static final Path SOURCE = Path.of("src/main/java/com/antondev/crates/service/OpeningLog.java");

    @Test
    void fileLoggingUsesABoundedNonCallerRunsQueue() throws Exception {
        String source = Files.readString(SOURCE);
        assertTrue(source.contains("MAXIMUM_QUEUED_WRITES = 1024"));
        assertTrue(source.contains("new ArrayBlockingQueue<>(MAXIMUM_QUEUED_WRITES)"));
        assertTrue(source.contains("new ThreadPoolExecutor.AbortPolicy()"));
        assertTrue(source.contains("catch (RejectedExecutionException rejected)"));
        assertFalse(source.contains("Executors.newSingleThreadExecutor"));
        assertFalse(source.contains("CallerRunsPolicy"));
    }

    @Test
    void rejectionWarningsAreRateLimitedAndShutdownIsExplicit() throws Exception {
        String source = Files.readString(SOURCE);
        assertTrue(source.contains("REJECTION_WARNING_INTERVAL_MILLIS = 60_000L"));
        assertTrue(source.contains("compareAndSet"));
        assertTrue(source.contains("writer.shutdown()"));
        assertTrue(source.contains("writer.awaitTermination(5, TimeUnit.SECONDS)"));
        assertTrue(source.contains("writer.shutdownNow()"));
    }
}
