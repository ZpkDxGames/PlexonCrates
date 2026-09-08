package com.antondev.crates.integration.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.antondev.crates.PlexonCrates;
import com.zpkdxgames.plexoncore.api.PlexonCoreAPI;
import com.zpkdxgames.plexoncore.api.PlexonCoreAPI.CoreVersion;
import com.zpkdxgames.plexoncore.integration.IntegrationRegistry;
import com.zpkdxgames.plexoncore.module.ModuleRegistry;
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
    void compatibleCoreRegistersCratesReadyAndPublishesIntegration() {
        var corePlugin = MockBukkit.createMockPlugin("PlexonCore");
        CoreVersion version = CoreVersion.of(1, 0, "1.0.0");
        ModuleRegistry modules = new ModuleRegistry(version);
        IntegrationRegistry integrations = new IntegrationRegistry(server.getPluginManager());
        PlexonCoreAPI api = mock(PlexonCoreAPI.class);
        when(api.version()).thenReturn(version);
        when(api.modules()).thenReturn(modules);
        when(api.integrations()).thenReturn(integrations);
        server.getServicesManager().register(PlexonCoreAPI.class, api, corePlugin, ServicePriority.Normal);

        PlexonCrates crates = MockBukkit.load(PlexonCrates.class);

        assertEquals("CORE", crates.coreBridge().mode());
        assertEquals("READY", crates.coreBridge().registrationState());
        var descriptor = modules.find("crates").orElseThrow();
        assertSame(crates, descriptor.plugin());
        assertEquals("PlexonCrates", descriptor.displayName());
        assertEquals(ModuleRegistry.ModuleState.READY, descriptor.state());
        assertTrue(descriptor.capabilities().contains("crate-open-event"));
        assertTrue(descriptor.capabilities().contains("phoenix-migration"));

        var integration = integrations.get("PLEXON_CRATES").orElseThrow();
        assertEquals(IntegrationRegistry.IntegrationState.READY, integration.state());
        assertEquals(crates.getPluginMeta().getVersion(), integration.version());
    }

    @Test
    void incompatibleCoreSafelyKeepsCratesStandalone() {
        var corePlugin = MockBukkit.createMockPlugin("PlexonCore");
        CoreVersion version = CoreVersion.of(2, 0, "2.0.0");
        ModuleRegistry modules = new ModuleRegistry(version);
        IntegrationRegistry integrations = new IntegrationRegistry(server.getPluginManager());
        PlexonCoreAPI api = mock(PlexonCoreAPI.class);
        when(api.version()).thenReturn(version);
        when(api.modules()).thenReturn(modules);
        when(api.integrations()).thenReturn(integrations);
        server.getServicesManager().register(PlexonCoreAPI.class, api, corePlugin, ServicePriority.Normal);

        PlexonCrates crates = MockBukkit.load(PlexonCrates.class);

        assertEquals("STANDALONE", crates.coreBridge().mode());
        assertEquals(false, crates.coreBridge().compatible());
    }
}
