# Game Events

## Responsibility

re-frame event handlers that orchestrate the top-level game flow. This component owns the primary game action pipeline: spell casting (pre-cast validation, stack placement), stack resolution, phase and turn transitions, and priority passing between players. The module is decomposed into focused sub-modules (cast, resolve, stack, phase, turn) but presents a single orchestration surface to the rest of the system. Event handlers coordinate between the Engine (for rules) and the Data Foundation (for state reads/writes) but contain no game rules themselves.

## Interface Contract

### IN

- re-frame events dispatched by Views / UI and Opponent AI: `::cast-spell`, `::resolve-top`, `::advance-phase`, `::start-turn`, `::pass-priority`, and related game action keywords
- Datascript db (via re-frame cofx) containing current game state

### OUT

- Updated Datascript db (via re-frame fx) reflecting new game state after the action
- Downstream event dispatches: may chain to selection events when an action requires player input, or to resolve events when the stack is non-empty
- History interceptor observations (transparent, not an explicit output)

## Local Changes

Adding a new top-level game action (a new event keyword with its orchestration handler) is local to this component as long as it delegates rule evaluation to the Engine and state persistence to the Data Foundation. Changes become non-local when a new action requires a new engine function, a new Datascript attribute, or a new UI element.
