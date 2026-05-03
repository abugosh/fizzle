---
name: Bot subsystem architecture and dead code
description: Bot module structure (4 files, 520 LOC), dependency graph, dead event handler path, and tight coupling points
type: reference
---

# Bot Subsystem Architecture

## Overview
- **Module**: bots/ (4 files: protocol.cljs, definitions.cljs, rules.cljs, interceptor.cljs)
- **LOC**: ~520 lines total
- **Status**: Partially dead code (event handler path orphaned in favor of director)

## Public API (bots/protocol.cljs)

Entry points for bot behavior:
- `get-bot-archetype(db, player-id)` → keyword | nil
- `bot-priority-decision(archetype, context)` → :pass | action-map
- `bot-phase-action(archetype, phase, db, player-id)` → {:action ...}
- `bot-stops(archetype)` → #{:phase ...}
- `bot-deck(archetype)` → [{:card/id :count}]
- `bot-choose-attackers(archetype, eligible-attacker-ids)` → [object-ids]

## Who Calls Bots

**Direct callers** (must import from bots/):
- `events/director.cljs` — calls bot-protocol functions + bot-decide-action (main orchestrator)
- `events/init.cljs` — calls bot-deck, bot-stops (opponent setup)
- `events/setup.cljs` — calls list-archetypes
- `events/resolution.cljs`, `sharing/restorer.cljs` — call bot-deck (deck initialization)

**Dead code path** (registered but never called):
- `bots/interceptor.cljs` :: `::bot-decide` event handler (lines 252-261)
  - Fully functional but unreachable
  - Was dispatched by db_effect in old architecture
  - Replaced by director's pure bot-act function (inline calls)
  - Tests still exercise it (burn_integration_test, interceptor_test)

## Key Architectural Change

**Old**: db_effect → queue ::bot-decide event → bot-decide-handler → dispatch cast/tap/pass
**New**: director/run-to-decision → call bot-act (pure fn) → call engine functions directly

**Cost**: 
- Dead code not removed (interceptor.cljs still registered in core.cljs)
- app-db keys `:bot/action-pending?`, `:bot/action-count` unused in production

## Dependencies Flow

```
bots.protocol
  ├── bots.definitions (spec lookup)
  └── bots.rules (condition evaluation, action resolution)

bots.rules
  ├── fizzle.db.queries (zone queries, stack, mana pool)
  └── fizzle.engine.mana (can-pay?)

bots.interceptor
  ├── bots.protocol
  ├── fizzle.engine.priority (priority-holder-eid)
  ├── fizzle.engine.rules (can-play-land?)
  ├── fizzle.db.queries
  └── re-frame.core (event registration)

events/director
  ├── bots.interceptor (bot-decide-action, find-tap-sequence)
  ├── bots.protocol (get-bot-archetype, bot-phase-action)
  ├── fizzle.engine.priority
  └── fizzle.engine.{mana-activation, rules, state-based}
```

No circular dependencies detected.

## Critical Issues

### Dead Code: ::bot-decide Event Handler
- **File**: bots/interceptor.cljs lines 252-261
- **Status**: Registered at startup, never dispatched in production
- **Evidence**: db_effect.cljs comment (line 9) says "Bot decisions now handled inline by director"
- **Tests**: Still pass (tests/bots/interceptor_test.cljs, burn_integration_test.cljs)
- **Action**: Should be removed; move remaining logic to director if needed

### Mutable Volatile in "Pure" Function
- **File**: bots/interceptor.cljs find-tap-sequence (line 53: `volatile! #{}`)
- **Issue**: Uses mutable state despite "Pure function" documentation
- **Impact**: Low (single-threaded JS), philosophically inconsistent
- **Action**: Document or refactor

### Leaky Multimethod: No Schema Validation
- **File**: bots/rules.cljs multimethod :default case throws (line 108)
- **Issue**: No schema validation of bot specs against known condition types
- **Risk**: Invalid :check keyword in bot spec throws at decision time
- **Action**: Add spec schema validation in definitions.cljs

### Tight Coupling: Opponent Deck Initialization
- **Files**: 4 modules call bot-protocol/bot-deck (init, setup, resolution, restorer)
- **Issue**: No fallback if bot-archetype is invalid
- **Risk**: Invalid archetype → empty opponent deck
- **Pattern**: Should consolidate to single function with consistent fallback

## Co-Change Cluster

Recent commits touching bots/:
- a72bebc (2026-04-01): Revert q-safe/pull-safe wrappers
- 3a46619: Unify bot/human phase advancement
- 0e45281: Turn-based bot action limit
- 91bdddf: Action-pending flag (race condition fix)
- fdcc25d: Remove SBA/bot interceptors (major refactoring)

Pattern: Race condition fixes (91bdddf, fdcc25d, 8af5006) cluster from March 26-27 due to db_effect + bot-decide sequencing issues.

## Fan-In/Fan-Out

- **bots.protocol** fan-in: 6 modules (public API)
- **bots.interceptor** fan-out: 6 modules (but unreachable)
- **bots.rules** fan-in: 1 (protocol only)
- **bots.definitions** fan-in: 1 (protocol only), dependencies: 0

## Recommendations

1. Remove bots/interceptor.cljs orphaned event handlers
2. Add spec schema validation for bot conditions
3. Consolidate opponent deck initialization
4. Clean up unused app-db keys (:bot/action-pending?, :bot/action-count)
5. Extract find-tap-sequence to engine/mana (reusable, testable independently)
