# Data Foundation

## Responsibility

Datascript schema definitions and entity-level query/transaction helpers. This component defines the game state schema — the attributes for zones, card entities, player state, mana pool, stack items, grants, and restrictions. It also provides query helpers that other components use to read entity state without writing raw Datalog everywhere. All game state is stored in a single Datascript db value; this component defines the shape of that value.

## Interface Contract

### IN

- Datascript transaction maps from event handlers (Game Events, Selection Events, Ability Events)
- Datalog query calls from the Engine and Subscriptions layer

### OUT

- Updated Datascript db values after transactions
- Entity attribute values and entity maps returned from query helpers
- Schema definition consumed at database initialization (`core.cljs`)

## Local Changes

Adding a new Datascript attribute for a new piece of game state is local to this component. Changes become non-local when a new attribute drives a new subscription (owned by Subscriptions) or requires new query helpers consumed by the Engine.
