# Phase 3 Spec Adoption: Stack Items and Bot Actions (2026-04-03)

Complete investigation for implementing specs for stack items and bot actions, building on Phase 1 (effect specs) and Phase 2 (selection specs).

## Phase 1 & 2 Pattern Reference

### Phase 1: Effect Multi-Spec (engine/card_spec.cljs)
**File**: `/Users/abugosh/g/fizzle/src/main/fizzle/engine/card_spec.cljs`
- **Lines 15-24**: `valid-effect-types` set — 29 effect types defined
- **Lines 124-405**: `(defmulti effect-type-spec :effect/type)` with 29 defmethods
- **Lines 348-403**: `minimal-effects` map with one entry per type (minimal valid effect for each type)
- **Lines 398-402**: `(defn minimal-valid-effect [effect-type])` — query function
- **Lines 405**: `(s/def ::effect (s/multi-spec effect-type-spec :effect/type))` — composite spec
- **Validation**: `valid-card?` (line 628), `explain-card` (635), `validate-cards!` (643) — throw ex-info with card name
- **Validation entry point**: `events/init.cljs:107` calls `(card-spec/validate-cards! cards/all-cards)` at startup
- **Error handling**: Throws ex-info with `:card/name`, `:card/id`, `:explain-data` keys

**Key structural patterns**:
- No inheritance/hierarchy (no derive, every type has complete defmethod)
- Each defmethod returns `(s/keys :req [...] :opt [...])`
- Common orthogonal field `:effect/condition` marked optional on every type
- Required fields describe essence of type (e.g., `:destroy` needs `:effect/target-ref`)
- Optional fields are context-dependent (e.g., `:effect/condition` applies to all)

### Phase 2: Selection Multi-Spec (events/selection/spec.cljs)
**File**: `/Users/abugosh/g/fizzle/src/main/fizzle/events/selection/spec.cljs`
- **Lines 20-52**: Base field specs (`:selection/type`, `:selection/lifecycle`, `:selection/validation`, optional base keys)
- **Lines 59**: `(defmulti selection-type-spec :selection/type)` multimethod
- **Lines 72-681**: 34 defmethods (one per concrete type, organized in 9 groups by pattern)
- **Lines 696-999**: `minimal-valid-selections` map with one entry per type (minimal valid selection for each type)
- **Lines 1002-1006**: `(defn minimal-valid-selection [selection-type])` — query function
- **Line 687**: `(s/def ::selection (s/multi-spec selection-type-spec :selection/type))` — composite spec
- **Lines 1013-1026**: `set-pending-selection` function with validation
  - **Dev-only validation**: `when ^boolean goog.DEBUG` (dead-code eliminated in release)
  - **No throw**: Logs `js/console.error` on invalid, always returns updated app-db
  - **Chokepoint**: Single setter for `:game/pending-selection` (all builders use it)

**Key structural patterns**:
- Organized by **pattern groups** (zone-pick, accumulator, reorder, library-ops, targeting, costs, land-types, combat, other)
- Each defmethod returns `(s/keys :req [...] :opt [...])`
- Base keys shared across many types (`:selection/type`, `:selection/lifecycle`, `:selection/player-id`)
- Type-specific keys document what extra data each selection type needs
- No inheritance (every type is explicit)

---

## Stack Item Types and Field Specs

### Stack Item Creation Points (All in engine/rules.cljs or engine/resolution.cljs)

**File locations for creation**:
1. `engine/rules.cljs:305` — `:stack-item/type :storm` (storm trigger)
2. `engine/rules.cljs:321` — `:stack-item/type :spell` (casting spell)
3. `engine/triggers.cljs:80` — `:stack-item/type :storm-copy` (storm copy via create-spell-copy)
4. `engine/combat.cljs:219` — `:stack-item/type :declare-attackers` (combat phase)
5. `events/selection/combat.cljs:37` — `:stack-item/type :combat-damage` (combat resolution)
6. `events/selection/combat.cljs:41` — `:stack-item/type :declare-blockers` (blocker declaration)
7. `events/abilities.cljs:131, 152` — `:stack-item/type :activated-ability` (mana/activated ability)
8. `engine/state_based.cljs:325` — `:stack-item/type :state-check-trigger` (SBA checks)
9. `engine/turn_based.cljs:84` — `:stack-item/type :delayed-trigger` (delayed effects grant)

### Stack-Item Schema (engine/stack.cljs:29-40)

Generic create-stack-item signature: `(db, attrs) -> db`

attrs map has:
- **Required**: `:stack-item/type`, `:stack-item/controller`
- **Optional**: `:stack-item/source`, `:stack-item/effects`, `:stack-item/targets`, `:stack-item/description`, `:stack-item/is-copy`, `:stack-item/cast-mode`, `:stack-item/object-ref`, `:stack-item/position` (auto-assigned)

### Type-Specific Field Catalogs

#### `:spell` Stack Item
**Created**: `engine/rules.cljs:321-329` in `create-spell-stack-item`
```clojure
{:stack-item/type :spell
 :stack-item/controller player-id      ; player casting spell
 :stack-item/source object-id          ; object-id of spell
 :stack-item/object-ref obj-eid        ; Datascript EID reference
 :stack-item/position (auto)            ; auto-assigned by create-stack-item
 :stack-item/targets {}                 ; (optional) cast-time targeting data, key :target-ref -> resolved-id
}
```
**Resolution**: `engine/resolution.cljs:223-225` via `resolve-spell-type`
**Notes**: `:object-ref` stores EID, used to look up cast-mode and effects from object

---

#### `:storm-copy` Stack Item
**Created**: `engine/triggers.cljs:80` in `create-spell-copy`
**Fields**: Same as `:spell` (reuses `resolve-spell-type`)
**Notes**: Marked with `:object/is-copy true` on the object itself (not stack-item)

---

#### `:activated-ability` Stack Item
**Created**: `events/abilities.cljs:131, 152` in `cast-ability-handler`
```clojure
{:stack-item/type :activated-ability
 :stack-item/controller player-id      ; player activating ability
 :stack-item/source object-id          ; object-id of permanent
 :stack-item/effects [...]             ; ability effects directly (not fetched from object)
 :stack-item/targets {}                ; (optional) targeted ability targeting data
 :stack-item/description "..."         ; optional description
}
```
**Resolution**: `engine/resolution.cljs:269-299`
**Notes**: Stores effects directly (unlike spell which fetches from object); handles :any-player targeting specially

---

#### `:permanent-entered` Stack Item
**Created**: `engine/resolution.cljs:150-154` in `move-resolved-spell` dispatch
```clojure
{:stack-item/type :permanent-entered
 :stack-item/controller player-id      ; owner (controller) of permanent
 :stack-item/source object-id          ; object-id of the permanent
 :stack-item/effects [...]             ; ETB effects from card
 :stack-item/description "..."         ; (optional) description
}
```
**Resolution**: `engine/resolution.cljs:237-244`
**Notes**: Dispatched from event system, not directly created (goes through event dispatch); represents ETB trigger

---

#### `:storm` Stack Item
**Created**: `engine/rules.cljs:305-310` in `maybe-create-storm-trigger`
```clojure
{:stack-item/type :storm
 :stack-item/controller player-id      ; player whose storm is resolving
 :stack-item/source object-id          ; source object (for copy targeting)
 :stack-item/effects [{:effect/type :storm-copies
                       :effect/count copy-count}]
 :stack-item/description "Storm - create N copies"
 :stack-item/targets {}                ; (optional) will be set if targeted storm
}
```
**Resolution**: `engine/resolution.cljs:306-335`
**Notes**: Special handling: signals `:needs-storm-split true` if source has targeting (to build copy-selection first)

---

#### `:declare-attackers` Stack Item
**Created**: `engine/combat.cljs:219` (search incomplete, called during combat phase)
```clojure
{:stack-item/type :declare-attackers
 :stack-item/controller player-id      ; attacking player
 :stack-item/source (optional)          ; (likely source data, needs verification)
 :stack-item/position (auto)
}
```
**Resolution**: `engine/resolution.cljs:342-352`
**Returns**: `:needs-attackers true` + `:eligible-attackers [...]` (signals attacker selection needed)

---

#### `:declare-blockers` Stack Item
**Created**: `events/selection/combat.cljs:41` (during combat resolution)
```clojure
{:stack-item/type :declare-blockers
 :stack-item/controller player-id      ; defending player
 :stack-item/source (optional)
 :stack-item/position (auto)
}
```
**Resolution**: `engine/resolution.cljs:355-365`
**Returns**: `:needs-blockers true` + `:attackers [...]` + `:defender-id` (signals blocker selection needed)

---

#### `:combat-damage` Stack Item
**Created**: `events/selection/combat.cljs:37` (after blockers resolved)
```clojure
{:stack-item/type :combat-damage
 :stack-item/controller player-id      ; attacking player (whose creatures deal damage)
 :stack-item/source (optional)
 :stack-item/position (auto)
}
```
**Resolution**: `engine/resolution.cljs:368-371`
**Returns**: `{:db (combat/deal-combat-damage db controller)}` (executes immediately)

---

#### `:delayed-trigger` Stack Item
**Created**: `engine/turn_based.cljs:84` in `fire-delayed-effects`
```clojure
{:stack-item/type :delayed-trigger
 :stack-item/controller player-id      ; player whose delayed effect fires
 :stack-item/effects [effect]           ; single effect from grant's :delayed/effect
 :stack-item/description "Delayed trigger" ; from grant's :delayed/description or default
}
```
**Resolution**: Uses `:default` handler (lines 46-69 of resolution.cljs)
**Notes**: Created from grants with `:grant/type :delayed-effect`; fires when phase matches

---

#### `:state-check-trigger` Stack Item
**Created**: `engine/state_based.cljs:325` (during SBA checking)
```clojure
{:stack-item/type :state-check-trigger
 :stack-item/controller player-id      ; (likely player checking/affected)
 :stack-item/effects [...]             ; SBA effects
 :stack-item/description "..."         ; (optional) description
}
```
**Resolution**: Uses `:default` handler
**Notes**: Fires when state-based conditions are met (power >= threshold, etc.)

---

## Stack Item Spec Design (Phase 3 Proposal)

**File location**: Create `engine/stack_spec.cljs` (parallel to card_spec.cljs)

**Structure** (following Phase 1 pattern):

```clojure
(ns fizzle.engine.stack-spec
  "Stack item validation using cljs.spec.alpha.
   
   Validates stack-item entities at the stack/create-stack-item chokepoint.
   Multi-spec dispatches on :stack-item/type. Each defmethod declares required
   and optional keys for that type.
   
   Validation is dev-only (goog.DEBUG), throws ex-info on invalid stack-item."
  (:require [cljs.spec.alpha :as s]))

;; Base field specs (all stack items have these)
(s/def :stack-item/type keyword?)
(s/def :stack-item/controller keyword?)        ; player-id
(s/def :stack-item/position nat-int?)          ; auto-assigned, LIFO order
(s/def :stack-item/source (s/or :kw keyword? :uuid uuid?))  ; object-id
(s/def :stack-item/object-ref int?)            ; Datascript EID
(s/def :stack-item/effects vector?)            ; vector of effects
(s/def :stack-item/targets map?)               ; cast-time targeting data
(s/def :stack-item/description string?)        ; optional description
(s/def :stack-item/is-copy boolean?)           ; only on objects, not stack-items
(s/def :stack-item/cast-mode map?)             ; on spell stack-items
(s/def :stack-item/chosen-x int?)              ; on X-cost spells

;; Multi-spec
(defmulti stack-item-type-spec :stack-item/type)

;; Defmethods (9 types total)
(defmethod stack-item-type-spec :spell [_]
  (s/keys :req [:stack-item/type :stack-item/controller]
          :opt [:stack-item/source :stack-item/object-ref 
                :stack-item/targets :stack-item/description
                :stack-item/position]))

(defmethod stack-item-type-spec :spell-copy [_]
  (s/keys :req [:stack-item/type :stack-item/controller]
          :opt [:stack-item/source :stack-item/object-ref
                :stack-item/targets :stack-item/description
                :stack-item/position]))

;; ... (7 more defmethods)

(s/def ::stack-item (s/multi-spec stack-item-type-spec :stack-item/type))

;; minimal-valid-stack-items map
(def minimal-valid-stack-items {...})

(defn minimal-valid-stack-item [stack-item-type]
  (get minimal-valid-stack-items stack-item-type))

;; Validation function
(defn validate-stack-item!
  "Validate a stack-item entity. Throws ex-info on invalid."
  [stack-item]
  (when-not (s/valid? ::stack-item stack-item)
    (throw (ex-info (str "Invalid stack-item type " 
                        (:stack-item/type stack-item)
                        ": " (s/explain-str ::stack-item stack-item))
                    {:stack-item/type (:stack-item/type stack-item)
                     :explain-data (s/explain-data ::stack-item stack-item)}))))
```

---

## Bot Action Types and Field Specs

### Bot Action Creation Points

**File**: `bots/decisions.cljs:96-132` — sole source of bot actions
**Function**: `bot-decide-action` (lines 96-132)

**Returns** one of:
1. `{:action :pass}` — bot passes priority
2. `{:action :cast-spell :object-id oid :target tid :player-id pid :tap-sequence [...]}`
3. (implied) `:action :play-land` via `bot-phase-action` pathway

### Bot Action Types

#### `:pass` Action
**Created**: `bots/decisions.cljs:110, 113, 127`
```clojure
{:action :pass}
```
**Consumed**: `events/director.cljs:98, 110, 131` (checked with `(not= :cast-spell (:action action))`)
**Meaning**: Bot gives up priority / passes on decision

---

#### `:cast-spell` Action
**Created**: `bots/decisions.cljs:128-132`
```clojure
{:action :cast-spell
 :object-id oid              ; object-id of card to cast
 :target tid                 ; optional target player-id (from decision map)
 :player-id pid              ; player whose turn it is
 :tap-sequence [             ; calculated mana tap sequence
   {:object-id oid :mana-color :red}
   {:object-id oid2 :mana-color :generic}
 ]
}
```
**Consumed**: `events/director.cljs:109-136` (branch based on `:action :cast-spell`)
**Flow**:
1. Get archetype's priority decision via `bot/bot-priority-decision`
2. If decision has `:object-id`, look up card's mana cost
3. Allocate mana via `find-tap-sequence`
4. Check mana is payable
5. Return action or `:action :pass` if can't pay

**Tap sequence structure**:
- Vector of `{:object-id land-id :mana-color color-keyword}`
- Colored mana allocated first (from lands producing that color)
- Generic mana allocated from any remaining lands (first color in that land's produces)

---

#### `:play-land` Action (Implicit)
**Created**: Via `bots/protocol.cljs:37-45` (`bot-phase-action`) + `bots/definitions.cljs`
**Data structure**: `{:action :play-land}` (from bot-phase-action return)
**Consumed**: `events/director.cljs:105-107` (if land-id found, returns action-type :play-land)
**Flow**:
1. Check phase has a phase-action in bot spec (e.g., `:main1 :play-land`)
2. Find eligible land via `find-bot-land-to-play`
3. Play land via `lands/play-land`

---

### Bot Decision Protocol (bots/protocol.cljs)

**Functions**:
- `bot-priority-decision(archetype, context)` — returns `:pass` or `{:action ... :object-id ... :target ...}`
- `bot-phase-action(archetype, phase, db, player-id)` — returns `{:action :play-land}` or `{:action :pass}`

**Context for bot-priority-decision**:
```clojure
{:db game-db
 :player-id player-id}
```

---

## Bot Action Spec Design (Phase 3 Proposal)

**File location**: Create `bots/action_spec.cljs` (new file)

**Structure**:

```clojure
(ns fizzle.bots.action-spec
  "Bot action validation using cljs.spec.alpha.
   
   Validates bot action maps returned by bot-decide-action and bot-phase-action.
   Multi-spec dispatches on :action. Each defmethod declares required and
   optional keys for that action type.
   
   Validation is dev-only (goog.DEBUG), logs console.error on invalid."
  (:require [cljs.spec.alpha :as s]))

;; Base field specs
(s/def :action/type keyword?)
(s/def :action/object-id (s/or :kw keyword? :uuid uuid?))
(s/def :action/target keyword?)
(s/def :action/player-id keyword?)

;; Tap sequence specs
(s/def :tap/object-id (s/or :kw keyword? :uuid uuid?))
(s/def :tap/mana-color keyword?)
(s/def :tap-entry
  (s/keys :req [:tap/object-id :tap/mana-color]))
(s/def :action/tap-sequence (s/coll-of :tap-entry :kind vector?))

;; Multi-spec
(defmulti action-type-spec :action)

(defmethod action-type-spec :pass [_]
  (s/keys :req [:action]))

(defmethod action-type-spec :cast-spell [_]
  (s/keys :req [:action :action/object-id :action/player-id]
          :opt [:action/target :action/tap-sequence]))

(defmethod action-type-spec :play-land [_]
  (s/keys :req [:action]))

;; Top-level action spec
(s/def ::bot-action (s/multi-spec action-type-spec :action))

;; minimal-valid-actions
(def minimal-valid-actions
  {:pass {:action :pass}
   :cast-spell {:action :cast-spell 
                :action/object-id :card-id
                :action/player-id :player-1
                :action/tap-sequence []}
   :play-land {:action :play-land}})

(defn minimal-valid-action [action-type]
  (get minimal-valid-actions action-type))

;; Validation (dev-only, logs console.error)
(defn validate-bot-action
  "Validate a bot action map. Returns true if valid, false otherwise."
  [action]
  (when ^boolean goog.DEBUG
    (when-not (s/valid? ::bot-action action)
      (js/console.error
        "Invalid bot action:"
        (s/explain-str ::bot-action action))
      false)))
```

---

## Implementation Checklist

### Phase 3 Tasks

1. **Create `/Users/abugosh/g/fizzle/src/main/fizzle/engine/stack_spec.cljs`**
   - Define 9 stack-item type defmethods
   - Create minimal-valid-stack-items map
   - Add validation function
   - Lines: ~200-250

2. **Create `/Users/abugosh/g/fizzle/src/main/fizzle/bots/action_spec.cljs`**
   - Define 3 action type defmethods
   - Create minimal-valid-actions map
   - Add validation function
   - Lines: ~80-120

3. **Update `engine/stack.cljs:create-stack-item`**
   - Add validation call (dev-only)
   - No throw (log console.error, allow invalid)
   - Lines: ~29-40 (add 3-5 lines)

4. **Update `bots/decisions.cljs:bot-decide-action`**
   - Add validation before returning action
   - Dev-only, log console.error
   - Lines: ~96-132 (add 2-3 lines)

5. **Create tests**
   - `test/fizzle/engine/stack_spec_test.cljs`
   - `test/fizzle/bots/action_spec_test.cljs`
   - Verify each minimal-valid-* passes validation
   - Lines: ~100-150 each

---

## Key Design Decisions

1. **No inheritance**: Follow Phase 1 pattern (no derive, every type explicit)
2. **Dev-only validation**: Use `goog.DEBUG`, dead-code eliminated in prod
3. **Error handling**:
   - Stack items: throw ex-info (chokepoint is single function)
   - Bot actions: log console.error (actions come from many contexts)
   - Selections: log console.error (already established in Phase 2)
4. **Minimal-valid pattern**: One entry per type for tests and spec exercise
5. **Chokepoint locations**:
   - Stack items: `engine/stack.cljs:create-stack-item`
   - Bot actions: `bots/decisions.cljs:bot-decide-action` (and phase-action path)
