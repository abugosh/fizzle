(ns fizzle.events.scry-confirm-test
  "Tests for scry confirmation handler in game.cljs.

   When player confirms scry selection, the handler:
   - Reorders library with top-pile cards on top, bottom-pile cards on bottom
   - Preserves click order within each pile
   - Executes remaining effects (e.g., draw for Opt)
   - Moves spell to graveyard
   - Clears pending selection state"
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [datascript.core :as d]
    [fizzle.db.init :refer [init-game-state]]
    [fizzle.db.queries :as q]
    [fizzle.events.selection.library :as library]))


;; === Test helpers ===

(defn add-library-cards-with-ids
  "Add cards to a player's library with sequential positions and known object-ids.
   Returns [db [obj-id-1 obj-id-2 ...]] tuple."
  [db player-id num-cards]
  (let [conn (d/conn-from-db db)
        player-eid (q/get-player-eid db player-id)
        card-eid (d/q '[:find ?e .
                        :where [?e :card/id :dark-ritual]]
                      @conn)
        obj-ids (vec (repeatedly num-cards random-uuid))]
    (doseq [idx (range num-cards)]
      (d/transact! conn [{:object/id (nth obj-ids idx)
                          :object/card card-eid
                          :object/zone :library
                          :object/owner player-eid
                          :object/controller player-eid
                          :object/position idx
                          :object/tapped false}]))
    [@conn obj-ids]))


(defn add-spell-on-stack
  "Add a spell object on the stack. Returns [db spell-id] tuple."
  [db player-id]
  (let [conn (d/conn-from-db db)
        player-eid (q/get-player-eid db player-id)
        card-eid (d/q '[:find ?e .
                        :where [?e :card/id :dark-ritual]]
                      @conn)
        spell-id (random-uuid)]
    (d/transact! conn [{:object/id spell-id
                        :object/card card-eid
                        :object/zone :stack
                        :object/owner player-eid
                        :object/controller player-eid
                        :object/tapped false}])
    [@conn spell-id]))


(defn get-library-order
  "Get object-ids from library in position order (top to bottom)."
  [db player-id]
  (let [objs (q/get-objects-in-zone db player-id :library)]
    (->> objs
         (sort-by :object/position)
         (mapv :object/id))))


(defn create-app-db-with-scry-selection
  "Create app-db with pending scry selection."
  [game-db selection]
  {:game/db game-db
   :game/pending-selection selection})


;; === confirm-scry-selection tests ===

(deftest test-confirm-scry-1-top-keeps-card-on-top
  ;; Bug caught: Top pile not placing cards at position 0
  (testing "Scry 1 top keeps card on top of library"
    (let [[db [a b c]] (add-library-cards-with-ids (init-game-state) :player-1 3)
          [db spell-id] (add-spell-on-stack db :player-1)
          selection {:selection/type :scry
                     :selection/player-id :player-1
                     :selection/cards [a]  ; Card A revealed
                     :selection/top-pile [a]  ; Put A on top
                     :selection/bottom-pile []
                     :selection/spell-id spell-id
                     :selection/remaining-effects []}
          app-db (create-app-db-with-scry-selection db selection)
          result-app-db (library/confirm-scry-selection app-db)
          result-db (:game/db result-app-db)
          library-order (get-library-order result-db :player-1)]
      (is (= a (first library-order))
          "Card A should remain at top")
      (is (= [a b c] library-order)
          "Library order should be unchanged when putting scry card on top"))))


(deftest test-confirm-scry-1-bottom-moves-card-to-bottom
  ;; Bug caught: Bottom pile not placing cards at end
  (testing "Scry 1 bottom moves card to bottom of library"
    (let [[db [a b c]] (add-library-cards-with-ids (init-game-state) :player-1 3)
          [db spell-id] (add-spell-on-stack db :player-1)
          selection {:selection/type :scry
                     :selection/player-id :player-1
                     :selection/cards [a]  ; Card A revealed
                     :selection/top-pile []
                     :selection/bottom-pile [a]  ; Put A on bottom
                     :selection/spell-id spell-id
                     :selection/remaining-effects []}
          app-db (create-app-db-with-scry-selection db selection)
          result-app-db (library/confirm-scry-selection app-db)
          result-db (:game/db result-app-db)
          library-order (get-library-order result-db :player-1)]
      (is (= a (last library-order))
          "Card A should be at bottom")
      (is (= [b c a] library-order)
          "Library order should be [B C A] when putting A on bottom"))))


(deftest test-confirm-scry-2-both-top-click-order-preserved
  ;; Bug caught: Click order ignored, cards reversed
  (testing "Scry 2 both top preserves click order"
    (let [[db [a b c]] (add-library-cards-with-ids (init-game-state) :player-1 3)
          [db spell-id] (add-spell-on-stack db :player-1)
          selection {:selection/type :scry
                     :selection/player-id :player-1
                     :selection/cards [a b]  ; Cards A, B revealed
                     :selection/top-pile [a b]  ; A clicked first, then B
                     :selection/bottom-pile []
                     :selection/spell-id spell-id
                     :selection/remaining-effects []}
          app-db (create-app-db-with-scry-selection db selection)
          result-app-db (library/confirm-scry-selection app-db)
          result-db (:game/db result-app-db)
          library-order (get-library-order result-db :player-1)]
      (is (= [a b c] library-order)
          "Library order should be [A B C] - click order preserved"))))


(deftest test-confirm-scry-2-both-bottom-click-order-preserved
  ;; Bug caught: Click order ignored for bottom pile
  (testing "Scry 2 both bottom preserves click order"
    (let [[db [a b c]] (add-library-cards-with-ids (init-game-state) :player-1 3)
          [db spell-id] (add-spell-on-stack db :player-1)
          selection {:selection/type :scry
                     :selection/player-id :player-1
                     :selection/cards [a b]  ; Cards A, B revealed
                     :selection/top-pile []
                     :selection/bottom-pile [a b]  ; A clicked first, then B
                     :selection/spell-id spell-id
                     :selection/remaining-effects []}
          app-db (create-app-db-with-scry-selection db selection)
          result-app-db (library/confirm-scry-selection app-db)
          result-db (:game/db result-app-db)
          library-order (get-library-order result-db :player-1)]
      (is (= [c a b] library-order)
          "Library order should be [C A B] - C stays, then A B at bottom in click order"))))


(deftest test-confirm-scry-2-split-top-and-bottom
  ;; Bug caught: Split assignment not handling both piles
  (testing "Scry 2 split - one top, one bottom"
    (let [[db [a b c]] (add-library-cards-with-ids (init-game-state) :player-1 3)
          [db spell-id] (add-spell-on-stack db :player-1)
          selection {:selection/type :scry
                     :selection/player-id :player-1
                     :selection/cards [a b]  ; Cards A, B revealed
                     :selection/top-pile [b]  ; B goes to top
                     :selection/bottom-pile [a]  ; A goes to bottom
                     :selection/spell-id spell-id
                     :selection/remaining-effects []}
          app-db (create-app-db-with-scry-selection db selection)
          result-app-db (library/confirm-scry-selection app-db)
          result-db (:game/db result-app-db)
          library-order (get-library-order result-db :player-1)]
      (is (= [b c a] library-order)
          "Library order should be [B C A] - B on top, C in middle, A on bottom"))))


(deftest test-confirm-scry-executes-remaining-effects-opt-pattern
  ;; Bug caught: Remaining effects lost after scry
  (testing "Remaining effects executed (Opt pattern: scry then draw)"
    (let [[db [a _b _c]] (add-library-cards-with-ids (init-game-state) :player-1 3)
          [db spell-id] (add-spell-on-stack db :player-1)
          initial-hand-count (count (q/get-objects-in-zone db :player-1 :hand))
          selection {:selection/type :scry
                     :selection/player-id :player-1
                     :selection/cards [a]
                     :selection/top-pile [a]
                     :selection/bottom-pile []
                     :selection/spell-id spell-id
                     :selection/remaining-effects [{:effect/type :draw :effect/amount 1}]}
          app-db (create-app-db-with-scry-selection db selection)
          result-app-db (library/confirm-scry-selection app-db)
          result-db (:game/db result-app-db)
          final-hand-count (count (q/get-objects-in-zone result-db :player-1 :hand))]
      (is (= (inc initial-hand-count) final-hand-count)
          "Hand should have 1 more card after draw effect"))))


(deftest test-confirm-scry-clears-pending-selection
  ;; Bug caught: Selection state leaking after confirm
  (testing "Pending selection cleared after confirm"
    (let [[db [a]] (add-library-cards-with-ids (init-game-state) :player-1 1)
          [db spell-id] (add-spell-on-stack db :player-1)
          selection {:selection/type :scry
                     :selection/player-id :player-1
                     :selection/cards [a]
                     :selection/top-pile [a]
                     :selection/bottom-pile []
                     :selection/spell-id spell-id
                     :selection/remaining-effects []}
          app-db (create-app-db-with-scry-selection db selection)
          result-app-db (library/confirm-scry-selection app-db)]
      (is (nil? (:game/pending-selection result-app-db))
          "Pending selection should be nil after confirm"))))


(deftest test-confirm-scry-moves-spell-to-graveyard
  ;; Bug caught: Spell stays on stack after resolution
  (testing "Spell moved to graveyard after confirm"
    (let [[db [a]] (add-library-cards-with-ids (init-game-state) :player-1 1)
          [db spell-id] (add-spell-on-stack db :player-1)
          selection {:selection/type :scry
                     :selection/player-id :player-1
                     :selection/cards [a]
                     :selection/top-pile [a]
                     :selection/bottom-pile []
                     :selection/spell-id spell-id
                     :selection/remaining-effects []}
          app-db (create-app-db-with-scry-selection db selection)
          result-app-db (library/confirm-scry-selection app-db)
          result-db (:game/db result-app-db)
          spell-obj (q/get-object result-db spell-id)]
      (is (= :graveyard (:object/zone spell-obj))
          "Spell should be in graveyard after resolution"))))


(deftest test-confirm-scry-empty-piles-allowed
  ;; Bug caught: Crash when confirming with all cards unassigned
  (testing "Empty piles allowed - unassigned cards stay in place"
    (let [[db [a _b _c]] (add-library-cards-with-ids (init-game-state) :player-1 3)
          [db spell-id] (add-spell-on-stack db :player-1)
          original-order (get-library-order db :player-1)
          selection {:selection/type :scry
                     :selection/player-id :player-1
                     :selection/cards [a]  ; Card A revealed but not assigned
                     :selection/top-pile []  ; Empty - A not assigned
                     :selection/bottom-pile []
                     :selection/spell-id spell-id
                     :selection/remaining-effects []}
          app-db (create-app-db-with-scry-selection db selection)
          result-app-db (library/confirm-scry-selection app-db)
          result-db (:game/db result-app-db)
          library-order (get-library-order result-db :player-1)]
      (is (= a (first library-order))
          "Unassigned card A should stay at top")
      (is (= 3 (count library-order))
          "All 3 library cards should still be in library")
      (is (= original-order library-order)
          "Library order should be unchanged when no cards assigned to piles"))))
