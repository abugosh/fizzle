# Selection Multi-Spec Quick Reference

**For**: Designing Phase 2 selection validation layer  
**Reference Doc**: `SELECTION_SYSTEM_MULTI_SPEC_INVESTIGATION.md`  
**Phase 1 Example**: `/Users/abugosh/g/fizzle/src/main/fizzle/engine/card_spec.cljs`

---

## 21 Selection Types at a Glance

```
ZONE-PICK (7)      | ACCUMULATOR (3)       | REORDER (4)         | LIBRARY OPS (3)
:zone-pick         | :storm-split          | :scry               | :tutor
:discard           | :x-mana-cost          | :peek-and-reorder   | :pile-choice
:graveyard-return  | :mana-allocation      | :order-bottom       | :peek-and-select
:hand-reveal-disc. |                       | :order-top          |
:chain-bounce      | TARGETING (3)         |                     | PRE-CAST COSTS (4)
:chain-bounce-tgt  | :cast-time-targeting  | MODAL (1)           | :exile-cards-cost
:unless-pay        | :ability-targeting    | :spell-mode         | :return-land-cost
                   | :player-target        |                     | :discard-specific
                   |                       |                     | :sacrifice-perm.
```

---

## Base Keys (Every Selection)

```clojure
{:selection/type :keyword           ; Dispatch key for execute-confirmed-selection
 :selection/player-id :keyword      ; Who is selecting
 :selection/selected ??? / #{}      ; Varies: set, vec, map, int
 :selection/spell-id :keyword       ; Spell that triggered selection
 :selection/remaining-effects []    ; Effects yet to execute
 :selection/validation :keyword     ; :exact/:at-most/:at-least-one/:exact-or-zero/:always
 :selection/auto-confirm? boolean   ; Auto-confirm on toggle
 :selection/lifecycle :keyword      ; :standard (default) / :finalized / :chaining
 :selection/on-complete map?        ; Optional: {:continuation/type ...}
}
```

---

## Pattern-Specific Keys

### Zone-Pick Pattern
- `:selection/zone` — source zone (:hand, :graveyard, etc.)
- `:selection/target-zone` — destination
- `:selection/select-count` — N cards
- `:selection/candidate-ids` — valid targets (set)
- `:selection/min-count` — for :at-most validation

### Accumulator Pattern
- `:selection/selected-x` — current X value
- `:selection/max-x` — max available
- `:selection/copy-count` — storm-split specific
- `:selection/allocation` — {player → amount}
- `:selection/valid-targets` — valid players

### Reorder Pattern
- `:selection/cards` — cards to reorder (vec)
- `:selection/top-pile` — top placement
- `:selection/bottom-pile` — bottom placement
- `:selection/selected` — {card-id → pile}

### Targeting Pattern
- `:selection/targeting-reqs` — requirements vec
- `:selection/valid-targets` — valid object IDs
- `:selection/selected` — {target-id → object-id}

### Modal Pattern
- `:selection/candidates` — mode maps from :card/modes
- `:selection/selected` — {mode-map → true}

---

## Multimethod Structure (from Phase 1)

```clojure
;; Dispatcher
(defmulti selection-type-spec :selection/type)

;; Per-type defmethods
(defmethod selection-type-spec :discard [_]
  (s/keys :req [:selection/type :selection/zone ...]
          :opt [:selection/pattern ...]))

;; 20 more defmethods...

;; Helper: minimal valid selections
(def minimal-selections
  {:discard {...}
   :tutor {...}
   ; ... one per type ...
  })

;; Multi-spec
(s/def ::selection (s/multi-spec selection-type-spec :selection/type))

;; Validation function
(defn valid-selection? [sel]
  (s/valid? ::selection sel))
```

---

## Validation Chokepoint

**File**: `/Users/abugosh/g/fizzle/src/main/fizzle/events/selection/core.cljs`  
**Line**: 426 in `confirm-selection-impl`

```clojure
(let [selection (:game/pending-selection app-db)]
  (if-not (validation/validate-selection selection)  ; <-- CHOKEPOINT
    app-db
    ...))
```

**All selections validated here**. Currently dispatches on `:selection/validation` keyword.  
**Phase 2**: Add cljs.spec multi-spec check before or instead.

---

## Hierarchy: What Derives From What

```
:zone-pick
  ├─ :discard
  ├─ :graveyard-return
  ├─ :hand-reveal-discard
  ├─ :chain-bounce
  ├─ :chain-bounce-target
  └─ :unless-pay

:accumulator
  ├─ :storm-split
  ├─ :x-mana-cost
  └─ :mana-allocation

:reorder
  ├─ :scry
  ├─ :peek-and-reorder
  ├─ :order-bottom
  └─ :order-top

:builder-declared-chain
  ├─ :tutor
  ├─ :peek-and-select
  └─ :x-mana-cost (double-registered)

:pre-cast-cost-to-targeting
  ├─ :discard-specific-cost
  ├─ :return-land-cost
  └─ :sacrifice-permanent-cost

:targeting-to-mana-allocation
  ├─ :cast-time-targeting
  └─ :ability-targeting

(no parent)
  ├─ :player-target
  ├─ :spell-mode
  ├─ :pay-x-life
  └─ :pile-choice
```

---

## Recommended Spec Groups

**Group 1: Simple Zone-Pick** (6 types)  
- `:discard`, `:graveyard-return`, `:hand-reveal-discard`, `:chain-bounce`, `:chain-bounce-target`, `:unless-pay`
- Shared: zone, target-zone, selected (set)

**Group 2: Accumulator** (3 types)  
- `:storm-split`, `:x-mana-cost`, `:mana-allocation`
- Shared: selected-x, max-x, allocation

**Group 3: Reorder** (4 types)  
- `:scry`, `:peek-and-reorder`, `:order-bottom`, `:order-top`
- Shared: cards, top-pile, bottom-pile

**Group 4: Targeting** (3 types)  
- `:cast-time-targeting`, `:ability-targeting`, `:player-target`
- Shared: valid-targets, selected (map)

**Group 5: Library Ops** (3 types)  
- `:tutor`, `:pile-choice`, `:peek-and-select`
- Shared: candidate-ids, chain-builder

**Group 6: Pre-Cast Costs** (4 types)  
- `:exile-cards-cost`, `:return-land-cost`, `:discard-specific-cost`, `:sacrifice-permanent-cost`
- Shared: zone-pick + chain-builder

**Group 7: Special** (2 types)  
- `:spell-mode`, `:pay-x-life`
- Unique specs

---

## Key Differences from Effects

1. **Effect-to-selection mapping varies**: `:return-from-graveyard` effect → `:graveyard-return` selection
2. **Lifecycle is declared** by builder, not returned by executor
3. **Chain-builder function** stored on selection (can't validate functions directly)
4. **No executor inheritance**: Each type needs its own `execute-confirmed-selection` defmethod
5. **Two parallel validation protocols**: `:selection/validation` keyword dispatch (orthogonal to multi-spec)

---

## File Locations

**Selection Builders**:
- Costs/targeting: `/Users/abugosh/g/fizzle/src/main/fizzle/events/selection/costs.cljs`
- Library: `/Users/abugosh/g/fizzle/src/main/fizzle/events/selection/library.cljs`
- Zone ops: `/Users/abugosh/g/fizzle/src/main/fizzle/events/selection/zone_ops.cljs`
- Targeting: `/Users/abugosh/g/fizzle/src/main/fizzle/events/selection/targeting.cljs`
- Storm: `/Users/abugosh/g/fizzle/src/main/fizzle/events/selection/storm.cljs`
- Modal: `/Users/abugosh/g/fizzle/src/main/fizzle/events/casting.cljs`
- Hierarchy & core: `/Users/abugosh/g/fizzle/src/main/fizzle/events/selection/core.cljs`

**Phase 1 Reference**:
- `/Users/abugosh/g/fizzle/src/main/fizzle/engine/card_spec.cljs` — Multimethod + minimal-effects pattern
- `/Users/abugosh/g/fizzle/src/test/fizzle/engine/card_spec_test.cljs` — Test structure (exact assertions, no tautologies)

**Tests**:
- `/Users/abugosh/g/fizzle/src/test/fizzle/events/selection/hierarchy_test.cljs` — Zone-pick, accumulator patterns
- `/Users/abugosh/g/fizzle/src/test/fizzle/events/selection/core_test.cljs` — Confirm/toggle implementation
- `/Users/abugosh/g/fizzle/src/test/fizzle/events/selection/lifecycle_test.cljs` — Lifecycle routing
- `/Users/abugosh/g/fizzle/src/test/fizzle/events/selection/costs_allocation_test.cljs` — Cost selections

