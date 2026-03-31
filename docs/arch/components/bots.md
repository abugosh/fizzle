# Opponent AI

## Responsibility

Bot system that simulates opponent behavior for practice scenarios. The system is built around an `IBot` multimethod protocol dispatching on bot archetype (goldfish, burn). Bot definitions declare deck composition, starting hand, and behavior rules. `bots/interceptor.cljs` registers `::bot-decide` and `::bot-action-complete` re-frame event handlers that fire via the `db_effect` chokepoint for compound actions (tap+cast sequences). It also exposes `bot-decide-action` as a pure function called inline by `events/director.cljs` during `run-to-decision`. Bots dispatch the same re-frame events a human player would dispatch — no parallel engine paths.

## Interface Contract

### IN

- Calls to `bot-decide-action` from `events/director` during `run-to-decision` (inline, synchronous)
- `::bot-decide` re-frame events queued by `db_effect` after game-db mutations where a bot holds priority
- Datascript db (queried by bot decision logic to evaluate game state)
- Bot configuration: archetype keyword, deck list, behavior rules

### OUT

- `bot-decide-action` return: `{:action :cast-spell :object-id :target :tap-sequence}` or `{:action :pass}`
- re-frame event dispatches for compound actions: `::activate-mana-ability`, `::cast-spell`, `::bot-action-complete` sentinel
- No direct db writes — all state changes go through the event system

## Local Changes

Adding a new bot archetype (a new `defmethod` on `IBot`) or a new behavior rule is local to this component. Changes become non-local when a bot needs to interact with a game action not yet covered by the existing event API, or when bot configuration UX requires new view components.
