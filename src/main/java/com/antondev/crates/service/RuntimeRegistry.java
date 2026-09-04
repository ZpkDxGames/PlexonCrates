package com.antondev.crates.service;

import com.antondev.crates.model.Crate;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/** Owns the one atomic reference to the active immutable runtime snapshot. */
public final class RuntimeRegistry {
    private final AtomicReference<RuntimeSnapshot> active;

    public RuntimeRegistry(RuntimeSnapshot initial) {
        active = new AtomicReference<>(Objects.requireNonNull(initial, "initial"));
    }

    public RuntimeSnapshot snapshot() {
        return active.get();
    }

    public Optional<Crate> find(String crateId) {
        return active.get().find(crateId);
    }

    public long crateRevision(String crateId) {
        return active.get().crateRevision(crateId);
    }

    public Optional<byte[]> payload(String crateId) {
        return active.get().payload(crateId);
    }

    public Collection<Crate> all() {
        return active.get().all();
    }

    public List<Crate> ordered() {
        return active.get().ordered();
    }

    public int rewardCount() {
        return active.get().rewardCount();
    }

    public String ids() {
        return active.get().ids();
    }

    public void install(long runtimeRevision, long crateRevision, Crate crate, byte[] payload) {
        RuntimeSnapshot.Entry entry = new RuntimeSnapshot.Entry(crateRevision, crate, payload);
        active.updateAndGet(current -> current.install(runtimeRevision, entry));
    }
}
