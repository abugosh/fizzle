(ns fizzle.cards.blue.foil-test
  "Tests for Foil card.

   Foil: {2}{U}{U} - Instant
   You may discard an Island card and another card rather than pay
   this spell's mana cost.
   Counter target spell.

   Key behaviors:
   - Hard counter (no unless-they-pay)
   - Two casting modes: normal ({2}{U}{U}) and free (discard Island + another card)
   - Alternate cost requires an Island card and another card in hand"
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [fizzle.cards.blue.foil :as foil]
    [fizzle.db.queries :as q]
    [fizzle.engine.mana :as mana]
    [fizzle.engine.rules :as rules]
    [fizzle.engine.stack :as stack]
    [fizzle.engine.targeting :as targeting]
    [fizzle.engine.validation :as validation]
    [fizzle.events.game :as game]
    [fizzle.events.selection.core :as sel-core]
    [fizzle.events.selection.costs :as sel-costs]
    [fizzle.test-helpers :as th]))


;; === A. Card Definition Tests ===

(deftest foil-card-definition-test
  (testing "card has correct oracle properties"
    (let [card foil/card]
      (is (= :foil (:card/id card))
          "Card ID should be :foil")
      (is (= "Foil" (:card/name card))
          "Card name should match oracle")
      (is (= 4 (:card/cmc card))
          "CMC should be 4")
      (is (= {:colorless 2 :blue 2} (:card/mana-cost card))
          "Mana cost should be {2}{U}{U}")
      (is (= #{:blue} (:card/colors card))
          "Card should be blue")
      (is (= #{:instant} (:card/types card))
          "Card should be an instant")))

  (testing "card has correct targeting"
    (let [targeting (:card/targeting foil/card)]
      (is (= 1 (count targeting)))
      (let [req (first targeting)]
        (is (= :target-spell (:target/id req)))
        (is (= :object (:target/type req)))
        (is (= :stack (:target/zone req)))
        (is (= :any (:target/controller req)))
        (is (true? (:target/required req))))))

  (testing "card has alternate cost"
    (let [alternates (:card/alternate-costs foil/card)]
      (is (= 1 (count alternates)))
      (let [alt (first alternates)]
        (is (= :foil-free (:alternate/id alt)))
        (is (= :hand (:alternate/zone alt)))
        (is (= {} (:alternate/mana-cost alt)))
        (let [add-costs (:alternate/additional-costs alt)]
          (is (= 1 (count add-costs)))
          (let [cost (first add-costs)]
            (is (= :discard-specific (:cost/type cost)))
            (is (= 2 (:cost/total cost)))
            (is (= 2 (count (:cost/groups cost)))))))))

  (testing "card has hard counter effect (no unless-pay)"
    (let [effects (:card/effects foil/card)]
      (is (= 1 (count effects)))
      (let [effect (first effects)]
        (is (= :counter-spell (:effect/type effect)))
        (is (= :target-spell (:effect/target-ref effect)))
        (is (nil? (:effect/unless-pay effect))
            "Should be a hard counter (no unless-pay)")))))


;; === B. Cast-Resolve Happy Path ===

(defn- cast-foil-targeting
  "Helper: cast Foil targeting a spell, using primary mode via production path."
  [db foil-id target-id]
  (th/cast-with-target db :player-1 foil-id target-id))


(deftest foil-counters-spell-normal-cast-test
  (testing "Foil hard counters a spell (normal cast with {2}{U}{U})"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          [db ritual-id] (th/add-card-to-zone db :dark-ritual :hand :player-2)
          db (mana/add-mana db :player-2 {:black 1})
          db (rules/cast-spell db :player-2 ritual-id)
          [db foil-id] (th/add-card-to-zone db :foil :hand :player-1)
          db (mana/add-mana db :player-1 {:blue 2 :colorless 2})
          db-cast (cast-foil-targeting db foil-id ritual-id)
          result (game/resolve-one-item db-cast)
          db-resolved (:db result)]
      ;; Should be a hard counter (no selection needed)
      (is (nil? (:pending-selection result))
          "Hard counter should not create a selection")
      (is (= :graveyard (:object/zone (q/get-object db-resolved ritual-id)))
          "Countered spell should be in graveyard")
      ;; Stack should be empty — countered spell's stack item must be removed
      (is (nil? (stack/get-top-stack-item db-resolved))
          "Stack should be empty after counter resolves"))))


;; === C. Cannot-Cast Guards ===

(deftest foil-cannot-cast-without-mana-or-discard-test
  (testing "Cannot cast Foil without mana and without Island + card in hand"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          [db ritual-id] (th/add-card-to-zone db :dark-ritual :hand :player-2)
          db (mana/add-mana db :player-2 {:black 1})
          db (rules/cast-spell db :player-2 ritual-id)
          [db foil-id] (th/add-card-to-zone db :foil :hand :player-1)]
      (is (false? (rules/can-cast? db :player-1 foil-id))
          "Should not be castable without mana or discard cards"))))


(deftest foil-cannot-cast-with-empty-stack-test
  (testing "Cannot cast Foil with empty stack"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          [db foil-id] (th/add-card-to-zone db :foil :hand :player-1)
          db (mana/add-mana db :player-1 {:blue 2 :colorless 2})]
      (is (false? (rules/can-cast? db :player-1 foil-id))
          "Should not be castable with empty stack"))))


(deftest foil-cannot-cast-from-graveyard-test
  (testing "Cannot cast Foil from graveyard"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          [db foil-id] (th/add-card-to-zone db :foil :graveyard :player-1)
          db (mana/add-mana db :player-1 {:blue 2 :colorless 2})
          [db ritual-id] (th/add-card-to-zone db :dark-ritual :hand :player-2)
          db (mana/add-mana db :player-2 {:black 1})
          db (rules/cast-spell db :player-2 ritual-id)]
      (is (false? (rules/can-cast? db :player-1 foil-id))
          "Should not be castable from graveyard"))))


;; === D. Storm Count ===

(deftest foil-increments-storm-count-test
  (testing "Casting Foil increments storm count"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          [db ritual-id] (th/add-card-to-zone db :dark-ritual :hand :player-2)
          db (mana/add-mana db :player-2 {:black 1})
          db (rules/cast-spell db :player-2 ritual-id)
          [db foil-id] (th/add-card-to-zone db :foil :hand :player-1)
          db (mana/add-mana db :player-1 {:blue 2 :colorless 2})
          storm-before (q/get-storm-count db :player-1)
          db-cast (cast-foil-targeting db foil-id ritual-id)]
      (is (= (inc storm-before) (q/get-storm-count db-cast :player-1))
          "Storm count should increment by 1"))))


;; === E. Alternate Cost Tests ===

(deftest foil-has-two-casting-modes-test
  (testing "Foil has primary and alternate modes when Island + card in hand"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          [db ritual-id] (th/add-card-to-zone db :dark-ritual :hand :player-2)
          db (mana/add-mana db :player-2 {:black 1})
          db (rules/cast-spell db :player-2 ritual-id)
          ;; Foil in hand + Island card + another card in hand
          [db foil-id] (th/add-card-to-zone db :foil :hand :player-1)
          [db _island-id] (th/add-card-to-zone db :island :hand :player-1)
          [db _other-id] (th/add-card-to-zone db :dark-ritual :hand :player-1)
          db (mana/add-mana db :player-1 {:blue 2 :colorless 2})
          modes (rules/get-casting-modes db :player-1 foil-id)]
      (is (= 2 (count modes))
          "Should have primary and alternate modes")
      (is (some #(= :primary (:mode/id %)) modes))
      (is (some #(= :foil-free (:mode/id %)) modes)))))


(deftest foil-alternate-mode-castable-with-island-and-card-test
  (testing "Alternate mode is castable with Island + another card (no mana needed)"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          [db ritual-id] (th/add-card-to-zone db :dark-ritual :hand :player-2)
          db (mana/add-mana db :player-2 {:black 1})
          db (rules/cast-spell db :player-2 ritual-id)
          ;; Foil + Island + another card in hand, NO mana
          [db foil-id] (th/add-card-to-zone db :foil :hand :player-1)
          [db _island-id] (th/add-card-to-zone db :island :hand :player-1)
          [db _other-id] (th/add-card-to-zone db :dark-ritual :hand :player-1)]
      (is (true? (rules/can-cast? db :player-1 foil-id))
          "Should be castable via alternate cost"))))


(deftest foil-alternate-mode-not-castable-without-island-test
  (testing "Alternate mode requires an Island card"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          [db ritual-id] (th/add-card-to-zone db :dark-ritual :hand :player-2)
          db (mana/add-mana db :player-2 {:black 1})
          db (rules/cast-spell db :player-2 ritual-id)
          ;; Foil + 2 non-Island cards in hand, NO mana
          [db foil-id] (th/add-card-to-zone db :foil :hand :player-1)
          [db _card1] (th/add-card-to-zone db :dark-ritual :hand :player-1)
          [db _card2] (th/add-card-to-zone db :dark-ritual :hand :player-1)]
      (is (false? (rules/can-cast? db :player-1 foil-id))
          "Should not be castable without Island card and without mana"))))


(deftest foil-alternate-mode-not-castable-with-only-island-test
  (testing "Alternate mode requires two cards total (Island + another)"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          [db ritual-id] (th/add-card-to-zone db :dark-ritual :hand :player-2)
          db (mana/add-mana db :player-2 {:black 1})
          db (rules/cast-spell db :player-2 ritual-id)
          ;; Foil + only one Island, no other cards, NO mana
          [db foil-id] (th/add-card-to-zone db :foil :hand :player-1)
          [db _island-id] (th/add-card-to-zone db :island :hand :player-1)]
      (is (false? (rules/can-cast? db :player-1 foil-id))
          "Should not be castable with only one card to discard"))))


(deftest foil-alternate-cost-discards-cards-test
  (testing "Casting with alternate cost discards Island + card from hand"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          [db ritual-id] (th/add-card-to-zone db :dark-ritual :hand :player-2)
          db (mana/add-mana db :player-2 {:black 1})
          db (rules/cast-spell db :player-2 ritual-id)
          ;; Foil + Island + Dark Ritual in hand
          [db foil-id] (th/add-card-to-zone db :foil :hand :player-1)
          [db island-id] (th/add-card-to-zone db :island :hand :player-1)
          [db other-id] (th/add-card-to-zone db :dark-ritual :hand :player-1)
          modes (rules/get-casting-modes db :player-1 foil-id)
          alt-mode (first (filter #(= :foil-free (:mode/id %)) modes))
          discard-cost (sel-costs/get-discard-specific-cost alt-mode)
          sel (sel-costs/build-discard-specific-selection db :player-1 foil-id alt-mode discard-cost)
          ;; Select both cards
          sel-with-cards (assoc sel :selection/selected #{island-id other-id})
          result (sel-core/execute-confirmed-selection db sel-with-cards)]
      ;; Both cards should be discarded to graveyard
      (is (= :graveyard (:object/zone (q/get-object (:db result) island-id)))
          "Island should be in graveyard")
      (is (= :graveyard (:object/zone (q/get-object (:db result) other-id)))
          "Other card should be in graveyard")
      ;; Should chain to targeting selection for choosing counter target
      (is (some? (:pending-selection result))
          "Should chain to targeting selection"))))


;; === F. Targeting Tests ===

(deftest foil-targets-any-spell-on-stack-test
  (testing "Targets any spell on the stack"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          [db opt-id] (th/add-card-to-zone db :opt :hand :player-2)
          db (mana/add-mana db :player-2 {:blue 1})
          db (rules/cast-spell db :player-2 opt-id)
          target-req (first (:card/targeting foil/card))
          targets (targeting/find-valid-targets db :player-1 target-req)]
      (is (= 1 (count targets))
          "Should find the spell on stack"))))


;; === G. Edge Cases ===

(deftest foil-discard-rejects-non-island-cards-test
  (testing "Selecting two non-Island cards is invalid for Foil alternate cost"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          [db ritual-id] (th/add-card-to-zone db :dark-ritual :hand :player-2)
          db (mana/add-mana db :player-2 {:black 1})
          db (rules/cast-spell db :player-2 ritual-id)
          ;; Foil + two Lotus Petals in hand (no Island)
          [db foil-id] (th/add-card-to-zone db :foil :hand :player-1)
          [db petal1-id] (th/add-card-to-zone db :lotus-petal :hand :player-1)
          [db petal2-id] (th/add-card-to-zone db :lotus-petal :hand :player-1)
          modes (rules/get-casting-modes db :player-1 foil-id)
          alt-mode (first (filter #(= :foil-free (:mode/id %)) modes))
          discard-cost (sel-costs/get-discard-specific-cost alt-mode)
          sel (sel-costs/build-discard-specific-selection db :player-1 foil-id alt-mode discard-cost)
          ;; Select two Lotus Petals (no Island)
          sel-invalid (assoc sel :selection/selected #{petal1-id petal2-id})]
      ;; Selection should be invalid — need at least one Island card
      (is (not (validation/validate-selection sel-invalid))
          "Two non-Island cards should not satisfy Foil's discard groups"))))


(deftest foil-discard-accepts-island-plus-other-test
  (testing "Selecting an Island card and another card is valid for Foil alternate cost"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          [db ritual-id] (th/add-card-to-zone db :dark-ritual :hand :player-2)
          db (mana/add-mana db :player-2 {:black 1})
          db (rules/cast-spell db :player-2 ritual-id)
          ;; Foil + Island + Lotus Petal in hand
          [db foil-id] (th/add-card-to-zone db :foil :hand :player-1)
          [db island-id] (th/add-card-to-zone db :island :hand :player-1)
          [db petal-id] (th/add-card-to-zone db :lotus-petal :hand :player-1)
          modes (rules/get-casting-modes db :player-1 foil-id)
          alt-mode (first (filter #(= :foil-free (:mode/id %)) modes))
          discard-cost (sel-costs/get-discard-specific-cost alt-mode)
          sel (sel-costs/build-discard-specific-selection db :player-1 foil-id alt-mode discard-cost)
          ;; Select Island + Lotus Petal
          sel-valid (assoc sel :selection/selected #{island-id petal-id})]
      (is (true? (validation/validate-selection sel-valid))
          "Island + non-Island card should satisfy Foil's discard groups"))))


(deftest foil-fizzles-when-target-leaves-stack-test
  (testing "Foil fizzles when target resolves before it"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          [db ritual-id] (th/add-card-to-zone db :dark-ritual :hand :player-2)
          db (mana/add-mana db :player-2 {:black 1})
          db (rules/cast-spell db :player-2 ritual-id)
          [db foil-id] (th/add-card-to-zone db :foil :hand :player-1)
          db (mana/add-mana db :player-1 {:blue 2 :colorless 2})
          db-cast (cast-foil-targeting db foil-id ritual-id)
          db-ritual-resolved (rules/move-spell-off-stack db-cast nil ritual-id)
          result (game/resolve-one-item db-ritual-resolved)
          db-resolved (:db result)]
      (is (= :graveyard (:object/zone (q/get-object db-resolved foil-id)))
          "Foil should be in graveyard after fizzling"))))
