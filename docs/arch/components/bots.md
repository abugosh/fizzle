# Opponent AI

## Responsibility

Bot system that simulates opponent behavior for practice scenarios. The system is built around an `IBot` multimethod protocol dispatching on bot archetype (goldfish, burn). Bot definitions declare deck composition, starting hand, and behavior rules. A global re-frame interceptor (`bots/interceptor.cljs`) fires after each game-state-changing event and drives the bot's turn by evaluating `should-act?` and `choose-action`, then dispatching the same re-frame events a human player would dispatch. Bots use no parallel engine paths — they interact with the game entirely through the public event API.

## Interface Contract

### IN

- re-frame event stream (observed by the bot interceptor transparently after each event)
- Datascript db (queried by bot decision logic to evaluate game state)
- Bot configuration: archetype keyword, deck list, behavior rules

### OUT

- re-frame event dispatches: the same `::cast-spell`, `::activate-ability`, `::advance-phase`, `::pass-priority` events a human player would dispatch
- No direct db writes — all state changes go through the event system

## Local Changes

Adding a new bot archetype (a new `defmethod` on `IBot`) or a new behavior rule is local to this component. Changes become non-local when a bot needs to interact with a game action not yet covered by the existing event API, or when bot configuration UX requires new view components.
