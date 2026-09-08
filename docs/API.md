# PlexonCrates Public API

PlexonCrates 3.0 exposes its public runtime contract through Bukkit ServicesManager and Bukkit events. PlexonCore integration does not replace these APIs.

## Service API

Service type:

```java
com.antondev.crates.api.PlexonCratesApi
```

Resolve it through Bukkit ServicesManager:

```java
RegisteredServiceProvider<PlexonCratesApi> registration =
        Bukkit.getServicesManager().getRegistration(PlexonCratesApi.class);
PlexonCratesApi crates = registration == null ? null : registration.getProvider();
```

The service is registered in both CORE and STANDALONE modes.

### Current methods

```java
Optional<Crate> crate(String id);
Collection<Crate> crates();
Optional<KeyDefinition> key(String id);
Collection<KeyDefinition> keys();
long runtimeRevision();
long crateRevision(String crateId);
boolean requestOpening(Player player, String crateId, int amount, OpenSource source);
```

`requestOpening` routes through the normal PlexonCrates opening coordinator. Consumers should not bypass key validation, limits, journals or reward delivery by manipulating internal services directly.

## Opening events

Public event package:

```text
com.antondev.crates.api.event
```

The existing 3.0 event surface includes:

```text
CratePreOpenEvent
CrateOpenEvent
CrateRewardSelectEvent
CrateKeyConsumeEvent
CrateDefinitionChangeEvent
CrateDraftPublishEvent
CrateLinkEvent
CrateUnlinkEvent
CrateMilestoneEarnEvent
PortableCrateUseEvent
```

These events remain PlexonCrates-owned. No Core-specific replacement event package is introduced in 3.0.0.

## CrateOpenEvent

`CrateOpenEvent` is the post-success opening event used by PlexonQuests 3.1.0.

```java
@EventHandler
public void onCrateOpen(CrateOpenEvent event) {
    Player player = event.player();
    OpeningPlan plan = event.plan();
}
```

Required `OpeningPlan` metadata:

```java
UUID transactionId();
String crateId();
String keyId();
int openingCount();
List<String> rewardIds();
OpenSource source();
```

One logical opening transaction emits one post-success `CrateOpenEvent`. For bulk openings, `openingCount()` contains the transaction count. Consumers that persist progress should use `transactionId()` as the deduplication/source token.

`CrateOpenEvent` is not cancellable. Use `CratePreOpenEvent` for cancellable pre-open policy.

## Compatibility

PlexonCore is optional. The service API and event classes exist and are registered when PlexonCrates runs standalone.

PlexonKeys remains a separate provider. PlexonCrates 3.0 prefers PlexonKeys 1.2's stable Bukkit service API for live physical-key templates and retains an isolated compatibility adapter for older runtime surfaces where necessary.

## Threading

Treat Bukkit-facing API calls and event handling as primary-thread operations unless a specific API explicitly documents otherwise. Do not perform direct SQLite access or long-running file operations from an opening event handler.
