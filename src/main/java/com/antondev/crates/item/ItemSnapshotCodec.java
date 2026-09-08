package com.antondev.crates.item;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import org.bukkit.block.BlockState;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.BundleMeta;
import org.bukkit.inventory.meta.ItemMeta;

/**
 * Lossless Paper item snapshots used by 3.0 definitions and value-bearing queues.
 * The stored payload always represents an amount-one template; delivery quantity
 * is explicit metadata and never reconstructed from visible item properties.
 */
public final class ItemSnapshotCodec {
    public static final int DEFAULT_MAXIMUM_PAYLOAD_BYTES = 3_000_000;
    public static final int DEFAULT_MAXIMUM_DELIVERY_AMOUNT = 1_000_000;

    private final int maximumPayloadBytes;
    private final int maximumDeliveryAmount;

    public ItemSnapshotCodec() {
        this(DEFAULT_MAXIMUM_PAYLOAD_BYTES, DEFAULT_MAXIMUM_DELIVERY_AMOUNT);
    }

    public ItemSnapshotCodec(int maximumPayloadBytes, int maximumDeliveryAmount) {
        if (maximumPayloadBytes < 1_024 || maximumPayloadBytes > 16_000_000) {
            throw new IllegalArgumentException("Maximum item payload must be between 1,024 and 16,000,000 bytes");
        }
        if (maximumDeliveryAmount < 1 || maximumDeliveryAmount > 10_000_000) {
            throw new IllegalArgumentException("Maximum delivery amount must be between 1 and 10,000,000");
        }
        this.maximumPayloadBytes = maximumPayloadBytes;
        this.maximumDeliveryAmount = maximumDeliveryAmount;
    }

    public record Snapshot(
            byte[] bytes,
            String material,
            int capturedAmount,
            int serializedSize,
            String sha256,
            boolean customDataPresent,
            boolean containerContentsPresent,
            Instant capturedAt) {

        public Snapshot {
            bytes = Objects.requireNonNull(bytes, "bytes").clone();
            material = Objects.requireNonNull(material, "material");
            sha256 = Objects.requireNonNull(sha256, "sha256");
            capturedAt = Objects.requireNonNull(capturedAt, "capturedAt");
            if (bytes.length == 0 || serializedSize != bytes.length) {
                throw new IllegalArgumentException("Serialized item size does not match its payload");
            }
            if (capturedAmount < 1) throw new IllegalArgumentException("Captured amount must be positive");
            if (!sha256.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Invalid SHA-256 fingerprint");
        }

        @Override
        public byte[] bytes() {
            return bytes.clone();
        }

        public String shortFingerprint() {
            return sha256.substring(0, 12);
        }
    }

    /** Captures an exact copy without mutating or consuming the source stack. */
    public Snapshot capture(ItemStack source) {
        if (source == null || source.getType().isAir()) {
            throw new IllegalArgumentException("An exact non-air item is required");
        }
        ItemStack captured = source.clone();
        int amount = captured.getAmount();
        if (amount < 1 || amount > maximumDeliveryAmount) {
            throw new IllegalArgumentException("Captured amount exceeds the configured delivery bound");
        }
        ItemMeta metadata = captured.hasItemMeta() ? captured.getItemMeta() : null;
        boolean customData = metadata != null && (!metadata.getPersistentDataContainer().getKeys().isEmpty()
                || metadata.hasCustomModelData());
        boolean container = containsContainerContents(metadata);

        captured.setAmount(1);
        byte[] bytes;
        try {
            bytes = captured.serializeAsBytes();
        } catch (RuntimeException error) {
            throw new IllegalArgumentException("Paper could not serialize this exact item", error);
        }
        validatePayloadSize(bytes);
        return new Snapshot(bytes, source.getType().getKey().toString(), amount, bytes.length, sha256(bytes),
                customData, container, Instant.now());
    }

    /** Restores and verifies the immutable amount-one template. */
    public ItemStack restoreTemplate(Snapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        byte[] bytes = snapshot.bytes();
        validatePayloadSize(bytes);
        if (snapshot.serializedSize() != bytes.length || !snapshot.sha256().equals(sha256(bytes))) {
            throw new IllegalArgumentException("Exact item fingerprint verification failed");
        }
        try {
            ItemStack restored = ItemStack.deserializeBytes(bytes);
            if (restored == null || restored.getType().isAir()) {
                throw new IllegalArgumentException("Stored exact item decoded to an empty item");
            }
            restored.setAmount(1);
            return restored;
        } catch (IllegalArgumentException error) {
            throw error;
        } catch (RuntimeException error) {
            throw new IllegalArgumentException("Paper could not decode the stored exact item", error);
        }
    }

    /** Splits one exact template by its real maximum stack size. */
    public List<ItemStack> deliveryStacks(Snapshot snapshot, int totalAmount) {
        if (totalAmount < 1 || totalAmount > maximumDeliveryAmount) {
            throw new IllegalArgumentException("Delivery amount exceeds the configured bound");
        }
        ItemStack template = restoreTemplate(snapshot);
        int maximumStack = Math.max(1, template.getMaxStackSize());
        var result = new ArrayList<ItemStack>();
        int remaining = totalAmount;
        while (remaining > 0) {
            ItemStack stack = template.clone();
            stack.setAmount(Math.min(remaining, maximumStack));
            result.add(stack);
            remaining -= stack.getAmount();
        }
        return List.copyOf(result);
    }

    public List<ItemStack> capturedDeliveryStacks(Snapshot snapshot) {
        return deliveryStacks(snapshot, snapshot.capturedAmount());
    }

    public String fingerprint(byte[] bytes) {
        Objects.requireNonNull(bytes, "bytes");
        validatePayloadSize(bytes);
        return sha256(bytes);
    }

    private void validatePayloadSize(byte[] bytes) {
        if (bytes == null || bytes.length == 0 || bytes.length > maximumPayloadBytes) {
            throw new IllegalArgumentException("Exact item payload is empty or exceeds " + maximumPayloadBytes + " bytes");
        }
    }

    private static boolean containsContainerContents(ItemMeta metadata) {
        if (metadata instanceof BundleMeta bundle && !bundle.getItems().isEmpty()) return true;
        if (!(metadata instanceof BlockStateMeta blockStateMeta)) return false;
        BlockState state = blockStateMeta.getBlockState();
        if (!(state instanceof InventoryHolder holder)) return false;
        for (ItemStack item : holder.getInventory().getContents()) {
            if (item != null && !item.getType().isAir()) return true;
        }
        return false;
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("Java runtime does not provide SHA-256", impossible);
        }
    }
}
