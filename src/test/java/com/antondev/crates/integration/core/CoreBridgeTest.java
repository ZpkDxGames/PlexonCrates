package com.antondev.crates.integration.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.Test;

final class CoreBridgeTest {
    @Test
    void standaloneBridgeReportsMissingCoreWithoutLinkingCoreTypes() {
        CoreBridge bridge = new StandaloneCoreBridge(false, null, null, "PlexonCore is not installed");

        assertFalse(bridge.installed());
        assertFalse(bridge.available());
        assertFalse(bridge.compatible());
        assertEquals("STANDALONE", bridge.mode());
        assertEquals("NOT_INSTALLED", bridge.registrationState());
        assertEquals("-", bridge.pluginVersion());
        assertEquals("-", bridge.apiVersion());
        assertEquals(CoreBridge.ProviderHint.UNKNOWN, bridge.providerHint("PLEXON_KEYS"));
    }

    @Test
    void publicCoreContractUsesCratesIdentityAndCoreOneXRange() {
        assertEquals("crates", CoreBridge.MODULE_ID);
        assertEquals(">=1.0 <2.0", CoreBridge.SUPPORTED_API_RANGE);
    }
}
