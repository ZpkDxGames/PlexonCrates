package com.antondev.crates.integration.core;

import com.zpkdxgames.plexoncore.api.PlexonCoreAPI;
import com.zpkdxgames.plexoncore.api.PlexonCoreAPI.CoreVersion;
import com.zpkdxgames.plexoncore.integration.IntegrationRegistry.IntegrationState;
import com.zpkdxgames.plexoncore.module.ModuleRegistry;
import com.zpkdxgames.plexoncore.module.ModuleRegistry.ModuleDescriptor;
import com.zpkdxgames.plexoncore.module.ModuleRegistry.ModuleState;
import com.zpkdxgames.plexoncore.module.ModuleRegistry.ModuleVersionRange;
import java.time.Instant;
import java.util.Set;
import org.bukkit.Bukkit;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

/** PlexonCore-backed bridge. Loaded only when Core is present and enabled. */
public final class PlexonCoreBridge implements CoreBridge {
    private static final Set<String> CAPABILITIES = Set.of(
            "crate-engine",
            "crate-api",
            "crate-open-event",
            "physical-keys",
            "plexonkeys-integration",
            "exact-item-rewards",
            "percentage-chances",
            "durable-drafts",
            "sqlite-definitions",
            "world-crates",
            "portable-crates",
            "claim-inbox",
            "milestones",
            "rerolls",
            "virtual-keys",
            "opening-journal",
            "phoenix-migration",
            "core2-runtime-compatible",
            "optimized-local-interactions");

    private final JavaPlugin plugin;
    private final PlexonCoreAPI core;
    private final CoreVersion version;
    private final boolean compatible;
    private boolean ownsRegistration;
    private String registrationState = "NOT_REGISTERED";
    private String detail = "PlexonCore API resolved";

    public PlexonCoreBridge(JavaPlugin plugin) {
        this.plugin = plugin;
        RegisteredServiceProvider<PlexonCoreAPI> registration =
                Bukkit.getServicesManager().getRegistration(PlexonCoreAPI.class);
        if (registration == null) {
            throw new IllegalStateException("PlexonCore API service is not registered");
        }
        this.core = registration.getProvider();
        this.version = core.version();
        this.compatible = ModuleVersionRange.parse(SUPPORTED_API_RANGE).contains(version);
        if (!compatible) {
            detail = "Core API " + version.apiVersion() + " is outside supported range " + SUPPORTED_API_RANGE;
        } else if (version.apiMajor() >= 2) {
            detail = "Core 2 runtime detected; PlayerInteract ownership remains LOCAL because Core 2.0 has no mutable interaction gateway";
        }
    }

    @Override
    public boolean installed() {
        return true;
    }

    @Override
    public boolean available() {
        return compatible;
    }

    @Override
    public boolean compatible() {
        return compatible;
    }

    @Override
    public String pluginVersion() {
        return version.pluginVersion();
    }

    @Override
    public String apiVersion() {
        return version.apiVersion();
    }

    @Override
    public String mode() {
        if (!compatible || !ownsRegistration) return "STANDALONE";
        return version.apiMajor() >= 2 ? "CORE_RUNTIME" : "CORE_LEGACY";
    }

    @Override
    public String registrationState() {
        if (ownsRegistration) {
            return core.modules().find(MODULE_ID).map(descriptor -> descriptor.state().name()).orElse("NOT_REGISTERED");
        }
        return registrationState;
    }

    @Override
    public String detail() {
        if (ownsRegistration) {
            return core.modules().find(MODULE_ID).map(ModuleDescriptor::detail).orElse(detail);
        }
        return detail;
    }

    @Override
    public void registerStarting() {
        ModuleDescriptor descriptor = new ModuleDescriptor(
                MODULE_ID,
                "PlexonCrates",
                plugin.getName(),
                plugin.getPluginMeta().getVersion(),
                plugin,
                ModuleVersionRange.parse(SUPPORTED_API_RANGE),
                CAPABILITIES,
                ModuleState.STARTING,
                "Initializing PlexonCrates",
                Instant.now());

        ModuleRegistry.RegistrationResult result = core.modules().register(descriptor);
        ModuleDescriptor registered = result.descriptor();
        ownsRegistration = registered != null && registered.plugin() == plugin;
        registrationState = registered == null ? "NOT_REGISTERED" : registered.state().name();
        detail = result.message();

        if (!result.success() && !ownsRegistration) {
            plugin.getLogger().warning("PlexonCore module registration rejected: " + result.message());
        } else if (!compatible) {
            plugin.getLogger().warning("PlexonCore API " + version.apiVersion()
                    + " is incompatible with supported range " + SUPPORTED_API_RANGE
                    + "; crate gameplay will continue in standalone compatibility mode.");
        } else if (version.apiMajor() >= 2) {
            plugin.getLogger().info("PlexonCore 2 runtime detected. PlexonCrates 4.6 keeps PlayerInteractEvent and block protection local because Core 2.0 does not provide equivalent mutable interaction/cancellation semantics.");
        }
    }

    @Override
    public void markReady(String detail) {
        update(ModuleState.READY, IntegrationState.READY, detail);
    }

    @Override
    public void markDegraded(String detail) {
        update(ModuleState.DEGRADED, IntegrationState.DEGRADED, detail);
    }

    @Override
    public void markFailed(String detail) {
        update(ModuleState.FAILED, IntegrationState.FAILED, detail);
    }

    private void update(ModuleState moduleState, IntegrationState integrationState, String newDetail) {
        if (!compatible || !ownsRegistration) return;
        String resolvedDetail = newDetail == null ? "" : newDetail;
        if (version.apiMajor() >= 2) {
            if (!core.modules().updateState(MODULE_ID, plugin, moduleState, resolvedDetail)) {
                ownsRegistration = false;
                registrationState = "NOT_REGISTERED";
                detail = "Core module ownership changed before state update";
                return;
            }
        } else {
            core.modules().updateState(MODULE_ID, moduleState, resolvedDetail);
        }
        core.integrations().publish(
                "PLEXON_CRATES",
                plugin.getName(),
                plugin.getPluginMeta().getVersion(),
                integrationState,
                CAPABILITIES,
                resolvedDetail);
        registrationState = moduleState.name();
        detail = resolvedDetail;
    }

    @Override
    public void unregister() {
        if (!ownsRegistration) return;
        if (version.apiMajor() >= 2) {
            core.modules().unregisterOwnedBy(plugin);
        } else {
            core.modules().find(MODULE_ID)
                    .filter(descriptor -> descriptor.plugin() == plugin)
                    .ifPresent(descriptor -> core.modules().unregister(MODULE_ID));
        }
        ownsRegistration = false;
        registrationState = "UNREGISTERED";
    }

    @Override
    public ProviderHint providerHint(String integrationId) {
        if (!compatible) return ProviderHint.UNKNOWN;
        return core.integrations().get(integrationId).map(view -> switch (view.state()) {
            case READY -> ProviderHint.PRESENT;
            case MISSING -> ProviderHint.MISSING;
            case DEGRADED, INCOMPATIBLE, FAILED -> ProviderHint.UNKNOWN;
        }).orElse(ProviderHint.UNKNOWN);
    }
}
