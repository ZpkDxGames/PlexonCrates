package com.antondev.crates.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.antondev.crates.model.BlockPosition;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;

final class LocationStoreIndexTest {

    @Test
    void indexesWorldAndChunkWithoutGlobalScanningSemantics() {
        UUID world = UUID.randomUUID();
        LocationStore.Link origin = link(world, "world", 1, 64, 1, "basic");
        LocationStore.Link far = link(world, "world", 1600, 64, 1600, "rare");
        LocationStore store = store(origin, far);

        assertEquals(java.util.List.of(origin), java.util.List.copyOf(store.inChunk(world, "world", 0, 0)));
        assertEquals(java.util.List.of(far), java.util.List.copyOf(store.inChunk(world, "world", 100, 100)));
        assertTrue(store.inChunk(world, "world", 1, 1).isEmpty());
    }

    @Test
    void isolatesSameCoordinatesAcrossWorlds() {
        UUID overworld = UUID.randomUUID();
        UUID nether = UUID.randomUUID();
        LocationStore.Link a = link(overworld, "world", 32, 70, 32, "basic");
        LocationStore.Link b = link(nether, "world_nether", 32, 70, 32, "rare");
        LocationStore store = store(a, b);

        assertEquals(java.util.List.of(a), java.util.List.copyOf(store.inChunk(overworld, "world", 2, 2)));
        assertEquals(java.util.List.of(b), java.util.List.copyOf(store.inChunk(nether, "world_nether", 2, 2)));
    }

    @Test
    void supportsNegativeChunkCoordinates() {
        UUID world = UUID.randomUUID();
        LocationStore.Link negative = link(world, "world", -17, 64, -33, "basic");
        LocationStore store = store(negative);

        assertEquals(java.util.List.of(negative), java.util.List.copyOf(store.inChunk(world, "world", -2, -3)));
        assertTrue(store.inChunk(world, "world", -1, -2).isEmpty());
    }

    @Test
    void nearbyChunkQueryReturnsOnlyRequestedRadius() {
        UUID world = UUID.randomUUID();
        LocationStore.Link center = link(world, "world", 0, 64, 0, "basic");
        LocationStore.Link neighbor = link(world, "world", 31, 64, 0, "basic");
        LocationStore.Link outside = link(world, "world", 48, 64, 0, "basic");
        LocationStore store = store(center, neighbor, outside);

        var nearby = store.nearbyChunks(world, "world", 0, 0, 1);
        assertEquals(2, nearby.size());
        assertTrue(nearby.contains(center));
        assertTrue(nearby.contains(neighbor));
    }

    @Test
    void crateCountUsesIndexedMembership() {
        UUID world = UUID.randomUUID();
        LocationStore store = store(
                link(world, "world", 0, 64, 0, "basic"),
                link(world, "world", 16, 64, 0, "BASIC"),
                link(world, "world", 32, 64, 0, "rare"));

        assertEquals(2, store.count("basic"));
        assertEquals(1, store.count("RARE"));
        assertEquals(0, store.count("missing"));
    }

    private static LocationStore store(LocationStore.Link... links) {
        Map<String, LocationStore.Link> values = new LinkedHashMap<>();
        for (LocationStore.Link link : links) values.put(link.position().key(), link);
        return new LocationStore(null, Logger.getLogger("LocationStoreIndexTest"), new LocationStore.Snapshot(values));
    }

    private static LocationStore.Link link(UUID world, String worldName, int x, int y, int z, String crate) {
        return new LocationStore.Link(new BlockPosition(world, worldName, x, y, z), crate, Instant.EPOCH);
    }
}
