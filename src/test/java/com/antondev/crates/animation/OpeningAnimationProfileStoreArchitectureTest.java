package com.antondev.crates.animation;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class OpeningAnimationProfileStoreArchitectureTest {
    private static final Path STORE = Path.of(
            "src/main/java/com/antondev/crates/animation/OpeningAnimationProfileStore.java");

    @Test
    void liveSnapshotsAreValidatedBeforeOrderedOffThreadPersistence() throws Exception {
        String source = Files.readString(STORE);
        assertTrue(source.contains("OpeningAnimationProfiles.load"));
        assertTrue(source.contains("profiles.apply(next)"));
        assertTrue(source.contains("OpeningAnimationProfiles.serialize(next)"));
        assertTrue(source.contains("pendingWrite = pendingWrite.handle"));
        assertTrue(source.contains("runTaskAsynchronously"));
        assertTrue(source.contains("StandardCopyOption.ATOMIC_MOVE"));
        assertFalse(source.contains("runTaskTimer"));
        assertFalse(source.contains("runTaskLater"));
    }

    @Test
    void storeContainsNoOpeningTransactionAuthority() throws Exception {
        String source = Files.readString(STORE);
        assertFalse(source.contains("OpeningService"));
        assertFalse(source.contains("KeyService"));
        assertFalse(source.contains("RewardDelivery"));
        assertFalse(source.contains("consume("));
        assertFalse(source.contains("deliver("));
        assertFalse(source.contains("journal"));
    }
}
