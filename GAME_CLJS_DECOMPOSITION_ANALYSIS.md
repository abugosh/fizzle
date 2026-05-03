# game.cljs Decomposition Analysis

**File**: `/Users/abugosh/g/fizzle/src/main/fizzle/events/game.cljs`
**Current size**: 903 lines
**Status**: ADR-016 accepted — ready for decomposition

---

## Executive Summary

`game.cljs` braids three independent concerns (~250 + 93 + 420 = 763 LOC), with a 140-line re-export facade above. The decomposition follows the extraction pattern already applied to phases, cleanup, and lands modules. The three modules have clear seams: **casting has zero dependencies** on the other two, **resolution** is only called by **priority**, and **priority** orchestrates all three through public interfaces.

---

## 1. CURRENT STRUCTURE

### Overall Architecture (903 lines)

```
Lines 1-47:     Re-exports of extracted modules (phases, cleanup, lands, init, ui)
Lines 49-296:   CASTING CONCERN (~250 lines)
Lines 298-430:  RESOLUTION CONCERN (~130 lines)
Lines 433-855:  PRIORITY FLOW CONCERN (~420 lines)
Lines 858-903:  UTILITY (cycling) (~45 lines)
```

---

## 2. CASTING CONCERN (~250 lines)

### Scope: Lines 49-296

**Entry point**: `::cast-spell` event handler (line 299-301)

**Public functions to extract:**
- `cast-spell-handler` (lines 256-295) — Pure function: (app-db, opts?) -> app-db
- `select-casting-mode-handler` (lines 825-836) — Pure function: (app-db, mode) -> app-db
- `cancel-mode-selection-handler` (lines 845-849) — Pure function: (app-db) -> app-db

**Internal functions:**
- `initiate-cast-with-mode` (lines 173-198) — Drive pre-cast pipeline, return app-db
- `get-valid-spell-modes` (lines 201-213) — Filter modes by valid targets
- `build-spell-mode-selection` (lines 216-232) — Construct modal spell selector

**Multimethod definitions:**
- `evaluate-pre-cast-step` multimethod (line 56-68) — Dispatch key: step keyword
  - `:exile-cards-cost` (lines 78-84)
  - `:return-land-cost` (lines 87-93)
  - `:discard-specific-cost` (lines 96-102)
  - `:sacrifice-permanent-cost` (lines 105-111)
  - `:pay-x-life` (lines 114-117)
  - `:x-mana-cost` (lines 120-123)
  - `:targeting` (lines 126-158) — Complex: handles both pre-determined and interactive targets
  - `:mana-allocation` (lines 161-170)

**Data structures:**
- `pre-cast-pipeline` (lines 71-75) — Vector of step keywords in order

**Multimethod implementations (defmethod):**
- `:spell-mode` executor (lines 235-242) — Executes spell-mode selection
- `:cast-after-spell-mode` continuation (lines 245-253) — Routes to pre-cast pipeline after mode selection

**Event handlers:**
- `::cast-spell` (lines 298-301) — Calls `cast-spell-handler`, re-frames wrapper
- `::select-casting-mode` (lines 839-842) — Calls `select-casting-mode-handler`
- `::cancel-mode-selection` (lines 852-855) — Calls `cancel-mode-selection-handler`

**Dependencies (external):**
- `datascript.core` (d/pull, d/db-with)
- `fizzle.db.queries` (get-object, get-human-player-id)
- `fizzle.engine.rules` (can-cast?, get-casting-modes, can-cast-mode?, cast-spell-mode)
- `fizzle.engine.static-abilities` (get-effective-mana-cost)
- `fizzle.engine.targeting` (get-targeting-requirements, find-valid-targets)
- `fizzle.events.selection.core` (build-selection-for-effect, execute-confirmed-selection, apply-continuation)
- `fizzle.events.selection.costs` (has-*-cost?, build-*-selection functions)
- `fizzle.events.selection.targeting` (build-cast-time-target-selection, confirm-cast-time-target)
- `re-frame.core` (rf/reg-event-db)

**No internal dependencies on**: resolution, priority, or any other game.cljs function

---

## 3. RESOLUTION CONCERN (~130 lines)

### Scope: Lines 304-430

**Entry point**: `::resolve-top` event handler (lines 400-409)

**Public functions to extract:**
- `resolve-one-item` (lines 352-397) — Resolve top stack item, return {:db} or {:db :pending-selection}
  - Core logic: dispatch on stack-item/type via engine-resolution multimethod
  - Handles 4 interactive branches: :needs-storm-split, :needs-attackers, :needs-blockers, :needs-selection

**Internal functions:**
- `get-source-id` (lines 304-311) — Extract object-id from stack-item for selection building
- `build-selection-from-result` (lines 314-339) — Convert engine-resolution result with :needs-selection into pending-selection
- `clear-peek-result` (lines 342-349) — Housekeeping: clear stale game/peek-result

**Event handlers:**
- `::resolve-top` (lines 400-409) — Single-resolution entry point
  - Calls `resolve-one-item`, handles pending-selection return
  - Calls `cleanup/maybe-continue-cleanup` for next phase
- `::resolve-all` (lines 412-430) — Cascade resolution until stack empty
  - Re-frames loop via `:fx [[:dispatch [::resolve-all initial-ids]]]`

**Dependencies (external):**
- `datascript.core` (d/pull)
- `fizzle.db.queries` (get-object, get-human-player-id, stack-empty?, get-all-stack-items)
- `fizzle.engine.resolution` (resolve-stack-item)
- `fizzle.engine.stack` (get-top-stack-item, remove-stack-item)
- `fizzle.bots.protocol` (get-bot-archetype, bot-choose-attackers)
- `fizzle.events.selection.core` (build-selection-for-effect, confirm-selection-impl)
- `fizzle.events.selection.combat` (build-attacker-selection, build-blocker-selection)
- `fizzle.events.selection.storm` (build-storm-split-selection)
- `fizzle.events.cleanup` (maybe-continue-cleanup)
- `re-frame.core` (rf/reg-event-db, rf/reg-event-fx)

**Internal dependency**: Called ONLY by priority flow (`yield-impl` → `yield-resolve-stack` → `resolve-one-item`)

---

## 4. PRIORITY FLOW CONCERN (~420 lines)

### Scope: Lines 433-820

**Entry point**: `::yield` event handler (lines 721-753)

**Public functions to extract:**
- `negotiate-priority` (lines 638-691) — Pass priority between players, return {:app-db, :all-passed?}
- `advance-with-stops` (lines 435-482) — Batch advance phases until stop/turn-boundary, return {:app-db}
- `yield-impl` (lines 694-712) — Core priority logic: negotiate → resolve-stack or advance-phases

**Internal functions:**
- `yield-resolve-stack` (lines 485-512) — When all passed + stack not empty: resolve top item
- `player-is-bot?` (lines 515-520) — Check DB for bot archetype
- `bot-would-pass?` (lines 523-532) — Query bot protocol for priority decision
- `bot-turn-advance-one-phase` (lines 535-557) — Advance exactly one phase during bot turn
- `yield-advance-phase` (lines 560-635) — When all passed + stack empty: advance phases
  - Bot turns: one phase at a time (for bot interceptor)
  - Human turns: batch to next stop (via `advance-with-stops`)
- `resolve-one-and-stop` (lines 769-779) — Resolve top item with temporary :resolving auto-mode

**Constants:**
- `max-yield-steps` (lines 715-718) — Safety limit = 200 steps per cascade

**Event handlers:**
- `::yield` (lines 721-753) — Recursive priority passing
  - Calls `yield-impl`
  - Handles auto-mode cascading via dispatch-later (100ms intervals)
  - Manages `:yield/step-count` for safety limit
  - Returns `{:db, :fx [[:dispatch [...]]]}` on continue
- `::yield-all` (lines 756-766) — Enter auto-mode
  - Sets auto-mode to `:f6` or `:resolving` based on stack
  - Dispatches `::yield` to start cascade
- `::cast-and-yield` (lines 789-813) — Cast then resolve once
  - Calls `cast-spell-handler`
  - Sets `:resolve-one-and-stop` continuation if needed
  - Calls `resolve-one-and-stop` for auto-pass resolution
- `::cast-and-yield-resolve` (lines 816-819) — One-item resolution helper

**Multimethod implementations:**
- `:resolve-one-and-stop` continuation (lines 784-786) — Calls `resolve-one-and-stop`

**Dependencies (external):**
- `datascript.core` (d/q, d/pull)
- `fizzle.db.queries` (get-active-player-id, get-player-eid, get-other-player-id, get-game-state, stack-empty?, get-human-player-id, get-all-stack-items)
- `fizzle.engine.priority` (get-auto-mode, check-stop, yield-priority, both-passed?, reset-passes, transfer-priority, set-auto-mode, clear-auto-mode, get-priority-holder-eid)
- `fizzle.bots.protocol` (get-bot-archetype, bot-priority-decision)
- `fizzle.events.phases` (next-phase, advance-phase, start-turn)
- `fizzle.events.cleanup` (begin-cleanup, maybe-continue-cleanup)
- `fizzle.events.selection.core` (apply-continuation)
- `re-frame.core` (rf/reg-event-db, rf/reg-event-fx)

**Internal dependencies:**
- Calls `resolve-one-item` (from resolution concern)
- Calls `cast-spell-handler` (from casting concern)
- Calls `advance-phase` (from phases)
- Calls `cleanup/begin-cleanup` and `cleanup/maybe-continue-cleanup` (from cleanup)

---

## 5. EXTRACTION PATTERN

### Precedent: phases, cleanup, lands modules

**Pattern observed:**

1. **Extract to new file** (e.g., `events/phases.cljs`)
   - Move all functions, defmultis, defmethods
   - Preserve internal `defn-` for private functions
   - Include docstrings and comments

2. **Register event handlers in the extracted file**
   ```clojure
   (rf/reg-event-db
     ::start-turn
     (fn [db _]
       (start-turn-handler db)))
   ```

3. **Re-export facade in game.cljs** (lines 33-46 current)
   ```clojure
   (def start-turn phases/start-turn)
   (def advance-phase phases/advance-phase)
   ```

4. **Import the extracted module in game.cljs**
   ```clojure
   [fizzle.events.phases :as phases]
   ```

5. **No downstream changes needed** — All existing requires of `fizzle.events.game` still work

### Files to create:

1. **`events/casting.cljs`** — ~250 lines
   - Ns: `fizzle.events.casting`
   - Exports: `cast-spell-handler`, `select-casting-mode-handler`, `cancel-mode-selection-handler`, `evaluate-pre-cast-step`, `pre-cast-pipeline`
   - Multimethod defs: `:spell-mode`, `:cast-after-spell-mode`

2. **`events/resolution.cljs`** — ~130 lines
   - Ns: `fizzle.events.resolution`
   - Exports: `resolve-one-item`
   - Public helpers: (none required for downstream)

3. **`events/priority_flow.cljs`** — ~420 lines
   - Ns: `fizzle.events.priority-flow`
   - Exports: `negotiate-priority`, `advance-with-stops`, `yield-impl`

4. **`events/game.cljs` → facade** — ~140 lines
   - Re-exports from all 6 modules (init, phases, cleanup, lands, casting, resolution, priority-flow, ui)
   - Utility: `cycle-card` (stays in game.cljs as catch-all)

---

## 6. DEPENDENCY GRAPH

### Casting → (Zero dependencies on other concerns)
```
casting.cljs
  ← engine/rules, engine/targeting, engine/static-abilities
  ← selection/* (costs, targeting)
```

### Resolution → (One dependency: only called by priority)
```
resolution.cljs
  → priority_flow.cljs (via yield-resolve-stack)
  ← engine/resolution, engine/stack
  ← selection/* (combat, storm)
  ← cleanup (maybe-continue-cleanup)
```

### Priority Flow → (Orchestrates casting + resolution + phases)
```
priority_flow.cljs
  → casting.cljs (cast-spell-handler)
  → resolution.cljs (resolve-one-item)
  → phases.cljs (advance-phase, next-phase, start-turn)
  → cleanup.cljs (begin-cleanup, maybe-continue-cleanup)
  ← engine/priority, bots/protocol
  ← selection/core (apply-continuation)
```

### game.cljs → (Pure re-export facade, no logic)
```
game.cljs facade
  re-exports from: casting, resolution, priority_flow, phases, cleanup, lands, init, ui
```

---

## 7. EXTRACTION ORDER

**Critical**: Extract in dependency order to avoid circular imports.

1. **Phase 1: casting.cljs** (zero deps) — Lines 49-296, then lines 825-855
2. **Phase 2: resolution.cljs** (depends on nothing in codebase) — Lines 304-430
3. **Phase 3: priority_flow.cljs** (depends on casting + resolution + phases) — Lines 433-820

---

## 8. MULTIMETHOD CONTINUATIONS

### Multimethod registrations that move with modules:

**casting.cljs:**
```clojure
;; Line 235-242: execute-confirmed-selection for :spell-mode
(defmethod sel-core/execute-confirmed-selection :spell-mode ...)

;; Line 245-253: apply-continuation for :cast-after-spell-mode
(defmethod sel-core/apply-continuation :cast-after-spell-mode ...)
```

**priority_flow.cljs:**
```clojure
;; Line 784-786: apply-continuation for :resolve-one-and-stop
(defmethod sel-core/apply-continuation :resolve-one-and-stop ...)
```

All multimethod registrations require the module to be loaded at startup. The current pattern in `selection/core.cljs` handles this via require-side-effects.

---

## 9. WHAT REQUIRES game.cljs?

### Direct requires (via grep):
```bash
grep -r "fizzle\.events\.game" src/main src/test
```

Expected: Mostly re-frame event keywords (`:game/::cast-spell`, etc.) and test files.

**Change impact**: None. All downstream code dispatches events by keyword, not by importing functions. The re-export facade preserves all function names.

---

## 10. Re-export Facade Template (game.cljs post-extraction)

```clojure
(ns fizzle.events.game
  "Game orchestration façade. Re-exports from decomposed sub-modules for backward compatibility."
  (:require
    [fizzle.events.init :as init]
    [fizzle.events.phases :as phases]
    [fizzle.events.cleanup :as cleanup]
    [fizzle.events.lands :as lands]
    [fizzle.events.casting :as casting]
    [fizzle.events.resolution :as resolution]
    [fizzle.events.priority-flow :as priority-flow]
    [fizzle.events.ui :as ui]))

;; Re-export public API from extracted modules for backward compatibility

(def init-game-state init/init-game-state)
(def set-active-screen-handler ui/set-active-screen-handler)
(def begin-cleanup cleanup/begin-cleanup)
(def complete-cleanup-discard cleanup/complete-cleanup-discard)
(def maybe-continue-cleanup cleanup/maybe-continue-cleanup)
(def phases phases/phases)
(def next-phase phases/next-phase)
(def advance-phase phases/advance-phase)
(def untap-all-permanents phases/untap-all-permanents)
(def start-turn phases/start-turn)
(def land-card? lands/land-card?)
(def can-play-land? lands/can-play-land?)
(def play-land lands/play-land)
(def tap-permanent lands/tap-permanent)
(def cast-spell-handler casting/cast-spell-handler)
(def select-casting-mode-handler casting/select-casting-mode-handler)
(def cancel-mode-selection-handler casting/cancel-mode-selection-handler)
(def resolve-one-item resolution/resolve-one-item)
(def negotiate-priority priority-flow/negotiate-priority)
(def advance-with-stops priority-flow/advance-with-stops)
(def yield-impl priority-flow/yield-impl)

;; Utility function (low-churn, stays in facade)
(defn cycle-card [...] ...)

;; Event handlers are registered in their respective modules,
;; but can also be registered here if needed for organization.
```

---

## 11. Key Decisions

1. **utility function**: `cycle-card` (lines 862-894) stays in game.cljs facade — it doesn't fit cleanly into any concern and is low-churn.

2. **Multimethod registrations**: Stay with their executor modules (casting, priority_flow). The executor defmethod is only registered when the module loads.

3. **Backward compatibility**: All public functions re-exported from game.cljs; no downstream code changes needed.

4. **Event handler registration**: Each module registers its own `rf/reg-event-db` and `rf/reg-event-fx` calls. This is already the pattern for phases, cleanup, lands.

---

## 12. Testing Impact

### Current structure:
- Single test file might import from `fizzle.events.game`
- Tests dispatch events like `::cast-spell`, `::yield`, etc. (by keyword, not import)

### Post-extraction:
- Test files can require specific modules: `fizzle.events.casting-test`, `fizzle.events.resolution-test`, `fizzle.events.priority-flow-test`
- OR continue requiring the facade `fizzle.events.game` (still works)
- Event dispatch unchanged (keywords are stable)

---

## Summary Table

| Concern | Lines | Functions | Multimethods | Event Handlers | File Target |
|---------|-------|-----------|--------------|----------------|-------------|
| **Casting** | 49-296, 825-855 | 4 public + 3 private | 8 defmethods | ::cast-spell, ::select-casting-mode, ::cancel-mode-selection | `events/casting.cljs` |
| **Resolution** | 304-430 | 1 public + 3 private | 0 | ::resolve-top, ::resolve-all | `events/resolution.cljs` |
| **Priority** | 433-820 | 3 public + 6 private | 0 (but registers 1 continuation) | ::yield, ::yield-all, ::cast-and-yield, ::cast-and-yield-resolve | `events/priority_flow.cljs` |
| **Facade** | 1-47, 858-903 | 13 re-exports + 1 utility | 0 | 0 | `events/game.cljs` |

