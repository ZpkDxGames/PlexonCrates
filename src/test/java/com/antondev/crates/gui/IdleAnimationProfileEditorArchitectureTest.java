package com.antondev.crates.gui;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class IdleAnimationProfileEditorArchitectureTest {
    private static final Path EDITOR = Path.of(
            "src/main/java/com/antondev/crates/gui/IdleAnimationProfileEditor.java");
    private static final Path HUB = Path.of(
            "src/main/java/com/antondev/crates/gui/SimulationAdminListener.java");

    @Test
    void editorUsesSharedProfileStoreAndCentralRouterOnly() throws Exception {
        String source = Files.readString(EDITOR);
        assertTrue(source.contains("IdleAnimationProfileStore store"));
        assertTrue(source.contains("store.mutate"));
        assertTrue(source.contains("IdleAnimationProfiles.assign"));
        assertTrue(source.contains("IdleAnimationProfiles.inheritGlobal"));
        assertTrue(source.contains("IdleAnimationProfiles.withGlobal"));
        assertFalse(source.contains("implements Listener"));
        assertFalse(source.contains("@EventHandler"));
        assertFalse(source.contains("runTaskTimer"));
        assertFalse(source.contains("new BukkitRunnable"));
    }

    @Test
    void editorExposesBoundedOneFramePreviewWithoutTransactionAuthority() throws Exception {
        String source = Files.readString(EDITOR);
        assertTrue(source.contains("IdleAnimationMath.sample(profile, 0L)"));
        assertTrue(source.contains("Math.min(256, profile.maxPerViewerPerTick())"));
        assertTrue(source.contains("player.spawnParticle"));
        assertFalse(source.contains("plugin.openings()"));
        assertFalse(source.contains("plugin.keys().consume"));
        assertFalse(source.contains("plugin.database()"));
        assertFalse(source.contains("plugin.statistics()"));
        assertFalse(source.contains("deliver("));
    }

    @Test
    void editorSupportsStyleGeometryBudgetsCloneResetAndAssignments() throws Exception {
        String source = Files.readString(EDITOR);
        assertTrue(source.contains("IdleAnimationStyle.values()"));
        assertTrue(source.contains("case \"radius\""));
        assertTrue(source.contains("case \"height\""));
        assertTrue(source.contains("case \"points\""));
        assertTrue(source.contains("case \"rotation\""));
        assertTrue(source.contains("case \"vertical\""));
        assertTrue(source.contains("case \"per-point\""));
        assertTrue(source.contains("case \"range\""));
        assertTrue(source.contains("case \"crate-budget\""));
        assertTrue(source.contains("case \"viewer-budget\""));
        assertTrue(source.contains("case \"clone\""));
        assertTrue(source.contains("case \"reset\""));
        assertTrue(source.contains("case \"assign-crate\""));
        assertTrue(source.contains("case \"set-global\""));
    }

    @Test
    void testLabDelegatesIdleEditorBeforeItsOwnInventoryRouting() throws Exception {
        String source = Files.readString(HUB);
        assertTrue(source.contains("IdleAnimationProfileStore.shared(plugin)"));
        assertTrue(source.contains("new IdleAnimationProfileEditor(plugin, idleProfiles, this::openHub)"));
        assertTrue(source.contains("if (idleEditor.routeClick(event)) return true"));
        assertTrue(source.contains("if (idleEditor.routeDrag(event)) return true"));
        assertTrue(source.contains("slot == 23"));
        assertTrue(source.contains("idleEditor.open(player, holder.crateId, holder.snapshot.mode())"));
    }
}
