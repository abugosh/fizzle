# Card Testing Strategy

This guide defines the testing standards for card implementations in Fizzle. Every card must have a dedicated test file that covers the mandatory categories below.

## Philosophy

**Test through real event dispatch, not manual handler reimplementation.** Tests should exercise the same code paths as the running application. If a test constructs game state and calls production functions directly (like `rules/cast-spell`, `rules/resolve-spell`, `ability-events/activate-mana-ability`), it tests real behavior. If a test copies handler logic into the test file and asserts on that copy, it tests nothing useful — the copy can drift from production while tests stay green.

**Use shared helpers from `fizzle.test-helpers`.** All test files require `[fizzle.test-helpers :as th]` for game state setup. Never define local `create-test-db` or `add-card-to-zone` helpers.

**Verify exact values, not just existence.** Assertions should check specific expected values. `(is (= :dark-ritual (:card/id card)))` catches real bugs. `(is (some? (:card/id card)))` catches almost nothing.

## Test File Structure

```
src/test/fizzle/cards/<card_name>_test.cljs
```

Every card test file follows this structure:

```clojure
(ns fizzle.cards.<card-name>-test
  "Tests for <Card Name> — <brief description>.
   <What this file tests:>
   - Card definition
   - Cast/resolve happy path
   - ..."
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [fizzle.cards.iggy-pop :as cards]     ; or specific card ns
    [fizzle.db.queries :as q]
    [fizzle.engine.rules :as rules]
    [fizzle.test-helpers :as th]))

;; === File-specific helpers (if needed) ===
;; Keep minimal. Only for card-specific setup that doesn't belong in th/.

;; === Card definition tests ===
;; === Cast-resolve tests ===
;; === Cannot-cast guards ===
;; === Storm count tests ===
;; === (Conditional categories as applicable) ===
;; === Edge case tests ===
```

## Mandatory Test Categories (A–D)

Every card MUST have tests for these four categories.

### Category A: Card Definition

Verify ALL fields of the card definition against expected values. This catches data entry errors and ensures the card data matches the oracle text.

**IMPORTANT:** Category A tests must NOT be tautological. Check exact values, not just presence.

**Good — catches real bugs:**
```clojure
(deftest brain-freeze-card-definition-test
  (testing "Brain Freeze card data is correct"
    (let [card cards/brain-freeze]
      (is (= :brain-freeze (:card/id card)))
      (is (= "Brain Freeze" (:card/name card)))
      (is (= 2 (:card/cmc card)))
      (is (= {:colorless 1 :blue 1} (:card/mana-cost card)))
      (is (= #{:instant} (:card/types card)))
      (is (= #{:blue} (:card/colors card)))
      (is (= #{:storm} (:card/keywords card)))
      (is (= 1 (count (:card/effects card))))
      (let [effect (first (:card/effects card))]
        (is (= :mill (:effect/type effect)))
        (is (= 3 (:effect/amount effect)))
        (is (= :any-player (:effect/target effect)))))))
```

**Bad — tautological, catches nothing:**
```clojure
;; DO NOT WRITE TESTS LIKE THIS
(deftest bad-card-definition-test
  (is (some? (:card/id card)))           ; any id passes
  (is (string? (:card/name card)))       ; any string passes
  (is (number? (:card/cmc card)))        ; any number passes
  (is (set? (:card/types card)))         ; any set passes
  (is (contains? (:card/types card) :instant))) ; doesn't verify ONLY instant
```

**What to verify per card type:**

| Card type | Required fields |
|-----------|----------------|
| All cards | `:card/id`, `:card/name`, `:card/cmc`, `:card/mana-cost`, `:card/types`, `:card/colors` |
| Spells | Above + `:card/effects` (verify each effect's type and parameters) |
| Lands | Above + `:card/abilities` (verify ability type, cost, produces) |
| Storm spells | Above + `:card/keywords` contains `:storm` |
| Flashback spells | Above + `:card/alternate-costs` (verify id, zone, cost, on-resolve) |
| Targeted spells | Above + `:card/targeting` (verify each requirement's id, type, zone, criteria) |
| ETB permanents | Above + `:card/etb-effects` |
| Cards with conditions | Above + `:card/conditional-effects` (verify threshold/condition structure) |

### Category B: Cast-Resolve Happy Path

Test the complete cast-resolve cycle with valid mana and game state. Verify:
- Spell moves from hand to stack to graveyard
- Effects execute correctly (mana added, cards drawn, etc.)
- Storm count increments

```clojure
(deftest dark-ritual-cast-resolve-test
  (testing "Dark Ritual adds 3 black mana on resolve"
    (let [db (th/create-test-db)
          [db' obj-id] (th/add-card-to-zone db :dark-ritual :hand :player-1)
          db-mana (mana/add-mana db' :player-1 {:black 1})
          db-cast (rules/cast-spell db-mana :player-1 obj-id)
          db-resolved (rules/resolve-spell db-cast :player-1 obj-id)]
      (is (= 3 (:black (q/get-mana-pool db-resolved :player-1))))
      (is (= :graveyard (th/get-object-zone db-resolved obj-id))))))
```

### Category C: Cannot-Cast Guards

Verify the spell cannot be cast when preconditions are not met.

Common guards to test:
- **Insufficient mana** — no mana or wrong colors
- **Wrong zone** — card not in hand (or graveyard for flashback)
- **No valid targets** — for targeted spells
- **Wrong phase** — sorcery-speed spells during wrong phase (when implemented)

```clojure
(deftest brain-freeze-cannot-cast-without-mana-test
  (testing "Brain Freeze cannot be cast without {1}{U} mana"
    (let [db (th/create-test-db)
          [db' obj-id] (th/add-card-to-zone db :brain-freeze :hand :player-1)]
      (is (false? (rules/can-cast? db' :player-1 obj-id))))))
```

### Category D: Storm Count

Every spell should increment storm count when cast. This catches regressions in the cast pipeline.

```clojure
(deftest dark-ritual-increments-storm-test
  (testing "Casting Dark Ritual increments storm count"
    (let [db (th/create-test-db)
          [db' obj-id] (th/add-card-to-zone db :dark-ritual :hand :player-1)
          db-mana (mana/add-mana db' :player-1 {:black 1})
          db-cast (rules/cast-spell db-mana :player-1 obj-id)]
      (is (= 1 (q/get-storm-count db-cast :player-1))))))
```

## Conditional Test Categories (E–I)

Add these categories when the card has the corresponding mechanic.

### Category E: Selection/Modal Tests

**When:** Card requires player choices (discard selection, mode choice, etc.)

Test the full selection flow:
1. Spell resolution creates pending selection state
2. Player toggles choices
3. Confirmation executes the selection

See: `src/test/fizzle/cards/careful_study_test.cljs` — tests draw-then-discard selection flow through real event dispatch.

### Category F: Targeting Tests

**When:** Card has `:card/targeting` requirements.

Test:
- Target selection prompt appears during cast
- Effect resolves against chosen target
- Spell fizzles when target becomes invalid before resolution

See: `src/test/fizzle/cards/recoup_test.cljs` — tests cast-time targeting, fizzle on target removal, and targeting restrictions (sorceries only, own graveyard only).

### Category G: Edge Cases

**When:** Always — every card should have at least 2 edge case tests.

Common edge cases:
- **Empty zones** — mill with empty library, discard with empty hand
- **Partial resources** — mill 3 from 1-card library, draw more than available
- **Boundary conditions** — exactly lethal damage, exactly enough mana
- **Zone restrictions** — ability cannot activate from wrong zone (hand, graveyard, exile)
- **Tapped state** — mana ability cannot activate when already tapped
- **No-op conditions** — effect does nothing when condition not met

See: `src/test/fizzle/cards/gemstone_mine_test.cljs` — tests cannot-activate from graveyard, hand, and when tapped. Also tests mana produced even on sacrifice tap.

### Category H: Flashback Tests

**When:** Card has `:card/alternate-costs` with `:flashback`.

Test:
- Card castable from graveyard with flashback cost
- Card exiles after flashback resolution
- Card NOT castable from exile via flashback

See: `src/test/fizzle/cards/recoup_test.cljs` — tests flashback from graveyard, exile on resolve, and zone restriction (exile blocks flashback).

### Category I: Trigger/Ability Tests

**When:** Card has `:card/abilities` or `:card/triggers`.

Test:
- Ability activation produces correct effect
- Ability costs are paid (tap, sacrifice, counter removal)
- Triggers fire at correct time

See: `src/test/fizzle/cards/gemstone_mine_test.cljs` — tests mana ability activation, counter depletion per tap, sacrifice on last counter.

## Minimum Test Counts

| Card type | Minimum tests | Rationale |
|-----------|---------------|-----------|
| Simple spell (e.g., Dark Ritual) | 5 | A(1) + B(1) + C(1) + D(1) + G(1) |
| Targeted spell (e.g., Brain Freeze) | 8 | A(1) + B(2) + C(1) + D(1) + F(2) + G(1) |
| Selection spell (e.g., Careful Study) | 8 | A(1) + B(1) + C(1) + D(1) + E(3) + G(1) |
| Land with ability (e.g., Gemstone Mine) | 8 | A(2) + I(3) + G(3) |
| Flashback spell (e.g., Recoup) | 12 | A(2) + B(1) + C(2) + D(1) + F(2) + H(3) + G(1) |
| Storm spell (e.g., Brain Freeze) | 12 | A(1) + B(2) + C(1) + D(3) + F(2) + G(2) + storm-specific(1) |

These are minimums. Complex cards (e.g., Ill-Gotten Gains with multi-player selection) will naturally have more.

## Parameterization Patterns

### When to use `doseq`

Use `doseq` when multiple tests differ ONLY by a parameter value. This prevents copy-paste maintenance burden.

**Good — parameterized color tests:**
```clojure
(def ^:private mana-colors [:black :blue :white :red :green])

(deftest test-lotus-petal-sacrifice-for-any-color
  (doseq [color mana-colors]
    (testing (str "Lotus Petal sacrifices for " (name color) " mana")
      (let [db (th/create-test-db)
            [db' obj-id] (th/add-card-to-zone db :lotus-petal :battlefield :player-1)
            db'' (ability-events/activate-mana-ability db' :player-1 obj-id color)]
        (is (= :graveyard (th/get-object-zone db'' obj-id))
            (str "Should be in graveyard after " (name color)))
        (is (= 1 (get (q/get-mana-pool db'' :player-1) color))
            (str (name color) " mana should be 1"))))))
```

### When NOT to use `doseq`

Keep tests separate when they have meaningfully different setup, assertions, or document distinct behaviors. For example, testing "cannot activate from graveyard" vs "cannot activate from hand" are conceptually different guards even though the assertion shape is similar — separate tests make failures more informative.

## Anti-Patterns

### 1. Reimplementing production handlers in tests

**Before (anti-pattern):**
```clojure
;; DO NOT DO THIS — manually reimplements the event handler
(defn fake-resolve-careful-study [db player-id]
  (let [hand (q/get-objects-in-zone db player-id :hand)
        draw-effect {:effect/type :draw :effect/amount 2}
        db' (effects/execute-effect db player-id draw-effect)]
    ;; ... 50+ more lines reimplementing production logic
    db'))
```

**After (correct):**
```clojure
;; Test through real event dispatch
(let [db-cast (rules/cast-spell db-mana :player-1 obj-id)
      db-resolved (rules/resolve-spell db-cast :player-1 obj-id)]
  ;; Assert on actual resolved state
  ...)
```

See: `src/test/fizzle/cards/careful_study_test.cljs` — the rewritten version tests through `rules/cast-spell` and `resolution/resolve-spell-with-selection`.

### 2. Copy-pasted test variants

**Before (anti-pattern):**
```clojure
;; 5 nearly identical tests, one per color
(deftest test-lotus-petal-black ...)
(deftest test-lotus-petal-blue ...)
(deftest test-lotus-petal-white ...)
(deftest test-lotus-petal-red ...)
(deftest test-lotus-petal-green ...)
```

**After (correct):**
```clojure
;; Single parameterized test
(doseq [color [:black :blue :white :red :green]]
  (testing (str "Lotus Petal sacrifices for " (name color) " mana")
    ...))
```

See: `src/test/fizzle/cards/lotus_petal_test.cljs` and `src/test/fizzle/cards/led_test.cljs`.

### 3. Tautological assertions

**Bad:** `(is (some? (:card/id card)))` — any non-nil value passes.
**Good:** `(is (= :dark-ritual (:card/id card)))` — only the correct value passes.

### 4. Mocking re-frame dispatch

Card tests should use real function calls (`rules/cast-spell`, `rules/resolve-spell`, etc.), not mock `rf/dispatch`. The point is integration testing — verifying the full pipeline works.

### 5. Test-only methods on production code

Never add methods to production namespaces purely for test access. If a test needs to inspect internal state, use Datascript queries directly.

## Shared Test Helpers Reference

All helpers are in `fizzle.test-helpers` (`src/test/fizzle/test_helpers.cljs`).

| Helper | Signature | Returns | Purpose |
|--------|-----------|---------|---------|
| `create-test-db` | `([] [opts])` | `db` | Game state with all cards loaded. Opts: `{:mana {:blue 1} :life 20}` |
| `add-card-to-zone` | `[db card-id zone owner]` | `[db obj-id]` | Add one card object to a zone |
| `add-cards-to-library` | `[db card-ids owner]` | `[db obj-ids]` | Add cards to library with sequential positions |
| `add-cards-to-graveyard` | `[db card-ids owner]` | `[db obj-ids]` | Add cards to graveyard |
| `get-zone-count` | `[db zone owner]` | `int` | Count objects in a zone |
| `get-object-zone` | `[db obj-id]` | `keyword` | Get zone of an object |
| `get-hand-count` | `[db owner]` | `int` | Shorthand for hand zone count |
| `add-opponent` | `[db]` | `db` | Add `:player-2` with standard settings |

**Helpers are card-agnostic.** Pass card-id keywords (e.g., `:dark-ritual`), not card definition maps.

## Exemplar Test Files

These files demonstrate the testing patterns described above:

| File | Demonstrates |
|------|-------------|
| `cards/brain_freeze_test.cljs` | Full storm pipeline, targeting, parameterized copy tests, edge cases |
| `cards/recoup_test.cljs` | Targeting, flashback, fizzle behavior, grant expiration, zone restrictions |
| `cards/gemstone_mine_test.cljs` | Land ability testing, counter depletion, parameterized colors, zone guards |
| `cards/lotus_petal_test.cljs` | Mana ability, `doseq` parameterization, zone restrictions |
| `cards/led_test.cljs` | Sacrifice + discard combo, `doseq` parameterization, tapped state guard |
| `cards/careful_study_test.cljs` | Selection/modal flow, draw-then-discard integration |
| `engine/effects_test.cljs` | Engine-level effect testing with thorough corner cases |
