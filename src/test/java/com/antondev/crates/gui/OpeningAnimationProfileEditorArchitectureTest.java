package com.antondev.crates.gui;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class OpeningAnimationProfileEditorArchitectureTest {
    private static final Path EDITOR = Path.of(
            "src/main/java/com/antondev/crates/gui/OpeningAnimationProfileEditor.java");
    private static final Path LAB = Path.of(
            "src/main/java/com/antondev/crates/gui/SimulationAdminListener.java");

    @Test
    void editorProvidesRequiredProfileManagementWithoutRegisteringAListener() throws Exception {
        String source = Files.readString(EDITOR);
        assertTrue(source.contains("OpeningAnimationStyle.values()"));
        assertTrue(source.contains("OpeningAnimationStage.values()"));
        assertTrue(source.contains("\"particle\""));
        assertTrue(source.contains("\"sound\""));
        assertTrue(source.contains("\"particle-budget\""));
        assertTrue(source.contains("\"range\""));
        assertTrue(source.contains("\"assign-crate\""));
        assertTrue(source.contains("\"set-global\""));
        assertTrue(source.contains("\"clone\""));
        assertTrue(source.contains("\"reset\""));
        assertFalse(source.contains("implements Listener"));
        assertFalse(source.contains("@EventHandler"));
    }

    @Test
    void editorNeverOwnsRewardPaymentOrOpeningTransactions() throws Exception {
        String source = Files.readString(EDITOR);
        assertFalse(source.contains("OpeningService"));
        assertFalse(source.contains("plugin.openings()"));
        assertFalse(source.contains("plugin.keys().consume"));
        assertFalse(source.contains("deliver("));
        assertFalse(source.contains("journal"));
        assertFalse(source.contains("pity"));
    }

    @Test
    void testLabUsesSharedProfilesForEditingAndNonGrantingPreview() throws Exception {
        String source = Files.readString(LAB);
        assertTrue(source.contains("OpeningAnimationProfileStore.shared(plugin)"));
        assertTrue(source.contains("animationEditor.routeClick(event)"));
        assertTrue(source.contains("animationEditor.routeDrag(event)"));
        assertTrue(source.contains("animationEditor.open(player, holder.crateId, holder.snapshot.mode())"));
        assertTrue(source.contains("animationProfiles.resolve(crate.id(), crate.animation())"));
        assertFalse(source.contains("plugin.openings().open("));
    }
}
