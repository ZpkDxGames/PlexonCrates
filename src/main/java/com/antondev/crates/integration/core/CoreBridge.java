package com.antondev.crates.integration.core;

/**
 * Optional PlexonCore boundary. This interface intentionally has no PlexonCore
 * types so PlexonCrates can load safely when Core is absent or incompatible.
 */
public interface CoreBridge {
    String SUPPORTED_API_RANGE = ">=1.0 <2.0";
    String MODULE_ID = "crates";

    boolean installed();

    boolean available();

    boolean compatible();

    String pluginVersion();

    String apiVersion();

    String mode();

    String registrationState();

    String detail();

    void registerStarting();

    void markReady(String detail);

    void markDegraded(String detail);

    void markFailed(String detail);

    void unregister();

    ProviderHint providerHint(String integrationId);

    enum ProviderHint {
        PRESENT,
        MISSING,
        UNKNOWN
    }
}
