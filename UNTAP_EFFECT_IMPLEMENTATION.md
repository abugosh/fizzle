# Implementing the Untap Effect

This document provides the exact code needed to add `:effect/type :untap` to Fizzle, which unblocks Turnabout, Frantic Search, and Cloud of Faeries.

---

## IMPLEMENTATION: Add Untap Effect to zones.cljs

**File**: `/Users/abugosh/g/fizzle/src/main/fizzle/engine/effects/zones.cljs`

**Location**: Add this code after the `:bounce` effect (around line 130)

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

**What it does**:
1. Extracts the target player (`:effect/target`)
2. Gets the count limit from `:effect/count` (defaults to 999 if not specified)
3. Queries battlefield permanents matching the criteria (e.g., `{:match/types #{:land}}`)
4. Takes up to `count-limit` permanents
5. Sets each permanent's `:object/tapped` field to `false`

---

## TEST: Add to zones_test.cljs

**File**: `/Users/abugosh/g/fizzle/src/test/fizzle/engine/effects/zones_test.cljs`

Add this test block after the bounce tests:

```clojure
;; === Untap Effect Tests ===

(deftest untap-single-land-test
  (testing "Untap effect untaps a single tapped land"
    (let [db (th/create-test-db)
          [db land-id] (th/add-card-to-zone db :mountain :battlefield :player-1)
          land-eid (q/get-object-eid db land-id)
          ;; Tap the land
          db-tapped (d/db-with db [[:db/add land-eid :object/tapped true]])
          ;; Untap it via effect
          db-untapped (effects/execute-effect db-tapped :player-1
                        {:effect/type :untap
                         :effect/target :self
                         :effect/count 1})]
      (is (not (d/q '[:find ?tapped .
                      :in $ ?land-id
                      :where [?land :object/id ?land-id]
                      [?land :object/tapped ?tapped]]
                    db-untapped land-id))
          "Land should be untapped"))))


(deftest untap-multiple-with-limit-test
  (testing "Untap respects count limit"
    (let [db (th/create-test-db)
          ;; Add 3 mountains
          [db m1-id] (th/add-card-to-zone db :mountain :battlefield :player-1)
          [db m2-id] (th/add-card-to-zone db :mountain :battlefield :player-1)
          [db m3-id] (th/add-card-to-zone db :mountain :battlefield :player-1)
          ;; Tap all 3
          eids (map (fn [id] (q/get-object-eid db id)) [m1-id m2-id m3-id])
          db-tapped (reduce (fn [d eid]
                              (d/db-with d [[:db/add eid :object/tapped true]]))
                            db eids)
          ;; Untap only 2
          db-untapped (effects/execute-effect db-tapped :player-1
                        {:effect/type :untap
                         :effect/target :self
                         :effect/count 2})]
      ;; Query to check how many are untapped
      (let [untapped-count (d/q '[:find (count ?land) .
                                  :in $ ?player
                                  :where [?land :object/owner ?player-eid]
                                  [?player :player/id ?player]
                                  [?player-eid :player/id ?player]
                                  [?land :object/zone :battlefield]
                                  [?land :object/tapped false]]
                               db-untapped :player-1)]
        (is (= 2 untapped-count)
            "Should have 2 untapped lands (1 still tapped)")))))


(deftest untap-with-type-criteria-test
  (testing "Untap only untaps permanents matching type criteria"
    (let [db (th/create-test-db)
          ;; Add 1 mountain and 1 artifact
          [db land-id] (th/add-card-to-zone db :mountain :battlefield :player-1)
          [db artifact-id] (th/add-card-to-zone db :sol-ring :battlefield :player-1)
          ;; Tap both
          land-eid (q/get-object-eid db land-id)
          artifact-eid (q/get-object-eid db artifact-id)
          db-tapped (-> db
                        (d/db-with [[:db/add land-eid :object/tapped true]])
                        (d/db-with [[:db/add artifact-eid :object/tapped true]]))
          ;; Untap only lands
          db-untapped (effects/execute-effect db-tapped :player-1
                        {:effect/type :untap
                         :effect/target :self
                         :effect/count 999
                         :effect/criteria {:match/types #{:land}}})]
      ;; Check land is untapped
      (is (not (d/q '[:find ?tapped .
                      :in $ ?land-id
                      :where [?land :object/id ?land-id]
                      [?land :object/tapped ?tapped]]
                    db-untapped land-id))
          "Land should be untapped")
      ;; Check artifact is still tapped
      (is (d/q '[:find ?tapped .
                 :in $ ?artifact-id
                 :where [?artifact :object/id ?artifact-id]
                 [?artifact :object/tapped ?tapped]]
               db-untapped artifact-id)
          "Artifact should still be tapped"))))


(deftest untap-opponent-lands-test
  (testing "Untap can target opponent's permanents"
    (let [db (th/create-test-db)
          [db _] (th/add-opponent db)
          ;; Add land to opponent
          [db opp-land-id] (th/add-card-to-zone db :mountain :battlefield :player-2)
          opp-land-eid (q/get-object-eid db opp-land-id)
          ;; Tap it
          db-tapped (d/db-with db [[:db/add opp-land-eid :object/tapped true]])
          ;; Player 1 untaps opponent's land
          db-untapped (effects/execute-effect db-tapped :player-1
                        {:effect/type :untap
                         :effect/target :opponent
                         :effect/count 1})]
      (is (not (d/q '[:find ?tapped .
                      :in $ ?land-id
                      :where [?land :object/id ?land-id]
                      [?land :object/tapped ?tapped]]
                    db-untapped opp-land-id))
          "Opponent's land should be untapped"))))


(deftest untap-no-permanents-to-untap-test
  (testing "Untap with no matching permanents is a no-op"
    (let [db (th/create-test-db)
          [db artifact-id] (th/add-card-to-zone db :sol-ring :battlefield :player-1)
          result (effects/execute-effect db :player-1
                   {:effect/type :untap
                    :effect/target :self
                    :effect/count 3
                    :effect/criteria {:match/types #{:land}}})]
      ;; Should not throw, just return db unchanged (or with no tapped changes)
      (is (= result db)
          "Should be a no-op when no permanents match"))))
```

---

## CARD USAGE EXAMPLES

### Turnabout (Modal Spell)

```clojure
(ns fizzle.cards.blue.turnabout)

(def card
  {:card/id :turnabout
   :card/name "Turnabout"
   :card/cmc 2
   :card/mana-cost {:blue 2}
   :card/colors #{:blue}
   :card/types #{:instant}
   :card/text "Choose one —\n• Tap up to three target artifact creatures.\n• Untap up to three target artifact creatures.\n• Tap up to three target creatures.\n• Untap up to three target creatures."

   :card/modes
   [{:mode/label "Tap up to three target artifact creatures"
     :mode/targeting [{:target/id :artifact-creatures
                       :target/type :object
                       :target/zone :battlefield
                       :target/controller :any
                       :target/criteria {:match/types #{:artifact :creature}}
                       :target/required false}]
     :mode/effects [{:effect/type :tap
                     :effect/target :artifact-creatures
                     :effect/count 3}]}

    {:mode/label "Untap up to three target artifact creatures"
     :mode/targeting [{:target/id :artifact-creatures
                       :target/type :object
                       :target/zone :battlefield
                       :target/controller :any
                       :target/criteria {:match/types #{:artifact :creature}}
                       :target/required false}]
     :mode/effects [{:effect/type :untap
                     :effect/target :artifact-creatures
                     :effect/count 3}]}

    {:mode/label "Tap up to three target creatures"
     :mode/targeting [{:target/id :creatures
                       :target/type :object
                       :target/zone :battlefield
                       :target/controller :any
                       :target/criteria {:match/types #{:creature}}
                       :target/required false}]
     :mode/effects [{:effect/type :tap
                     :effect/target :creatures
                     :effect/count 3}]}

    {:mode/label "Untap up to three target creatures"
     :mode/targeting [{:target/id :creatures
                       :target/type :object
                       :target/zone :battlefield
                       :target/controller :any
                       :target/criteria {:match/types #{:creature}}
                       :target/required false}]
     :mode/effects [{:effect/type :untap
                     :effect/target :creatures
                     :effect/count 3}]}]})
```

### Cloud of Faeries (Creature with ETB Untap)

```clojure
(ns fizzle.cards.blue.cloud-of-faeries)

(def card
  {:card/id :cloud-of-faeries
   :card/name "Cloud of Faeries"
   :card/cmc 1
   :card/mana-cost {:blue 1}
   :card/colors #{:blue}
   :card/types #{:creature}
   :card/subtypes #{:faerie}
   :card/power 0
   :card/toughness 2
   :card/keywords #{:flying}
   :card/text "Flying\nWhen Cloud of Faeries enters the battlefield, untap up to two target lands."

   :card/etb-effects
   [{:effect/type :untap
     :effect/target :any-player
     :effect/count 2
     :effect/criteria {:match/types #{:land}}}]})
```

### Frantic Search (Draw + Discard + Untap)

```clojure
(ns fizzle.cards.blue.frantic-search)

(def card
  {:card/id :frantic-search
   :card/name "Frantic Search"
   :card/cmc 1
   :card/mana-cost {:blue 1}
   :card/colors #{:blue}
   :card/types #{:sorcery}
   :card/text "Draw two cards, then discard two cards. Then untap up to three lands."

   :card/effects
   [{:effect/type :draw
     :effect/amount 2}

    {:effect/type :discard
     :effect/count 2
     :effect/selection :player}

    {:effect/type :untap
     :effect/target :self
     :effect/count 3
     :effect/criteria {:match/types #{:land}}}]})
```

---

## EFFECT PARAMETERS REFERENCE

### `:effect/type :untap`

| Parameter | Type | Required | Default | Example |
|-----------|------|----------|---------|---------|
| `:effect/target` | Keyword | Yes | — | `:self`, `:opponent`, `:any-player` |
| `:effect/count` | Integer | No | 999 | `2`, `3`, `5` |
| `:effect/criteria` | Map | No | `{}` (all permanents) | `{:match/types #{:land}}` |

### Examples

**Untap all your permanents**:
```clojure
{:effect/type :untap
 :effect/target :self}
```

**Untap up to 3 target lands**:
```clojure
{:effect/type :untap
 :effect/target :any-player
 :effect/count 3
 :effect/criteria {:match/types #{:land}}}
```

**Untap up to 2 opponent's creatures**:
```clojure
{:effect/type :untap
 :effect/target :opponent
 :effect/count 2
 :effect/criteria {:match/types #{:creature}}}
```

**Untap up to 4 permanents (any type)**:
```clojure
{:effect/type :untap
 :effect/target :self
 :effect/count 4}
```

---

## VERIFICATION CHECKLIST

After implementing, verify:

- [ ] Code compiles: `make fmt && make lint`
- [ ] Tests pass: `make test`
- [ ] Card can be cast from hand
- [ ] Untap effect executes during resolution
- [ ] Only specified count of permanents are untapped
- [ ] Type criteria filters correctly
- [ ] Opponent untaps work correctly
- [ ] No-op when no matching permanents exist
- [ ] Card files register in registry.cljs

---

## COMPARISON: Tap vs Untap

Once you implement `:untap`, you may want to implement `:tap` similarly for completeness.

**Tap effect** (identical pattern, but sets to `true`):

```clojure
(defmethod effects/execute-effect-impl :tap
  [db _player-id effect _object-id]
  (let [target-player (:effect/target effect)
        count-limit (or (:effect/count effect) 999)
        criteria (or (:effect/criteria effect) {})
        permanents (or (queries/query-zone-by-criteria
                         db target-player :battlefield criteria)
                      [])
        to-tap (take count-limit permanents)]
    (reduce (fn [db' obj]
              (let [obj-eid (:db/id obj)]
                (d/db-with db' [[:db/add obj-eid :object/tapped true]])))
            db
            to-tap)))
```

---

## TIMELINE

- **Implementation**: 20 min (untap effect code)
- **Testing**: 15 min (write + verify tests)
- **Card definitions**: 15 min (Turnabout)
- **Card definitions**: 10 min (Cloud of Faeries)
- **Card definitions**: 10 min (Frantic Search)
- **Card tests**: 30 min (8-10 tests per card)
- **Integration verification**: 10 min

**Total**: ~2 hours for all three cards

---

## NOTES FOR IMPLEMENTATION

1. **No modal selection needed**: Unlike discard/tutor, untap doesn't require player selection if count ≤ permanents. Fizzle will automatically select any permanents matching the criteria (up to the limit). This is how effects like "untap up to 3 lands" work in Magic.

2. **Criteria filtering**: The `queries/query-zone-by-criteria` helper already handles type matching. Use it just like the other effects do.

3. **Order of untap**: Permanents are untapped in the order they appear in the database query. For simplicity, we don't need to match Magic's specific ordering rules.

4. **Simultaneous action**: Untaps happen simultaneously (they're all in one reduce), which matches Magic semantics.

5. **No range checking**: If effect asks to untap 5 lands but only 2 exist, only 2 untap (via `take`). This is correct behavior.
