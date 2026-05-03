# Selection System Multi-Spec Validation Layer Investigation

**Scope**: Small (6 files, focused investigation)  
**Date**: 2026-04-03  
**Purpose**: Understand selection system structure to design Phase 2 multi-spec validation (parallel to Phase 1 effect multi-spec)

---

## 1. ALL 21+ SELECTION TYPES

Complete catalog from codebase exploration:

### **Core Selection Types** (18 production types)

| Type | File | Builder | Category | Notes |
|------|------|---------|----------|-------|
| `:zone-pick` | selection/core.cljs | `build-selection-for-effect :zone-pick` (line 148) | Zone-pick pattern | Generic executor at line 181 |
| `:discard` | selection/core.cljs | Inherits from :zone-pick via hierarchy (line 42) | Zone-pick pattern | Custom executor exists in zone_ops |
| `:graveyard-return` | selection/core.cljs | Inherits from :zone-pick; config at line 128 | Zone-pick pattern | Mapped from `:return-from-graveyard` effect |
| `:hand-reveal-discard` | selection/zone_ops.cljs | Line 40 | Zone-pick pattern | Opponent discard with reveal |
| `:chain-bounce` | selection/zone_ops.cljs | Line 116 | Zone-pick pattern | Chain of Vapor mechanic |
| `:chain-bounce-target` | selection/zone_ops.cljs | Line 151 | Zone-pick pattern | Chaining selection for chain-bounce |
| `:unless-pay` | selection/zone_ops.cljs | Line 240 | Zone-pick pattern | "Unless you pay X" mana cost |
| `:tutor` | selection/library.cljs | Line 54 | Builder-declared-chain | Derives at core.cljs:62 |
| `:pile-choice` | selection/library.cljs | Line 91, 103 | Chaining intermediate | Post-tutor pile selection |
| `:scry` | selection/library.cljs | Line 141 | Reorder pattern | Derives at core.cljs:50 |
| `:peek-and-select` | selection/library.cljs | Line 188 | Builder-declared-chain | Derives at core.cljs:63 |
| `:peek-and-reorder` | selection/library.cljs | Line 334 | Reorder pattern | Derives at core.cljs:51 |
| `:order-bottom` | selection/library.cljs | Line 243 | Reorder pattern | Derives at core.cljs:52 |
| `:order-top` | selection/library.cljs | Line 266 | Reorder pattern | Derives at core.cljs:53 |
| `:storm-split` | selection/storm.cljs | Line 45 | Accumulator pattern | Derives at core.cljs:46 |
| `:x-mana-cost` | selection/costs.cljs | Line 286 | Accumulator pattern | Derives at core.cljs:47 |
| `:mana-allocation` | selection/costs.cljs | Line 335 & 401 | Accumulator pattern | Derives at core.cljs:48 |
| `:spell-mode` | events/casting.cljs | Line 190 | Modal casting | Non-pattern type |

### **Pre-Cast Cost Chain Types** (4 types - require targeting after)

| Type | File | Builder | Derives From | Notes |
|------|------|---------|--------------|-------|
| `:exile-cards-cost` | selection/costs.cljs | Line 111 | `:pre-cast-cost-to-targeting` (core.cljs:55) | Chains to targeting |
| `:return-land-cost` | selection/costs.cljs | Line 150 | `:pre-cast-cost-to-targeting` (core.cljs:56) | Chains to targeting |
| `:discard-specific-cost` | selection/costs.cljs | Line 187 | `:pre-cast-cost-to-targeting` (core.cljs:55) | Chains to targeting |
| `:sacrifice-permanent-cost` | selection/costs.cljs | Line 233 | `:pre-cast-cost-to-targeting` (core.cljs:57) | Chains to targeting |

### **Targeting Types** (3 types - cast-time and ability)

| Type | File | Builder | Derives From | Notes |
|------|------|---------|--------------|-------|
| `:cast-time-targeting` | selection/costs.cljs | Line 434 | `:targeting-to-mana-allocation` (core.cljs:59) | Chains to mana-allocation |
| `:ability-targeting` | selection/costs.cljs | Line 463 | `:targeting-to-mana-allocation` (core.cljs:59) | Ability casting |
| `:player-target` | selection/targeting.cljs | Line 30 | (no derive) | :any-player effect dispatch |

### **Additional/Test Types** (5+ types)

| Type | Location | Status | Notes |
|------|----------|--------|-------|
| `:pay-x-life` | selection/costs.cljs:369 | Test/special | Pay X life cost selection |
| `:test-standard` | selection tests | Test-only | For validating :selection/lifecycle |
| `:test-finalized` | selection tests | Test-only | For validating :selection/lifecycle |
| `:test-chaining` | selection tests | Test-only | For validating :selection/lifecycle |
| `:test-chaining-nil` | selection tests | Test-only | For conditional chaining |

---

## 2. SELECTION BUILDER OUTPUT SHAPES

### **Base Keys** (present on every selection map)

```clojure
{:selection/type           keyword?        ; Dispatch key for execute-confirmed-selection
 :selection/player-id      keyword?        ; Player who is making selection
 :selection/selected       (set|vec|any)   ; User selections (varies per type)
 :selection/spell-id       keyword?        ; ID of spell that triggered selection
 :selection/remaining-effects []           ; Effects still to execute
 :selection/validation     keyword?        ; :exact/:at-most/:at-least-one/:exact-or-zero/:always
 :selection/auto-confirm?  boolean?        ; Auto-confirm on toggle (data-driven)
 :selection/lifecycle      keyword?        ; :standard/:finalized/:chaining (declared by builder)
 
 ; Optional context for continuation
 :selection/on-complete    map?            ; {:continuation/type ...} (set by casting/abilities)}
```

**Location**: Lines 97-107 (core.cljs default builder), lines 160-174 (zone-pick builder)

### **Zone-Pick Pattern Keys** (for :discard, :graveyard-return, etc.)

```clojure
{:selection/pattern        :zone-pick      ; View dispatch key
 :selection/zone           keyword?        ; Source zone (:hand, :graveyard, etc.)
 :selection/card-source    keyword?        ; :hand or :zone (if zone, filter candidates)
 :selection/target-zone    keyword?        ; Destination zone
 :selection/select-count   int?            ; N cards to select
 :selection/candidate-ids  set?            ; Valid target object IDs (when card-source :zone)
 :selection/min-count      int?            ; Optional: minimum cards (for :at-most validation)
}
```

**Location**: Lines 148-174 (zone-pick builder in core.cljs)

### **Accumulator Pattern Keys** (for :storm-split, :x-mana-cost, :mana-allocation)

```clojure
{:selection/pattern        :accumulator    ; View dispatch key
 :selection/selected-x     int?            ; Current X value (for x-mana-cost, storm-split)
 :selection/max-x          int?            ; Max X available
 :selection/copy-count     int?            ; Storm split specific: number of copies
 :selection/allocation     map?            ; Player → amount (storm-split)
 :selection/valid-targets  vec?            ; Valid players for allocation
 :selection/source-object-id keyword?      ; For storm-split: source spell ID
 :selection/controller-id  keyword?        ; Controller of source
 :selection/stack-item-eid int?            ; Storm SI entity ID for removal
}
```

**Location**: Lines 286-401 (costs.cljs for builders), storm.cljs line 45

### **Reorder Pattern Keys** (for :scry, :peek-and-reorder, :order-bottom)

```clojure
{:selection/pattern        :reorder        ; View dispatch key
 :selection/cards          vec?            ; Cards to reorder (with IDs)
 :selection/top-pile       vec?            ; Cards to place on top
 :selection/bottom-pile    vec?            ; Cards to place on bottom
 :selection/selected       {}              ; Pile assignments by card
 :selection/can-shuffle?   boolean?        ; Whether shuffle is allowed after
 :selection/may-shuffle?   boolean?        ; Peek-and-reorder: optional shuffle
}
```

**Location**: Lines 141, 243, 266, 334 (library.cljs)

### **Modal Selection Keys** (for :spell-mode)

```clojure
{:selection/type           :spell-mode
 :selection/lifecycle      :finalized      ; Modal selection always finalizes
 :selection/player-id      keyword?        ; Casting player
 :selection/object-id      keyword?        ; Spell ID
 :selection/candidates     vec?            ; Mode maps from :card/modes
 :selection/selected       {}              ; {mode-map → true}
 :selection/select-count   1               ; Exactly one mode
 :selection/validation     :exact
 :selection/auto-confirm?  true            ; Auto-confirm after selection
}
```

**Location**: Lines 184-206 (casting.cljs)

### **Targeting Selection Keys** (for :cast-time-targeting, :player-target)

```clojure
{:selection/type           :cast-time-targeting | :player-target
 :selection/targeting-reqs vec?            ; Targeting requirements from card
 :selection/selected       {}              ; {target-id → object-id}
 :selection/valid-targets  vec?            ; Valid target object IDs
 :selection/select-count   int?            ; Number of targets required
 :selection/validation     :exact | :at-most
 :selection/auto-confirm?  false
 :selection/remaining-effects [] | :mana-allocation  ; Next step after targeting
}
```

**Location**: Lines 25-65 (targeting.cljs), lines 434-485 (costs.cljs)

### **Cost Chaining Keys** (for :exile-cards-cost, :return-land-cost, etc.)

```clojure
{:selection/type           :exile-cards-cost | :discard-specific-cost | etc.
 :selection/pattern        :zone-pick      ; These are zone-pick pattern
 :selection/lifecycle      :chaining       ; Will chain to targeting
 :selection/chain-builder  fn?             ; Function to build next selection
 :selection/candidates     set?            ; Cards in source zone
 :selection/select-count   int?
 :selection/remaining-effects [] | :cast-time-targeting  ; Override for chaining
}
```

**Location**: Lines 111-171 (costs.cljs - exotic/return-land), lines 187-285 (discard/sacrifice)

---

## 3. THE CHOKEPOINT: `:game/pending-selection` Set Points

### **Primary Setters** (7 distinct locations)

| Location | File | Line | Context | Pattern |
|----------|------|------|---------|---------|
| 1 | events/casting.cljs | 162 | Pre-cast pipeline result | `(assoc :game/pending-selection (:selection result))` |
| 2 | events/casting.cljs | 263 | Post-cast evaluation | `(assoc :game/pending-selection ...)` |
| 3 | events/selection/core.cljs | 368 | Standard lifecycle chain | `(assoc :game/pending-selection next-sel)` |
| 4 | events/selection/core.cljs | 403 | Chaining lifecycle | `(assoc :game/pending-selection chained-sel)` |
| 5 | events/resolution.cljs | 88 | Resolve-one-item entry | `(assoc :game/db ... :game/pending-selection sel)` |
| 6 | events/resolution.cljs | 118, 137 | Resolution result handling | `(assoc :game/pending-selection ...)` |
| 7 | events/abilities.cljs | 299 | Ability activation | `(assoc :game/pending-selection ...)` |

### **Single Primary Chokepoint for Validation**

**YES—there is ONE chokepoint**: `confirm-selection-impl` (selection/core.cljs:408)

```clojure
(defn confirm-selection-impl
  "Shared wrapper for all selection confirmations.
   1. Validates selection — returns app-db unchanged on failure (no exceptions)
   ...
   Returns updated app-db."
  [app-db]
  (let [selection (:game/pending-selection app-db)]
    (if-not (validation/validate-selection selection)  ; <-- LINE 426: THE CHOKEPOINT
      app-db
      ...)))
```

**Validation Dispatcher**: `engine/validation.cljs` (line not yet found, but called at line 426)

This is where `validate-selection` multimethod dispatches on `:selection/validation` keyword.

### **Secondary Setters** (not chokepoints, just data mutations)

- `toggle-selection-impl` (line 449): Updates `:selection/selected` but doesn't set :game/pending-selection itself
- `selection/costs.cljs` lines 717, 724, 727: Increment/decrement X, update pending-selection's :selection/selected-x
- `events/director.cljs` multiple: Updates :game/pending-selection in director loop

---

## 4. SELECTION HIERARCHY

### **Hierarchy Structure** (selection/core.cljs, lines 31-64)

```clojure
(def selection-hierarchy
  (-> (make-hierarchy)
      ;; Zone-pick: select N cards from a zone
      (derive :discard :zone-pick)
      (derive :return-from-graveyard :zone-pick)
      (derive :graveyard-return :zone-pick)
      
      ;; Accumulator: distribute/increment a value
      (derive :storm-split :accumulator)
      (derive :x-mana-cost :accumulator)
      (derive :mana-allocation :accumulator)
      
      ;; Reorder: sort/assign cards into piles
      (derive :scry :reorder)
      (derive :peek-and-reorder :reorder)
      (derive :order-bottom :reorder)
      (derive :order-top :reorder)
      
      ;; Pre-cast cost chains: cost selections → targeting
      (derive :discard-specific-cost :pre-cast-cost-to-targeting)
      (derive :return-land-cost :pre-cast-cost-to-targeting)
      (derive :sacrifice-permanent-cost :pre-cast-cost-to-targeting)
      
      ;; Targeting chains: targeting selections → mana-allocation
      (derive :cast-time-targeting :targeting-to-mana-allocation)
      (derive :ability-targeting :targeting-to-mana-allocation)
      
      ;; Builder-declared chains: store chain-builder fn
      (derive :tutor :builder-declared-chain)
      (derive :peek-and-select :builder-declared-chain)
      (derive :x-mana-cost :builder-declared-chain)))
```

### **Multimethod Dispatch Keys**

| Multimethod | Dispatch Key | Hierarchy Used | Lines |
|-------------|--------------|-----------------|-------|
| `execute-confirmed-selection` | `:selection/type` | YES (line 83) | 71-83 |
| `build-selection-for-effect` | `:effect/type` or `:player-target` | YES (line 94) | 86-94 |
| `build-chain-selection` | `:selection/type` | YES (line 228) | 221-228 |
| `apply-continuation` | `:continuation/type` | NO | 195-209 |

### **Key Hierarchy Insights**

1. **Effect-to-selection mapping differs**: `:return-from-graveyard` effect → `:graveyard-return` selection (lines 39, 128-133)
2. **Both sides must derive**: Effect type AND selection type must both derive from parent for routing to work
3. **Generic builders use hierarchy**: `:zone-pick` builder handles `:discard`, `:graveyard-return`, etc. via hierarchy
4. **Custom executors take precedence**: If `:discard` executor exists, it runs even though `:discard :zone-pick`
5. **No executor inheritance**: Each `:selection/type` needs its own executor defmethod or inherits generic

---

## 5. PHASE 1 PATTERN: EFFECT MULTI-SPEC REFERENCE

**File**: `/Users/abugosh/g/fizzle/src/main/fizzle/engine/card_spec.cljs`  
**Test**: `/Users/abugosh/g/fizzle/src/test/fizzle/engine/card_spec_test.cljs`

### **Structure**

```clojure
;; Step 1: Define multimethod dispatcher (line 124)
(defmulti effect-type-spec :effect/type)

;; Step 2: Define per-type defmethods (lines 129-342)
(defmethod effect-type-spec :mill [_]
  (s/keys :req [:effect/type :effect/amount]
          :opt [:effect/target :effect/target-ref :effect/condition]))

(defmethod effect-type-spec :draw [_]
  (s/keys :req [:effect/type :effect/amount]
          :opt [:effect/target :effect/condition]))

;; ... 27 more defmethods ...

;; Step 3: Define minimal-valid-effect helper (lines 348-402)
(def minimal-effects
  {:add-mana {:effect/type :add-mana :effect/mana {:black 1}}
   :mill {:effect/type :mill :effect/amount 1}
   ; ... one per type ...
  })

(defn minimal-valid-effect [effect-type]
  (get minimal-effects effect-type))

;; Step 4: Use multi-spec (line 405)
(s/def ::effect (s/multi-spec effect-type-spec :effect/type))

;; Step 5: Validation function (elsewhere)
(defn valid-card? [card]
  (s/valid? ::card card))
```

### **Test Pattern** (card_spec_test.cljs)

```clojure
;; Exact value assertions (not tautological is? calls)
(is (= true (card-spec/valid-card? card)))
(is (= false (card-spec/valid-card? invalid-card)))

;; Per-type exercises
(doseq [effect-type effect-types]
  (is (s/valid? ::effect (minimal-valid-effect effect-type))))

;; All production cards pass
(doseq [card cards/all-cards]
  (is (= true (card-spec/valid-card? card))))
```

### **Critical Details**

1. **:effect/type is required** on every effect defmethod
2. **:effect/condition is optional** on every type (orthogonal)
3. **Minimal examples** for testing (minimal-effects map)
4. **Exact assertions** in tests (not `some?` or type predicates)
5. **Production card validation** at load time (validate-cards! called in core.cljs)
6. **No :default method** — all types explicitly listed

---

## 6. SELECTION SPEC DESIGN IMPLICATIONS

### **Phase 2 Structure** (for selection multi-spec, parallel to effect multi-spec)

```clojure
;; engine/selection_spec.cljs (NEW)

;; Multimethod dispatcher
(defmulti selection-type-spec :selection/type)

;; Base keys (present on every selection)
(def selection-base-keys
  [:selection/type :selection/player-id :selection/selected
   :selection/spell-id :selection/remaining-effects
   :selection/validation :selection/auto-confirm?])

;; Minimal selections for every type
(def minimal-selections
  {:zone-pick {...}
   :discard {...}
   :storm-split {...}
   :spell-mode {...}
   ; ... 18 production types ...
  })

;; Per-type specs
(defmethod selection-type-spec :zone-pick [_]
  (s/keys :req [:selection/type :selection/zone
                :selection/target-zone ...]
          :opt [:selection/pattern ...]))

; ... 17 more defmethods ...

;; Multi-spec
(s/def ::selection (s/multi-spec selection-type-spec :selection/type))

;; Validation function
(defn valid-selection? [sel]
  (s/valid? ::selection sel))
```

### **Integration Point**

The validation chokepoint `confirm-selection-impl` (core.cljs:426) calls:
```clojure
(if-not (validation/validate-selection selection)
  app-db
  ...)
```

This should dispatch to the new multi-spec:
```clojure
(defmulti validate-selection :selection/validation)  ; Current design

; OR replace with:

(defn validate-selection [selection]
  (and (s/valid? ::selection selection)  ; Type structure validation
       (validate-count-constraints selection)))  ; Count/range validation
```

---

## 7. RECOMMENDED SPEC GROUPING

Based on Phase 1 pattern and selection structure:

### **Group 1: Simple Zone-Pick** (6 types)
- `:discard`, `:graveyard-return`, `:hand-reveal-discard`
- `:chain-bounce`, `:chain-bounce-target`, `:unless-pay`
- **Shared fields**: `:selection/zone`, `:selection/target-zone`, `:selection/selected` (set)

### **Group 2: Accumulator** (3 types)
- `:storm-split`, `:x-mana-cost`, `:mana-allocation`
- **Shared fields**: `:selection/selected-x`, `:selection/max-x`, `:selection/allocation`

### **Group 3: Reorder** (4 types)
- `:scry`, `:peek-and-reorder`, `:order-bottom`, `:order-top`
- **Shared fields**: `:selection/cards`, `:selection/top-pile`, `:selection/bottom-pile`

### **Group 4: Library Ops** (3 types)
- `:tutor`, `:pile-choice`, `:peek-and-select`
- **Shared fields**: `:selection/candidate-ids` or `:selection/cards`, `:selection/chain-builder`

### **Group 5: Targeting** (3 types)
- `:cast-time-targeting`, `:ability-targeting`, `:player-target`
- **Shared fields**: `:selection/targeting-reqs`, `:selection/valid-targets`, `:selection/selected` (map)

### **Group 6: Pre-Cast Costs** (4 types)
- `:exile-cards-cost`, `:return-land-cost`, `:discard-specific-cost`, `:sacrifice-permanent-cost`
- **Shared fields**: Zone-pick + `:selection/chain-builder`

### **Group 7: Modal** (1 type)
- `:spell-mode`
- **Unique fields**: `:selection/candidates` (mode maps)

### **Group 8: Special/Test** (5+ types)
- `:pay-x-life`, test-only types
- **Minimal specs**

---

## 8. CRITICAL FINDINGS

### **No Generic Executor Inheritance**
- `:zone-pick` has a generic executor (line 181)
- `:discard` derives from `:zone-pick` but may have its own executor
- Other types must explicitly implement `execute-confirmed-selection`
- **Implication**: Spec must validate each type's custom fields, not just base

### **Chain Selection Builder Pattern**
- `:selection/chain-builder` function stored on selection map (line 240)
- Used by `build-chain-selection :builder-declared-chain` (line 238)
- **Implication**: Spec cannot validate functions, only presence of key when needed

### **Effect-to-Selection Mapping Gap**
- `:return-from-graveyard` effect produces `:graveyard-return` selection
- Config map at line 128-133 handles the mapping
- **Implication**: Spec should mirror effect-type-spec structure but with selection-specific fields

### **Lifecycle is Declaration, Not Executor Output**
- `:selection/lifecycle` (:standard/:finalized/:chaining) declared by builder
- Not returned by executor
- **Implication**: Spec must require `:selection/lifecycle` on every selection

### **No Validation Method Overriding**
- Current validation dispatches on `:selection/validation` keyword (:exact/:at-most/etc.)
- Phase 2 multi-spec cannot replace this—should complement it
- **Implication**: Call both checks in confirm-selection-impl (line 426)

---

## File Paths Summary

### **Core Selection System**
- `/Users/abugosh/g/fizzle/src/main/fizzle/events/selection/core.cljs` — Hierarchy, multimethods, confirm-selection-impl
- `/Users/abugosh/g/fizzle/src/main/fizzle/events/selection/costs.cljs` — Costs, targeting, x-mana, mana-allocation
- `/Users/abugosh/g/fizzle/src/main/fizzle/events/selection/library.cljs` — Tutor, scry, peek-and-select/reorder, order-bottom/top
- `/Users/abugosh/g/fizzle/src/main/fizzle/events/selection/zone_ops.cljs` — Chain-bounce, hand-reveal-discard, unless-pay
- `/Users/abugosh/g/fizzle/src/main/fizzle/events/selection/targeting.cljs` — Player-target, targeting
- `/Users/abugosh/g/fizzle/src/main/fizzle/events/selection/storm.cljs` — Storm-split
- `/Users/abugosh/g/fizzle/src/main/fizzle/events/casting.cljs` — Spell-mode selection

### **Phase 1 Reference (Effect Multi-Spec)**
- `/Users/abugosh/g/fizzle/src/main/fizzle/engine/card_spec.cljs` — Multimethod structure, minimal-effects, validation
- `/Users/abugosh/g/fizzle/src/test/fizzle/engine/card_spec_test.cljs` — Test patterns

### **Tests (Selection Types)**
- `/Users/abugosh/g/fizzle/src/test/fizzle/events/selection/hierarchy_test.cljs` — Zone-pick, accumulator, reorder tests
- `/Users/abugosh/g/fizzle/src/test/fizzle/events/selection/core_test.cljs` — Confirm-selection-impl, toggle-selection-impl
- `/Users/abugosh/g/fizzle/src/test/fizzle/events/selection/lifecycle_test.cljs` — Lifecycle routing
- `/Users/abugosh/g/fizzle/src/test/fizzle/events/selection/costs_allocation_test.cljs` — Cost selections
- `/Users/abugosh/g/fizzle/src/test/fizzle/events/selection/storm_split_test.cljs` — Storm split

### **Chokepoint**
- `/Users/abugosh/g/fizzle/src/main/fizzle/events/selection/core.cljs` **line 426** — `confirm-selection-impl` validation call
- `/Users/abugosh/g/fizzle/src/main/fizzle/engine/validation.cljs` — `validate-selection` multimethod (current, not yet read)

