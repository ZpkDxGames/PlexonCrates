package com.antondev.crates.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class OpeningProfileRuntimeIntegrationArchitectureTest {
    private static final Path SOURCE = Path.of(
            "src/main/java/com/antondev/crates/service/OpeningService.java");

    @Test
    void finalizedOpeningsResolveSixOhProfilesAtPresentationBoundary() throws Exception {
        String source = Files.readString(SOURCE);
        String showResult = section(source, "private void showResult", "private void finishSinglePresentation");
        assertTrue(showResult.contains("animationProfiles.resolve(crate.id(), crate.animation())"));
        assertTrue(showResult.contains("profilePresentation.present(player, crate, reward, profile"));
        assertTrue(showResult.contains("profile.summaryOnFinish()"));
        assertFalse(showResult.contains("switch (crate.animation())"));
        assertFalse(showResult.contains("AnimationType."));
    }

    @Test
    void presentationBoundaryOwnsNoSelectionPaymentJournalOrDeliveryMutation() throws Exception {
        String source = Files.readString(SOURCE);
        String showResult = section(source, "private void showResult", "private void finishSinglePresentation");
        assertFalse(showResult.contains("RewardSelector"));
        assertFalse(showResult.contains("keys().consume"));
        assertFalse(showResult.contains("consumePhysical"));
        assertFalse(showResult.contains("consumeVirtual"));
        assertFalse(showResult.contains("createOpeningTransaction"));
        assertFalse(showResult.contains("finalizeOpening"));
        assertFalse(showResult.contains("deliver("));
        assertFalse(showResult.contains("statistics().record"));
        assertFalse(showResult.contains("pity"));
    }

    @Test
    void sharedProfileRendererIsStoppedWithOpeningServiceLifecycle() throws Exception {
        String source = Files.readString(SOURCE);
        assertTrue(source.contains("OpeningAnimationProfileStore.shared(plugin)"));
        assertTrue(source.contains("OpeningProfilePresentationService.shared(plugin)"));
        String clear = section(source, "public void clear()", "public RerollMetrics rerollMetrics()");
        assertTrue(clear.contains("profilePresentation.stop()"));
    }

    @Test
    void summaryAfterAnimatedSingleDoesNotReopenAfterViewerLeavesOpeningSurface() throws Exception {
        String source = Files.readString(SOURCE);
        String finish = section(source, "private void finishSinglePresentation", "private void announceSingle");
        assertTrue(finish.contains("profile.summaryOnFinish()"));
        assertTrue(finish.contains("player.isOnline()"));
        assertTrue(finish.contains("holder.kind() == MenuHolder.Kind.OPENING"));
        assertTrue(finish.contains("plugin.menus().openSummary"));
    }

    private static String section(String source, String start, String end) {
        int from = source.indexOf(start);
        int to = source.indexOf(end, from + start.length());
        if (from < 0 || to < 0) throw new AssertionError("Could not isolate " + start);
        return source.substring(from, to);
    }
}
