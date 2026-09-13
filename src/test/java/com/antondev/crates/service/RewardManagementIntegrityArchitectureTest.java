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
        String method = method(source, "public void copyReward(");
        assertTrue(method.contains("section.getValues(true)"));
        assertTrue(method.contains("entry.getValue()"));
        assertFalse(method.contains("new ItemStack"));
        assertFalse(method.contains("Material.matchMaterial"));
        assertFalse(method.contains("ItemCodec.encode"));
    }

    @Test
    void reorderMovesWholeRewardSectionsWithoutReencodingExactItems() throws Exception {
        String source = Files.readString(REGISTRY);
        String method = method(source, "public void moveReward(");
        assertTrue(method.contains("reward.getValues(true)"));
        assertTrue(method.contains("entry.getValue()"));
        assertFalse(method.contains("new ItemStack"));
        assertFalse(method.contains("Material.matchMaterial"));
        assertFalse(method.contains("ItemCodec.encode"));
    }

    private static String method(String source, String signature) {
        int from = source.indexOf(signature);
        if (from < 0) throw new AssertionError("Could not find " + signature);
        int next = source.indexOf("\n    public ", from + signature.length());
        return source.substring(from, next < 0 ? source.length() : next);
    }
}
