# ADR 021: Director Is Sole Bot Orchestration Mechanism

## Status

Accepted

## Context

The bot system was originally built on event-dispatch orchestration: a global re-frame interceptor (`bots/interceptor.cljs`) registered `::bot-decide` and `::bot-action-complete` event handlers, and the `db_effect` chokepoint dispatched `::bot-decide` after every game-db mutation where a bot held priority. Compound actions (tap lands then cast a spell) required an action-pending flag, sentinel dispatches, and race condition guards.

This architecture produced cascading bugs across bot epics 1-3: event sequencing failures, stale `::bot-decide` dispatches during intermediate states, duplicate actions, and race conditions between the SBA chokepoint and bot decision dispatches. Each fix introduced new compensating patterns (action-pending guards, action count limits, sentinel events).

The director (`events/director.cljs`) was introduced to replace this architecture. It runs the game loop as a single synchronous pure function — bot actions are applied inline via pure function calls, with no re-frame dispatch, no action-pending flags, and no race conditions. The director is now the production code path for all bot orchestration.

However, the transition was never structurally completed. The old event-dispatch infrastructure remains in `bots/interceptor.cljs`:
- `::bot-decide` and `::bot-action-complete` handlers are registered at startup but never dispatched
- `bot-decide-handler`, `bot-action-complete-handler`, `build-bot-dispatches` are dead functions
- `find-bot-land-to-play` is duplicated between interceptor.cljs and director.cljs
- The module docstring describes the old architecture
- Dead app-db keys (`:bot/action-pending?`, `:bot/action-count`) are stripped by the director on entry

The module name "interceptor" reflects the old architecture, not the current role (pure decision computation for the director).

## Decision

We will complete the architecture transition by removing all dead event-dispatch infrastructure from the bot system and renaming the module to reflect its actual role.

Specifically:
1. Delete dead event handlers (`::bot-decide`, `::bot-action-complete`) and their supporting functions (`bot-decide-handler`, `bot-action-complete-handler`, `build-bot-dispatches`, interceptor's `find-bot-land-to-play`)
2. Rename `bots/interceptor.cljs` to reflect its current role (pure decision/mana-allocation computation)
3. Remove dead app-db key references (`:bot/action-pending?`, `:bot/action-count`) from director entry-point cleanup
4. Remove the side-effect require of `bots.interceptor` in `core.cljs` (no handlers to register)
5. Update the module docstring to describe the current architecture
6. Update architecture model descriptions (`bots.c4`, `model.c4`) to reflect director-based orchestration

The director is the sole bot orchestration mechanism. Event-dispatch bot orchestration is permanently retired.

## Consequences

**Positive:**
- ~100 lines of dead code removed from `bots/interceptor.cljs`
- No dormant re-frame handlers registered at startup — eliminates the risk of accidental dispatch activating the old architecture
- Module name matches its role — developers reading the code see what it actually does
- Model and docstrings describe the architecture that exists, not the one that was replaced
- The director's entry-point cleanup (`dissoc` of dead keys) becomes unnecessary

**Negative:**
- Tests exercising dead handlers (`interceptor_test.cljs`, portions of `burn_integration_test.cljs`) need updating or removal
- If a future design requires event-dispatch bot orchestration, the code must be rewritten from scratch rather than reactivated

**Neutral:**
- The active pure functions (`bot-decide-action`, `find-tap-sequence`, `bot-should-act?`) are unchanged — only their module location and name change
- The director's import path updates but its behavior is identical
