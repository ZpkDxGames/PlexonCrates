package com.antondev.crates.item;

import java.util.Objects;
import org.bukkit.inventory.ItemStack;

/**
 * Read-only exact-item diagnostics for admin capture and Test Lab surfaces.
 *
 * <p>This class deliberately derives every diagnostic from {@link ItemSnapshotCodec}'s native Paper
 * serialization. It never rebuilds or normalizes an item from Material, lore, ItemMeta fields, or an
 * external plugin identifier.</p>
 */
public final class ExactItemInspector {
    private final ItemSnapshotCodec codec;

    public ExactItemInspector() {
        this(new ItemSnapshotCodec());
    }

    ExactItemInspector(ItemSnapshotCodec codec) {
        this.codec = Objects.requireNonNull(codec, "codec");
    }

    public record Diagnostics(
            String material,
            int capturedAmount,
            int serializedBytes,
            String sha256,
            String shortFingerprint,
            boolean customDataPresent,
            boolean containerContentsPresent,
            int maximumStackSize) {
        public Diagnostics {
            Objects.requireNonNull(material, "material");
            Objects.requireNonNull(sha256, "sha256");
            Objects.requireNonNull(shortFingerprint, "shortFingerprint");
            if (capturedAmount < 1 || serializedBytes < 1 || maximumStackSize < 1) {
                throw new IllegalArgumentException("Exact item diagnostics contain invalid numeric data");
            }
        }
    }

    /**
     * Validates a proposed capture without mutating it and returns UI-safe diagnostics.
     * Any native-byte round-trip problem fails closed before the caller changes draft state.
     */
    public Diagnostics inspect(ItemStack source) {
        Objects.requireNonNull(source, "source");
        ItemSnapshotCodec.Snapshot snapshot = codec.capture(source);
        ItemStack restored = codec.restoreTemplate(snapshot);
        return new Diagnostics(snapshot.material(), snapshot.capturedAmount(), snapshot.serializedSize(),
                snapshot.sha256(), snapshot.shortFingerprint(), snapshot.customDataPresent(),
                snapshot.containerContentsPresent(), restored.getMaxStackSize());
    }
}
