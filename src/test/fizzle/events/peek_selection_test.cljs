(ns fizzle.events.peek-selection-test
  "Tests for peek-and-select selection state creation in game.cljs.

   When a spell with :peek-and-select effect resolves, the selection system
   creates pending selection state for the UI to render a selection modal.
   Player looks at top N cards and selects some for hand, rest go to bottom.

   Tests verify:
   - build-peek-selection creates correct selection state structure
   - Top N cards from library are correctly selected as candidates
   - Edge cases: X=0, library < N, empty library, fail-to-find
   - execute-peek-selection moves cards to correct zones
   - Anti-pattern: single card does NOT auto-select (always shows UI)"
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [datascript.core :as d]
    [fizzle.db.init :refer [init-game-state]]
    [fizzle.db.queries :as q]
    [fizzle.events.selection :as selection]))


;; === Test helpers ===

(defn add-library-cards-with-ids
  "Add cards to a player's library with sequential positions and known object-ids.
   Takes a vector of object-id keywords and adds them with positions 0, 1, 2...
   Position 0 = top of library.
   Returns updated db."
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


(defn add-library-cards
  "Add cards to a player's library with sequential positions.
   Takes a vector of card-ids (keywords for labeling only) and adds them with positions 0, 1, 2...
   Position 0 = top of library.
   Returns updated db."
  [db player-id card-ids]
  (let [conn (d/conn-from-db db)
        player-eid (q/get-player-eid db player-id)]
    (doseq [idx (range (count card-ids))]
      (let [card-eid (d/q '[:find ?e .
                            :where [?e :card/id :dark-ritual]]
                          @conn)]
        (d/transact! conn [{:object/id (random-uuid)
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


;; === build-peek-selection tests ===

(deftest test-build-peek-selection-basic
  ;; Bug caught: effect doesn't peek correct number of cards
  (testing "peek-and-select with count=3 shows top 3 cards"
    (let [db (-> (init-game-state)
                 (add-library-cards :player-1 [:a :b :c :d]))
          effect {:effect/type :peek-and-select
                  :effect/count 3
                  :effect/select-count 1
                  :effect/selected-zone :hand
                  :effect/remainder-zone :bottom-of-library
                  :effect/shuffle-remainder? true}
          result (selection/build-peek-selection db :player-1 (random-uuid) effect [])]
      (is (= 3 (count (:selection/candidates result)))
          "Should have 3 candidates from top of library")
      (is (= 1 (:selection/select-count result))
          "Should select exactly 1 card")
      (is (= :peek-and-select (:selection/type result))
          "Effect type should be :peek-and-select"))))


(deftest test-build-peek-selection-x-zero-returns-nil
  ;; Bug caught: X=0 creates empty selection causing UI errors
  (testing "peek-and-select with count=0 is a no-op (returns nil)"
    (let [db (-> (init-game-state)
                 (add-library-cards :player-1 [:a :b]))
          effect {:effect/type :peek-and-select
                  :effect/count 0
                  :effect/select-count 1}
          result (selection/build-peek-selection db :player-1 (random-uuid) effect [])]
      (is (nil? result)
          "Should return nil for count=0 (no selection needed)"))))


(deftest test-build-peek-selection-library-smaller-than-count
  ;; Bug caught: crashes when library has fewer cards than count
  (testing "peek-and-select peeks available cards when library < count"
    (let [db (-> (init-game-state)
                 (add-library-cards :player-1 [:a :b]))  ; only 2 cards
          effect {:effect/type :peek-and-select
                  :effect/count 5  ; asking for 5
                  :effect/select-count 1}
          result (selection/build-peek-selection db :player-1 (random-uuid) effect [])]
      (is (= 2 (count (:selection/candidates result)))
          "Should peek all 2 available cards"))))


(deftest test-build-peek-selection-empty-library-returns-nil
  ;; Bug caught: crashes or invalid selection with empty library
  (testing "peek-and-select with empty library returns nil"
    (let [db (init-game-state)  ; no library cards
          effect {:effect/type :peek-and-select
                  :effect/count 3
                  :effect/select-count 1}
          result (selection/build-peek-selection db :player-1 (random-uuid) effect [])]
      (is (nil? result)
          "Should return nil for empty library (no selection needed)"))))


(deftest test-build-peek-selection-allow-fail-to-find
  ;; Bug caught: player forced to select when they want to fail-to-find
  (testing "peek-and-select allows fail-to-find (select 0)"
    (let [db (-> (init-game-state)
                 (add-library-cards :player-1 [:a :b :c]))
          effect {:effect/type :peek-and-select
                  :effect/count 3
                  :effect/select-count 1}
          result (selection/build-peek-selection db :player-1 (random-uuid) effect [])]
      (is (true? (:selection/allow-fail-to-find? result))
          "Must allow player to select 0 cards"))))


(deftest test-build-peek-selection-single-card-shows-ui
  ;; Bug caught: auto-selecting when only 1 card (violates anti-pattern)
  (testing "peek-and-select with 1 card still shows selection UI"
    (let [db (-> (init-game-state)
                 (add-library-cards :player-1 [:a]))
          effect {:effect/type :peek-and-select
                  :effect/count 1
                  :effect/select-count 1}
          result (selection/build-peek-selection db :player-1 (random-uuid) effect [])]
      (is (= 1 (count (:selection/candidates result)))
          "Should have 1 candidate")
      (is (empty? (:selection/selected result))
          "Should NOT auto-select - player must choose"))))


(deftest test-build-peek-selection-correct-candidates
  ;; Bug caught: wrong cards selected (not top N by position)
  (testing "peek-and-select gets correct cards from top of library"
    (let [obj-ids [(random-uuid) (random-uuid) (random-uuid) (random-uuid)]
          db (-> (init-game-state)
                 (add-library-cards-with-ids :player-1 obj-ids))
          effect {:effect/type :peek-and-select
                  :effect/count 2
                  :effect/select-count 1}
          result (selection/build-peek-selection db :player-1 (random-uuid) effect [])
          expected-ids (set (take 2 obj-ids))]  ; First 2 are positions 0 and 1
      (is (= expected-ids (:selection/candidates result))
          "Candidates should be the top 2 cards by position"))))


(deftest test-build-peek-selection-preserves-remaining-effects
  ;; Bug caught: remaining effects lost after selection
  (testing "Remaining effects preserved for execution after selection"
    (let [db (-> (init-game-state)
                 (add-library-cards :player-1 [:a :b]))
          peek-effect {:effect/type :peek-and-select
                       :effect/count 2
                       :effect/select-count 1}
          draw-effect {:effect/type :draw :effect/amount 1}
          result (selection/build-peek-selection db :player-1 (random-uuid) peek-effect [draw-effect])]
      (is (= [draw-effect] (:selection/remaining-effects result))
          "Remaining effects must be preserved"))))


(deftest test-build-peek-selection-spell-id-set
  ;; Bug caught: missing spell-id prevents proper cleanup
  (testing "Selection state includes spell-id for cleanup"
    (let [db (-> (init-game-state)
                 (add-library-cards :player-1 [:a :b]))
          effect {:effect/type :peek-and-select
                  :effect/count 2
                  :effect/select-count 1}
          spell-id (random-uuid)
          result (selection/build-peek-selection db :player-1 spell-id effect [])]
      (is (= spell-id (:selection/spell-id result))
          "Spell ID must be preserved for cleanup"))))


(deftest test-build-peek-selection-zones-set
  ;; Bug caught: missing zone configuration
  (testing "Selection state includes zone configuration"
    (let [db (-> (init-game-state)
                 (add-library-cards :player-1 [:a :b]))
          effect {:effect/type :peek-and-select
                  :effect/count 2
                  :effect/select-count 1
                  :effect/selected-zone :hand
                  :effect/remainder-zone :bottom-of-library
                  :effect/shuffle-remainder? true}
          result (selection/build-peek-selection db :player-1 (random-uuid) effect [])]
      (is (= :hand (:selection/selected-zone result))
          "Selected zone should be :hand")
      (is (= :bottom-of-library (:selection/remainder-zone result))
          "Remainder zone should be :bottom-of-library")
      (is (true? (:selection/shuffle-remainder? result))
          "Shuffle remainder flag should be true"))))


(deftest test-build-peek-selection-defaults
  ;; Bug caught: missing defaults cause nil pointer
  (testing "Selection uses sensible defaults when not specified"
    (let [db (-> (init-game-state)
                 (add-library-cards :player-1 [:a :b :c]))
          effect {:effect/type :peek-and-select
                  :effect/count 2}  ; minimal effect, no select-count or zones
          result (selection/build-peek-selection db :player-1 (random-uuid) effect [])]
      (is (= 1 (:selection/select-count result))
          "Should default to selecting 1 card")
      (is (= :hand (:selection/selected-zone result))
          "Should default to hand for selected zone")
      (is (= :bottom-of-library (:selection/remainder-zone result))
          "Should default to bottom-of-library for remainder"))))


;; === execute-peek-selection tests ===

(deftest test-execute-peek-selection-moves-selected-to-hand
  ;; Bug caught: selected cards not moved to correct zone
  (testing "execute-peek-selection moves selected card to hand"
    (let [obj-ids [(random-uuid) (random-uuid) (random-uuid)]
          db (-> (init-game-state)
                 (add-library-cards-with-ids :player-1 obj-ids))
          selected-id (first obj-ids)
          selection {:selection/type :peek-and-select
                     :selection/selected #{selected-id}
                     :selection/candidates (set obj-ids)
                     :selection/selected-zone :hand
                     :selection/remainder-zone :bottom-of-library
                     :selection/shuffle-remainder? false
                     :selection/player-id :player-1}
          db' (selection/execute-peek-selection db selection)]
      (is (= :hand (get-object-zone db' selected-id))
          "Selected card should be in hand"))))


(deftest test-execute-peek-selection-moves-remainder-to-bottom
  ;; Bug caught: remainder cards not moved to bottom of library
  (testing "execute-peek-selection moves non-selected cards to bottom"
    (let [obj-ids [(random-uuid) (random-uuid) (random-uuid)]
          extra-ids [(random-uuid) (random-uuid)]
          all-ids (concat obj-ids extra-ids)
          db (-> (init-game-state)
                 (add-library-cards-with-ids :player-1 all-ids))
          selected-id (first obj-ids)
          remainder-ids (rest obj-ids)
          ;; extra cards start at positions 3, 4 (below peeked cards)
          extra-positions-before (mapv #(get-object-position db %) extra-ids)
          selection {:selection/type :peek-and-select
                     :selection/selected #{selected-id}
                     :selection/candidates (set obj-ids)
                     :selection/selected-zone :hand
                     :selection/remainder-zone :bottom-of-library
                     :selection/shuffle-remainder? false
                     :selection/player-id :player-1}
          db' (selection/execute-peek-selection db selection)]
      (doseq [rem-id remainder-ids]
        (is (= :library (get-object-zone db' rem-id))
            "Remainder cards should stay in library"))
      ;; Remainder cards should be at positions higher than the extra cards
      (let [extra-max-pos (apply max extra-positions-before)]
        (doseq [rem-id remainder-ids]
          (is (> (get-object-position db' rem-id) extra-max-pos)
              "Remainder cards should be positioned below pre-existing library cards"))))))


(deftest test-execute-peek-selection-fail-to-find
  ;; Bug caught: crash when player selects 0 cards
  (testing "execute-peek-selection handles fail-to-find (empty selection)"
    (let [obj-ids [(random-uuid) (random-uuid) (random-uuid)]
          db (-> (init-game-state)
                 (add-library-cards-with-ids :player-1 obj-ids))
          original-positions (mapv #(get-object-position db %) obj-ids)
          selection {:selection/type :peek-and-select
                     :selection/selected #{}  ; Player chose nothing
                     :selection/candidates (set obj-ids)
                     :selection/selected-zone :hand
                     :selection/remainder-zone :bottom-of-library
                     :selection/shuffle-remainder? false
                     :selection/player-id :player-1}
          db' (selection/execute-peek-selection db selection)]
      (doseq [obj-id obj-ids]
        (is (= :library (get-object-zone db' obj-id))
            "All cards should stay in library when fail-to-find"))
      ;; Cards should have been repositioned to bottom (not at original top positions)
      (let [new-positions (mapv #(get-object-position db' %) obj-ids)]
        (is (not= original-positions new-positions)
            "Cards should be repositioned to bottom of library")))))


(deftest test-execute-peek-selection-remainder-at-bottom-positions
  ;; Bug caught: remainder cards staying at top instead of getting bottom positions
  (testing "Remainder cards get positions below all existing library cards"
    (let [;; Create 3 peeked cards (positions 0,1,2) and 2 extra (positions 3,4)
          peek-ids [(random-uuid) (random-uuid) (random-uuid)]
          extra-ids [(random-uuid) (random-uuid)]
          all-ids (into peek-ids extra-ids)
          db (-> (init-game-state)
                 (add-library-cards-with-ids :player-1 all-ids))
          selected-id (first peek-ids)
          remainder-ids (rest peek-ids)
          ;; Select first card for hand, rest go to bottom
          selection {:selection/type :peek-and-select
                     :selection/selected #{selected-id}
                     :selection/candidates (set peek-ids)
                     :selection/selected-zone :hand
                     :selection/remainder-zone :bottom-of-library
                     :selection/shuffle-remainder? false
                     :selection/player-id :player-1}
          db' (selection/execute-peek-selection db selection)
          ;; Get positions of all library cards after execution
          extra-positions (mapv #(get-object-position db' %) extra-ids)
          remainder-positions (mapv #(get-object-position db' %) remainder-ids)]
      ;; Remainder cards should be at positions HIGHER than all extra (pre-existing) cards
      (let [max-extra-pos (apply max extra-positions)
            min-remainder-pos (apply min remainder-positions)]
        (is (> min-remainder-pos max-extra-pos)
            (str "Remainder cards (min pos " min-remainder-pos
                 ") should be below pre-existing cards (max pos " max-extra-pos ")"))))))
