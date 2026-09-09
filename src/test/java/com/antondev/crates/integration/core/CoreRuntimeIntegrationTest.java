package com.antondev.crates.integration.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.antondev.crates.PlexonCrates;
import com.zpkdxgames.plexoncore.api.PlexonCoreAPI;
import com.zpkdxgames.plexoncore.api.PlexonCoreAPI.CoreVersion;
import com.zpkdxgames.plexoncore.integration.IntegrationRegistry;
import com.zpkdxgames.plexoncore.module.ModuleRegistry;
import com.zpkdxgames.plexoncore.module.ModuleRegistry.ModuleDescriptor;
import com.zpkdxgames.plexoncore.module.ModuleRegistry.ModuleState;
import com.zpkdxgames.plexoncore.module.ModuleRegistry.ModuleVersionRange;
import java.time.Instant;
import java.util.Set;
import org.bukkit.plugin.ServicePriority;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

final class CoreRuntimeIntegrationTest {
    private ServerMock server;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        server.addSimpleWorld("Survival_World");
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void core204RegistersRuntimeModeAndFinishesReadyWhileInteractionsRemainLocal() {
        PlexonCrates crates = loadWithCore(2, 0, "2.0.4");

        assertEquals("CORE_RUNTIME", crates.coreBridge().mode());
        assertEquals("LOCAL", crates.coreBridge().interactionOwnership());
        assertEquals("LOCAL", crates.coreBridge().protectionOwnership());
        assertFalse(crates.coreBridge().sharedInteractionAvailable());
        assertEquals("READY", crates.coreBridge().registrationState());
    }

    @Test
    void core1RemainsSupportedAsLegacyMode() {
        PlexonCrates crates = loadWithCore(1, 0, "1.0.0");
        assertEquals("CORE_LEGACY", crates.coreBridge().mode());
        assertTrue(crates.coreBridge().compatible());
    }

    @Test
    void futureIncompatibleCoreSafelyKeepsCratesStandalone() {
        PlexonCrates crates = loadWithCore(3, 0, "3.0.0");
        assertEquals("STANDALONE", crates.coreBridge().mode());
        assertFalse(crates.coreBridge().compatible());
    }

    @Test
    void duplicateModuleOwnedByAnotherPluginIsNeitherReplacedNorRemoved() {
        var corePlugin = MockBukkit.createMockPlugin("PlexonCore");
        var otherPlugin = MockBukkit.createMockPlugin("OtherCrates");
        CoreVersion version = CoreVersion.of(2, 0, "2.0.4");
        ModuleRegistry modules = new ModuleRegistry(version);
        IntegrationRegistry integrations = new IntegrationRegistry(server.getPluginManager());
        ModuleDescriptor existing = new ModuleDescriptor(
                CoreBridge.MODULE_ID,
                "Other Crates",
                otherPlugin.getName(),
                otherPlugin.getPluginMeta().getVersion(),
                otherPlugin,
                ModuleVersionRange.parse(CoreBridge.SUPPORTED_API_RANGE),
                Set.of("other-crate-engine"),
                ModuleState.READY,
                "Already registered",
                Instant.now());
        assertTrue(modules.register(existing).success());

        PlexonCoreAPI api = mock(PlexonCoreAPI.class);
        when(api.version()).thenReturn(version);
        when(api.modules()).thenReturn(modules);
        when(api.integrations()).thenReturn(integrations);
        server.getServicesManager().register(PlexonCoreAPI.class, api, corePlugin, ServicePriority.Normal);

        PlexonCrates crates = MockBukkit.load(PlexonCrates.class);
        assertEquals("STANDALONE", crates.coreBridge().mode());
        assertSame(otherPlugin, modules.find(CoreBridge.MODULE_ID).orElseThrow().plugin());

        server.getPluginManager().disablePlugin(crates);
        assertSame(otherPlugin, modules.find(CoreBridge.MODULE_ID).orElseThrow().plugin());
        assertEquals(ModuleState.READY, modules.find(CoreBridge.MODULE_ID).orElseThrow().state());
    }

    private PlexonCrates loadWithCore(int major, int minor, String pluginVersion) {
        var corePlugin = MockBukkit.createMockPlugin("PlexonCore");
        CoreVersion version = CoreVersion.of(major, minor, pluginVersion);
        ModuleRegistry modules = new ModuleRegistry(version);
        IntegrationRegistry integrations = new IntegrationRegistry(server.getPluginManager());
        PlexonCoreAPI api = mock(PlexonCoreAPI.class);
        when(api.version()).thenReturn(version);
        when(api.modules()).thenReturn(modules);
        when(api.integrations()).thenReturn(integrations);
        server.getServicesManager().register(PlexonCoreAPI.class, api, corePlugin, ServicePriority.Normal);

        PlexonCrates crates = MockBukkit.load(PlexonCrates.class);
        if (major < 3) {
            var descriptor = modules.find("crates").orElseThrow();
            assertSame(crates, descriptor.plugin());
            assertEquals("PlexonCrates", descriptor.displayName());
            assertEquals(ModuleState.READY, descriptor.state());
            assertTrue(descriptor.capabilities().contains("crate-open-event"));
            assertTrue(descriptor.capabilities().contains("optimized-local-interactions"));

            var integration = integrations.get("PLEXON_CRATES").orElseThrow();
            assertEquals(IntegrationRegistry.IntegrationState.READY, integration.state());
            assertEquals(crates.getPluginMeta().getVersion(), integration.version());
        }
        return crates;
    }
}
