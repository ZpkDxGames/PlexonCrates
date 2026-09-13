package com.antondev.crates.animation;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class OpeningAnimationProfileArchitectureTest {
    private static final Path PROFILE = Path.of(
            "src/main/java/com/antondev/crates/animation/OpeningAnimationProfile.java");

    @Test
    void profileRemainsPresentationOnly() throws Exception {
        String source = Files.readString(PROFILE);
        assertTrue(source.contains("OpeningAnimationStyle"));
        assertTrue(source.contains("OpeningAnimationStage"));
        assertTrue(source.contains("particleBudgetPerTick"));
        assertFalse(source.contains("OpeningService"));
        assertFalse(source.contains("DatabaseService"));
        assertFalse(source.contains("RewardStateService"));
        assertFalse(source.contains("KeyService"));
        assertFalse(source.contains("ClaimService"));
        assertFalse(source.contains("Statistics"));
        assertFalse(source.contains("consume("));
        assertFalse(source.contains("deliver("));
    }
}
