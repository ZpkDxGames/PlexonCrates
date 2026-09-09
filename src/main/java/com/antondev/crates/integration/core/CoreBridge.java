package com.antondev.crates.integration.core;

/**
 * Optional PlexonCore boundary. This interface intentionally has no PlexonCore
 * types so PlexonCrates can load safely when Core is absent or incompatible.
 */
public interface CoreBridge {
    /** Core 1 compatibility plus the released Core 2 additive runtime API. */
    String SUPPORTED_API_RANGE = ">=1.0 <3.0";
    String MODULE_ID = "crates";

    boolean installed();

    boolean available();

    boolean compatible();

    String pluginVersion();

    String apiVersion();

    /** CORE_RUNTIME, CORE_LEGACY, or STANDALONE. */
    String mode();

    String registrationState();

    String detail();

    void registerStarting();

    void markReady(String detail);

    void markDegraded(String detail);

    void markFailed(String detail);

    void unregister();

    ProviderHint providerHint(String integrationId);

    /**
     * Core 2.0.0 does not expose a PlayerInteractEvent gateway with mutable
     * cancellation/priority parity, so 4.6 keeps interaction ownership local.
     */
    default String interactionOwnership() {
        return "LOCAL";
    }

    /** Safety-critical linked-block protection remains local in 4.6. */
    default String protectionOwnership() {
        return "LOCAL";
    }

    default boolean sharedInteractionAvailable() {
        return false;
    }

    enum ProviderHint {
        PRESENT,
        MISSING,
        UNKNOWN
    }
}
