# Quick Reference: Card Implementation Patterns

**Start here for the 2-minute overview of patterns you need.**

---

## The Three Cards You Want to Build

| Card | Type | Complexity | Blockers |
|------|------|-----------|----------|
| **Turnabout** | Modal instant (4 modes) | Medium | Needs `:untap` effect |
| **Cloud of Faeries** | 0/2 creature, flying, ETB untap | Medium | Needs `:untap` effect |
| **Frantic Search** | Sorcery: draw+discard+untap | Medium | Needs `:untap` effect |

**All three are unblocked by adding a single effect type (30 min of work)**

---

## Pattern 1: Modal Spells (Turnabout Template)

Use `:card/modes` instead of `:card/effects`.

**Reference card**: `/Users/abugosh/g/fizzle/src/main/fizzle/cards/blue/blue_elemental_blast.cljs`

```clojure
:card/modes
[{:mode/label "Mode 1 description"
  :mode/targeting [{:target/id :target-id
                    :target/type :object
                    :target/zone :battlefield
                    :target/controller :any
                    :target/criteria {:match/types #{:artifact}}}]
  :mode/effects [{:effect/type :destroy
                  :effect/target-ref :target-id}]}

 {:mode/label "Mode 2 description"
  :mode/targeting [...]
  :mode/effects [...]}]
```

**Turnabout needs 4 modes**:
1. Tap artifacts
2. Untap artifacts
3. Tap creatures
4. Untap creatures

---

## Pattern 2: Creature Cards (Cloud of Faeries Template)

Define power/toughness at **card level** (not object level).

**Reference card**: `/Users/abugosh/g/fizzle/src/main/fizzle/cards/green/nimble_mongoose.cljs`

```clojure
{:card/id :cloud-of-faeries
 :card/name "Cloud of Faeries"
 :card/cmc 1
 :card/mana-cost {:blue 1}
 :card/colors #{:blue}
 :card/types #{:creature}           ; ← Must have :creature
 :card/subtypes #{:faerie}
 :card/power 0                       ; ← At card level!
 :card/toughness 2                   ; ← At card level!
 :card/keywords #{:flying}
 :card/text "..."
 
 :card/etb-effects [...]             ; ← ETB effects go here
}
```

---

## Pattern 3: Draw + Discard Chaining (Frantic Search)

Effects in `:card/effects` array execute sequentially. Interactive effects pause.

**Reference cards**: 
- `/Users/abugosh/g/fizzle/src/main/fizzle/cards/blue/opt.cljs` (scry then draw)
- `/Users/abugosh/g/fizzle/src/main/fizzle/cards/blue/careful_study.cljs` (draw then discard)

```clojure
:card/effects
[{:effect/type :draw
  :effect/amount 2}                  ; Non-interactive

 {:effect/type :discard
  :effect/count 2                    ; ← Count, not amount
  :effect/selection :player}         ; ← Pauses here for selection

 {:effect/type :untap
  :effect/target :self
  :effect/count 3
  :effect/criteria {:match/types #{:land}}}]  ; ← Resumes here
```

---

## Pattern 4: ETB Effects (Cloud of Faeries)

Use `:card/etb-effects` array (separate from `:card/effects`).

**Reference card**: `/Users/abugosh/g/fizzle/src/main/fizzle/cards/lands/gemstone_mine.cljs`

```clojure
:card/etb-effects
[{:effect/type :untap
  :effect/target :any-player         ; Can target any player's lands
  :effect/count 2
  :effect/criteria {:match/types #{:land}}}]
```

Executes automatically when permanent enters.

---

## Effect Types Quick Lookup

**Zone/Card Operations**:
- `:draw` — Draw cards
- `:discard` — Discard cards (needs `:effect/selection :player`)
- `:scry` — Look and reorder library
- `:mill` — Mill cards
- `:tutor` — Search library
- `:destroy` — Destroy permanent
- `:bounce` — Return to hand
- `:sacrifice` — Sacrifice permanent

**Mana & Counters**:
- `:add-mana` — Add mana
- `:add-counters` — Add counters

**Damage**:
- `:deal-damage` — Damage to player (not creatures yet)
- `:gain-life` — Gain life

**Missing**:
- `:untap` — **NOT IMPLEMENTED** (blocking your 3 cards)

---

## File Locations

| What | Where |
|------|-------|
| Card definitions | `/Users/abugosh/g/fizzle/src/main/fizzle/cards/{color}/{card_name}.cljs` |
| Effects | `/Users/abugosh/g/fizzle/src/main/fizzle/engine/effects/zones.cljs` |
| Tests | `/Users/abugosh/g/fizzle/src/test/fizzle/cards/{color}/{card_name}_test.cljs` |
| Registry | `/Users/abugosh/g/fizzle/src/main/fizzle/cards/registry.cljs` |
| Schema | `/Users/abugosh/g/fizzle/src/main/fizzle/db/schema.cljs` |

---

## Targeting Reference

```clojure
;; Object targeting (permanents, spells)
{:target/id :my-target
 :target/type :object
 :target/zone :battlefield              ; :battlefield, :graveyard, :stack, :hand, :library
 :target/controller :any                ; :any or :controller
 :target/criteria {:match/types #{:artifact :creature}}
 :target/required true}

;; Player targeting
{:target/id :my-player
 :target/type :player
 :target/options [:self :opponent]}

;; Multi-target constraint
{:target/id :second-target
 :target/same-controller-as :first-target}  ; Must match first's controller
```

---

## The One Thing Blocking You

Add `:untap` effect to `/Users/abugosh/g/fizzle/src/main/fizzle/engine/effects/zones.cljs`:

```clojure
(defmethod effects/execute-effect-impl :untap
  [db _player-id effect _object-id]
  (let [target-player (:effect/target effect)
        count-limit (or (:effect/count effect) 999)
        criteria (or (:effect/criteria effect) {})
        permanents (or (queries/query-zone-by-criteria
                         db target-player :battlefield criteria)
                      [])
        to-untap (take count-limit permanents)]
    (reduce (fn [db' obj]
              (let [obj-eid (:db/id obj)]
                (d/db-with db' [[:db/add obj-eid :object/tapped false]])))
            db
            to-untap)))
```

That's it. ~20 lines. Then all three cards work.

---

## Test Template

```clojure
(deftest test-card-definition
  (testing "Card identity"
    (is (= :card-id (:card/id card)))
    (is (= "Card Name" (:card/name card)))
    (is (= X (:card/cmc card)))))

(deftest test-cast-and-resolve
  (let [db (th/create-test-db {:mana {:blue 1}})
        [db _] (th/add-cards-to-library db [...] :player-1)
        [db obj-id] (th/add-card-to-zone db :card-id :hand :player-1)
        db-cast (rules/cast-spell db :player-1 obj-id)]
    ;; Assert effects happened
    (is ...)))

(deftest test-cannot-cast-without-mana
  (let [db (th/create-test-db)
        [db obj-id] (th/add-card-to-zone db :card-id :hand :player-1)]
    (is (false? (rules/can-cast? db :player-1 obj-id)))))

(deftest test-storm-count
  (let [db (th/create-test-db {:mana {...}})
        [db obj-id] (th/add-card-to-zone db :card-id :hand :player-1)
        db-cast (rules/cast-spell db :player-1 obj-id)]
    (is (= 1 (q/get-storm-count db-cast :player-1)))))
```

**Mandatory**: Card def, cast-resolve, cannot-cast guards, storm count

---

## Next Steps (In Order)

1. **Read** UNTAP_EFFECT_IMPLEMENTATION.md (~5 min)
2. **Add** untap effect to effects/zones.cljs (~15 min)
3. **Test** untap: `make test` (~5 min)
4. **Create** card files for Turnabout, Cloud of Faeries, Frantic Search (~30 min)
5. **Add** tests for each card (~30 min)
6. **Update** registry.cljs to require new cards (~5 min)
7. **Validate**: `make validate` (~10 min)

**Total: ~2 hours**

---

## Color Directories

- Blue: `/Users/abugosh/g/fizzle/src/main/fizzle/cards/blue/`
- Green: `/Users/abugosh/g/fizzle/src/main/fizzle/cards/green/`
- Red: `/Users/abugosh/g/fizzle/src/main/fizzle/cards/red/`
- White: `/Users/abugosh/g/fizzle/src/main/fizzle/cards/white/`
- Black: `/Users/abugosh/g/fizzle/src/main/fizzle/cards/black/`
- Multicolor: `/Users/abugosh/g/fizzle/src/main/fizzle/cards/multicolor/`

---

## Related Documentation

- Full patterns: See **CARD_PATTERNS_REFERENCE.md**
- Implementation details: See **UNTAP_EFFECT_IMPLEMENTATION.md**
- Full investigation: See **TURNABOUT_INVESTIGATION.md**

---

## Pro Tips

1. **Copy from existing cards**: Don't write card defs from scratch. Use Blue Elemental Blast as template for modal, Careful Study for draw+discard, Nimble Mongoose for creatures.

2. **Tests first**: Write tests that match mandatory categories, then card definition, then effects. It's easier to verify what works.

3. **Criteria filters**: Use `{:match/types #{:land}}` to filter by type. Creatures/instants/artifacts all supported.

4. **Interactive effects**: Only `:discard`, `:tutor`, `:scry`, etc. pause. Non-interactive effects like `:untap` execute immediately.

5. **Multi-mode targeting**: Each mode targets independently. Player chooses mode first, then targets for that mode.

---

## Debugging Checklist

- [ ] Card requires in registry.cljs?
- [ ] Card uses :card/id (not name)?
- [ ] Card effects match implemented effect types?
- [ ] Creature card has power/toughness (at card level)?
- [ ] Modal card uses :card/modes (not :card/effects)?
- [ ] Tests use `rules/cast-spell`, not manual operations?
- [ ] Tests check mandatory categories (def, cast, guards, storm)?

---

That's everything you need. Start with UNTAP_EFFECT_IMPLEMENTATION.md.
