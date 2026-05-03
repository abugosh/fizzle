# SBA Insertion Points Investigation

**Objective**: Map every place where game-db is mutated and returned to the app-db layer, to understand where SBA checks should be inserted into the pipeline instead of relying on an interceptor.

---

## CURRENT SBA INVOCATION PATTERN

**Today**: Global re-frame interceptor (`events/interceptors/sba.cljs`) runs `check-and-execute-sbas` in the `:after` phase of specific events.

**Interceptor trigger events** (line 14-28 in sba.cljs):
```clojure
:fizzle.events.priority-flow/yield
:fizzle.events.priority-flow/yield-all
:fizzle.events.resolution/resolve-top
:fizzle.events.casting/cast-spell
:fizzle.events.priority-flow/cast-and-yield
:fizzle.events.lands/play-land
:fizzle.events.phases/advance-phase
:fizzle.events.phases/start-turn
:fizzle.events.selection/confirm-selection
:fizzle.events.selection/toggle-selection
:fizzle.events.abilities/activate-mana-ability
```

---

## MUTATION POINT 1: `reduce-effects` (engine/effects.cljs:186-211)

**Location**: `/Users/abugosh/g/fizzle/src/main/fizzle/engine/effects.cljs:186-211`

**Code**:
```clojure
(defn reduce-effects
  "Execute effects sequentially, pausing when an interactive effect is encountered."
  ([db player-id effects]
   (reduce-effects db player-id effects nil))
  ([db player-id effects object-id]
   (loop [db db
          [effect & remaining] (seq effects)]
     (if-not effect
       {:db (sba/check-and-execute-sbas db)}                         ;; LINE 207
       (let [result (execute-effect-checked db player-id effect object-id)]
         (if (:needs-selection result)
           (assoc result :remaining-effects (vec remaining))
           (recur (sba/check-and-execute-sbas (:db result)) remaining)))))))  ;; LINE 211
```

**Flow**:
- Executes effects sequentially from array
- **After each non-interactive effect**, calls `check-and-execute-sbas` (line 211)
- **After all effects exhausted**, calls `check-and-execute-sbas` (line 207)
- Returns `{:db db'}`

**Callers** (all pass through this reduction path):
- `events/resolution.cljs:resolve-one-item` → indirectly via `engine/resolution.cljs:resolve-stack-item`
- `events/resolution.cljs:resolve-top` (event handler)
- `events/resolution.cljs:resolve-all` (event handler)
- `events/selection/core.cljs:standard-path` (line 316-317)
- Ability effects in `events/abilities.cljs:activate-granted-mana-ability` (line 355-358)

**Key insight**: SBA checks are **already inline** in the reduce-effects loop. The loop calls SBA after each effect AND after the full sequence completes. This is the primary mutation point.

---

## MUTATION POINT 2: `activate-mana-ability` (engine/mana_activation.cljs:37-119)

**Location**: `/Users/abugosh/g/fizzle/src/main/fizzle/engine/mana_activation.cljs:115`

**Code**:
```clojure
(let [;; Step 4: Check state-based actions
      db-after-sbas (state-based/check-and-execute-sbas db-after-triggers)]
  db-after-sbas)
```

**Flow**:
1. Find mana ability from card data
2. Check `can-activate?`
3. Pay costs (tap, remove counters)
4. Add mana to pool
5. Dispatch `:permanent-tapped` event for triggers
6. **Call `check-and-execute-sbas`** (line 115)
7. Return mutated db

**Event handler**: `events/abilities.cljs::activate-mana-ability` (line 25-30)
- Reads `:game/db` from app-db
- Calls `activate-mana-ability` (engine function)
- Writes result back to `:game/db`
- Trigger event fires this path

**Key insight**: Mana ability is a **direct engine call with inline SBA check**. Not part of reduce-effects flow.

---

## MUTATION POINT 3: `confirm-selection-impl` (events/selection/core.cljs:368-392)

**Location**: `/Users/abugosh/g/fizzle/src/main/fizzle/events/selection/core.cljs:368-392`

**Code** (simplified):
```clojure
(defn confirm-selection-impl
  "Shared wrapper for all selection confirmations."
  [app-db]
  (let [selection (:game/pending-selection app-db)
        on-complete (:selection/on-complete selection)
        game-db (:game/db app-db)
        result (execute-confirmed-selection game-db selection)    ;; Executes multimethod
        lifecycle (or (:selection/lifecycle selection) :standard)]
    (case lifecycle
      :chaining (chaining-path app-db result selection on-complete)
      :finalized (finalized-path app-db result on-complete ...)
      :standard (standard-path app-db result selection on-complete))))
```

**Three lifecycle paths**:

### Path 3a: `:standard` (lines 308-336)
```clojure
(defn- standard-path
  "Standard lifecycle: execute remaining-effects and cleanup source."
  [app-db result selection on-complete]
  (let [remaining-effects (:selection/remaining-effects selection)
        player-id (:selection/player-id selection)
        object-id (:selection/spell-id selection)
        remaining-result (effects/reduce-effects (:db result) player-id
                                                 (or remaining-effects []))]
    (if (:needs-selection remaining-result)
      ;; Interactive effect pauses → return pending selection
      ...
      ;; No more interactive effects: cleanup and apply continuation
      (let [db-final (cleanup-selection-source (:db remaining-result) selection)
            updated (-> app-db
                        (assoc :game/db db-final)
                        (dissoc :game/pending-selection))]
        (if on-complete
          (apply-continuation on-complete updated)
          updated)))))
```

**Mutation**: Calls `reduce-effects` which **already calls SBA inline**. Then calls `apply-continuation` which may continue through further events.

### Path 3b: `:finalized` (lines 339-350)
```clojure
(defn- finalized-path
  "Finalized lifecycle: no remaining-effects, clear selection."
  [app-db result on-complete clear-selected-card?]
  (let [updated (cond-> app-db
                  true (assoc :game/db (:db result))
                  true (dissoc :game/pending-selection)
                  clear-selected-card? (dissoc :game/selected-card))]
    (if on-complete
      (apply-continuation on-complete updated)
      updated)))
```

**Mutation**: No effects execution (SBA was already done by executor or not needed). Just clears pending-selection from app-db.

### Path 3c: `:chaining` (lines 353-365)
```clojure
(defn- chaining-path
  "Chaining lifecycle: call build-chain-selection."
  [app-db result selection on-complete]
  (let [next-sel (build-chain-selection (:db result) selection)]
    (if next-sel
      (let [chained-sel (cond-> next-sel
                          on-complete (assoc :selection/on-complete on-complete))]
        (-> app-db
            (assoc :game/db (:db result))
            (assoc :game/pending-selection chained-sel)))
      ;; nil = conditional chaining, fall through to standard
      (standard-path app-db result selection on-complete))))
```

**Mutation**: No effect execution if chain succeeds; uses standard-path if chain is nil.

---

## MUTATION POINT 4: `toggle-selection-impl` auto-confirm path (events/selection/core.cljs:399-448)

**Location**: `/Users/abugosh/g/fizzle/src/main/fizzle/events/selection/core.cljs:443-447`

**Code**:
```clojure
(defn toggle-selection-impl
  "Handle toggling a selection item."
  [app-db id]
  (let [selection (get app-db :game/pending-selection)
        selected (get selection :selection/selected #{})
        ...
        [new-db selected?] (cond ... )
        ;; Auto-confirm when select-count=1 and auto-confirm?=true
        (if (and selected?
                 (= select-count 1)
                 (:selection/auto-confirm? selection))
          (confirm-selection-impl new-db)    ;; LINE 447
          new-db)))
```

**Flow**:
- User clicks a card (toggle-selection event)
- If `:selection/auto-confirm? true` AND select-count=1, automatically calls `confirm-selection-impl`
- This triggers the full selection confirmation pipeline (standard/finalized/chaining paths above)

**Event handler**: `events/selection.cljs::toggle-selection` (line 26-29)
- Dispatches to `core/toggle-selection-impl`

**Key insight**: Auto-confirm is a **data-driven flag** on the selection map. When true, one-click toggles automatically flow through `confirm-selection-impl`.

---

## MUTATION POINT 5: `allocate-mana-color-impl` (events/selection/costs.cljs:740-767)

**Location**: `/Users/abugosh/g/fizzle/src/main/fizzle/events/selection/costs.cljs:765-766`

**Code**:
```clojure
(defn allocate-mana-color-impl
  "Allocate one generic mana to a color."
  [app-db color]
  (let [selection (:game/pending-selection app-db)
        ...
        new-remaining (dec remaining)
        ...
        updated-db (-> app-db
                       (assoc-in [:game/pending-selection :selection/generic-remaining] new-remaining)
                       (assoc-in [:game/pending-selection :selection/allocation] new-allocation)
                       (assoc-in [:game/pending-selection :selection/remaining-pool] new-pool))]
    (if (zero? new-remaining)
      (core/confirm-selection-impl updated-db)    ;; LINE 766
      updated-db)))
```

**Flow**:
- User clicks a mana color button
- Allocation counter decrements
- When allocation is complete (zero remaining), automatically calls `confirm-selection-impl`

**Event handler**: `events/selection/costs.cljs::allocate-mana-color` (line 787-790)

**Key insight**: Another **auto-confirm path**, but triggered by reaching target count in mana allocation accumulator.

---

## MUTATION POINT 6: Combat selection auto-confirm (events/resolution.cljs:84-90)

**Location**: `/Users/abugosh/g/fizzle/src/main/fizzle/events/resolution.cljs:84-90`

**Code**:
```clojure
(if archetype
  ;; Bot chooses attackers via configurable rules
  (let [chosen (bot-protocol/bot-choose-attackers archetype eligible)
        sel (sel-combat/build-attacker-selection
              eligible controller (:db/id top))
        sel (assoc sel :selection/selected (set chosen))
        app-db {:game/db game-db :game/pending-selection sel}
        result-db (sel-core/confirm-selection-impl app-db)]
    {:db (:game/db result-db)}))
```

**Flow**:
- During `resolve-one-item`, when combat phase needs attacker declaration
- If active player is a bot:
  - Bot protocol chooses attackers
  - Builds attacker selection
  - Pre-populates `:selection/selected` with bot's choice
  - **Calls `confirm-selection-impl` directly**
  - Extracts `:game/db` from result
- If human player: returns pending selection for UI

**Key insight**: Direct in-line call to `confirm-selection-impl` for bot attacker selection (not through event dispatcher).

---

## MUTATION POINT 7: `resolve-one-and-stop` continuation (events/priority_flow.cljs:359-376)

**Location**: `/Users/abugosh/g/fizzle/src/main/fizzle/events/priority_flow.cljs:359-376`

**Code**:
```clojure
(defn- resolve-one-and-stop
  "Resolve the top stack item with temporary :resolving auto-mode."
  [app-db]
  (if (or (:game/pending-selection app-db)
          (queries/stack-empty? (:game/db app-db)))
    app-db
    (let [adb (update app-db :game/db priority/set-auto-mode :resolving)
          result (yield-impl adb)]
      (update (:app-db result) :game/db priority/clear-auto-mode))))

(defmethod sel-core/apply-continuation :resolve-one-and-stop
  [_ app-db]
  (resolve-one-and-stop app-db))
```

**Flow**:
- Registered as continuation `:resolve-one-and-stop`
- Applied by `apply-continuation` after a selection completes
- Calls `yield-impl` which may call `resolve-one-item` which may call `reduce-effects`
- Returns mutated app-db

**Called by**:
- `:cast-and-yield` event → sets this continuation on pre-cast selections (line 389-390)
- Selections that target cost need final resolution after choice completes

**Key insight**: A **continuation dispatcher** that triggers further game state changes post-selection. Flows through `yield-impl` → `resolve-one-item` → `reduce-effects`.

---

## CONTINUATIONS REGISTERED (all paths through `apply-continuation`)

1. **`:default`** → returns app-db unchanged (line 209-211 in selection/core.cljs)
2. **`:resolve-one-and-stop`** → calls `resolve-one-and-stop` (events/priority_flow.cljs:374-376)
   - Triggers `yield-impl` → potentially `resolve-one-item`
3. **`:cast-after-spell-mode`** → calls `initiate-cast-with-mode` (events/casting.cljs:212-220)
   - Continues casting pipeline with chosen mode

**Key insight**: Continuations are **async entry points** to further mutations. They run after `:game/pending-selection` is cleared.

---

## CALL GRAPH: ALL MUTATION PATHS

### Path A: Direct Effect Execution → `reduce-effects` → SBA inline
```
event handler
  ↓
effect executor (multimethod)
  ↓
reduce-effects (engine/effects.cljs)
  ├─→ execute-effect-checked (per effect)
  ├─→ SBA CHECK-AND-EXECUTE (line 211 after each effect)
  └─→ SBA CHECK-AND-EXECUTE (line 207 at end)
```

**Entry points**:
- `events/resolution.cljs::resolve-top` (event)
- `events/resolution.cljs::resolve-all` (event)
- `events/selection/core.cljs:standard-path` (internal, called by confirm-selection-impl)

### Path B: Mana Ability → Direct SBA call
```
events/abilities.cljs::activate-mana-ability (event)
  ↓
engine/mana_activation.cljs:activate-mana-ability (function)
  ├─→ pay-all-costs
  ├─→ reduce (execute each effect)
  ├─→ dispatch-event (:permanent-tapped)
  └─→ SBA CHECK-AND-EXECUTE (line 115)
```

**Entry point**:
- `events/abilities.cljs::activate-mana-ability` (event)

### Path C: Selection Confirm → standard-path → reduce-effects → SBA inline
```
events/selection.cljs::confirm-selection (event)
  ↓
selection/core.cljs:confirm-selection-handler
  ↓
selection/core.cljs:confirm-selection-impl
  ├─→ execute-confirmed-selection (multimethod)
  └─→ (by lifecycle)
      ├─ :standard → standard-path
      │   ├─→ reduce-effects (SBA inline)
      │   ├─→ apply-continuation
      │   └─→ continuation dispatcher
      ├─ :finalized → finalized-path
      │   └─→ apply-continuation
      └─ :chaining → chaining-path
          ├─→ build-chain-selection
          └─→ (if nil) standard-path (SBA inline)
```

**Entry points**:
- `events/selection.cljs::confirm-selection` (event, from UI button)
- `selection/core.cljs:toggle-selection-impl` (internal, line 447 if auto-confirm)
- `selection/costs.cljs:allocate-mana-color-impl` (internal, line 766 if allocation complete)
- `events/resolution.cljs:resolve-one-item` (internal, line 89 for bot attacker selection)

### Path D: Continuation → `resolve-one-and-stop` → `yield-impl` → (recursive)
```
apply-continuation (:resolve-one-and-stop)
  ↓
priority_flow.cljs:resolve-one-and-stop
  ↓
priority_flow.cljs:yield-impl
  ├─→ negotiate-priority
  ├─→ (if all passed + stack not empty) yield-resolve-stack
  │   └─→ resolve-one-item (SBA via reduce-effects)
  └─→ (if all passed + stack empty) yield-advance-phase
      └─→ advance-phase (no SBA here, fires via interceptor)
```

**Entry point**:
- Continuation `:resolve-one-and-stop` (set by cast-and-yield)

---

## WHERE SBA CHECKS HAPPEN TODAY

**1. Inline in `reduce-effects` loop** (engine/effects.cljs:207, 211)
   - ✅ After each effect execution
   - ✅ After all effects exhausted
   - ✅ Covers all spell/ability resolution flows

**2. Direct engine call in `activate-mana-ability`** (engine/mana_activation.cljs:115)
   - ✅ After mana ability effects execute

**3. Global interceptor in `:after` phase**
   - ✅ Fires after trigger events (redundant with inline SBAs in most cases)
   - ✅ Fires after UI-initiated events (cast-spell, play-land, etc.)
   - ✅ Redundant for resolution flows that already call SBA inline

---

## POINTS WHERE SBA IS NOT CURRENTLY CALLED

### Phase Advancement (no SBA)
- `events/phases.cljs:advance-phase` → Only fire-matching-triggers for phase events
- Called by `priority_flow.cljs:advance-with-stops` and `bot-turn-advance-one-phase`
- **Interceptor** fires SBA after the event completes

**Why**: Phase advancement doesn't change game state that triggers SBAs (creatures don't get +1/-1 on phase advance). Only triggers fire (declared attackers, untapped permanents, etc.).

### Priority Passing (no SBA)
- `priority_flow.cljs:negotiate-priority` → Only modifies `:player/passed` flags
- **No game state changes** that could trigger SBAs

### Casting Pipeline (SBA via interceptor)
- `events/casting.cljs::cast-spell` → Builds stack item, no effects execute
- **Interceptor** fires SBA after stack item created
- **Why**: Stack items don't execute effects until resolution

---

## DESIGN ALTERNATIVE: ELIMINATE INTERCEPTOR, USE DECLARATIVE CONTINUATION

**Current approach**: Interceptor catches all game-state mutations, runs SBA unconditionally.

**Alternative**: Make SBA checks **part of the data flow** by:
1. Registering continuation `:check-sbas` on all selection paths that need post-resolution SBA
2. Embedding SBA continuation in the pipeline for non-selection paths

**Pros**:
- Explicit (no implicit side effects from interceptor)
- Easier to understand control flow
- Can avoid redundant SBA checks (e.g., reduce-effects already checks inline)

**Cons**:
- More boilerplate (set continuation on every selection)
- Harder to add SBA checks retroactively if rules change

---

## CONCLUSION

**Every place where game-db is mutated and returned to app-db layer**:

| Path | Location | SBA Check | Caller |
|------|----------|-----------|--------|
| **Reduce-effects** | engine/effects.cljs:186-211 | ✅ Inline (lines 207, 211) | resolve-one-item, resolve-top, standard-path |
| **Mana ability** | engine/mana_activation.cljs:37-119 | ✅ Inline (line 115) | events/abilities.cljs::activate-mana-ability |
| **Selection confirm** | selection/core.cljs:368-392 | ✅ Via reduce-effects OR executor only | confirm-selection-handler, toggle-selection-impl, allocate-mana-color-impl, resolve-one-item |
| **Resolve-one-and-stop** | priority_flow.cljs:359-376 | ✅ Via resolve-one-item if stack not empty | apply-continuation |
| **Combat selection** | resolution.cljs:84-90 | ✅ Via confirm-selection-impl | resolve-one-item (for bots) |
| **Phase advance** | phases.cljs | ❌ Via interceptor only | priority_flow.cljs |
| **Cast spell** | casting.cljs | ❌ Via interceptor only | (event) |
| **Play land** | lands.cljs | ❌ Via interceptor only | (event) |

**Interceptor is redundant for**:
- Selection confirmations (SBA already in reduce-effects)
- Mana ability (SBA already inline)
- Resolution paths (SBA already inline)

**Interceptor is necessary for**:
- Phase advancement
- Casting (before resolution)
- Land play
- Direct ability activation (non-mana, pre-stack)
