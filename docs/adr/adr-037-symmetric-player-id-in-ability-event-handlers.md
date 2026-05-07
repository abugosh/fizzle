# ADR 037: Symmetric player-id parameterization in ability event handlers

Date: 2026-05-07

## Status

Accepted

## Context

The two ability-activation event handlers in `events/abilities.cljs` have asymmetric interfaces for player-id:

- `::activate-mana-ability` (line 28) accepts an optional `player-id` argument. When nil, it defaults to `queries/get-human-player-id`. This was introduced as part of ADR-032 (unify mana ability activation paths).

- `::activate-ability` (line 300) does not accept a player-id argument. It always derives it from `queries/get-human-player-id` (line 304).

Both handlers call engine-layer functions that are fully parameterized — `activate-ability` and `activate-mana-ability` both accept explicit player-id arguments. The controller check at line 290 verifies that the passed player-id matches the permanent's controller, which works correctly with any player-id (not just the human's). The gap exists only at the event handler boundary.

This asymmetry prevents non-mana abilities from being activated on behalf of a non-human player through the standard event dispatch path. The immediate driver is practice scenarios where the human simulates a competent opponent triggering hate pieces (e.g., Tormod's Crypt). Future cases include test harnesses and potential Mindslaver-style effects.

## Decision

We will align `::activate-ability` with `::activate-mana-ability` by accepting an optional `player-id` argument. When omitted or nil, it defaults to `queries/get-human-player-id` (backward compatible). When provided, the handler uses the explicit player-id.

The pure `activate-ability` function (line 279) already accepts player-id — no engine changes needed. The view dispatch sites add player-id as an additional argument when activating opponent permanents.

## Consequences

- Both ability event handlers have the same parameterization pattern: optional player-id, defaulting to human. Callers that omit it (existing UI code) continue working unchanged.
- Opponent ability activation becomes possible through the standard event dispatch path without bypassing the event layer or creating parallel handlers.
- The controller check at line 290 continues to enforce that player-id matches the permanent's controller — passing the opponent's player-id for an opponent-controlled permanent passes the check naturally.
- Selection state carries `:selection/player-id` through multi-step chains (targeting, sacrifice costs), which already works correctly regardless of which player-id is passed.
- This is a minimal interface change (adding one optional arg) that enables a feature without new mechanisms or architectural patterns.
