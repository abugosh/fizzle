# Fizzle Selection System: Data Flow Analysis

## Executive Summary

The selection system is the **Game Orchestration layer** that pauses spell resolution to collect player input, then resumes execution. It's split across two architectural layers:

1. **Game Orchestration** (`events/selection/*`) — Routes player interactions through domain-specific selection types
2. **Interpretation Core** (`engine/resolution.cljs`, `engine/effects.cljs`) — Detects interactive effects and signals back to orchestration

Data flows **bidirectionally**:
- Core → Orchestration: `{:db db :needs-selection effect :remaining-effects [...]}`
- Orchestration → Core: Player's confirmed selection fed to `reduce-effects` to resume

**Architecture model declares**: Game Orchestration → Interpretation Core (rule delegation) + Data Foundation (queries).
**Reality**: Orchestration also registers methods on Core's multimethods, making the relationship **protocol-based composition** rather than strict layering.

---

## 1. CAST-WITH-SELECTION FLOW

### Entry Point: `::cast-spell` Event

**File**: `events/game.cljs:412–415`

```clojure
(rf/reg-event-db ::cast-spell
  (fn [db [_ opts]]
    (cast-spell-handler db opts)))
```

Dispatched from UI when player clicks a card in hand to cast.

### Phase 1: Modal Spell Detection

**Function**: `cast-spell-handler` (line 369)

```
Input:  app-db (has :game/selected-card, :game/db)
Output: app-db with potential :game/pending-selection or :game/pending-spell-mode-selection
```

First check: Does the card have **modes** (spelled as `:card/modes` in the card definition)?

**File**: `events/game.cljs:354–366` — `get-valid-spell-modes`

Returns only modes with valid targets (for modal cards). Sets `:game/pending-spell-mode-selection` if multiple valid modes exist.

### Phase 2: Casting Mode Selection

After modal spell selection (if applicable), `initiate-cast-with-mode` is called with a specific mode.

**File**: `events/game.cljs:243–351`

Data flows through a **cascade of checks**:

1. **Flashback Cost Check** (line 257)
   - No selection; moves spell to graveyard as alternate destination

2. **Discard-Specific Cost Check** (line 283)
   - Example: Foil's "discard 2 cards, one an Island" cost
   - Chains to `:discard-specific-cost` selection (library.cljs)
   - **Data flow**: Player selects cards → `sel-costs/execute-confirmed-selection` → cost satisfied → resumption to casting

3. **X Mana Cost Check** (line 293)
   - Example: `{:x true}` in `:mode/mana-cost`
   - Chains to `:x-mana-cost` selection (costs.cljs)
   - **Data flow**: Player enters X value → confirmed → moves to next check

4. **Predetermined Target (from opts)** (line 300)
   - Used in bot scenarios where target is pre-selected
   - Calls `sel-targeting/confirm-cast-time-target` directly (no selection)
   - **Data flow**: Target baked into opts → skip player interaction → cast

5. **Targeting Requirement Check** (line 313)
   - Card has `:card/targeting` requirements
   - Special case: Single player target auto-casts (line 316)
   - General case: Chains to `:cast-time-targeting` selection
   - **Data flow**: Player selects target → confirmed → moves to mana allocation check

6. **Generic Mana Cost Check** (line 336)
   - Card has `:colorless > 0` in resolved cost (after X resolution)
   - Chains to `:x-mana-cost` or `:mana-allocation` selection
   - **Data flow**: Player allocates mana → confirmed → proceed to casting

7. **Direct Cast** (line 348)
   - No X, targeting, or generic mana
   - Calls `rules/cast-spell-mode` directly
   - **No selection**; spell moves to stack

### Phase 2a: Targeting Selection Details

**File**: `events/selection/targeting.cljs`

**Selection Type**: `:cast-time-targeting`

```clojure
{:selection/type :cast-time-targeting
 :selection/player-id player-id
 :selection/object-id object-id          ; The spell being cast
 :selection/mode mode                    ; The mode being used
 :selection/target-requirement target-req ; Targeting requirement from card
 :selection/valid-targets valid-targets  ; Pre-computed: which objects are valid
 :selection/selected #{}                 ; Player picks 1 target
 :selection/validation :exact
 :selection/auto-confirm? true}          ; Auto-confirm when 1 target selected
```

**Execution** (`execute-confirmed-selection :cast-time-targeting`):
1. Reads `:selection/selected` set (player's choice)
2. If mode has generic mana cost, chains to `:mana-allocation` selection with `:selection/pending-targets {target-id selected-target}`
3. Otherwise, calls `confirm-cast-time-target` which:
   - Calls `rules/cast-spell-mode` to move spell to stack
   - Finds the spell's `object-ref` to locate its stack-item
   - Stores target on stack-item as `{:stack-item/targets {target-id selected-target}}`

### Phase 2b: X Mana Cost Selection Details

**File**: `events/selection/costs.cljs`

**Selection Type**: `:x-mana-cost`

Player enters a number via UI stepper.

**Execution** (`execute-confirmed-selection :x-mana-cost`):
1. Reads player's X value from selection UI state
2. Resolves mana cost to concrete map with `:colorless X`
3. If resolved cost has generic portion, chains to `:mana-allocation`
4. Otherwise, casts spell directly via `rules/cast-spell-mode`

### Phase 2c: Mana Allocation Selection Details

**File**: `events/selection/costs.cljs`

**Selection Type**: `:mana-allocation`

Player allocates generic mana across colors (e.g., `{2 blue, 1 black}` for cost `{generic 3}`).

**Execution** (`execute-confirmed-selection :mana-allocation`):
1. Reads allocated mana from selection state
2. Calls `mana/pay-mana` with allocation
3. Proceeds to next stage:
   - If `:selection/pending-targets` exists, calls `confirm-cast-time-target` with target
   - Otherwise, casts spell via `rules/cast-spell-mode`

### Phase 3: Spell Moves to Stack

**File**: `engine/rules.cljs` — `cast-spell` or `cast-spell-mode`

Spell object is created in hand with `:object/zone :stack` and costs are paid.

A **stack-item** is created:
```clojure
{:stack-item/type :spell
 :stack-item/object-ref [EID of spell object]
 :stack-item/source spell-id
 :stack-item/controller player-id
 :stack-item/effects [... card's effects ...]
 :stack-item/targets {target-id selected-target}  ; If targeted
 :stack-item/position (highest on stack)
 ...}
```

### Phase 4: Stack Item Resolution

Triggered by `::resolve-one-item` event or priority passing.

**File**: `events/game.cljs:453–472`

```clojure
(defn resolve-one-item [game-db]
  (let [top (stack/get-top-stack-item game-db)]
    (if-not top
      {:db game-db}
      (let [controller (:stack-item/controller top)
            result (engine-resolution/resolve-stack-item game-db top)]
        (cond
          (:needs-storm-split result) ...
          (:needs-selection result)
          (build-selection-from-result game-db controller top result)
          :else
          {:db (stack/remove-stack-item (:db result) (:db/id top))})))))
```

### Phase 4a: Engine Resolution Path

**File**: `engine/resolution.cljs:169–192` — `resolve-spell-type`

1. Gets object from stack-item's `:stack-item/object-ref`
2. Verifies spell is on stack
3. Calls `resolve-spell-effects`:
   - Gets active effects from card definition
   - Reads targets from `:stack-item/targets` (single source of truth)
   - Checks target legality against `:card/targeting` requirements
   - **Pre-resolves all target references**:
     - `:effect/target-ref` → looks up in `:stack-item/targets`
     - `:self` → resolves to spell object-id
     - `:controller` → resolves to controller
     - `:any-player` → resolves to stored player target
   - Calls `effects/reduce-effects` to execute effects

### Phase 4b: Effect Reduction

**File**: `engine/effects.cljs:138–163` — `reduce-effects`

```clojure
(defn reduce-effects [db player-id effects object-id]
  (loop [db db
         [effect & remaining] (seq effects)]
    (if-not effect
      {:db db}
      (let [result (execute-effect-checked db player-id effect object-id)]
        (if (:needs-selection result)
          (assoc result :remaining-effects (vec remaining))
          (recur (:db result) remaining))))))
```

**Critical**: Interactive effects return `{:db db :needs-selection effect}`.
This **pauses the loop** and returns with remaining effects unconsumed.

#### Interactive Effect Types

An effect is interactive if its `:effect/type` multimethod returns `{:db db :needs-selection effect}`:

**File**: `engine/effects.cljs:53–69`

Interactive types (via `execute-effect-impl`):
- `:tutor` — build-selection-for-effect → `:tutor` selection
- `:scry` — build-selection-for-effect → `:scry` selection
- `:peek-and-select` — build-selection-for-effect → `:peek-and-select` selection
- `:return-from-graveyard` — build-selection-for-effect → `:graveyard-return` selection
- `:discard` — build-selection-for-effect → `:discard` selection
- `:discard-from-revealed-hand` — build-selection-for-effect → `:hand-reveal-discard` selection
- `:draw` (if `effect/selection :any-player`) — build-selection-for-effect → `:player-target` selection

### Phase 4c: Selection Builder Registration

**File**: `events/selection/core.cljs:35–54`

```clojure
(defmulti build-selection-for-effect
  "Dispatches on effect type or :player-target for :any-player targeting."
  (fn [_db _player-id _object-id effect _remaining-effects]
    (if (= :any-player (:effect/target effect))
      :player-target
      (:effect/type effect))))
```

Domain modules register methods:
- `selection/library.cljs` — `:tutor`, `:scry`, `:peek-and-select`
- `selection/zone_ops.cljs` — `:discard`, `:return-from-graveyard`, `:discard-from-revealed-hand`
- `selection/targeting.cljs` — `:player-target`

### Phase 4d: Selection Returns to Orchestration

**File**: `events/game.cljs:428–450` — `build-selection-from-result`

Converts Core's `{:db db :needs-selection effect :remaining-effects [...]}` into Game Orchestration's selection format:

```clojure
(let [sel-effect (:needs-selection result)
      sel (sel-core/build-selection-for-effect
            (:db result) sel-player-id source-id
            sel-effect
            (vec (:remaining-effects result)))
      ;; Adjust for ability vs spell (different cleanup path)
      sel (if (= :activated-ability (:stack-item/type stack-item))
            (-> sel
                (dissoc :selection/spell-id)
                (assoc :selection/stack-item-eid stack-item-eid
                       :selection/source-type :stack-item))
            sel)]
  {:db (:db result) :pending-selection sel})
```

Sets `:game/pending-selection` in app-db → UI shows selection controls.

### Phase 5: Player Interaction

Player interacts with selection UI (clicks card, enters value, clicks button).

#### Toggle Selection

**File**: `events/selection.cljs:27–29`

```clojure
(rf/reg-event-db ::toggle-selection
  (fn [db [_ id]]
    (core/toggle-selection-impl db id)))
```

Updates `:game/pending-selection :selection/selected` set.

**File**: `events/selection/core.cljs:183–232` — `toggle-selection-impl`

```clojure
(defn toggle-selection-impl [app-db id]
  (let [selection (get app-db :game/pending-selection)
        selected (get selection :selection/selected #{})
        ...
        [new-db selected?]
        (cond
          (and valid-targets (not (contains? (set valid-targets) id)))
          [app-db false]

          currently-selected?
          [(assoc-in app-db [...] (disj selected id)) false]

          (= select-count 1)
          [(assoc-in app-db [...] #{id}) true]

          ...)]
    ;; Auto-confirm if :selection/auto-confirm? true and count satisfied
    (if (and selected? (= select-count 1) (:selection/auto-confirm? selection))
      (confirm-selection-impl new-db)
      new-db)))
```

Data-driven validation: `:selection/select-count`, `:selection/exact?`, `:selection/valid-targets`.

#### Confirm Selection

**File**: `events/selection.cljs:32–35`

```clojure
(rf/reg-event-db ::confirm-selection
  (fn [db _]
    (core/confirm-selection-handler db)))
```

**File**: `events/selection/core.cljs:129–176` — `confirm-selection-impl`

```clojure
(defn confirm-selection-impl [app-db]
  (let [selection (:game/pending-selection app-db)
        on-complete (:selection/on-complete selection)
        game-db (:game/db app-db)
        result (execute-confirmed-selection game-db selection)]
    (cond
      (:pending-selection result)
      ;; Chain to next selection
      (-> app-db
          (assoc :game/db (:db result))
          (assoc :game/pending-selection (assoc ...)))

      (:finalized? result)
      ;; Fully handled (ability, pre-cast, pile-choice)
      (let [updated (cond-> app-db
                      true (assoc :game/db (:db result))
                      true (dissoc :game/pending-selection)
                      ...)]
        (if on-complete (apply-continuation on-complete updated) updated))

      :else
      ;; Standard: execute remaining-effects, cleanup source
      (let [remaining-effects (:selection/remaining-effects selection)
            player-id (:selection/player-id selection)
            db-after-remaining (reduce (fn [d effect]
                                         (effects/execute-effect d player-id effect))
                                       (:db result)
                                       (or remaining-effects []))
            db-final (cleanup-selection-source db-after-remaining selection)
            updated (-> app-db
                        (assoc :game/db db-final)
                        (dissoc :game/pending-selection))]
        (if on-complete (apply-continuation on-complete updated) updated)))))
```

### Phase 5a: Execution of Confirmed Selection

**File**: `events/selection/core.cljs:20–32` — Multimethod dispatch

```clojure
(defmulti execute-confirmed-selection
  "Dispatches on :selection/type"
  (fn [_game-db selection] (:selection/type selection)))
```

Domain modules register methods by type:

**Example: Tutor Selection**

**File**: `events/selection/library.cljs` (defmethod tutor)

```clojure
(defmethod core/execute-confirmed-selection :tutor
  [game-db selection]
  (let [selected (:selection/selected selection)
        target-zone (:selection/target-zone selection)
        player-id (:selection/player-id selection)
        ...
        db-with-shuffle (if shuffle?
                          (zones/shuffle-library game-db player-id)
                          game-db)]
    (if (:selection/pile-choice selection)
      ;; Multi-select tutor: chain to pile-choice
      {:db db-with-shuffle
       :pending-selection (build-pile-choice-selection selected pile-choice ...)}
      ;; Single-select: just move and return (wrapper handles remaining-effects)
      {:db (reduce (fn [gdb id]
                     (zones/move-to-zone gdb id target-zone))
                   db-with-shuffle
                   selected)})))
```

**Return values** from `execute-confirmed-selection`:
- `{:db db'}` — Standard: wrapper executes remaining-effects
- `{:db db' :pending-selection next-sel}` — Chain: skip remaining, go to next selection
- `{:db db' :finalized? true}` — Finalized: no remaining effects, done with this selection source

### Phase 5b: Remaining Effects Execution

After selection confirmed, `confirm-selection-impl` executes remaining effects via `reduce-effects`:

```clojure
(let [remaining-effects (:selection/remaining-effects selection)
      player-id (:selection/player-id selection)
      db-after-remaining (reduce (fn [d effect]
                                   (effects/execute-effect d player-id effect))
                                 (:db result)
                                 (or remaining-effects []))]
```

This resumes the effect stream **at the next effect after the interactive one**.

If **another interactive effect** is encountered during this reduction, the loop detects it and chains to next selection (see: **Multi-Selection Flow** below).

### Phase 5c: Source Cleanup

**File**: `events/selection/core.cljs:100–122` — `cleanup-selection-source`

Removes the spell/stack-item from the stack after resolution:

```clojure
(defn cleanup-selection-source [game-db selection]
  (let [source-type (:selection/source-type selection)]
    (if (= source-type :stack-item)
      ;; Ability: remove stack-item entity
      (stack/remove-stack-item game-db (:selection/stack-item-eid selection))
      ;; Spell: remove stack-item, then move spell off stack
      (let [spell-id (:selection/spell-id selection)
            spell-obj (queries/get-object game-db spell-id)
            current-zone (:object/zone spell-obj)]
        (if (= current-zone :stack)
          (-> game-db
              (remove-spell-stack-item spell-id)
              (resolution/move-resolved-spell spell-id spell-obj))
          game-db)))))
```

**Critical invariant**: `resolution/move-resolved-spell` is the **single source of truth** for determining a spell's destination zone after resolution (graveyard, battlefield, exile, etc.).

---

## 2. MULTI-SELECTION FLOW

### Sequential Interactive Effects

When a spell has **multiple interactive effects**, they're executed **sequentially** with selections chained together.

**Example**: A spell that tutors then discards

```clojure
{:card/effects [{:effect/type :tutor :effect/criteria {...}}
                {:effect/type :discard :effect/count 1}]}
```

### Chaining Mechanism

**File**: `events/selection/core.cljs:138–176` — `confirm-selection-impl`

The cond branch for chaining:

```clojure
;; Chain to next selection
(when (:pending-selection result)
  (let [chained-sel (cond-> (:pending-selection result)
                      on-complete (assoc :selection/on-complete on-complete))]
    (-> app-db
        (assoc :game/db (:db result))
        (assoc :game/pending-selection chained-sel))))
```

When `execute-confirmed-selection` returns `{:db db' :pending-selection next-sel}`, the framework:
1. Sets the returned db as new game state
2. Sets the chained selection as new pending selection
3. **Clears app-db/game/pending-selection is NOT dissoc'd** — replaced with new selection
4. **on-complete continuation is propagated** to chained selection

### Example: Tutor → Pile-Choice Chaining

**File**: `events/selection/library.cljs` (tutor handler)

```clojure
(defmethod core/execute-confirmed-selection :tutor
  [game-db selection]
  (let [selected (:selection/selected selection)
        pile-choice (:selection/pile-choice selection)]
    (if (:selection/pile-choice selection)
      ;; Chain to pile-choice
      {:db db-with-shuffle
       :pending-selection (build-pile-choice-selection selected pile-choice ...)}
      ;; Standard return
      {:db db-with-shuffle})))
```

### Example: Draw → Discard Chaining

**File**: `events/selection/targeting.cljs:169–199` — `:player-target` execution

When a player-targeted `:draw` effect with `effect/selection :player` is confirmed:

```clojure
(defmethod core/execute-confirmed-selection :player-target
  [game-db selection]
  (let [selected-target (first (:selection/selected selection))
        target-effect (:selection/target-effect selection)
        remaining-effects (vec (or (:selection/remaining-effects selection) []))]
    ;; Execute the resolved player-target effect
    (let [resolved-effect (assoc target-effect :effect/target selected-target)
          db-after-effect (effects/execute-effect game-db player-id resolved-effect)
          ;; Resume remaining-effects via reduce-effects
          result (effects/reduce-effects db-after-effect player-id remaining-effects)]
      ;; If reduce-effects found another interactive effect...
      (if (:needs-selection result)
        ;; Chain to next selection
        {:db (:db result) :pending-selection (next-sel ...)}
        ;; No more selections
        {:db (:db result)}))))
```

The reduction of `remaining-effects` via `reduce-effects` **may itself encounter an interactive effect**, which triggers the same builder → multimethod flow.

### Remaining Effects Are Tracked

**File**: `events/selection/core.cljs:162–169`

```clojure
;; Standard: execute remaining-effects and cleanup
:else
(let [remaining-effects (:selection/remaining-effects selection)
      player-id (:selection/player-id selection)
      db-after-remaining (reduce (fn [d effect]
                                   (effects/execute-effect d player-id effect))
                                 (:db result)
                                 (or remaining-effects []))]
```

Each selection state carries `:selection/remaining-effects` as a **vec** of unconsumed effects. This vec is updated at each pause point by `reduce-effects`:

**File**: `engine/effects.cljs:160–162`

```clojure
(if (:needs-selection result)
  (assoc result :remaining-effects (vec remaining))
  ...)
```

The `remaining` var in `reduce-effects` is a **lazy seq** of effects after the current one. When converted to vec for storage, it freezes the sequence at that point.

---

## 3. TARGETING FLOW

### Two Distinct Targeting Types

#### Type A: Cast-Time Targeting

**When**: Spell has `:card/targeting` requirements, evaluated **before** the spell moves to stack.

**File**: `events/selection/targeting.cljs:77–112` — `cast-spell-with-targeting`

Called as part of `initiate-cast-with-mode` cascade (line 313 of game.cljs).

**Mechanism**:
1. Check `targeting/get-targeting-requirements` from card definition
2. If no reqs, cast immediately
3. If reqs exist, call `build-cast-time-target-selection` to create selection
4. Player picks target (selection type `:cast-time-targeting`)
5. On confirm, call `sel-targeting/confirm-cast-time-target`:
   - Calls `rules/cast-spell-mode` to move spell to stack
   - Stores target on stack-item as `:stack-item/targets {target-id target-value}`

**Data shape**:
```clojure
{:selection/type :cast-time-targeting
 :selection/valid-targets [...]  ; Pre-computed valid targets
 :selection/selected #{...}      ; Player's choice (1 target)
 :selection/mode mode            ; The casting mode
 :selection/target-requirement target-req}
```

#### Type B: Resolution-Time Targeting

**When**: Effect at resolution time has `:effect/target :any-player` requiring player choice.

**File**: `events/selection/targeting.cljs:160–162` — `:player-target` multimethod

**Mechanism**:
1. Spell on stack, resolving normally
2. First effect in list has `:effect/target :any-player`
3. `reduce-effects` calls `execute-effect-checked` for that effect
4. Effect's multimethod handler detects `:effect/target :any-player` and returns `{:db db :needs-selection effect}`
5. `reduce-effects` pauses, returns to orchestration
6. `build-selection-for-effect` dispatches on `:player-target` (special case in core.cljs:39-41)
7. `targeting/build-player-target-selection` creates selection state
8. Player picks player (selection type `:player-target`)
9. On confirm, effect is re-executed with `:effect/target` resolved to chosen player

**Data shape**:
```clojure
{:selection/type :player-target
 :selection/valid-targets #{:player-1 :opponent}
 :selection/selected #{...}     ; Player's choice (1 player)
 :selection/target-effect effect  ; The effect needing the target
 :selection/remaining-effects [...]}
```

### Key Difference

**Cast-time targeting** blocks spell from entering stack until target is confirmed.
**Resolution-time targeting** allows spell to enter stack, pauses resolution mid-effect stream.

### Target Resolution During Resolution

**File**: `engine/resolution.cljs:84–99` — `pre-resolve-targets`

Before executing effects, the resolution system **pre-resolves all target references** in the effects list:

```clojure
(defn- pre-resolve-targets [effects-list source-id controller stored-targets]
  (mapv (fn [effect]
          (-> effect
              (resolve-targeted-player stored-targets)
              (stack/resolve-effect-target source-id controller stored-targets)))
        effects-list))
```

**File**: `engine/stack.cljs:78–105` — `resolve-effect-target`

Resolution precedence:
1. `:effect/target-ref` → lookup in `:stack-item/targets`
2. `:self` → replace with `source-id`
3. `:controller` → replace with `controller`
4. `:any-player` → lookup in `stored-targets[:player]`
5. Pass through unchanged

**Critical**: Pre-resolution only happens when `stored-targets` exists (cast-time targeting or player-target was confirmed). Effects with `:self/:opponent` internally still receive symbolic values (they handle resolution themselves).

---

## 4. MODAL FLOW

### Two Modal Selection Types

#### Type A: Spell-Mode Selection (Pre-Cast)

**When**: Card has multiple **casting modes** (e.g., different costs/effects per mode).

**File**: `events/game.cljs:354–366` — `get-valid-spell-modes`

After `can-cast?` passes, check for `:card/modes` field.

**Selection Type**: NOT a standard selection; sets `:game/pending-spell-mode-selection` (app-db field, not `game/pending-selection`).

**Handler**: `events/game.cljs:321–351` — Mode-selection event

Player picks mode → calls `initiate-cast-with-mode` with the chosen mode.

**Data shape**:
```clojure
{:game/pending-spell-mode-selection
 {:object-id object-id
  :modes valid-spell-modes}}  ; Modes that have valid targets
```

#### Type B: Casting Mode vs Modal Spell

MTG uses "modes" for two different mechanics:
1. **Multiple ways to cast a spell** (via `:mode/id ` in `:card/modes`) — e.g., spell with 2 different costs
2. **Choose one effect upon cast** (via `:mode/modes`) — e.g., "Choose one: A or B or C"

Fizzle implementation uses `:card/modes` vector with `:mode/id :primary :alternate` etc.

**File**: `engine/rules.cljs` — `get-casting-modes`

Returns all modes from card; filtered to castable ones.

**File**: `engine/rules.cljs` — `can-cast-mode?`

Validates cost payability for specific mode.

### Modal Card Targeting

Modal spells can have **per-mode targeting** via `:mode/targeting` field.

**File**: `events/game.cljs:354–366` — `get-valid-spell-modes`

```clojure
(filterv (fn [spell-mode]
           (let [targeting (or (:mode/targeting spell-mode) [])]
             (every? (fn [req]
                       (if (:target/required req)
                         (seq (targeting/find-valid-targets game-db player-id req))
                         true))
                     targeting)))
         card-modes)
```

Only returns modes with **all required targets available**.

### Casting Mode Stored on Object

**File**: `engine/rules.cljs`

When a spell is cast with a mode, `:object/cast-mode` is set on the spell object:

```clojure
{:object/id spell-id
 :object/zone :stack
 :object/cast-mode mode  ; The chosen mode map
 ...}
```

This allows resolution to use per-mode effects (if implemented in future).

---

## 5. FLOW ANOMALIES: Undeclared Relationships

### A. Selection Module ↔ Resolution Module: Protocol-Based Composition

**Declared relationship**: Game Orchestration → Interpretation Core

**Actual relationship**: Game Orchestration (`selection/core.cljs`) **defines multimethods**, and Interpretation Core (`resolution.cljs`, `effects.cljs`) **triggers them indirectly**.

The data flow is:

```
effects/reduce-effects (in Core)
  → execute-effect-checked
    → if :needs-selection
      → return to orchestration
        → confirm-selection-impl (in Orchestration)
          → execute-confirmed-selection (multimethod)
            → effects/reduce-effects (back to Core!)
              → may need-selection again
```

**This is a circular data flow disguised as layering.**

The architecture model shows a **DAG** (directed acyclic graph), but the reality is a **cycle with pause points**.

### B. Selection Types Determined by Orchestration, Not Core

**Expected**: Core's `reduce-effects` would signal "need player input for tutor" and let Core handle it.

**Actual**: Core's `execute-effect-checked` returns a **tagged effect object**, not type information. Orchestration inspects `execute-confirmed-selection`'s **multimethod dispatch** to determine builder.

**File**: `selection/core.cljs:35–42`

```clojure
(defmulti build-selection-for-effect
  (fn [_db _player-id _object-id effect _remaining-effects]
    (if (= :any-player (:effect/target effect))
      :player-target  ; Special case: dispatch on target, not effect type
      (:effect/type effect))))
```

This means **type mapping is implicit**: `:tutor` effect type → `:tutor` selection type (same name). But changing either requires updating both layers.

### C. Remaining Effects Are Reconstructed, Not Passed Intact

**Expected**: Remaining effects would be passed as a ref/pointer.

**Actual**: Remaining effects are **cloned into each selection state**:

**File**: `events/game.cljs:443` (building first selection)

```clojure
sel (sel-core/build-selection-for-effect
      (:db result) sel-player-id source-id
      sel-effect
      (vec (:remaining-effects result)))  ; Vec of remaining effects
```

Each selection state carries a complete **vec copy** of remaining effects:

```clojure
:selection/remaining-effects (vec (:remaining-effects result))
```

This means:
- If a selection is **cancelled**, remaining effects are **lost** (user would need to replay)
- If a selection is **modified** (e.g., "undo last toggle"), remaining effects stay in the selection state
- Remaining effects are **frozen at the point of pause**, not dynamic

### D. Stack-Item Targets vs Effect Target-Refs

**Declared relationship**: Stack-item stores `:stack-item/targets`; effects use `:effect/target-ref` to look them up.

**Actual complexity**: Multiple target storage locations:

1. `:stack-item/targets` — map of `{target-id → target-value}`
2. `:effect/target` — resolved target on individual effects (after pre-resolution)
3. `:object/targets` — (UNUSED in resolution, per comment in resolution.cljs line 11-13)

**File**: `engine/resolution.cljs:147–162`

```clojure
;; Pre-resolve targets only when stored-targets exist
(let [resolved-effects (if (seq stored-targets)
                         (pre-resolve-targets effects-list object-id controller stored-targets)
                         effects-list)]
  (effects/reduce-effects db controller resolved-effects object-id))
```

This creates a **data transformation**: Effects transition from having `:effect/target-ref` (symbolic) to `:effect/target` (concrete).

But effects executed **inside** `reduce-effects` during selection handling (e.g., `effects/execute-effect` in `confirm-selection-impl`) are NOT pre-resolved, they use symbolic targets.

### E. Continuation Protocol: Hidden Post-Selection Logic

**File**: `events/selection/core.cljs:61–78`

```clojure
(defmulti apply-continuation
  (fn [continuation _app-db] (:continuation/type continuation)))
```

This multimethod is **referenced by orchestration** but **registered in game.cljs**, creating a cross-module dispatch that's not immediately visible.

**File**: `events/game.cljs:1073–1075`

```clojure
(defmethod sel-core/apply-continuation :resolve-one-and-stop
  [_continuation app-db]
  (resolve-one-and-stop app-db))
```

This pattern allows orchestration to defer post-selection actions to game logic, but the flow is **implicit**: selection doesn't know what happens after it's confirmed.

### F. Interactive Effect Detection Is Multimethod-Based, Not Type-Safe

**Expected**: A registry of interactive effect types.

**Actual**: Any effect type can become interactive by having its `execute-effect-impl` return `{:db db :needs-selection effect}`.

**File**: `engine/effects.cljs:72–75`

```clojure
(defmethod execute-effect-impl :default
  [db _player-id _effect _object-id]
  db)  ; Unknown types are no-ops, never interactive
```

There's no central registry of "these effect types are interactive." You discover it by:
1. Reading `execute-effect-impl` multimethod defs (scattered across codebase)
2. Checking if they return tagged values
3. Hoping domain modules register builders on `build-selection-for-effect`

If someone adds a new interactive effect type without registering a builder, you'll get an error at runtime (missing multimethod defmethod).

---

## 6. DATA TRANSFORMATIONS AT LAYER BOUNDARIES

### Boundary 1: Spell → Stack-Item

**From**: Spell object in hand
**To**: Stack-item with references, targets, effects

**Location**: `engine/rules.cljs` — `cast-spell` or `cast-spell-mode`

**Transformation**:
```clojure
Input:  spell object {:object/id spell-id :object/zone :hand ...}
Output: stack-item {:stack-item/type :spell
                    :stack-item/object-ref [entity-id]
                    :stack-item/targets {target-id target-value}
                    :stack-item/effects [...]
                    ...}
```

The stack-item **reads effects from the card definition** at this point, not from the object. This means:
- Dynamic effects (e.g., X-cost resolves to concrete value) are NOT stored on the stack-item
- Targets are stored separately from effects
- Effects are immutable once on stack (correct per MTG rules)

### Boundary 2: Effect → Selection State

**From**: Interactive effect from reduce-effects
**To**: Selection state for UI

**Location**: `events/selection/core.cljs:35–54` + domain modules

**Transformation**:
```clojure
Input:  effect {:effect/type :tutor :effect/criteria {...} ...}
Output: selection {:selection/type :tutor
                   :selection/zone :library
                   :selection/candidates [...]
                   :selection/select-count 1
                   :selection/remaining-effects [effects after this one]
                   ...}
```

**Key data shape changes**:
- Effect's `:effect/criteria` → Selection's `:selection/candidates` (pre-computed matching cards)
- Effect's `:effect/count` → Selection's `:selection/select-count` (normalized to max possible)
- Effect itself is NOT stored (though called `:selection/remaining-effects`, the effect is removed from the list)

### Boundary 3: Selection → Executed Effect

**From**: Selection state with player's choices
**To**: Modified game-db

**Location**: `events/selection/core.cljs` — `execute-confirmed-selection` and domain modules

**Transformation**:
```clojure
Input:  selection {:selection/selected #{card-id-1 card-id-2}
                   :selection/type :tutor
                   :selection/target-zone :hand
                   ...}
Output: game-db' (cards moved to :hand, library shuffled, ...)
```

The selection **never returns to an effect structure**. Instead:
- Selection handler directly modifies game-db
- Returns either `{:db db'}` or `{:db db' :pending-selection next-sel}` or `{:db db' :finalized? true}`
- Wrapper decides what to do with remaining-effects

---

## 7. IMPLICIT ORDERING REQUIREMENTS

### A. Pre-Resolution Must Happen Before Effect Execution

Effects requiring pre-resolved targets **MUST** be executed only after `pre-resolve-targets` completes.

**Violation pattern**: Executing effects before resolution initialization.

**Current code**: Correct — `pre-resolve-targets` is always called before `reduce-effects` in `resolve-spell-effects`.

### B. Selection Confirmation Must Restore Remaining Effects in Order

When a selection is confirmed and remaining-effects are resumed via `reduce-effects`, the **order must be preserved**.

**Current code**: Uses vec and loops in order — correct.

**Potential issue**: If remaining-effects are **modified** between storage and execution (e.g., filtered), order might diverge.

### C. Cleanup Must Happen After Remaining Effects

**File**: `events/selection/core.cljs:164–170`

```clojure
(let [remaining-effects (:selection/remaining-effects selection)
      db-after-remaining (reduce (fn [d effect]
                                   (effects/execute-effect d player-id effect))
                                 (:db result)
                                 (or remaining-effects []))
      db-final (cleanup-selection-source db-after-remaining selection)]
```

Cleanup is ordered **after** remaining-effects execution. This is correct: stack-item must stay on stack until all effects have run.

### D. Grants Must Expire After Cleanup Discard

**File**: `events/selection/zone_ops.cljs:116–130`

```clojure
(defmethod core/execute-confirmed-selection :discard
  [game-db selection]
  (let [db-after-discard (reduce (fn [gdb obj-id]
                                   (zones/move-to-zone gdb obj-id :graveyard))
                                 game-db
                                 selected)]
    (if (:selection/cleanup? selection)
      ;; Cleanup path: expire grants AFTER discard
      (let [game-state (queries/get-game-state db-after-discard)
            current-turn (:game/turn game-state)
            db-final (grants/expire-grants db-after-discard current-turn :cleanup)]
        {:db db-final :finalized? true})
      ;; Standard path: wrapper handles remaining-effects
      {:db db-after-discard})))
```

The flag `:selection/cleanup?` controls **when** grant expiration happens:
- `true` → finalized selection, expire grants immediately
- `false/nil` → standard selection, wrapper handles remaining-effects

This enforces **MTG Rule 514.1 before 514.2** (discard before end-of-turn effects expire).

---

## 8. STORM SELECTION: Special Case

### Storm Splitting

When a **targeted storm spell** resolves, a special `:needs-storm-split` signal is returned from resolution.

**File**: `engine/resolution.cljs:264–293` — `:storm` defmethod

```clojure
(defmethod resolve-stack-item :storm
  [db stack-item]
  (let [controller (:stack-item/controller stack-item)
        source-id (:stack-item/source stack-item)
        source-obj (queries/get-object db source-id)
        has-targeting (when source-obj (:card/targeting (:object/card source-obj)))]
    (if has-targeting
      ;; Targeted storm: pause for split selection BEFORE creating copies
      {:db db :needs-storm-split true}
      ;; Non-targeted storm: create copies directly
      {:db (reduce ...)))))
```

### Storm-Split Selection Details

**File**: `events/selection/storm.cljs:22–55`

```clojure
(defn build-storm-split-selection
  [game-db player-id storm-stack-item]
  (let [source-id (:stack-item/source storm-stack-item)
        copy-count (or (:effect/count
                        (first (filter #(= :storm-copies (:effect/type %)) effects)))
                      0)
        valid-targets (filterv some? [opponent-id player-id])]
    {:selection/type :storm-split
     :selection/copy-count copy-count
     :selection/allocation (assoc (zipmap valid-targets (repeat 0))
                                  (first valid-targets) copy-count)  ; Default to first target
     ...}))
```

**Execution** (`execute-confirmed-selection :storm-split`):

```clojure
(defmethod core/execute-confirmed-selection :storm-split
  [game-db selection]
  (let [allocation (:selection/allocation selection)
        ...
        db-with-copies (reduce-kv
                         (fn [d target-player-id cnt]
                           (if (pos? cnt)
                             (loop [d' d remaining cnt]
                               (if (pos? remaining)
                                 (recur (triggers/create-spell-copy
                                          d' source-id controller
                                          {:player target-player-id})
                                        (dec remaining))
                                 d'))
                             d))
                         game-db allocation)]
    {:db db-final :finalized? true}))
```

**Key difference from other selections**: Storm-split creates **copies with individually assigned targets**, then **removes the original storm stack-item** (`db-final` applies `remove-stack-item`).

This means the original spell's stack-item is **consumed** by storm-split, not resumed for remaining-effects.

---

## Summary: Architecture vs Reality

| Aspect | Declared (Model) | Reality (Code) |
|--------|------------------|----------------|
| **Layer flow** | Orchestration → Core (DAG) | Cycle with pause points |
| **Effect ↔ Selection** | Core returns effect, Orchestration builds selection | Multimethod dispatch bridges both |
| **Type mapping** | Explicit registry | Implicit via multimethod names |
| **Remaining effects** | Assumed immutable refs | Cloned into each selection state |
| **Target storage** | Stack-item `:targets` | Multiple locations (stack-item, effect, object) |
| **Post-selection flow** | Transparent in Orchestration | Hidden in continuation protocol |
| **Interactive detection** | Static set of types | Runtime dispatch via multimethod |
| **Storm handling** | Via stack items | Via special `:needs-storm-split` signal |

The **selection system is sophisticated but implicit**. The protocol-based composition (multimethods) is more flexible than strict layering but harder to trace statically.

