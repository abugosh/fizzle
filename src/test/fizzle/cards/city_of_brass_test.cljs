(ns fizzle.cards.city-of-brass-test
  "Tests for City of Brass — rainbow land with damage trigger.
   Whenever City of Brass becomes tapped, it deals 1 damage to you.
   T: Add one mana of any color.

   What this file tests:
   - Card definition (fields, trigger structure, ability structure)
   - Mana ability activation (all 5 colors via doseq)
   - Damage trigger (fires on tap, resolves from stack)
   - Cannot-activate guards (wrong zone, already tapped)
   - Edge cases (cumulative damage, play-from-hand trigger registration)"
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [datascript.core :as d]
    [fizzle.cards.iggy-pop :as cards]
    [fizzle.db.queries :as q]
    [fizzle.events.abilities :as ability-events]
    [fizzle.events.game :as game]
    [fizzle.test-helpers :as th]))


;; === Card definition tests ===

(deftest city-of-brass-card-definition-test
  (testing "City of Brass base card data is correct"
    (let [card cards/city-of-brass]
      (is (= :city-of-brass (:card/id card)))
      (is (= "City of Brass" (:card/name card)))
      (is (= 0 (:card/cmc card)))
      (is (= {} (:card/mana-cost card)))
      (is (= #{} (:card/colors card)))
      (is (= #{:land} (:card/types card)))))

  (testing "Trigger structure — becomes-tapped deals 1 damage to controller"
    (let [triggers (:card/triggers cards/city-of-brass)]
      (is (= 1 (count triggers))
          "Should have exactly one trigger")
      (let [trigger (first triggers)]
        (is (= :becomes-tapped (:trigger/type trigger)))
        (is (= 1 (count (:trigger/effects trigger)))
            "Trigger should have one effect")
        (let [effect (first (:trigger/effects trigger))]
          (is (= :deal-damage (:effect/type effect)))
          (is (= 1 (:effect/amount effect)))
          (is (= :controller (:effect/target effect)))))))

  (testing "Mana ability structure — tap for any color"
    (let [abilities (:card/abilities cards/city-of-brass)]
      (is (= 1 (count abilities))
          "Should have exactly one ability")
      (let [ability (first abilities)]
        (is (= :mana (:ability/type ability)))
        (is (true? (get-in ability [:ability/cost :tap]))
            "Should require tap")
        (is (= 1 (count (:ability/effects ability)))
            "Should have one effect")
        (let [effect (first (:ability/effects ability))]
          (is (= :add-mana (:effect/type effect)))
          (is (= {:any 1} (:effect/mana effect))
              "Should produce 1 mana of any color"))))))


;; === Parameterized color mana tests ===

(def ^:private mana-colors [:black :blue :white :red :green])


(deftest city-of-brass-tap-for-any-color-test
  (doseq [color mana-colors]
    (testing (str "City of Brass taps for " (name color) " mana")
      (let [db (th/create-test-db)
            [db' obj-id] (th/add-card-to-zone db :city-of-brass :hand :player-1)
            db-played (game/play-land db' :player-1 obj-id)
            _ (is (= :battlefield (th/get-object-zone db-played obj-id))
                  "Precondition: City of Brass on battlefield")
            initial-pool (q/get-mana-pool db-played :player-1)
            _ (is (= 0 (get initial-pool color))
                  (str "Precondition: " (name color) " mana is 0"))
            db-tapped (ability-events/activate-mana-ability db-played :player-1 obj-id color)]
        (is (= 1 (get (q/get-mana-pool db-tapped :player-1) color))
            (str (name color) " mana should be added to pool"))))))


;; === Trigger/ability tests ===

(deftest city-of-brass-trigger-on-tap-test
  (testing "Tapping City of Brass puts trigger on stack (damage not immediate)"
    (let [db (th/create-test-db)
          [db' obj-id] (th/add-card-to-zone db :city-of-brass :hand :player-1)
          db-played (game/play-land db' :player-1 obj-id)
          initial-life (q/get-life-total db-played :player-1)
          _ (is (= 20 initial-life) "Precondition: player starts at 20 life")
          db-tapped (ability-events/activate-mana-ability db-played :player-1 obj-id :black)]
      ;; Mana is added immediately (mana ability doesn't use stack)
      (is (= 1 (:black (q/get-mana-pool db-tapped :player-1)))
          "Black mana should be added immediately")
      ;; Life unchanged before trigger resolves
      (is (= 20 (q/get-life-total db-tapped :player-1))
          "Life unchanged — trigger on stack, not yet resolved")
      ;; Trigger is on the stack
      (is (= 1 (count (q/get-all-stack-items db-tapped)))
          "One trigger should be on the stack"))))


(deftest city-of-brass-trigger-deals-damage-test
  (testing "Resolving City of Brass trigger deals 1 damage to controller"
    (let [db (th/create-test-db)
          [db' obj-id] (th/add-card-to-zone db :city-of-brass :hand :player-1)
          db-played (game/play-land db' :player-1 obj-id)
          db-tapped (ability-events/activate-mana-ability db-played :player-1 obj-id :blue)
          _ (is (= 20 (q/get-life-total db-tapped :player-1))
                "Life unchanged before resolve")
          db-resolved (:db (game/resolve-one-item db-tapped))]
      (is (= 19 (q/get-life-total db-resolved :player-1))
          "Player should lose 1 life after trigger resolves")
      (is (= 0 (count (q/get-all-stack-items db-resolved)))
          "Stack should be empty after resolving trigger"))))


(deftest city-of-brass-cumulative-damage-test
  (testing "Tapping City of Brass twice deals 2 cumulative damage"
    (let [db (th/create-test-db)
          [db' obj-id] (th/add-card-to-zone db :city-of-brass :hand :player-1)
          db-played (game/play-land db' :player-1 obj-id)
          ;; First tap
          db-tap1 (ability-events/activate-mana-ability db-played :player-1 obj-id :black)
          db-resolve1 (:db (game/resolve-one-item db-tap1))
          _ (is (= 19 (q/get-life-total db-resolve1 :player-1))
                "19 life after first trigger")
          ;; Manually untap for second activation
          obj-eid (d/q '[:find ?e . :in $ ?oid :where [?e :object/id ?oid]]
                       db-resolve1 obj-id)
          db-untapped (d/db-with db-resolve1 [[:db/add obj-eid :object/tapped false]])
          ;; Second tap
          db-tap2 (ability-events/activate-mana-ability db-untapped :player-1 obj-id :red)
          db-resolve2 (:db (game/resolve-one-item db-tap2))]
      (is (= 18 (q/get-life-total db-resolve2 :player-1))
          "Player at 18 life after both triggers resolved"))))


;; === Cannot-activate guard tests ===

(deftest city-of-brass-cannot-activate-from-graveyard-test
  (testing "City of Brass in graveyard cannot activate mana ability"
    (let [db (th/create-test-db)
          [db' obj-id] (th/add-card-to-zone db :city-of-brass :graveyard :player-1)
          initial-pool (q/get-mana-pool db' :player-1)
          _ (is (= 0 (:black initial-pool)) "Precondition: black mana is 0")
          db'' (ability-events/activate-mana-ability db' :player-1 obj-id :black)]
      (is (= 0 (:black (q/get-mana-pool db'' :player-1)))
          "Mana should NOT be added (card in graveyard)")
      (is (= :graveyard (th/get-object-zone db'' obj-id))
          "City of Brass should remain in graveyard"))))


(deftest city-of-brass-cannot-activate-from-hand-test
  (testing "City of Brass in hand cannot activate mana ability"
    (let [db (th/create-test-db)
          [db' obj-id] (th/add-card-to-zone db :city-of-brass :hand :player-1)
          initial-pool (q/get-mana-pool db' :player-1)
          _ (is (= 0 (:green initial-pool)) "Precondition: green mana is 0")
          db'' (ability-events/activate-mana-ability db' :player-1 obj-id :green)]
      (is (= 0 (:green (q/get-mana-pool db'' :player-1)))
          "Mana should NOT be added (card not on battlefield)")
      (is (= :hand (th/get-object-zone db'' obj-id))
          "City of Brass should remain in hand"))))


(deftest city-of-brass-cannot-activate-when-tapped-test
  (testing "City of Brass already tapped cannot activate again"
    (let [db (th/create-test-db)
          [db' obj-id] (th/add-card-to-zone db :city-of-brass :hand :player-1)
          db-played (game/play-land db' :player-1 obj-id)
          ;; First tap succeeds
          db-tap1 (ability-events/activate-mana-ability db-played :player-1 obj-id :black)
          _ (is (= 1 (:black (q/get-mana-pool db-tap1 :player-1)))
                "Precondition: first tap adds mana")
          ;; Second tap (still tapped) should fail
          db-tap2 (ability-events/activate-mana-ability db-tap1 :player-1 obj-id :blue)]
      (is (= 1 (:black (q/get-mana-pool db-tap2 :player-1)))
          "Black mana unchanged after failed second tap")
      (is (= 0 (:blue (q/get-mana-pool db-tap2 :player-1)))
          "Blue mana should NOT be added (already tapped)"))))


;; === Edge case tests ===

(deftest city-of-brass-play-from-hand-registers-trigger-test
  (testing "Playing City of Brass from hand registers becomes-tapped trigger"
    (let [db (th/create-test-db)
          [db' obj-id] (th/add-card-to-zone db :city-of-brass :hand :player-1)
          db-played (game/play-land db' :player-1 obj-id)
          ;; Tap to verify trigger fires (proof trigger was registered)
          db-tapped (ability-events/activate-mana-ability db-played :player-1 obj-id :black)]
      (is (= 1 (count (q/get-all-stack-items db-tapped)))
          "Trigger should fire — proves ETB registered the trigger"))))


(deftest city-of-brass-no-trigger-without-play-test
  (testing "City of Brass added directly to battlefield has no trigger (no ETB path)"
    (let [db (th/create-test-db)
          [db' obj-id] (th/add-card-to-zone db :city-of-brass :battlefield :player-1)
          ;; Tap — no trigger registration because we bypassed play-land
          db-tapped (ability-events/activate-mana-ability db' :player-1 obj-id :black)]
      ;; Mana should still be produced
      (is (= 1 (:black (q/get-mana-pool db-tapped :player-1)))
          "Mana should be added regardless of trigger registration")
      ;; No trigger on stack (triggers weren't registered via ETB)
      (is (= 0 (count (q/get-all-stack-items db-tapped)))
          "No trigger should fire — card bypassed ETB trigger registration"))))
