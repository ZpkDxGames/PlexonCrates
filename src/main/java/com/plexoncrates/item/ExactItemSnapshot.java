package com.plexoncrates.item;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;
import org.bukkit.Bukkit;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

/** Immutable Paper-native ItemStack snapshot used as the canonical reward/key representation. */
public final class ExactItemSnapshot {
    public static final int FORMAT_VERSION = 2;
    public static final String FORMAT_NAME = "paper-native-nbt-v2";
    public static final int MAX_ENCODED_BYTES = 16 * 1024 * 1024;

    private final int formatVersion;
    private final int minecraftDataVersion;
    private final byte[] encodedBytes;
    private final String sha256;
    private final String materialHint;
    private final int amountHint;
    private final String displayNameHint;
    private final long capturedAt;

    private ExactItemSnapshot(
            int minecraftDataVersion,
            byte[] encodedBytes,
            String materialHint,
            int amountHint,
            String displayNameHint,
            long capturedAt) {
        this.formatVersion = FORMAT_VERSION;
        this.minecraftDataVersion = minecraftDataVersion;
        this.encodedBytes = encodedBytes.clone();
        this.sha256 = sha256Hex(encodedBytes);
        this.materialHint = materialHint;
        this.amountHint = amountHint;
        this.displayNameHint = displayNameHint;
        this.capturedAt = capturedAt;
    }

    public static ExactItemSnapshot capture(ItemStack source) {
        Objects.requireNonNull(source, "source");
        if (source.getType().isAir()) throw new IllegalArgumentException("Cannot capture an air ItemStack");
        return fromCanonicalItem(source.clone(), Instant.now().toEpochMilli());
    }

    /**
     * Loads native bytes through Paper's data-version-aware decoder, then stores the canonical bytes
     * produced by the current server. Paper is allowed to normalize an older native payload during
     * data fixing; rejecting that normalization made otherwise valid custom items unreadable after
     * a server update.
     */
    public static ExactItemSnapshot fromBytes(byte[] encodedBytes) {
        validateSize(encodedBytes);
        ItemStack restored = ItemStack.deserializeBytes(encodedBytes.clone());
        if (restored == null || restored.getType().isAir()) {
            throw new IllegalArgumentException("Native item snapshot decoded to an invalid/air item");
        }
        byte[] canonical = restored.serializeAsBytes();
        validateSize(canonical);
        verifyCanonicalRoundTrip(canonical);
        return create(canonical, restored, Instant.now().toEpochMilli());
    }

    private static ExactItemSnapshot fromCanonicalItem(ItemStack item, long capturedAt) {
        byte[] canonical = item.serializeAsBytes();
        validateSize(canonical);
        ItemStack restored = ItemStack.deserializeBytes(canonical);
        verifyRoundTrip(canonical, restored);
        return create(canonical, restored, capturedAt);
    }

    private static ExactItemSnapshot create(byte[] canonical, ItemStack item, long capturedAt) {
        ItemMeta meta = item.getItemMeta();
        String displayName = meta != null && meta.hasDisplayName() ? meta.getDisplayName() : null;
        int dataVersion;
        try {
            dataVersion = Bukkit.getUnsafe().getDataVersion();
        } catch (RuntimeException unavailable) {
            dataVersion = -1;
        }
        return new ExactItemSnapshot(
                dataVersion,
                canonical,
                item.getType().getKey().toString(),
                item.getAmount(),
                displayName,
                capturedAt);
    }

    private static void verifyCanonicalRoundTrip(byte[] canonical) {
        ItemStack restored = ItemStack.deserializeBytes(canonical.clone());
        verifyRoundTrip(canonical, restored);
    }

    private static void verifyRoundTrip(byte[] expected, ItemStack restored) {
        if (restored == null || restored.getType().isAir()) {
            throw new IllegalStateException("Paper native ItemStack serialization decoded to an invalid item");
        }
        byte[] roundTrip = restored.serializeAsBytes();
        if (!Arrays.equals(expected, roundTrip)) {
            throw new IllegalStateException("Paper native ItemStack serialization is not stable on this server build");
        }
    }

    private static void validateSize(byte[] bytes) {
        Objects.requireNonNull(bytes, "encodedBytes");
        if (bytes.length == 0) throw new IllegalArgumentException("Cannot create an exact snapshot from empty bytes");
        if (bytes.length > MAX_ENCODED_BYTES) {
            throw new IllegalArgumentException("Item snapshot exceeds the 16 MiB safety limit");
        }
    }

    public ItemStack toItemStack() {
        verifyIntegrity();
        ItemStack item = ItemStack.deserializeBytes(encodedBytes.clone());
        verifyRoundTrip(encodedBytes, item);
        return item;
    }

    public void verifyIntegrity() {
        if (!sha256.equals(sha256Hex(encodedBytes))) {
            throw new IllegalStateException("Exact item snapshot checksum mismatch");
        }
    }

    public int formatVersion() { return formatVersion; }
    public String formatName() { return FORMAT_NAME; }
    public int minecraftDataVersion() { return minecraftDataVersion; }
    public byte[] encodedBytes() { return encodedBytes.clone(); }
    public String sha256() { return sha256; }
    public String materialHint() { return materialHint; }
    public int amountHint() { return amountHint; }
    public String displayNameHint() { return displayNameHint; }
    public long capturedAt() { return capturedAt; }

    public static String sha256Hex(byte[] bytes) {
        Objects.requireNonNull(bytes, "bytes");
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is not available", impossible);
        }
    }
}
