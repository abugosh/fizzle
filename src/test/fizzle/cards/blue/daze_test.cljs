(ns fizzle.cards.blue.daze-test
  "Tests for Daze card.

   Daze: {1}{U} - Instant
   You may return an Island you control to its owner's hand rather
   than pay this spell's mana cost.
   Counter target spell unless its controller pays {1}.

   Key behaviors:
   - Two casting modes: normal ({1}{U}) and free (return an Island)
   - Counter-unless-they-pay-{1} mechanic
   - Alternate cost requires an Island (land with :island subtype) on battlefield"
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [fizzle.cards.blue.daze :as daze]
    [fizzle.db.queries :as q]
    [fizzle.engine.mana :as mana]
    [fizzle.engine.rules :as rules]
    [fizzle.events.resolution :as resolution]
    [fizzle.events.selection.costs :as sel-costs]
    [fizzle.test-helpers :as th]))


;; === A. Card Definition Tests ===

(deftest daze-card-definition-test
  (testing "card has correct oracle properties"
    (let [card daze/card]
      (is (= :daze (:card/id card))
          "Card ID should be :daze")
      (is (= "Daze" (:card/name card))
          "Card name should match oracle")
      (is (= 2 (:card/cmc card))
          "CMC should be 2")
      (is (= {:colorless 1 :blue 1} (:card/mana-cost card))
          "Mana cost should be {1}{U}")
      (is (= #{:blue} (:card/colors card))
          "Card should be blue")
      (is (= #{:instant} (:card/types card))
          "Card should be an instant")
      (is (= "You may return an Island you control to its owner's hand rather than pay this spell's mana cost.\nCounter target spell unless its controller pays {1}."
             (:card/text card))
          "Oracle text should match")))

  (testing "card has correct targeting"
    (let [targeting (:card/targeting daze/card)]
      (is (= 1 (count targeting))
          "Should have one target requirement")
      (let [req (first targeting)]
        (is (= :target-spell (:target/id req)))
        (is (= :object (:target/type req)))
        (is (= :stack (:target/zone req)))
        (is (= :any (:target/controller req)))
        (is (true? (:target/required req))))))

  (testing "card has alternate cost"
    (let [alternates (:card/alternate-costs daze/card)]
      (is (= 1 (count alternates))
          "Should have one alternate cost")
      (let [alt (first alternates)]
        (is (= :daze-free (:alternate/id alt)))
        (is (= :hand (:alternate/zone alt)))
        (is (= {} (:alternate/mana-cost alt))
            "Alternate cost has no mana cost")
        (let [add-costs (:alternate/additional-costs alt)]
          (is (= 1 (count add-costs)))
          (let [cost (first add-costs)]
            (is (= :return-land (:cost/type cost)))
            (is (= #{:island} (get-in cost [:cost/criteria :match/subtypes]))))))))

  (testing "card has counter-spell effect with unless-pay"
    (let [effects (:card/effects daze/card)]
      (is (= 1 (count effects)))
      (let [effect (first effects)]
        (is (= :counter-spell (:effect/type effect)))
        (is (= :target-spell (:effect/target-ref effect)))
        (is (= {:colorless 1} (:effect/unless-pay effect)))))))


;; === B. Cast-Resolve Happy Path ===

(defn- cast-daze-targeting
  "Helper: cast Daze targeting a spell, using primary mode via production path."
  [db daze-id target-id]
  (th/cast-with-target db :player-1 daze-id target-id))


(deftest daze-counters-when-declined-normal-cast-test
  (testing "Daze counters spell when controller declines payment (normal cast)"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          ;; Opponent casts Dark Ritual — rules/cast-spell required, spell must stay on stack as target
          [db ritual-id] (th/add-card-to-zone db :dark-ritual :hand :player-2)
          db (mana/add-mana db :player-2 {:black 1})
          ;; rules/cast-spell required — spell must stay on stack as target
          db (rules/cast-spell db :player-2 ritual-id)
          ;; Cast Daze normally
          [db daze-id] (th/add-card-to-zone db :daze :hand :player-1)
          db (mana/add-mana db :player-1 {:blue 1 :colorless 1})
          db-cast (cast-daze-targeting db daze-id ritual-id)
          ;; Resolve
          resolve-result (th/resolve-top db-cast)
          selection (:selection resolve-result)
          ;; Decline payment via production helper
          decline-result (th/confirm-selection (:db resolve-result) selection #{:decline})]
      (is (= :graveyard (:object/zone (q/get-object (:db decline-result) ritual-id)))
          "Spell should be countered when payment declined"))))


(deftest daze-spell-survives-when-paid-test
  (testing "Spell survives when controller pays {1}"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          ;; rules/cast-spell required — spell must stay on stack as target
          [db ritual-id] (th/add-card-to-zone db :dark-ritual :hand :player-2)
          db (mana/add-mana db :player-2 {:black 1 :colorless 1})
          ;; rules/cast-spell required — spell must stay on stack as target
          db (rules/cast-spell db :player-2 ritual-id)
          [db daze-id] (th/add-card-to-zone db :daze :hand :player-1)
          db (mana/add-mana db :player-1 {:blue 1 :colorless 1})
          db-cast (cast-daze-targeting db daze-id ritual-id)
          resolve-result (th/resolve-top db-cast)
          selection (:selection resolve-result)
          ;; Pay via production helper
          pay-result (th/confirm-selection (:db resolve-result) selection #{:pay})]
      (is (= :stack (:object/zone (q/get-object (:db pay-result) ritual-id)))
          "Spell should remain on stack when payment made"))))


;; === C. Cannot-Cast Guards ===

(deftest daze-cannot-cast-without-mana-or-island-test
  (testing "Cannot cast Daze without mana and without an Island"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          ;; rules/cast-spell required — spell must stay on stack as target
          [db ritual-id] (th/add-card-to-zone db :dark-ritual :hand :player-2)
          db (mana/add-mana db :player-2 {:black 1})
          ;; rules/cast-spell required — spell must stay on stack as target
          db (rules/cast-spell db :player-2 ritual-id)
          [db daze-id] (th/add-card-to-zone db :daze :hand :player-1)]
      (is (false? (rules/can-cast? db :player-1 daze-id))
          "Should not be castable without mana or Island"))))


(deftest daze-cannot-cast-with-empty-stack-test
  (testing "Cannot cast Daze with empty stack"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          [db daze-id] (th/add-card-to-zone db :daze :hand :player-1)
          db (mana/add-mana db :player-1 {:blue 1 :colorless 1})]
      (is (false? (rules/can-cast? db :player-1 daze-id))
          "Should not be castable with empty stack"))))


;; === D. Storm Count ===

(deftest daze-increments-storm-count-test
  (testing "Casting Daze increments storm count"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          ;; rules/cast-spell required — spell must stay on stack as target
          [db ritual-id] (th/add-card-to-zone db :dark-ritual :hand :player-2)
          db (mana/add-mana db :player-2 {:black 1})
          ;; rules/cast-spell required — spell must stay on stack as target
          db (rules/cast-spell db :player-2 ritual-id)
          [db daze-id] (th/add-card-to-zone db :daze :hand :player-1)
          db (mana/add-mana db :player-1 {:blue 1 :colorless 1})
          storm-before (q/get-storm-count db :player-1)
          db-cast (cast-daze-targeting db daze-id ritual-id)]
      (is (= (inc storm-before) (q/get-storm-count db-cast :player-1))
          "Storm count should increment by 1"))))


;; === E. Alternate Cost Tests ===

(deftest daze-has-two-casting-modes-test
  (testing "Daze has primary and alternate casting modes when Island on battlefield"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          ;; Put a spell on stack — rules/cast-spell required, spell must stay on stack as target
          [db ritual-id] (th/add-card-to-zone db :dark-ritual :hand :player-2)
          db (mana/add-mana db :player-2 {:black 1})
          ;; rules/cast-spell required — spell must stay on stack as target
          db (rules/cast-spell db :player-2 ritual-id)
          ;; Daze in hand with Island on battlefield
          [db daze-id] (th/add-card-to-zone db :daze :hand :player-1)
          [db _island-id] (th/add-card-to-zone db :island :battlefield :player-1)
          db (mana/add-mana db :player-1 {:blue 1 :colorless 1})
          modes (rules/get-casting-modes db :player-1 daze-id)]
      (is (= 2 (count modes))
          "Should have primary and alternate modes")
      (is (some #(= :primary (:mode/id %)) modes)
          "Should have primary mode")
      (is (some #(= :daze-free (:mode/id %)) modes)
          "Should have daze-free alternate mode"))))


(deftest daze-alternate-mode-castable-with-island-test
  (testing "Alternate mode is castable with Island on battlefield (no mana needed)"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          ;; rules/cast-spell required — spell must stay on stack as target
          [db ritual-id] (th/add-card-to-zone db :dark-ritual :hand :player-2)
          db (mana/add-mana db :player-2 {:black 1})
          ;; rules/cast-spell required — spell must stay on stack as target
          db (rules/cast-spell db :player-2 ritual-id)
          ;; Daze in hand, Island on battlefield, NO mana in pool
          [db daze-id] (th/add-card-to-zone db :daze :hand :player-1)
          [db _island-id] (th/add-card-to-zone db :island :battlefield :player-1)]
      (is (true? (rules/can-cast? db :player-1 daze-id))
          "Should be castable with Island but no mana"))))


(deftest daze-alternate-mode-not-castable-without-island-test
  (testing "Alternate mode is not castable without an Island"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          ;; rules/cast-spell required — spell must stay on stack as target
          [db ritual-id] (th/add-card-to-zone db :dark-ritual :hand :player-2)
          db (mana/add-mana db :player-2 {:black 1})
          ;; rules/cast-spell required — spell must stay on stack as target
          db (rules/cast-spell db :player-2 ritual-id)
          ;; Daze in hand, no Island, no mana
          [db daze-id] (th/add-card-to-zone db :daze :hand :player-1)]
      (is (false? (rules/can-cast? db :player-1 daze-id))
          "Should not be castable without Island or mana"))))


(deftest daze-alternate-cost-returns-island-to-hand-test
  (testing "Casting with alternate cost returns the Island to hand"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          ;; rules/cast-spell required — spell must stay on stack as target
          [db ritual-id] (th/add-card-to-zone db :dark-ritual :hand :player-2)
          db (mana/add-mana db :player-2 {:black 1})
          ;; rules/cast-spell required — spell must stay on stack as target
          db (rules/cast-spell db :player-2 ritual-id)
          ;; Daze in hand, Island on battlefield
          [db daze-id] (th/add-card-to-zone db :daze :hand :player-1)
          [db island-id] (th/add-card-to-zone db :island :battlefield :player-1)
          ;; Get the alternate mode
          modes (rules/get-casting-modes db :player-1 daze-id)
          alt-mode (first (filter #(= :daze-free (:mode/id %)) modes))
          return-cost (sel-costs/get-return-land-cost alt-mode)
          ;; Build return-land selection
          sel (sel-costs/build-return-land-selection db :player-1 daze-id alt-mode return-cost)
          ;; Confirm return-land-cost selection via production path
          {:keys [db selection]} (th/confirm-selection db sel #{island-id})]
      ;; Island should be returned to hand
      (is (= :hand (:object/zone (q/get-object db island-id)))
          "Island should be returned to hand")
      ;; Should chain to targeting selection
      (is (= :cast-time-targeting (:selection/domain selection))
          "Should chain to cast-time-targeting selection"))))


;; === G. Edge Cases ===

(deftest daze-only-islands-are-valid-for-alternate-cost-test
  (testing "Non-Island lands don't satisfy the return-land cost"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          ;; rules/cast-spell required — spell must stay on stack as target
          [db ritual-id] (th/add-card-to-zone db :dark-ritual :hand :player-2)
          db (mana/add-mana db :player-2 {:black 1})
          ;; rules/cast-spell required — spell must stay on stack as target
          db (rules/cast-spell db :player-2 ritual-id)
          ;; Daze in hand, Mountain on battlefield (not an Island)
          [db daze-id] (th/add-card-to-zone db :daze :hand :player-1)
          [db _mountain-id] (th/add-card-to-zone db :mountain :battlefield :player-1)
          modes (rules/get-casting-modes db :player-1 daze-id)
          alt-mode (first (filter #(= :daze-free (:mode/id %)) modes))]
      ;; Alternate mode should exist but not be castable
      (is (some? alt-mode)
          "Alternate mode should be defined")
      (is (false? (rules/can-cast-mode? db :player-1 daze-id alt-mode))
          "Alternate mode should not be castable with Mountain"))))


(deftest daze-fizzles-when-target-leaves-stack-test
  (testing "Daze fizzles when target resolves before it"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          ;; rules/cast-spell required — spell must stay on stack as target
          [db ritual-id] (th/add-card-to-zone db :dark-ritual :hand :player-2)
          db (mana/add-mana db :player-2 {:black 1})
          ;; rules/cast-spell required — spell must stay on stack as target
          db (rules/cast-spell db :player-2 ritual-id)
          [db daze-id] (th/add-card-to-zone db :daze :hand :player-1)
          db (mana/add-mana db :player-1 {:blue 1 :colorless 1})
          db-cast (cast-daze-targeting db daze-id ritual-id)
          db-ritual-resolved (rules/move-spell-off-stack db-cast nil ritual-id)
          result (resolution/resolve-one-item db-ritual-resolved)
          db-resolved (:db result)]
      (is (= :graveyard (:object/zone (q/get-object db-resolved daze-id)))
          "Daze should be in graveyard after fizzling"))))
