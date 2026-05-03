# game.cljs Decomposition — Quick Start Guide

Follow this step-by-step to extract the three concerns from `events/game.cljs` per ADR-016.

---

## Phase 1: Extract Casting (~2 hours)

### 1a. Create `src/main/fizzle/events/casting.cljs`

Copy these sections from game.cljs:

| Lines | Content | Action |
|-------|---------|--------|
| 1-29 | Namespace and requires | Copy, update ns name |
| 56-68 | `defmulti evaluate-pre-cast-step` | Copy as-is |
| 71-75 | `def pre-cast-pipeline` | Copy as-is |
| 78-170 | All 8 `defmethod evaluate-pre-cast-step` | Copy as-is |
| 173-198 | `defn- initiate-cast-with-mode` | Copy as-is |
| 201-213 | `defn- get-valid-spell-modes` | Copy as-is |
| 216-232 | `defn- build-spell-mode-selection` | Copy as-is |
| 235-242 | `defmethod sel-core/execute-confirmed-selection :spell-mode` | Copy as-is |
| 245-253 | `defmethod sel-core/apply-continuation :cast-after-spell-mode` | Copy as-is |
| 256-295 | `defn cast-spell-handler` | Make public (remove `-` suffix in name) |
| 298-301 | `rf/reg-event-db ::cast-spell` | Copy, move to end of file |
| 825-836 | `defn select-casting-mode-handler` | Make public |
| 839-842 | `rf/reg-event-db ::select-casting-mode` | Copy to end |
| 845-849 | `defn cancel-mode-selection-handler` | Make public |
| 852-855 | `rf/reg-event-db ::cancel-mode-selection` | Copy to end |

**New casting.cljs structure:**
```clojure
(ns fizzle.events.casting
  "Spell casting pipeline: cost/targeting/mana selection, modal spells.
   Pure event handlers and selection builders."
  (:require [...]))

;; Multimethod definition
(defmulti evaluate-pre-cast-step ...)

;; Data
(def pre-cast-pipeline ...)

;; Pre-cast pipeline implementations (8 defmethods)
(defmethod evaluate-pre-cast-step :exile-cards-cost ...)
;; ... etc

;; Internal functions (private)
(defn- initiate-cast-with-mode ...)
(defn- get-valid-spell-modes ...)
(defn- build-spell-mode-selection ...)

;; Public handlers (no - prefix)
(defn cast-spell-handler ...)
(defn select-casting-mode-handler ...)
(defn cancel-mode-selection-handler ...)

;; Multimethod continuations
(defmethod sel-core/execute-confirmed-selection :spell-mode ...)
(defmethod sel-core/apply-continuation :cast-after-spell-mode ...)

;; Event registrations
(rf/reg-event-db ::cast-spell ...)
(rf/reg-event-db ::select-casting-mode ...)
(rf/reg-event-db ::cancel-mode-selection ...)
```

### 1b. Update game.cljs

1. Remove lines 49-296 (casting pipeline + multimethod defs)
2. Remove lines 825-855 (mode handlers + events)
3. Remove lines 235-253 (multimethod implementations)
4. Remove lines 298-301, 839-842, 852-855 (event handlers)
5. At top of file, add import:
   ```clojure
   [fizzle.events.casting :as casting]
   ```
6. Add re-exports after other re-exports (line ~46):
   ```clojure
   (def cast-spell-handler casting/cast-spell-handler)
   (def select-casting-mode-handler casting/select-casting-mode-handler)
   (def cancel-mode-selection-handler casting/cancel-mode-selection-handler)
   ```

### 1c. Verify

Run tests:
```bash
make test
```

Check that these still work:
- `fizzle.events.game/cast-spell-handler` (via facade)
- Event dispatch `[:fizzle.events.casting/::cast-spell {...}]`
- Event dispatch `[:fizzle.events.casting/::select-casting-mode mode]`

---

## Phase 2: Extract Resolution (~1.5 hours)

### 2a. Create `src/main/fizzle/events/resolution.cljs`

Copy these sections from updated game.cljs:

| Lines (approx, post-Phase1) | Content | Action |
|-------|---------|--------|
| 1-29 | Namespace and requires | Copy, update ns name |
| ~270-295 | `defn- get-source-id` | Copy as-is |
| ~298-313 | `defn- build-selection-from-result` | Copy as-is |
| ~316-323 | `defn- clear-peek-result` | Copy as-is |
| ~326-371 | `defn resolve-one-item` | Make public |
| ~374-383 | `rf/reg-event-db ::resolve-top` | Copy to end |
| ~386-403 | `rf/reg-event-fx ::resolve-all` | Copy to end |

**New resolution.cljs structure:**
```clojure
(ns fizzle.events.resolution
  "Stack resolution: resolve stack items, handle interactive effects.
   Used by priority flow after all players pass."
  (:require [...]))

;; Internal helpers (private)
(defn- get-source-id ...)
(defn- build-selection-from-result ...)
(defn- clear-peek-result ...)

;; Public entry point
(defn resolve-one-item ...)

;; Event handlers
(rf/reg-event-db ::resolve-top ...)
(rf/reg-event-fx ::resolve-all ...)
```

### 2b. Update game.cljs

1. Remove the 4 functions (get-source-id, build-selection-from-result, clear-peek-result, resolve-one-item)
2. Remove the 2 event registrations (::resolve-top, ::resolve-all)
3. Add import:
   ```clojure
   [fizzle.events.resolution :as resolution]
   ```
4. Add re-export:
   ```clojure
   (def resolve-one-item resolution/resolve-one-item)
   ```

### 2c. Update priority_flow.cljs imports (in Phase 3)

When you create priority_flow.cljs, import:
```clojure
[fizzle.events.resolution :as resolution]
```

And change calls from `resolve-one-item` to `resolution/resolve-one-item`.

### 2d. Verify

```bash
make test
```

Check that:
- `fizzle.events.game/resolve-one-item` still works (via facade)
- Event dispatch `[:fizzle.events.resolution/::resolve-top]` works

---

## Phase 3: Extract Priority Flow (~2.5 hours)

### 3a. Create `src/main/fizzle/events/priority_flow.cljs`

Copy these sections from updated game.cljs:

| Lines (approx, post-Phase 1+2) | Content | Action |
|-------|---------|--------|
| 1-29 | Namespace and requires | Copy, update ns name |
| ~270-305 | `defn advance-with-stops` | Make public |
| ~308-327 | `defn- yield-resolve-stack` | Copy as-is |
| ~330-335 | `defn- player-is-bot?` | Copy as-is |
| ~338-347 | `defn- bot-would-pass?` | Copy as-is |
| ~350-371 | `defn- bot-turn-advance-one-phase` | Copy as-is |
| ~374-459 | `defn- yield-advance-phase` | Copy as-is |
| ~462-487 | `defn negotiate-priority` | Make public |
| ~490-509 | `defn yield-impl` | Make public |
| ~512-514 | `def max-yield-steps` (private const) | Copy as-is |
| ~517-591 | `rf/reg-event-fx ::yield` | Copy to end, update call to `resolution/resolve-one-item` |
| ~594-603 | `rf/reg-event-fx ::yield-all` | Copy to end |
| ~606-647 | `defn- resolve-one-and-stop` | Copy as-is |
| ~650-652 | `defmethod sel-core/apply-continuation :resolve-one-and-stop` | Copy as-is |
| ~655-683 | `rf/reg-event-fx ::cast-and-yield` | Copy to end, update imports |
| ~686-689 | `rf/reg-event-db ::cast-and-yield-resolve` | Copy to end |

**New priority_flow.cljs structure:**
```clojure
(ns fizzle.events.priority-flow
  "Priority system: yielding, phase advancement, bot turn automation.
   Orchestrates casting, resolution, and phase progression."
  (:require
    [... as resolution]
    [... as casting]
    [... as phases]
    [... as cleanup]
    [...]))

;; Internal helpers (private)
(defn- yield-resolve-stack ...)
(defn- player-is-bot? ...)
(defn- bot-would-pass? ...)
(defn- bot-turn-advance-one-phase ...)
(defn- yield-advance-phase ...)
(defn- resolve-one-and-stop ...)
(def ^:private max-yield-steps ...)

;; Public entry points
(defn advance-with-stops ...)
(defn negotiate-priority ...)
(defn yield-impl ...)

;; Multimethod continuation
(defmethod sel-core/apply-continuation :resolve-one-and-stop ...)

;; Event handlers
(rf/reg-event-fx ::yield ...)
(rf/reg-event-fx ::yield-all ...)
(rf/reg-event-fx ::cast-and-yield ...)
(rf/reg-event-db ::cast-and-yield-resolve ...)
```

### 3b. Update priority_flow.cljs imports

Make sure to add:
```clojure
[fizzle.events.casting :as casting]
[fizzle.events.resolution :as resolution]
```

And update function calls:
- `resolve-one-item` → `resolution/resolve-one-item`
- `cast-spell-handler` → `casting/cast-spell-handler`

### 3c. Update game.cljs

1. Remove all priority flow functions and event handlers
2. Add import:
   ```clojure
   [fizzle.events.priority-flow :as priority-flow]
   ```
3. Add re-exports:
   ```clojure
   (def negotiate-priority priority-flow/negotiate-priority)
   (def advance-with-stops priority-flow/advance-with-stops)
   (def yield-impl priority-flow/yield-impl)
   ```

### 3d. Verify

```bash
make test
make lint
make fmt-check
```

Check that:
- `fizzle.events.game/negotiate-priority` still works (via facade)
- Event dispatch `[:fizzle.events.priority-flow/::yield]` works
- Event dispatch `[:fizzle.events.priority-flow/::yield-all]` works

---

## Phase 4: Finalize game.cljs Facade (~30 minutes)

### 4a. Clean up game.cljs

After all three extractions, game.cljs should be ~140 lines:
- Lines 1-30: ns declaration with all imports
- Lines 32-50: re-exports from init, phases, cleanup, lands, casting, resolution, priority-flow, ui
- Lines 52-140: `cycle-card` function + `rf/reg-event-db ::cycle-card` (utility)

**Final game.cljs template:**
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
    [fizzle.events.ui :as ui]
    [re-frame.core :as rf]))

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

;; =====================================================
;; Cycling — utility function (low-churn, stays here)
;; =====================================================
(defn cycle-card [...] ...)

(rf/reg-event-db
  ::cycle-card
  (fn [db [_ object-id player-id]] ...))
```

### 4b. Run full validation

```bash
make validate
```

Should pass: lint, format, and all tests.

---

## Checklist

- [ ] Phase 1: Create casting.cljs
- [ ] Phase 1: Update game.cljs, verify tests pass
- [ ] Phase 2: Create resolution.cljs
- [ ] Phase 2: Update game.cljs and priority_flow (to be created) imports, verify tests pass
- [ ] Phase 3: Create priority_flow.cljs with correct imports
- [ ] Phase 3: Update game.cljs, verify tests pass
- [ ] Phase 4: Clean up game.cljs facade
- [ ] Full validation: `make validate`
- [ ] Review: compare game.cljs line count (should be ~140)
- [ ] Commit: One commit per phase for clear history

---

## Commit Messages

```
Phase 1: Extract casting concern from events/game.cljs

- Create events/casting.cljs (~250 lines)
- Move ::cast-spell, ::select-casting-mode, ::cancel-mode-selection event handlers
- Move evaluate-pre-cast-step multimethod and all 8 defmethods
- Move helper functions: initiate-cast-with-mode, get-valid-spell-modes, build-spell-mode-selection
- game.cljs re-exports casting/cast-spell-handler for backward compatibility
- All tests pass; no downstream changes needed

Per ADR-016.
```

```
Phase 2: Extract resolution concern from events/game.cljs

- Create events/resolution.cljs (~130 lines)
- Move ::resolve-top and ::resolve-all event handlers
- Move resolve-one-item function and helpers
- game.cljs re-exports resolution/resolve-one-item
- All tests pass; no downstream changes needed

Per ADR-016.
```

```
Phase 3: Extract priority flow concern from events/game.cljs

- Create events/priority_flow.cljs (~420 lines)
- Move ::yield, ::yield-all, ::cast-and-yield, ::cast-and-yield-resolve event handlers
- Move core functions: negotiate-priority, advance-with-stops, yield-impl
- Move helpers and bot turn logic
- game.cljs re-exports priority flow functions
- All tests pass; no downstream changes needed

Per ADR-016.
```

```
Phase 4: Finalize game.cljs as decomposed facade

- game.cljs now ~140 lines (was 903)
- game.cljs re-exports from 8 sub-modules (init, phases, cleanup, lands, casting, resolution, priority-flow, ui)
- cycle-card utility remains in game.cljs
- No logic changes; pure re-export facade
- Verified: make validate (lint + format + tests)

Closes ADR-016 implementation.
```

---

## Testing Strategy

**Each phase should pass all tests:**
```bash
make test  # All 2124 tests
```

**Key verification points:**
1. Event dispatch by keyword still works (subscribers use event/event-handler, not imports)
2. Function calls via facade still work (game/cast-spell-handler, etc.)
3. No new compiler warnings or linter errors

---

## Common Pitfalls

1. **Forget the re-export**: If downstream code imports `fizzle.events.game/cast-spell-handler`, it must be re-exported in game.cljs facade.

2. **Circular imports**: Ensure priority_flow imports casting and resolution, but not vice versa.

3. **Multimethod registration order**: All modules must be required before calling their defmethods. Current pattern in `selection/core.cljs` handles this via require-side-effects. Verify this still works.

4. **Event handler names**: Event handlers are registered with `::keyword` (namespaced keyword), so after extraction they become `:fizzle.events.casting/::cast-spell` instead of `:fizzle.events.game/::cast-spell`. Update test event dispatch if needed.

