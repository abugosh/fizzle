---
name: cljs.spec Adoption Investigation — Data Contract Boundaries
description: Complete audit of data shapes, validation, and spec coverage for cljs.spec adoption brainstorm
type: reference
---

# cljs.spec Adoption Investigation — Data Contract Boundaries

**Investigated**: 2026-04-02  
**Status**: Existing spec infrastructure found; high-friction boundaries identified

---

## 1. Card Specification

### File: `/Users/abugosh/g/fizzle/src/main/fizzle/engine/card_spec.cljs` (373 lines)

**Status**: ✅ **Comprehensive cljs.spec already exists**

This file defines full card validation using `cljs.spec.alpha` with:
- 25+ enums for effect types, cost types, ability types, conditions, restrictions
- Mana cost spec (lines 66–74): `::mana-cost` validates key/value pairs
- Effect spec (lines 110–122): `::effect` with 23 optional keys, dispatches on `:effect/type`
- Card spec (lines 330–340): Top-level `::card` with cross-field validation
  - Requires: `:card/id`, `:card/name`, `:card/cmc`, `:card/mana-cost`, `:card/colors`, `:card/types`, `:card/text`
  - Optional: 16 keys including `:card/effects`, `:card/abilities`, `:card/etb-effects`, `:card/triggers`, etc.
  - Custom predicate `creature-has-pt?` (lines 321–327) enforces creature cards have `:card/power` and `:card/toughness`
  - Custom predicate `valid-static-ability?` (lines 254–262) cross-validates static abilities

**Validation entry**: Called in `/Users/abugosh/g/fizzle/src/main/fizzle/events/init.cljs:107`
```clojure
(card-spec/validate-cards! cards/all-cards)
```
Runs at game initialization. Throws `ex-info` on first invalid card.

### Card Definition Shapes

**Dark Ritual** (`cards/black/dark_ritual.cljs`):
```clojure
{:card/id :dark-ritual
 :card/name "Dark Ritual"
 :card/cmc 1
 :card/mana-cost {:black 1}
 :card/colors #{:black}
 :card/types #{:instant}
 :card/text "Add {B}{B}{B}."
 :card/effects [{:effect/type :add-mana
                 :effect/mana {:black 3}}]}
```

**Cunning Wish** (`cards/blue/cunning_wish.cljs`):
```clojure
{:card/id :cunning-wish
 :card/name "Cunning Wish"
 :card/cmc 3
 :card/mana-cost {:colorless 2 :blue 1}
 :card/colors #{:blue}
 :card/types #{:instant}
 :card/text "..."
 :card/effects [{:effect/type :exile-self}
                {:effect/type :tutor
                 :effect/source-zone :sideboard
                 :effect/criteria {:match/types #{:instant}}
                 :effect/target-zone :hand
                 :effect/shuffle? false}]}
```

**City of Brass** (`cards/lands/city_of_brass.cljs`):
```clojure
{:card/id :city-of-brass
 :card/name "City of Brass"
 :card/cmc 0
 :card/mana-cost {}
 :card/colors #{}
 :card/types #{:land}
 :card/text "..."
 :card/triggers [{:trigger/type :becomes-tapped
                  :trigger/description "deals 1 damage to you"
                  :trigger/effects [{:effect/type :deal-damage
                                     :effect/amount 1
                                     :effect/target :controller}]}]
 :card/abilities [{:ability/type :mana
                   :ability/cost {:tap true}
                   :ability/effects [{:effect/type :add-mana
                                      :effect/mana {:any 1}}]}]}
```

### Key Observations
- **Required keys present**: All three cards have `:card/id`, `:card/name`, `:card/cmc`, `:card/mana-cost`, `:card/colors`, `:card/types`, `:card/text`
- **Mana costs**: Use keyword keys (`:black`, `:blue`, `:colorless`, `:any`, `:x`); maps with nat-int or boolean values
- **Colors/Types**: Sets of keywords
- **Effects**: Vectors of effect maps; no validation on `:effect/criteria` or `:effect/mana` content currently (too permissive)
- **Abilities**: Vectors; `:ability/cost` is `any-map`, `:ability/type` dispatches behavior
- **Triggers**: Vectors; no validation on `:trigger/filter` content

### Registry Loading

**File**: `/Users/abugosh/g/fizzle/src/main/fizzle/cards/registry.cljs` (157 lines)

- Requires **all 47 card namespaces explicitly** (no build-time discovery)
- Exports `all-cards` vector with individual cards + 3 cycle files
- **No registration-time validation** — validation happens at game init in `events/init.cljs`
- Cards are **pure EDN data** — no side effects on load

---

## 2. Selection Map Shape

### File: `/Users/abugosh/g/fizzle/src/main/fizzle/events/selection/core.cljs`

**Selection hierarchy** (lines 31–64):
- Derives effect types from interaction patterns (`:zone-pick`, `:accumulator`, `:reorder`, etc.)
- Used by `execute-confirmed-selection` and `build-selection-for-effect` multimethods

### Generic Selection State (zone-pick builder, lines 148–174)

```clojure
{:selection/type              :discard  ; Keyword, dispatches executor
 :selection/pattern           :zone-pick  ; Interaction pattern
 :selection/zone              :hand  ; Source zone
 :selection/card-source       :hand | :zone  ; How to get candidates
 :selection/target-zone       :graveyard  ; Destination zone
 :selection/select-count      1  ; Integer, how many to select
 :selection/player-id         :player-1  ; Target player
 :selection/selected          #{}  ; Selected card object-ids
 :selection/spell-id          object-id  ; Spell/source object
 :selection/remaining-effects [...]  ; Effects to resume after
 :selection/validation        :exact  ; :exact | :at-most | :at-least-one | :exact-or-zero | :always
 :selection/auto-confirm?     false  ; Boolean, auto-confirm if validation passes
 :selection/candidate-ids     #{...}  ; For zone sources, candidate IDs
 :selection/lifecycle         :standard  ; :standard | :finalized | :chaining
 :selection/min-count         0  ; Optional, for :at-most validation}
```

### Cast-Time Targeting Selection (targeting.cljs, lines 59–78)

```clojure
{:selection/type           :cast-time-targeting | :ability-cast-targeting
 :selection/player-id      :player-1
 :selection/object-id      object-id  ; Spell being cast
 :selection/mode           {:mode/id :primary :mode/mana-cost {...}}  ; Casting mode
 :selection/target-requirement  {:target/id :target-artifact
                                  :target/type :object | :player
                                  :target/zone :battlefield
                                  :target/controller :any
                                  :target/criteria {:match/types #{:artifact}}}
 :selection/valid-targets        #{object-id-1 object-id-2}  ; Pre-computed valid targets
 :selection/selected             #{}  ; Player's choice
 :selection/select-count         1
 :selection/validation           :exact
 :selection/auto-confirm?        true
 :selection/card-source          :valid-targets
 :selection/lifecycle            :chaining | :finalized}  ; Depends on generic mana cost
```

### Player-Target Selection (targeting.cljs, lines 26–40)

```clojure
{:selection/type           :player-target
 :selection/lifecycle      :finalized
 :selection/player-id      :player-1
 :selection/selected       #{}
 :selection/select-count   1
 :selection/valid-targets  #{:player-1 :player-2}
 :selection/spell-id       object-id
 :selection/target-effect  {...}  ; The effect needing a target
 :selection/remaining-effects [...]
 :selection/validation     :exact
 :selection/auto-confirm?  true}
```

**No current spec exists for selection maps** — high friction boundary.

---

## 3. Effect Definition Shape

### Files: `engine/effects/zones.cljs` (100+ lines), `engine/effects/life.cljs` (100+ lines)

Multimethods dispatch on `:effect/type`. Each effect is a map with:

**Common fields** (from card_spec.cljs lines 110–122):
- `:effect/type` (required, keyword)
- `:effect/mana` (optional, mana-cost map)
- `:effect/amount` (optional, int | keyword | dynamic map)
- `:effect/count` (optional, int | keyword | dynamic map)
- `:effect/target` (optional, keyword — `:self`, `:opponent`, `:any-player`, or player-id)
- `:effect/target-ref` (optional, keyword)
- `:effect/selection` (optional, keyword)
- `:effect/condition` (optional, map with `:condition/type`)
- `:effect/zone` (optional, keyword)
- `:effect/criteria` (optional, any map — **unvalidated**)

**Zone-operation examples** (zones.cljs):

:mill (lines 11–23):
```clojure
{:effect/type :mill
 :effect/target nil | :opponent  ; Defaults to player-id
 :effect/amount 3}
```

:draw (lines 26–48):
```clojure
{:effect/type :draw
 :effect/amount 1
 :effect/target nil | :opponent | :any-player
 :effect/selection :player}  ; For :any-player, needs selection
```

:return-from-graveyard (lines 75–92):
```clojure
{:effect/type :return-from-graveyard
 :effect/target nil | :opponent
 :effect/selection :player | :random  ; Triggers selection or random
 :effect/count 1}
```

**Life-operation examples** (life.cljs):

:lose-life (lines 10–25):
```clojure
{:effect/type :lose-life
 :effect/amount 3
 :effect/target nil | :opponent | :self}
```

:deal-damage (lines 46–63):
```clojure
{:effect/type :deal-damage
 :effect/amount 1
 :effect/target :controller  ; Resolved at runtime
 :effect/target-ref :target-artifact  ; From targeting}
```

:gain-life-equal-to-cmc (lines 66–82):
```clojure
{:effect/type :gain-life-equal-to-cmc
 :effect/target object-id}
```

**Key observation**: Effects are **polymorphic by `:effect/type`** — each type expects different optional fields. `card_spec.cljs` allows all fields on all types (permissive). No per-type validation.

**No execution-level specs** — effects consumed by `execute-effect-impl` multimethods with no validation on consumption.

---

## 4. Stack Item Shape

### File: `/Users/abugosh/g/fizzle/src/main/fizzle/engine/rules.cljs` (lines 304–329)

Stack items created by `stack/create-stack-item` with these shapes:

**Spell stack item** (lines 315–329):
```clojure
{:stack-item/type :spell
 :stack-item/controller player-id
 :stack-item/source object-id
 :stack-item/object-ref obj-eid  ; Datascript entity ID, not object-id
 :stack-item/position position}  ; Auto-assigned by create-stack-item
```

**Storm trigger item** (lines 304–311):
```clojure
{:stack-item/type :storm
 :stack-item/controller player-id
 :stack-item/source object-id
 :stack-item/effects [{:effect/type :storm-copies
                        :effect/count copy-count}]
 :stack-item/description "Storm - create N copies"}
```

### Database Schema (schema.cljs, lines 86–100)

Stack-item entity schema:
```clojure
:stack-item/position    {}  ; Integer, LIFO ordering
:stack-item/type        {}  ; Keyword: :spell, :storm-copy, :activated-ability, :etb, :permanent-tapped, :land-entered
:stack-item/controller  {}  ; Player entity ID
:stack-item/source      {}  ; Source object entity ID
:stack-item/effects     {}  ; Vector of effect maps (EDN)
:stack-item/targets     {}  ; Map of stored targeting choices
:stack-item/description {}  ; String
:stack-item/is-copy     {}  ; Boolean
:stack-item/cast-mode   {}  ; Map, the casting mode used
:stack-item/chosen-x    {}  ; Integer, X value chosen
:stack-item/sacrifice-info {}  ; Map {:power N}
:stack-item/object-ref  {:db/valueType :db.type/ref}  ; Ref to game object entity
```

**No spec exists for stack-item maps** — another friction boundary.

---

## 5. Bot Action Shape

### Files: `bots/protocol.cljs` (78 lines), `bots/definitions.cljs` (57 lines)

**Bot spec structure** (definitions.cljs):

```clojure
{:bot/name "Goldfish"
 :bot/deck [{:card/id :plains :count 12}
            {:card/id :island :count 12}]
 :bot/phase-actions {:main1 :play-land}  ; Map of phase -> action
 :bot/priority-rules []}  ; Vector of rule specs
```

**Priority rule spec** (burn-spec, definitions.cljs lines 20–31):

```clojure
{:rule/mode :auto | :manual
 :rule/conditions [{:check :zone-contains :zone :hand :player :self :card-id :lightning-bolt}
                   {:check :has-untapped-source :color :red}
                   {:check :stack-empty}]
 :rule/action {:action :cast-spell :card-id :lightning-bolt :target :opponent}}
```

**Action maps returned by bot protocol** (protocol.cljs lines 26–45):

:pass (lines 26–34):
```clojure
:pass  ; Keyword, not a map
```

Priority decision action (protocol.cljs docstring):
```clojure
{:action :cast-spell
 :object-id oid
 :target pid}
```

Phase action (protocol.cljs lines 37–45):
```clojure
{:action :play-land} | {:action :pass}
```

**No spec exists for bot action or rule shapes** — permissive data layer.

---

## 6. Existing cljs.spec Usage

**Only location**: `engine/card_spec.cljs`

- No specs for selections, effects (execution-level), stack items, or bot actions
- No specs for Datascript query results or subscription shapes
- No specs for re-frame events or effects (app-db updates)

---

## 7. Highest-Friction Boundaries (for spec adoption)

### Tier 1: Polymorphic by dispatch key (effect/type)
**Problem**: Different effect types expect different optional fields. Current spec allows all fields on all types.

**Current**: `card_spec.cljs:110–122` — single permissive `::effect` spec
```clojure
(s/def ::effect
  (s/keys :req [:effect/type]
          :opt [...23 optional keys...]))
```

**Friction**: Adding a new effect type requires:
- Updating card spec (new optional key)
- Adding defmethod in effects/ module
- No validation that the effect was well-formed before execution

**Recommendation**: Multi-spec pattern
```clojure
(defmulti spec-for-effect :effect/type)
(defmethod spec-for-effect :draw [_] (s/keys :req [:effect/type] :opt [:effect/amount :effect/target ...]))
(defmethod spec-for-effect :mill [_] (s/keys :req [:effect/type] :opt [...]))
```

### Tier 2: Selection maps (no spec)
**Problem**: 21+ selection types with type-specific keys. Selection machinery dispatches on `:selection/type` but no validation.

**Current**: Builders return ad-hoc maps; no schema
```clojure
(defmethod build-selection-for-effect :zone-pick ...)  ; Returns map with :selection/zone, :selection/candidate-ids, etc.
(defmethod build-selection-for-effect :cast-time-targeting ...)  ; Returns different keys
```

**Friction**: 
- Easy to mistype `:selection/selected` vs `:selection/select-count`
- Executors assume keys exist; runtime errors if missing
- No validation on `:selection/valid-targets` structure

**Recommendation**: Multi-spec pattern (selection type dispatch)
```clojure
(defmulti spec-for-selection :selection/type)
(defmethod spec-for-selection :zone-pick [_] (s/keys :req [...] :opt [...]))
(defmethod spec-for-selection :cast-time-targeting [_] (s/keys :req [...] :opt [...]))
```

### Tier 3: Stack items (partial spec)
**Problem**: Stack items are polymorphic (`:spell`, `:storm-copy`, `:activated-ability`, `:etb`, etc.) with type-specific fields.

**Current**: No validation layer
```clojure
;; schema.cljs declares fields, but no constraint on which combinations are valid
:stack-item/type    {}
:stack-item/object-ref {:db/valueType :db.type/ref}  ; Spells only
:stack-item/effects {}  ; Triggers/abilities only
```

**Friction**: 
- Can create a stack-item with `:type :spell` but no `:object-ref` (invalid)
- Can create a stack-item with `:type :etb` and `:object-ref` (nonsensical)
- Resolver code assumes shapes without checking

**Recommendation**: Multi-spec with cross-field validation
```clojure
(defmulti spec-for-stack-item :stack-item/type)
(defmethod spec-for-stack-item :spell [_]
  (s/keys :req [:stack-item/type :stack-item/controller :stack-item/object-ref]
          :opt [...]))
(defmethod spec-for-stack-item :activated-ability [_]
  (s/keys :req [:stack-item/type :stack-item/controller :stack-item/effects]
          :opt [...]))
```

### Tier 4: Bot actions (unvalidated)
**Problem**: Bot rules generate action maps with `:action :cast-spell` or `:action :play-land`, but no validation on required fields.

**Current**: `definitions.cljs` lines 20–31 show expected shapes, but `rules.cljs` doesn't validate.

**Friction**:
- Easy to define a rule with `{:action :cast-spell}` (missing `:card-id` and `:target`)
- Director dispatches on `:action` keyword without validating required fields
- Errors surface late in game loop

**Recommendation**: Multi-spec
```clojure
(defmulti spec-for-action :action)
(defmethod spec-for-action :cast-spell [_]
  (s/keys :req [:action :card-id] :opt [:target]))
(defmethod spec-for-action :play-land [_]
  (s/keys :req [:action]))
```

---

## 8. Data Flow Validation Gaps

### Entry Points (no validation)
- Game initialization: `events/init.cljs` calls `validate-cards!` ✅
- Bot deck: `definitions.cljs` raw map, no spec
- User input (hand sculpting): No selection validation during build

### Dispatch Points (fragile)
- `execute-effect-impl` multimethods assume effect shape
- `execute-confirmed-selection` multimethods assume selection shape
- `bot-priority-decision` assumes rule matches return valid action

### Consumption Points (no validation)
- `resolve-effect-target` consumes `:effect/target-ref` without checking it exists
- Selection executors assume `:selection/selected` is a set, even if builder returned a vec

---

## Summary: Spec Adoption Roadmap

**Phase 1: Card effects (already done)** ✅  
`engine/card_spec.cljs` — card shape, effect type enum, per-effect validation needed

**Phase 2: Effect execution layer (high friction)**  
- Multi-spec dispatch on `:effect/type`
- Validate before calling `execute-effect-impl`
- ~5-8 effect categories

**Phase 3: Selection system (high friction)**  
- Multi-spec dispatch on `:selection/type`
- Validate on builder return and executor consumption
- 21+ selection types, but consolidated into 3-4 dispatch patterns

**Phase 4: Stack items (medium friction)**  
- Multi-spec dispatch on `:stack-item/type`
- Validate on creation and before resolution
- 6-7 item types

**Phase 5: Bot system (low friction)**  
- Multi-spec dispatch on `:action`
- Validate rule matching results
- 3-4 action types

**Phase 6: Re-frame event/effects (optional, high reward)**  
- Spec for app-db shape
- Spec for `:db/add`, `:db/retract` transaction forms
- Prevents silent Datascript errors

---

## Files to Spec

| Module | File | Entities | Status |
|--------|------|----------|--------|
| Cards | `engine/card_spec.cljs` | Card, effect, ability, trigger | ✅ Exists |
| Effects | `engine/effects/*.cljs` | Effect (dispatch on type) | ❌ Missing |
| Selection | `events/selection/*.cljs` | Selection (dispatch on type) | ❌ Missing |
| Stack | `engine/stack.cljs` | Stack item (dispatch on type) | ❌ Missing |
| Bots | `bots/definitions.cljs` | Bot spec, rule, action | ❌ Missing |
| DB | `db/schema.cljs` | Object, player, game state | ❌ Missing |
| Re-frame | `events/*.cljs` | Event effects, app-db shape | ❌ Missing |

