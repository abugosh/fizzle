---
name: Storm-Split System Complete Investigation
description: Full 7-area trace of storm-split resolution, selection shape, UI, copy targeting, targeting infrastructure, effect resolution, and keyboard context
type: reference
---

# Storm-Split System — Complete Investigation

## 1. Storm Resolution Flow (events/resolution.cljs → engine/resolution.cljs)

**Entry point:** `events/resolution.cljs:114-117` in `resolve-one-item`
```clojure
(:needs-storm-split result)
(if-let [sel (sel-storm/build-storm-split-selection game-db controller top)]
  {:db game-db :pending-selection sel}
  {:db (stack/remove-stack-item game-db (:db/id top))})
```

**Engine detection:** `engine/resolution.cljs:336-349`
- `:storm` stack-item type dispatches to `resolve-stack-item :storm` multimethod
- Checks if source object has `:card/targeting`
- If yes: signals `{:db db :needs-storm-split true}` (pauses, returns to events layer)
- If no: executes effects directly, creates copies inline

**Selection builder called:** `events/selection/storm.cljs:22-58`
- `build-storm-split-selection` extracts copy count from `:effect/count` in `:effect/type :storm-copies`
- Builds `valid-targets` as ordered vector `[opponent-id player-id]` (opponent first)
- Default allocation: all copies to first target (opponent)
- Returns nil if copy-count is 0, source doesn't exist, or no valid targets

**Confirm handler:** `events/selection/storm.cljs:65-88`
- `(defmethod core/apply-domain-policy :storm-split ...)`
- Validates total allocation equals copy-count
- Calls `triggers/create-spell-copy` for each target player in allocation map
- Removes the original `:storm` stack-item

**Full chain:**
```
engine/resolution.cljs (resolve-stack-item :storm)
  → returns :needs-storm-split
→ events/resolution.cljs:115 (resolve-one-item catches it)
  → calls sel-storm/build-storm-split-selection
→ events/selection/core.cljs (set-pending-selection enriches + validates)
→ UI renders accumulator/storm-split-inline-view
→ user adjusts allocation via ::adjust-storm-split events
→ user confirms via ::confirm-selection event
→ events/selection/core.cljs (confirm-selection-impl)
  → dispatches to apply-domain-policy :storm-split
→ triggers/create-spell-copy called for each allocation
→ copies end up on stack with :stack-item/targets {:player player-id}
```

## 2. Storm-Split Selection Shape

**Location:** `events/selection/storm.cljs:45-58` (builder return keys)

```clojure
{:selection/mechanism :accumulate
 :selection/domain :storm-split
 :selection/lifecycle :finalized
 :selection/copy-count 3              ;; How many copies to distribute
 :selection/valid-targets [:player-2 :player-1]  ;; Player IDs (opponent first)
 :selection/allocation {:player-2 3 :player-1 0} ;; Map of target → count
 :selection/source-object-id (uuid)   ;; Original spell on stack
 :selection/source-name "Brain Freeze" ;; Card name for UI
 :selection/player-id :player-1       ;; Controller/caster
 :selection/stack-item-eid 12345      ;; Datascript EID of :storm stack-item
 :selection/selected #{}              ;; (unused for storm)
 :selection/validation :always
 :selection/auto-confirm? false}
```

**Key points:**
- `:selection/allocation` is the state being adjusted by stepper controls
- Values are counts (0, 1, 2, 3...), NOT object IDs or complex structures
- `:selection/valid-targets` is ordered vector (opponent at index 0)
- `:selection/mechanism` is `:accumulate` (shared with x-mana-cost, pay-x-life)
- `:selection/domain` is `:storm-split` (distinguishes from other accumulators)

## 3. Storm-Split UI

**Location:** `views/selection/accumulator.cljs:133-171`

**Component:** `storm-split-inline-view`
- Reads from selection: `:copy-count`, `:valid-targets`, `:allocation`, `:source-name`
- Renders wrapper with border
- Header: `"DISTRIBUTE N COPIES of [source-name]"`
- Body: `map-indexed` over `valid-targets` → renders `accumulate-inline-stepper-row` for each
- Footer: counter `"X / N assigned"`, confirm button (disabled until total == copy-count)

**Stepper rows:** `accumulate-inline-stepper-row` (lines 73-130)
- Per-target display: `[«] [-] count [+] [»]`
- Buttons dispatch: `::storm-events/adjust-storm-split target-id delta`
- Deltas: -count («), -1 (-), +1 (+), +(remaining) (»)
- Keyboard hints computed from chord context

**Subscription:** Uses `@(rf/subscribe [::subs/pending-selection])` from app-db (set by `set-pending-selection`)

## 4. Target Resolution for Copies (engine/triggers.cljs)

**Location:** `engine/triggers.cljs:36-91` (create-spell-copy)

**4-arg signature** (with target override):
```clojure
(create-spell-copy db source-object-id controller-id target-override)
```

**target-override format:** `{:player player-id}` (example: `{:player :player-2}`)

**How targets reach stack-item:**
1. Override passed to `create-spell-copy` (line 54)
2. Copy object created with `:object/zone :stack` (lines 62-68)
3. If override is provided OR inherited targets exist (lines 71-77)
4. Stack-item created with `:stack-item/targets` key set to that value (lines 79-86)
5. Stack-item position auto-assigned (line 42 in create-stack-item)

**3-arg signature** (backward compat, inherits targets):
- Queries original spell's stack-item for `:stack-item/targets` (lines 73-77)
- Uses inherited targets if no override provided

**Backward compat query** (lines 73-77):
```clojure
(d/q '[:find (pull ?e [:stack-item/targets]) .
       :in $ ?src
       :where [?e :stack-item/source ?src]]
     db-with-copy source-object-id)
```
- Finds stack-item where `:stack-item/source` matches source object ID
- Extracts `:stack-item/targets` from that stack-item

## 5. Targeting Infrastructure (engine/targeting.cljs)

**Location:** `engine/targeting.cljs:26-93`

**find-valid-targets** signature:
```clojure
(find-valid-targets db player-id target-requirement)
(find-valid-targets db player-id target-requirement chosen-targets)
```

**Return value:** Vector of valid target IDs
- For `:target/type :player` → returns player keywords (`:player-1`, `:player-2`)
- For `:target/type :object` → returns object UUIDs (`:object/id`)
- For `:target/type :any` → returns mixed vector of both

**Object targeting** (`:target/type :object`):
- Reads `:target/zone` (required)
- Reads `:target/controller` (`:self`, `:opponent`, `:any`)
- Reads `:target/criteria` (optional match predicate)
- Reads `:target/same-controller-as` (optional anchor ref for multi-target)
- Filters out shroud creatures (lines 91-92)
- Returns `->> objects-in-zone (filter matches-criteria) (remove shroud) (mapv :object/id)`

**Graveyard objects** example:
```clojure
(find-valid-targets db :player-1 
  {:target/type :object
   :target/zone :graveyard
   :target/controller :opponent
   :target/criteria [:match/types #{:card-type/land}]})
;; Returns vector of object UUIDs from opponent's graveyard
```

## 6. Effect Resolution via stack/resolve-effect-target

**Location:** `engine/stack.cljs:80-116`

**Signature:**
```clojure
(resolve-effect-target effect source-id controller stored-targets)
```

**Target resolution precedence** (in order):
1. `:effect/target-ref` (key) → look up in `stored-targets` map (cast-time targeting)
2. `:self` → replace with `source-id`
3. `:controller` → replace with `controller`
4. `:any-player` → look up `(:player stored-targets)` 
5. Default: pass effect through unchanged

**For storm copies with override targets:**
```clojure
stored-targets = {:player :player-2}
effect = {:effect/type :targeted-ability, :effect/target :any-player}
(resolve-effect-target effect source-id :player-1 stored-targets)
;; → {:effect/type :targeted-ability, :effect/target :player-2}
```

**Used in 3 places:**
1. `engine/resolution.cljs:65` — pre-resolve spell effects before execution
2. `engine/resolution.cljs:314` — pre-resolve activated ability's player target
3. `engine/mana_activation.cljs:80` — pre-resolve mana ability effects

**Secondary ref resolution** (lines 96-100):
- Also resolves `:effect/graveyard-ref` → `:effect/graveyard-id` for multi-target effects (Goblin Welder pattern)

## 7. Keyboard Context for Storm-Split

**Location:** `views/keyboard.cljs:155-182` (derive-context)

**Context name:** `:storm-split`

**How determined:**
```clojure
(and (= mechanism :accumulate)
     (= :storm-split (:selection/domain pending-selection)))
;; → :storm-split context
```

**Keymap** (lines 92-107):
```clojure
[:storm-split "1"]         :chord-start        ;; Prefix: select target 1
[:storm-split "2"]         :chord-start        ;; Prefix: select target 2
[:storm-split "1>w"]       :storm-add-all-1    ;; Chord: add remaining to target 1
[:storm-split "1>s"]       :storm-clear-1     ;; Chord: remove all from target 1
[:storm-split "1>Shift+W"] :storm-inc-1       ;; Chord: +1 to target 1
[:storm-split "1>Shift+S"] :storm-dec-1       ;; Chord: -1 from target 1
[:storm-split "2>w"]       :storm-add-all-2    ;; Similar for target 2
[:storm-split "2>s"]       :storm-clear-2
[:storm-split "2>Shift+W"] :storm-inc-2
[:storm-split "2>Shift+S"] :storm-dec-2
[:storm-split "Space"]     :confirm
```

**Action dispatch** (lines 205-228):
```clojure
(defn- storm-target-dispatch
  [pending-selection idx action-type]
  ;; idx = 0-based index into :selection/valid-targets
  ;; action-type = :add-all, :clear, :inc, :dec
  (when-let [target-id (nth valid-targets idx nil)]
    (let [allocation (:selection/allocation selection)
          current (get allocation target-id 0)
          delta (case action-type
                  :add-all (- copy-count total)    ;; remaining
                  :clear (- current)               ;; negate current
                  :inc 1
                  :dec -1)]
      [::storm-events/adjust-storm-split target-id delta])))
```

**Used in action-dispatch** (lines 382-388):
```clojure
:storm-clear-1   (storm-target-dispatch pending-selection 0 :clear)
:storm-inc-1     (storm-target-dispatch pending-selection 0 :inc)
:storm-dec-1     (storm-target-dispatch pending-selection 0 :dec)
:storm-clear-2   (storm-target-dispatch pending-selection 1 :clear)
:storm-inc-2     (storm-target-dispatch pending-selection 1 :inc)
:storm-dec-2     (storm-target-dispatch pending-selection 1 :dec)
```

**Hint system** (lines 442-449):
- Maps (context, action-keyword) → human-readable key string
- Example: `(hint-for-action :storm-split :storm-add-all-1)` → `"1 W"`

---

## Summary: Object-Targeting Extension Path

To extend storm-split for object targets (e.g., graveyard cards):

1. **Allocation shape changes:** `{:player player-id}` → `{:object object-uuid}` OR keep object IDs in a vector inside allocation
2. **Valid targets sourcing:** Use `find-valid-targets` instead of hardcoded players (matching `:card/targeting` zone spec)
3. **Copy creation:** Pass object ID in target override to `create-spell-copy`, not player ID
4. **Stack-item targets:** `:stack-item/targets {:object object-uuid}` (new format)
5. **Effect resolution:** `resolve-effect-target` with `{:object object-uuid}` in stored-targets
6. **UI stepper rows:** Render object names/types instead of player names (via subscription)
7. **Keyboard context:** Can reuse `:storm-split` context (chord bindings already support N targets)
