package com.antondev.crates.gui.player;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class Phase3PlayerCrateUxArchitectureTest {
    private static final Path UX = Path.of("src/main/java/com/antondev/crates/gui/player/PlayerCrateMenuService.java");
    private static final Path ROUTER = Path.of("src/main/java/com/antondev/crates/gui/player/PlayerCrateCommandRouter.java");
    private static final Path HOLDER = Path.of("src/main/java/com/antondev/crates/gui/MenuHolder.java");
    private static final Path SESSION = Path.of("src/main/java/com/antondev/crates/gui/GuiSessionService.java");
    private static final Path CLAIMS = Path.of("src/main/java/com/antondev/crates/service/ClaimService.java");
    private static final Path PHYSICAL = Path.of("src/main/java/com/antondev/crates/listener/CrateListener.java");
    private static final Path LEGACY_MENU = Path.of("src/main/java/com/antondev/crates/gui/MenuService.java");

    @Test void hallCardRoutesToPreviewAndNeverToOpeningAuthority() throws IOException {
        String method = section(Files.readString(UX), "private void hallClick", "private void previewClick");
        assertTrue(method.contains("openPreview"));
        assertFalse(method.contains("openings().open("));
        assertFalse(method.contains("openSelected("));
    }

    @Test void ordinaryCommandSurfaceRoutesToHallOrPreviewWithoutGlobalInventoryInterception() throws IOException {
        String router = Files.readString(ROUTER);
        assertTrue(router.contains("menus.openHall(player, 0)"));
        assertTrue(router.contains("menus.openPreview(player, crate"));
        assertFalse(router.contains("import org.bukkit.event.inventory.InventoryOpenEvent"));
        assertFalse(router.contains("redirectLegacyPlayerSurface"));
    }

    @Test void normalPlayerUxContainsNoHiddenRightClickConsumeSemantic() throws IOException {
        String source = Files.readString(UX) + Files.readString(ROUTER);
        assertFalse(source.contains("isRightClick"));
        assertFalse(source.contains("RIGHT_CLICK"));
    }

    @Test void previewRenderingIsNonMutating() throws IOException {
        String method = section(Files.readString(UX), "private void renderRewards", "private void previewNavigation");
        assertTrue(method.contains("previewOutcome"));
        assertFalse(method.contains("openings().open("));
        assertFalse(method.contains("openSelected("));
        assertFalse(method.contains("claims().claim"));
    }

    @Test void rewardRenderingNeverQueriesDatabasePerRewardCard() throws IOException {
        String method = section(Files.readString(UX), "private void renderRewards", "private void previewNavigation");
        assertFalse(method.contains("plugin.database()"));
        assertFalse(method.contains("loadVirtualKeyBalance"));
    }

    @Test void playerRenderingNeverRunsSimulation() throws IOException {
        String source = Files.readString(UX);
        assertFalse(source.contains("CrateSimulationService"));
        assertFalse(source.contains("simulate("));
    }

    @Test void playerUxAddsNoRepeatingGuiRefreshTask() throws IOException {
        String source = Files.readString(UX) + Files.readString(ROUTER);
        assertFalse(source.contains("runTaskTimer"));
        assertFalse(source.contains("runTaskTimerAsynchronously"));
    }

    @Test void openOneDelegatesToExistingOpeningAuthorityWithSingleSubmitGuard() throws IOException {
        String method = section(Files.readString(UX), "private void submitRandom", "private void submitSelective");
        assertTrue(method.contains("submitted.add(holder.sessionId())"));
        assertTrue(method.contains("plugin.openings().open("));
        assertTrue(method.contains("paymentPreference()"));
    }

    @Test void openMoreUsesExistingCapacityAuthorityBeforeConfirmation() throws IOException {
        String source = Files.readString(UX);
        assertTrue(source.contains("maximumAvailableAmount(player, crate)"));
        assertTrue(source.contains("PLAYER_QUANTITY"));
        assertTrue(source.contains("PLAYER_MASS_CONFIRM"));
        assertTrue(source.contains("confirm-mass"));
    }

    @Test void massConfirmationRoutesExactSelectedQuantity() throws IOException {
        String method = section(Files.readString(UX), "private void massClick", "private void selectiveClick");
        assertTrue(method.contains("state.context().amount()"));
        assertTrue(method.contains("submitRandom"));
    }

    @Test void selectiveBrowsingRequiresExplicitConfirmation() throws IOException {
        String source = Files.readString(UX);
        assertTrue(source.contains("PLAYER_SELECTIVE_CONFIRM"));
        assertTrue(source.contains("Nothing is granted until you confirm."));
        assertTrue(source.contains("confirm-selective"));
    }

    @Test void selectiveConfirmationDelegatesToExistingAuthorityExactlyOnce() throws IOException {
        String method = section(Files.readString(UX), "private void submitSelective", "private Crate currentCrate");
        assertTrue(method.contains("submitted.add(holder.sessionId())"));
        assertTrue(method.contains("plugin.openings().openSelected("));
    }

    @Test void pendingRewardsListAndClaimUseDurableClaimService() throws IOException {
        String source = Files.readString(UX);
        assertTrue(source.contains("plugin.claims().list("));
        assertTrue(source.contains("plugin.claims().claim("));
        assertTrue(source.contains("Pending Rewards"));
        assertFalse(source.contains("Claim Inbox"));
    }

    @Test void pendingRewardCardsDoNotDisplayClaimIdsOrJournalState() throws IOException {
        String method = section(Files.readString(UX), "private ItemStack pendingCard", "private List<Component> rewardSummary");
        assertFalse(method.contains("claimId()"));
        assertFalse(method.contains("state()"));
        assertFalse(method.contains("idempotencyToken"));
        assertFalse(method.contains("attemptToken"));
    }

    @Test void fullInventoryClaimPathPreservesPendingRewardAndDoesNotWorldDrop() throws IOException {
        String source = Files.readString(CLAIMS);
        assertTrue(source.contains("InventoryPlanner.fits"));
        assertTrue(source.contains("releaseClaim"));
        assertFalse(source.contains("dropItemNaturally"));
        assertFalse(source.contains("dropItem("));
    }

    @Test void playerFacingSourceDoesNotExposeReliabilityEnumsOrSchemaTerms() throws IOException {
        String source = Files.readString(UX);
        for (String forbidden : new String[]{"PAYMENT_ATTEMPTED", "GRANT_ATTEMPTED", "MANUAL_REVIEW", "SQLITE_BUSY", "schema 4", "journal version"}) {
            assertFalse(source.contains(forbidden), forbidden);
        }
    }

    @Test void crateAndRewardInternalIdsAreOnlyBoundAsHiddenActionState() throws IOException {
        String source = Files.readString(UX);
        assertFalse(source.contains("<gray>Crate ID"));
        assertFalse(source.contains("<gray>Reward ID"));
        assertFalse(source.contains("ID:</gray>"));
        assertTrue(source.contains("holder.bind(slot, \"preview\", crate.id())"));
    }

    @Test void asyncUpdatesValidateExactExistingGuiSessionAndRevision() throws IOException {
        String source = Files.readString(UX);
        assertTrue(source.contains("plugin.guiSessions().validate"));
        assertTrue(source.contains("plugin.runtime().crateRevision(holder.crateId()) == expectedRevision"));
        assertTrue(source.contains("plugin.runtime().snapshot().revision() == expectedRevision"));
    }

    @Test void playerUxReusesExistingGuiSessionAuthority() throws IOException {
        String session = Files.readString(SESSION);
        String holder = Files.readString(HOLDER);
        assertTrue(session.contains("new PlayerCrateCommandRouter(plugin)"));
        assertTrue(holder.contains("PLAYER_HALL"));
        assertTrue(session.contains("ConcurrentHashMap<UUID, Active> active"));
        assertFalse(Files.readString(UX).contains("new GuiSessionService"));
    }

    @Test void physicalBlockOpeningAuthorityRemainsSeparateAndUntouchedByPlayerHall() throws IOException {
        String source = Files.readString(PHYSICAL);
        assertTrue(source.contains("plugin.openings().open(event.getPlayer(), crate, 1, OpenSource.BLOCK, position)"));
        assertTrue(source.contains("openMassOpening(event.getPlayer(), crate, OpenSource.BLOCK"));
    }

    @Test void resultSummaryStillUsesExactRewardDisplayCopies() throws IOException {
        String source = Files.readString(LEGACY_MENU);
        String method = section(source, "public void openSummary", "/** Opens or refreshes");
        assertTrue(method.contains("entry.reward().displayCopy()"));
        assertTrue(method.contains("Received"));
        assertFalse(method.contains("transactionId"));
    }

    private static String section(String source, String start, String end) {
        int from = source.indexOf(start);
        int to = source.indexOf(end, from + start.length());
        if (from < 0 || to < 0) throw new AssertionError("Could not isolate source section " + start);
        return source.substring(from, to);
    }
}
