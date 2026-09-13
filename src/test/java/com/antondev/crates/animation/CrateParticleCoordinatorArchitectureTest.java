package com.antondev.crates.animation;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class CrateParticleCoordinatorArchitectureTest {
    private static final Path COORDINATOR = Path.of(
            "src/main/java/com/antondev/crates/animation/CrateParticleCoordinator.java");
    private static final Path DISPLAY = Path.of(
            "src/main/java/com/antondev/crates/service/DisplayService.java");

    @Test
    void oneSharedCoordinatorOwnsIdleScheduling() throws Exception {
        String source = Files.readString(COORDINATOR);
        assertTrue(source.contains("private BukkitTask task"));
        assertTrue(source.contains("runTaskTimer(plugin, this::tick"));
        assertFalse(source.contains("Map<UUID, BukkitTask>"));
        assertFalse(source.contains("Map<String, BukkitTask>"));
        assertTrue(source.contains("public void stop()"));
    }

    @Test
    void particlesAreReceiverScopedAndBudgeted() throws Exception {
        String source = Files.readString(COORDINATOR);
        assertTrue(source.contains("viewer.spawnParticle"));
        assertFalse(source.contains("world.spawnParticle"));
        assertTrue(source.contains("particleMaxParticlesPerTick"));
        assertTrue(source.contains("maxPerCratePerTick"));
        assertTrue(source.contains("maxPerViewerPerTick"));
        assertTrue(source.contains("nearbyChunks"));
        assertTrue(source.contains("isChunkLoaded"));
    }

    @Test
    void eachLinkedCrateResolvesItsIdleProfileWithoutCreatingMoreSchedulers() throws Exception {
        String source = Files.readString(COORDINATOR);
        assertTrue(source.contains("IdleAnimationProfileStore.shared(plugin)"));
        assertTrue(source.contains("profiles.maximumReceiverRange(legacy)"));
        assertTrue(source.contains("profiles.resolve(crate.id(), legacy)"));
        assertTrue(source.contains("profile.receiverRange() * profile.receiverRange()"));
        assertTrue(source.contains("profile.particlesPerPoint()"));
        assertTrue(source.contains("profile.maxPerCratePerTick()"));
        assertTrue(source.contains("profile.maxPerViewerPerTick()"));
        assertFalse(source.contains("runTaskLater"));
    }

    @Test
    void displayServiceDelegatesInsteadOfRunningSecondParticleLoop() throws Exception {
        String source = Files.readString(DISPLAY);
        assertTrue(source.contains("CrateParticleCoordinator particles"));
        assertTrue(source.contains("particles.refresh()"));
        assertTrue(source.contains("particles.stop()"));
        assertFalse(source.contains("private void particles()"));
        assertFalse(source.contains("runTaskTimer"));
    }
}
