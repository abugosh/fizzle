# ADR 018: Explicit History Entry Creation

## Status

Accepted

## Context

The history system uses a global re-frame interceptor that watches every event and infers when to create history entries. The interceptor maintains two static sets (`priority-events` with 10 event types, `priority-selection-types` with 4 selection types) and uses heuristics — checking whether `game-db` changed, whether a `pending-selection` was created, which event fired — to decide when a meaningful player action occurred.

This design complects **mechanism** (detect state changes, create entries) with **policy** (which actions are meaningful, what snapshot and description to record). The complection produces cascading bugs when a conceptual action spans multiple events:

- `cast-and-yield` for a targeted spell fires `::cast-and-yield` (creates selection), then `::confirm-selection` (confirms target, may chain to mana allocation), then another `::confirm-selection` (completes cast, continuation resolves spell). The interceptor sees each event in isolation and cannot determine when the full action is complete. This produces duplicate entries, entries with pre-cast snapshots, or missing entries depending on the selection chain length.

- The `selection-triggers-entry` set, `casting-spell-id` fallback chains, and `game-db-changed` checks are all symptoms of the interceptor trying to reconstruct the action's meaning from mechanical side effects.

This is the same pattern we encountered with the bot system: a global interceptor (`bots/interceptor.cljs`) tried to infer when to trigger bot behavior from event mechanics, producing repeated bugs until we refactored to explicit triggering via the `db_effect` chokepoint (see bot epics 1-3).

## Decision

We will replace the inference-based global interceptor with explicit history entry creation. Event handlers that complete meaningful actions will set `:history/pending-entry` on the app-db with the description, snapshot, turn, and principal already computed. A thin interceptor (or effect handler) will move the pending entry into the history data structure.

The 10 priority event types and 4 priority selection types collapse to ~10 call sites in event handlers. Since bots dispatch the same events as human players, each handler creates entries for both — no separate bot/human paths.

Description generation moves from the interceptor to the call sites (or a shared helper they invoke). The interceptor shrinks from ~70 lines of inference logic to ~10 lines of data movement.

## Consequences

**Positive:**
- Each action owns its history entry — the code that knows when the action is complete creates the entry with the correct snapshot and description.
- Multi-event actions (cast-and-yield with selections) produce exactly one entry at the right point, with no special-case logic.
- No more `priority-events`, `priority-selection-types`, `selection-triggers-entry`, or `casting-spell-id` inference.
- Follows the same pattern as the bot refactor: explicit declaration over implicit inference.

**Negative:**
- History entry creation is distributed across ~10 event handlers instead of centralized in one interceptor. Adding a new action type requires remembering to add history entry creation.
- Description generation logic must be available at each call site (mitigated by a shared helper module).

**Neutral:**
- The `descriptions.cljs` module remains useful as a helper but is called explicitly rather than from the interceptor.
- Test structure shifts from testing interceptor behavior to testing that each handler produces the correct history entry.
