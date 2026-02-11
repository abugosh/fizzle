(ns fizzle.events.pile-choice-test
  "Tests for pile choice selection phase.

   Pile choice is the second phase of Intuition-style tutors:
   1. Multi-select tutor: Player selects N cards from library
   2. Pile choice: Player chooses which of those cards go to hand vs graveyard

   Tests verify:
   - build-pile-choice-selection creates correct state
   - execute-pile-choice moves cards to correct zones
   - confirm-tutor-selection chains to pile-choice when present
   - Random selection picks correct count
   - Edge cases: single card, empty selection"
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [datascript.core :as d]
    [fizzle.cards.iggy-pop :as cards]
    [fizzle.db.queries :as q]
    [fizzle.db.schema :refer [schema]]
    [fizzle.events.selection.library :as library]))


;; === Test helpers ===

(defn create-test-db
  "Create a game state with all card definitions loaded."
  []
  (let [conn (d/create-conn schema)]
    ;; Transact all card definitions
    (d/transact! conn cards/all-cards)
    ;; Transact player
    (d/transact! conn [{:player/id :player-1
                        :player/name "Player"
                        :player/life 20
                        :player/mana-pool {:white 0 :blue 3 :black 0
                                           :red 0 :green 0 :colorless 0}
                        :player/storm-count 0
                        :player/land-plays-left 1}])
    ;; Transact game state
    (let [player-eid (d/q '[:find ?e . :where [?e :player/id :player-1]] @conn)]
      (d/transact! conn [{:game/id :game-1
                          :game/turn 1
                          :game/phase :main1
                          :game/active-player player-eid
                          :game/priority player-eid}]))
    @conn))


(defn add-cards-to-library
  "Add multiple cards to the library with positions.
   Returns [db object-ids] tuple with object-ids in order (first = top of library)."
  [db card-ids player-id]
  (let [conn (d/conn-from-db db)
        player-eid (q/get-player-eid db player-id)
        get-card-eid (fn [card-id]
                       (d/q '[:find ?e .
                              :in $ ?cid
                              :where [?e :card/id ?cid]]
                            @conn card-id))]
    (loop [remaining-cards card-ids
           position 0
           object-ids []]
      (if (empty? remaining-cards)
        [@conn object-ids]
        (let [card-id (first remaining-cards)
              obj-id (random-uuid)
              card-eid (get-card-eid card-id)]
          (d/transact! conn [{:object/id obj-id
                              :object/card card-eid
                              :object/zone :library
                              :object/owner player-eid
                              :object/controller player-eid
                              :object/tapped false
                              :object/position position}])
          (recur (rest remaining-cards)
                 (inc position)
                 (conj object-ids obj-id)))))))


(defn get-object-zone
  "Get the current zone of an object by its ID."
  [db object-id]
  (:object/zone (q/get-object db object-id)))


(defn get-library-count
  "Get the number of cards in a player's library."
  [db player-id]
  (count (q/get-objects-in-zone db player-id :library)))


(defn get-hand-count
  "Get the number of cards in a player's hand."
  [db player-id]
  (count (q/get-hand db player-id)))


(defn get-graveyard-count
  "Get the number of cards in a player's graveyard."
  [db player-id]
  (count (q/get-objects-in-zone db player-id :graveyard)))


;; === build-pile-choice-selection tests ===

(deftest test-build-pile-choice-selection-creates-correct-state
  ;; Bug caught: Missing required fields in pile-choice selection state
  (testing "build-pile-choice-selection creates selection with all required fields"
    (let [id1 (random-uuid)
          id2 (random-uuid)
          id3 (random-uuid)
          card-ids #{id1 id2 id3}
          pile-choice {:hand 1 :graveyard :rest}
          player-id :player-1
          spell-id (random-uuid)
          remaining-effects []
          result (library/build-pile-choice-selection card-ids pile-choice player-id spell-id remaining-effects)]
      (is (= :pile-choice (:selection/type result))
          "Selection type must be :pile-choice")
      (is (= card-ids (:selection/candidates result))
          "Candidates must be the card IDs passed in")
      (is (= 1 (:selection/hand-count result))
          "Hand count must match pile-choice :hand value")
      (is (= player-id (:selection/player-id result))
          "Player ID must be set")
      (is (= spell-id (:selection/spell-id result))
          "Spell ID must be set")
      (is (= true (:selection/allow-random result))
          "Random selection must be allowed")
      (is (= #{} (:selection/selected result))
          "Initial selection must be empty (no auto-select for 3 cards)"))))


(deftest test-build-pile-choice-selection-auto-selects-single-card
  ;; Bug caught: Single card not auto-selected, user must manually click
  (testing "Single card is auto-selected in pile-choice"
    (let [single-id (random-uuid)
          card-ids #{single-id}
          pile-choice {:hand 1 :graveyard :rest}
          result (library/build-pile-choice-selection card-ids pile-choice :player-1 (random-uuid) [])]
      (is (= #{single-id} (:selection/selected result))
          "Single card must be auto-selected")
      (is (= :pile-choice (:selection/type result))
          "Still shows pile-choice UI for consistency"))))


(deftest test-build-pile-choice-selection-preserves-remaining-effects
  ;; Bug caught: Remaining effects lost during pile-choice phase
  (testing "Remaining effects are preserved in pile-choice selection"
    (let [remaining [{:effect/type :shuffle}]
          result (library/build-pile-choice-selection
                   #{(random-uuid)} {:hand 1} :player-1 (random-uuid) remaining)]
      (is (= remaining (:selection/remaining-effects result))
          "Remaining effects must be preserved"))))


;; === execute-pile-choice tests ===

(deftest test-execute-pile-choice-moves-selected-to-hand
  ;; Bug caught: Selected cards not moved to hand
  (testing "execute-pile-choice moves hand-selected cards to hand zone"
    (let [db (create-test-db)
          [db' [obj1 obj2 obj3]] (add-cards-to-library
                                   db
                                   [:dark-ritual :cabal-ritual :brain-freeze]
                                   :player-1)
          selection {:selection/type :pile-choice
                     :selection/candidates #{obj1 obj2 obj3}
                     :selection/selected #{obj1}  ; obj1 goes to hand
                     :selection/hand-count 1
                     :selection/player-id :player-1}
          db-after (library/execute-pile-choice db' selection)]
      (is (= :hand (get-object-zone db-after obj1))
          "Selected card must be in hand"))))


(deftest test-execute-pile-choice-moves-rest-to-graveyard
  ;; Bug caught: Non-selected cards left in library instead of graveyard
  (testing "execute-pile-choice moves non-selected cards to graveyard"
    (let [db (create-test-db)
          [db' [obj1 obj2 obj3]] (add-cards-to-library
                                   db
                                   [:dark-ritual :cabal-ritual :brain-freeze]
                                   :player-1)
          selection {:selection/type :pile-choice
                     :selection/candidates #{obj1 obj2 obj3}
                     :selection/selected #{obj1}  ; obj1 goes to hand
                     :selection/hand-count 1
                     :selection/player-id :player-1}
          db-after (library/execute-pile-choice db' selection)]
      (is (= :graveyard (get-object-zone db-after obj2))
          "Non-selected card 2 must be in graveyard")
      (is (= :graveyard (get-object-zone db-after obj3))
          "Non-selected card 3 must be in graveyard"))))


(deftest test-execute-pile-choice-does-not-shuffle
  ;; Bug caught: Library shuffled during pile-choice (should only shuffle on confirm)
  (testing "execute-pile-choice does NOT shuffle library (shuffle is separate)"
    (let [db (create-test-db)
          ;; Add cards to library beyond the pile-choice cards
          [db' [obj1 obj2 obj3 obj4 obj5]] (add-cards-to-library
                                             db
                                             [:dark-ritual :cabal-ritual :brain-freeze
                                              :careful-study :mental-note]
                                             :player-1)
          ;; Get original positions of remaining cards
          orig-pos4 (:object/position (q/get-object db' obj4))
          orig-pos5 (:object/position (q/get-object db' obj5))
          selection {:selection/type :pile-choice
                     :selection/candidates #{obj1 obj2 obj3}
                     :selection/selected #{obj1}
                     :selection/hand-count 1
                     :selection/player-id :player-1}
          db-after (library/execute-pile-choice db' selection)
          ;; Check positions preserved (no shuffle)
          new-pos4 (:object/position (q/get-object db-after obj4))
          new-pos5 (:object/position (q/get-object db-after obj5))]
      (is (= orig-pos4 new-pos4)
          "Remaining library cards should not be shuffled")
      (is (= orig-pos5 new-pos5)
          "Remaining library cards should not be shuffled"))))


(deftest test-execute-pile-choice-all-to-hand-when-count-matches
  ;; Bug caught: Edge case where hand-count >= candidate count
  (testing "All cards go to hand when hand-count >= candidate count"
    (let [db (create-test-db)
          [db' [obj1]] (add-cards-to-library db [:dark-ritual] :player-1)
          selection {:selection/type :pile-choice
                     :selection/candidates #{obj1}
                     :selection/selected #{obj1}
                     :selection/hand-count 1
                     :selection/player-id :player-1}
          db-after (library/execute-pile-choice db' selection)]
      (is (= :hand (get-object-zone db-after obj1))
          "Single card should go to hand")
      (is (= 0 (get-graveyard-count db-after :player-1))
          "No cards should be in graveyard"))))


;; === confirm-tutor-selection chaining tests ===

(deftest test-build-tutor-selection-includes-pile-choice
  ;; Bug caught: pile-choice not passed through from effect
  (testing "build-tutor-selection includes :selection/pile-choice when effect has it"
    (let [db (create-test-db)
          [db' _] (add-cards-to-library
                    db
                    [:dark-ritual :cabal-ritual :brain-freeze]
                    :player-1)
          effect {:effect/type :tutor
                  :effect/select-count 3
                  :effect/target-zone :hand
                  :effect/pile-choice {:hand 1 :graveyard :rest}}
          result (library/build-tutor-selection db' :player-1 (random-uuid) effect [])]
      (is (= {:hand 1 :graveyard :rest} (:selection/pile-choice result))
          "pile-choice config must be in selection state"))))


(deftest test-build-tutor-selection-no-pile-choice-when-not-present
  ;; Bug caught: nil pile-choice breaks normal tutors
  (testing "build-tutor-selection has nil pile-choice for normal tutors"
    (let [db (create-test-db)
          [db' _] (add-cards-to-library db [:dark-ritual] :player-1)
          effect {:effect/type :tutor
                  :effect/target-zone :hand}
          result (library/build-tutor-selection db' :player-1 (random-uuid) effect [])]
      (is (nil? (:selection/pile-choice result))
          "Normal tutors should not have pile-choice"))))


;; === select-random-pile-choice tests ===

(deftest test-select-random-picks-correct-count
  ;; Bug caught: Random picks wrong number of cards
  (testing "select-random-pile-choice picks exactly hand-count cards"
    (let [id1 (random-uuid)
          id2 (random-uuid)
          id3 (random-uuid)
          app-db {:game/pending-selection
                  {:selection/type :pile-choice
                   :selection/candidates #{id1 id2 id3}
                   :selection/hand-count 1
                   :selection/selected #{}}}
          ;; Run multiple times to verify count is always correct
          results (repeatedly 10 #(library/select-random-pile-choice app-db))]
      (doseq [result results]
        (let [selected (get-in result [:game/pending-selection :selection/selected])]
          (is (= 1 (count selected))
              "Random must select exactly 1 card (hand-count)")
          (is (every? #(contains? #{id1 id2 id3} %) selected)
              "Selected card must be from candidates"))))))


(deftest test-select-random-does-not-auto-confirm
  ;; Bug caught: Random auto-confirms, user can't review
  (testing "select-random-pile-choice updates selection but does not confirm"
    (let [id1 (random-uuid)
          id2 (random-uuid)
          id3 (random-uuid)
          app-db {:game/pending-selection
                  {:selection/type :pile-choice
                   :selection/candidates #{id1 id2 id3}
                   :selection/hand-count 1
                   :selection/selected #{}}}
          result (library/select-random-pile-choice app-db)]
      (is (= :pile-choice (get-in result [:game/pending-selection :selection/type]))
          "Selection type must still be pile-choice"))))


;; === Edge case tests ===

(deftest test-pile-choice-fail-to-find
  ;; Bug caught: Crash when 0 cards selected from tutor (fail-to-find)
  (testing "Empty selection from tutor skips pile-choice phase"
    ;; When fail-to-find on tutor, there are 0 cards for pile-choice
    ;; Should skip pile-choice entirely and just shuffle
    (let [empty-candidates #{}
          pile-choice {:hand 1 :graveyard :rest}
          result (library/build-pile-choice-selection empty-candidates pile-choice :player-1 (random-uuid) [])]
      ;; Even with 0 candidates, function should return valid selection
      ;; But caller (confirm-tutor-selection) should skip pile-choice when empty
      (is (= #{} (:selection/candidates result))
          "Empty candidates produces empty selection")
      (is (= #{} (:selection/selected result))
          "Auto-select is empty when no candidates"))))
