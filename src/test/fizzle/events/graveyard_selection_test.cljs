(ns fizzle.events.graveyard-selection-test
  "Tests for graveyard selection state creation and confirmation in game.cljs.

   When a spell with :return-from-graveyard effect and :selection :player resolves,
   the selection system creates :selection/type :graveyard-return state for the UI.

   Tests verify:
   - build-graveyard-selection creates correct selection state structure
   - Edge cases: empty graveyard, target resolution (:self/:opponent)
   - confirm-graveyard-selection validates and moves cards correctly
   - Confirm allows 0 to max-count selection (not exact count required)"
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [datascript.core :as d]
    [fizzle.db.init :refer [init-game-state]]
    [fizzle.db.queries :as q]
    [fizzle.events.selection :as selection]))


;; === Test helpers ===

(defn add-opponent
  "Add an opponent player to the game state."
  [db]
  (let [conn (d/conn-from-db db)]
    (d/transact! conn [{:player/id :player-2
                        :player/name "Opponent"
                        :player/life 20
                        :player/mana-pool {:white 0 :blue 0 :black 0 :red 0 :green 0 :colorless 0}
                        :player/storm-count 0
                        :player/land-plays-left 1
                        :player/is-opponent true}])
    @conn))


(defn add-graveyard-cards
  "Add cards to a player's graveyard.
   Takes a count of cards to add (all referencing Dark Ritual card def).
   Returns [db added-object-ids] tuple."
  [db player-id n]
  (let [conn (d/conn-from-db db)
        player-eid (q/get-player-eid db player-id)
        object-ids (atom [])]
    (doseq [_ (range n)]
      (let [card-eid (d/q '[:find ?e .
                            :where [?e :card/id :dark-ritual]]
                          @conn)
            obj-id (random-uuid)]
        (d/transact! conn [{:object/id obj-id
                            :object/card card-eid
                            :object/zone :graveyard
                            :object/owner player-eid
                            :object/controller player-eid
                            :object/tapped false}])
        (swap! object-ids conj obj-id)))
    [@conn @object-ids]))


(defn count-zone
  "Count objects in a zone for a player."
  [db player-id zone]
  (count (q/get-objects-in-zone db player-id zone)))


;; === build-graveyard-selection tests ===

(deftest test-build-graveyard-selection-creates-correct-state
  ;; Bug caught: Selection state missing required fields
  (testing "build-graveyard-selection creates correct selection state structure"
    (let [[db gy-ids] (add-graveyard-cards (init-game-state) :player-1 3)
          effect {:effect/type :return-from-graveyard
                  :effect/count 3
                  :effect/selection :player}
          spell-id (random-uuid)
          remaining-effects [{:effect/type :draw :effect/amount 1}]
          result (selection/build-graveyard-selection db :player-1 spell-id effect remaining-effects)]
      ;; Selection type must be :graveyard-return (distinct from :tutor, :discard)
      (is (= :graveyard-return (:selection/type result))
          "Selection type must be :graveyard-return")
      ;; Zone should be :graveyard
      (is (= :graveyard (:selection/zone result))
          "Selection zone must be :graveyard")
      ;; Count should match effect
      (is (= 3 (:selection/select-count result))
          "Selection count must match effect count")
      ;; Min count should be 0 (can select fewer)
      (is (= 0 (:selection/min-count result))
          "Min count must be 0 (up to N, not exact N)")
      ;; Player ID should be set
      (is (= :player-1 (:selection/player-id result))
          "Player ID must be set")
      ;; Candidate IDs should contain graveyard cards
      (is (= (set gy-ids) (:selection/candidate-ids result))
          "Candidate IDs must contain graveyard card IDs")
      ;; Remaining effects preserved
      (is (= remaining-effects (:selection/remaining-effects result))
          "Remaining effects must be preserved")
      ;; Selected starts empty
      (is (= #{} (:selection/selected result))
          "Selected must start empty")
      ;; Spell ID preserved for cleanup
      (is (= spell-id (:selection/spell-id result))
          "Spell ID must be preserved"))))


(deftest test-build-graveyard-selection-empty-graveyard
  ;; Bug caught: Nil pointer or crash when graveyard empty
  (testing "build-graveyard-selection with empty graveyard returns valid state"
    (let [db (init-game-state) ; no graveyard cards
          effect {:effect/type :return-from-graveyard
                  :effect/count 3
                  :effect/selection :player}
          result (selection/build-graveyard-selection db :player-1 (random-uuid) effect [])]
      ;; Should return valid state, not nil or crash
      (is (some? result)
          "Should return valid state, not nil")
      ;; Candidate IDs should be empty set (not nil)
      (is (= #{} (:selection/candidate-ids result))
          "Candidate IDs must be empty set, not nil")
      ;; Selection type still set correctly
      (is (= :graveyard-return (:selection/type result))
          "Selection type must be :graveyard-return"))))


(deftest test-build-graveyard-selection-resolves-opponent-target
  ;; Bug caught: :opponent keyword not resolved to actual player-id
  (testing "build-graveyard-selection resolves :opponent target correctly"
    (let [db (-> (init-game-state)
                 (add-opponent))
          [db opp-ids] (add-graveyard-cards db :player-2 4)
          effect {:effect/type :return-from-graveyard
                  :effect/count 2
                  :effect/selection :player
                  :effect/target :opponent}
          ;; Player-1 casts, targets opponent
          result (selection/build-graveyard-selection db :player-1 (random-uuid) effect [])]
      ;; Player ID should be opponent's player-id, not :opponent keyword
      (is (= :player-2 (:selection/player-id result))
          "Player ID must be resolved to :player-2, not :opponent keyword")
      ;; Candidate IDs should be opponent's graveyard cards
      (is (= (set opp-ids) (:selection/candidate-ids result))
          "Candidate IDs must be opponent's graveyard cards"))))


(deftest test-build-graveyard-selection-resolves-self-target
  ;; Bug caught: :self keyword passed directly to queries
  (testing "build-graveyard-selection resolves :self target correctly"
    (let [[db gy-ids] (add-graveyard-cards (init-game-state) :player-1 2)
          effect {:effect/type :return-from-graveyard
                  :effect/count 2
                  :effect/selection :player
                  :effect/target :self}
          result (selection/build-graveyard-selection db :player-1 (random-uuid) effect [])]
      ;; Player ID should be caster's player-id, not :self keyword
      (is (= :player-1 (:selection/player-id result))
          "Player ID must be resolved to :player-1, not :self keyword")
      ;; Candidate IDs should be caster's graveyard cards
      (is (= (set gy-ids) (:selection/candidate-ids result))
          "Candidate IDs must be caster's graveyard cards"))))


(deftest test-build-graveyard-selection-defaults-target-to-caster
  ;; Bug caught: Missing target causes crash or wrong player
  (testing "build-graveyard-selection defaults target to caster when not specified"
    (let [[db gy-ids] (add-graveyard-cards (init-game-state) :player-1 2)
          effect {:effect/type :return-from-graveyard
                  :effect/count 2
                  :effect/selection :player
                  ;; No :effect/target - should default to caster
                  }
          result (selection/build-graveyard-selection db :player-1 (random-uuid) effect [])]
      (is (= :player-1 (:selection/player-id result))
          "Player ID must default to caster when target not specified")
      (is (= (set gy-ids) (:selection/candidate-ids result))
          "Candidate IDs must be caster's graveyard cards"))))
