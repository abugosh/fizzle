# Card Implementation Patterns: Flashback, Storm, and Creatures

**Date**: 2026-03-09
**Scope**: Investigation for implementing Crippling Fatigue (flashback black sorcery) and Hunting Pack (storm green instant with tokens)

---

## 1. FLASHBACK IMPLEMENTATION PATTERN

### How Flashback is Defined in Card Data

Flashback uses `:card/alternate-costs` vector with the following structure:

```clojure
:card/alternate-costs [{:alternate/id :flashback
                        :alternate/zone :graveyard
                        :alternate/mana-cost {:colorless 1 :blue 1}
                        :alternate/additional-costs [{:cost/type :pay-life :cost/amount 3}]
                        :alternate/on-resolve :exile}]
```

**Key Fields**:
- `:alternate/id` — Always `:flashback` (keyword)
- `:alternate/zone` — Where spell becomes castable (`:graveyard`)
- `:alternate/mana-cost` — Flashback mana cost (map like `{:colorless 1 :blue 1}`)
- `:alternate/additional-costs` — OPTIONAL: Vector of additional costs (e.g., pay life, discard)
  - Format: `{:cost/type :pay-life :cost/amount 3}` for life payment
  - Converted to multimethod dispatch in rules.cljs:additional-cost->cost-map
- `:alternate/on-resolve` — What happens to card after flashback spell resolves (`:exile`)

### Existing Flashback Examples

#### Deep Analysis (3U instant)
**File**: `/Users/abugosh/g/fizzle/src/main/fizzle/cards/blue/deep_analysis.cljs`

```clojure
{:card/id :deep-analysis
 :card/name "Deep Analysis"
 :card/types #{:sorcery}
 :card/targeting [{:target/id :player
                   :target/type :player
                   :target/options [:self :opponent :any-player]
                   :target/required true}]
 :card/effects [{:effect/type :draw
                 :effect/amount 2
                 :effect/target :any-player}]
 :card/alternate-costs [{:alternate/id :flashback
                         :alternate/zone :graveyard
                         :alternate/mana-cost {:colorless 1 :blue 1}
                         :alternate/additional-costs [{:cost/type :pay-life :cost/amount 3}]
                         :alternate/on-resolve :exile}]}
```

- Cast-time targeting allows player to choose target (self/opponent/any)
- Main effect draws 2 cards at target player
- Flashback costs {1}{U} + pay 3 life
- When flashback spell resolves, card is exiled

#### Recoup (Red sorcery)
**File**: `/Users/abugosh/g/fizzle/src/main/fizzle/cards/red/recoup.cljs`

- Targets a sorcery in graveyard
- Effect grants flashback to target via `:grant-flashback` effect
- Has its own flashback alternate cost ({3}{R})

### Cost Payment for Flashback

**File**: `/Users/abugosh/g/fizzle/src/main/fizzle/engine/costs.cljs` (lines 179-215)

Pay-life cost implementation:
```clojure
(defmethod can-pay? :pay-life [db object-id cost]
  ;; Check if player has enough life to pay
  ;; Reads current life total and ensures paying won't go below 0
  )

(defmethod pay-cost :pay-life [db object-id cost]
  ;; Reduce player life by the amount specified in :pay-life key
  ;; {:pay-life 3} -> subtract 3 from player life
  )
```

**Usage in rules**: `/Users/abugosh/g/fizzle/src/main/fizzle/engine/rules.cljs` (lines 115-122)
```clojure
(defn- additional-cost->cost-map
  "Convert alternate cost format to costs.cljs multimethod format"
  [cost]
  (case (:cost/type cost)
    :pay-life {:pay-life (:cost/amount cost)}
    ...))
```

### Test Pattern for Flashback

**File**: `/Users/abugosh/g/fizzle/src/test/fizzle/cards/blue/deep_analysis_test.cljs`

Mandatory test sections for flashback cards:

1. **Card definition test** (lines 32-71)
   - Verify alternate-costs structure
   - Check all fields: id, zone, mana-cost, additional-costs, on-resolve

2. **Cast from hand test** (lines 76-84)
   - Verify spell is castable for primary mana cost

3. **Flashback cast test** (lines 97+)
   - Move card from graveyard (test setup)
   - Verify modes include flashback mode
   - Verify flashback is castable from graveyard
   - Verify pay-life cost can be paid
   - Execute cast and verify card exiles on resolve

4. **Counter/fizzle test** (lines showing flashback spell behavior)
   - When flashback spell is countered, it exiles (not to graveyard)
   - Normal spells go to graveyard when countered

---

## 2. STORM IMPLEMENTATION PATTERN

### How Storm is Defined in Card Data

Storm is a keyword mechanic marked in two places:

**File**: `/Users/abugosh/g/fizzle/src/main/fizzle/cards/blue/brain_freeze.cljs`

```clojure
{:card/id :brain-freeze
 :card/name "Brain Freeze"
 :card/types #{:instant}
 :card/keywords #{:storm}  ; <-- Mark spell as having storm
 :card/text "Target player mills 3. Storm."
 :card/targeting [{:target/id :player
                   :target/type :player
                   :target/options #{:any-player}
                   :target/required true}]
 :card/effects [{:effect/type :mill
                 :effect/amount 3
                 :effect/target :any-player}]}
```

**Key Points**:
- `:card/keywords #{:storm}` marks the spell as having storm
- Card itself has no special effect data for storm
- Storm resolution happens in the engine during spell resolution

### Storm Resolution (Engine)

**File**: `/Users/abugosh/g/fizzle/src/main/fizzle/engine/storm.cljs`

Storm creates copies on the stack. The system:
1. Tracks `:player/storm-count` (spells cast this turn)
2. When a storm spell resolves, creates N copies where N = storm count at cast time
3. Each copy is marked with `:object/is-copy true` or `:stack-item/is-copy true`
4. Copies resolve in sequence after the original

### Test Pattern for Storm Cards

**File**: `/Users/abugosh/g/fizzle/src/test/fizzle/cards/blue/brain_freeze_test.cljs`

Mandatory test sections for storm cards:

1. **Card definition test** (lines 48-76)
   - Verify `:card/keywords #{:storm}` is present
   - Verify effect targeting (cast-time targeting for player-targeted spells)

2. **Cast and storm count test**
   - Cast the spell
   - Verify storm count incremented by 1
   - Check that storm count is readable from player state

3. **Storm copy creation test**
   - After casting storm spell, verify stack has N+1 items (original + copies)
   - Verify copies have correct targets (inherited from original)

4. **Copy resolution test**
   - Resolve copies in sequence
   - Verify each copy resolves independently with same effects
   - Verify copies don't persist after resolution

### Storm with Cast-Time Targeting

**Important**: Storm spells with player targeting use the standard cast-time targeting system:

```clojure
:card/targeting [{:target/id :player
                  :target/type :player
                  :target/options #{:any-player}
                  :target/required true}]
```

The player chooses the target once at cast time. This single target is **stored on the original stack-item** and **inherited by all storm copies** (they all hit the same target).

---

## 3. CREATURE TOKENS IMPLEMENTATION PATTERN

### Token Creation System

**File**: `/Users/abugosh/g/fizzle/src/main/fizzle/engine/effects/tokens.cljs`

Token creation is an effect type: `:create-token`

```clojure
:card/effects [{:effect/type :create-token
                :effect/token {:token/name "Beast"
                               :token/types #{:creature}
                               :token/subtypes #{:beast}
                               :token/power 4
                               :token/toughness 4
                               :token/colors #{:green}
                               :token/keywords #{}}}]
```

**Token Definition Structure**:
- `:token/name` — Display name (string)
- `:token/types` — Card types set (e.g., `#{:creature}`)
- `:token/subtypes` — Subtypes set (e.g., `#{:beast}`)
- `:token/power` — Power (integer)
- `:token/toughness` — Toughness (integer)
- `:token/colors` — Color set (e.g., `#{:green}`)
- `:token/keywords` — Keywords set (optional, e.g., `#{:flying}`)

### How Tokens Are Created in the Engine

When `:create-token` effect executes (lines 13-44):

1. Creates a synthetic card entity in Datascript for the token
   - Sets `:card/types`, `:card/power`, `:card/toughness`, etc.
   - This allows token to appear in queries like other cards

2. Creates an object entity on the battlefield
   - `:object/is-token true` marks it as a token
   - `:object/power` and `:object/toughness` initialized from token def
   - `:object/zone :battlefield`
   - `:object/summoning-sick true` (token is newly created)
   - `:object/damage-marked 0` (for creature damage tracking)

3. Both entities are transacted together

### Creature Fields in Database Schema

**File**: `/Users/abugosh/g/fizzle/src/main/fizzle/db/schema.cljs` (lines 46-52)

Creature-related object fields:
```clojure
:object/power           {}  ; integer — base power (from card definition)
:object/toughness       {}  ; integer — base toughness (from card definition)
:object/damage-marked   {}  ; integer — damage taken this turn (default 0)
:object/summoning-sick  {}  ; boolean — entered battlefield this turn
:object/attacking       {}  ; boolean — declared as attacker this combat
:object/blocking        {}  ; ref — eid of attacker being blocked
:object/is-token        {}  ; boolean — token creature
```

### Creature Infrastructure

**File**: `/Users/abugosh/g/fizzle/src/main/fizzle/engine/creatures.cljs`

Pure functions for creature stat computation:

```clojure
(defn creature? [db object-id]
  "Check if object's card has :creature in :card/types")

(defn effective-power [db object-id]
  "Compute: base + counters + grants + self-static-abilities"
  ;; Returns nil for non-creatures

(defn effective-toughness [db object-id]
  "Compute: base + counters + grants + self-static-abilities"
  ;; Returns nil for non-creatures

(defn can-attack? [db object-id]
  "Check if creature can attack: not sick, not tapped, not defender"

(defn can-block? [db object-id attacker-id]
  "Check if creature can block: not tapped, flying/reach logic"

(defn has-keyword? [db object-id kw]
  "Check if object has keyword from card def OR granted keywords"
```

### Card Definition for Creatures

**File**: `/Users/abugosh/g/fizzle/src/main/fizzle/cards/green/xantid_swarm.cljs`

```clojure
{:card/id :xantid-swarm
 :card/name "Xantid Swarm"
 :card/cmc 1
 :card/mana-cost {:green 1}
 :card/types #{:creature}
 :card/subtypes #{:insect}
 :card/power 0
 :card/toughness 1
 :card/keywords #{:flying}
 :card/text "Flying\nWhenever Xantid Swarm attacks, defending player can't cast spells this turn."
 :card/triggers [{:trigger/type :creature-attacks
                  :trigger/description "defending player can't cast spells this turn"
                  :trigger/effects [{:effect/type :add-restriction
                                     :effect/target :opponent
                                     :restriction/type :cannot-cast-spells}]}]}
```

**Key Creature Fields**:
- `:card/power` — Base power (integer)
- `:card/toughness` — Base toughness (integer)
- `:card/keywords` — Set of keywords (e.g., `#{:flying :haste}`)
- `:card/types` — Must include `:creature`
- `:card/subtypes` — Subtypes like `:insect`, `:beast`
- `:card/triggers` — Creature-specific triggers like `:creature-attacks`

### Existing Creature Examples in Codebase

1. **Xantid Swarm** (0/1 green insect)
   - Has flying keyword
   - Has triggered ability on attack

2. **Nimble Mongoose** (1/1 green creature)
   - Has shroud keyword
   - Has static ability for threshold (+2/+2)

---

## 4. CREATURE TARGETING AND -X/-X EFFECTS

### Status: NOT YET IMPLEMENTED

As of 2026-03-09, there are **NO effects for** -X/-X modifications or creature damage.

From `/Users/abugosh/g/fizzle/INVESTIGATION_REPORT.md`:
- `:deal-damage` effect exists but **only targets players**, not creatures
- No `:apply-damage-to-creature` effect exists
- No `:creature-death` effect exists
- No -X/-X effect exists

### What WOULD Need to Be Implemented

For "Crippling Fatigue" (black sorcery, -2/-2 to target creature until end of turn):

1. **Creature Targeting**
   ```clojure
   :card/targeting [{:target/id :target-creature
                     :target/type :object
                     :target/zone :battlefield
                     :target/controller :any
                     :target/criteria {:match/types #{:creature}}
                     :target/required true}]
   ```

2. **Effect Type for -X/-X** (would need to be added)
   ```clojure
   :card/effects [{:effect/type :apply-pt-modifier  ; HYPOTHETICAL - doesn't exist yet
                   :effect/target-ref :target-creature
                   :effect/power -2
                   :effect/toughness -2
                   :effect/duration :until-end-of-turn}]
   ```

3. **OR Using Grants** (temporary ability system)
   ```clojure
   :card/effects [{:effect/type :add-grant  ; HYPOTHETICAL
                   :effect/target-ref :target-creature
                   :effect/grant {:grant/type :pt-modifier
                                  :grant/data {:grant/power -2 :grant/toughness -2}
                                  :grant/expires {:expires/phase :end-of-turn}}}]
   ```

### Current Power/Toughness Modification Mechanism

The only existing PT modification is **static abilities** (self-only):

**File**: `/Users/abugosh/g/fizzle/src/main/fizzle/engine/static_abilities.cljs` (lines 124-142)

```clojure
(defn get-self-pt-modifiers [db object-eid]
  "Get :pt-modifier static abilities on a specific object that apply to :self"
  ;; Checks conditions (e.g., threshold) against the object's controller
  ;; Returns vector of {:power N :toughness N} maps for applicable modifiers
  )
```

Static abilities structure (from Nimble Mongoose):
```clojure
:card/static-abilities [{:static/type :pt-modifier
                        :modifier/power 2
                        :modifier/toughness 2
                        :modifier/condition {:condition/type :threshold}
                        :modifier/applies-to :self}]
```

This is **only for self-modifying creatures** with conditions. Does NOT apply to other creatures.

---

## 5. PAY-LIFE-AS-COST PATTERN

### Implementation

Pay-life is implemented as an additional cost type:

**Cost Definition** (in card's `:card/alternate-costs`):
```clojure
:alternate/additional-costs [{:cost/type :pay-life :cost/amount 3}]
```

**Conversion Layer**: `/Users/abugosh/g/fizzle/src/main/fizzle/engine/rules.cljs`
```clojure
(defn- additional-cost->cost-map
  "Convert alternate cost format to costs.cljs multimethod format"
  [cost]
  (case (:cost/type cost)
    :pay-life {:pay-life (:cost/amount cost)}))
```

**Cost Payment**: `/Users/abugosh/g/fizzle/src/main/fizzle/engine/costs.cljs`

```clojure
(defmethod can-pay? :pay-life [db object-id cost]
  ;; Cost map is {:pay-life 3}
  ;; Checks if player has at least N life
  ;; Cost cannot be paid if it would reduce life below 0
  (let [required (:pay-life cost)]
    (if-let [player-id (:player/id ...)]
      (>= (get-life-total db player-id) required)
      false)))

(defmethod pay-cost :pay-life [db object-id cost]
  ;; Reduces player life by amount
  ;; Re-checks can-pay? guard before executing
  )
```

### Example: Deep Analysis Flashback

```clojure
{:alternate/id :flashback
 :alternate/mana-cost {:colorless 1 :blue 1}
 :alternate/additional-costs [{:cost/type :pay-life :cost/amount 3}]}
```

When casting Deep Analysis from graveyard via flashback:
1. Pay {1}{U} mana
2. Pay 3 life (verified can-pay? before execution)
3. Spell resolves and exiles

---

## 6. COMPLETE CARD DEFINITION PATTERN

### Example: Full Card Structure

Based on existing patterns, a complete card looks like:

```clojure
(ns fizzle.cards.color.card-name
  "Card description with oracle text.")

(def card
  {:card/id          :card-keyword-id
   :card/name        "Full Card Name"
   :card/cmc         3
   :card/mana-cost   {:black 1 :red 2}
   :card/colors      #{:black :red}
   :card/types       #{:sorcery}      ; or :creature, :instant, etc.
   :card/subtypes    #{}              ; OPTIONAL: for creatures, e.g., #{:beast}
   :card/keywords    #{:storm}        ; OPTIONAL: #{:storm :flashback}, etc.
   :card/text        "Oracle text here..."

   ;; OPTIONAL: Cast-time targeting requirements
   :card/targeting   [{:target/id     :target-name
                       :target/type   :object          ; or :player
                       :target/zone   :battlefield
                       :target/controller :any         ; or :self
                       :target/criteria {:match/types #{:creature}}
                       :target/required true}]

   ;; Main effects
   :card/effects     [{:effect/type  :destroy
                       :effect/target-ref :target-name}]

   ;; OPTIONAL: Creature stats
   :card/power       4                 ; for creatures
   :card/toughness   4                 ; for creatures

   ;; OPTIONAL: Static abilities (self-only conditions)
   :card/static-abilities []           ; e.g., pt-modifier with conditions

   ;; OPTIONAL: Triggered abilities
   :card/triggers    [{:trigger/type :creature-attacks
                       :trigger/effects [...]}]

   ;; OPTIONAL: Activated abilities
   :card/abilities   [{:ability/type :activated
                       :ability/costs [{...}]
                       :ability/effects [...]}]

   ;; OPTIONAL: Enter-battlefield effects
   :card/etb-effects [{...}]

   ;; OPTIONAL: Alternate casting modes (flashback, etc.)
   :card/alternate-costs [{:alternate/id :flashback
                           :alternate/zone :graveyard
                           :alternate/mana-cost {:colorless 1}
                           :alternate/additional-costs [{:cost/type :pay-life :cost/amount 3}]
                           :alternate/on-resolve :exile}]})
```

### File Locations

- **Black sorceries**: `/Users/abugosh/g/fizzle/src/main/fizzle/cards/black/`
- **Green instants**: `/Users/abugosh/g/fizzle/src/main/fizzle/cards/green/`
- **All cards registered in**: `/Users/abugosh/g/fizzle/src/main/fizzle/cards/registry.cljs`

---

## 7. TESTING REQUIREMENTS BY CARD TYPE

### Mandatory Test Categories (from CLAUDE.md)

**A. Card Definition** — Verify ALL fields with exact values
```clojure
(is (= :card-id (:card/id card)))
(is (= "Card Name" (:card/name card)))
(is (= #{:sorcery} (:card/types card)))
(is (= {:black 1} (:card/mana-cost card)))
```

**B. Cast-Resolve Happy Path** — Full cycle through production code
```clojure
(let [db (th/create-test-db)
      [db obj-id] (th/add-card-to-zone db :card-id :hand :player-1)
      db (mana/add-mana db :player-1 {:black 1})
      db (events/dispatch-sync db ::cast-spell {:player :player-1 :object obj-id ...})]
  (is (= :stack (:object/zone (q/get-object db obj-id)))))
```

**C. Cannot-Cast Guards** — Insufficient mana, invalid targets, wrong zone

**D. Storm Count** — Verify casting increments `:player/storm-count`

**E. Selection Tests** — If card requires player choice (modal, targeting, etc.)

**F. Targeting Tests** — For targeted spells, verify target validation

**G. Edge Cases** — Empty zones, partial resources, special conditions (2+ per card)

**H. Flashback Tests** — If card has flashback
- Cast from graveyard
- Pay additional costs
- Card exiles on resolve

**I. Trigger Tests** — If card has triggered abilities

### Test Minimum Requirements by Type

| Card Type | Min Tests |
|-----------|-----------|
| Sorcery without special mechanics | 5 |
| Targeted sorcery | 8 |
| Sorcery with flashback | 12 |
| Instant with storm | 10 |
| Creature | 8 |
| Creature with triggers | 12 |

### Test Helper Functions (from `/Users/abugosh/g/fizzle/src/test/fizzle/test_helpers.cljs`)

```clojure
(th/create-test-db)
  ;; Create initialized test game database

(th/add-card-to-zone db :card-id :zone :player-id)
  ;; Return [db object-id]

(th/add-opponent db :player-id)
  ;; Add opponent player to game

(th/cast-and-resolve db :player-id :card-id)
  ;; Simple spell: cast and resolve, return db

(th/cast-with-target db :player-id :card-id :target-player-id)
  ;; Targeted spell: select target, cast through production flow

(th/resolve-top db)
  ;; Resolve top stack item, return {:db} or {:db :selection}

(th/confirm-selection db :player-id :selection-state)
  ;; Confirm interactive selection, return {:db} or chains to next
```

---

## SUMMARY: Implementation for Crippling Fatigue and Hunting Pack

### Crippling Fatigue (Black Sorcery)

**Would use patterns**:
1. ✅ **Flashback** — Alternate costs with `:pay-life` additional cost
   - File: `/Users/abugosh/g/fizzle/src/main/fizzle/cards/black/crippling_fatigue.cljs`
   - Follow Deep Analysis pattern for flashback structure

2. ❌ **-2/-2 Effect** — NOT YET IMPLEMENTED
   - Current system has NO effect type for creature PT modification until end of turn
   - Would need new effect type added to effects system

3. ✅ **Creature Targeting** — Use standard targeting system
   - Pattern: `:target/criteria {:match/types #{:creature}}`
   - Like Crumble pattern but targets creatures instead of artifacts

### Hunting Pack (Green Instant)

**Would use patterns**:
1. ✅ **Storm** — Card keyword + existing storm engine
   - File: `/Users/abugosh/g/fizzle/src/main/fizzle/cards/green/hunting_pack.cljs`
   - Follow Brain Freeze pattern, add `:card/keywords #{:storm}`

2. ✅ **Token Creation** — Use `:create-token` effect
   - Effect: `{:effect/type :create-token :effect/token {:token/name "Beast" ...}}`
   - Creates 4/4 green Beast tokens on each resolution (original + storm copies)

3. ✅ **No Targeting** — Simple spell, no choices needed
   - Just effect execution

### Blockers for Current Implementation

**Crippling Fatigue blocked on**:
- Implementation of -X/-X effect type (new feature)
- OR: Implementation of temporary PT modification via grants

**Hunting Pack ready to implement**:
- No blockers; all infrastructure exists

---

## Key Files Reference

| Purpose | File | Lines |
|---------|------|-------|
| Flashback card example | `/Users/abugosh/g/fizzle/src/main/fizzle/cards/blue/deep_analysis.cljs` | All |
| Storm card example | `/Users/abugosh/g/fizzle/src/main/fizzle/cards/blue/brain_freeze.cljs` | All |
| Creature example | `/Users/abugosh/g/fizzle/src/main/fizzle/cards/green/xantid_swarm.cljs` | All |
| Token creation effect | `/Users/abugosh/g/fizzle/src/main/fizzle/engine/effects/tokens.cljs` | 13-44 |
| Pay-life cost | `/Users/abugosh/g/fizzle/src/main/fizzle/engine/costs.cljs` | 179-215 |
| Creature infrastructure | `/Users/abugosh/g/fizzle/src/main/fizzle/engine/creatures.cljs` | All |
| DB schema | `/Users/abugosh/g/fizzle/src/main/fizzle/db/schema.cljs` | 46-52 |
| Card spec validation | `/Users/abugosh/g/fizzle/src/main/fizzle/engine/card_spec.cljs` | 15-26 |
| Flashback test example | `/Users/abugosh/g/fizzle/src/test/fizzle/cards/blue/deep_analysis_test.cljs` | All |
| Storm test example | `/Users/abugosh/g/fizzle/src/test/fizzle/cards/blue/brain_freeze_test.cljs` | All |

