package com.antondev.crates.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class AdminExactItemDiagnosticsArchitectureTest {
    private static final Path ADMIN = Path.of(
            "src/main/java/com/antondev/crates/gui/AdminMenuService.java");
    private static final Path MENUS = Path.of("src/main/resources/menus.yml");

    @Test
    void keysInspectResolvedTemplateRatherThanCosmeticIcon() throws Exception {
        String source = Files.readString(ADMIN);
        assertTrue(source.contains("ExactItemDiagnosticPresentation exactDiagnostics"));
        assertTrue(source.contains("ItemStack exactTemplate = plugin.keys().template(definition.id()).orElse(null)"));
        assertTrue(source.contains("exactDiagnostics.single(entry.exactTemplate(), \"Resolved exact key template\")"));
        assertTrue(source.contains("exactTemplate = exactTemplate == null ? null : exactTemplate.clone()"));
    }

    @Test
    void rewardBuilderKeepsExactInputSlotsUntouchedAndUsesCompanionDiagnostics() throws Exception {
        String source = Files.readString(ADMIN);
        String builder = section(source, "public void openRewardBuilder", "public void openMilestones");
        assertTrue(builder.contains("List<ItemStack> items = draft.items()"));
        assertTrue(builder.contains("reward-builder.exact-diagnostics"));
        assertTrue(builder.contains("exactDiagnostics.bundle(items)"));
        assertTrue(builder.contains("holder.bind(exactDiagnosticsSlot, \"noop\", draft.id())"));
        assertFalse(builder.contains("items.get(index).editMeta"));
        assertFalse(builder.contains("items.get(index).setItemMeta"));
        assertFalse(builder.contains("items.get(index).setType"));
    }

    @Test
    void globalRewardDiagnosticsReadExactDeliveryCopiesOnly() throws Exception {
        String source = Files.readString(ADMIN);
        String global = section(source, "public void openGlobalRewards", "public void openWandSelector");
        assertTrue(global.contains("entry.reward().displayCopy()"));
        assertTrue(global.contains("exactDiagnostics.bundle(entry.reward().itemCopies())"));
        assertFalse(global.contains("entry.reward().items().get"));
    }

    @Test
    void companionSlotIsConfiguredSeparatelyFromRewardInputSlots() {
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(MENUS.toFile());
        List<Integer> inputSlots = yaml.getIntegerList("reward-builder.item-slots");
        int diagnosticSlot = yaml.getInt("reward-builder.exact-diagnostics.slot", -1);
        assertEquals(List.of(10, 11, 12, 13, 14, 15, 16), inputSlots);
        assertEquals(27, diagnosticSlot);
        assertEquals("SPYGLASS", yaml.getString("reward-builder.exact-diagnostics.material"));
        assertFalse(inputSlots.contains(diagnosticSlot));
    }

    private static String section(String source, String start, String end) {
        int from = source.indexOf(start);
        int to = source.indexOf(end, from + start.length());
        if (from < 0 || to < 0) throw new AssertionError("Could not isolate " + start);
        return source.substring(from, to);
    }
}
