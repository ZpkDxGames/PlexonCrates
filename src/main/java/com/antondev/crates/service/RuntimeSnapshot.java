package com.antondev.crates.service;

import com.antondev.crates.domain.crate.CrateState;
import com.antondev.crates.model.Crate;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Immutable published definitions used by every player-facing interaction. */
public final class RuntimeSnapshot {
    public record Entry(long revision, Crate crate, byte[] payload) {
        public Entry {
            if (revision < 1) throw new IllegalArgumentException("Published revision must be positive");
            crate = Objects.requireNonNull(crate, "crate");
            if (crate.state() != CrateState.PUBLISHED) {
                throw new IllegalArgumentException("A runtime crate must be published");
            }
            payload = Objects.requireNonNull(payload, "payload").clone();
            if (payload.length == 0) throw new IllegalArgumentException("Published payload cannot be empty");
        }

        @Override public byte[] payload() { return payload.clone(); }
    }

    private final long revision;
    private final Map<String, Entry> entries;

    public RuntimeSnapshot(long revision, Collection<Entry> entries) {
        if (revision < 0) throw new IllegalArgumentException("Runtime revision cannot be negative");
        var copy = new LinkedHashMap<String, Entry>();
        for (Entry entry : entries) {
            String id = normalize(entry.crate().id());
            if (copy.putIfAbsent(id, entry) != null) {
                throw new IllegalArgumentException("Duplicate published crate ID: " + id);
            }
        }
        this.revision = revision;
        this.entries = Collections.unmodifiableMap(copy);
    }

    public static RuntimeSnapshot empty() {
        return new RuntimeSnapshot(0, List.of());
    }

    public long revision() {
        return revision;
    }

    public Optional<Entry> entry(String crateId) {
        return Optional.ofNullable(entries.get(normalize(crateId)));
    }

    public Optional<Crate> find(String crateId) {
        return entry(crateId).map(Entry::crate);
    }

    public long crateRevision(String crateId) {
        return entry(crateId).map(Entry::revision).orElse(0L);
    }

    public Optional<byte[]> payload(String crateId) {
        return entry(crateId).map(Entry::payload);
    }

    public Collection<Crate> all() {
        return entries.values().stream().map(Entry::crate).toList();
    }

    public List<Crate> ordered() {
        return entries.values().stream().map(Entry::crate).sorted(Comparator
                .comparingInt(Crate::displayOrder).thenComparing(Crate::id)).toList();
    }

    public int rewardCount() {
        return entries.values().stream().map(Entry::crate)
                .mapToInt(crate -> crate.rewards().size()).sum();
    }

    public String ids() {
        return String.join(", ", ordered().stream().map(Crate::id).toList());
    }

    RuntimeSnapshot install(long nextRuntimeRevision, Entry entry) {
        Entry current = entries.get(normalize(entry.crate().id()));
        if (current != null && current.revision() >= entry.revision()) return this;
        var next = new LinkedHashMap<>(entries);
        next.put(normalize(entry.crate().id()), entry);
        return new RuntimeSnapshot(Math.max(revision, nextRuntimeRevision), next.values());
    }

    RuntimeSnapshot remove(long nextRuntimeRevision, String crateId, long crateRevision) {
        String id = normalize(crateId);
        Entry current = entries.get(id);
        if (current == null || current.revision() > crateRevision) {
            return new RuntimeSnapshot(Math.max(revision, nextRuntimeRevision), entries.values());
        }
        var next = new LinkedHashMap<>(entries);
        next.remove(id);
        return new RuntimeSnapshot(Math.max(revision, nextRuntimeRevision), next.values());
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
