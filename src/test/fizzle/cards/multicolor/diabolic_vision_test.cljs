(ns fizzle.cards.multicolor.diabolic-vision-test
  "Tests for Diabolic Vision card.

   Diabolic Vision: {U}{B} - Sorcery
   Look at the top five cards of your library. Put one of them into your
   hand and the rest on top of your library in any order.

   First multicolor card. Uses peek-and-select with :top-of-library remainder."
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [fizzle.cards.multicolor.diabolic-vision :as diabolic-vision]
    [fizzle.db.queries :as q]
    [fizzle.engine.rules :as rules]
    [fizzle.events.resolution :as resolution]
    [fizzle.test-helpers :as th]))


;; === A. Card Definition Tests ===

(deftest card-definition-test
  (testing "Diabolic Vision card fields"
    (let [card diabolic-vision/card]
      (is (= :diabolic-vision (:card/id card)))
      (is (= "Diabolic Vision" (:card/name card)))
      (is (= 2 (:card/cmc card)))
      (is (= {:blue 1 :black 1} (:card/mana-cost card)))
      (is (= #{:blue :black} (:card/colors card)))
      (is (= #{:sorcery} (:card/types card)))))

  (testing "Diabolic Vision uses peek-and-select with top-of-library remainder"
    (let [effects (:card/effects diabolic-vision/card)]
      (is (= 1 (count effects)))
      (let [effect (first effects)]
        (is (= :peek-and-select (:effect/type effect)))
        (is (= 5 (:effect/count effect)))
        (is (= 1 (:effect/select-count effect)))
        (is (= :hand (:effect/selected-zone effect)))
        (is (= :top-of-library (:effect/remainder-zone effect)))
        (is (true? (:effect/order-remainder? effect)))))))


;; === B. Cast-Resolve Happy Path ===

(deftest cast-resolve-creates-peek-selection-test
  (testing "Cast with UB, resolve creates peek-and-select selection for top 5"
    (let [db (th/create-test-db {:mana {:blue 1 :black 1}})
          [db lib-ids] (th/add-cards-to-library db
                                                [:dark-ritual :cabal-ritual :brain-freeze
                                                 :careful-study :mental-note :island]
                                                :player-1)
          [db dv-id] (th/add-card-to-zone db :diabolic-vision :hand :player-1)
          ;; Interactive spell — rules/cast-spell required (selection on resolve)
          db-cast (rules/cast-spell db :player-1 dv-id)
          result (resolution/resolve-one-item db-cast)
          sel (:pending-selection result)]
      (is (= :peek-and-select (:selection/type sel)))
      (is (= 5 (count (:selection/candidates sel))))
      (is (= (set (take 5 lib-ids)) (:selection/candidates sel)))
      (is (= 1 (:selection/select-count sel)))
      (is (true? (:selection/order-remainder? sel))))))


;; === C. Cannot-Cast Guards ===

(deftest cannot-cast-without-mana-test
  (testing "Cannot cast Diabolic Vision without mana"
    (let [db (th/create-test-db)
          [db dv-id] (th/add-card-to-zone db :diabolic-vision :hand :player-1)]
      (is (false? (rules/can-cast? db :player-1 dv-id))))))


(deftest cannot-cast-without-blue-test
  (testing "Cannot cast Diabolic Vision without blue"
    (let [db (th/create-test-db {:mana {:black 1}})
          [db dv-id] (th/add-card-to-zone db :diabolic-vision :hand :player-1)]
      (is (false? (rules/can-cast? db :player-1 dv-id))))))


(deftest cannot-cast-without-black-test
  (testing "Cannot cast Diabolic Vision without black"
    (let [db (th/create-test-db {:mana {:blue 1}})
          [db dv-id] (th/add-card-to-zone db :diabolic-vision :hand :player-1)]
      (is (false? (rules/can-cast? db :player-1 dv-id))))))


(deftest cannot-cast-from-graveyard-test
  (testing "Cannot cast Diabolic Vision from graveyard"
    (let [db (th/create-test-db {:mana {:blue 1 :black 1}})
          [db dv-id] (th/add-card-to-zone db :diabolic-vision :graveyard :player-1)]
      (is (false? (rules/can-cast? db :player-1 dv-id))))))


;; === D. Storm Count ===

(deftest storm-count-increments-test
  (testing "Casting Diabolic Vision increments storm count"
    (let [db (th/create-test-db {:mana {:blue 1 :black 1}})
          [db _] (th/add-cards-to-library db
                                          [:dark-ritual :cabal-ritual :brain-freeze
                                           :careful-study :mental-note]
                                          :player-1)
          [db dv-id] (th/add-card-to-zone db :diabolic-vision :hand :player-1)
          _ (is (= 0 (q/get-storm-count db :player-1)))
          ;; Interactive spell — rules/cast-spell required (selection on resolve)
          db-cast (rules/cast-spell db :player-1 dv-id)]
      (is (= 1 (q/get-storm-count db-cast :player-1))))))


;; === E. Selection Tests ===

(deftest peek-select-chains-to-order-top-test
  (testing "After selecting 1, chains to order-top (NOT order-bottom)"
    (let [db (th/create-test-db {:mana {:blue 1 :black 1}})
          [db lib-ids] (th/add-cards-to-library db
                                                [:dark-ritual :cabal-ritual :brain-freeze
                                                 :careful-study :mental-note :island]
                                                :player-1)
          [db dv-id] (th/add-card-to-zone db :diabolic-vision :hand :player-1)
          ;; Interactive spell — rules/cast-spell required (selection on resolve)
          db-cast (rules/cast-spell db :player-1 dv-id)
          result (resolution/resolve-one-item db-cast)
          sel (:pending-selection result)
          selected-card (first lib-ids)
          {:keys [db selection]} (th/confirm-selection
                                   (:db result) sel #{selected-card})]
      ;; Selected card should be in hand
      (is (= :hand (th/get-object-zone db selected-card)))
      ;; Should chain to order-top
      (is (some? selection))
      (is (= :order-top (:selection/type selection))
          "Chained selection should be :order-top, not :order-bottom")
      (is (= 4 (count (:selection/candidates selection)))
          "Order-top should have 4 remainder cards"))))


(deftest full-resolution-order-top-test
  (testing "After order-top, remainder cards are at top of library"
    (let [db (th/create-test-db {:mana {:blue 1 :black 1}})
          [db lib-ids] (th/add-cards-to-library db
                                                [:dark-ritual :cabal-ritual :brain-freeze
                                                 :careful-study :mental-note :island]
                                                :player-1)
          [db dv-id] (th/add-card-to-zone db :diabolic-vision :hand :player-1)
          ;; Interactive spell — rules/cast-spell required (selection on resolve)
          db-cast (rules/cast-spell db :player-1 dv-id)
          result (resolution/resolve-one-item db-cast)
          sel (:pending-selection result)
          selected-card (first lib-ids)
          {:keys [db selection]} (th/confirm-selection
                                   (:db result) sel #{selected-card})
          remainder-ids (vec (:selection/candidates selection))
          ;; Confirm order-top with a specific ordering
          order-sel (assoc selection :selection/ordered remainder-ids)
          {:keys [db]} (th/confirm-selection db order-sel #{})]
      ;; All 4 remainder cards should still be in library
      (doseq [r-id remainder-ids]
        (is (= :library (th/get-object-zone db r-id))))
      ;; Remainder cards should be at top positions (0-3)
      (doseq [i (range (count remainder-ids))]
        (let [obj (q/get-object db (nth remainder-ids i))
              pos (:object/position obj)]
          (is (= i pos)
              (str "Remainder card " i " should be at position " i))))
      ;; The 6th card (not peeked) should be after the remainder
      (let [unpeeked-pos (:object/position (q/get-object db (nth lib-ids 5)))]
        (is (>= unpeeked-pos (count remainder-ids))
            "Unpeeked card should be after remainder cards")))))


(deftest fail-to-find-all-to-top-test
  (testing "Fail-to-find: all 5 chain to order-top"
    (let [db (th/create-test-db {:mana {:blue 1 :black 1}})
          [db _] (th/add-cards-to-library db
                                          [:dark-ritual :cabal-ritual :brain-freeze
                                           :careful-study :mental-note]
                                          :player-1)
          [db dv-id] (th/add-card-to-zone db :diabolic-vision :hand :player-1)
          ;; Interactive spell — rules/cast-spell required (selection on resolve)
          db-cast (rules/cast-spell db :player-1 dv-id)
          result (resolution/resolve-one-item db-cast)
          sel (:pending-selection result)
          {:keys [selection]} (th/confirm-selection (:db result) sel #{})]
      (is (= :order-top (:selection/type selection)))
      (is (= 5 (count (:selection/candidates selection)))))))


;; === G. Edge Cases ===

(deftest library-smaller-than-five-test
  (testing "Library has 3 cards: peek 3, select 1, 2 to order-top"
    (let [db (th/create-test-db {:mana {:blue 1 :black 1}})
          [db lib-ids] (th/add-cards-to-library db
                                                [:dark-ritual :cabal-ritual :brain-freeze]
                                                :player-1)
          [db dv-id] (th/add-card-to-zone db :diabolic-vision :hand :player-1)
          ;; Interactive spell — rules/cast-spell required (selection on resolve)
          db-cast (rules/cast-spell db :player-1 dv-id)
          result (resolution/resolve-one-item db-cast)
          sel (:pending-selection result)]
      (is (= 3 (count (:selection/candidates sel))))
      (let [selected (first lib-ids)
            {:keys [selection]} (th/confirm-selection (:db result) sel #{selected})]
        (is (= :order-top (:selection/type selection)))
        (is (= 2 (count (:selection/candidates selection))))))))


(deftest library-exactly-one-test
  (testing "Library has 1 card: peek 1, select it, no order-top chain"
    (let [db (th/create-test-db {:mana {:blue 1 :black 1}})
          [db lib-ids] (th/add-cards-to-library db [:dark-ritual] :player-1)
          [db dv-id] (th/add-card-to-zone db :diabolic-vision :hand :player-1)
          ;; Interactive spell — rules/cast-spell required (selection on resolve)
          db-cast (rules/cast-spell db :player-1 dv-id)
          result (resolution/resolve-one-item db-cast)
          sel (:pending-selection result)]
      (is (= 1 (count (:selection/candidates sel))))
      (let [only-card (first lib-ids)
            {:keys [db selection]} (th/confirm-selection (:db result) sel #{only-card})]
        (is (= :hand (th/get-object-zone db only-card)))
        ;; With 0 remainder, no order-top chain
        (is (nil? selection))))))


(deftest empty-library-test
  (testing "Empty library: no selection created"
    (let [db (th/create-test-db {:mana {:blue 1 :black 1}})
          [db dv-id] (th/add-card-to-zone db :diabolic-vision :hand :player-1)
          ;; Interactive spell — rules/cast-spell required (selection on resolve)
          db-cast (rules/cast-spell db :player-1 dv-id)
          result (resolution/resolve-one-item db-cast)]
      (is (nil? (:pending-selection result))))))
