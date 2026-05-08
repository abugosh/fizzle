# ADR 040: Accept :card-discarded trigger ordering during cycling

Date: 2026-05-08

## Status

Accepted

## Context

When cycling a card, the game state receives events in this order:

1. Cost payment begins (discard the card via `zone-change-dispatch/move-to-zone-db`)
2. `:card-discarded` trigger fires (during zone change at line `zone_change_dispatch.cljs:N`)
3. `:card-discarded` trigger resolves (lands on the stack)
4. Cycling ability creates its stack item (the draw effect)
5. Cycling draw effect resolves

MTG rules (CR 702.29) specify that cycling costs are paid during the activation process, and trigger resolution occurs after the ability itself has been put on the stack. This means `:card-discarded` should resolve *before* the cycling draw.

However, Fizzle's architecture dispatches `:card-discarded` from `zone-change-dispatch` (the single chokepoint for all zone transitions). During cycling, cost payment happens before the cycling stack item is created, so `:card-discarded` triggers stack below the draw ability. The trigger resolves after the draw instead of before.

## Decision

We will accept this ordering simplification for the Premodern card pool. The end state (card drawn, trigger effect resolved) is identical, and no Premodern cycling card makes the ordering observable (i.e., no card's effect depends on having resolution priority before versus after the cycling draw).

Specifically:

1. `:card-discarded` triggers during cycling will resolve after the cycling draw, not before.
2. No special-casing in `zone-change-dispatch` or cycling event handlers. The trigger ordering falls out naturally from the single-chokepoint dispatch model.
3. This is acceptable because the Premodern card pool lacks:
   - Cards with `:card-discarded` triggers on permanents that affect the cycling draw resolution
   - Cards that care about trigger ordering within a single activation sequence
   - Cycle-trigger interactions where ordering would create distinct game states

## Consequences

- `:card-discarded` triggers from cycling resolve cosmetically out-of-sequence compared to MTG rules, but with identical game effect for all known Premodern cards.
- The single-chokepoint pattern (ADR-026, ADR-027, ADR-031) remains intact. No dispatch duplicates or special cases.
- If a future Premodern cycling card makes the ordering observable (e.g., a cascade trigger that depends on cycle-trigger resolution order), we will either:
  - Introduce a second `:card-discarded` dispatch point specific to cycling, OR
  - Refactor zone-change-dispatch to accept ordering preferences (e.g., `:dispatch-after-stack-item`), OR
  - Lift cycling cost payment above zone-change-dispatch
- Until then, the simplification holds and eliminates unnecessary branching logic.
