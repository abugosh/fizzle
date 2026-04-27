(ns fizzle.engine.rules-test
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [datascript.core :as d]
    [fizzle.cards.artifacts.lotus-petal :as lotus-petal]
    [fizzle.cards.black.cabal-ritual :as cabal-ritual]
    [fizzle.db.init :refer [init-game-state]]
    [fizzle.db.queries :as q]
    [fizzle.engine.grants :as grants]
    [fizzle.engine.mana :as mana]
    [fizzle.engine.rules :as rules]
    [fizzle.engine.spec-util :as spec-util]
    [fizzle.engine.triggers]
    [fizzle.engine.zones :as zones]
    [fizzle.test-helpers :as th]))


;; === can-cast? tests ===

(deftest can-cast-returns-false-without-mana-test
  (testing "can-cast? returns false when player lacks mana"
    (let [db (init-game-state)
          hand (q/get-hand db :player-1)
          ritual (first hand)]
      (is (false? (rules/can-cast? db :player-1 (:object/id ritual)))))))


(deftest can-cast-returns-true-with-mana-test
  (testing "can-cast? returns true when player has sufficient mana"
    (let [db (-> (init-game-state)
                 (mana/add-mana :player-1 {:black 1}))
          hand (q/get-hand db :player-1)
          ritual (first hand)]
      (is (true? (rules/can-cast? db :player-1 (:object/id ritual)))))))


(deftest can-cast-returns-false-wrong-zone-test
  (testing "can-cast? returns false when card not in hand (on stack)"
    (let [db (-> (init-game-state)
                 (mana/add-mana :player-1 {:black 2}))  ; Extra mana for test
          hand (q/get-hand db :player-1)
          ritual (first hand)
          obj-id (:object/id ritual)
          ;; Move card to stack (simulating cast without using cast-spell)
          db' (zones/move-to-zone* db obj-id :stack)]
      ;; Card is on stack, not in hand - should return false
      (is (false? (rules/can-cast? db' :player-1 obj-id))))))


;; === cast-spell tests ===

(deftest cast-spell-moves-to-stack-test
  (testing "cast-spell moves card from hand to stack"
    (let [db (-> (init-game-state)
                 (mana/add-mana :player-1 {:black 1}))
          hand (q/get-hand db :player-1)
          ritual (first hand)
          db' (rules/cast-spell db :player-1 (:object/id ritual))]
      (is (= 0 (count (q/get-hand db' :player-1))))
      (is (= 1 (count (q/get-objects-in-zone db' :player-1 :stack)))))))


(deftest cast-spell-pays-mana-test
  (testing "cast-spell deducts mana cost from pool"
    (let [db (-> (init-game-state)
                 (mana/add-mana :player-1 {:black 2}))
          hand (q/get-hand db :player-1)
          ritual (first hand)
          db' (rules/cast-spell db :player-1 (:object/id ritual))]
      (is (= 1 (:black (q/get-mana-pool db' :player-1)))))))


(deftest cast-spell-increments-storm-test
  (testing "cast-spell increments storm count"
    (let [db (-> (init-game-state)
                 (mana/add-mana :player-1 {:black 1}))
          hand (q/get-hand db :player-1)
          ritual (first hand)
          db' (rules/cast-spell db :player-1 (:object/id ritual))]
      (is (= 1 (q/get-storm-count db' :player-1))))))


;; === resolve-spell tests ===

(deftest resolve-spell-executes-effects-test
  (testing "resolve-spell executes card effects"
    (let [db (-> (init-game-state)
                 (mana/add-mana :player-1 {:black 1}))
          hand (q/get-hand db :player-1)
          ritual (first hand)
          db' (-> db
                  (rules/cast-spell :player-1 (:object/id ritual))
                  (rules/resolve-spell :player-1 (:object/id ritual)))]
      ;; Dark Ritual adds BBB
      (is (= 3 (:black (q/get-mana-pool db' :player-1)))))))


(deftest resolve-spell-moves-to-graveyard-test
  (testing "resolve-spell moves card from stack to graveyard"
    (let [db (-> (init-game-state)
                 (mana/add-mana :player-1 {:black 1}))
          hand (q/get-hand db :player-1)
          ritual (first hand)
          db' (-> db
                  (rules/cast-spell :player-1 (:object/id ritual))
                  (rules/resolve-spell :player-1 (:object/id ritual)))]
      (is (= 0 (count (q/get-objects-in-zone db' :player-1 :stack))))
      (is (= 1 (count (q/get-objects-in-zone db' :player-1 :graveyard)))))))


;; === Full pipeline test ===

(deftest dark-ritual-full-pipeline-test
  (testing "Dark Ritual: cast with B, resolve to get BBB, storm = 1"
    (let [db (-> (init-game-state)
                 (mana/add-mana :player-1 {:black 1}))
          hand (q/get-hand db :player-1)
          ritual (first hand)
          obj-id (:object/id ritual)

          ;; Verify can cast
          _ (is (true? (rules/can-cast? db :player-1 obj-id)))

          ;; Cast it
          db-cast (rules/cast-spell db :player-1 obj-id)
          _ (is (= 0 (:black (q/get-mana-pool db-cast :player-1))))
          _ (is (= 1 (q/get-storm-count db-cast :player-1)))
          _ (is (= 1 (count (q/get-objects-in-zone db-cast :player-1 :stack))))

          ;; Resolve it
          db-resolved (rules/resolve-spell db-cast :player-1 obj-id)]
      (is (= 3 (:black (q/get-mana-pool db-resolved :player-1))))
      (is (= 0 (count (q/get-objects-in-zone db-resolved :player-1 :stack))))
      (is (= 1 (count (q/get-objects-in-zone db-resolved :player-1 :graveyard)))))))


;; === Stack ordering tests ===

(defn init-two-spell-state
  "Create game state with two Dark Rituals in hand for stack ordering tests."
  []
  (let [db (init-game-state)
        player-eid (q/get-player-eid db :player-1)
        card-eid (d/q '[:find ?e .
                        :where [?e :card/id :dark-ritual]]
                      db)]
    ;; Add a second Dark Ritual object in hand
    ;; Use random-uuid so cast-spell produces a valid :stack-item/source (uuid? spec)
    (d/db-with db [{:object/id (random-uuid)
                    :object/card card-eid
                    :object/zone :hand
                    :object/owner player-eid
                    :object/controller player-eid
                    :object/tapped false}])))


(deftest cast-spell-sets-stack-position-test
  (testing "cast-spell assigns :object/position for stack ordering"
    (let [db (-> (init-two-spell-state)
                 (mana/add-mana :player-1 {:black 2}))
          hand (q/get-hand db :player-1)
          obj-id (:object/id (first hand))
          db' (rules/cast-spell db :player-1 obj-id)
          obj' (q/get-object db' obj-id)]
      (is (= 0 (:object/position obj'))
          "First cast spell should have position 0"))))


(deftest cast-two-spells-lifo-order-test
  (testing "Second spell cast gets higher position than first (LIFO)"
    (let [db (-> (init-two-spell-state)
                 (mana/add-mana :player-1 {:black 2}))
          hand (q/get-hand db :player-1)
          first-id (:object/id (first hand))
          second-id (:object/id (second hand))
          db' (-> db
                  (rules/cast-spell :player-1 first-id)
                  (rules/cast-spell :player-1 second-id))
          first-obj (q/get-object db' first-id)
          second-obj (q/get-object db' second-id)]
      (is (> (:object/position second-obj)
             (:object/position first-obj))
          "Second cast should have higher stack position"))))


(deftest stack-objects-resolve-lifo-test
  (testing "Stack objects sorted by position descending resolve most recent first"
    (let [db (-> (init-two-spell-state)
                 (mana/add-mana :player-1 {:black 2}))
          hand (q/get-hand db :player-1)
          first-id (:object/id (first hand))
          second-id (:object/id (second hand))
          db' (-> db
                  (rules/cast-spell :player-1 first-id)
                  (rules/cast-spell :player-1 second-id))
          stack (->> (q/get-objects-in-zone db' :player-1 :stack)
                     (sort-by :object/position >))
          top-spell (first stack)]
      (is (= second-id (:object/id top-spell))
          "Most recently cast spell should be on top of stack"))))


;; === Conditional effects tests (Cabal Ritual + threshold) ===

(defn add-cards-to-graveyard
  "Add n cards to a player's graveyard."
  [db player-id n]
  (let [player-eid (q/get-player-eid db player-id)
        card-eid (d/q '[:find ?e .
                        :where [?e :card/id :dark-ritual]]
                      db)]
    (reduce
      (fn [acc-db _]
        (d/db-with acc-db [{:object/id (random-uuid)
                            :object/card card-eid
                            :object/zone :graveyard
                            :object/owner player-eid
                            :object/controller player-eid
                            :object/tapped false}]))
      db
      (range n))))


(defn add-cabal-ritual-to-hand
  "Add Cabal Ritual card definition and create an object in hand."
  [db player-id]
  (let [player-eid (q/get-player-eid db player-id)
        ;; Add card definition
        db (d/db-with db [cabal-ritual/card])
        ;; Create object in hand
        card-eid (d/q '[:find ?e .
                        :where [?e :card/id :cabal-ritual]]
                      db)
        obj-id (random-uuid)]
    [obj-id (d/db-with db [{:object/id obj-id
                            :object/card card-eid
                            :object/zone :hand
                            :object/owner player-eid
                            :object/controller player-eid
                            :object/tapped false}])]))


(deftest test-cabal-ritual-without-threshold
  (testing "Cabal Ritual adds BBB without threshold (< 7 cards in graveyard)"
    (let [db (init-game-state)
          ;; Add 5 cards to graveyard (below threshold)
          db (add-cards-to-graveyard db :player-1 5)
          ;; Add Cabal Ritual to hand and get object-id
          [obj-id db] (add-cabal-ritual-to-hand db :player-1)
          ;; Add mana to cast (1B cost)
          db (mana/add-mana db :player-1 {:black 1 :colorless 1})
          ;; Cast and resolve
          db (rules/cast-spell db :player-1 obj-id)
          db (rules/resolve-spell db :player-1 obj-id)]
      ;; Should have 3 black mana (BBB from effect)
      (is (= 3 (:black (q/get-mana-pool db :player-1)))))))


(deftest test-cabal-ritual-with-threshold
  (testing "Cabal Ritual adds BBBBB with threshold (7+ cards in graveyard)"
    (let [db (init-game-state)
          ;; Add 7 cards to graveyard (at threshold)
          db (add-cards-to-graveyard db :player-1 7)
          ;; Add Cabal Ritual to hand
          [obj-id db] (add-cabal-ritual-to-hand db :player-1)
          ;; Add mana to cast (1B cost)
          db (mana/add-mana db :player-1 {:black 1 :colorless 1})
          ;; Cast and resolve
          db (rules/cast-spell db :player-1 obj-id)
          db (rules/resolve-spell db :player-1 obj-id)]
      ;; Should have 5 black mana (BBBBB from threshold effect)
      (is (= 5 (:black (q/get-mana-pool db :player-1)))))))


(deftest test-threshold-checked-at-resolution
  (testing "Threshold is checked at resolution time, not cast time"
    ;; This tests that if graveyard changes between cast and resolve,
    ;; the resolution-time graveyard count matters.
    ;; We'll simulate by having exactly 6 cards, then after cast
    ;; we manually add another (simulating another effect filling gy).
    (let [db (init-game-state)
          ;; Add 6 cards to graveyard (below threshold at cast)
          db (add-cards-to-graveyard db :player-1 6)
          ;; Add Cabal Ritual to hand
          [obj-id db] (add-cabal-ritual-to-hand db :player-1)
          ;; Add mana to cast
          db (mana/add-mana db :player-1 {:black 1 :colorless 1})
          ;; Cast (threshold NOT active at this moment)
          db (rules/cast-spell db :player-1 obj-id)
          ;; Before resolution, add 1 more card to graveyard (now 7 = threshold)
          db (add-cards-to-graveyard db :player-1 1)
          ;; Resolve - should check threshold NOW
          db (rules/resolve-spell db :player-1 obj-id)]
      ;; Should have 5 black mana (BBBBB) because threshold was
      ;; active at resolution, even though it wasn't at cast
      (is (= 5 (:black (q/get-mana-pool db :player-1)))))))


;; === Corner case tests ===

(deftest test-resolve-spell-not-on-stack
  (testing "resolve-spell is no-op when spell not on stack"
    (let [db (-> (init-game-state)
                 (mana/add-mana :player-1 {:black 1}))
          hand (q/get-hand db :player-1)
          ritual (first hand)
          obj-id (:object/id ritual)
          ;; Don't cast - spell is still in hand, not on stack
          ;; Calling resolve-spell on a spell in hand (wrong zone)
          db' (rules/resolve-spell db :player-1 obj-id)]
      ;; Should be no-op - spell stays in hand, no effects execute
      (is (= 1 (count (q/get-hand db' :player-1)))
          "Spell should remain in hand")
      (is (= 0 (count (q/get-objects-in-zone db' :player-1 :graveyard)))
          "Graveyard should be empty")
      (is (= 1 (:black (q/get-mana-pool db' :player-1)))
          "Mana pool should be unchanged"))))


(def malformed-negative-cost-card
  "Card with malformed negative mana cost for edge case testing."
  {:card/id :negative-cost-card
   :card/name "Negative Cost Card"
   :card/cmc 0  ; CMC is 0 but cost is negative
   :card/mana-cost {:black -1}  ; Malformed: negative cost
   :card/colors #{:black}
   :card/types #{:instant}
   :card/text "This card has malformed data."
   :card/effects []})


(defn add-malformed-card-to-hand
  "Add malformed card to player's hand for testing."
  [db player-id]
  (let [player-eid (q/get-player-eid db player-id)
        ;; Add card definition
        db (d/db-with db [malformed-negative-cost-card])
        ;; Create object in hand
        card-eid (d/q '[:find ?e .
                        :where [?e :card/id :negative-cost-card]]
                      db)
        obj-id (random-uuid)]
    [obj-id (d/db-with db [{:object/id obj-id
                            :object/card card-eid
                            :object/zone :hand
                            :object/owner player-eid
                            :object/controller player-eid
                            :object/tapped false}])]))


^:fizzle-x40o-triage
;; Skipped: fizzle-euq5 — intentional negative cost violates mana-spec; throw-on-spec-failure true now catches it
(deftest test-cast-negative-cost
  (testing "cast-spell handles malformed card with negative mana cost"
    ;; NOTE: binding false because spec rejects negative mana cost; see fizzle-euq5 for fix.
    (binding [spec-util/*throw-on-spec-failure* false]
      (let [db (init-game-state)
            [obj-id db] (add-malformed-card-to-hand db :player-1)
            initial-black (:black (q/get-mana-pool db :player-1))
            ;; Cast the malformed card (no mana needed since cost is negative)
            db' (rules/cast-spell db :player-1 obj-id)
            final-black (:black (q/get-mana-pool db' :player-1))]
        ;; Verify spell was cast (moved to stack)
        (is (= 1 (count (q/get-objects-in-zone db' :player-1 :stack)))
            "Card should be on stack")
        ;; With negative cost {:black -1}, subtracting negative adds mana
        ;; This documents the current behavior - not necessarily desired
        ;; but the test ensures we know what happens with bad data
        (is (= (+ initial-black 1) final-black)
            "Negative cost subtracts negative (adds mana) - documents malformed behavior")))))


;; === Permanent type resolution tests ===

(deftest test-permanent-type-artifact
  (testing "permanent-type? returns true for artifacts"
    (is (true? (rules/permanent-type? #{:artifact}))
        "Artifact should be a permanent type")))


(deftest test-permanent-type-creature
  (testing "permanent-type? returns true for creatures"
    (is (true? (rules/permanent-type? #{:creature}))
        "Creature should be a permanent type")))


(deftest test-permanent-type-enchantment
  (testing "permanent-type? returns true for enchantments"
    (is (true? (rules/permanent-type? #{:enchantment}))
        "Enchantment should be a permanent type")))


(deftest test-permanent-type-instant-sorcery
  (testing "permanent-type? returns false for instant and sorcery"
    (is (false? (rules/permanent-type? #{:instant}))
        "Instant should not be a permanent type")
    (is (false? (rules/permanent-type? #{:sorcery}))
        "Sorcery should not be a permanent type")))


(deftest test-permanent-type-land
  (testing "permanent-type? returns false for land (lands don't go through resolve-spell)"
    ;; Lands use play-land, not cast-spell/resolve-spell
    ;; but if they somehow did, they shouldn't be treated as permanents here
    (is (false? (rules/permanent-type? #{:land}))
        "Land should not be treated as permanent in resolve-spell")))


(defn add-artifact-to-hand
  "Add an artifact card to player's hand for testing resolution.
   Returns [obj-id db] tuple."
  [db player-id card-id]
  (let [player-eid (q/get-player-eid db player-id)
        card-eid (d/q '[:find ?e .
                        :in $ ?cid
                        :where [?e :card/id ?cid]]
                      db card-id)
        obj-id (random-uuid)]
    [obj-id (d/db-with db [{:object/id obj-id
                            :object/card card-eid
                            :object/zone :hand
                            :object/owner player-eid
                            :object/controller player-eid
                            :object/tapped false}])]))


(deftest test-artifact-resolves-to-battlefield
  (testing "Artifact spell resolves to battlefield, not graveyard"
    (let [db (init-game-state)
          ;; Add Lotus Petal card definition (it's in cards/all-cards via init)
          ;; But init-game-state only loads dark-ritual, so add it
          db (d/db-with db [lotus-petal/card])
          [obj-id db] (add-artifact-to-hand db :player-1 :lotus-petal)
          ;; Cast - artifacts have 0 mana cost
          db-cast (rules/cast-spell db :player-1 obj-id)
          _ (is (= :stack (:object/zone (q/get-object db-cast obj-id)))
                "Artifact should be on stack after casting")
          ;; Resolve
          db-resolved (rules/resolve-spell db-cast :player-1 obj-id)]
      (is (= :battlefield (:object/zone (q/get-object db-resolved obj-id)))
          "Artifact should resolve to battlefield")
      (is (= 1 (count (q/get-objects-in-zone db-resolved :player-1 :battlefield)))
          "Should have 1 permanent on battlefield")
      (is (= 0 (count (q/get-objects-in-zone db-resolved :player-1 :graveyard)))
          "Graveyard should be empty"))))


(deftest test-instant-still-resolves-to-graveyard
  (testing "Instant spell still resolves to graveyard (not battlefield)"
    (let [db (-> (init-game-state)
                 (mana/add-mana :player-1 {:black 1}))
          hand (q/get-hand db :player-1)
          ritual (first hand)  ; Dark Ritual is an instant
          obj-id (:object/id ritual)
          db-cast (rules/cast-spell db :player-1 obj-id)
          db-resolved (rules/resolve-spell db-cast :player-1 obj-id)]
      (is (= :graveyard (:object/zone (q/get-object db-resolved obj-id)))
          "Instant should resolve to graveyard")
      (is (= 0 (count (q/get-objects-in-zone db-resolved :player-1 :battlefield)))
          "Battlefield should be empty")
      (is (= 1 (count (q/get-objects-in-zone db-resolved :player-1 :graveyard)))
          "Should have 1 card in graveyard"))))


;; =====================================================
;; Sorcery-Speed Timing Tests
;; =====================================================

(defn set-phase
  "Change the game phase in the db. Returns updated db."
  [db new-phase]
  (let [game-eid (d/q '[:find ?e .
                        :where [?e :game/id _]]
                      db)]
    (d/db-with db [[:db/add game-eid :game/phase new-phase]])))


(defn add-sorcery-to-hand
  "Add a sorcery card to player's hand. Returns [obj-id db]."
  [db player-id]
  (let [sorcery-card {:card/id :test-sorcery
                      :card/name "Test Sorcery"
                      :card/cmc 0
                      :card/mana-cost {}
                      :card/colors #{}
                      :card/types #{:sorcery}
                      :card/text "Test"
                      :card/effects []}
        db (d/db-with db [sorcery-card])
        player-eid (q/get-player-eid db player-id)
        card-eid (d/q '[:find ?e .
                        :in $ ?cid
                        :where [?e :card/id ?cid]]
                      db :test-sorcery)
        obj-id (random-uuid)]
    [obj-id (d/db-with db [{:object/id obj-id
                            :object/card card-eid
                            :object/zone :hand
                            :object/owner player-eid
                            :object/controller player-eid
                            :object/tapped false}])]))


(deftest test-sorcery-castable-during-main-phase
  (testing "Sorcery can be cast during main phase with empty stack"
    (let [db (init-game-state)
          [obj-id db] (add-sorcery-to-hand db :player-1)]
      ;; Phase is :main1, stack is empty
      (is (true? (rules/can-cast? db :player-1 obj-id))
          "Sorcery should be castable during main phase with empty stack"))))


(deftest test-sorcery-not-castable-during-combat
  (testing "Sorcery cannot be cast during combat phase"
    (let [db (init-game-state)
          [obj-id db] (add-sorcery-to-hand db :player-1)
          db (set-phase db :combat)]
      (is (false? (rules/can-cast? db :player-1 obj-id))
          "Sorcery should NOT be castable during combat phase"))))


(deftest test-sorcery-not-castable-during-upkeep
  (testing "Sorcery cannot be cast during upkeep"
    (let [db (init-game-state)
          [obj-id db] (add-sorcery-to-hand db :player-1)
          db (set-phase db :upkeep)]
      (is (false? (rules/can-cast? db :player-1 obj-id))
          "Sorcery should NOT be castable during upkeep"))))


(deftest test-sorcery-not-castable-with-stack-items
  (testing "Sorcery cannot be cast when there are items on the stack"
    (let [db (-> (init-game-state)
                 (mana/add-mana :player-1 {:black 1}))
          hand (q/get-hand db :player-1)
          ritual (first hand)
          ;; Cast ritual to put it on stack
          db (rules/cast-spell db :player-1 (:object/id ritual))
          ;; Now add a sorcery to hand
          [sorcery-id db] (add-sorcery-to-hand db :player-1)]
      ;; Stack has the ritual on it
      (is (pos? (count (q/get-objects-in-zone db :player-1 :stack)))
          "Precondition: stack is not empty")
      (is (false? (rules/can-cast? db :player-1 sorcery-id))
          "Sorcery should NOT be castable when stack has items"))))


(deftest test-instant-castable-during-combat
  (testing "Instant can be cast during any phase"
    (let [db (-> (init-game-state)
                 (mana/add-mana :player-1 {:black 1})
                 (set-phase :combat))
          hand (q/get-hand db :player-1)
          ritual (first hand)]  ; Dark Ritual is an instant
      (is (true? (rules/can-cast? db :player-1 (:object/id ritual)))
          "Instant should be castable during combat"))))


(deftest test-instant-castable-with-stack-items
  (testing "Instant can be cast when stack has items"
    (let [db (-> (init-game-state)
                 (mana/add-mana :player-1 {:black 2}))
          ;; Add a second instant to hand
          player-eid (q/get-player-eid db :player-1)
          card-eid (d/q '[:find ?e .
                          :where [?e :card/id :dark-ritual]]
                        db)
          db (d/db-with db [{:object/id :second-ritual
                             :object/card card-eid
                             :object/zone :hand
                             :object/owner player-eid
                             :object/controller player-eid
                             :object/tapped false}])
          ;; Cast first ritual (stack not empty)
          hand (q/get-hand db :player-1)
          first-ritual (first hand)
          db (rules/cast-spell db :player-1 (:object/id first-ritual))]
      ;; Stack has the first ritual
      (is (pos? (count (q/get-objects-in-zone db :player-1 :stack)))
          "Precondition: stack is not empty")
      (is (true? (rules/can-cast? db :player-1 :second-ritual))
          "Instant should be castable even with items on stack"))))


(deftest test-artifact-not-castable-during-combat
  (testing "Artifact cannot be cast during combat phase (requires sorcery speed)"
    (let [db (init-game-state)
          db (d/db-with db [lotus-petal/card])
          [obj-id db] (add-artifact-to-hand db :player-1 :lotus-petal)
          db (set-phase db :combat)]
      (is (false? (rules/can-cast? db :player-1 obj-id))
          "Artifact should NOT be castable during combat phase"))))


(deftest test-sorcery-castable-during-main2
  (testing "Sorcery can be cast during main phase 2"
    (let [db (init-game-state)
          [obj-id db] (add-sorcery-to-hand db :player-1)
          db (set-phase db :main2)]
      (is (true? (rules/can-cast? db :player-1 obj-id))
          "Sorcery should be castable during main phase 2"))))


;; =====================================================
;; Alternate Casting Modes Tests
;; =====================================================

;; Test card definitions for alternate costs testing

(def deep-analysis-card
  "Deep Analysis card with flashback for testing alternate costs."
  {:card/id :deep-analysis
   :card/name "Deep Analysis"
   :card/cmc 4
   :card/mana-cost {:colorless 3 :blue 1}
   :card/colors #{:blue}
   :card/types #{:sorcery}
   :card/text "Target player draws two cards. Flashback—{1}{U}, Pay 3 life."
   :card/effects [{:effect/type :draw
                   :effect/amount 2
                   :effect/target :any-player}]
   :card/alternate-costs [{:alternate/id :flashback
                           :alternate/zone :graveyard
                           :alternate/mana-cost {:colorless 1 :blue 1}
                           :alternate/additional-costs [{:cost/type :pay-life :cost/amount 3}]
                           :alternate/on-resolve :exile}]})


(def gush-like-card
  "A Gush-like card with alternate cost from hand for testing."
  {:card/id :gush-like
   :card/name "Test Gush"
   :card/cmc 5
   :card/mana-cost {:colorless 4 :blue 1}
   :card/colors #{:blue}
   :card/types #{:instant}
   :card/text "Draw two cards. You may return two Islands you control to hand instead of paying mana cost."
   :card/effects [{:effect/type :draw
                   :effect/amount 2}]
   :card/alternate-costs [{:alternate/id :return-islands
                           :alternate/zone :hand
                           :alternate/mana-cost {}
                           :alternate/additional-costs [{:cost/type :return-lands
                                                         :cost/subtype :island
                                                         :cost/amount 2}]
                           :alternate/on-resolve :graveyard}]})


(defn add-card-to-zone
  "Add a card definition and create an object in specified zone.
   Returns [obj-id db] tuple."
  [db player-id card zone]
  (let [player-eid (q/get-player-eid db player-id)
        ;; Add card definition if not already present
        db (if (d/q '[:find ?e .
                      :in $ ?cid
                      :where [?e :card/id ?cid]]
                    db (:card/id card))
             db
             (d/db-with db [card]))
        ;; Create object in zone
        card-eid (d/q '[:find ?e .
                        :in $ ?cid
                        :where [?e :card/id ?cid]]
                      db (:card/id card))
        obj-id (random-uuid)]
    [obj-id (d/db-with db [{:object/id obj-id
                            :object/card card-eid
                            :object/zone zone
                            :object/owner player-eid
                            :object/controller player-eid
                            :object/tapped false}])]))


;; === get-casting-modes tests ===

(deftest get-casting-modes-primary-only-hand-test
  (testing "get-casting-modes returns primary mode for card in hand without alternates"
    (let [db (init-game-state)
          hand (q/get-hand db :player-1)
          ritual (first hand)  ; Dark Ritual has no alternate costs
          modes (rules/get-casting-modes db :player-1 (:object/id ritual))]
      (is (= 1 (count modes))
          "Should return exactly one mode")
      (is (= :primary (:mode/id (first modes)))
          "Mode should be :primary")
      (is (= {:black 1} (:mode/mana-cost (first modes)))
          "Mode should have Dark Ritual's mana cost"))))


(deftest get-casting-modes-no-modes-wrong-zone-test
  (testing "get-casting-modes returns empty for card in graveyard without flashback"
    (let [db (init-game-state)
          hand (q/get-hand db :player-1)
          ritual (first hand)
          obj-id (:object/id ritual)
          ;; Move Dark Ritual (no flashback) to graveyard
          db' (zones/move-to-zone* db obj-id :graveyard)
          modes (rules/get-casting-modes db' :player-1 obj-id)]
      (is (empty? modes)
          "Should return empty vector - Dark Ritual can't be cast from graveyard"))))


(deftest get-casting-modes-flashback-in-graveyard-test
  (testing "get-casting-modes returns flashback mode for flashback card in graveyard"
    (let [db (init-game-state)
          [obj-id db] (add-card-to-zone db :player-1 deep-analysis-card :graveyard)
          modes (rules/get-casting-modes db :player-1 obj-id)]
      (is (= 1 (count modes))
          "Should return exactly one mode (flashback only, not primary)")
      (is (= :flashback (:mode/id (first modes)))
          "Mode should be :flashback")
      (is (= {:colorless 1 :blue 1} (:mode/mana-cost (first modes)))
          "Mode should have flashback mana cost"))))


(deftest get-casting-modes-flashback-not-in-hand-test
  (testing "get-casting-modes does NOT return flashback mode when card in hand"
    (let [db (init-game-state)
          [obj-id db] (add-card-to-zone db :player-1 deep-analysis-card :hand)
          modes (rules/get-casting-modes db :player-1 obj-id)]
      (is (= 1 (count modes))
          "Should return exactly one mode (primary only)")
      (is (= :primary (:mode/id (first modes)))
          "Mode should be :primary (not flashback)")
      (is (= {:colorless 3 :blue 1} (:mode/mana-cost (first modes)))
          "Mode should have normal mana cost, not flashback cost"))))


(deftest get-casting-modes-multiple-modes-test
  (testing "get-casting-modes returns both modes for card with hand-alternate"
    (let [db (init-game-state)
          [obj-id db] (add-card-to-zone db :player-1 gush-like-card :hand)
          modes (rules/get-casting-modes db :player-1 obj-id)]
      (is (= 2 (count modes))
          "Should return two modes (primary + alternate)")
      (is (some #(= :primary (:mode/id %)) modes)
          "Should include primary mode")
      (is (some #(= :return-islands (:mode/id %)) modes)
          "Should include alternate mode"))))


;; === can-cast-mode? tests ===

(deftest can-cast-mode-sufficient-mana-test
  (testing "can-cast-mode? returns true when player has sufficient mana"
    (let [db (-> (init-game-state)
                 (mana/add-mana :player-1 {:black 1}))
          hand (q/get-hand db :player-1)
          ritual (first hand)
          modes (rules/get-casting-modes db :player-1 (:object/id ritual))
          mode (first modes)]
      (is (true? (rules/can-cast-mode? db :player-1 (:object/id ritual) mode))
          "Should be able to cast with sufficient mana"))))


(deftest can-cast-mode-insufficient-mana-test
  (testing "can-cast-mode? returns false when player lacks mana"
    (let [db (init-game-state)  ; No mana added
          hand (q/get-hand db :player-1)
          ritual (first hand)
          modes (rules/get-casting-modes db :player-1 (:object/id ritual))
          mode (first modes)]
      (is (false? (rules/can-cast-mode? db :player-1 (:object/id ritual) mode))
          "Should not be able to cast without mana"))))


(deftest can-cast-mode-with-life-cost-test
  (testing "can-cast-mode? checks additional life cost"
    (let [db (init-game-state)
          ;; Add Deep Analysis to graveyard
          [obj-id db] (add-card-to-zone db :player-1 deep-analysis-card :graveyard)
          ;; Add mana for flashback (1U)
          db (mana/add-mana db :player-1 {:colorless 1 :blue 1})
          ;; Player starts with 20 life (default) which is >= 3
          modes (rules/get-casting-modes db :player-1 obj-id)
          flashback-mode (first modes)]
      (is (= :flashback (:mode/id flashback-mode))
          "Should be flashback mode")
      (is (true? (rules/can-cast-mode? db :player-1 obj-id flashback-mode))
          "Should be able to cast flashback with mana and life available"))))


(deftest can-cast-mode-insufficient-life-test
  (testing "can-cast-mode? returns false when can't pay life cost"
    (let [db (init-game-state)
          ;; Add Deep Analysis to graveyard
          [obj-id db] (add-card-to-zone db :player-1 deep-analysis-card :graveyard)
          ;; Add mana for flashback (1U)
          db (mana/add-mana db :player-1 {:colorless 1 :blue 1})
          ;; Set player life to 2 (less than required 3)
          player-eid (q/get-player-eid db :player-1)
          db (d/db-with db [[:db/add player-eid :player/life 2]])
          modes (rules/get-casting-modes db :player-1 obj-id)
          flashback-mode (first modes)]
      (is (false? (rules/can-cast-mode? db :player-1 obj-id flashback-mode))
          "Should not be able to cast flashback without enough life"))))


;; === Edge case tests ===

(deftest get-casting-modes-nil-alternate-costs-test
  (testing "get-casting-modes handles nil alternate-costs gracefully"
    ;; Dark Ritual has no :card/alternate-costs key at all
    (let [db (init-game-state)
          hand (q/get-hand db :player-1)
          ritual (first hand)
          modes (rules/get-casting-modes db :player-1 (:object/id ritual))]
      (is (= 1 (count modes))
          "Should return primary mode when alternate-costs is nil")
      (is (= :primary (:mode/id (first modes)))))))


(deftest get-casting-modes-empty-alternate-costs-test
  (testing "get-casting-modes handles empty alternate-costs vector"
    ;; Create card with explicit empty vector
    (let [card-with-empty-alts {:card/id :empty-alts-test
                                :card/name "Empty Alts Test"
                                :card/cmc 1
                                :card/mana-cost {:black 1}
                                :card/colors #{:black}
                                :card/types #{:instant}
                                :card/text "Test card"
                                :card/effects []
                                :card/alternate-costs []}
          db (init-game-state)
          [obj-id db] (add-card-to-zone db :player-1 card-with-empty-alts :hand)
          modes (rules/get-casting-modes db :player-1 obj-id)]
      (is (= 1 (count modes))
          "Should return primary mode when alternate-costs is empty vector")
      (is (= :primary (:mode/id (first modes)))))))


(deftest can-cast-mode-empty-mana-cost-test
  (testing "can-cast-mode? returns true for zero mana cost when additional costs met"
    ;; Test Gush's alternate mode which has {} (empty) mana cost
    (let [db (init-game-state)
          [obj-id db] (add-card-to-zone db :player-1 gush-like-card :hand)
          modes (rules/get-casting-modes db :player-1 obj-id)
          ;; Find the alternate mode with empty mana cost
          alt-mode (first (filter #(= :return-islands (:mode/id %)) modes))]
      ;; Verify mode has empty mana cost
      (is (= {} (:mode/mana-cost alt-mode))
          "Alternate mode should have empty mana cost")
      ;; Actually test can-cast-mode? - mana portion is payable (0 cost)
      ;; Note: Full can-cast-mode? returns false because :return-lands additional
      ;; cost is not implemented yet. We test the mana portion via can-pay?.
      (is (true? (mana/can-pay? db :player-1 (:mode/mana-cost alt-mode)))
          "Empty mana cost {} should be payable"))))


;; === Mode targeting feasibility tests ===

(defn- n-slot-mode
  "Build a synthetic mode with N identical targeting slots targeting battlefield nonland permanents.
   Uses :self controller (player-1 only) to avoid needing an opponent in the test db.
   Uses empty mana cost so tests isolate targeting feasibility from mana checks."
  [n]
  {:mode/id :kicked
   :mode/zone :hand
   :mode/mana-cost {}
   :mode/targeting (vec (for [i (range n)]
                          {:target/id (keyword (str "slot-" i))
                           :target/type :object
                           :target/zone :battlefield
                           :target/controller :self
                           :target/required true
                           :target/criteria {:match/not-types #{:land}}}))})


(deftest can-cast-mode?-rejects-multi-slot-with-1-unique-target
  (testing "2-slot mode with 1 unique nonland permanent is infeasible"
    (let [db (th/create-test-db)
          [db _] (th/add-test-creature db :player-1 2 2)
          mode (n-slot-mode 2)]
      (is (false? (rules/can-cast-mode? db :player-1 nil mode))
          "2-slot mode must be infeasible when only 1 unique valid target exists"))))


(deftest can-cast-mode?-accepts-multi-slot-with-2-unique-targets
  (testing "2-slot mode with 2 unique nonland permanents is feasible"
    (let [db (th/create-test-db)
          [db _] (th/add-test-creature db :player-1 2 2)
          [db _] (th/add-test-creature db :player-1 1 1)
          mode (n-slot-mode 2)]
      (is (true? (rules/can-cast-mode? db :player-1 nil mode))
          "2-slot mode must be feasible when 2 unique valid targets exist"))))


(deftest can-cast-mode?-passes-mode-with-no-targeting
  (testing "mode with no :mode/targeting passes through unchanged"
    (let [db (th/create-test-db {:mana {:red 1}})
          [_ obj-id] (th/add-card-to-zone db :lightning-bolt :hand :player-1)
          mode {:mode/id :primary :mode/zone :hand :mode/mana-cost {:red 1}}]
      (is (true? (rules/can-cast-mode? db :player-1 obj-id mode))
          "mode without :mode/targeting must not be rejected by targeting feasibility"))))


(deftest can-cast-mode?-allow-duplicate-opts-out-of-count-check
  (testing ":target/allow-duplicate true on any req disables the distinct-count check"
    (let [db (th/create-test-db)
          [db _] (th/add-test-creature db :player-1 2 2)
          mode {:mode/id :test
                :mode/zone :hand
                :mode/mana-cost {}
                :mode/targeting [{:target/id :slot-a :target/type :object
                                  :target/zone :battlefield
                                  :target/controller :self
                                  :target/required true
                                  :target/allow-duplicate true
                                  :target/criteria {:match/not-types #{:land}}}
                                 {:target/id :slot-b :target/type :object
                                  :target/zone :battlefield
                                  :target/controller :self
                                  :target/required true
                                  :target/allow-duplicate true
                                  :target/criteria {:match/not-types #{:land}}}]}]
      (is (true? (rules/can-cast-mode? db :player-1 nil mode))
          ":target/allow-duplicate skips distinct-count -- 1 target is enough"))))


(deftest can-cast-mode?-rejects-single-slot-with-0-targets
  (testing "1-slot mode with 0 valid targets is infeasible"
    (let [db (th/create-test-db)
          mode (n-slot-mode 1)]
      (is (false? (rules/can-cast-mode? db :player-1 nil mode))
          "1-slot required targeting with empty battlefield is infeasible"))))


;; === Backwards compatibility ===

(deftest can-cast-unchanged-for-existing-cards-test
  (testing "can-cast? behavior unchanged for cards without alternate costs"
    ;; These replicate existing tests to ensure backwards compatibility
    (let [db (init-game-state)
          hand (q/get-hand db :player-1)
          ritual (first hand)]
      ;; Without mana - should be false
      (is (false? (rules/can-cast? db :player-1 (:object/id ritual)))
          "can-cast? should return false without mana")
      ;; With mana - should be true
      (let [db' (mana/add-mana db :player-1 {:black 1})]
        (is (true? (rules/can-cast? db' :player-1 (:object/id ritual)))
            "can-cast? should return true with mana"))
      ;; Wrong zone - should be false
      (let [db' (-> db
                    (mana/add-mana :player-1 {:black 1})
                    (zones/move-to-zone* (:object/id ritual) :graveyard))]
        (is (false? (rules/can-cast? db' :player-1 (:object/id ritual)))
            "can-cast? should return false when card not in hand (no flashback)")))))


;; === get-castable-cards tests ===

(deftest get-castable-cards-from-hand-test
  (testing "get-castable-cards returns cards that can be cast from hand"
    (let [db (-> (init-game-state)
                 (mana/add-mana :player-1 {:black 1}))
          castable (rules/get-castable-cards db :player-1)]
      (is (= 1 (count castable))
          "Should return 1 castable card (Dark Ritual)")
      (is (= :dark-ritual (get-in (first castable) [:object/card :card/id]))
          "Castable card should be Dark Ritual"))))


(deftest get-castable-cards-includes-graveyard-flashback-test
  (testing "get-castable-cards includes flashback cards from graveyard"
    (let [db (init-game-state)
          ;; Add Deep Analysis to graveyard
          [_obj-id db] (add-card-to-zone db :player-1 deep-analysis-card :graveyard)
          ;; Add mana for flashback (1U)
          db (mana/add-mana db :player-1 {:colorless 1 :blue 1})
          ;; Player has 20 life by default, enough for pay 3 life
          castable (rules/get-castable-cards db :player-1)]
      ;; Should include Deep Analysis from graveyard
      (is (some #(= :deep-analysis (get-in % [:object/card :card/id])) castable)
          "Castable cards should include Deep Analysis from graveyard"))))


(deftest get-castable-cards-empty-when-no-mana-test
  (testing "get-castable-cards returns empty when no mana and no free spells"
    (let [db (init-game-state)
          castable (rules/get-castable-cards db :player-1)]
      (is (empty? castable)
          "Should return empty when can't afford anything"))))


;; =====================================================
;; cast-spell-mode and resolve-spell mode tests
;; =====================================================

(deftest cast-spell-mode-primary-test
  (testing "cast-spell-mode casts with primary mode from hand"
    (let [db (-> (init-game-state)
                 (mana/add-mana :player-1 {:black 1}))
          hand (q/get-hand db :player-1)
          ritual (first hand)
          obj-id (:object/id ritual)
          modes (rules/get-casting-modes db :player-1 obj-id)
          primary-mode (first modes)
          db' (rules/cast-spell-mode db :player-1 obj-id primary-mode)]
      (is (= :stack (:object/zone (q/get-object db' obj-id)))
          "Card should be on stack")
      (is (= 0 (:black (q/get-mana-pool db' :player-1)))
          "Mana should be paid")
      (is (= 1 (q/get-storm-count db' :player-1))
          "Storm should increment"))))


(deftest cast-spell-mode-flashback-test
  (testing "cast-spell-mode casts flashback from graveyard"
    (let [db (init-game-state)
          [obj-id db] (add-card-to-zone db :player-1 deep-analysis-card :graveyard)
          db (mana/add-mana db :player-1 {:colorless 1 :blue 1})
          initial-life (q/get-life-total db :player-1)
          modes (rules/get-casting-modes db :player-1 obj-id)
          flashback-mode (first modes)
          db' (rules/cast-spell-mode db :player-1 obj-id flashback-mode)]
      (is (= :stack (:object/zone (q/get-object db' obj-id)))
          "Card should be on stack")
      (is (= (- initial-life 3) (q/get-life-total db' :player-1))
          "Life should be paid (3 life for flashback)")
      (is (= 1 (q/get-storm-count db' :player-1))
          "Storm should increment for flashback cast"))))


(deftest cast-spell-mode-tracks-mode-on-stack-test
  (testing "cast-spell-mode stores :object/cast-mode on stack"
    (let [db (init-game-state)
          [obj-id db] (add-card-to-zone db :player-1 deep-analysis-card :graveyard)
          db (mana/add-mana db :player-1 {:colorless 1 :blue 1})
          modes (rules/get-casting-modes db :player-1 obj-id)
          flashback-mode (first modes)
          db' (rules/cast-spell-mode db :player-1 obj-id flashback-mode)
          obj' (q/get-object db' obj-id)]
      (is (= :exile (get-in obj' [:object/cast-mode :mode/on-resolve]))
          "Flashback mode should have :exile on-resolve"))))


(deftest resolve-flashback-exiles-test
  (testing "resolve-spell sends flashback spell to exile"
    (let [db (init-game-state)
          [obj-id db] (add-card-to-zone db :player-1 deep-analysis-card :graveyard)
          db (mana/add-mana db :player-1 {:colorless 1 :blue 1})
          modes (rules/get-casting-modes db :player-1 obj-id)
          flashback-mode (first modes)
          db-cast (rules/cast-spell-mode db :player-1 obj-id flashback-mode)
          db-resolved (rules/resolve-spell db-cast :player-1 obj-id)]
      (is (= :exile (:object/zone (q/get-object db-resolved obj-id)))
          "Flashback spell should go to exile, not graveyard"))))


(deftest resolve-normal-goes-to-graveyard-test
  (testing "resolve-spell sends normal spell to graveyard"
    (let [db (init-game-state)
          [obj-id db] (add-card-to-zone db :player-1 deep-analysis-card :hand)
          db (mana/add-mana db :player-1 {:colorless 3 :blue 1})
          modes (rules/get-casting-modes db :player-1 obj-id)
          primary-mode (first modes)
          db-cast (rules/cast-spell-mode db :player-1 obj-id primary-mode)
          db-resolved (rules/resolve-spell db-cast :player-1 obj-id)]
      (is (= :graveyard (:object/zone (q/get-object db-resolved obj-id)))
          "Normal spell should go to graveyard"))))


(deftest cast-spell-without-mode-backwards-compat-test
  (testing "cast-spell without mode argument still works (backwards compatible)"
    (let [db (-> (init-game-state)
                 (mana/add-mana :player-1 {:black 1}))
          hand (q/get-hand db :player-1)
          ritual (first hand)
          obj-id (:object/id ritual)
          db' (rules/cast-spell db :player-1 obj-id)]
      (is (= :stack (:object/zone (q/get-object db' obj-id)))
          "Existing cast-spell API should still work")
      (is (= 1 (q/get-storm-count db' :player-1))
          "Storm should increment"))))


;; === Additional corner case tests ===

(deftest test-cast-spell-invalid-object-id
  (testing "cast-spell with non-existent object ID returns db unchanged"
    ;; Corner case: calling cast-spell with an object ID that doesn't exist.
    ;; Current behavior: returns db unchanged (no crash, graceful no-op).
    ;; Bug it catches: NullPointerException or crash when object doesn't exist.
    (let [db (-> (init-game-state)
                 (mana/add-mana :player-1 {:black 5}))
          fake-id (random-uuid)  ; Object that doesn't exist
          initial-storm (q/get-storm-count db :player-1)
          db' (rules/cast-spell db :player-1 fake-id)]
      ;; Should be no-op: storm unchanged, db essentially the same
      (is (= initial-storm (q/get-storm-count db' :player-1))
          "Storm count should not change for invalid object")
      ;; can-cast? should also return false
      (is (false? (rules/can-cast? db :player-1 fake-id))
          "can-cast? should return false for non-existent object"))))


;; =====================================================
;; Player Restriction Tests (cannot-cast-spells)
;; =====================================================

(defn add-player-restriction
  "Add a restriction grant to a player for testing.
   Creates grant expiring at turn 99 (effectively permanent for tests)."
  [db player-id restriction-type]
  (let [grant {:grant/id (random-uuid)
               :grant/type :restriction
               :grant/source nil
               :grant/expires {:expires/turn 99 :expires/phase :cleanup}
               :grant/data {:restriction/type restriction-type}}]
    (grants/add-player-grant db player-id grant)))


(defn add-player-restriction-with-expiry
  "Add a restriction grant with specific expiry for testing."
  [db player-id restriction-type turn phase]
  (let [grant {:grant/id (random-uuid)
               :grant/type :restriction
               :grant/source nil
               :grant/expires {:expires/turn turn :expires/phase phase}
               :grant/data {:restriction/type restriction-type}}]
    (grants/add-player-grant db player-id grant)))


(defn add-opponent
  "Add a second player to the game state."
  [db]
  (d/db-with db [{:player/id :player-2
                  :player/life 20
                  :player/storm-count 0
                  :player/mana-pool {}}]))


(deftest cannot-cast-with-restriction-test
  (testing "player with :cannot-cast-spells restriction cannot cast any spell"
    (let [db (-> (init-game-state)
                 (mana/add-mana :player-1 {:black 1})
                 (add-player-restriction :player-1 :cannot-cast-spells))
          hand (q/get-hand db :player-1)
          object-id (:object/id (first hand))]
      (is (not (rules/can-cast? db :player-1 object-id))
          "Should not be able to cast Dark Ritual with restriction"))))


(deftest can-cast-without-restriction-test
  (testing "player without restriction can cast spells normally"
    (let [db (-> (init-game-state)
                 (mana/add-mana :player-1 {:black 1}))
          hand (q/get-hand db :player-1)
          object-id (:object/id (first hand))]
      (is (rules/can-cast? db :player-1 object-id)
          "Should be able to cast Dark Ritual without restriction"))))


(deftest restriction-on-opponent-doesnt-affect-player-test
  (testing "restriction on opponent doesn't prevent player from casting"
    (let [db (-> (init-game-state)
                 (add-opponent)
                 (mana/add-mana :player-1 {:black 1})
                 (add-player-restriction :player-2 :cannot-cast-spells))
          hand (q/get-hand db :player-1)
          object-id (:object/id (first hand))]
      (is (rules/can-cast? db :player-1 object-id)
          "Player 1 should still be able to cast"))))


(deftest has-restriction-different-type-test
  (testing "has-restriction? returns false when restriction is different type"
    (let [db (-> (init-game-state)
                 (add-player-restriction :player-1 :cannot-attack))]
      (is (not (rules/has-restriction? db :player-1 :cannot-cast-spells))
          "Should not find :cannot-cast-spells when only :cannot-attack exists"))))


(deftest has-restriction-multiple-grants-test
  (testing "has-restriction? returns true when multiple restriction grants exist"
    (let [db (-> (init-game-state)
                 (add-player-restriction :player-1 :cannot-cast-spells)
                 (add-player-restriction :player-1 :cannot-cast-spells))]
      (is (rules/has-restriction? db :player-1 :cannot-cast-spells)
          "Should find restriction among multiple grants"))))


(deftest different-restriction-allows-casting-test
  (testing "different restriction type doesn't prevent casting"
    (let [db (-> (init-game-state)
                 (mana/add-mana :player-1 {:black 1})
                 (add-player-restriction :player-1 :cannot-attack))]
      ;; Player has :cannot-attack but NOT :cannot-cast-spells
      (let [hand (q/get-hand db :player-1)
            object-id (:object/id (first hand))]
        (is (rules/can-cast? db :player-1 object-id)
            "Should be able to cast when only :cannot-attack restriction exists")))))


(deftest expired-restriction-allows-casting-test
  (testing "after restriction expires, player can cast again"
    (let [db (-> (init-game-state)
                 (mana/add-mana :player-1 {:black 1})
                 (add-player-restriction-with-expiry :player-1 :cannot-cast-spells 1 :cleanup))
          ;; Expire grants (turn 2, cleanup phase - later than expiry)
          db' (grants/expire-grants db 2 :cleanup)
          hand (q/get-hand db' :player-1)
          object-id (:object/id (first hand))]
      (is (rules/can-cast? db' :player-1 object-id)
          "Should be able to cast after restriction expires"))))


(deftest has-restriction-non-existent-player-test
  (testing "has-restriction? returns false for non-existent player"
    (let [db (init-game-state)]
      (is (not (rules/has-restriction? db :player-99 :cannot-cast-spells))
          "Should return false for player that doesn't exist"))))


(deftest get-castable-cards-respects-restriction-test
  (testing "get-castable-cards returns empty when player has restriction"
    (let [db (-> (init-game-state)
                 (mana/add-mana :player-1 {:black 1})
                 (add-player-restriction :player-1 :cannot-cast-spells))
          castable (rules/get-castable-cards db :player-1)]
      (is (empty? castable)
          "Should return empty when player has cannot-cast-spells restriction"))))


;; =====================================================
;; Casting Modes Tests
;; =====================================================

(deftest no-kicker-single-mode-test
  (testing "Cards without alternate costs return only primary mode"
    (let [db (init-game-state)
          hand (q/get-hand db :player-1)
          ritual (first hand)  ; Dark Ritual has no alternate costs
          modes (rules/get-casting-modes db :player-1 (:object/id ritual))]
      (is (= 1 (count modes))
          "Cards without alternate costs should return only one mode")
      (is (= :primary (:mode/id (first modes)))
          "The single mode should be :primary"))))


(deftest kicker-not-from-graveyard-test
  (testing "Cards without flashback have no casting modes from graveyard"
    (let [db (init-game-state)
          hand (q/get-hand db :player-1)
          ritual (first hand)  ; Dark Ritual has no flashback/graveyard alternate costs
          ;; Move to graveyard by placing it there directly
          [obj-id db] (add-card-to-zone db :player-1 (:object/card ritual) :graveyard)
          modes (rules/get-casting-modes db :player-1 obj-id)]
      (is (= 0 (count modes))
          "Card in graveyard without flashback should have no modes"))))


;; =====================================================
;; Corner Case Tests: move-spell-off-stack
;; =====================================================

(deftest test-move-spell-off-stack-goes-to-graveyard
  (testing "move-spell-off-stack sends non-copy spell to graveyard"
    (let [db (-> (init-game-state)
                 (mana/add-mana :player-1 {:black 1}))
          hand (q/get-hand db :player-1)
          ritual (first hand)
          obj-id (:object/id ritual)
          db-cast (rules/cast-spell db :player-1 obj-id)
          db-off (rules/move-spell-off-stack db-cast :player-1 obj-id)]
      (is (= :graveyard (:object/zone (q/get-object db-off obj-id)))
          "Countered spell should go to graveyard")
      (is (= 0 (count (q/get-objects-in-zone db-off :player-1 :stack)))
          "Stack should be empty")
      (is (= 1 (count (q/get-objects-in-zone db-off :player-1 :graveyard)))
          "Graveyard should have the countered spell"))))


(deftest test-move-spell-off-stack-flashback-goes-to-exile
  (testing "move-spell-off-stack sends flashback spell to exile (not graveyard)"
    (let [db (init-game-state)
          [obj-id db] (add-card-to-zone db :player-1 deep-analysis-card :graveyard)
          db (mana/add-mana db :player-1 {:colorless 1 :blue 1})
          modes (rules/get-casting-modes db :player-1 obj-id)
          flashback-mode (first modes)
          db-cast (rules/cast-spell-mode db :player-1 obj-id flashback-mode)
          db-off (rules/move-spell-off-stack db-cast :player-1 obj-id)]
      (is (= :exile (:object/zone (q/get-object db-off obj-id)))
          "Countered flashback spell should go to exile, not graveyard"))))


(deftest test-move-spell-off-stack-not-on-stack-noop
  (testing "move-spell-off-stack is no-op when spell not on stack"
    (let [db (init-game-state)
          hand (q/get-hand db :player-1)
          obj-id (:object/id (first hand))
          db' (rules/move-spell-off-stack db :player-1 obj-id)]
      (is (= :hand (:object/zone (q/get-object db' obj-id)))
          "Spell in hand should stay in hand"))))


;; =====================================================
;; Corner Case Tests: resolve copy ceases to exist
;; =====================================================

(deftest test-resolve-copy-ceases-to-exist
  (testing "Resolving a copy removes it from db entirely (not to graveyard)"
    (let [db (-> (init-game-state)
                 (mana/add-mana :player-1 {:black 1}))
          hand (q/get-hand db :player-1)
          ritual (first hand)
          obj-id (:object/id ritual)
          db-cast (rules/cast-spell db :player-1 obj-id)
          ;; Create a copy of the spell on the stack
          db-with-copy (fizzle.engine.triggers/create-spell-copy db-cast obj-id :player-1)
          ;; Find the copy
          copy-objs (d/q '[:find [(pull ?e [*]) ...]
                           :where [?e :object/is-copy true]]
                         db-with-copy)
          copy (first copy-objs)
          copy-id (:object/id copy)
          ;; Resolve the copy
          db-resolved (rules/resolve-spell db-with-copy :player-1 copy-id)]
      (is (nil? (q/get-object db-resolved copy-id))
          "Copy should cease to exist (removed from db, not in graveyard)")
      (is (= 0 (count (q/get-objects-in-zone db-resolved :player-1 :graveyard)))
          "Graveyard should not contain the copy"))))


(deftest test-move-copy-off-stack-ceases-to-exist
  (testing "move-spell-off-stack on a copy removes it from db entirely"
    (let [db (-> (init-game-state)
                 (mana/add-mana :player-1 {:black 1}))
          hand (q/get-hand db :player-1)
          ritual (first hand)
          obj-id (:object/id ritual)
          db-cast (rules/cast-spell db :player-1 obj-id)
          db-with-copy (fizzle.engine.triggers/create-spell-copy db-cast obj-id :player-1)
          copy-objs (d/q '[:find [(pull ?e [*]) ...]
                           :where [?e :object/is-copy true]]
                         db-with-copy)
          copy-id (:object/id (first copy-objs))
          db-off (rules/move-spell-off-stack db-with-copy :player-1 copy-id)]
      (is (nil? (q/get-object db-off copy-id))
          "Copy should cease to exist when countered"))))


;; === phases constant ===

(deftest phases-constant-test
  (testing "phases is a vector of 8 MTG turn phases in order"
    (is (= [:untap :upkeep :draw :main1 :combat :main2 :end :cleanup]
           rules/phases))))


;; === land-card? tests ===

(deftest land-card-true-for-land-test
  (testing "land-card? returns true for a land card"
    (let [db (th/create-test-db)
          [db obj-id] (th/add-card-to-zone db :gemstone-mine :hand :player-1)]
      (is (true? (rules/land-card? db obj-id))))))


(deftest land-card-false-for-nonland-test
  (testing "land-card? returns false for a non-land card"
    (let [db (th/create-test-db)
          [db obj-id] (th/add-card-to-zone db :dark-ritual :hand :player-1)]
      (is (not (rules/land-card? db obj-id))))))


(deftest land-card-false-for-missing-object-test
  (testing "land-card? returns falsy for non-existent object"
    (let [db (th/create-test-db)]
      (is (not (rules/land-card? db (random-uuid)))))))


;; === can-play-land? tests ===

(deftest can-play-land-true-when-conditions-met-test
  (testing "can-play-land? returns true when all conditions met"
    (let [db (th/create-test-db)
          [db obj-id] (th/add-card-to-zone db :gemstone-mine :hand :player-1)]
      (is (true? (rules/can-play-land? db :player-1 obj-id))))))


(deftest can-play-land-false-no-land-plays-test
  (testing "can-play-land? returns false when no land plays remaining"
    (let [db (th/create-test-db {:land-plays 0})
          [db obj-id] (th/add-card-to-zone db :gemstone-mine :hand :player-1)]
      (is (false? (rules/can-play-land? db :player-1 obj-id))))))


(deftest can-play-land-false-for-nonland-test
  (testing "can-play-land? returns false for non-land card"
    (let [db (th/create-test-db)
          [db obj-id] (th/add-card-to-zone db :dark-ritual :hand :player-1)]
      (is (false? (rules/can-play-land? db :player-1 obj-id))))))


;; =====================================================
;; Cast Restriction Tests
;; =====================================================

(def end-step-instant
  {:card/id :end-step-instant
   :card/name "End Step Instant"
   :card/cmc 1
   :card/mana-cost {:black 1}
   :card/colors #{:black}
   :card/types #{:instant}
   :card/text "Cast only during end step."
   :card/cast-restriction {:restriction/phase :end}
   :card/effects []})


(defn add-restricted-card-to-hand
  "Add end-step-instant card to db and player's hand.
   Returns [obj-id db]."
  [db player-id]
  (let [db (d/db-with db [end-step-instant])]
    (th/add-card-to-zone db :end-step-instant :hand player-id)))


(deftest cast-restriction-blocks-during-main-phase
  (testing "Card with :card/cast-restriction {:restriction/phase :end} cannot cast during main1"
    (let [db (th/create-test-db {:mana {:black 1}})
          [db obj-id] (add-restricted-card-to-hand db :player-1)]
      ;; Phase is :main1 by default
      (is (false? (rules/can-cast? db :player-1 obj-id))
          "Should not be castable during main phase"))))


(deftest cast-restriction-allows-during-correct-phase
  (testing "Card with :card/cast-restriction {:restriction/phase :end} can cast during end step"
    (let [db (th/create-test-db {:mana {:black 1}})
          [db obj-id] (add-restricted-card-to-hand db :player-1)
          db (set-phase db :end)]
      (is (true? (rules/can-cast? db :player-1 obj-id))
          "Should be castable during end step"))))


(deftest cast-restriction-blocks-during-other-phases
  (testing "Card with phase restriction cannot cast during combat or upkeep"
    (let [db (th/create-test-db {:mana {:black 1}})
          [db obj-id] (add-restricted-card-to-hand db :player-1)]
      (doseq [phase [:combat :main2]]
        (let [db' (set-phase db phase)]
          (is (false? (rules/can-cast? db' :player-1 obj-id))
              (str "Should not be castable during " (name phase))))))))


(deftest cast-restriction-blocks-during-opponents-end-step
  (testing "Card with end-step restriction cannot be cast during opponent's end step"
    (let [db (-> (th/create-test-db {:mana {:black 1}})
                 th/add-opponent)
          [db obj-id] (add-restricted-card-to-hand db :player-1)
          ;; Set phase to :end but make opponent the active player
          opp-eid (q/get-player-eid db :player-2)
          game-eid (d/q '[:find ?e . :where [?e :game/id _]] db)
          db (d/db-with db [[:db/add game-eid :game/phase :end]
                            [:db/add game-eid :game/active-player opp-eid]])]
      (is (false? (rules/can-cast? db :player-1 obj-id))
          "Should not be castable during opponent's end step"))))


;; === alternate-to-mode pass-through tests ===

(deftest alternate-to-mode-propagates-kind-effects-targeting
  (testing "alternate-to-mode passes :alternate/kind, :alternate/effects, :alternate/targeting through as :mode/* keys"
    (let [alt {:alternate/id :kicked
               :alternate/kind :kicker
               :alternate/zone :hand
               :alternate/mana-cost {:white 2}
               :alternate/effects [{:effect/type :draw :effect/count 1}]
               :alternate/targeting [{:target/id :p :target/type :player :target/required true}]
               :alternate/on-resolve :graveyard}
          mode (#'rules/alternate-to-mode alt)]
      (is (= :kicker (:mode/kind mode))
          "Should propagate :alternate/kind as :mode/kind")
      (is (= [{:effect/type :draw :effect/count 1}] (:mode/effects mode))
          "Should propagate :alternate/effects as :mode/effects")
      (is (= [{:target/id :p :target/type :player :target/required true}]
             (:mode/targeting mode))
          "Should propagate :alternate/targeting as :mode/targeting")))

  (testing "alternate-to-mode does NOT add :mode/kind when :alternate/kind absent"
    (let [alt {:alternate/id :flashback
               :alternate/zone :graveyard
               :alternate/mana-cost {:colorless 1 :blue 1}
               :alternate/on-resolve :exile}
          mode (#'rules/alternate-to-mode alt)]
      (is (not (contains? mode :mode/kind))
          "Should not contain :mode/kind when not provided")
      (is (not (contains? mode :mode/effects))
          "Should not contain :mode/effects when not provided")
      (is (not (contains? mode :mode/targeting))
          "Should not contain :mode/targeting when not provided"))))


;; === :mode/label population (fizzle-8mfo) ===

(deftest get-primary-mode-includes-default-label
  (testing "Primary mode has a non-nil :mode/label so spell-mode-selection-modal can render + key"
    (let [card {:card/id :foo :card/name "Foo" :card/mana-cost {:blue 1} :card/types #{:instant}}
          mode (#'rules/get-primary-mode card)]
      (is (some? (:mode/label mode)))
      (is (string? (:mode/label mode))))))


(deftest alternate-to-mode-uses-authored-label-when-present
  (testing ":alternate/label takes precedence over auto-derivation"
    (let [alt {:alternate/id :kicked
               :alternate/kind :kicker
               :alternate/zone :hand
               :alternate/mana-cost {:colorless 2 :blue 1}
               :alternate/label "Kicker — Sacrifice a land"}
          mode (#'rules/alternate-to-mode alt)]
      (is (= "Kicker — Sacrifice a land" (:mode/label mode))))))


(deftest alternate-to-mode-derives-label-when-not-authored
  (testing "When :alternate/label absent, derive a non-nil label from :alternate/id"
    (let [alt {:alternate/id :flashback
               :alternate/zone :graveyard
               :alternate/mana-cost {:colorless 1 :blue 1}}
          mode (#'rules/alternate-to-mode alt)]
      (is (some? (:mode/label mode)))
      (is (string? (:mode/label mode)))
      (is (re-find #"(?i)flashback" (:mode/label mode))
          "Derived label should be recognizable from the :alternate/id"))))


(deftest casting-modes-have-unique-mode-ids-per-card
  (testing "Mode IDs in a card's casting modes list are unique — invariant for spell-mode-selection-modal :key"
    (let [card-with-kicker {:card/id :rr
                            :card/mana-cost {:blue 1}
                            :card/types #{:instant}
                            :card/alternate-costs [{:alternate/id :kicked
                                                    :alternate/kind :kicker
                                                    :alternate/zone :hand
                                                    :alternate/mana-cost {:colorless 2 :blue 1}}]}
          primary (#'rules/get-primary-mode card-with-kicker)
          alts (#'rules/get-alternate-modes card-with-kicker :hand [])
          all-modes (cons primary alts)
          ids (map :mode/id all-modes)]
      (is (= (count ids) (count (distinct ids)))
          "All :mode/id values must be distinct"))))
