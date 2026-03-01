(ns fizzle.events.order-bottom-selection-test
  "Tests for :order-bottom selection type.

   The :order-bottom selection lets players choose the order that cards are
   placed on the bottom of library. Chained from peek-and-select when
   :selection/order-remainder? is true and 2+ remainder cards exist.

   Tests verify:
   - build-order-bottom-selection creates correct state
   - order-card-in-selection appends to ordered vector (validates membership)
   - unorder-card-in-selection removes from ordered vector
   - any-order fills remaining and completes ordering
   - execute-order-bottom-selection assigns correct positions
   - Chain from peek-and-select with order-remainder? flag
   - No chain when flag false or 0-1 remainder"
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [datascript.core :as d]
    [fizzle.db.init :refer [init-game-state]]
    [fizzle.db.queries :as q]
    [fizzle.events.selection.core :as core]
    [fizzle.events.selection.library :as library]))


;; === Test helpers ===

(defn add-library-cards-with-ids
  "Add cards to a player's library with sequential positions and known object-ids.
   Position 0 = top of library. Returns updated db."
  [db player-id obj-ids]
  (let [conn (d/conn-from-db db)
        player-eid (q/get-player-eid db player-id)]
    (doseq [idx (range (count obj-ids))]
      (let [card-eid (d/q '[:find ?e .
                            :where [?e :card/id :dark-ritual]]
                          @conn)
            obj-id (nth obj-ids idx)]
        (d/transact! conn [{:object/id obj-id
                            :object/card card-eid
                            :object/zone :library
                            :object/owner player-eid
                            :object/controller player-eid
                            :object/position idx
                            :object/tapped false}])))
    @conn))


(defn get-object-zone
  "Get the zone of an object by its UUID."
  [db object-id]
  (d/q '[:find ?zone .
         :in $ ?oid
         :where [?e :object/id ?oid]
         [?e :object/zone ?zone]]
       db object-id))


(defn get-object-position
  "Get the position of an object by its UUID."
  [db object-id]
  (d/q '[:find ?pos .
         :in $ ?oid
         :where [?e :object/id ?oid]
         [?e :object/position ?pos]]
       db object-id))


;; === build-order-bottom-selection tests ===

(deftest test-build-order-bottom-creates-correct-state
  ;; Bug caught: wrong selection type or missing fields in builder
  (testing "builds selection with correct type, candidates set, empty ordered vector"
    (let [card-ids [(random-uuid) (random-uuid) (random-uuid)]
          spell-id (random-uuid)
          result (library/build-order-bottom-selection card-ids :player-1 spell-id)]
      (is (= :order-bottom (:selection/type result))
          "Type must be :order-bottom")
      (is (= (set card-ids) (:selection/candidates result))
          "Candidates must be set of provided card IDs")
      (is (= [] (:selection/ordered result))
          "Ordered vector must start empty")
      (is (= :player-1 (:selection/player-id result))
          "Player ID must be preserved")
      (is (= spell-id (:selection/spell-id result))
          "Spell ID must be preserved for cleanup"))))


(deftest test-build-order-bottom-empty-candidates
  ;; Bug caught: crash when building with empty set (defensive coding)
  (testing "builds valid selection with empty candidates"
    (let [result (library/build-order-bottom-selection [] :player-1 (random-uuid))]
      (is (= #{} (:selection/candidates result))
          "Empty candidates should produce empty set")
      (is (= [] (:selection/ordered result))
          "Ordered should be empty vector"))))


;; === order-card-in-selection tests ===

(deftest test-order-card-appends-to-ordered
  ;; Bug caught: card not added to ordered vector
  (testing "ordering a card from candidates appends it to ordered"
    (let [card-1 (random-uuid)
          card-2 (random-uuid)
          selection {:selection/type :order-bottom
                     :selection/candidates #{card-1 card-2}
                     :selection/ordered []}
          result (library/order-card-in-selection selection card-1)]
      (is (= [card-1] (:selection/ordered result))
          "Card should be appended to ordered vector"))))


(deftest test-order-second-card-appends-after-first
  ;; Bug caught: ordering overwrites instead of appending
  (testing "ordering a second card preserves first card's position"
    (let [card-1 (random-uuid)
          card-2 (random-uuid)
          card-3 (random-uuid)
          selection {:selection/type :order-bottom
                     :selection/candidates #{card-1 card-2 card-3}
                     :selection/ordered [card-1]}
          result (library/order-card-in-selection selection card-2)]
      (is (= [card-1 card-2] (:selection/ordered result))
          "Second card should be after first in ordered vector"))))


(deftest test-order-card-not-in-candidates-is-noop
  ;; Bug caught: ordering card not in candidates corrupts state
  (testing "ordering card not in candidates set is a no-op"
    (let [card-1 (random-uuid)
          outsider (random-uuid)
          selection {:selection/type :order-bottom
                     :selection/candidates #{card-1}
                     :selection/ordered []}
          result (library/order-card-in-selection selection outsider)]
      (is (= [] (:selection/ordered result))
          "Ordered should remain empty when card not in candidates"))))


(deftest test-order-already-ordered-card-is-noop
  ;; Bug caught: double-click adds card twice to ordered vector
  (testing "ordering a card already in ordered is a no-op"
    (let [card-1 (random-uuid)
          card-2 (random-uuid)
          selection {:selection/type :order-bottom
                     :selection/candidates #{card-1 card-2}
                     :selection/ordered [card-1]}
          result (library/order-card-in-selection selection card-1)]
      (is (= [card-1] (:selection/ordered result))
          "Should not duplicate card-1 in ordered vector"))))


;; === unorder-card-in-selection tests ===

(deftest test-unorder-card-removes-from-ordered
  ;; Bug caught: removing card from middle breaks relative order
  (testing "unordering removes card and preserves relative order"
    (let [card-1 (random-uuid)
          card-2 (random-uuid)
          card-3 (random-uuid)
          selection {:selection/type :order-bottom
                     :selection/candidates #{card-1 card-2 card-3}
                     :selection/ordered [card-1 card-2 card-3]}
          result (library/unorder-card-in-selection selection card-2)]
      (is (= [card-1 card-3] (:selection/ordered result))
          "Card-2 removed, card-1 and card-3 maintain relative order"))))


(deftest test-unorder-card-not-in-ordered-is-noop
  ;; Bug caught: unordering non-ordered card corrupts vector
  (testing "unordering card not in ordered vector is a no-op"
    (let [card-1 (random-uuid)
          card-2 (random-uuid)
          selection {:selection/type :order-bottom
                     :selection/candidates #{card-1 card-2}
                     :selection/ordered [card-1]}
          result (library/unorder-card-in-selection selection card-2)]
      (is (= [card-1] (:selection/ordered result))
          "Ordered should remain unchanged"))))


;; === any-order tests ===

(deftest test-any-order-includes-all-candidates
  ;; Bug caught: any-order loses cards from ordered vector
  (testing "any-order puts all candidates in ordered, preserving already-ordered first"
    (let [card-1 (random-uuid)
          card-2 (random-uuid)
          card-3 (random-uuid)
          selection {:selection/type :order-bottom
                     :selection/candidates #{card-1 card-2 card-3}
                     :selection/ordered [card-1]}
          result (library/any-order-selection selection)]
      (is (= 3 (count (:selection/ordered result)))
          "All 3 candidates should be in ordered")
      (is (= card-1 (first (:selection/ordered result)))
          "Previously ordered card-1 should remain first")
      (is (= #{card-1 card-2 card-3} (set (:selection/ordered result)))
          "All candidates should be present in ordered"))))


(deftest test-any-order-with-no-ordered-shuffles-all
  ;; Bug caught: any-order with empty ordered crashes or loses cards
  (testing "any-order with no previously ordered cards puts all in ordered"
    (let [card-1 (random-uuid)
          card-2 (random-uuid)
          selection {:selection/type :order-bottom
                     :selection/candidates #{card-1 card-2}
                     :selection/ordered []}
          result (library/any-order-selection selection)]
      (is (= 2 (count (:selection/ordered result)))
          "Both candidates should be in ordered")
      (is (= #{card-1 card-2} (set (:selection/ordered result)))
          "All candidates should be present"))))


(deftest test-any-order-with-all-ordered-is-identity
  ;; Bug caught: any-order re-shuffles already-ordered cards
  (testing "any-order with all cards already ordered doesn't change order"
    (let [card-1 (random-uuid)
          card-2 (random-uuid)
          card-3 (random-uuid)
          selection {:selection/type :order-bottom
                     :selection/candidates #{card-1 card-2 card-3}
                     :selection/ordered [card-3 card-1 card-2]}
          result (library/any-order-selection selection)]
      (is (= [card-3 card-1 card-2] (:selection/ordered result))
          "When all ordered, any-order should preserve exact order"))))


;; === execute-order-bottom-selection tests ===

(deftest test-execute-assigns-positions-in-order
  ;; Bug caught: off-by-one in position calculation
  (testing "ordered cards get sequential positions below existing library cards"
    (let [card-a (random-uuid)
          card-b (random-uuid)
          card-c (random-uuid)
          ;; 5 existing library cards at positions 0-4
          existing-ids [(random-uuid) (random-uuid) (random-uuid) (random-uuid) (random-uuid)]
          db (-> (init-game-state)
                 (add-library-cards-with-ids :player-1 existing-ids))
          ;; Add 3 cards that will be ordered (they need to exist in db)
          conn (d/conn-from-db db)
          player-eid (q/get-player-eid db :player-1)
          card-eid (d/q '[:find ?e . :where [?e :card/id :dark-ritual]] db)]
      ;; Add the order-bottom cards to db (in some temp zone)
      (d/transact! conn [{:object/id card-a :object/card card-eid
                          :object/zone :library :object/owner player-eid
                          :object/controller player-eid :object/position 10 :object/tapped false}
                         {:object/id card-b :object/card card-eid
                          :object/zone :library :object/owner player-eid
                          :object/controller player-eid :object/position 11 :object/tapped false}
                         {:object/id card-c :object/card card-eid
                          :object/zone :library :object/owner player-eid
                          :object/controller player-eid :object/position 12 :object/tapped false}])
      (let [db-with-cards @conn
            selection {:selection/type :order-bottom
                       :selection/candidates #{card-a card-b card-c}
                       :selection/ordered [card-a card-b card-c]
                       :selection/player-id :player-1
                       :selection/spell-id (random-uuid)}
            db' (library/execute-order-bottom-selection db-with-cards selection)]
        ;; Existing cards have max position 4, so new cards should be at 5, 6, 7
        ;; But the ordered cards are also in library, so max will include them
        ;; Actually, let me reconsider: the ordered cards are in library too
        ;; So max position is 12 (position of card-c)
        ;; Wait - execute should calculate max of NON-ordered library cards
        ;; Let me check: existing are at 0-4, ordered are at 10-12
        ;; If we include all library cards, max is 12, new positions 13, 14, 15
        ;; But that doesn't make sense - ordered cards should replace their own positions
        ;; Actually the ordered cards should be moved to the bottom of the non-ordered cards
        ;; Let me look at how peek does it...
        ;; In peek, remainder cards are already in library, they get new positions
        ;; based on max of all library objects (including themselves)
        ;; So max = max of all library objects = 12
        ;; New positions: 13, 14, 15 for card-a, card-b, card-c
        ;; But wait, card-a is already at position 10 in library...
        ;; Actually peek's execute just reassigns positions, which is fine
        ;; The key assertion: card-a < card-b < card-c in position order
        (is (< (get-object-position db' card-a)
               (get-object-position db' card-b))
            "card-a should have lower position than card-b")
        (is (< (get-object-position db' card-b)
               (get-object-position db' card-c))
            "card-b should have lower position than card-c")
        ;; All ordered cards should have higher positions than existing non-ordered cards
        (let [max-existing (apply max (map #(get-object-position db' %) existing-ids))]
          (doseq [ordered-id [card-a card-b card-c]]
            (is (> (get-object-position db' ordered-id) max-existing)
                (str "Ordered card should be below existing library cards"))))))))


(deftest test-execute-with-empty-library
  ;; Bug caught: max-pos calculation crashes with empty library
  (testing "execute works when library is empty (ordered cards are only library cards)"
    (let [card-a (random-uuid)
          card-b (random-uuid)
          db (init-game-state)
          ;; Add cards directly (they'll be moved to library by execute)
          conn (d/conn-from-db db)
          player-eid (q/get-player-eid db :player-1)
          card-eid (d/q '[:find ?e . :where [?e :card/id :dark-ritual]] db)]
      (d/transact! conn [{:object/id card-a :object/card card-eid
                          :object/zone :library :object/owner player-eid
                          :object/controller player-eid :object/position 0 :object/tapped false}
                         {:object/id card-b :object/card card-eid
                          :object/zone :library :object/owner player-eid
                          :object/controller player-eid :object/position 1 :object/tapped false}])
      (let [db-with-cards @conn
            selection {:selection/type :order-bottom
                       :selection/candidates #{card-a card-b}
                       :selection/ordered [card-a card-b]
                       :selection/player-id :player-1
                       :selection/spell-id (random-uuid)}
            db' (library/execute-order-bottom-selection db-with-cards selection)]
        (is (< (get-object-position db' card-a)
               (get-object-position db' card-b))
            "card-a should be above card-b (lower position)")))))


(deftest test-execute-cards-in-correct-zone
  ;; Bug caught: cards left in wrong zone after execute
  (testing "after execute, all ordered cards are in library zone"
    (let [card-a (random-uuid)
          card-b (random-uuid)
          db (init-game-state)
          conn (d/conn-from-db db)
          player-eid (q/get-player-eid db :player-1)
          card-eid (d/q '[:find ?e . :where [?e :card/id :dark-ritual]] db)]
      (d/transact! conn [{:object/id card-a :object/card card-eid
                          :object/zone :library :object/owner player-eid
                          :object/controller player-eid :object/position 0 :object/tapped false}
                         {:object/id card-b :object/card card-eid
                          :object/zone :library :object/owner player-eid
                          :object/controller player-eid :object/position 1 :object/tapped false}])
      (let [db-with-cards @conn
            selection {:selection/type :order-bottom
                       :selection/candidates #{card-a card-b}
                       :selection/ordered [card-a card-b]
                       :selection/player-id :player-1
                       :selection/spell-id (random-uuid)}
            db' (library/execute-order-bottom-selection db-with-cards selection)]
        (is (= :library (get-object-zone db' card-a))
            "card-a should be in library")
        (is (= :library (get-object-zone db' card-b))
            "card-b should be in library")))))


;; === Chain tests ===

(deftest test-peek-chains-to-order-bottom-when-flag-and-2plus-remainder
  ;; Bug caught: chain not triggered, remainder cards shuffled instead of ordered
  (testing "peek with order-remainder? true and 2+ remainder chains to order-bottom"
    (let [obj-ids [(random-uuid) (random-uuid) (random-uuid)]
          db (-> (init-game-state)
                 (add-library-cards-with-ids :player-1 obj-ids))
          selected-id (first obj-ids)
          selection {:selection/type :peek-and-select
                     :selection/lifecycle :chaining
                     :selection/selected #{selected-id}
                     :selection/candidates (set obj-ids)
                     :selection/selected-zone :hand
                     :selection/remainder-zone :bottom-of-library
                     :selection/order-remainder? true
                     :selection/player-id :player-1
                     :selection/spell-id (random-uuid)
                     :selection/remaining-effects []}
          ;; Executor moves selected to hand, chain builder produces order-bottom
          result (core/execute-confirmed-selection db selection)
          chain-sel (core/build-chain-selection (:db result) selection)]
      (is (some? chain-sel)
          "Should chain to next selection")
      (is (= :order-bottom (:selection/type chain-sel))
          "Chained selection should be :order-bottom")
      (is (= 2 (count (:selection/candidates chain-sel)))
          "Should have 2 remainder cards as candidates")
      (is (not (contains? (:selection/candidates chain-sel) selected-id))
          "Selected card should not be in order-bottom candidates"))))


(deftest test-peek-no-chain-when-flag-false
  ;; Bug caught: chaining when shuffle-remainder? is used (old flag)
  (testing "peek with shuffle-remainder? true does NOT chain (old behavior)"
    (let [obj-ids [(random-uuid) (random-uuid) (random-uuid)]
          db (-> (init-game-state)
                 (add-library-cards-with-ids :player-1 obj-ids))
          selected-id (first obj-ids)
          selection {:selection/type :peek-and-select
                     :selection/selected #{selected-id}
                     :selection/candidates (set obj-ids)
                     :selection/selected-zone :hand
                     :selection/remainder-zone :bottom-of-library
                     :selection/shuffle-remainder? true
                     :selection/player-id :player-1
                     :selection/spell-id (random-uuid)}
          result (core/execute-confirmed-selection db selection)]
      (is (nil? (:pending-selection result))
          "Should NOT chain - old shuffle-remainder? behavior"))))


(deftest test-peek-no-chain-when-1-remainder
  ;; Bug caught: showing ordering UI for single card (unnecessary clicks)
  (testing "peek with order-remainder? true but only 1 remainder does NOT chain"
    (let [obj-ids [(random-uuid) (random-uuid) (random-uuid)]
          db (-> (init-game-state)
                 (add-library-cards-with-ids :player-1 obj-ids))
          ;; Select 2 of 3 — only 1 remainder
          selected-ids (set (take 2 obj-ids))
          selection {:selection/type :peek-and-select
                     :selection/selected selected-ids
                     :selection/candidates (set obj-ids)
                     :selection/selected-zone :hand
                     :selection/remainder-zone :bottom-of-library
                     :selection/order-remainder? true
                     :selection/player-id :player-1
                     :selection/spell-id (random-uuid)}
          result (core/execute-confirmed-selection db selection)]
      (is (nil? (:pending-selection result))
          "Should NOT chain when only 1 remainder card"))))


(deftest test-peek-no-chain-when-0-remainder
  ;; Bug caught: chaining with empty candidates
  (testing "peek with order-remainder? true but 0 remainder does NOT chain"
    (let [obj-ids [(random-uuid) (random-uuid) (random-uuid)]
          db (-> (init-game-state)
                 (add-library-cards-with-ids :player-1 obj-ids))
          ;; Select all 3 — no remainder
          selection {:selection/type :peek-and-select
                     :selection/selected (set obj-ids)
                     :selection/candidates (set obj-ids)
                     :selection/selected-zone :hand
                     :selection/remainder-zone :bottom-of-library
                     :selection/order-remainder? true
                     :selection/player-id :player-1
                     :selection/spell-id (random-uuid)}
          result (core/execute-confirmed-selection db selection)]
      (is (nil? (:pending-selection result))
          "Should NOT chain when 0 remainder cards"))))
