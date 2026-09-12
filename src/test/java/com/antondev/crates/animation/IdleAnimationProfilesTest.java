package com.antondev.crates.animation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.bukkit.Particle;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class IdleAnimationProfilesTest {
    @TempDir Path temporary;

    @Test
    void migrationSafeDefaultsPreserveLegacyUntilExplicitAssignment() {
        IdleAnimationProfile legacy = legacy();
        IdleAnimationProfiles profiles = new IdleAnimationProfiles(
                IdleAnimationProfiles.migrationSafeDefaults(legacy));
        assertEquals(IdleAnimationProfiles.LEGACY_INHERIT, profiles.snapshot().globalProfileId());
        assertEquals(legacy, profiles.resolve("basic", legacy));

        IdleAnimationProfile named = new IdleAnimationProfile(IdleAnimationStyle.HELIX, Particle.END_ROD,
                1.0, 2.0, 16, 0.3, 0.1, 2, 40.0, 64, 100);
        IdleAnimationProfiles.Snapshot withNamed = IdleAnimationProfiles.withProfile(
                profiles.snapshot(), "helix", named);
        IdleAnimationProfiles.Snapshot assigned = IdleAnimationProfiles.assign(withNamed, "basic", "helix");
        profiles.apply(assigned);
        assertEquals(named, profiles.resolve("basic", legacy));
        assertEquals(legacy, profiles.resolve("rare", legacy));
    }

    @Test
    void serializeAndLoadRoundTripAssignments() throws Exception {
        IdleAnimationProfile legacy = legacy();
        IdleAnimationProfile pulse = new IdleAnimationProfile(IdleAnimationStyle.PULSE, Particle.END_ROD,
                0.8, 1.4, 20, 0.18, 0.05, 3, 48.0, 90, 180);
        IdleAnimationProfiles.Snapshot snapshot = IdleAnimationProfiles.withProfile(
                IdleAnimationProfiles.migrationSafeDefaults(legacy), "pulse", pulse);
        snapshot = IdleAnimationProfiles.withGlobal(snapshot, "pulse");
        snapshot = IdleAnimationProfiles.assign(snapshot, "rare", IdleAnimationProfiles.LEGACY_INHERIT);

        File file = temporary.resolve("idle-animations.yml").toFile();
        Files.writeString(file.toPath(), IdleAnimationProfiles.serialize(snapshot));
        IdleAnimationProfiles.Snapshot loaded = IdleAnimationProfiles.load(file, legacy);

        assertEquals("pulse", loaded.globalProfileId());
        assertEquals("legacy", loaded.crateAssignments().get("rare"));
        assertEquals(pulse, loaded.profiles().get("pulse"));
    }

    @Test
    void danglingAssignmentsAndReservedProfileIdFailClosed() {
        IdleAnimationProfile legacy = legacy();
        assertThrows(IllegalArgumentException.class, () -> new IdleAnimationProfiles.Snapshot(
                "missing", Map.of("default", legacy), Map.of()));
        assertThrows(IllegalArgumentException.class, () -> IdleAnimationProfiles.assign(
                IdleAnimationProfiles.migrationSafeDefaults(legacy), "basic", "missing"));
        assertThrows(IllegalArgumentException.class, () -> IdleAnimationProfiles.withProfile(
                IdleAnimationProfiles.migrationSafeDefaults(legacy), "legacy", legacy));
    }

    @Test
    void maximumReceiverRangeCoversLegacyAndNamedProfiles() {
        IdleAnimationProfile legacy = legacy();
        IdleAnimationProfile distant = new IdleAnimationProfile(IdleAnimationStyle.RING, Particle.END_ROD,
                1.0, 1.0, 12, 0.2, 0.0, 1, 96.0, 80, 200);
        IdleAnimationProfiles.Snapshot snapshot = IdleAnimationProfiles.withProfile(
                IdleAnimationProfiles.migrationSafeDefaults(legacy), "distant", distant);
        IdleAnimationProfiles profiles = new IdleAnimationProfiles(snapshot);
        assertEquals(96.0, profiles.maximumReceiverRange(legacy));
        assertTrue(profiles.resolve("basic", legacy).enabled());
    }

    private static IdleAnimationProfile legacy() {
        return new IdleAnimationProfile(IdleAnimationStyle.AURA, Particle.END_ROD,
                0.65, 1.25, 12, 0.22, 0.08, 1, 32.0, 80, 200);
    }
}
