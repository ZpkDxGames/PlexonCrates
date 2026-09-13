package com.antondev.crates.animation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.antondev.crates.domain.crate.AnimationType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OpeningAnimationProfilesTest {
    @TempDir Path temporary;

    @Test
    void missingFileFallsBackToAcceptedLegacyDefault() {
        OpeningAnimationProfiles.Snapshot snapshot = OpeningAnimationProfiles.load(
                temporary.resolve("missing.yml").toFile(), AnimationType.REVEAL);
        assertEquals(OpeningAnimationProfiles.BUILTIN_DEFAULT, snapshot.globalProfileId());
        assertEquals(OpeningAnimationStyle.CHARGE_REVEAL,
                snapshot.profiles().get(OpeningAnimationProfiles.BUILTIN_DEFAULT).style());
        assertTrue(snapshot.crateAssignments().isEmpty());
    }

    @Test
    void migrationSafeGlobalPreservesEachCratesLegacyPresentation() {
        OpeningAnimationProfiles registry = new OpeningAnimationProfiles(
                OpeningAnimationProfiles.migrationSafeDefaults(AnimationType.ROULETTE));
        assertEquals(OpeningAnimationProfiles.LEGACY_INHERIT, registry.snapshot().globalProfileId());
        assertEquals(OpeningAnimationStyle.INSTANT, registry.resolve("basic", AnimationType.INSTANT).style());
        assertEquals(OpeningAnimationStyle.ROULETTE, registry.resolve("rare", AnimationType.ROULETTE).style());
        assertEquals(OpeningAnimationStyle.CHARGE_REVEAL, registry.resolve("epic", AnimationType.REVEAL).style());
        assertTrue(registry.resolve("legendary", AnimationType.SUMMARY).summaryOnFinish());
    }

    @Test
    void namedGlobalCanBeEnabledWhileOneCrateExplicitlyKeepsLegacy() {
        OpeningAnimationProfiles.Snapshot source = OpeningAnimationProfiles.migrationSafeDefaults(AnimationType.ROULETTE);
        source = OpeningAnimationProfiles.withProfile(source, "spin",
                OpeningAnimationProfile.defaults(OpeningAnimationStyle.SPIN));
        source = OpeningAnimationProfiles.withGlobal(source, "spin");
        source = OpeningAnimationProfiles.assign(source, "legacy_crate", OpeningAnimationProfiles.LEGACY_INHERIT);
        OpeningAnimationProfiles registry = new OpeningAnimationProfiles(source);

        assertEquals(OpeningAnimationStyle.SPIN, registry.resolve("modern", AnimationType.REVEAL).style());
        assertEquals(OpeningAnimationStyle.CHARGE_REVEAL,
                registry.resolve("legacy_crate", AnimationType.REVEAL).style());
    }

    @Test
    void serializeAndLoadRoundTripProfilesAndAssignments() throws Exception {
        OpeningAnimationProfiles.Snapshot source = OpeningAnimationProfiles.defaults(AnimationType.ROULETTE);
        source = OpeningAnimationProfiles.withProfile(source, "burst",
                OpeningAnimationProfile.defaults(OpeningAnimationStyle.SPIRAL_BURST));
        source = OpeningAnimationProfiles.assign(source, "legendary", "burst");
        String yaml = OpeningAnimationProfiles.serialize(source);
        Path file = temporary.resolve("animations.yml");
        Files.writeString(file, yaml);

        OpeningAnimationProfiles.Snapshot loaded = OpeningAnimationProfiles.load(file.toFile(), AnimationType.INSTANT);
        assertEquals(source.globalProfileId(), loaded.globalProfileId());
        assertEquals(source.crateAssignments(), loaded.crateAssignments());
        assertEquals(OpeningAnimationStyle.SPIRAL_BURST, loaded.profiles().get("burst").style());
        assertEquals(source.profiles().get("burst").stageTicks(), loaded.profiles().get("burst").stageTicks());
    }

    @Test
    void legacyGlobalRoundTripsWithoutBecomingANamedProfile() throws Exception {
        OpeningAnimationProfiles.Snapshot source = OpeningAnimationProfiles.migrationSafeDefaults(AnimationType.ROULETTE);
        String yaml = OpeningAnimationProfiles.serialize(source);
        Path file = temporary.resolve("legacy.yml");
        Files.writeString(file, yaml);

        OpeningAnimationProfiles.Snapshot loaded = OpeningAnimationProfiles.load(file.toFile(), AnimationType.INSTANT);
        assertEquals(OpeningAnimationProfiles.LEGACY_INHERIT, loaded.globalProfileId());
        assertFalse(loaded.profiles().containsKey(OpeningAnimationProfiles.LEGACY_INHERIT));
        assertEquals(OpeningAnimationStyle.CHARGE_REVEAL,
                new OpeningAnimationProfiles(loaded).resolve("crate", AnimationType.REVEAL).style());
    }

    @Test
    void explicitCrateAssignmentWinsAndInheritanceCanBeRestored() {
        OpeningAnimationProfiles.Snapshot source = OpeningAnimationProfiles.defaults(AnimationType.ROULETTE);
        source = OpeningAnimationProfiles.withProfile(source, "spin",
                OpeningAnimationProfile.defaults(OpeningAnimationStyle.SPIN));
        source = OpeningAnimationProfiles.assign(source, "rare", "spin");
        OpeningAnimationProfiles registry = new OpeningAnimationProfiles(source);
        assertEquals(OpeningAnimationStyle.SPIN, registry.resolve("rare", AnimationType.INSTANT).style());
        assertEquals(OpeningAnimationStyle.ROULETTE, registry.resolve("basic", AnimationType.INSTANT).style());

        OpeningAnimationProfiles.Snapshot inherited = OpeningAnimationProfiles.inheritGlobal(source, "rare");
        registry.apply(inherited);
        assertEquals(OpeningAnimationStyle.ROULETTE, registry.resolve("rare", AnimationType.INSTANT).style());
    }

    @Test
    void assignedOrGlobalProfilesCannotBeRemovedUnsafely() {
        OpeningAnimationProfiles.Snapshot source = OpeningAnimationProfiles.defaults(AnimationType.ROULETTE);
        source = OpeningAnimationProfiles.withProfile(source, "spin",
                OpeningAnimationProfile.defaults(OpeningAnimationStyle.SPIN));
        OpeningAnimationProfiles.Snapshot removable = source;
        OpeningAnimationProfiles.Snapshot assigned = OpeningAnimationProfiles.assign(source, "epic", "spin");
        assertThrows(IllegalArgumentException.class,
                () -> OpeningAnimationProfiles.removeProfile(assigned, "spin"));
        assertThrows(IllegalArgumentException.class,
                () -> OpeningAnimationProfiles.removeProfile(removable, OpeningAnimationProfiles.BUILTIN_DEFAULT));
        assertThrows(IllegalArgumentException.class,
                () -> OpeningAnimationProfiles.removeProfile(removable, OpeningAnimationProfiles.LEGACY_INHERIT));
    }

    @Test
    void legacyIsReservedAndCannotBeCreatedAsANamedProfile() {
        OpeningAnimationProfiles.Snapshot source = OpeningAnimationProfiles.defaults(AnimationType.ROULETTE);
        assertThrows(IllegalArgumentException.class, () -> OpeningAnimationProfiles.withProfile(
                source, OpeningAnimationProfiles.LEGACY_INHERIT,
                OpeningAnimationProfile.defaults(OpeningAnimationStyle.SPIN)));
    }

    @Test
    void snapshotRejectsDanglingAssignments() {
        assertThrows(IllegalArgumentException.class, () -> new OpeningAnimationProfiles.Snapshot(
                "default",
                Map.of("default", OpeningAnimationProfile.defaults(OpeningAnimationStyle.ROULETTE)),
                Map.of("legendary", "missing")));
    }

    @Test
    void serializedRegistryNeverContainsTransactionalState() {
        String yaml = OpeningAnimationProfiles.serialize(OpeningAnimationProfiles.defaults(AnimationType.ROULETTE));
        assertFalse(yaml.contains("payment"));
        assertFalse(yaml.contains("reward"));
        assertFalse(yaml.contains("journal"));
        assertFalse(yaml.contains("pity"));
        assertFalse(yaml.contains("statistics"));
    }
}
