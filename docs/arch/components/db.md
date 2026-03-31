# Data Foundation

## Responsibility

Datascript schema definitions, entity-level query/transaction helpers, and game entity factory functions. This component defines the game state schema — the attributes for zones, card entities, player state, mana pool, stack items, grants, and restrictions. It provides query helpers that other components use to read entity state without writing raw Datalog everywhere. It also provides `game_state.cljs` as the single source of truth for player identity constants (`human-player-id`, `opponent-player-id`) and the canonical factory functions for creating player and game entities. All game state is stored in a single Datascript db value; this component defines the shape of that value.

## Interface Contract

### IN

- Datascript transaction maps from event handlers (Game Events, Selection Events, Ability Events)
- Datalog query calls from the Engine and Subscriptions layer
- Factory function calls from events/init, events/director, bots/interceptor, sharing/restorer, and subs/game for player identity and entity creation

### OUT

- Updated Datascript db values after transactions
- Entity attribute values and entity maps returned from query helpers
- Schema definition consumed at database initialization (`core.cljs`)
- Player identity constants (`human-player-id :player-1`, `opponent-player-id :player-2`) consumed by all components that need to identify players
- Populated Datascript db from `create-complete-player` (player entity + turn-based triggers transacted)

## Local Changes

Adding a new Datascript attribute for a new piece of game state is local to this component. Changes become non-local when a new attribute drives a new subscription (owned by Subscriptions) or requires new query helpers consumed by the Engine. Changing player identity constants (`human-player-id`, `opponent-player-id`) is non-local by definition — all consumers must be updated together (ADR-016).
