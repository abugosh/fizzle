---
name: Infernal Contract Implementation Patterns
description: Complete implementation guide for black sorcery draw card with life payment cost
type: reference
---

# Infernal Contract Implementation Guide

**Card**: Infernal Contract — {2}{B} Sorcery
- Effect: Draw 4 cards
- Cost: Pay half your life (rounded up) in addition to mana cost

## 1. Black Card File Location & Structure

**Location**: `/Users/abugosh/g/fizzle/src/main/fizzle/cards/black/infernal_contract.cljs`

**Basic structure** (from existing black cards like necrologia.cljs):
```clojure
(ns fizzle.cards.black.infernal-contract)

(def card
  {:card/id :infernal-contract
   :card/name "Infernal Contract"
   :card/cmc 3
   :card/mana-cost {:colorless 2 :black 1}
   :card/colors #{:black}
   :card/types #{:sorcery}
   :card/text "Pay half your life, rounded up. Draw 4 cards."
   :card/effects [...]
   :card/additional-costs [...]})
```

## 2. Draw Effect Pattern

**Reference**: `src/main/fizzle/engine/effects/zones.cljs:26-48`

Effect type: `:draw`
- `:effect/type :draw`
- `:effect/amount` — can be static integer or dynamic map with `:dynamic/type`
- `:effect/target` (optional) — defaults to casting player

**For static draw of 4 cards**:
```clojure
{:effect/type :draw
 :effect/amount 4}
```

**Note**: Draw effects support dynamic amounts via `:effect/amount {:dynamic/type :chosen-x}` for X-mana cards like Necrologia.

## 3. Life Payment Cost Mechanism

### Additional Cost Declaration

**Reference**: `src/main/fizzle/cards/black/necrologia.cljs:19`

Add to card map:
```clojure
:card/additional-costs [{:cost/type :pay-x-life}]
```

This triggers the pre-cast pipeline to insert a pay-x-life selection step.

### Pre-Cast Pipeline Integration

**Reference**: `src/main/fizzle/events/casting.cljs`

Pipeline steps (line visible in grep output):
```
[:exile-cards-cost :return-land-cost :discard-specific-cost :sacrifice-permanent-cost
 :pay-x-life :x-mana-cost :targeting :mana-allocation]
```

The `:pay-x-life` step is automatically triggered by the `evaluate-pre-cast-step` multimethod.

### Pay-X-Life Selection Builder

**Reference**: `src/main/fizzle/events/selection/costs.cljs:353-379`

The builder is automatically called by the casting pipeline and:
1. Reads player's current life total via `(queries/get-life-total game-db player-id)`
2. Sets `:selection/max-x` to that life total
3. Player uses accumulator UI to select amount
4. Selected amount is stored in `:selection/selected-x`

## 4. Challenge: "Half Life (Rounded Up)"

**Problem**: Infernal Contract requires paying half your life, rounded up, NOT choosing X.

**Solution**: We need a **custom additional cost type** because:
- Current `:pay-x-life` cost allows player to choose ANY amount up to max life
- Infernal Contract forces a fixed amount: `ceil(current-life / 2)`

### Implementation Options

#### Option A: Extend the System (Recommended)

Create a new cost type `:pay-half-life` that:

1. **Add cost type check** to `src/main/fizzle/events/selection/costs.cljs`:
   ```clojure
   (defn has-pay-half-life-cost?
     [mode]
     (some (fn [cost] (= :pay-half-life (:cost/type cost)))
           (:mode/additional-costs mode)))
   ```

2. **Create a builder** in same file:
   ```clojure
   (defn build-pay-half-life-selection
     [game-db player-id object-id mode]
     (let [current-life (queries/get-life-total game-db player-id)
           half-life-ceil (js/Math.ceil (/ current-life 2))]
       {:selection/type :pay-half-life
        :selection/lifecycle :finalized
        :selection/player-id player-id
        :selection/spell-id object-id
        :selection/mode mode
        :selection/fixed-amount half-life-ceil
        :selection/validation :always
        :selection/auto-confirm? true}))  ; Auto-confirm because amount is fixed
   ```

3. **Add selection spec** to `src/main/fizzle/events/selection/spec.cljs`
   (follow pattern from `:pay-x-life` spec at relevant lines)

4. **Add executor** to `src/main/fizzle/events/selection/costs.cljs`:
   ```clojure
   (defmethod core/execute-confirmed-selection :pay-half-life
     [game-db selection]
     (let [amount (:selection/fixed-amount selection)
           player-id (:selection/player-id selection)
           mode (:selection/mode selection)
           object-id (:selection/spell-id selection)
           db-with-payment (lose-life db player-id amount)]
       {:db db-with-payment}))  ; Continue to next pre-cast step
   ```

5. **Add to pre-cast pipeline** in `src/main/fizzle/events/casting.cljs`:
   ```clojure
   (defmethod evaluate-pre-cast-step :pay-half-life
     [_ {:keys [game-db player-id object-id mode]}]
     (when (sel-costs/has-pay-half-life-cost? mode)
       {:selection (sel-costs/build-pay-half-life-selection game-db player-id object-id mode)}))
   ```

6. **Card definition**:
   ```clojure
   :card/additional-costs [{:cost/type :pay-half-life}]
   ```

#### Option B: Computed Cost via Pre-Cast Handler

Use card/pre-cast-hook (if available) to compute and store the life cost before casting.
Less recommended — adds complexity and violates data-driven cost design.

#### Option C: Store on Stack Item

Compute half-life during stack item creation and use `:stack-item/forced-life-cost` (hypothetical).
Requires schema change; not recommended.

## 5. Life Loss Effect Integration

**Reference**: `src/main/fizzle/engine/effects/life.cljs:10-25`

If needed for the executor, use:
```clojure
(defmethod effects/execute-effect-impl :lose-life
  [db player-id effect _object-id]
  ;; Looks for :effect/amount and :effect/target
  ...)
```

Or directly in selection executor:
```clojure
(let [player-eid (q/get-player-eid game-db player-id)
      current-life (q/get-life-total game-db player-id)
      new-life (- current-life amount)]
  (d/db-with game-db [[:db/add player-eid :player/life new-life]]))
```

## 6. Test File Structure

**Location**: `/Users/abugosh/g/fizzle/src/test/fizzle/cards/black/infernal_contract_test.cljs`

**Reference**: `src/test/fizzle/cards/black/necrologia_test.cljs`

**Mandatory test categories**:
- **A. Card definition** — verify exact field values (cmc, mana cost, types, text, costs, effects)
- **B. Cast-resolve happy path** — full cast/resolve cycle through production code
- **C. Cannot-cast guards** — insufficient mana, wrong zone
- **D. Storm count** — casting increments storm counter
- **E. Additional cost behavior** — pay-half-life auto-computes to ceil(life/2), deducts life, draws 4

**Key test helper**: `fizzle.test-helpers/cast-and-resolve` for simple spell flow

**Example test structure**:
```clojure
(deftest infernal-contract-cast-resolve-happy-path
  (let [db (th/create-test-db {:mana {:black 1 :colorless 2} :life 19})
        [db lib-ids] (th/add-cards-to-library db [...] :player-1)
        [db obj-id] (th/add-card-to-zone db :infernal-contract :hand :player-1)
        initial-hand (th/get-hand-count db :player-1)]
    ;; Life is 19, ceil(19/2) = 10
    ;; Should deduct 10 life (19 → 9), draw 4
    ;; ... use th/cast-and-resolve or th/resolve-top
    ))
```

## 7. Registry Integration

**Reference**: `src/main/fizzle/cards/registry.cljs`

Add require:
```clojure
[fizzle.cards.black.infernal-contract :as infernal-contract]
```

And add to all-cards vector:
```clojure
infernal-contract/card
```

## 8. Dynamic Amount System (For Reference)

**Location**: `src/main/fizzle/engine/effects.cljs:24-94`

Available dynamic types for future use:
- `:count-named-in-zone` — counts cards in zone by name
- `:chosen-x` — reads X from stack item (for variable-cost cards)
- `:sacrificed-power` — reads power of sacrificed creature
- `:cost-exiled-card-mana-value` — reads CMC of exiled card

**Note**: For Infernal Contract, we handle the "half-life" computation in the cost builder, not as a dynamic effect type.

## 9. Key Files to Modify

1. **Create**: `src/main/fizzle/cards/black/infernal_contract.cljs` — card definition
2. **Create**: `src/test/fizzle/cards/black/infernal_contract_test.cljs` — tests
3. **Modify**: `src/main/fizzle/cards/registry.cljs` — add require and export
4. **Modify** (if implementing custom cost):
   - `src/main/fizzle/events/selection/costs.cljs` — add builders/helpers/executors
   - `src/main/fizzle/events/selection/spec.cljs` — add selection spec
   - `src/main/fizzle/events/casting.cljs` — add pre-cast step handler
   - `src/main/fizzle/events/selection/core.cljs` — may need view registration (if UI is custom)

## 10. Command to Verify Compilation

```bash
make validate  # Runs lint, format-check, and tests
```

## Summary of Required New Cost Type

For "pay half your life (rounded up)", implement `:pay-half-life` cost type because:
- ✓ Follows existing cost type pattern (`:pay-life`, `:pay-x-life`)
- ✓ Encodes policy (fixed amount) in data (selection/fixed-amount)
- ✓ Reuses selection/executor infrastructure
- ✓ Decouples from card definition (cost type just triggers builder)
- ✓ Testable in isolation
