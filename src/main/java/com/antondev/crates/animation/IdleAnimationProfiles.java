package com.antondev.crates.animation;

import com.antondev.crates.service.CrateRegistry;
import java.io.File;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.bukkit.Particle;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

/** Immutable registry of named physical-crate idle animation profiles. */
public final class IdleAnimationProfiles {
    public static final int CONFIG_VERSION = 1;
    public static final String BUILTIN_DEFAULT = "default";
    /** Reserved assignment that keeps the accepted config.yml idle profile. */
    public static final String LEGACY_INHERIT = "legacy";

    public record Snapshot(
            String globalProfileId,
            Map<String, IdleAnimationProfile> profiles,
            Map<String, String> crateAssignments) {
        public Snapshot {
            globalProfileId = assignmentId(globalProfileId, "global profile");
            profiles = Map.copyOf(new LinkedHashMap<>(Objects.requireNonNull(profiles, "profiles")));
            crateAssignments = Map.copyOf(new LinkedHashMap<>(Objects.requireNonNull(crateAssignments, "crateAssignments")));
            if (!LEGACY_INHERIT.equals(globalProfileId) && !profiles.containsKey(globalProfileId)) {
                throw new IllegalArgumentException("Global idle animation profile does not exist: " + globalProfileId);
            }
            for (Map.Entry<String, String> entry : crateAssignments.entrySet()) {
                String crateId = id(entry.getKey(), "crate assignment");
                if (!crateId.equals(entry.getKey())) {
                    throw new IllegalArgumentException("Crate idle assignment IDs must be normalized: " + entry.getKey());
                }
                String profileId = assignmentId(entry.getValue(), "crate profile");
                if (!LEGACY_INHERIT.equals(profileId) && !profiles.containsKey(profileId)) {
                    throw new IllegalArgumentException("Crate " + crateId + " references unknown idle profile " + profileId);
                }
            }
        }
    }

    private volatile Snapshot snapshot;

    public IdleAnimationProfiles(Snapshot snapshot) {
        this.snapshot = Objects.requireNonNull(snapshot, "snapshot");
    }

    public Snapshot snapshot() { return snapshot; }

    public void apply(Snapshot next) { snapshot = Objects.requireNonNull(next, "next"); }

    public Optional<IdleAnimationProfile> named(String profileId) {
        if (profileId == null || LEGACY_INHERIT.equals(profileId.trim().toLowerCase(Locale.ROOT))) {
            return Optional.empty();
        }
        return Optional.ofNullable(snapshot.profiles().get(profileId.trim().toLowerCase(Locale.ROOT)));
    }

    public IdleAnimationProfile resolve(String crateId, IdleAnimationProfile legacy) {
        Objects.requireNonNull(legacy, "legacy");
        String normalized = crateId == null ? "" : crateId.trim().toLowerCase(Locale.ROOT);
        String assigned = snapshot.crateAssignments().get(normalized);
        if (assigned != null) return resolveAssignment(assigned, legacy);
        return resolveAssignment(snapshot.globalProfileId(), legacy);
    }

    public double maximumReceiverRange(IdleAnimationProfile legacy) {
        double maximum = legacy.receiverRange();
        for (IdleAnimationProfile profile : snapshot.profiles().values()) {
            maximum = Math.max(maximum, profile.receiverRange());
        }
        return maximum;
    }

    private IdleAnimationProfile resolveAssignment(String assignment, IdleAnimationProfile legacy) {
        if (LEGACY_INHERIT.equals(assignment)) return legacy;
        IdleAnimationProfile profile = snapshot.profiles().get(assignment);
        return profile == null ? legacy : profile;
    }

    public static Snapshot migrationSafeDefaults(IdleAnimationProfile legacy) {
        return new Snapshot(LEGACY_INHERIT, Map.of(BUILTIN_DEFAULT, Objects.requireNonNull(legacy, "legacy")), Map.of());
    }

    public static Snapshot load(File file, IdleAnimationProfile legacy) {
        if (file == null || !file.isFile()) return migrationSafeDefaults(legacy);
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        int version = yaml.getInt("config-version", CONFIG_VERSION);
        if (version != CONFIG_VERSION) {
            throw new IllegalArgumentException("Unsupported idle-animations.yml config-version; expected " + CONFIG_VERSION);
        }
        ConfigurationSection root = yaml.getConfigurationSection("profiles");
        if (root == null || root.getKeys(false).isEmpty()) return migrationSafeDefaults(legacy);
        Map<String, IdleAnimationProfile> profiles = new LinkedHashMap<>();
        for (String raw : root.getKeys(false)) {
            String profileId = id(raw, "profile");
            if (LEGACY_INHERIT.equals(profileId)) {
                throw new IllegalArgumentException("Idle profile ID 'legacy' is reserved for config inheritance");
            }
            if (!profileId.equals(raw)) throw new IllegalArgumentException("Idle profile IDs must be normalized: " + raw);
            profiles.put(profileId, readProfile(yaml, "profiles." + profileId, legacy));
        }
        String global = assignmentId(yaml.getString("global-profile", LEGACY_INHERIT), "global profile");
        Map<String, String> assignments = new LinkedHashMap<>();
        ConfigurationSection crates = yaml.getConfigurationSection("crate-profiles");
        if (crates != null) {
            for (String rawCrate : crates.getKeys(false)) {
                String crateId = id(rawCrate, "crate assignment");
                if (!crateId.equals(rawCrate)) {
                    throw new IllegalArgumentException("Crate idle assignment IDs must be normalized: " + rawCrate);
                }
                assignments.put(crateId, assignmentId(crates.getString(rawCrate, ""), "crate profile"));
            }
        }
        return new Snapshot(global, profiles, assignments);
    }

    public static String serialize(Snapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("config-version", CONFIG_VERSION);
        yaml.set("global-profile", snapshot.globalProfileId());
        snapshot.profiles().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry ->
                writeProfile(yaml, "profiles." + entry.getKey(), entry.getValue()));
        snapshot.crateAssignments().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry ->
                yaml.set("crate-profiles." + entry.getKey(), entry.getValue()));
        return yaml.saveToString();
    }

    public static Snapshot withProfile(Snapshot source, String rawId, IdleAnimationProfile profile) {
        String profileId = id(rawId, "profile");
        if (LEGACY_INHERIT.equals(profileId)) throw new IllegalArgumentException("Idle profile ID 'legacy' is reserved");
        Map<String, IdleAnimationProfile> profiles = new LinkedHashMap<>(source.profiles());
        profiles.put(profileId, Objects.requireNonNull(profile, "profile"));
        return new Snapshot(source.globalProfileId(), profiles, source.crateAssignments());
    }

    public static Snapshot withGlobal(Snapshot source, String rawProfileId) {
        return new Snapshot(assignmentId(rawProfileId, "global profile"), source.profiles(), source.crateAssignments());
    }

    public static Snapshot assign(Snapshot source, String rawCrateId, String rawProfileId) {
        String crateId = id(rawCrateId, "crate assignment");
        String profileId = assignmentId(rawProfileId, "crate profile");
        Map<String, String> assignments = new LinkedHashMap<>(source.crateAssignments());
        assignments.put(crateId, profileId);
        return new Snapshot(source.globalProfileId(), source.profiles(), assignments);
    }

    public static Snapshot inheritGlobal(Snapshot source, String rawCrateId) {
        String crateId = id(rawCrateId, "crate assignment");
        Map<String, String> assignments = new LinkedHashMap<>(source.crateAssignments());
        assignments.remove(crateId);
        return new Snapshot(source.globalProfileId(), source.profiles(), assignments);
    }

    public static Snapshot removeProfile(Snapshot source, String rawProfileId) {
        String profileId = id(rawProfileId, "profile");
        if (LEGACY_INHERIT.equals(profileId)) throw new IllegalArgumentException("Legacy inheritance cannot be removed");
        if (profileId.equals(source.globalProfileId())) throw new IllegalArgumentException("The global idle profile cannot be removed");
        if (source.crateAssignments().containsValue(profileId)) {
            throw new IllegalArgumentException("Idle profile is still assigned to one or more crates");
        }
        Map<String, IdleAnimationProfile> profiles = new LinkedHashMap<>(source.profiles());
        profiles.remove(profileId);
        return new Snapshot(source.globalProfileId(), profiles, source.crateAssignments());
    }

    private static IdleAnimationProfile readProfile(YamlConfiguration yaml, String path, IdleAnimationProfile legacy) {
        IdleAnimationStyle style = IdleAnimationStyle.parse(yaml.getString(path + ".style", legacy.style().name()));
        Particle particle;
        try {
            particle = Particle.valueOf(yaml.getString(path + ".particle", legacy.particle().name())
                    .trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException(path + ".particle is invalid", error);
        }
        return new IdleAnimationProfile(style, particle,
                yaml.getDouble(path + ".radius", legacy.radius()),
                yaml.getDouble(path + ".height", legacy.height()),
                yaml.getInt(path + ".points", legacy.points()),
                yaml.getDouble(path + ".rotation-speed", legacy.rotationSpeed()),
                yaml.getDouble(path + ".vertical-speed", legacy.verticalSpeed()),
                yaml.getInt(path + ".particles-per-point", legacy.particlesPerPoint()),
                yaml.getDouble(path + ".receiver-range", legacy.receiverRange()),
                yaml.getInt(path + ".max-per-crate-per-tick", legacy.maxPerCratePerTick()),
                yaml.getInt(path + ".max-per-viewer-per-tick", legacy.maxPerViewerPerTick()));
    }

    private static void writeProfile(YamlConfiguration yaml, String path, IdleAnimationProfile profile) {
        yaml.set(path + ".style", profile.style().name());
        yaml.set(path + ".particle", profile.particle().name());
        yaml.set(path + ".radius", profile.radius());
        yaml.set(path + ".height", profile.height());
        yaml.set(path + ".points", profile.points());
        yaml.set(path + ".rotation-speed", profile.rotationSpeed());
        yaml.set(path + ".vertical-speed", profile.verticalSpeed());
        yaml.set(path + ".particles-per-point", profile.particlesPerPoint());
        yaml.set(path + ".receiver-range", profile.receiverRange());
        yaml.set(path + ".max-per-crate-per-tick", profile.maxPerCratePerTick());
        yaml.set(path + ".max-per-viewer-per-tick", profile.maxPerViewerPerTick());
    }

    private static String assignmentId(String raw, String label) {
        String value = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        if (LEGACY_INHERIT.equals(value)) return value;
        return id(value, label);
    }

    private static String id(String raw, String label) {
        String value = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        if (!CrateRegistry.validId(value)) throw new IllegalArgumentException("Invalid " + label + " ID: " + raw);
        return value;
    }
}
