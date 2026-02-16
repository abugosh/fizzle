# Game Orchestration

## Volatility Axis
Flow coordination and event sequencing. Changes when the game flow needs new event patterns, new selection types, or new phase transitions.

## Layer: Manager
Re-frame event coordination. No business logic — delegates all game rule computation to Interpretation Core. Translates between re-frame's event/effect model and pure function calls.

## Interface Contract
- IN: Re-frame events dispatched by Presentation (cast-spell, play-land, resolve, phase transitions)
- OUT: Re-frame effects (db updates, dispatches) that update game state; delegates to Interpretation Core for all rule computation

## Responsibility
Coordinate game state changes through re-frame events. Three event namespaces handle: core game actions (cast, resolve, phases), selection system (player choices for targeting, tutoring, discarding), and activated abilities. The selection system uses the builder -> toggle -> confirm -> execute multimethod pattern.

## What Changes Should Be Local
- Adding a new game event handler
- Adding a new selection type (re-frame portions)
- Changing event sequencing or flow coordination
- Modifying the selection confirm/execute pipeline

## Modules
- `src/fizzle/events/game.cljs` (~670 lines, 26 imports — fan-out champion)
- `src/fizzle/events/selection.cljs` (~1437 lines, bare-requires submodules)
- `src/fizzle/events/selection/*.cljs` (re-frame handler portions: core, targeting, costs, library, zone-ops, storm)
- `src/fizzle/events/abilities.cljs` (~273 lines)
- `src/fizzle/events/setup.cljs`, `events/opening-hand.cljs`
- `src/fizzle/core.cljs` (app bootstrap, wiring)
