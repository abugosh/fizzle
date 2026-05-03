# Circular Dependency Investigation: casting.cljs ↔ priority-flow.cljs

## Executive Summary

**The Problem**: `events/casting.cljs` needs to call `resolve-one-and-stop` (a function in `events/priority-flow.cljs`). But `priority-flow.cljs` already requires `casting.cljs` to use `casting/cast-spell-handler`, creating a circular dependency.

**The Escape Hatch**: Casting.cljs works around this using `rf/dispatch` (re-frame runtime dispatch) at **line 236** instead of calling the function directly.

**The Better Solution**: Use the continuation pattern that's already defined in the codebase. Create a "ghost" `:finalized` selection with `:resolve-one-and-stop` continuation, then let the normal selection confirmation machinery fire the continuation. No dispatch needed, no circular require needed.

---

## Part 1: The Circular Dependency

### Dependency Direction

```
priority-flow.cljs (line 13)
    ↓ requires
casting.cljs
    ↓ tries to use resolve-one-and-stop from priority-flow
X cannot require priority-flow (circular)
```

### Why priority-flow Requires casting

In `priority-flow.cljs`, the `cast-and-yield-handler` (line 122) calls:
```clojure
(defn- cast-and-yield-handler
  [db]
  (let [pre-game-db (:game/db db)
        player-id (queries/get-human-player-id pre-game-db)
        after-cast (casting/cast-spell-handler db)]  ← REQUIRES casting
    ...))
```

### Why casting Needs priority-flow

In `casting.cljs`, the `:cast-after-spell-mode` continuation handler (line 213-239) detects when we're in a cast-and-yield operation:

```clojure
(defmethod sel-core/apply-continuation :cast-after-spell-mode
  [continuation app-db]
  (let [... 
        cast-and-yield? (= :cast-and-yield (:type (:history/deferred-entry result)))]
    (cond
      ;; Case 1: Targeted mode (has pending selection) — easy
      (and cast-and-yield? (:game/pending-selection result) ...)
      (assoc-in result ... :selection/on-complete {:continuation/type :resolve-one-and-stop})

      ;; Case 2: Non-targeted mode (no pending selection) — needs immediate resolve
      (and cast-and-yield? (nil? (:game/pending-selection result)))
      (do (rf/dispatch [:fizzle.events.priority-flow/cast-and-yield-resolve])  ← WORKAROUND
          result)
      
      :else result)))
```

---

## Part 2: The Escape Hatch

### Location and Code

**File**: `/Users/abugosh/g/fizzle/src/main/fizzle/events/casting.cljs`  
**Lines**: 232-237

```clojure
;; Non-targeted mode in cast-and-yield: spell cast directly,
;; dispatch auto-resolve (can't call priority-flow directly — circular dep).
(and cast-and-yield?
     (nil? (:game/pending-selection result)))
(do (rf/dispatch [:fizzle.events.priority-flow/cast-and-yield-resolve])
    result)
```

### Why It's an Escape Hatch

1. It uses `rf/dispatch` (re-frame runtime event dispatch) instead of a direct function call
2. This sidesteps the require graph — the dispatcher runs asynchronously at runtime
3. The target event is registered in priority-flow.cljs (line 183-186):

```clojure
(rf/reg-event-db
  ::cast-and-yield-resolve
  (fn [db _]
    (cast-and-yield-resolve-handler db)))
```

Which calls `resolve-one-and-stop`:

```clojure
(defn- cast-and-yield-resolve-handler
  [db]
  (resolve-one-and-stop db))
```

---

## Part 3: What resolve-one-and-stop Does

### Definition

**File**: `/Users/abugosh/g/fizzle/src/main/fizzle/events/priority_flow.cljs`  
**Lines**: 100-109

```clojure
(defn- resolve-one-and-stop
  "Resolve the top stack item, letting the director handle priority and resolution.
   Returns updated app-db. Used by cast-and-yield and the :resolve-one-and-stop
   continuation. Passes human-yielded? true so the human auto-passes once
   (the human chose Cast & Yield, meaning they want the spell to resolve)."
  [app-db]
  (if (or (:game/pending-selection app-db)
          (queries/stack-empty? (:game/db app-db)))
    app-db
    (:app-db (director/run-to-decision app-db {:yield-all? false :human-yielded? true}))))
```

### What It Does

1. Checks if a selection is pending — if so, returns app-db unchanged (can't resolve while waiting for input)
2. Checks if stack is empty — if so, returns app-db unchanged (nothing to resolve)
3. Otherwise: calls `director/run-to-decision` with `human-yielded? true`
   - This means the human already yielded (they clicked Cast & Yield)
   - So the director auto-passes the human once at the current stop
   - Then resolves the top stack item
   - Then stops

### Where It's Used

Three places:
1. **Line 118**: In the `:resolve-one-and-stop` continuation defmethod (called by selection machinery)
2. **Line 161**: In `cast-and-yield-handler` when casting succeeded without selection
3. **Line 180**: In `cast-and-yield-resolve-handler` (called via the dispatch escape hatch)

---

## Part 4: The Continuation Pattern (The Correct Solution)

### How Continuations Work

The continuation system is defined in `/Users/abugosh/g/fizzle/src/main/fizzle/events/selection/core.cljs`:

1. **Selection stores a continuation** (optional):
   ```clojure
   :selection/on-complete {:continuation/type :resolve-one-and-stop}
   ```

2. **Selection confirmation calls it** (lines 362-378):
   ```clojure
   (if on-complete
     (apply-continuation on-complete updated)
     updated)
   ```

3. **apply-continuation is a multimethod** that domain modules register on:
   ```clojure
   (defmulti apply-continuation
     (fn [continuation _app-db] (:continuation/type continuation)))
   ```

4. **priority-flow.cljs registers the :resolve-one-and-stop handler** (lines 115-119):
   ```clojure
   (defmethod sel-core/apply-continuation :resolve-one-and-stop
     [_ app-db]
     (-> app-db
         resolve-one-and-stop
         sel-core/process-deferred-entry))
   ```

### How casting.cljs Already Uses This Pattern

At **lines 229-230**, when the spell has targeting (pending-selection exists):

```clojure
(and cast-and-yield?
     (:game/pending-selection result)
     (not (:selection/on-complete (:game/pending-selection result))))
(assoc-in result [:game/pending-selection :selection/on-complete]
          {:continuation/type :resolve-one-and-stop})
```

This works perfectly — it's just data, no require needed.

---

## Part 5: Why Case 2 (Non-Targeted) Breaks the Pattern

When the spell has NO targeting (line 234-237):

```clojure
(and cast-and-yield? (nil? (:game/pending-selection result)))
(do (rf/dispatch [:fizzle.events.priority-flow/cast-and-yield-resolve])
    result)
```

The continuation pattern can't be used here because there's no selection to attach the continuation to. The code needs to:

1. Run `resolve-one-and-stop` immediately after casting
2. But that's a function in priority-flow, which can't be required

The escape hatch dispatches an event instead of calling the function.

---

## Part 6: The Better Solution (Using Continuations)

### The Idea: Fake Selection + Continuation

Instead of dispatching, create a `:finalized` selection with no UI and the `:resolve-one-and-stop` continuation:

```clojure
;; Non-targeted mode in cast-and-yield: spell cast directly,
;; but use continuation pattern to trigger resolve-one-and-stop.
(and cast-and-yield? (nil? (:game/pending-selection result)))
(-> result
    (assoc :game/pending-selection
           {:selection/type :finalized
            :selection/lifecycle :finalized
            :selection/selected #{}
            :selection/player-id player-id
            :selection/object-id object-id
            :selection/on-complete {:continuation/type :resolve-one-and-stop}})
    (assoc :history/deferred-entry
           (assoc (:history/deferred-entry result)
                  :type :cast-and-yield)))
```

### How This Works

1. A `:finalized` selection is created (no UI shown, no executor logic needed)
2. It has `:selection/on-complete` with the `:resolve-one-and-stop` continuation
3. The selection machinery (`confirm-selection-impl`) immediately confirms it
4. Since it's `:finalized`, it skips remaining-effects and source cleanup (line 367-378 in selection/core.cljs)
5. Then it calls `apply-continuation` with the `:resolve-one-and-stop` continuation
6. The continuation (line 115-119 in priority-flow.cljs) runs `resolve-one-and-stop`

### Why This Is Better

- ✓ Uses the documented continuation pattern (no surprises)
- ✓ No dispatch escape hatch (all pure functions)
- ✓ No circular require (continuation is just data)
- ✓ Consistent with the targeted case (line 229-230)
- ✓ All logic stays within the selection/continuation machinery
- ✓ Removes technical debt (dispatch)

---

## Part 7: Full Dependency Graph

### Cast-and-Yield Orchestration Flow

```
1. User clicks "Cast & Yield" button
   → dispatches ::cast-and-yield event (priority-flow.cljs)

2. cast-and-yield-handler (priority-flow.cljs, line 122)
   → calls casting/cast-spell-handler (line 126)
   → gets after-cast result

3. Three paths in after-cast:
   
   a) Pre-cast selection needed (mana allocation, targeting, etc.)
      → Set selection/on-complete to :resolve-one-and-stop continuation
      → Return with pending-selection
      → User interacts with selection
      → Selection confirms, continuation fires, resolve-one-and-stop runs
   
   b) Modal selection needed (multiple spell modes)
      → Return with pending-mode-selection
      → User selects mode
      → Triggers apply-continuation :cast-after-spell-mode (casting.cljs, line 213)
      
   c) No selection needed (simple spell)
      → [CURRENT] rf/dispatch escape hatch
      → [PROPOSED] Use continuation pattern via fake :finalized selection
```

### Require Graph

```
core.cljs
├─ casting.cljs
│  ├─ db.queries
│  ├─ engine.rules
│  ├─ engine.static-abilities
│  ├─ engine.targeting
│  ├─ events.selection.core (multimethod registry)
│  ├─ events.selection.costs
│  ├─ events.selection.targeting
│  ├─ history.descriptions
│  └─ re-frame.core
│
├─ priority-flow.cljs
│  ├─ datascript.core
│  ├─ db.queries
│  ├─ events.casting (CIRCULAR ORIGIN: line 13)
│  ├─ events.director
│  ├─ events.selection.core (multimethod registry)
│  ├─ history.descriptions
│  └─ re-frame.core
│
├─ director.cljs
│  ├─ engine/* modules
│  ├─ events.casting (line 31)
│  ├─ events.cleanup
│  ├─ events.lands
│  ├─ events.phases
│  ├─ events.resolution
│  └─ ... (pure function, no event handlers)
│
└─ selection/core.cljs
   ├─ defines execute-confirmed-selection multimethod
   ├─ defines apply-continuation multimethod
   ├─ NO casting.cljs, NO priority-flow.cljs
   └─ ... (composition point for all selection types)
```

### Which Functions Require Which

**casting.cljs exports**:
- Public: `build-spell-mode-selection`, `cast-spell-handler`, `select-casting-mode-handler`, `cancel-mode-selection-handler`
- Methods: `:spell-mode` executor, `:cast-after-spell-mode` continuation handler

**priority-flow.cljs exports**:
- Public: `check-stop`, `set-player-stops`, `check-opponent-stop`, `set-opponent-stops`
- Private: `resolve-one-and-stop` (line 100) — used by:
  - `:resolve-one-and-stop` continuation defmethod (line 115)
  - `cast-and-yield-handler` (line 161)
  - `cast-and-yield-resolve-handler` (line 180)
- Methods: `:resolve-one-and-stop` continuation handler
- Events: `::yield`, `::yield-all`, `::cast-and-yield`, `::cast-and-yield-resolve`

**director.cljs exports**:
- Public: `run-to-decision`, `human-should-auto-pass`, `get-player-stops`, `get-player-opponent-stops`, `bot-act`

---

## Part 8: Files That Require These Modules

### Who Requires casting.cljs

```
src/main/fizzle/core.cljs (line 7)
src/main/fizzle/events/director.cljs (line 31)
src/main/fizzle/events/priority_flow.cljs (line 13) ← circular origin
src/main/fizzle/views/selection/custom.cljs
src/main/fizzle/views/controls.cljs

+ 8 test files (not in dep graph)
```

### Who Requires priority-flow.cljs

```
src/main/fizzle/core.cljs (line 15)
src/main/fizzle/events/ui.cljs
src/main/fizzle/views/controls.cljs

+ 5 test files (not in dep graph)
```

### Who Requires director.cljs

```
src/main/fizzle/events/priority_flow.cljs (line 14)

+ 3 test files (not in dep graph)
```

---

## Part 9: Test Coverage of the Escape Hatch

The escape hatch is tested implicitly through:
- `cast-and-yield` integration tests (test the full flow)
- Modal spell casting tests (test the path that uses apply-continuation)

But there's no explicit test of the dispatch call itself. If you refactor, tests should catch the behavioral change.

---

## Summary: Why This Pattern Exists

| Aspect | Why |
|--------|-----|
| **Priority-flow requires casting** | `cast-and-yield-handler` needs `cast-spell-handler` |
| **Casting can't require priority-flow** | Would create a cycle |
| **dispatch escape hatch exists** | Breaks the cycle at runtime |
| **Better solution: continuation pattern** | Use fake selection + continuation (no dispatch, no require) |
| **Why not fixed yet** | The current workaround works; refactor is cleanup, not bug |

---

## Recommended Refactor

Replace lines 234-237 in `/Users/abugosh/g/fizzle/src/main/fizzle/events/casting.cljs`:

**Before**:
```clojure
;; Non-targeted mode in cast-and-yield: spell cast directly,
;; dispatch auto-resolve (can't call priority-flow directly — circular dep).
(and cast-and-yield?
     (nil? (:game/pending-selection result)))
(do (rf/dispatch [:fizzle.events.priority-flow/cast-and-yield-resolve])
    result)
```

**After**:
```clojure
;; Non-targeted mode in cast-and-yield: no selection needed,
;; but resolve-one-and-stop via continuation system.
(and cast-and-yield?
     (nil? (:game/pending-selection result)))
(-> result
    (assoc :game/pending-selection
           {:selection/type :finalized
            :selection/lifecycle :finalized
            :selection/selected #{}
            :selection/player-id player-id
            :selection/object-id object-id
            :selection/on-complete {:continuation/type :resolve-one-and-stop}})
    (assoc :history/deferred-entry
           (assoc (:history/deferred-entry result)
                  :type :cast-and-yield)))
```

This uses the documented continuation pattern, eliminates the dispatch, and requires no changes to other files.
