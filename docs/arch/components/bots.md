# Opponent AI

## Responsibility

Bot system that simulates opponent behavior for practice scenarios. The system is built around an `IBot` multimethod protocol dispatching on bot archetype (goldfish, burn). Bot definitions declare deck composition, starting hand, and behavior rules. `bots/decisions.cljs` exposes pure decision functions (`bot-should-act?`, `bot-decide-action`, `find-tap-sequence`) called inline by `events/director.cljs` during `run-to-decision`. Bots dispatch the same re-frame events a human player would dispatch — no parallel engine paths. No re-frame event handlers are registered by the bot system; the director applies all bot actions through the standard event pipeline.

## Interface Contract

### IN

- Calls to `bot-should-act?`, `bot-decide-action`, `find-tap-sequence` from `events/director` during `run-to-decision` (inline, synchronous, pure)
- Datascript db (queried by bot decision logic to evaluate game state)
- Bot configuration: archetype keyword, deck list, behavior rules

### OUT

- `bot-decide-action` return: `{:action :cast-spell :object-id :target :tap-sequence}` or `{:action :pass}`
- No direct db writes — the director applies action results through the standard event pipeline

## Local Changes

Adding a new bot archetype (a new `defmethod` on `IBot`) or a new behavior rule is local to this component. Changes become non-local when a bot needs to interact with a game action not yet covered by the existing event API, or when bot configuration UX requires new view components.
