(ns fizzle.cards.blue.hydroblast-test
  "Tests for Hydroblast card.

   Hydroblast: U - Instant
   Choose one —
   * Counter target spell if it's red.
   * Destroy target permanent if it's red.

   Key behaviors:
   - Modal spell: player must choose a mode at cast time
   - Can target ANY spell/permanent (no color restriction at cast time)
   - Effect is conditional: only counters/destroys if target is red at resolution
   - Non-red targets: spell resolves but does nothing
   - Different from BEB: BEB restricts targets at cast time"
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [fizzle.cards.blue.hydroblast :as hydroblast]
    [fizzle.db.queries :as q]
    [fizzle.engine.mana :as mana]
    [fizzle.engine.rules :as rules]
    [fizzle.engine.targeting :as targeting]
    [fizzle.test-helpers :as th]))


;; === A. Card Definition Tests ===

(deftest hydroblast-card-definition-test
  (testing "card has correct oracle properties"
    (let [card hydroblast/card]
      (is (= :hydroblast (:card/id card))
          "Card ID should be :hydroblast")
      (is (= "Hydroblast" (:card/name card))
          "Card name should match oracle")
      (is (= 1 (:card/cmc card))
          "CMC should be 1")
      (is (= {:blue 1} (:card/mana-cost card))
          "Mana cost should be {U}")
      (is (= #{:blue} (:card/colors card))
          "Card should be blue")
      (is (= #{:instant} (:card/types card))
          "Card should be an instant")
      (is (= "Choose one —\n• Counter target spell if it's red.\n• Destroy target permanent if it's red."
             (:card/text card))
          "Oracle text should match")))

  (testing "card has two modes"
    (let [modes (:card/modes hydroblast/card)]
      (is (vector? modes)
          "Modes must be a vector")
      (is (= 2 (count modes))
          "Should have exactly two modes")))

  (testing "mode 1: counter target spell (no color restriction in targeting)"
    (let [mode (first (:card/modes hydroblast/card))]
      (is (= "Counter target spell if it's red" (:mode/label mode))
          "Mode label should match")
      (let [req (first (:mode/targeting mode))]
        (is (= :target-spell (:target/id req)))
        (is (= :object (:target/type req)))
        (is (= :stack (:target/zone req)))
        (is (nil? (:target/criteria req))
            "Should have NO targeting criteria"))
      (let [effect (first (:mode/effects mode))]
        (is (= :counter-spell (:effect/type effect)))
        (is (= :target-is-color (get-in effect [:effect/condition :condition/type])))
        (is (= :red (get-in effect [:effect/condition :condition/color]))
            "Condition should check for red"))))

  (testing "mode 2: destroy target permanent (no color restriction in targeting)"
    (let [mode (second (:card/modes hydroblast/card))]
      (is (= "Destroy target permanent if it's red" (:mode/label mode))
          "Mode label should match")
      (let [req (first (:mode/targeting mode))]
        (is (= :target-permanent (:target/id req)))
        (is (= :battlefield (:target/zone req)))
        (is (nil? (:target/criteria req))
            "Should have NO targeting criteria"))
      (let [effect (first (:mode/effects mode))]
        (is (= :destroy (:effect/type effect)))
        (is (= :target-is-color (get-in effect [:effect/condition :condition/type])))
        (is (= :red (get-in effect [:effect/condition :condition/color]))
            "Condition should check for red")))))


;; === B. Cast-Resolve Happy Path ===

(deftest hydroblast-counters-red-spell-test
  (testing "Hydroblast counters a red spell on the stack"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          [db bolt-id] (th/add-card-to-zone db :lightning-bolt :hand :player-2)
          db (mana/add-mana db :player-2 {:red 1})
          db (rules/cast-spell db :player-2 bolt-id)
          [db hydro-id] (th/add-card-to-zone db :hydroblast :hand :player-1)
          db (mana/add-mana db :player-1 {:blue 1})
          counter-mode (first (:card/modes hydroblast/card))
          db-cast (th/cast-mode-with-target db :player-1 hydro-id counter-mode bolt-id)
          {:keys [db]} (th/resolve-top db-cast)]
      (is (= :graveyard (:object/zone (q/get-object db bolt-id)))
          "Red spell should be countered to graveyard")
      (is (= :graveyard (:object/zone (q/get-object db hydro-id)))
          "Hydroblast should be in graveyard after resolving"))))


(deftest hydroblast-destroys-red-permanent-test
  (testing "Hydroblast destroys a red permanent on the battlefield"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          [db perm-id] (th/add-card-to-zone db :lightning-bolt :battlefield :player-2)
          [db hydro-id] (th/add-card-to-zone db :hydroblast :hand :player-1)
          db (mana/add-mana db :player-1 {:blue 1})
          destroy-mode (second (:card/modes hydroblast/card))
          db-cast (th/cast-mode-with-target db :player-1 hydro-id destroy-mode perm-id)
          {:keys [db]} (th/resolve-top db-cast)]
      (is (= :graveyard (:object/zone (q/get-object db perm-id)))
          "Red permanent should be destroyed")
      (is (= :graveyard (:object/zone (q/get-object db hydro-id)))
          "Hydroblast should be in graveyard after resolving"))))


;; === C. Cannot-Cast Guards ===

(deftest hydroblast-cannot-cast-without-mana-test
  (testing "Cannot cast Hydroblast without blue mana"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          [db bolt-id] (th/add-card-to-zone db :lightning-bolt :hand :player-2)
          db (mana/add-mana db :player-2 {:red 1})
          db (rules/cast-spell db :player-2 bolt-id)
          [db hydro-id] (th/add-card-to-zone db :hydroblast :hand :player-1)]
      (is (false? (rules/can-cast? db :player-1 hydro-id))
          "Should not be castable without mana"))))


(deftest hydroblast-cannot-cast-with-empty-board-and-stack-test
  (testing "Cannot cast Hydroblast with nothing to target"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          [db hydro-id] (th/add-card-to-zone db :hydroblast :hand :player-1)
          db (mana/add-mana db :player-1 {:blue 1})]
      (is (false? (rules/can-cast? db :player-1 hydro-id))
          "Should not be castable with no targets at all"))))


(deftest hydroblast-cannot-cast-from-graveyard-test
  (testing "Cannot cast Hydroblast from graveyard"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          [db hydro-id] (th/add-card-to-zone db :hydroblast :graveyard :player-1)
          db (mana/add-mana db :player-1 {:blue 1})
          [db bolt-id] (th/add-card-to-zone db :lightning-bolt :hand :player-2)
          db (mana/add-mana db :player-2 {:red 1})
          db (rules/cast-spell db :player-2 bolt-id)]
      (is (false? (rules/can-cast? db :player-1 hydro-id))
          "Should not be castable from graveyard"))))


;; === D. Storm Count ===

(deftest hydroblast-increments-storm-count-test
  (testing "Casting Hydroblast increments storm count"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          [db bolt-id] (th/add-card-to-zone db :lightning-bolt :hand :player-2)
          db (mana/add-mana db :player-2 {:red 1})
          db (rules/cast-spell db :player-2 bolt-id)
          [db hydro-id] (th/add-card-to-zone db :hydroblast :hand :player-1)
          db (mana/add-mana db :player-1 {:blue 1})
          storm-before (q/get-storm-count db :player-1)
          counter-mode (first (:card/modes hydroblast/card))
          db-cast (th/cast-mode-with-target db :player-1 hydro-id counter-mode bolt-id)]
      (is (= (inc storm-before) (q/get-storm-count db-cast :player-1))
          "Storm count should increment by 1"))))


;; === F. Targeting Tests ===

(deftest hydroblast-can-target-any-spell-test
  (testing "Counter mode can target any spell, not just red"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          [db bolt-id] (th/add-card-to-zone db :lightning-bolt :hand :player-2)
          db (mana/add-mana db :player-2 {:red 1})
          db (rules/cast-spell db :player-2 bolt-id)
          [db opt-id] (th/add-card-to-zone db :opt :hand :player-2)
          db (mana/add-mana db :player-2 {:blue 1})
          db (rules/cast-spell db :player-2 opt-id)
          counter-mode (first (:card/modes hydroblast/card))
          target-req (first (:mode/targeting counter-mode))
          targets (targeting/find-valid-targets db :player-1 target-req)]
      (is (= 2 (count targets))
          "Should find both spells as valid targets"))))


;; === G. Edge Cases (resolution-time condition) ===

(deftest hydroblast-counter-does-nothing-to-non-red-spell-test
  (testing "Hydroblast counter mode resolves but does nothing to non-red spell"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          ;; Put a blue spell on stack
          [db opt-id] (th/add-card-to-zone db :opt :hand :player-2)
          db (mana/add-mana db :player-2 {:blue 1})
          db (rules/cast-spell db :player-2 opt-id)
          [db hydro-id] (th/add-card-to-zone db :hydroblast :hand :player-1)
          db (mana/add-mana db :player-1 {:blue 1})
          counter-mode (first (:card/modes hydroblast/card))
          db-cast (th/cast-mode-with-target db :player-1 hydro-id counter-mode opt-id)
          {:keys [db]} (th/resolve-top db-cast)]
      (is (= :stack (:object/zone (q/get-object db opt-id)))
          "Non-red spell should remain on stack (not countered)")
      (is (= :graveyard (:object/zone (q/get-object db hydro-id)))
          "Hydroblast should be in graveyard after resolving"))))


(deftest hydroblast-destroy-does-nothing-to-non-red-permanent-test
  (testing "Hydroblast destroy mode resolves but does nothing to non-red permanent"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          [db blue-perm-id] (th/add-card-to-zone db :counterspell :battlefield :player-2)
          [db hydro-id] (th/add-card-to-zone db :hydroblast :hand :player-1)
          db (mana/add-mana db :player-1 {:blue 1})
          destroy-mode (second (:card/modes hydroblast/card))
          db-cast (th/cast-mode-with-target db :player-1 hydro-id destroy-mode blue-perm-id)
          {:keys [db]} (th/resolve-top db-cast)]
      (is (= :battlefield (:object/zone (q/get-object db blue-perm-id)))
          "Non-red permanent should remain on battlefield")
      (is (= :graveyard (:object/zone (q/get-object db hydro-id)))
          "Hydroblast should be in graveyard after resolving"))))


(deftest hydroblast-castable-with-non-red-target-test
  (testing "Hydroblast is castable even when only non-red targets exist"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          [db opt-id] (th/add-card-to-zone db :opt :hand :player-2)
          db (mana/add-mana db :player-2 {:blue 1})
          db (rules/cast-spell db :player-2 opt-id)
          [db hydro-id] (th/add-card-to-zone db :hydroblast :hand :player-1)
          db (mana/add-mana db :player-1 {:blue 1})]
      (is (true? (rules/can-cast? db :player-1 hydro-id))
          "Should be castable targeting non-red spell"))))
