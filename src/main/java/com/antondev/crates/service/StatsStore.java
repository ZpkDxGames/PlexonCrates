package com.antondev.crates.service;

import com.antondev.crates.database.DatabaseService;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Fast main-thread statistics view; successful openings are persisted with their journal transaction. */
public final class StatsStore {
    private final Map<String, Long> global = new HashMap<>();
    private final Map<UUID, Map<String, Long>> players = new HashMap<>();

    public StatsStore(DatabaseService.StatsSnapshot snapshot) {
        global.putAll(snapshot.global());
        snapshot.players().forEach((uuid, values) -> players.put(uuid, new HashMap<>(values)));
    }

    public void record(UUID player, String crateId) {
        record(player, crateId, 1);
    }

    public void record(UUID player, String crateId, int amount) {
        if (amount < 1) throw new IllegalArgumentException("Opening amount must be positive");
        global.merge(crateId, (long) amount, Long::sum);
        players.computeIfAbsent(player, ignored -> new HashMap<>()).merge(crateId, (long) amount, Long::sum);
    }

    public long global(String crateId) {
        return global.getOrDefault(crateId, 0L);
    }

    public long player(UUID player, String crateId) {
        return players.getOrDefault(player, Map.of()).getOrDefault(crateId, 0L);
    }

    /** Compatibility method; SQLite writes are already queued transactionally. */
    public boolean save() {
        return false;
    }
}
