package com.antondev.crates.item;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

class ItemSnapshotCodecTest {
    private ItemSnapshotCodec codec;

    @BeforeEach
    void setUp() {
        MockBukkit.mock();
        codec = new ItemSnapshotCodec();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void captureIsNonDestructiveAndRoundTripsCompletePaperData() {
        ItemStack source = customItem("alpha", 37);
        byte[] before = source.serializeAsBytes();

        ItemSnapshotCodec.Snapshot snapshot = codec.capture(source);
        ItemStack restored = codec.restoreTemplate(snapshot);

        assertArrayEquals(before, source.serializeAsBytes());
        assertEquals(37, source.getAmount());
        assertEquals(37, snapshot.capturedAmount());
        assertEquals(1, restored.getAmount());
        ItemStack normalized = source.clone();
        normalized.setAmount(1);
        assertTrue(restored.isSimilar(normalized));
        assertTrue(snapshot.customDataPresent());
        assertEquals(64, snapshot.sha256().length());
        assertEquals(12, snapshot.shortFingerprint().length());
    }

    @Test
    void deliverySplitsOnlyByTheRealMaximumStackSize() {
        ItemSnapshotCodec.Snapshot snapshot = codec.capture(customItem("split", 7));
        var stacks = codec.deliveryStacks(snapshot, 130);

        assertEquals(Arrays.asList(64, 64, 2), stacks.stream().map(ItemStack::getAmount).toList());
        assertEquals(130, stacks.stream().mapToInt(ItemStack::getAmount).sum());
        ItemStack reference = codec.restoreTemplate(snapshot);
        assertTrue(stacks.stream().allMatch(reference::isSimilar));
    }

    @Test
    void metadataDifferentLookalikesHaveDifferentFingerprints() {
        ItemSnapshotCodec.Snapshot first = codec.capture(customItem("first", 1));
        ItemSnapshotCodec.Snapshot second = codec.capture(customItem("second", 1));

        assertNotEquals(first.sha256(), second.sha256());
    }

    @Test
    void corruptPayloadIsRejectedWithoutBeingRewritten() {
        ItemSnapshotCodec.Snapshot valid = codec.capture(customItem("safe", 1));
        byte[] corrupt = valid.bytes();
        corrupt[corrupt.length / 2] ^= 0x01;
        ItemSnapshotCodec.Snapshot damaged = new ItemSnapshotCodec.Snapshot(corrupt, valid.material(),
                valid.capturedAmount(), corrupt.length, valid.sha256(), valid.customDataPresent(),
                valid.containerContentsPresent(), valid.capturedAt());

        assertThrows(IllegalArgumentException.class, () -> codec.restoreTemplate(damaged));
        assertArrayEquals(corrupt, damaged.bytes());
    }

    @Test
    void snapshotPayloadIsDefensivelyCopied() {
        ItemSnapshotCodec.Snapshot snapshot = codec.capture(customItem("copy", 1));
        byte[] external = snapshot.bytes();
        external[0] ^= 0x01;

        assertNotEquals(external[0], snapshot.bytes()[0]);
    }

    private static ItemStack customItem(String identity, int amount) {
        ItemStack item = new ItemStack(Material.DIAMOND, amount);
        item.editMeta(meta -> {
            meta.displayName(Component.text("Exact custom item"));
            meta.lore(java.util.List.of(Component.text("Preserve every component")));
            meta.setEnchantmentGlintOverride(true);
            meta.getPersistentDataContainer().set(new NamespacedKey("plexoncrates_test", "identity"),
                    PersistentDataType.STRING, identity);
        });
        return item;
    }
}
