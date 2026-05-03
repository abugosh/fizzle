# ADR 034: Game Controls Selection Awareness

## Status

Accepted

## Context

`views/controls.cljs` (mapped to `game_controls` in the architecture model) currently renders only game-action buttons (Cast, Yield, Cycle) and has no dependency on the selection subsystem. Epic fizzle-qnk6 introduces inline selection rendering: when a selection with an inline-eligible mechanism is pending, controls.cljs renders the inline widget in place of the priority buttons.

This expands game_controls' responsibility from "game-action dispatch" to "current player interaction surface" — which includes both game actions and selection interactions. The architecture model describes game_controls as "Phase bar, control buttons, and game-over screen" with no edge to events_selection.

The same pattern already exists in `mana_pool.cljs`, which subscribes to selection state (::pending-selection derivatives) and dispatches selection events for :allocate-resource and :unless-pay. That module has been stable (3 commits in 6 months) despite this dual responsibility.

## Decision

We accept that game_controls gains selection awareness. The component's responsibility is "what the player interacts with right now" — game-action buttons when no inline selection is pending, inline selection widget when one is active. These are mutually exclusive (switch pattern, not a braid).

The architecture model will be updated to reflect the new relationship: game_controls receives inline selection components as props from core.cljs.

## Consequences

- game_controls accepts an optional inline-component parameter from core.cljs. When non-nil, it renders the inline component in place of priority buttons.
- controls.cljs itself has no selection-related imports — the inline component is produced by selection_ui (via render-selection in modals.cljs) and routed through core.cljs as a prop.
- The architecture model's game_controls description and edges must be updated to reflect prop-based data flow.
- The mana_pool.cljs precedent (3 commits/6mo with similar dual responsibility) suggests this will stabilize.
