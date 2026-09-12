package com.antondev.crates.animation;

import com.antondev.crates.domain.crate.AnimationType;
import com.antondev.crates.service.CrateRegistry;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Immutable registry for named opening-animation profiles and per-crate
 * assignments. It contains presentation configuration only.
 */
public final class OpeningAnimationProfiles {
    public static final int CONFIG_VERSION = 1;
    public static final String BUILTIN_DEFAULT = "default";

    public record Snapshot(
            String globalProfileId,
            Map<String, OpeningAnimationProfile> profiles,
            Map<String, String> crateAssignments) {
        public Snapshot {
            globalProfileId = id(globalProfileId, "global profile");
            profiles = Map.copyOf(new LinkedHashMap<>(Objects.requireNonNull(profiles, "profiles")));
            crateAssignments = Map.copyOf(new LinkedHashMap<>(Objects.requireNonNull(crateAssignments, "crateAssignments")));
            if (!profiles.containsKey(globalProfileId)) {
                throw new IllegalArgumentException("Global opening animation profile does not exist: " + globalProfileId);
            }
            for (Map.Entry<String, String> entry : crateAssignments.entrySet()) {
                String crateId = id(entry.getKey(), "crate assignment");
                if (!crateId.equals(entry.getKey())) {
                    throw new IllegalArgumentException("Crate animation assignment IDs must be normalized: " + entry.getKey());
                }
                String profileId = id(entry.getValue(), "crate profile");
                if (!profiles.containsKey(profileId)) {
                    throw new IllegalArgumentException("Crate " + crateId + " references unknown animation profile " + profileId);
                }
            }
        }
    }

    private volatile Snapshot snapshot;

    public OpeningAnimationProfiles(Snapshot snapshot) {
        this.snapshot = Objects.requireNonNull(snapshot, "snapshot");
    }

    public Snapshot snapshot() {
        return snapshot;
    }

    public void apply(Snapshot next) {
        snapshot = Objects.requireNonNull(next, "next");
    }

    public Optional<OpeningAnimationProfile> named(String profileId) {
        if (profileId == null) return Optional.empty();
        return Optional.ofNullable(snapshot.profiles().get(profileId.trim().toLowerCase(Locale.ROOT)));
    }

    /**
     * Resolves a 6.0 assignment first and falls back to the crate's accepted
     * legacy animation value if no explicit assignment exists.
     */
    public OpeningAnimationProfile resolve(String crateId, AnimationType legacy) {
        String normalized = crateId == null ? "" : crateId.trim().toLowerCase(Locale.ROOT);
        String assigned = snapshot.crateAssignments().get(normalized);
        if (assigned != null) return snapshot.profiles().get(assigned);
        OpeningAnimationProfile global = snapshot.profiles().get(snapshot.globalProfileId());
        return global == null ? OpeningAnimationProfile.fromLegacy(legacy) : global;
    }

    public static Snapshot defaults(AnimationType legacyDefault) {
        OpeningAnimationProfile profile = OpeningAnimationProfile.fromLegacy(legacyDefault);
        return new Snapshot(BUILTIN_DEFAULT, Map.of(BUILTIN_DEFAULT, profile), Map.of());
    }

    public static Snapshot load(File file, AnimationType legacyDefault) {
        if (file == null || !file.isFile()) return defaults(legacyDefault);
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        int version = yaml.getInt("config-version", CONFIG_VERSION);
        if (version != CONFIG_VERSION) {
            throw new IllegalArgumentException("Unsupported animations.yml config-version; expected " + CONFIG_VERSION);
        }
        ConfigurationSection root = yaml.getConfigurationSection("profiles");
        if (root == null || root.getKeys(false).isEmpty()) return defaults(legacyDefault);
        Map<String, OpeningAnimationProfile> profiles = new LinkedHashMap<>();
        for (String raw : root.getKeys(false)) {
            String profileId = id(raw, "profile");
            if (!profileId.equals(raw)) throw new IllegalArgumentException("Animation profile IDs must be normalized: " + raw);
            profiles.put(profileId, readProfile(yaml, "profiles." + profileId));
        }
        String global = id(yaml.getString("global-profile", BUILTIN_DEFAULT), "global profile");
        Map<String, String> assignments = new LinkedHashMap<>();
        ConfigurationSection crates = yaml.getConfigurationSection("crate-profiles");
        if (crates != null) {
            for (String rawCrate : crates.getKeys(false)) {
                String crateId = id(rawCrate, "crate assignment");
                if (!crateId.equals(rawCrate)) {
                    throw new IllegalArgumentException("Crate animation assignment IDs must be normalized: " + rawCrate);
                }
                assignments.put(crateId, id(crates.getString(rawCrate, ""), "crate profile"));
            }
        }
        return new Snapshot(global, profiles, assignments);
    }

    /** Serializes a validated snapshot without touching disk. */
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

    public static Snapshot withProfile(Snapshot source, String rawId, OpeningAnimationProfile profile) {
        Objects.requireNonNull(source, "source");
        String profileId = id(rawId, "profile");
        Map<String, OpeningAnimationProfile> profiles = new LinkedHashMap<>(source.profiles());
        profiles.put(profileId, Objects.requireNonNull(profile, "profile"));
        return new Snapshot(source.globalProfileId(), profiles, source.crateAssignments());
    }

    public static Snapshot withGlobal(Snapshot source, String rawProfileId) {
        Objects.requireNonNull(source, "source");
        return new Snapshot(id(rawProfileId, "global profile"), source.profiles(), source.crateAssignments());
    }

    public static Snapshot assign(Snapshot source, String rawCrateId, String rawProfileId) {
        Objects.requireNonNull(source, "source");
        String crateId = id(rawCrateId, "crate assignment");
        String profileId = id(rawProfileId, "crate profile");
        Map<String, String> assignments = new LinkedHashMap<>(source.crateAssignments());
        assignments.put(crateId, profileId);
        return new Snapshot(source.globalProfileId(), source.profiles(), assignments);
    }

    public static Snapshot inheritGlobal(Snapshot source, String rawCrateId) {
        Objects.requireNonNull(source, "source");
        String crateId = id(rawCrateId, "crate assignment");
        Map<String, String> assignments = new LinkedHashMap<>(source.crateAssignments());
        assignments.remove(crateId);
        return new Snapshot(source.globalProfileId(), source.profiles(), assignments);
    }

    public static Snapshot removeProfile(Snapshot source, String rawProfileId) {
        Objects.requireNonNull(source, "source");
        String profileId = id(rawProfileId, "profile");
        if (profileId.equals(source.globalProfileId())) {
            throw new IllegalArgumentException("The global animation profile cannot be removed");
        }
        if (source.crateAssignments().containsValue(profileId)) {
            throw new IllegalArgumentException("Animation profile is still assigned to one or more crates");
        }
        Map<String, OpeningAnimationProfile> profiles = new LinkedHashMap<>(source.profiles());
        profiles.remove(profileId);
        return new Snapshot(source.globalProfileId(), profiles, source.crateAssignments());
    }

    private static OpeningAnimationProfile readProfile(YamlConfiguration yaml, String path) {
        OpeningAnimationStyle style;
        try {
            style = OpeningAnimationStyle.valueOf(yaml.getString(path + ".style", "ROULETTE")
                    .trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException(path + ".style is invalid", error);
        }
        OpeningAnimationProfile defaults = OpeningAnimationProfile.defaults(style);
        EnumMap<OpeningAnimationStage, Integer> ticks = new EnumMap<>(OpeningAnimationStage.class);
        for (OpeningAnimationStage stage : OpeningAnimationStage.values()) {
            String stagePath = path + ".stages." + stage.name().toLowerCase(Locale.ROOT) + "-ticks";
            ticks.put(stage, yaml.contains(stagePath) ? yaml.getInt(stagePath) : defaults.ticks(stage));
        }
        Particle particle;
        try {
            particle = Particle.valueOf(yaml.getString(path + ".particle", defaults.particle().name())
                    .trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException(path + ".particle is invalid", error);
        }
        Sound sound = soundConstant(yaml.getString(path + ".sound", soundConstantName(defaults.sound())), path + ".sound");
        return new OpeningAnimationProfile(style, ticks, particle, sound,
                (float) yaml.getDouble(path + ".sound-volume", defaults.soundVolume()),
                (float) yaml.getDouble(path + ".sound-pitch", defaults.soundPitch()),
                yaml.getInt(path + ".particle-budget-per-tick", defaults.particleBudgetPerTick()),
                yaml.getDouble(path + ".receiver-range", defaults.receiverRange()),
                yaml.getBoolean(path + ".summary-on-finish", defaults.summaryOnFinish()));
    }

    private static void writeProfile(YamlConfiguration yaml, String path, OpeningAnimationProfile profile) {
        yaml.set(path + ".style", profile.style().name());
        for (OpeningAnimationStage stage : OpeningAnimationStage.values()) {
            yaml.set(path + ".stages." + stage.name().toLowerCase(Locale.ROOT) + "-ticks", profile.ticks(stage));
        }
        yaml.set(path + ".particle", profile.particle().name());
        yaml.set(path + ".sound", soundConstantName(profile.sound()));
        yaml.set(path + ".sound-volume", profile.soundVolume());
        yaml.set(path + ".sound-pitch", profile.soundPitch());
        yaml.set(path + ".particle-budget-per-tick", profile.particleBudgetPerTick());
        yaml.set(path + ".receiver-range", profile.receiverRange());
        yaml.set(path + ".summary-on-finish", profile.summaryOnFinish());
    }

    /**
     * Resolves built-in Sound constants without touching Bukkit's runtime registry.
     * Paper's legacy Sound.valueOf/name bridge reaches Bukkit.getUnsafe(), which is
     * intentionally unavailable during pure configuration validation/unit tests.
     */
    private static Sound soundConstant(String raw, String path) {
        String name = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
        try {
            Field field = Sound.class.getField(name);
            if (!Modifier.isStatic(field.getModifiers()) || !Sound.class.isAssignableFrom(field.getType())) {
                throw new IllegalArgumentException(path + " is not a built-in sound constant: " + raw);
            }
            return (Sound) field.get(null);
        } catch (ReflectiveOperationException error) {
            throw new IllegalArgumentException(path + " is invalid: " + raw, error);
        }
    }

    private static String soundConstantName(Sound sound) {
        Objects.requireNonNull(sound, "sound");
        for (Field field : Sound.class.getFields()) {
            if (!Modifier.isStatic(field.getModifiers()) || !Sound.class.isAssignableFrom(field.getType())) continue;
            try {
                if (field.get(null) == sound) return field.getName();
            } catch (IllegalAccessException error) {
                throw new IllegalStateException("Could not inspect Paper sound constants", error);
            }
        }
        throw new IllegalArgumentException("Only built-in Paper sound constants can be persisted in animations.yml");
    }

    private static String id(String raw, String label) {
        String value = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        if (!CrateRegistry.validId(value)) throw new IllegalArgumentException("Invalid " + label + " ID: " + raw);
        return value;
    }
}
