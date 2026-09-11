# PlexonCrates Phase 3 — Player UX

## Accepted boundary

The player UX is stacked on the accepted reliability lineage at exact commit:

`c1b84c76430fd7196acc6ea4d45976e1b0740786`

The accepted Phase 3 UX ancestor is:

`f1bcb27186489b47c8692d126e0b1974e25881fa`

The final RC4 source boundary integrated into stable 5.0 is:

`1d4118673f250af72cecbec3ab91eb81cfa8830d`

Stable `5.0.0` preserves schema 4, the opening journal, payment/grant durability, recovery classification, Claim Inbox custody, PlexonKeys identity, exact ItemStack authority, statistics, limits, pity, replay protection and migration behavior. The stable source audit adds only the fail-closed virtual-key claim finalization correction documented in `releases/5.0.0.md`.

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

The Hall and Preview are non-consuming. Payment is submitted only through an explicit Open or Confirm action.

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

For random crates, chance text uses the effective current eligible-pool probability and keeps configured base chance distinct when it differs. For selective crates, cards say `Selective choice`; no random percentage is fabricated. Pity membership is described as guaranteed-pool context without inventing a pity-adjusted percentage.

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

The player recovery surface is named **Pending Rewards**. It uses existing `ClaimService.list(...)` and `ClaimService.claim(...)`. Claim IDs remain hidden routing state. Exact pending ItemStacks are decoded only for safe presentation.

Claim reservation/release and side-effect uncertainty remain owned by `ClaimService`. Stable 5.0 immediately moves a credited virtual-key claim to `REVIEW` if durable claim completion becomes uncertain, matching the existing fail-closed exact-item path. No uncertain claim is automatically retried.

## Session and stale-state behavior

The player UX reuses the existing `GuiSessionService`. `PlayerMenuContext` stores immutable navigation state such as Hall page, reward page, selected quantity and payment preference. Async payment/claim reads return to the main thread only after validating that the exact GUI session and crate/runtime revision are still current.

A stale player action is rejected and refreshed with player language: `This crate changed while the menu was open. The latest information has been loaded.`

## Performance boundaries

The player layer adds no repeating GUI task, inventory tick refresh, database query per reward card, simulation during player rendering, payment consume check during render or unbounded GUI cache. Hall/reward lists are bounded to the current page. Virtual payment reads are snapshot-scoped.

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
17. Virtual-key credit plus uncertain claim completion moves the claim to manual review without automatic retry.
18. Rapid duplicate Open/Confirm/Claim clicks do not multiply one accepted GUI submission.
19. Stale crate revision/session rejects the action and refreshes the player view.
20. Reconnect retains authoritative persisted state.
21. Normal restart retains authoritative persisted state.
22. Existing legacy `MANUAL_REVIEW` rows remain unchanged.
23. `/pcrates diagnose` remains accurate for administrators.
24. Observe PlexonKeys durable-consume latency without changing its authority.
25. Profile repeated Hall/Preview/pagination navigation with Spark/MSPT.
26. Include PlexonCrates in an integrated soak when performing deployment certification.

## Stable/runtime boundary

Phase 3 source and UX work is integrated into stable `5.0.0`. GitHub stable publication is gated by exact-source CI, lineage, package and release-byte verification. Live PlexonCraft runtime/soak certification remains a post-release deployment operation and may be recorded as `NOT_EXECUTED` in release provenance.
