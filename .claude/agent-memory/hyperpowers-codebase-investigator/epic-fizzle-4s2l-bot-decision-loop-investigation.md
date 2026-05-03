---
name: Epic fizzle-4s2l Bot Decision Loop Investigation
description: Complete trace of bot decision loop, race condition timing, action limit, multi-color mana handling
type: reference
---

# Epic fizzle-4s2l: Bot Decision Loop Deep Investigation

**Date**: 2026-04-02
**Status**: Architecture replaced with director-based approach (ADR-021)
**Key Finding**: Old async dispatch-based system replaced with pure synchronous director loop

---

## 1. Bot Decision Loop Architecture (Current: Director-Based)

### Entry Point: `events/director.cljs:run-to-decision`

The director is a **pure synchronous loop** that runs until human needs a decision.

**Location**: `/Users/abugosh/g/fizzle/src/main/fizzle/events/director.cljs` (359 lines)

**Core Loop Structure** (lines 317-358):
```clojure
(loop [app-db app-db
       yield-all? yield-all-init?
       yield-through-stack? yield-through-stack-init?
       human-yielded? human-yielded-init?
       steps 0]
  (cond
    (>= steps max-director-steps) → {:reason :safety-limit}
    (:game/pending-selection app-db) → {:reason :pending-selection}
    :else → (let [holder-pid (current-holder-player-id game-db)]
              (cond
                (bot-protocol/get-bot-archetype game-db holder-pid)
                → (step-bot-action ...)
                (= holder-pid human-pid)
                → (step-human-action ...)
                :else
                → {:reason :await-human}))))
```

**Safety Limit**: `max-director-steps = 300` (line 38)
- Hard limit prevents infinite loops
- Returns `:reason :safety-limit` when exceeded
- Only counts actual loop iterations (not async queues)
- Much more lenient than old 20-action-count (explained below)

---

## 2. Bot Action Execution: `step-bot-action` Flow

**Location**: `/Users/abugosh/g/fizzle/src/main/fizzle/events/director.cljs:176-214`

### `bot-act` Function (lines 90-135)

Pure function that runs one bot action cycle:

```clojure
(defn bot-act [game-db player-id]
  (let [archetype (bot-protocol/get-bot-archetype game-db player-id)]
    (if-not archetype
      {:action-type :pass :game-db game-db}
      (let [current-phase (:game/phase (queries/get-game-state game-db))
            phase-action (bot-protocol/bot-phase-action archetype current-phase game-db player-id)
            land-id (when (= :play-land (:action phase-action))
                      (find-bot-land-to-play game-db player-id))]
        (if land-id
          ;; Play land
          {:action-type :play-land
           :game-db (sba/check-and-execute-sbas (lands/play-land game-db player-id land-id))
           :object-id land-id}
          ;; Try to cast spell
          (let [action (bot-decisions/bot-decide-action game-db)]
            (if (not= :cast-spell (:action action))
              {:action-type :pass :game-db game-db}
              ;; Cast spell with mana payment
              (let [tap-seq (:tap-sequence action)
                    db-tapped (reduce (fn [d {:keys [object-id mana-color]}]
                                        (mana-activation/activate-mana-ability
                                          d player-id object-id mana-color))
                                      game-db tap-seq)
                    cast-result (casting/cast-spell-handler
                                  {:game/db db-tapped}
                                  {:player-id player-id
                                   :object-id (:object-id action)
                                   :target (:target action)})]
                ;; Handle result
                (cond
                  (:game/pending-selection cast-result) → return selection
                  (identical? (:game/db cast-result) db-tapped) → return pass (cast failed)
                  :else → return spell cast success)))))))))
```

**Key Points**:
1. **Phase action first** (main1): Check if bot should play land
2. **Land playing** delegates to `find-bot-land-to-play` (lines 82-87)
3. **Spell casting** delegates to `bot-decisions/bot-decide-action`
4. **Mana payment** happens via `mana-activation/activate-mana-ability` (inline loop, lines 111-114)
5. **Spell resolution** via `casting/cast-spell-handler`
6. **SBAs run** after land play and after cast success (lines 105, 135)

---

## 3. Mana Allocation: `bots/decisions.cljs:find-tap-sequence`

**Location**: `/Users/abugosh/g/fizzle/src/main/fizzle/bots/decisions.cljs:39-93` (55 lines)

This is the **multi-color mana handling function**. It allocates lands to pay a mana cost.

### Function Signature
```clojure
(defn find-tap-sequence [game-db player-id mana-cost]
  ;; mana-cost: {:red 1 :green 1 :generic 2} etc.
  ;; Returns: [{:object-id uuid :mana-color :red} ...]
```

### Algorithm

**Phase 1: Setup** (lines 47-56)
- Get all objects on player's battlefield
- Create volatile set to track already-allocated lands
- Define filter function `find-lands` that finds untapped, unallocated lands matching predicate

**Phase 2: Colored Mana** (lines 58-77)
```clojure
colored-entries (filter (fn [[color _]] (color-keys color)) mana-cost)
;; Iterate over [:red 1] [:green 1] etc.
colored-taps (reduce
  (fn [taps [color amount]]
    ;; For each color, find untapped lands producing that color
    (let [lands (find-lands
                  (fn [obj]
                    (some (fn [ability]
                            (and (= :mana (:ability/type ability))
                                 (get (:ability/produces ability) color)))
                          (get-in obj [:object/card :card/abilities])))
                  amount)]
      ;; Mark lands as allocated
      (doseq [obj lands] (vswap! allocated-ids conj (:object/id obj)))
      ;; Add to tap sequence with color
      (into taps (map (fn [obj]
                        {:object-id (:object/id obj) :mana-color color})
                      lands))))
  []
  colored-entries)
```

**Issue found**: Uses `volatile!` mutable state to track allocated lands. This is:
- ✓ Correct for tracking across loop iterations
- ✓ Safe (not shared with other functions)
- ✓ Necessary for preventing double-allocation of dual lands

**Phase 3: Generic Mana** (lines 79-93)
```clojure
(if (pos? generic-amount)
  (let [generic-lands (find-lands
                        (fn [obj]
                          (some (fn [ability]
                                  (= :mana (:ability/type ability)))
                                (get-in obj [:object/card :card/abilities])))
                        generic-amount)]
    (into colored-taps (map (fn [obj]
                              (let [first-color (some (fn [ability]
                                                        (when (= :mana (:ability/type ability))
                                                          (first (keys (:ability/produces ability)))))
                                                      (get-in obj [:object/card :card/abilities]))]
                                {:object-id (:object/id obj)
                                 :mana-color first-color})) generic-lands)))
  colored-taps)
```

**Issue found**: Generic mana selection picks `first-color` from ability (line 87-90). This:
- ✓ Works for basic lands (single color)
- ✓ Works for dual lands (picks first color alphabetically, or order in card definition)
- ✗ Doesn't check if chosen color was already used for colored costs

**Example**: Cost `{:white 1 :generic 2}`
- Card has Plains + Dual (white/blue)
- Phase 2: Allocate Plains for white requirement
- Phase 3: For generic, could pick Dual's white → wastes colored mana
- **Better**: Phase 3 should prefer already-exhausted colors

---

## 4. Bot Priority Decision: `bots/decisions.cljs:bot-decide-action`

**Location**: `/Users/abugosh/g/fizzle/src/main/fizzle/bots/decisions.cljs:96-132` (37 lines)

### Decision Flow

```clojure
(defn bot-decide-action [game-db]
  (let [holder-eid (priority/get-priority-holder-eid game-db)
        player-id (some (fn [pid] ...) [human-player-id opponent-player-id])
        archetype (when player-id (bot/get-bot-archetype game-db player-id))]
    (if-not archetype
      {:action :pass}
      (let [decision (bot/bot-priority-decision archetype {:db game-db :player-id player-id})]
        (if (= :pass decision)
          {:action :pass}
          (let [object-id (:object-id decision)
                target (:target decision)
                card (queries/get-card game-db object-id)
                mana-cost (or (:card/mana-cost card) {})
                tap-seq (find-tap-sequence game-db player-id mana-cost)
                can-pay? (every? (fn [[color amount]] ...) mana-cost)]
            (if-not can-pay?
              {:action :pass}
              {:action :cast-spell
               :object-id object-id
               :target target
               :player-id player-id
               :tap-sequence tap-seq})))))))
```

### Mana Payment Check (lines 119-125)

```clojure
can-pay? (every? (fn [[color amount]]
           (if (= :generic color)
             (let [colored-need (reduce + 0 (vals (dissoc mana-cost :generic)))]
               (>= (- (count tap-seq) colored-need) amount))
             (<= amount (count (filter #(= color (:mana-color %)) tap-seq)))))
         mana-cost)
```

**Analysis**:
- For colored mana: Count taps matching color ≥ required amount ✓
- For generic mana: Count taps minus colored taps ≥ generic amount ✓
- **This is correct** (fixes the old issue in BOT_SYSTEM_INVESTIGATION.md)

**Issue**: Doesn't validate tap-seq is actually valid for generic mana
- tap-seq includes already-tapped colored lands
- Generic check only counts (total - colored-need)
- ✓ Actually correct logic, but tight coupling

---

## 5. Race Condition Analysis

### Old Architecture Risk (Async Dispatch-Based)

From `BOT_SYSTEM_INVESTIGATION.md`: The old interceptor used `rf/dispatch` (async) to queue `::bot-decide`.

**Potential timing issue**:
1. Event: `::cast-spell` (bot casts bolt)
2. Handler updates game-db, dispatches `::bot-decide`
3. Before `::bot-decide` fires:
   - Another event (`::draw-card`) updates game-db
   - Dispatches another `::bot-decide`
4. First `::bot-decide` fires: bot at stale state
5. Second `::bot-decide` fires: bot at different state

### Current Architecture (Synchronous Director)

**Location**: `/Users/abugosh/g/fizzle/src/main/fizzle/events/director.cljs`

**Race condition eliminated**:
- Director is pure synchronous function
- No `dispatch` or `dispatch-later` in director loop
- All game mutations (lands/casting/mana) applied inline
- bot-act runs on **current** game-db, not cached state

**Entry points** (where director is invoked):
- Via re-frame event handler (exact location TBD — check events/priority_flow.cljs)
- Takes app-db, returns `{:app-db :reason}`

**No async gaps**: Bot acts, game-db mutates, loop immediately checks new state.

---

## 6. Action Limit Analysis

### Old Limit (Removed)

**File**: `bots/interceptor.cljs` (now renamed `bots/decisions.cljs`)
**Line**: 211 (old code)
```clojure
(>= bot-action-count 20)  ;; Forced pass after 20 actions
```

**Problems**:
- Arbitrary 20 actions per turn
- Burn bot could cast only 5 bolts before hitting limit
- Not reset at turn start (only reset when bot passed)

### Current Limit (Director Safety)

**File**: `/Users/abugosh/g/fizzle/src/main/fizzle/events/director.cljs`
**Line**: 38, 323
```clojure
(def ^:private max-director-steps 300)
(if (>= steps max-director-steps) {:app-db app-db :reason :safety-limit})
```

**Improvements**:
- ✓ Much higher limit (300 steps vs 20 actions)
- ✓ Counts loop iterations, not action count (more accurate)
- ✓ Resets per `run-to-decision` call
- ✓ Hard safety valve against infinite loops (pure synchronous can still have bugs)

**What is a "step"**:
- One iteration of the director loop
- One bot action (play land or cast) = ~2-3 steps
- One human action = 1-2 steps
- One resolution = 1 step
- One phase advance = 1-2 steps

**300 steps is equivalent to**:
- ~100 bot actions (generous for burn bot with 40 bolts)
- ~50 turns (150 phases per turn × 2 steps per phase)

**Correct**: Director limit is now appropriate for the synchronous architecture.

---

## 7. Multi-Color Mana Handling

### Current Implementation

**File**: `/Users/abugosh/g/fizzle/src/main/fizzle/bots/decisions.cljs:39-93`

### Allocation Algorithm Assessment

**Colored costs** (Phase 2):
- ✓ Correctly finds lands producing each color
- ✓ Respects multi-color constraints (each land tapped once)
- ✓ Allocates all colored mana first

**Generic costs** (Phase 3):
- ✓ Finds untapped, unallocated lands
- ✗ Picks `first-color` instead of least-valuable color
- ✗ Doesn't check color preference

**Example that might fail**:
- Cost: `{:white 1 :generic 2}`
- Hand: Dual land (produces white/blue), Plains
- Correct: Tap Plains for white, tap Dual for generic blue
- Actual: Taps Plains for white, picks `first-color` of Dual (could be white again, wasting)

**Burn bot**: Single-color (lightning bolt = `{:red 1}`), so no issue.

### Mana Activation (Inline Tapping)

**File**: `/Users/abugosh/g/fizzle/src/main/fizzle/engine/mana_activation.cljs`

**Location**: `director.cljs:111-114`
```clojure
(reduce (fn [d {:keys [object-id mana-color]}]
          (mana-activation/activate-mana-ability
            d player-id object-id mana-color))
        game-db tap-seq)
```

**Flow**:
1. Director passes `:mana-color` (keyword like `:red`)
2. `activate-mana-ability` finds mana ability producing that color
3. Handles `:ability/produces` (direct) and `:add-mana` effects
4. Resolves `:any` mana to chosen color (lines 100-102, 115-119)
5. Fires matching triggers

**Assessment**:
- ✓ Handles multi-color lands with `:any` produces
- ✓ Resolves symbolic targets
- ✓ Runs SBAs after each tap
- ✓ Supports land-type-override grants (Vision Charm pattern)

---

## 8. Interaction with Priority System

### Phase Stops & Passes

**File**: `/Users/abugosh/g/fizzle/src/main/fizzle/engine/priority.cljs`

**Director uses**:
- `get-priority-holder-eid` (line 142)
- `yield-priority` (line 192, 200)
- `transfer-priority` (line 204)
- `both-passed?` (line 201)
- `reset-passes` (line 210)
- `set-priority-holder` (line 211)

**Player stops**:
- `get-player-stops` (line 64)
- `get-player-opponent-stops` (line 72)

### Decision Points

**Stack non-empty**:
1. Determine priority holder
2. If bot: bot-act → result (play/cast/pass)
3. If human: auto-pass? based on stops
4. Both pass → reset → resolve one item
5. Repeat

**Stack empty**:
1. Both pass → reset
2. Advance phase
3. New phase: repeat from holder check

---

## Summary: Epic fizzle-4s2l Problem Areas

| Problem | Old Status | Current Status | Location |
|---------|-----------|-----------------|----------|
| **Race Condition** | Async dispatch queuing | Eliminated (pure sync director) | director.cljs:317-358 |
| **Action Limit** | 20 actions/turn | 300 steps/decision (much better) | director.cljs:38,323 |
| **Multi-Color Mana** | Generic cost picks `first-color` | Still picks `first-color` (works for burn) | decisions.cljs:87-90 |
| **Dead Code** | `bot-choose-attackers` not called | Still not called | protocol.cljs:48-56 |
| **Turn Cycle** | No declare-attackers phase | No declare-attackers phase | (combat not implemented) |

---

## Recommendations for Epic fizzle-4s2l

1. **Race condition**: ✓ RESOLVED via director architecture (ADR-021)
2. **Action limit**: ✓ RESOLVED via higher safety limit (300 vs 20)
3. **Multi-color mana**: MINOR ISSUE — can fix by prefer least-valuable color in generic allocation
4. **Dead code**: Consider removing `bot-choose-attackers` or implement combat phase (separate epic)
