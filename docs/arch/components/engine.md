# Engine

## Responsibility

Core game rules engine that interprets card effect data structures and enforces MTG rules. The engine owns mana validation and payment, zone management (moving cards between hand/library/graveyard/stack/battlefield/exile), stack operations, effect reduction (including pausing at interactive effects), the priority system (yield/yield-all/hold-priority), trigger evaluation (fire-triggers, trigger_db), and resolution dispatch (multimethod on stack-item type). It contains no card-specific logic — cards are pure data and the engine interprets them. The engine is a pure function library; it reads from Datascript but never dispatches re-frame events.

## Interface Contract

### IN

- Datascript db (passed directly as value, not subscribed)
- Card effect data maps (from Card Pool via registry): `{:effect/type :add-mana :effect/mana {:black 3}}` and similar
- Function calls from Game Events, Selection Events, and Ability Events orchestration layers

### OUT

- Updated Datascript db values (returned, not committed — callers commit via re-frame fx)
- `{:db db}` or `{:db db :needs-selection effect}` tagged maps from interactive effect resolution
- Validated boolean results (can-cast?, valid-target?, etc.) for pre-cast and targeting checks

## Local Changes

Adding a new effect type interpreter (a new defmethod on `resolve-effect` or `build-selection-for-effect`) is local to the engine. Changes become non-local when a new effect type requires a new Datascript schema attribute (owned by Data Foundation) or a new selection UI pattern (owned by Selection Events).
