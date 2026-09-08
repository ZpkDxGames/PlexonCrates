package com.plexoncrates.item;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class ItemBlobStoreTest {
    @TempDir
    Path tempDir;

    @Test
    void storesContentBySha256AndReadsItBack() throws Exception {
        ItemBlobStore store = new ItemBlobStore(tempDir.resolve("items"));
        byte[] bytes = "plexon-exact-item".getBytes(StandardCharsets.UTF_8);

        String hash = store.put(bytes);

        assertEquals(ExactItemSnapshot.sha256Hex(bytes), hash);
        assertTrue(store.contains(hash));
        assertArrayEquals(bytes, store.read(hash));
        assertTrue(Files.isRegularFile(tempDir.resolve("items").resolve(hash + ".bin")));
    }

    @Test
    void deduplicatesIdenticalPayloads() throws Exception {
        ItemBlobStore store = new ItemBlobStore(tempDir.resolve("items"));
        byte[] bytes = new byte[] {1, 2, 3, 4, 5};

        String first = store.put(bytes);
        String second = store.put(bytes.clone());

        assertEquals(first, second);
        try (var entries = Files.list(tempDir.resolve("items"))) {
            assertEquals(1L, entries.count());
        }
    }

    @Test
    void rejectsInvalidReferences() {
        ItemBlobStore store = new ItemBlobStore(tempDir.resolve("items"));
        assertThrows(IllegalArgumentException.class, () -> store.read("../not-a-hash"));
    }

    @Test
    void detectsCorruptedStoredContent() throws Exception {
        ItemBlobStore store = new ItemBlobStore(tempDir.resolve("items"));
        byte[] original = new byte[] {9, 8, 7};
        String hash = store.put(original);
        Files.write(tempDir.resolve("items").resolve(hash + ".bin"), new byte[] {0});

        assertThrows(java.io.IOException.class, () -> store.read(hash));
    }
}
