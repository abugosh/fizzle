(ns fizzle.cards.red.pyroblast-test
  "Tests for Pyroblast card.

   Pyroblast: R - Instant
   Choose one —
   * Counter target spell if it's blue.
   * Destroy target permanent if it's blue.

   Key behaviors:
   - Modal spell: player must choose a mode at cast time
   - Can target ANY spell/permanent (no color restriction at cast time)
   - Effect is conditional: only counters/destroys if target is blue at resolution
   - Non-blue targets: spell resolves but does nothing
   - Different from REB: REB restricts targets at cast time"
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [datascript.core :as d]
    [fizzle.cards.red.pyroblast :as pyroblast]
    [fizzle.db.queries :as q]
    [fizzle.engine.mana :as mana]
    [fizzle.engine.rules :as rules]
    [fizzle.engine.targeting :as targeting]
    [fizzle.events.game :as game]
    [fizzle.events.selection.targeting :as sel-targeting]
    [fizzle.test-helpers :as th]))


;; === A. Card Definition Tests ===

(deftest pyroblast-card-definition-test
  (testing "card has correct oracle properties"
    (let [card pyroblast/card]
      (is (= :pyroblast (:card/id card))
          "Card ID should be :pyroblast")
      (is (= "Pyroblast" (:card/name card))
          "Card name should match oracle")
      (is (= 1 (:card/cmc card))
          "CMC should be 1")
      (is (= {:red 1} (:card/mana-cost card))
          "Mana cost should be {R}")
      (is (= #{:red} (:card/colors card))
          "Card should be red")
      (is (= #{:instant} (:card/types card))
          "Card should be an instant")
      (is (= "Choose one —\n• Counter target spell if it's blue.\n• Destroy target permanent if it's blue."
             (:card/text card))
          "Oracle text should match")))

  (testing "card has two modes"
    (let [modes (:card/modes pyroblast/card)]
      (is (vector? modes)
          "Modes must be a vector")
      (is (= 2 (count modes))
          "Should have exactly two modes")))

  (testing "mode 1: counter target spell (no color restriction in targeting)"
    (let [mode (first (:card/modes pyroblast/card))]
      (is (= "Counter target spell if it's blue" (:mode/label mode))
          "Mode label should match")
      (let [req (first (:mode/targeting mode))]
        (is (= :target-spell (:target/id req))
            "Target ID should be :target-spell")
        (is (= :object (:target/type req))
            "Target type should be :object")
        (is (= :stack (:target/zone req))
            "Target zone should be :stack")
        (is (nil? (:target/criteria req))
            "Should have NO targeting criteria (can target any spell)"))
      (let [effect (first (:mode/effects mode))]
        (is (= :counter-spell (:effect/type effect))
            "Effect should be counter-spell")
        (is (= :target-spell (:effect/target-ref effect))
            "Effect should reference target spell")
        (is (= :target-is-color (get-in effect [:effect/condition :condition/type]))
            "Effect should have resolution-time color condition")
        (is (= :blue (get-in effect [:effect/condition :condition/color]))
            "Condition should check for blue"))))

  (testing "mode 2: destroy target permanent (no color restriction in targeting)"
    (let [mode (second (:card/modes pyroblast/card))]
      (is (= "Destroy target permanent if it's blue" (:mode/label mode))
          "Mode label should match")
      (let [req (first (:mode/targeting mode))]
        (is (= :target-permanent (:target/id req))
            "Target ID should be :target-permanent")
        (is (= :battlefield (:target/zone req))
            "Target zone should be :battlefield")
        (is (nil? (:target/criteria req))
            "Should have NO targeting criteria (can target any permanent)"))
      (let [effect (first (:mode/effects mode))]
        (is (= :destroy (:effect/type effect))
            "Effect should be destroy")
        (is (= :target-permanent (:effect/target-ref effect))
            "Effect should reference target permanent")
        (is (= :target-is-color (get-in effect [:effect/condition :condition/type]))
            "Effect should have resolution-time color condition")
        (is (= :blue (get-in effect [:effect/condition :condition/color]))
            "Condition should check for blue")))))


;; === B. Cast-Resolve Happy Path ===

(deftest pyroblast-counters-blue-spell-test
  (testing "Pyroblast counters a blue spell on the stack"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          ;; Put a blue spell on stack
          [db opt-id] (th/add-card-to-zone db :opt :hand :player-2)
          db (mana/add-mana db :player-2 {:blue 1})
          db (rules/cast-spell db :player-2 opt-id)
          ;; Add Pyroblast
          [db pyro-id] (th/add-card-to-zone db :pyroblast :hand :player-1)
          db (mana/add-mana db :player-1 {:red 1})
          ;; Cast Pyroblast choosing counter mode, targeting blue spell
          chosen-mode (first (:card/modes pyroblast/card))
          target-req (first (:mode/targeting chosen-mode))
          modes (rules/get-casting-modes db :player-1 pyro-id)
          mode (first modes)
          pyro-eid (q/get-object-eid db pyro-id)
          db (d/db-with db [[:db/add pyro-eid :object/chosen-mode chosen-mode]])
          selection {:selection/type :cast-time-targeting
                     :selection/player-id :player-1
                     :selection/object-id pyro-id
                     :selection/mode mode
                     :selection/target-requirement target-req
                     :selection/selected #{opt-id}}
          db-cast (sel-targeting/confirm-cast-time-target db selection)
          result (game/resolve-one-item db-cast)
          db-resolved (:db result)]
      ;; Blue spell should be countered
      (is (= :graveyard (:object/zone (q/get-object db-resolved opt-id)))
          "Blue spell should be countered to graveyard")
      (is (= :graveyard (:object/zone (q/get-object db-resolved pyro-id)))
          "Pyroblast should be in graveyard after resolving"))))


(deftest pyroblast-destroys-blue-permanent-test
  (testing "Pyroblast destroys a blue permanent on the battlefield"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          [db perm-id] (th/add-card-to-zone db :counterspell :battlefield :player-2)
          [db pyro-id] (th/add-card-to-zone db :pyroblast :hand :player-1)
          db (mana/add-mana db :player-1 {:red 1})
          chosen-mode (second (:card/modes pyroblast/card))
          target-req (first (:mode/targeting chosen-mode))
          modes (rules/get-casting-modes db :player-1 pyro-id)
          mode (first modes)
          pyro-eid (q/get-object-eid db pyro-id)
          db (d/db-with db [[:db/add pyro-eid :object/chosen-mode chosen-mode]])
          selection {:selection/type :cast-time-targeting
                     :selection/player-id :player-1
                     :selection/object-id pyro-id
                     :selection/mode mode
                     :selection/target-requirement target-req
                     :selection/selected #{perm-id}}
          db-cast (sel-targeting/confirm-cast-time-target db selection)
          result (game/resolve-one-item db-cast)
          db-resolved (:db result)]
      (is (= :graveyard (:object/zone (q/get-object db-resolved perm-id)))
          "Blue permanent should be destroyed")
      (is (= :graveyard (:object/zone (q/get-object db-resolved pyro-id)))
          "Pyroblast should be in graveyard after resolving"))))


;; === C. Cannot-Cast Guards ===

(deftest pyroblast-cannot-cast-without-mana-test
  (testing "Cannot cast Pyroblast without red mana"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          [db ritual-id] (th/add-card-to-zone db :dark-ritual :hand :player-2)
          db (mana/add-mana db :player-2 {:black 1})
          db (rules/cast-spell db :player-2 ritual-id)
          [db pyro-id] (th/add-card-to-zone db :pyroblast :hand :player-1)]
      (is (false? (rules/can-cast? db :player-1 pyro-id))
          "Should not be castable without mana"))))


(deftest pyroblast-cannot-cast-with-empty-board-and-stack-test
  (testing "Cannot cast Pyroblast with nothing to target"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          [db pyro-id] (th/add-card-to-zone db :pyroblast :hand :player-1)
          db (mana/add-mana db :player-1 {:red 1})]
      (is (false? (rules/can-cast? db :player-1 pyro-id))
          "Should not be castable with no targets at all"))))


(deftest pyroblast-cannot-cast-from-graveyard-test
  (testing "Cannot cast Pyroblast from graveyard"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          [db pyro-id] (th/add-card-to-zone db :pyroblast :graveyard :player-1)
          db (mana/add-mana db :player-1 {:red 1})
          [db opt-id] (th/add-card-to-zone db :opt :hand :player-2)
          db (mana/add-mana db :player-2 {:blue 1})
          db (rules/cast-spell db :player-2 opt-id)]
      (is (false? (rules/can-cast? db :player-1 pyro-id))
          "Should not be castable from graveyard"))))


;; === D. Storm Count ===

(deftest pyroblast-increments-storm-count-test
  (testing "Casting Pyroblast increments storm count"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          [db opt-id] (th/add-card-to-zone db :opt :hand :player-2)
          db (mana/add-mana db :player-2 {:blue 1})
          db (rules/cast-spell db :player-2 opt-id)
          [db pyro-id] (th/add-card-to-zone db :pyroblast :hand :player-1)
          db (mana/add-mana db :player-1 {:red 1})
          storm-before (q/get-storm-count db :player-1)
          chosen-mode (first (:card/modes pyroblast/card))
          target-req (first (:mode/targeting chosen-mode))
          modes (rules/get-casting-modes db :player-1 pyro-id)
          mode (first modes)
          pyro-eid (q/get-object-eid db pyro-id)
          db (d/db-with db [[:db/add pyro-eid :object/chosen-mode chosen-mode]])
          selection {:selection/type :cast-time-targeting
                     :selection/player-id :player-1
                     :selection/object-id pyro-id
                     :selection/mode mode
                     :selection/target-requirement target-req
                     :selection/selected #{opt-id}}
          db-cast (sel-targeting/confirm-cast-time-target db selection)]
      (is (= (inc storm-before) (q/get-storm-count db-cast :player-1))
          "Storm count should increment by 1"))))


;; === F. Targeting Tests ===

(deftest pyroblast-can-target-any-spell-test
  (testing "Counter mode can target any spell, not just blue"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          ;; Put a blue spell on stack
          [db opt-id] (th/add-card-to-zone db :opt :hand :player-2)
          db (mana/add-mana db :player-2 {:blue 1})
          db (rules/cast-spell db :player-2 opt-id)
          ;; Put a black spell on stack
          [db ritual-id] (th/add-card-to-zone db :dark-ritual :hand :player-2)
          db (mana/add-mana db :player-2 {:black 1})
          db (rules/cast-spell db :player-2 ritual-id)
          ;; Check counter mode targeting
          counter-mode (first (:card/modes pyroblast/card))
          target-req (first (:mode/targeting counter-mode))
          targets (targeting/find-valid-targets db :player-1 target-req)]
      (is (= 2 (count targets))
          "Should find both spells as valid targets")
      (is (contains? (set targets) opt-id)
          "Should include blue spell")
      (is (contains? (set targets) ritual-id)
          "Should include non-blue spell"))))


(deftest pyroblast-can-target-any-permanent-test
  (testing "Destroy mode can target any permanent, not just blue"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          [db blue-id] (th/add-card-to-zone db :counterspell :battlefield :player-2)
          [db red-id] (th/add-card-to-zone db :lightning-bolt :battlefield :player-2)
          destroy-mode (second (:card/modes pyroblast/card))
          target-req (first (:mode/targeting destroy-mode))
          targets (targeting/find-valid-targets db :player-1 target-req)]
      (is (= 2 (count targets))
          "Should find both permanents as valid targets")
      (is (contains? (set targets) blue-id)
          "Should include blue permanent")
      (is (contains? (set targets) red-id)
          "Should include non-blue permanent"))))


;; === G. Edge Cases (resolution-time condition) ===

(deftest pyroblast-counter-mode-does-nothing-to-non-blue-spell-test
  (testing "Pyroblast counter mode resolves but does nothing to non-blue spell"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          ;; Put a black spell (Dark Ritual) on stack
          [db ritual-id] (th/add-card-to-zone db :dark-ritual :hand :player-2)
          db (mana/add-mana db :player-2 {:black 1})
          db (rules/cast-spell db :player-2 ritual-id)
          ;; Cast Pyroblast targeting the non-blue spell
          [db pyro-id] (th/add-card-to-zone db :pyroblast :hand :player-1)
          db (mana/add-mana db :player-1 {:red 1})
          chosen-mode (first (:card/modes pyroblast/card))
          target-req (first (:mode/targeting chosen-mode))
          modes (rules/get-casting-modes db :player-1 pyro-id)
          mode (first modes)
          pyro-eid (q/get-object-eid db pyro-id)
          db (d/db-with db [[:db/add pyro-eid :object/chosen-mode chosen-mode]])
          selection {:selection/type :cast-time-targeting
                     :selection/player-id :player-1
                     :selection/object-id pyro-id
                     :selection/mode mode
                     :selection/target-requirement target-req
                     :selection/selected #{ritual-id}}
          db-cast (sel-targeting/confirm-cast-time-target db selection)
          result (game/resolve-one-item db-cast)
          db-resolved (:db result)]
      ;; Dark Ritual should NOT be countered — still on stack
      (is (= :stack (:object/zone (q/get-object db-resolved ritual-id)))
          "Non-blue spell should remain on stack (not countered)")
      ;; Pyroblast should still go to graveyard (it resolved, just did nothing)
      (is (= :graveyard (:object/zone (q/get-object db-resolved pyro-id)))
          "Pyroblast should be in graveyard after resolving"))))


(deftest pyroblast-destroy-mode-does-nothing-to-non-blue-permanent-test
  (testing "Pyroblast destroy mode resolves but does nothing to non-blue permanent"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          ;; Put a red permanent on battlefield
          [db red-perm-id] (th/add-card-to-zone db :lightning-bolt :battlefield :player-2)
          ;; Cast Pyroblast targeting the non-blue permanent
          [db pyro-id] (th/add-card-to-zone db :pyroblast :hand :player-1)
          db (mana/add-mana db :player-1 {:red 1})
          chosen-mode (second (:card/modes pyroblast/card))
          target-req (first (:mode/targeting chosen-mode))
          modes (rules/get-casting-modes db :player-1 pyro-id)
          mode (first modes)
          pyro-eid (q/get-object-eid db pyro-id)
          db (d/db-with db [[:db/add pyro-eid :object/chosen-mode chosen-mode]])
          selection {:selection/type :cast-time-targeting
                     :selection/player-id :player-1
                     :selection/object-id pyro-id
                     :selection/mode mode
                     :selection/target-requirement target-req
                     :selection/selected #{red-perm-id}}
          db-cast (sel-targeting/confirm-cast-time-target db selection)
          result (game/resolve-one-item db-cast)
          db-resolved (:db result)]
      ;; Red permanent should NOT be destroyed — still on battlefield
      (is (= :battlefield (:object/zone (q/get-object db-resolved red-perm-id)))
          "Non-blue permanent should remain on battlefield (not destroyed)")
      ;; Pyroblast should go to graveyard
      (is (= :graveyard (:object/zone (q/get-object db-resolved pyro-id)))
          "Pyroblast should be in graveyard after resolving"))))


(deftest pyroblast-castable-with-non-blue-target-test
  (testing "Pyroblast is castable even when only non-blue targets exist"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          ;; Only non-blue spell on stack
          [db ritual-id] (th/add-card-to-zone db :dark-ritual :hand :player-2)
          db (mana/add-mana db :player-2 {:black 1})
          db (rules/cast-spell db :player-2 ritual-id)
          [db pyro-id] (th/add-card-to-zone db :pyroblast :hand :player-1)
          db (mana/add-mana db :player-1 {:red 1})]
      (is (true? (rules/can-cast? db :player-1 pyro-id))
          "Should be castable targeting non-blue spell (condition checked at resolution)"))))


(deftest pyroblast-castable-with-non-blue-permanent-test
  (testing "Pyroblast is castable even when only non-blue permanents exist"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          [db _perm-id] (th/add-card-to-zone db :lightning-bolt :battlefield :player-2)
          [db pyro-id] (th/add-card-to-zone db :pyroblast :hand :player-1)
          db (mana/add-mana db :player-1 {:red 1})]
      (is (true? (rules/can-cast? db :player-1 pyro-id))
          "Should be castable targeting non-blue permanent (condition checked at resolution)"))))
