# Bot System Data Flows Investigation

**Investigation Date:** 2026-04-01  
**Scope:** Module-level data flows through bot subsystem (`src/main/fizzle/bots/`) and dependency neighborhood  
**Architecture Reference:** Declared dependencies in fizzle-design.md:
- `bots -> events_game`: "dispatches same casting, land-play, and priority events"
- `bots -> engine`: "queries game state for decision making"

---

## Executive Summary

The bot system has **TWO ACTIVE DECISION PATHS that operate in parallel**:

1. **Director path (CURRENT)**: `director/run-to-decision` → `bot-act` → inline engine calls (pure, synchronous)
2. **Interceptor path (LEGACY, STALE)**: `bots/interceptor.cljs` → re-frame events (async, dispatch-based)

Only the **director path is actually used**. The interceptor module still exists but is **unreachable code** — it defines `::bot-decide` and `::bot-action-complete` handlers that are never dispatched. The db_effect chokepoint was deliberately gutted to remove bot dispatches.

The architecture model's claim that "bots dispatch the same events as human players" is **partially true but misleading**: bots DO dispatch casting/land-play events, but only through the director's pure function calls, not through re-frame's event system. The director performs mana activation and spell casting by directly calling `events/casting` and `events/lands` module functions, bypassing re-frame's event dispatch entirely.

---

## 1. BOT TURN FLOW: Entry Point to Action Decision

### Entry: `director/run-to-decision` (line 290, events/director.cljs)

**Data Flow Diagram:**
```
Human/App code calls
  ↓
director/run-to-decision(app-db, opts)
  ↓
main loop:
  - Step 1: Check game state (nil? game-db, loss-condition?, pending-selection?)
  - Step 2: Determine priority holder (holder-pid)
  - Step 3: Dispatch to step-* handler based on holder:
      * If bot holds priority: step-bot-action
      * If human holds priority: step-human-action
      * Else: await-human
```

**Pure function, no re-frame dispatch.** Returns `{:app-db app-db :reason}` where reason is one of:
- `:await-human` — human decision point
- `:pending-selection` — selection needed
- `:game-over` — loss condition met
- `:safety-limit` — loop limit (300 steps)

### Bot Decision Point: `step-bot-action` (line 176, events/director.cljs)

When the loop detects a bot holds priority, it calls `step-bot-action`:

```clojure
(defn- step-bot-action
  [app-db game-db holder-pid yield-all? yield-through-stack?]
  (let [action (bot-act game-db holder-pid)]
    (cond
      (= atype :play-land) → continue loop, yield-all? flags preserved
      (and (= atype :cast-spell) (:pending-selection action)) → STOP, return pending-selection
      (= atype :cast-spell) → continue loop, yield-all? flags preserved
      :else (:pass) → priority yields, continue loop
```

**Critical insight:** Bot actions do NOT return to the director immediately. Each action cycles back to the loop, where `step-bot-action` is called again until:
- Bot passes priority (leading to phase advance or human turn)
- Bot cast creates a selection (pause for interactive effect)
- Loop limit hit

### `bot-act` Function: The Core Bot Decision Engine (line 90, events/director.cljs)

```
bot-act(game-db, player-id)
  ↓
1. Check phase action first:
   - Get current phase from game-state
   - Call bot-protocol/bot-phase-action(archetype, phase, ...)
   - If :play-land action AND land available:
       * Call lands/play-land(game-db, player-id, land-id)
       * SBA check
       * Return {:action-type :play-land, :game-db db'}
  ↓
2. If phase action didn't trigger, check priority decision:
   - Call bot-interceptor/bot-decide-action(game-db)
   - If :pass: return {:action-type :pass, :game-db game-db}
   - If :cast-spell:
       * Execute mana activation: tap lands via mana-activation/activate-mana-ability
       * Execute cast via casting/cast-spell-handler
       * If pending-selection: return {:action-type :cast-spell, :game-db db-tapped, :pending-selection sel}
       * If cast failed: return {:action-type :pass, :game-db game-db} (revert tapped lands)
       * If cast succeeded: SBA check, return {:action-type :cast-spell, :game-db db'}
```

**Critical data flow:** `bot-act` calls engine functions directly, with NO re-frame event dispatch:
- `lands/play-land` is called as pure function: `lands/play-land(db, player-id, object-id)`
- `casting/cast-spell-handler` is called as pure function: `casting/cast-spell-handler({:game/db db}, {:player-id pid :object-id oid :target tid})`

This is the key architectural truth: **bots do not dispatch events; they call handler functions directly.**

---

## 2. BOT ACTION FLOW: Casting and Land Play

### Path A: Land Play (Simple)

**Caller:** `bot-act` line 101-105  
**Trigger:** Phase action `:play-land` + valid land in hand

```
bot-act calls:
  (lands/play-land game-db player-id land-id)
    ↓
  (rules/can-play-land? db player-id object-id) [GUARD]
    ↓
  Moves land to battlefield
  Decrements land-plays-left
  Registers card triggers (trigger-db/create-triggers-for-card-tx)
  Fires ETB effects (card/etb-effects)
  Dispatches :land-entered event for triggers (handled in trigger_dispatch)
    ↓
  SBA check (sba/check-and-execute-sbas)
    ↓
  Returns db'
```

**Module crossing:** `bots` → `events/lands` → `engine/rules` ✓ (declared)  
**Module crossing:** `bots` → `engine/state-based` ✗ (NOT declared; triggered indirectly via director)

**Data flow:** Pure function composition; land object moves through zones EDN, no app-db mutations.

### Path B: Spell Cast (Complex, Multi-Stage)

**Caller:** `bot-act` line 107-135  
**Trigger:** Priority decision `:cast-spell` (from `bot-interceptor/bot-decide-action`)

**Stage 1: Mana Tapping**
```
bot-act calls:
  (bot-interceptor/bot-decide-action game-db)
    → returns {:action :cast-spell :object-id oid :target tid :tap-sequence [...]}
  ↓
For each tap in :tap-sequence:
  (mana-activation/activate-mana-ability db player-id object-id mana-color)
    ↓
    Taps land, adds mana to pool
    Returns db'
```

**Module crossing:** `bots/interceptor` → `bots/protocol` ✓ (same module)  
**Module crossing:** `bots/interceptor` → `engine/mana-activation` ✗ (NOT declared)

**Stage 2: Spell Casting**
```
bot-act calls:
  (casting/cast-spell-handler
    {:game/db db-tapped}
    {:player-id pid :object-id oid :target tid})
  ↓
Evaluates pre-cast pipeline:
  - :exile-cards-cost
  - :return-land-cost
  - :discard-specific-cost
  - :sacrifice-permanent-cost
  - :pay-x-life
  - :x-mana-cost
  - :targeting
  - :mana-allocation
  ↓
Returns:
  {:game/db db', :game/pending-selection sel} OR
  {:game/db db'} (cast succeeded, no selection)
```

**Module crossing:** `bots` → `events/casting` ✓ (declared)  
**Module crossing:** `bots` → `events/selection/*` ✗ (NOT declared; triggered indirectly)

**Stage 3: Post-Cast (if no selection needed)**
```
bot-act calls:
  (sba/check-and-execute-sbas db')
  ↓
  Returns db''
```

**Module crossing:** `bots` → `engine/state-based` ✗ (NOT declared)

**Critical fork:** If casting creates a selection (e.g., targeting, mana allocation):
```
bot-act returns:
  {:action-type :cast-spell
   :game-db db-tapped
   :pending-selection sel}
  ↓
step-bot-action in director sees :pending-selection
  ↓
Director loop stops with :reason :pending-selection
  ↓
WAITS FOR HUMAN INTERACTION
  ↓
Does NOT return app-db to game loop
```

**This is a critical behavior change from stated design:** When a bot cast requires interactive selection, the director STOPS and returns control to the human player. The bot does not auto-confirm selections; it yields to the human.

---

## 3. PRIORITY SYSTEM INTERACTION

### How Bots Participate in Priority Passing

**Module:** `engine/priority.cljs` (9 pure functions, no state mutation knowledge)

**Functions called by director:**
1. `priority/get-priority-holder-eid(game-db)` — query who has priority
2. `priority/yield-priority(game-db, player-eid)` — add player to passed set
3. `priority/both-passed?(game-db)` — check if priority cycle complete
4. `priority/transfer-priority(game-db, current-eid)` — give priority to other player
5. `priority/reset-passes(game-db)` — clear passed set on phase advance

**Bot interaction flow:**

```
step-bot-action calls bot-act
  ↓
bot-act returns {:action-type :pass} (no valid actions)
  ↓
step-bot-action extracts:
  holder-eid = priority/get-priority-holder-eid(game-db)
  all-passed? = priority/both-passed?(passed-db)
  ↓
If not all-passed?:
  → Transfer priority to other player
  → Loop continues (human now holds priority)
  ↓
If all-passed?:
  → Reset passes
  → Check if stack empty:
    * If stack has items: step-resolve-stack (begin resolution)
    * Else: step-advance-phase (advance to next phase)
```

**No bot-specific priority logic exists.** Bots follow identical priority passing rules as humans. The only difference is the decision-making function (`bot-decide-action` vs human click/keyboard).

### Yield/Yield-All Interaction

**Module:** `events/priority_flow.cljs` (contains `::yield` and `::yield-all` event handlers)

**Interaction point:** When human clicks Yield/F6 in UI:
```
Human action (UI click)
  ↓
::yield or ::yield-all event dispatched
  ↓
priority_flow/yield-handler or priority_flow/yield-all-handler
  ↓
Calls director/run-to-decision(app-db, {:yield-all? true/false})
  ↓
Director auto-passes both players
  ↓
Calls step-bot-action repeatedly until:
    - Human has decision point, OR
    - Resolution completes, OR
    - Turn ends
```

**Critical detail:** `yield-all?` flag does NOT make bots auto-pass. It makes the HUMAN auto-pass. Bots always make decisions via `bot-decide-action`. When `yield-all?` is true AND stack is non-empty:

```
yield-all? with items on stack:
  → Set yield-through-stack? = true (resolve stack, then stop)
  → human-should-auto-pass returns true
  → step-human-action yields
  → step-resolve-stack resolves one item
  → Loop continues until stack empty, then stops
```

This is a **UX orchestration feature, not a bot behavior change.**

---

## 4. UNDECLARED FLOWS (Gaps Between Model and Reality)

### 4.1 Direct Engine Calls from Director

**Declared:** `bots -> engine`  
**Reality:** Director calls engine modules that bots do not directly reference:

| Engine Module | Called by | Path |
|---|---|---|
| `engine/state-based.cljs` | `director.cljs` line 105, 161 | `bot-act` → `lands/play-land` → implicit SBA |
| `engine/state-based.cljs` | `director.cljs` line 258 | `step-resolve-stack` → SBA after resolution |
| `engine/mana-activation.cljs` | `director.cljs` line 112 | `bot-act` → tap mana for bot cast |
| `engine/rules.cljs` | `director.cljs` line 172 | `bot-act` → `can-play-land?` check |
| `engine/priority.cljs` | `director.cljs` multiple | Priority holder checks, pass tracking, phase resets |

**Analysis:** These are NOT undeclared for bots; they're declared as `director -> engine`. But bots are completely invisible in this chain. The architecture model should clarify: bots do NOT directly query engine; they **act through the director, which queries engine.**

### 4.2 Event Module Imports (Not Event Dispatch)

**Declared:** `bots -> events_game`  
**Reality:** No re-frame dispatch; direct function calls:

**bots/interceptor.cljs imports (but never uses for dispatch):**
- `fizzle.events.casting` — (unused in this module; called by director instead)
- `fizzle.events.lands` — (unused in this module; called by director instead)

**director.cljs imports (and calls directly):**
- `fizzle.events.casting` line 31
- `fizzle.events.lands` line 33
- `fizzle.events.cleanup` line 32
- `fizzle.events.phases` line 34
- `fizzle.events.resolution` line 35

**These are FUNCTION CALLS, not event dispatches.** Example:

```clojure
;; DECLARED PATH (not used):
(rf/dispatch [::casting/cast-spell {...}])

;; ACTUAL PATH:
(casting/cast-spell-handler {:game/db db} {:player-id pid :object-id oid})
```

This breaks the declared architectural boundary. **Events layer is being used as a library of pure functions, not as an event system.**

### 4.3 SBA Chokepoint (`db_effect.cljs`) Removed

**Status:** `db_effect.cljs` lines 9-11 state:
```
Bot decisions are now handled inline by the game director
(events/director.cljs), not dispatched from here.
```

**Finding:** The `:db` effect handler override (lines 45-50) does NOT queue bot decisions:

```clojure
(defn game-db-effect-handler [new-app-db]
  (let [old-game-db (:game/db @rf-db/app-db)
        new-game-db (:game/db new-app-db)]
    (if (or (nil? new-game-db) (identical? new-game-db old-game-db))
      (when-not (identical? @rf-db/app-db new-app-db)
        (reset! rf-db/app-db new-app-db))
      (let [sba-db (sba/check-and-execute-sbas new-game-db)
            final-app-db (assoc new-app-db :game/db sba-db)]
        (reset! rf-db/app-db final-app-db)))))
```

**Previous comment (now stale):** The memory note references db_effect dispatching `::bot-decide` "after every game-db mutation where bot holds priority." This is **no longer true.** The only dispatch here is the internal re-frame app-db reset. No `::bot-decide` event is queued.

**Consequence:** `bots/interceptor.cljs` contains unreachable code:
- `::bot-decide` handler (lines 252-255)
- `::bot-action-complete` handler (lines 258-261)
- `bot-decide-handler` function (lines 177-239)
- `bot-action-complete-handler` function (lines 242-249)

These functions are **never called.** They represent the old async, dispatch-based bot architecture.

### 4.4 Stale Schema Fields

**In `db/schema.cljs`:**
```clojure
:bot/action-pending? {}  ; NOT IN USE
:bot/action-count {}     ; NOT IN USE
```

And in `events/director.cljs` line 307-308:
```clojure
(let [app-db (dissoc app-db :yield/epoch :yield/step-count
                      :bot/action-pending? :bot/action-count)]
```

These fields are **explicitly removed** when director starts. They were used in the old async path to prevent race conditions between compound actions (tap + cast) completing out of order. With pure synchronous execution, they're no longer needed but haven't been removed from schema.

---

## 5. IMPLICIT ORDERING: Which Modules Must Act Before Others

### Ordering Constraints Enforced by Director

**Director's Main Loop Ordering (line 318, events/director.cljs):**

```
1. Check game state preconditions
     ↓
2. Determine priority holder
     ↓
3. Dispatch to step handler (bot vs human)
     ↓
4. For bots: bot-act → lands/play-land OR bot-decide-action
     ↓
5. For spells: tapping → casting → SBA check
     ↓
6. Restart loop
```

**Implicit ordering constraint:** Mana tapping MUST complete before casting, enforced by sequential `reduce` in bot-act line 111-114:

```clojure
db-tapped (reduce (fn [d {:keys [object-id mana-color]}]
                    (mana-activation/activate-mana-ability
                      d player-id object-id mana-color))
                  game-db tap-seq)
```

Each tap mutates db sequentially; next tap operates on tapped db.

**Implicit ordering constraint:** SBA checks fire AFTER every action, enforced by director calling `sba/check-and-execute-sbas` in:
- `bot-act` line 105 (after land play)
- `bot-act` line 135 (after spell cast)
- `step-resolve-stack` line 258 (after resolution)
- `advance-one-phase` line 164 (after phase advance)

**This is NOT captured in the architecture model.** The model does not document the SBA loop as a structural component.

### Ordering NOT Enforced by Structure (Implicit in Event Handler Order)

**Problem:** If an event handler depends on another being called first, this is implicit in handler registry order, not captured in module structure.

**Example:** `events/resolution.cljs` calls `sel-core/confirm-selection-impl` (line 89) after building a selection. But `confirm-selection-impl` is defined in `events/selection/core.cljs`. The dependency is:

- `resolution.cljs` imports `selection.core`
- `resolution.cljs` calls `sel-core/confirm-selection-impl` as a pure function
- No re-frame event dispatch; no order dependency

**Result:** Ordering is implicit in function call order, not in re-frame's handler registration. This is safe for bots (all calls are synchronous), but it means:

1. **Tests cannot fully validate ordering** — they would need to mock SBA checks to verify they fire at the right times
2. **New modules adding bot-related logic must be aware** of where SBA checks happen in the director loop

---

## 6. TWO-PLAYER PATH DIVERGENCE: Where Code Paths Split by Player Type

### 6.1 Main Loop Dispatch (line 340, events/director.cljs)

**Divergence point:**

```clojure
(let [holder-pid (current-holder-player-id game-db)
      step-result
      (cond
        (bot-protocol/get-bot-archetype game-db holder-pid)
        (step-bot-action app-db game-db holder-pid yield-all? yield-through-stack?)
        
        (= holder-pid human-pid)
        (step-human-action app-db game-db human-pid ...)
        
        :else
        {:done {:app-db app-db :reason :await-human}})]
```

**Both paths exist in the same function.** No separate code modules; same director orchestrates both.

### 6.2 Action Decision Paths

**Human decision:** UI click (keyboard/mouse) → event dispatch → event handler  
**Bot decision:** `bot-protocol/get-bot-archetype` query → `bot-act` function call

**Module-level divergence:**

| Component | Human | Bot |
|-----------|-------|-----|
| Decision entry | re-frame event (e.g., `::cast-spell`) | `bot-act` pure function |
| Action validation | `rules/can-cast-spell?` in casting.cljs | Same, but via bot-act path |
| Target selection | `sel-targeting/build-cast-time-target-selection` UI builder | `bot-interceptor/bot-decide-action` returns pre-determined target |
| Mana allocation | `sel-costs/build-mana-allocation-selection` UI builder | `bot-interceptor/find-tap-sequence` computes allocation |
| Execution | Event handler chain | Pure function chain (no re-frame dispatch) |

### 6.3 Attacker Declaration (Combat Phase)

**Special case:** Bots auto-declare attackers in `resolution.cljs` line 81-90:

```clojure
(:needs-attackers result)
(let [eligible (:eligible-attackers result)
      archetype (bot-protocol/get-bot-archetype game-db controller)]
  (if archetype
    ;; Bot path: auto-confirm
    (let [chosen (bot-protocol/bot-choose-attackers archetype eligible)
          sel (sel-combat/build-attacker-selection eligible controller ...)
          sel (assoc sel :selection/selected (set chosen))
          app-db {:game/db game-db :game/pending-selection sel}
          result-db (sel-core/confirm-selection-impl app-db)]
      {:db (:game/db result-db)})
    ;; Human path: show selection UI
    {:db game-db
     :pending-selection (sel-combat/build-attacker-selection ...)}))
```

**Divergence:** Bot's selection is pre-filled via `bot-protocol/bot-choose-attackers`, then immediately confirmed via `sel-core/confirm-selection-impl`. Human sees a dialog.

**Code smell:** The selection object is constructed the same way for both paths, but one is immediately destroyed by `confirm-selection-impl`. This suggests the pre-fill-then-confirm pattern is a workaround for a shared selection infrastructure that expects human UI interaction.

### 6.4 Blocker Declaration (Handled Identically)

**Line 96-102, resolution.cljs:**

```clojure
(:needs-blockers result)
(let [attackers (:attackers result)
      defender-id (:defender-id result)
      sel (sel-combat/build-blocker-selection game-db attackers defender-id ...)]
  {:db game-db
   :pending-selection sel})
```

**No special bot handling.** Bots and humans get the same selection UI. This is a **behavioral gap**: bots currently cannot declare blockers. The blocker selection UI is human-only.

### 6.5 Interactive Effects During Resolution

**Divergence:** When `resolve-one-item` encounters an interactive effect (`:needs-selection`), line 104-105:

```clojure
(:needs-selection result)
(build-selection-from-result game-db controller top result)
```

**Both paths see the same selection UI.** No special bot handling. If a bot's spell requires interactive selection during resolution (e.g., "choose a target"), the director **stops and waits for human input**, even though the bot owns the effect.

**Example:** Imagine a bot casts a spell with `{:effect/type :return-from-graveyard}`. The resolution would create a selection for "choose card from graveyard." The director does NOT auto-confirm this on behalf of the bot; it returns `:reason :pending-selection` and stops.

**This is correct behavior** (humans should control interactive effects), but it's a limitation of the current bot system: bots cannot handle interactive resolution effects autonomously. They require human guidance.

---

## 7. SUMMARY OF DATA FLOWS

### High-Level Flow Diagram

```
┌─────────────────────────────────────────────────────────────────┐
│                     events/priority_flow.cljs                   │
│              (::yield, ::yield-all handlers)                    │
└────────────────────────────┬────────────────────────────────────┘
                             ↓
┌─────────────────────────────────────────────────────────────────┐
│                  events/director.cljs                           │
│         (pure sync game loop orchestrator)                      │
│                                                                 │
│  run-to-decision(app-db, opts) → {:app-db, :reason}           │
│    ├─ step-bot-action ← bot holds priority                     │
│    │   └─ bot-act(game-db, player-id)                          │
│    │       ├─ bot-phase-action → play-land path                │
│    │       │   └─ lands/play-land(db, pid, oid)               │
│    │       │       └─ trigger-db/create-triggers               │
│    │       │       └─ sba/check-and-execute-sbas               │
│    │       │                                                    │
│    │       └─ bot-priority-decision → cast-spell path          │
│    │           ├─ bot-decide-action(game-db)                   │
│    │           │   └─ bot-rules/evaluate-condition(s)          │
│    │           │   └─ find-tap-sequence(game-db, pid, cost)    │
│    │           │                                                │
│    │           ├─ mana-activation/activate-mana-ability (x N)  │
│    │           │                                                │
│    │           ├─ casting/cast-spell-handler(app-db, opts)     │
│    │           │   └─ evaluate-pre-cast-step (pipeline)        │
│    │           │       ├─ costs selection builders             │
│    │           │       ├─ targeting selection builders         │
│    │           │       ├─ mana-allocation selection builder    │
│    │           │       └─ rules/cast-spell-mode(db)            │
│    │           │                                                │
│    │           └─ sba/check-and-execute-sbas                   │
│    │                                                            │
│    ├─ step-human-action ← human holds priority                 │
│    │   └─ human-should-auto-pass() + priority/yield-priority   │
│    │                                                            │
│    ├─ step-resolve-stack ← resolution                          │
│    │   └─ resolution/resolve-one-item(game-db)                 │
│    │       ├─ engine-resolution/resolve-stack-item             │
│    │       ├─ sel-combat/build-attacker-selection              │
│    │       │   (if bot, auto-confirm via bot-choose-attackers) │
│    │       └─ sba/check-and-execute-sbas                       │
│    │                                                            │
│    └─ step-advance-phase ← phase advance                       │
│        └─ phases/next-phase/advance-phase                      │
│            └─ cleanup/begin-cleanup (if cleanup)               │
│            └─ phases/start-turn (new turn)                     │
│            └─ sba/check-and-execute-sbas                       │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
                             ↑
                    Returns to caller
                    (e.g., event handler)
```

### Module Dependency Graph (Actual)

```
┌────────────────────┐
│  bots/protocol     │  (pure data + multimethod dispatch)
│  - bot-archetype   │
│  - bot-*-decision  │
└────────────────────┘
         ↑
         │ imports
         ↓
┌────────────────────────────────┐
│  bots/rules                    │  (evaluates conditions)
│  - evaluate-condition          │
│  - match-priority-rule         │
└────────────────────────────────┘
         ↑
         │ imports
         ↓
┌────────────────────────────────┐
│  bots/definitions              │  (pure specs)
└────────────────────────────────┘

┌────────────────────────────────────────────┐
│  bots/interceptor (UNREACHABLE)            │
│  - bot-should-act?                         │
│  - bot-decide-action                       │
│  - build-bot-dispatches                    │
│  - ::bot-decide handler (never dispatched) │
└────────────────────────────────────────────┘

┌──────────────────────────────────────────────────────────┐
│  events/director (ACTUAL ORCHESTRATOR)                   │
│  - run-to-decision                                       │
│  - bot-act → calls lands/play-land, casting/cast-spell   │
│  - step-bot-action                                       │
│  - step-human-action                                     │
│  - step-resolve-stack                                    │
│  - step-advance-phase                                    │
│                                                           │
│  Imports:                                                │
│  - bots/protocol (to check archetype, get decisions)     │
│  - bots/interceptor (to call bot-decide-action ONLY)     │
│  - events/{casting,lands,phases,cleanup,resolution,...}  │
│  - engine/{priority,rules,state-based,mana-activation}   │
└──────────────────────────────────────────────────────────┘
    ↓ calls (not re-frame dispatch)
    ├─ lands/play-land
    ├─ casting/cast-spell-handler
    ├─ resolution/resolve-one-item
    ├─ phases/*
    └─ cleanup/*

┌──────────────────────────────────────────────────────────┐
│  events/db_effect (SBA CHOKEPOINT)                       │
│  - game-db-effect-handler                                │
│  - Does NOT dispatch ::bot-decide (removed)              │
│  - Calls engine/state-based/check-and-execute-sbas       │
└──────────────────────────────────────────────────────────┘

┌──────────────────────────────────────────────────────────┐
│  events/priority_flow                                    │
│  - ::yield handler                                       │
│  - ::yield-all handler                                   │
│  - Both call director/run-to-decision                    │
└──────────────────────────────────────────────────────────┘
```

---

## 8. UNDECLARED DEPENDENCIES SUMMARY

### Declared in Architecture Model

- ✓ `bots -> engine`: "queries game state for decision making"
- ✓ `bots -> events_game`: "dispatches same casting, land-play, and priority events"

### Reality: Declared Mismatches

1. **`bots` does NOT directly call `events_game` functions**
   - `bots/interceptor.cljs` imports `casting` and `lands` but never uses them
   - Only `director.cljs` calls `events_game` functions
   - Model should be: `director -> events_game` (already true) and `director -> bots/protocol` (true)

2. **`bots` does NOT dispatch re-frame events**
   - Architecture says "dispatches events"
   - Reality: `director` calls handler functions directly, bypassing event system
   - Consequence: No time-travel replay of bot actions; bots are not in history

3. **`bots` queries both `engine` and `events` modules**
   - `bots/rules` imports `fizzle.db.queries` (engine)
   - `bots/rules` imports `fizzle.engine.mana` (engine)
   - `bots/interceptor` imports `fizzle.engine.priority` (engine)

### Undeclared Dependencies (Not in Model)

| Source | Target | Path | Type |
|--------|--------|------|------|
| `director.cljs` | `events/{casting,lands,resolution,phases,cleanup}` | Direct function calls | Architectural violation (events used as library) |
| `director.cljs` | `engine/{state-based,mana-activation,priority,rules}` | Direct function calls | Implicit SBA loop |
| `director.cljs` | `bots/interceptor` | Direct function call to `bot-decide-action` | Core logic dependency |
| `events/resolution.cljs` | `bots/protocol` | Queries bot archetype for attacker selection | Unidirectional |
| `events/resolution.cljs` | `sel-core/confirm-selection-impl` | Pre-fills and auto-confirms bot selections | Bot-specific special case |

### Code That Should Be Removed (Unreachable)

- `bots/interceptor.cljs` lines 252-255: `::bot-decide` event handler
- `bots/interceptor.cljs` lines 258-261: `::bot-action-complete` event handler
- `bots/interceptor.cljs` lines 177-239: `bot-decide-handler` function
- `bots/interceptor.cljs` lines 242-249: `bot-action-complete-handler` function

These represent the old async dispatch-based architecture and are completely disconnected from current code paths.

---

## 9. KEY ARCHITECTURAL INSIGHTS

### Insight 1: Director is the Invisible Orchestrator

The architecture model treats `events/priority_flow` as the orchestration layer. In reality, **`events/director` is the true orchestrator.** It:

- Runs the game loop until human decision points
- Instantiates bot actions inline
- Manages both players' priorities
- Decides when selections pause the loop

The model should explicitly document the director as a top-level component.

### Insight 2: Events Layer Used as Function Library

The declared pattern is "dispatch events → handlers mutate state." In reality:

- `director` calls event handler functions directly
- No re-frame dispatch; no time-travel replay
- Events module is being used as a pure function library

This **breaks the declared architecture.** Options to fix:
1. **Document it**: Explicitly state that director uses events as a library
2. **Refactor it**: Extract pure casting/lands/resolution logic into a separate `engine/*` module
3. **Integrate it**: Make director itself an event handler that re-dispatches bot actions

### Insight 3: Bot Actions Are Not Atomic

A bot's spell cast is implemented as:
1. N mana-activation calls (each pure function)
2. 1 casting call (pure function)
3. 1 SBA check (pure function)

If any step needs interactive selection, the director **stops immediately** and waits for the human. The human must confirm the selection, then the director resumes.

**This is correct from a UX perspective** (human controls selections) but it means bots do not "complete their turn"; they yield whenever interactivity is required.

### Insight 4: SBA Execution Is Implicit

The architecture model does not mention SBAs as a structural component. In reality, SBAs are checked after:
- Every bot action
- Every phase advance
- Every stack item resolution

This is critical for correctness (e.g., creatures with 0 toughness must die immediately) but it's invisible in the declared architecture.

### Insight 5: Two Player Paths Converge

Despite modules implementing `step-bot-action` and `step-human-action` separately, they're coordinated by a single loop with shared state (priority, phases, stops, yields). When a bot yields, priority transfers to the human via the same `priority/yield-priority` function used by humans.

There is no separate "bot game loop"; there is one game loop with conditional logic based on who holds priority.

---

## 10. TESTING IMPLICATIONS

### What Cannot Be Tested at Module Level

- **`bots/interceptor` handlers**: Dead code; no test coverage possible
- **Bot vs human ordering**: Director's loop is synchronous; tests cannot validate that bots get a "turn" before humans without running the full director
- **Selection auto-confirmation**: Pattern is hard-coded in `resolution.cljs` line 89 for attackers; no general mechanism exists for other selections

### What CAN Be Tested

- **`bots/protocol`**: Pure multimethods; test via direct calls
- **`bots/rules`**: Pure condition evaluators; test via direct calls
- **`bot-act` in director**: Pure function; test by passing game-db and checking output
- **`bot-decide-action` in interceptor**: Pure function; still testable even though handler is unreachable

### Critical Gap

There's no test coverage for "bot casts a spell with a selection requirement → director pauses → human confirms → director resumes." This scenario is only tested end-to-end, if at all.

---

## Conclusion

The bot system has undergone a major architectural refactoring from async dispatch-based (old `bots/interceptor`) to pure synchronous (new `events/director`). The old code is completely unreachable and should be removed. The new director-based approach is simpler and more testable, but it introduces undeclared coupling between the director and events modules, and it breaks the declared "dispatch events" architecture pattern.

The architecture model should be updated to reflect reality:
1. Document `director` as the top-level orchestrator
2. Clarify that `director` calls event handlers as pure functions, not via dispatch
3. Deprecate and remove unreachable code in `bots/interceptor`
4. Document SBA checks as implicit, structural component
5. Clarify that bots yield when selections are required (human-confirmed)
