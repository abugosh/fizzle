# Bot Subsystem Investigation Report

**Date**: 2026-04-01  
**Scope**: Module/package level structure, dependencies, co-change patterns, and architectural coupling

---

## 1. MODULE STRUCTURE AND BOUNDARIES

### Bot Subsystem Files (bots/ — 4 files, ~520 lines)

| Module | LOC | Responsibility | Boundary Quality |
|--------|-----|-----------------|------------------|
| **protocol.cljs** | ~78 | Gateway: archetype query, bot priority/phase decisions, deck lookup | Clean |
| **definitions.cljs** | ~57 | Pure EDN data: goldfish and burn bot specs | Very Clean |
| **rules.cljs** | ~180 | Condition evaluator: multimethod dispatching on `:check` keyword | Fuzzy (throws on unknown check) |
| **interceptor.cljs** | ~262 | DEPRECATED: event handler for bot actions (registered but not called) | Leaky (dead code) |

### Key Findings on Boundaries

**Clear boundaries:**
- `definitions.cljs` is pure data with no dependencies — cleanest module
- `protocol.cljs` has single-purpose exported functions (query, decide, deck) — reasonably clean API

**Fuzzy boundaries:**
- `rules.cljs` uses `:default` multimethod case that throws (line 106-109) — forces caller to know valid `:check` keywords
- `interceptor.cljs` imports from 6 modules (protocol, game-state, queries, priority, rules, re-frame) but is not called
  - Registered at app startup (core.cljs line 3) but never dispatched
  - Tests still exist for it (bots/interceptor_test.cljs)
  - Contains action-pending guard logic with :bot/action-pending? and :bot/action-count app-db keys

**Exported interfaces:**
- `bots.protocol/get-bot-archetype(db, player-id)` → keyword | nil
- `bots.protocol/bot-priority-decision(archetype, context)` → :pass | action-map
- `bots.protocol/bot-phase-action(archetype, phase, db, player-id)` → {:action ...}
- `bots.protocol/bot-stops(archetype)` → #{:phase ...}
- `bots.protocol/bot-deck(archetype)` → [{:card/id :count}]
- `bots.protocol/bot-choose-attackers(archetype, eligible-attacker-ids)` → [object-ids]

---

## 2. IMPORT/DEPENDENCY GRAPHS

### What bots/ imports from

```
bots.protocol
  ├── datascript (d/q for db lookup)
  ├── bots.definitions (registry lookup)
  └── bots.rules (condition evaluation, action resolution)

bots.definitions
  └── (none — pure data)

bots.rules
  ├── datascript (d/q, d/pull for game state)
  ├── fizzle.db.queries (get-objects-in-zone, get-other-player-id, stack-empty?, etc.)
  └── fizzle.engine.mana (can-pay? for mana cost evaluation)

bots.interceptor
  ├── fizzle.bots.protocol (all bot decisions)
  ├── fizzle.db.game-state (player-id constants)
  ├── fizzle.db.queries (hand, opponent queries)
  ├── fizzle.engine.priority (priority holder lookup)
  ├── fizzle.engine.rules (can-play-land? check)
  └── re-frame.core (event registration)
```

### What imports from bots/

**6 modules import from bots.protocol:**
1. `events/init.cljs` (bot-deck, bot-stops)
2. `events/setup.cljs` (list-archetypes)
3. `events/resolution.cljs` (bot-deck)
4. `sharing/restorer.cljs` (bot-deck)
5. `events/director.cljs` (get-bot-archetype, bot-phase-action)
6. `core.cljs` (bots.interceptor side-effect load)

**3 modules import bots.interceptor:**
1. `core.cljs` (side-effect: event registration)
2. `events/director.cljs` (bot-decide-action, find-tap-sequence)
3. Tests only (burn_integration_test, interceptor_test)

### No Circular Dependencies

- bots → db.queries → nothing back to bots ✓
- bots.rules → engine.mana → nothing back to bots ✓
- events.director → bots → nothing back ✓

Clean dependency tree: bots/ is a leaf with dependencies flowing outward only.

---

## 3. CO-CHANGE PATTERNS

### Most Recent Bot-Related Changes (last 30 commits)

**Commit a72bebc** (Revert q-safe/pull-safe): protocol.cljs, rules.cljs
**Commit 555f66d** (Add q-safe wrappers): protocol.cljs, rules.cljs — together

**Commit 3a46619** (fizzle-2815): Unify bot/human phase advancement
- Modified: bots/protocol.cljs
- Context: "remove bot-specific priority_flow code" — suggests bot code was duplicated elsewhere
- Pattern: Bot logic extracted from priority handling

**Commit 0e45281** (fizzle-yvvg): Turn-based bot action limit
- Modified: bots/interceptor.cljs
- Adds app-db keys: `:bot/action-count` (persisted), `:bot/action-pending?` (transient)
- Pattern: Compensating code — guards against multiple executions

**Commit f774631** (fizzle-kv1f): Fix multi-color mana in find-tap-sequence
- Modified: bots/interceptor.cljs (lines 44-98)
- Issue: "per-color validation" — complex mana allocation logic
- Pattern: Bug-fix clustering for related feature

**Commit 91bdddf** (fizzle-em22): Add action-pending flag
- Modified: bots/interceptor.cljs, burn_integration_test.cljs
- Issue: "stale bot-decide race condition" — intermediate db_effect fires queued stale events
- Pattern: Workaround for db_effect + bot-decide race (see section 4)

**Commit fdcc25d** (fizzle-8g7g): Remove SBA/bot interceptors, inline SBAs
- Deleted: events/interceptors/sba.cljs (58 lines)
- Modified: bots/interceptor.cljs (removed 72 lines), core.cljs, multiple tests
- Context: Architectural shift from dual-interceptor to single chokepoint (db_effect)
- Pattern: Major structural change affecting multiple modules

**Commit 0c084f3** (Data-driven bot definitions)
- Created: bots/definitions.cljs
- Deleted: bots/actions.cljs
- Pattern: Consolidation from multimethods → EDN data

### Key Co-Change Observation

**Bots and director.cljs evolve together** (implicit dependency):
- Recent commits modify casting, priority_flow, selection/core, director
- Bots changes are isolated but orchestrated through director
- Pattern: Bot logic moved from event dispatch (interceptor) to pure function calls (director/bot-act)

**Bots and db_effect have hidden coupling:**
- Commit 91bdddf adds :bot/action-pending? guard (action-pending guard in interceptor.cljs line 192)
- Commit fdcc25d removes SBA interceptor, comments say "Bot decisions now handled inline by director"
- Pattern: Compensating code for race condition (see section 7)

---

## 4. ACTUAL CALL PATTERNS

### How Bot Turns Work (Current Architecture)

**Entry Point**: `events/priority_flow.cljs`
- Handler `::yield` calls `director/run-to-decision db {:yield-all? false :human-yielded? true}`
- Handler `::yield-all` calls `director/run-to-decision db {:yield-all? true}`

**Director Loop** (`events/director.cljs`, lines 290-359):
```
run-to-decision(app-db, opts)
  loop while holder_pid = current priority holder
    if holder_pid is bot:
      call bot-act(game-db, holder-pid)
        → returns {:action-type, :game-db, ...}
    if holder_pid is human:
      call human-should-auto-pass(...) → boolean
      if auto-pass:
        yield priority, transfer, possibly resolve or advance
```

**Bot Acting** (`events/director.cljs` bot-act, lines 90-135):
```
bot-act(game-db, player-id):
  archetype = bot-protocol/get-bot-archetype(game-db, player-id)
  phase-action = bot-protocol/bot-phase-action(archetype, phase, db, pid)
  
  if phase-action is :play-land:
    find land in hand → play via lands/play-land
    run SBAs
    
  else:
    action = bot-interceptor/bot-decide-action(game-db)
      → calls mana-activation/find-tap-sequence for each color
      → builds tap sequence
      → validates can-pay?
      
    if action is :cast-spell:
      tap lands via mana-activation/activate-mana-ability
      cast via casting/cast-spell-handler
      run SBAs
      
    else:
      return :pass
```

### Key Function Call Sites

| Function | Called From | Line | Purpose |
|----------|------------|------|---------|
| `bot-protocol/get-bot-archetype` | director (line 95, 343), init (115), setup | Query bot type |
| `bot-protocol/bot-phase-action` | director line 100 | Phase action decision |
| `bot-protocol/bot-deck` | init (127), setup, resolution, restorer | Deck initialization |
| `bot-interceptor/bot-decide-action` | director line 107 | Priority decision + mana calculation |
| `bot-interceptor/find-tap-sequence` | director line 110-114 | Mana allocation logic |
| `bot-protocol/bot-stops` | init line 135 | Set bot's phase stops |

### Re-frame Event Path (Old, now unused)

The `::bot-decide` event (interceptor.cljs lines 252-261) is registered but:
- **Not dispatched by db_effect** — comment says "Bot decisions now handled inline by director"
- **Not called from priority_flow** — director handles bot actions directly
- **Still tested** — tests/bots/interceptor_test.cljs and burn_integration_test.cljs

This is **dead code that hasn't been removed**.

---

## 5. INTERFACE SURFACE AREA

### Public Exports from bots/

**bots.protocol** (7 functions):
- `get-bot-archetype` — query bot type
- `bot-priority-decision` — dispatch on priority
- `bot-phase-action` — dispatch during phase
- `bot-choose-attackers` — choose attack creatures
- `bot-stops` — derive phase stops set
- `bot-deck` — get deck list
- Internal: `get-bot-archetype` (also exported, duplicated logic)

**bots.definitions** (2 functions):
- `get-spec` — look up bot spec by archetype
- `get-deck` — look up deck by archetype
- `list-archetypes` — enumerate available types

**bots.rules** (exposed via protocol):
- `evaluate-condition` — multimethod: evaluate game state condition
- `evaluate-conditions` — evaluate AND of conditions
- `resolve-action` — resolve action template to concrete game objects
- `match-priority-rule` — find first matching rule
- `get-phase-action` — look up phase action
- `choose-attackers` — select attacking creatures

**bots.interceptor** (exposed but not called):
- `bot-should-act?` — check if priority holder is bot
- `bot-decide-action` — build action plan
- `build-bot-dispatches` — convert action to re-frame dispatches (unused)
- Event handlers: `::bot-decide`, `::bot-action-complete` (registered but not called)

### Interface Issues

1. **Rules multimethod is leaky** — `:default` case throws (line 106-109)
   - Caller must validate `:check` keyword against known types
   - No type registry or schema

2. **Interceptor's ::bot-decide is ghost code**
   - Fully functional but unreachable
   - Tests exist (interceptor_test, burn_integration_test)
   - Guards against stale events (action-pending flag) that no longer occur

3. **bot-deck is called from 4 places**
   - Tight coupling: all opponent initialization depends on bot protocol
   - No fallback if bot-archetype is invalid

---

## 6. SHARED STATE

### App-db Keys Used by Bots

**Set by interceptor.cljs (now dead code, but still registered):**
- `:bot/action-pending?` — guard against stale bot-decide events (line 238)
- `:bot/action-count` — track bot actions per turn (line 237, 224)

**Not set by director** — director is pure, doesn't touch app-db except via engine functions

**Set during initialization** (init.cljs):
- `player/bot-archetype` — stored on opponent entity (line 115)
- `player/stops` — set to `bot-protocol/bot-stops` for opponent (line 135)

### Game-db State Read by Bots

- `player/bot-archetype` — query to get archetype
- `game/priority` — priority holder
- `player/id` — player identity
- Zone objects — hand, battlefield, graveyard, library, stack
- Mana pool — for can-pay? checks
- Stack items — for condition evaluation

### No Mutable Shared State

- Bots don't mutate atoms or vars
- All state flows through Datascript db and re-frame app-db
- No race conditions from mutations, only from event sequencing (addressed by action-pending guard)

---

## 7. COMPENSATING CODE PATTERNS

### Pattern 1: Action-Pending Guard (Interceptor)

**File**: bots/interceptor.cljs, lines 192-193

```clojure
(cond
  ;; Guard: compound action in progress — suppress stale bot-decide (no-op)
  (:bot/action-pending? app-db)
  {:db app-db}
```

**Purpose**: Prevent stale bot-decide events from firing while tap+cast is executing

**Reason for Existence**:
- Old architecture: db_effect queued ::bot-decide after every game-db mutation
- Compound actions (tap+cast) mutate db twice (once per tap, once for cast)
- Each mutation triggered db_effect → queued stale ::bot-decide
- Result: Multiple ::bot-decide events in flight, causing involuntary human passes

**Commits referencing this**:
- 91bdddf (fizzle-em22): Adds the guard, documents race condition
- fdcc25d (fizzle-8g7g): Comment says "Bot decisions now handled inline by director" but guard remains

**Dead Code Indicator**: Director now handles bots inline (no event dispatch), so this guard is unreachable

### Pattern 2: Bot-Specific Phase Advancement (Removed)

**File**: Removed in 3a46619 (fizzle-2815)

**What was removed**: "remove bot-specific priority_flow code"
- Commit message suggests bots had separate phase advancement logic
- Unified into single path by moving bot logic to protocol

**Pattern**: Duplicate code reduction — consolidate bot and human paths

### Pattern 3: Mana Allocation Logic Extraction

**File**: bots/interceptor.cljs, lines 44-98 (find-tap-sequence)

**Purpose**: Allocate color-specific mana first, then generic mana

**Complexity**: 
- Tracks allocated-ids (volatile!) to prevent double-allocation of dual lands
- Per-color filtering with multimethod dispatch on ability-type
- 54 lines of stateful reduction

**Pattern**: Complex business logic embedded in interceptor module
- Not accessible to director's pure bot-act
- Director calls this directly (line 107: `bot-interceptor/bot-decide-action` → calls find-tap-sequence internally)

**Why not pure**: Uses volatile! for mutation (allocating ids) — not truly pure despite being a "pure function" comment

### Pattern 4: Bot-Specific Stop Initialization

**File**: events/init.cljs, lines 135

```clojure
[:db/add opp-eid :player/stops (bot-protocol/bot-stops bot-archetype)]
```

**Purpose**: Set opponent's phase stops based on bot spec

**Pattern**: Compensating for lack of human stop configuration UX
- Humans load stops from localStorage (events/init.cljs line 132)
- Bots derive stops from spec (derived from phase-actions keys)

**Tight Coupling**: Opponent initialization assumes bot spec exists

### Pattern 5: Orphaned Event Handler with Tests

**File**: bots/interceptor.cljs, lines 252-261

```clojure
(rf/reg-event-fx ::bot-decide ...)
(rf/reg-event-fx ::bot-action-complete ...)
```

**Status**: Registered at startup (core.cljs line 3), but:
- db_effect doesn't queue it
- director doesn't dispatch it
- Tests still exist (full integration tests)

**Pattern**: Code that became unreachable during architectural refactoring
- Still functional (event handlers work if dispatched)
- Still tested (tests pass, but exercise code path that's never hit in production)
- Not deleted because old tests document the design

---

## 8. FIX CLUSTERING IN GIT HISTORY

### Cluster 1: Race Condition Fixes (March 26-27, 2026)

**Commits**: 91bdddf, fdcc25d, 8af5006

| Commit | Focus | Issue | Fix |
|--------|-------|-------|-----|
| 91bdddf | Action-pending flag | Stale bot-decide during tap+cast | Add guard + sentinel |
| fdcc25d | Remove SBA interceptor | SBA not firing after selections | Move to db_effect chokepoint |
| 8af5006 | Auto-confirm dispatch | SBA not firing after auto-confirm | Use rf/dispatch (async) |

**Pattern**: All three commits fix cascading failures from db_effect → bot-decide → SBA ordering

### Cluster 2: Architecture Simplification (fizzle-8g7g to fizzle-2815)

**Commits**: fdcc25d (remove interceptors) → 3a46619 (unify phase advancement)

**Result**: Shift from dispatch-based (interceptor) to call-based (director)

**Trade-offs**:
- ✓ Simpler orchestration (pure function)
- ✓ Clearer data flow
- ✗ Dead code left (interceptor still registered)
- ✗ Tighter coupling (director imports both bot modules)

### Cluster 3: Data-Driven Refactoring (commits 0c084f3 onwards)

**Commits**: 0c084f3 (EDN specs), 5b71229 (attack strategy config)

**Pattern**: Move from multimethods → EDN data
- Reduces code paths
- Makes bot behavior inspectable/configurable
- But adds validation complexity (rules.cljs `:default` case)

### Critical Commits

**a72bebc** (Revert q-safe/pull-safe): Most recent (2026-04-01 estimated)
- Reverted 555f66d that added q-safe/pull-safe wrappers
- Suggests wrapper experiment failed or was deemed unnecessary

---

## 9. CRITICAL FINDINGS

### 🔴 Dead Code: Orphaned Event Handler Path

**Severity**: MEDIUM (not breaking, but confusing)

**Location**: bots/interceptor.cljs lines 252-261 + tests

**Issue**:
- `::bot-decide` and `::bot-action-complete` events are registered
- They are fully functional (tests pass)
- But they are **never dispatched** in production code
- db_effect comment explicitly says "Bot decisions handled inline by director"
- Director calls bot functions directly instead of dispatching events

**Evidence**:
- No `rf/dispatch [::bot-decide]` found in any non-test file
- db_effect.cljs (lines 1-51) doesn't queue bot-decide
- director.cljs inlines `bot-interceptor/bot-decide-action` (line 107)
- Tests still exist and exercise the event handler (burn_integration_test.cljs)

**Recommendation**: Remove the dead event handlers and consolidate tests

---

### 🟡 Unnecessary Coupling: Mutable Volatile in "Pure" Function

**Severity**: LOW (works, but violates design principle)

**Location**: bots/interceptor.cljs, lines 44-98 (find-tap-sequence)

**Issue**:
- Function is documented as "Pure function: ..."
- Actually uses `volatile!` for mutation (line 53: `(volatile! #{})`)
- ClojureScript's volatile! is mutable in spirit even if Datascript transactions are pure

**Impact**: Minimal (volatile is thread-safe in single-threaded JS), but philosophically inconsistent

**Recommendation**: 
- If inlining mutable state is necessary, document it
- Consider accepting allocated-ids as a parameter instead

---

### 🟡 Leaky Multimethod: Rules with No Schema

**Severity**: LOW (controlled by bot specs, but fragile)

**Location**: bots/rules.cljs, lines 26-109

**Issue**:
- 6 `:check` types implemented (zone-contains, has-mana-for, stack-empty, has-untapped-source, zone-count, stack-has)
- `:default` case throws (line 108)
- No schema/validation of bot specs against known condition types
- Caller must validate before dispatch or face runtime error

**Impact**: 
- Burn bot defines 3 conditions (zone-contains, has-untapped-source, stack-empty) — all valid
- Goldfish bot has 0 conditions — safe
- But no compile-time check if someone adds invalid condition to spec

**Recommendation**:
- Add spec schema validation in definitions.cljs
- Or use closed multimethod with default logging

---

### 🟡 Tight Coupling: Opponent Initialization Depends on Bot Specs

**Severity**: LOW (by design, but limits flexibility)

**Location**: 
- events/init.cljs lines 106-115
- events/setup.cljs
- events/resolution.cljs
- sharing/restorer.cljs

**Issue**:
- All 4 modules call `bot-protocol/bot-deck` to get opponent deck
- Breaks if archetype is invalid or missing
- No fallback to a default deck

**Pattern**:
```clojure
(or bot-deck [])  ; only used in init.cljs line 127
(or (definitions/get-deck archetype) (definitions/get-deck :goldfish))  ; fallback in protocol.cljs
```

**Impact**: If someone passes invalid bot-archetype, opponent starts with empty hand

**Recommendation**: Consistent fallback across all 4 call sites

---

### 🟢 Clean Separation: Protocol/Rules/Definitions

**Strength**: Module structure is clear

**Evidence**:
- definitions.cljs is pure data, no code
- protocol.cljs is a thin dispatch layer
- rules.cljs is condition evaluation
- Clear responsibility separation

**Pattern**: Well-designed public API with internal complexity hidden

---

## 10. SUMMARY: SHEARING LAYERS AND COUPLING POINTS

### Shearing Layers (Where Changes Would Propagate)

| Layer | Modules | Coupling Strength | Risk |
|-------|---------|------------------|------|
| Bot specs | definitions.cljs | Low (pure data) | Adding new archetype: easy |
| Bot decisions | protocol.cljs + rules.cljs | Medium (rules → queries) | Changing condition types: requires spec updates |
| Bot orchestration | director.cljs + priority_flow.cljs | Medium (director calls bot functions) | New bot action type: requires director update |
| Game initialization | init.cljs | High (tightly coupled to bot-deck) | Opponent deck initialization |

### Cross-Cutting Concerns Not Properly Abstracted

1. **Bot action counting** (app-db :bot/action-count)
   - Initialized in db_effect comment (line 308)
   - Used in director to guard safety limit (line 200)
   - Set in tests but never in production code

2. **Phase stops derivation**
   - Human: loaded from localStorage, set at init
   - Bot: derived from bot specs, set at init
   - No abstraction — two different code paths

3. **Mana allocation**
   - Embedded in bot-interceptor/find-tap-sequence
   - Only accessible via bot-decide-action
   - Not reusable for other purposes

### Dependency Inversion Issues

**Current**: director → bots.interceptor, bots.protocol

**Should be**: director → bots.protocol only
- Interceptor should not be imported by director
- Director should call bot-protocol functions directly (doesn't need bot-decide-action)
- Or: create director-specific bot functions in protocol layer

---

## 11. RECOMMENDATIONS FOR INVESTIGATION FOLLOW-UP

1. **Remove Dead Code**
   - Delete bots/interceptor.cljs (or move ::bot-decide to events/director.cljs if needed)
   - Update imports in core.cljs
   - Consolidate tests into director_test.cljs

2. **Add Bot Spec Schema Validation**
   - Validate :check keywords against known types
   - Validate :action keywords
   - Fail fast at init, not at decision time

3. **Consolidate Opponent Initialization**
   - Create shared function in events/init.cljs
   - All callers (setup, resolution, restorer) use same pattern with fallback

4. **Extract Mana Allocation**
   - Move find-tap-sequence to engine/mana (alongside can-pay?)
   - Make it testable independently
   - Document mutability constraints

5. **Clean Up App-db Keys**
   - :bot/action-pending?, :bot/action-count are dead
   - Remove from core.cljs init (line 151)
   - Remove from director cleanup (line 307)

---

## File Index

**Bot Subsystem**:
- `/Users/abugosh/g/fizzle/src/main/fizzle/bots/protocol.cljs` (78 lines)
- `/Users/abugosh/g/fizzle/src/main/fizzle/bots/definitions.cljs` (57 lines)
- `/Users/abugosh/g/fizzle/src/main/fizzle/bots/rules.cljs` (180 lines)
- `/Users/abugosh/g/fizzle/src/main/fizzle/bots/interceptor.cljs` (262 lines, dead code)

**Key Dependencies**:
- `/Users/abugosh/g/fizzle/src/main/fizzle/events/director.cljs` (360 lines, orchestrator)
- `/Users/abugosh/g/fizzle/src/main/fizzle/events/priority_flow.cljs` (175 lines, event handlers)
- `/Users/abugosh/g/fizzle/src/main/fizzle/events/db_effect.cljs` (51 lines, chokepoint)
- `/Users/abugosh/g/fizzle/src/main/fizzle/events/init.cljs` (game initialization)
- `/Users/abugosh/g/fizzle/src/main/fizzle/engine/priority.cljs` (88 lines, bookkeeping)

**Tests**:
- `src/test/fizzle/bots/interceptor_test.cljs` (dead code exercised)
- `src/test/fizzle/bots/burn_integration_test.cljs` (dead code exercised)
- `src/test/fizzle/bots/protocol_test.cljs`
- `src/test/fizzle/events/director_test.cljs` (exercises bot-act via director)

