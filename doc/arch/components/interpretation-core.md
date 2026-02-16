# Interpretation Core

## Volatility Axis
New effect types and interaction patterns. Adding a new effect defmethod is variability (handled by the existing multimethod dispatch). Adding a new interaction pattern (e.g., a fundamentally new selection type) is true volatility requiring structural change.

## Layer: Engine
Pure-function game rules. Zero re-frame dependencies (ADR-001). All functions take `db` and return `db` (or tagged `{:db :needs-selection}`). Testable in isolation without re-frame infrastructure.

## Interface Contract
- IN: Card EDN data (from Card Pool), Datascript db values (from Data Foundation)
- OUT: Pure functions called by Game Orchestration (cast-spell, resolve-stack-item, execute-effect), derived state queries called by Presentation (can-cast?, sort-cards, land-card?)

## Responsibility
Interpret card EDN into state transitions. Stack resolution via single `resolve-stack-item` multimethod dispatching on `:stack-item/type`. Effect execution via `execute-effect-impl` multimethod. Mana computation, zone management, targeting, grants, conditions, costs, state-based actions, turn-based logic, and display sorting.

## What Changes Should Be Local
- Adding a new effect type (defmethod on execute-effect-impl)
- Adding a new stack-item resolution type
- Modifying game rules (casting, mana, combat)
- Adding selection builder logic (pure portions)

## Key Invariant
Zero re-frame dependencies. This is an architectural invariant (ADR-001), not a preference. Engine purity enables testability, reuse by future bots/tactics, and reasoning about game rules as pure state transformations.

## Modules
- `src/fizzle/engine/*.cljs` (20 files: rules, stack, resolution, effects, mana, zones, targeting, grants, costs, conditions, validation, triggers, trigger-db, trigger-dispatch, turn-based, state-based, sorting, card-spec, events, deck-parser)
- `src/fizzle/events/selection/*.cljs` (pure logic portions: builders, validation)
