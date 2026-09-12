package com.antondev.crates.gui;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ExactCaptureArchitectureTest {
    private static final Path EDIT = Path.of("src/main/java/com/antondev/crates/gui/EditSessionService.java");

    @Test
    void rewardCaptureValidatesBeforeDraftMutation() throws Exception {
        String source = Files.readString(EDIT);
        String method = section(source, "public void addItem(ItemStack value)", "public void clearItems()");
        assertTrue(method.indexOf("EXACT_ITEMS.inspect(value)") < method.indexOf("items.add(value.clone())"));
    }

    @Test
    void physicalKeyCaptureValidatesBeforeTemplateReplacement() throws Exception {
        String source = Files.readString(EDIT);
        String method = section(source, "public void template(ItemStack value)", "public ItemStack previous()");
        assertTrue(method.indexOf("EXACT_ITEMS.inspect(value)") < method.indexOf("template = ItemCodec.one(value)"));
    }

    private static String section(String source, String start, String end) {
        int from = source.indexOf(start);
        int to = source.indexOf(end, from + start.length());
        if (from < 0 || to < 0) throw new AssertionError("Could not isolate " + start);
        return source.substring(from, to);
    }
}
