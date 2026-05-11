(ns fizzle.cards.black.reaping-the-graves-test
  "Tests for Reaping the Graves - {2}{B} instant with storm.
   Return target creature card from your graveyard to your hand.

   Targeted storm spell: creature chosen at cast time. Storm copies
   inherit the chosen target (or may choose new targets per ruling)."
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [fizzle.cards.black.reaping-the-graves :as reaping-the-graves]
    [fizzle.db.queries :as q]
    [fizzle.engine.mana :as mana]
    [fizzle.engine.rules :as rules]
    [fizzle.engine.targeting :as targeting]
    [fizzle.engine.zones :as zones]
    [fizzle.events.resolution :as resolution]
    [fizzle.test-helpers :as th]))


;; === A. Card Definition Tests ===

;; Oracle: "Return target creature card from your graveyard to your hand.
;;          Storm (When you cast this spell, copy it for each spell cast before
;;          it this turn. You may choose new targets for the copies.)"
(deftest reaping-the-graves-card-definition-test
  (testing "Card attributes match Scryfall data"
    (let [card reaping-the-graves/card]
      (is (= :reaping-the-graves (:card/id card)))
      (is (= "Reaping the Graves" (:card/name card)))
      (is (= 3 (:card/cmc card)))
      (is (= {:colorless 2 :black 1} (:card/mana-cost card)))
      (is (= #{:black} (:card/colors card)))
      (is (= #{:instant} (:card/types card)))
      (is (= #{:storm} (:card/keywords card)))))

  (testing "Has cast-time targeting for creature in own graveyard"
    (let [reqs (targeting/get-targeting-requirements reaping-the-graves/card)]
      (is (= 1 (count reqs)))
      (let [req (first reqs)]
        (is (= :graveyard-creature (:target/id req)))
        (is (= :object (:target/type req)))
        (is (= :graveyard (:target/zone req)))
        (is (= :self (:target/controller req)))
        (is (= {:match/types #{:creature}} (:target/criteria req)))
        (is (true? (:target/required req))))))

  (testing "Has bounce effect referencing graveyard-creature target"
    (let [effects (:card/effects reaping-the-graves/card)]
      (is (= 1 (count effects)))
      (let [effect (first effects)]
        (is (= :bounce (:effect/type effect)))
        (is (= :graveyard-creature (:effect/target-ref effect)))))))


;; === B. Cast-Resolve Happy Path ===

;; Oracle: "Return target creature card from your graveyard to your hand."
(deftest reaping-the-graves-returns-creature-to-hand-test
  (testing "Resolving Reaping the Graves moves target creature from graveyard to hand"
    (let [db (-> (th/create-test-db {:mana {:colorless 2 :black 1}})
                 (th/add-opponent))
          [db [creature-id]] (th/add-cards-to-graveyard db [:cloud-of-faeries] :player-1)
          [db rtg-id] (th/add-card-to-zone db :reaping-the-graves :hand :player-1)
          _ (is (= :graveyard (:object/zone (q/get-object db creature-id)))
                "Precondition: creature is in graveyard")
          db-cast (th/cast-with-target db :player-1 rtg-id creature-id)
          ;; Storm trigger on top (0 copies since first spell), resolve it first
          db-storm-resolved (:db (resolution/resolve-one-item db-cast))
          ;; Now resolve the actual spell
          db-resolved (:db (th/resolve-top db-storm-resolved))]
      (is (= :hand (:object/zone (q/get-object db-resolved creature-id)))
          "Target creature should be in hand after resolution")
      (is (= :graveyard (:object/zone (q/get-object db-resolved rtg-id)))
          "Reaping the Graves should be in graveyard after resolution"))))


;; === C. Cannot-Cast Guards ===

(deftest reaping-the-graves-cannot-cast-without-mana-test
  (testing "Cannot cast without {2}{B} mana"
    (let [db (-> (th/create-test-db) (th/add-opponent))
          [db [_creature-id]] (th/add-cards-to-graveyard db [:cloud-of-faeries] :player-1)
          [db rtg-id] (th/add-card-to-zone db :reaping-the-graves :hand :player-1)]
      (is (false? (rules/can-cast? db :player-1 rtg-id))))))


(deftest reaping-the-graves-cannot-cast-without-creature-in-graveyard-test
  (testing "Cannot cast without a creature card in graveyard"
    (let [db (-> (th/create-test-db {:mana {:colorless 2 :black 1}})
                 (th/add-opponent))
          [db rtg-id] (th/add-card-to-zone db :reaping-the-graves :hand :player-1)]
      (is (false? (targeting/has-valid-targets? db :player-1 reaping-the-graves/card))
          "No valid targets with empty graveyard")
      (is (false? (rules/can-cast? db :player-1 rtg-id))))))


(deftest reaping-the-graves-cannot-target-non-creature-test
  (testing "Cannot target non-creature cards in graveyard"
    (let [db (-> (th/create-test-db {:mana {:colorless 2 :black 1}})
                 (th/add-opponent))
          [db _] (th/add-cards-to-graveyard db [:dark-ritual] :player-1)
          [db rtg-id] (th/add-card-to-zone db :reaping-the-graves :hand :player-1)]
      (is (false? (targeting/has-valid-targets? db :player-1 reaping-the-graves/card))
          "Instant in graveyard is not a valid target")
      (is (false? (rules/can-cast? db :player-1 rtg-id))))))


(deftest reaping-the-graves-cannot-target-opponent-graveyard-test
  (testing "Cannot target creature in opponent's graveyard"
    (let [db (-> (th/create-test-db {:mana {:colorless 2 :black 1}})
                 (th/add-opponent))
          [db _] (th/add-cards-to-graveyard db [:cloud-of-faeries] :player-2)
          [db rtg-id] (th/add-card-to-zone db :reaping-the-graves :hand :player-1)]
      (is (false? (targeting/has-valid-targets? db :player-1 reaping-the-graves/card))
          "Creature in opponent graveyard is not a valid target")
      (is (false? (rules/can-cast? db :player-1 rtg-id))))))


;; === D. Storm Count ===

;; Oracle: "Storm (When you cast this spell, copy it for each spell cast before
;;          it this turn.)"
(deftest reaping-the-graves-increments-storm-count-test
  (testing "Casting Reaping the Graves increments storm count"
    (let [db (-> (th/create-test-db {:mana {:colorless 2 :black 1}})
                 (th/add-opponent))
          [db [creature-id]] (th/add-cards-to-graveyard db [:cloud-of-faeries] :player-1)
          [db rtg-id] (th/add-card-to-zone db :reaping-the-graves :hand :player-1)
          _ (is (= 0 (q/get-storm-count db :player-1)))
          db-cast (th/cast-with-target db :player-1 rtg-id creature-id)]
      (is (= 1 (q/get-storm-count db-cast :player-1))))))


;; === E. Storm Trigger Tests ===

;; Ruling (2022-12-08): "The copies are put directly onto the stack. They aren't
;;   cast and won't be counted by other spells with storm cast later in the turn."
(deftest reaping-the-graves-storm-trigger-created-test
  (testing "Storm trigger is created with correct copy count"
    (let [db (-> (th/create-test-db {:mana {:black 1}})
                 (th/add-opponent))
          [db [creature-id]] (th/add-cards-to-graveyard db [:cloud-of-faeries] :player-1)
          ;; Cast 2 rituals first
          [db dr1-id] (th/add-card-to-zone db :dark-ritual :hand :player-1)
          db (th/cast-and-resolve db :player-1 dr1-id)
          [db dr2-id] (th/add-card-to-zone db :dark-ritual :hand :player-1)
          db (th/cast-and-resolve db :player-1 dr2-id)
          _ (is (= 2 (q/get-storm-count db :player-1)))
          ;; Cast Reaping the Graves
          [db rtg-id] (th/add-card-to-zone db :reaping-the-graves :hand :player-1)
          db (mana/add-mana db :player-1 {:colorless 2 :black 1})
          db-cast (th/cast-with-target db :player-1 rtg-id creature-id)
          _ (is (= 3 (q/get-storm-count db-cast :player-1)))
          all-items (q/get-all-stack-items db-cast)
          storm-items (filter #(= :storm (:stack-item/type %)) all-items)]
      (is (= 1 (count storm-items)))
      (is (= 2 (get-in (first storm-items) [:stack-item/effects 0 :effect/count]))
          "Storm trigger count should be 2 (spells cast before Reaping)"))))


;; Ruling (2022-12-08): "You may choose new targets for any of the copies."
(deftest reaping-the-graves-storm-copies-return-creatures-test
  (testing "Storm copies each return a creature from graveyard"
    (let [db (-> (th/create-test-db {:mana {:black 1}})
                 (th/add-opponent))
          ;; Put creatures in graveyard for targeting
          [db [c1-id]] (th/add-cards-to-graveyard
                         db [:cloud-of-faeries]
                         :player-1)
          ;; Cast a ritual to build storm
          [db dr-id] (th/add-card-to-zone db :dark-ritual :hand :player-1)
          db (th/cast-and-resolve db :player-1 dr-id)
          ;; Cast Reaping targeting first creature
          [db rtg-id] (th/add-card-to-zone db :reaping-the-graves :hand :player-1)
          db (mana/add-mana db :player-1 {:colorless 2 :black 1})
          db-cast (th/cast-with-target db :player-1 rtg-id c1-id)
          ;; Resolve storm trigger (creates 1 copy)
          storm-result (th/resolve-top db-cast)
          storm-sel (:selection storm-result)
          ;; For object-targeting storm, provide sequence of targets for copies
          confirm-result (th/confirm-selection (:db storm-result)
                                               (assoc storm-sel :selection/sequence [c1-id])
                                               (:selection/selected storm-sel))
          db-copies (:db confirm-result)
          ;; Find copy on stack
          stack-objects (q/get-objects-in-zone db-copies :player-1 :stack)
          copies (filter :object/is-copy stack-objects)]
      (is (= 1 (count copies))
          "Should have 1 storm copy on stack")
      ;; Resolve copy (inherits target c1-id) then original
      (let [db-copy-resolved (:db (th/resolve-top db-copies))
            db-resolved (:db (th/resolve-top db-copy-resolved))]
        (is (= :hand (:object/zone (q/get-object db-resolved c1-id)))
            "Target creature should be in hand")))))


;; === G. Edge Cases ===

(deftest reaping-the-graves-storm-zero-no-copies-test
  (testing "Storm as first spell creates no copies"
    (let [db (-> (th/create-test-db {:mana {:colorless 2 :black 1}})
                 (th/add-opponent))
          [db [creature-id]] (th/add-cards-to-graveyard db [:cloud-of-faeries] :player-1)
          [db rtg-id] (th/add-card-to-zone db :reaping-the-graves :hand :player-1)
          db-cast (th/cast-with-target db :player-1 rtg-id creature-id)
          _ (is (= 1 (q/get-storm-count db-cast :player-1)))
          storm-trigger (first (filter #(= :storm (:stack-item/type %))
                                       (q/get-all-stack-items db-cast)))]
      (is (= 0 (get-in storm-trigger [:stack-item/effects 0 :effect/count]))
          "Storm count=0 means no copies")
      (let [db-after-trigger (:db (resolution/resolve-one-item db-cast))
            copies (filter :object/is-copy
                           (q/get-objects-in-zone db-after-trigger :player-1 :stack))]
        (is (= 0 (count copies)))))))


(deftest reaping-the-graves-fizzles-if-target-exiled-test
  (testing "Fizzles if target creature is exiled before resolution"
    (let [db (-> (th/create-test-db {:mana {:colorless 2 :black 1}})
                 (th/add-opponent))
          [db [creature-id]] (th/add-cards-to-graveyard db [:cloud-of-faeries] :player-1)
          [db rtg-id] (th/add-card-to-zone db :reaping-the-graves :hand :player-1)
          db-cast (th/cast-with-target db :player-1 rtg-id creature-id)
          ;; Resolve storm trigger first (0 copies)
          db-storm-resolved (:db (resolution/resolve-one-item db-cast))
          ;; Exile the target before spell resolution (simulate Tormod's Crypt)
          db-exiled (zones/move-to-zone* db-storm-resolved creature-id :exile)
          db-resolved (:db (resolution/resolve-one-item db-exiled))]
      (is (= :exile (:object/zone (q/get-object db-resolved creature-id)))
          "Creature should remain in exile")
      (is (= :graveyard (:object/zone (q/get-object db-resolved rtg-id)))
          "Reaping the Graves should go to graveyard (fizzled)"))))


(deftest reaping-the-graves-with-only-non-creatures-in-graveyard-test
  (testing "Cannot cast when only non-creature cards are in graveyard"
    (let [db (-> (th/create-test-db {:mana {:colorless 2 :black 1}})
                 (th/add-opponent))
          [db _] (th/add-cards-to-graveyard db [:dark-ritual :cabal-ritual :careful-study]
                                            :player-1)
          [db rtg-id] (th/add-card-to-zone db :reaping-the-graves :hand :player-1)]
      (is (false? (rules/can-cast? db :player-1 rtg-id))
          "Should not be castable with only instants/sorceries in graveyard"))))


(deftest reaping-the-graves-finds-creature-among-mixed-graveyard-test
  (testing "Can target creature even when mixed with non-creatures"
    (let [db (-> (th/create-test-db {:mana {:colorless 2 :black 1}})
                 (th/add-opponent))
          [db [_ritual-id creature-id]] (th/add-cards-to-graveyard
                                          db [:dark-ritual :cloud-of-faeries] :player-1)
          [db rtg-id] (th/add-card-to-zone db :reaping-the-graves :hand :player-1)
          req (first (targeting/get-targeting-requirements reaping-the-graves/card))
          valid-targets (targeting/find-valid-targets db :player-1 req)]
      (is (= [creature-id] valid-targets)
          "Only the creature should be a valid target")
      (is (true? (rules/can-cast? db :player-1 rtg-id))))))
