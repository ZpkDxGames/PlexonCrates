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
        OpeningAnimationProfiles.Snapshot assigned = OpeningAnimationProfiles.assign(source, "epic", "spin");
        assertThrows(IllegalArgumentException.class,
                () -> OpeningAnimationProfiles.removeProfile(assigned, "spin"));
        assertThrows(IllegalArgumentException.class,
                () -> OpeningAnimationProfiles.removeProfile(source, OpeningAnimationProfiles.BUILTIN_DEFAULT));
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
