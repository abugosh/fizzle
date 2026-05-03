# Circular Dependency: Quick Reference

## The One-Liner Problem

Casting.cljs line 236 uses `rf/dispatch` to sidestep circular dep with priority-flow.cljs (which already requires casting).

## The Escape Hatch Location

**File**: `src/main/fizzle/events/casting.cljs`  
**Lines**: 232-237

```clojure
;; Non-targeted mode in cast-and-yield: spell cast directly,
;; dispatch auto-resolve (can't call priority-flow directly — circular dep).
(and cast-and-yield?
     (nil? (:game/pending-selection result)))
(do (rf/dispatch [:fizzle.events.priority-flow/cast-and-yield-resolve])
    result)
```

## Why It Exists

```
priority-flow.cljs (line 13)
    ↓ requires
casting.cljs
    ↓ needs resolve-one-and-stop from priority-flow.cljs (line 100)
    ↗ but can't require priority-flow (circular)
    ↓ uses rf/dispatch instead
```

## What resolve-one-and-stop Does

Defined in priority-flow.cljs (lines 100-109):
```clojure
(defn- resolve-one-and-stop
  [app-db]
  (if (or (:game/pending-selection app-db)
          (queries/stack-empty? (:game/db app-db)))
    app-db
    (:app-db (director/run-to-decision app-db {:yield-all? false :human-yielded? true}))))
```

Resolves the top stack item once, then stops (letting human take another action).

## The Correct Solution: Use Continuation Pattern

**Same file, lines 229-230** already do this correctly for the targeted case:

```clojure
(and cast-and-yield?
     (:game/pending-selection result)
     (not (:selection/on-complete (:game/pending-selection result))))
(assoc-in result [:game/pending-selection :selection/on-complete]
          {:continuation/type :resolve-one-and-stop})
```

Attach `:resolve-one-and-stop` as a continuation to the pending selection. When selection confirms, the continuation fires (no dispatch needed).

## The Fix: Create a Ghost Selection

Instead of dispatching (line 236), create a `:finalized` selection (no UI) with the `:resolve-one-and-stop` continuation:

```clojure
;; Replace lines 234-237 with:
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
           (assoc (:history/deferred-entry result) :type :cast-and-yield)))
```

## Why This Works

1. Create a `:finalized` selection (no UI, auto-confirmed)
2. Attach `:resolve-one-and-stop` continuation
3. Selection machinery (`confirm-selection-impl`) confirms it immediately
4. Since it's `:finalized`, skips normal cleanup logic
5. Then fires the continuation, which calls `resolve-one-and-stop`
6. No dispatch, no circular require, pure functions

## Continuation System Overview

Three multimethods in `events/selection/core.cljs`:
- `execute-confirmed-selection` — run user's choice
- `apply-continuation` — run after selection clears
- `build-chain-selection` — for chained selections

Priority-flow registers on `apply-continuation`:
```clojure
(defmethod sel-core/apply-continuation :resolve-one-and-stop
  [_ app-db]
  (-> app-db resolve-one-and-stop sel-core/process-deferred-entry))
```

## Full Dependency Chain

```
priority-flow.cljs (line 13: requires casting)
├─ uses: casting/cast-spell-handler
└─ defines: resolve-one-and-stop, :resolve-one-and-stop continuation

casting.cljs
├─ needs: resolve-one-and-stop (from priority-flow)
├─ solution 1 (current): rf/dispatch escape hatch
├─ solution 2 (better): continuation pattern (no require needed)
└─ cannot: require priority-flow (would be circular)
```

## Test Files to Check

The circular dependency may not be caught by static analysis if:
- Only priority-flow.cljs is compiled with its requires
- Casting.cljs is never compiled with priority-flow.cljs in the same require

Core.cljs requires both (line 7 and 15), so the cycle is broken at load time, but it's still awkward.

## Changes Needed (Just 1 File)

| File | Lines | Change |
|------|-------|--------|
| `src/main/fizzle/events/casting.cljs` | 234-237 | Replace `rf/dispatch` with continuation pattern |

No changes to priority-flow, director, selection/core, or any test files.
