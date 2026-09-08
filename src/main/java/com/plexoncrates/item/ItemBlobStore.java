package com.plexoncrates.item;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Locale;
import java.util.Objects;

/** Content-addressed immutable store for exact ItemStack payloads. */
public final class ItemBlobStore {
    private final Path root;

    public ItemBlobStore(Path root) {
        this.root = Objects.requireNonNull(root, "root");
    }

    public String put(ExactItemSnapshot snapshot) throws IOException {
        Objects.requireNonNull(snapshot, "snapshot");
        snapshot.verifyIntegrity();
        return put(snapshot.encodedBytes());
    }

    public String put(byte[] bytes) throws IOException {
        Objects.requireNonNull(bytes, "bytes");
        if (bytes.length == 0) throw new IllegalArgumentException("Cannot store an empty item blob");

        String hash = ExactItemSnapshot.sha256Hex(bytes);
        Files.createDirectories(root);
        Path target = pathFor(hash);
        if (Files.exists(target)) {
            verifyExisting(target, hash);
            return hash;
        }

        Path temp = Files.createTempFile(root, hash + ".", ".tmp");
        boolean moved = false;
        try {
            Files.write(temp, bytes, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temp, target);
            } catch (java.nio.file.FileAlreadyExistsException raced) {
                verifyExisting(target, hash);
            }
            moved = true;
        } finally {
            if (!moved) Files.deleteIfExists(temp);
        }
        verifyExisting(target, hash);
        return hash;
    }

    public byte[] read(String sha256) throws IOException {
        String hash = normalizeHash(sha256);
        Path target = pathFor(hash);
        byte[] bytes = Files.readAllBytes(target);
        String actual = ExactItemSnapshot.sha256Hex(bytes);
        if (!hash.equals(actual)) {
            throw new IOException("Item blob checksum mismatch for " + hash);
        }
        return bytes;
    }

    public ExactItemSnapshot readSnapshot(String sha256) throws IOException {
        return ExactItemSnapshot.fromBytes(read(sha256));
    }

    public boolean contains(String sha256) {
        try {
            return Files.isRegularFile(pathFor(normalizeHash(sha256)));
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }

    public Path root() {
        return root;
    }

    private void verifyExisting(Path path, String expectedHash) throws IOException {
        byte[] existing = Files.readAllBytes(path);
        String actual = ExactItemSnapshot.sha256Hex(existing);
        if (!expectedHash.equals(actual)) {
            throw new IOException("Existing item blob failed integrity validation: " + path.getFileName());
        }
    }

    private Path pathFor(String hash) {
        return root.resolve(hash + ".bin");
    }

    private static String normalizeHash(String raw) {
        if (raw == null) throw new IllegalArgumentException("Snapshot hash is required");
        String hash = raw.toLowerCase(Locale.ROOT).trim();
        if (!hash.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Invalid SHA-256 item snapshot reference: " + raw);
        }
        return hash;
    }
}
