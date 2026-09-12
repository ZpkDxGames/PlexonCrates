package com.antondev.crates.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class RewardManagementIntegrityArchitectureTest {
    private static final Path REGISTRY = Path.of(
            "src/main/java/com/antondev/crates/service/CrateRegistry.java");

    @Test
    void copyRewardCopiesAuthoredLeafDataInsteadOfRebuildingItems() throws Exception {
        String source = Files.readString(REGISTRY);
        String method = section(source, "public void copyReward(", "public void moveReward(");
        assertTrue(method.contains("section.getValues(true)"));
        assertTrue(method.contains("yaml.set(targetPath + \".\" + entry.getKey(), entry.getValue())"));
        assertFalse(method.contains("new ItemStack"));
        assertFalse(method.contains("Material.matchMaterial"));
        assertFalse(method.contains("ItemCodec.encode"));
    }

    @Test
    void reorderMovesWholeRewardSectionsWithoutReencodingExactItems() throws Exception {
        String source = Files.readString(REGISTRY);
        String method = section(source, "public void moveReward(", "public void deleteReward(");
        assertTrue(method.contains("reward.getValues(true)"));
        assertTrue(method.contains("yaml.set(\"rewards.\" + current + \".\" + entry.getKey(), entry.getValue())"));
        assertFalse(method.contains("new ItemStack"));
        assertFalse(method.contains("ItemCodec.encode"));
    }

    private static String section(String source, String start, String end) {
        int from = source.indexOf(start);
        int to = source.indexOf(end, from + start.length());
        if (from < 0 || to < 0) throw new AssertionError("Could not isolate " + start);
        return source.substring(from, to);
    }
}
