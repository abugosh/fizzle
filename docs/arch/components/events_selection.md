# Selection Events

## Responsibility

re-frame event handlers that manage all player choice interactions. When a game effect requires a player decision (which card to discard, how to allocate mana, which target to choose, how to order cards for scry), this component builds the selection state, presents it to the view layer, processes toggle interactions, and executes confirmed choices. The system uses three multimethods — `build-selection-for-effect`, `execute-confirmed-selection`, and `build-chain-selection` — to dispatch across 21 distinct selection types organized into four patterns: zone-pick, accumulator, reorder, and custom.

## Interface Contract

### IN

- re-frame events: `::build-selection-for-effect`, `::toggle-selection`, `::confirm-selection`, `::cancel-selection`
- Selection context from Game Events: spell id, remaining effects list, player id
- Datascript db containing zone contents used to build candidate sets

### OUT

- Updated Datascript db with selection state written for the view layer to render
- Continuation dispatches after confirmation: may chain to the next effect in a remaining-effects list, resolve a pending stack item, or return priority to the active player
- `{:db db :needs-selection effect}` tagged returns from Engine used as input to build the next selection

## Local Changes

Adding a new selection type requires registering three defmethods (builder, executor, optionally chain-builder) and is local to this component. Changes become non-local when a new type requires a new Datascript attribute for selection state storage, a new Engine validation rule, or a new view modal.
