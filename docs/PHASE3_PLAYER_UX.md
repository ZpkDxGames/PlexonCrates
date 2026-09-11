# PlexonCrates Phase 3 — Player UX

## Dependency boundary

This UX layer is stacked on the accepted reliability branch `phase3/runtime-reliability` at exact commit `c1b84c76430fd7196acc6ea4d45976e1b0740786`.

It does not redesign schema 4, the opening journal, payment durability, grant durability, recovery classification, pending reward persistence, PlexonKeys identity, or exact ItemStack reward authority.

The ordinary `/crates` command surface is routed into the Phase 3 player UI. Direct `MenuService` compatibility calls, physical crate blocks, portable flows and admin tooling retain their existing behavior.

## Player journey

```text
/crates
  -> Crate Hall
     -> Preview
        -> Random: Open 1
        -> Random: Open More -> Quantity -> Confirmation
        -> Selective: Choose reward -> Confirmation
     -> Pending Rewards
```

The Hall and Preview are non-consuming. Payment is only submitted through an explicit Open or Confirm action.

## Shared layout

Hall and Preview use 54 slots with up to 28 content cards on slots `10-16`, `19-25`, `28-34`, `37-43`.

Bottom semantics where relevant:

- 45 Previous
- 46 Context / Refresh
- 47 Payment
- 48 Back
- 49 Primary action
- 50 Secondary action
- 51 Page / Status
- 52 Close
- 53 Next

Irrelevant controls are omitted.

## Crate Hall

Cards show display name/icon, brief configured description, human payment requirement, opening mode, Open More availability where supported, and useful availability. The card action is always Preview. Internal crate IDs remain hidden action state only.

## Preview and rewards

Preview shows the crate identity, payment requirement, opening mode, availability, reward pool, pity/milestone context where supported, and explicit actions.

Reward cards preserve the configured exact display ItemStack for presentation. Player lore summarizes rarity, item amount, money, experience and server-delivered extras without printing command strings, serialized data, reward IDs, transaction IDs, schema values or journal enums.

For random crates, chance text uses the effective current eligible pool probability and keeps configured base chance distinct when it differs. For selective crates, cards say `Selective choice`; no random percentage is fabricated. Pity membership is described as guaranteed-pool context without inventing a pity-adjusted percentage.

## Payment presentation

`PaymentPresentation` is a projection of `KeyPaymentPlanner`; it does not decide payment authority itself.

Physical-only and virtual-only policies show the corresponding source. `PLAYER_CHOICE` exposes a selector only when both physical and virtual alternatives can actually satisfy the current payment. The virtual balance is read once per preview/confirmation snapshot where required, never once per reward card. No consume API is used during rendering.

## Random Open 1

Open 1 is explicit. The UI validates the current crate revision, uses a one-submit holder gate, then calls the existing `OpeningService.open(...)`. The opening journal/payment/grant layer remains the correctness authority.

## Open More

Open More first calls existing `OpeningService.maximumAvailableAmount(...)`. The quantity screen offers only supported quantities from `1`, `5`, `10`, and the current safe maximum when applicable. A second confirmation shows exact quantity and total key requirement before calling the same opening authority with the selected amount.

## Selective opening

Selective Preview is non-consuming. A reward card opens a confirmation screen. Confirmation rechecks current eligibility and crate revision, then calls existing `OpeningService.openSelected(...)`. Back, Close and reward browsing do not grant or consume anything.

## Pending Rewards

The player recovery surface is named **Pending Rewards**. It uses existing `ClaimService.list(...)` and `ClaimService.claim(...)`. Claim IDs remain hidden routing state. Exact pending ItemStacks are decoded only for safe presentation. Full inventory behavior, durable reservation/release and no-world-drop recovery remain owned by `ClaimService`.

## Session and stale-state behavior

The player UX reuses the existing `GuiSessionService`. `PlayerMenuContext` stores immutable navigation state such as Hall page, reward page, selected quantity and payment preference. Async payment/claim reads return to the main thread only after validating that the exact GUI session and crate/runtime revision are still current.

A stale player action is rejected and refreshed with player language: `This crate changed while the menu was open. The latest information has been loaded.`

## Performance boundaries

The player layer adds no repeating GUI task, inventory tick refresh, database query per reward card, simulation during player rendering, payment consume check during render, or unbounded GUI cache. Hall/reward lists are bounded to the current page. Virtual payment reads are snapshot-scoped.

## Runtime validation matrix

1. `/crates` opens Crate Hall.
2. Hall card click opens Preview and consumes nothing.
3. Reward pagination preserves the same crate and exact Back behavior.
4. Physical/local-key Open 1 succeeds exactly once.
5. Captured PlexonKeys item Open 1 succeeds exactly once.
6. Virtual PlexonKeys wallet Open 1 succeeds exactly once.
7. Portable key path remains correct.
8. Insufficient payment is shown before explicit submission where safely known.
9. Payment-source choice appears only when policy and current payment allow real choice.
10. Open More offers supported quantities only.
11. Mass confirmation opens exactly the selected quantity.
12. Selective browse and Close consume nothing.
13. Selective confirmation delivers the exact selected eligible reward once.
14. Successful opening/result summary identifies crate and reward safely.
15. Pending Rewards lists durable pending delivery entries.
16. Full inventory preserves a pending reward for later claim and performs no world-drop recovery.
17. Rapid duplicate Open/Confirm/Claim clicks do not multiply one accepted GUI submission.
18. Stale crate revision/session rejects the action and refreshes the player view.
19. Reconnect retains authoritative persisted state.
20. Normal restart retains authoritative persisted state.
21. Existing legacy MANUAL_REVIEW rows remain unchanged.
22. `/pcrates diagnose` remains accurate for administrators.
23. Observe PlexonKeys durable-consume latency without changing its authority.
24. Profile repeated Hall/Preview/pagination navigation with Spark/MSPT.
25. Include PlexonCrates in the later integrated >=30-minute soak.

## Release gate

This branch is source/CI UX work only. Do not merge independently, publish another RC, modify `v5.0.0-rc.2`, or promote stable until the coordinated runtime gate is complete.
