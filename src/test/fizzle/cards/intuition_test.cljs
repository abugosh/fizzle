(ns fizzle.cards.intuition-test
  "Tests for Intuition card definition.

   Intuition: 2U - Instant
   Search your library for three cards and reveal them. Target opponent
   chooses one. Put that card into your hand and the rest into your graveyard.
   Then shuffle.

   This tests:
   - Intuition card exists in all-cards
   - Correct mana cost and attributes
   - Effect structure with :effect/select-count 3 and :effect/pile-choice
   - Integration with multi-select tutor and pile choice systems"
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [datascript.core :as d]
    [fizzle.cards.iggy-pop :as cards]
    [fizzle.db.queries :as q]
    [fizzle.db.schema :refer [schema]]
    [fizzle.engine.rules :as rules]
    [fizzle.events.selection :as selection]))


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


;; === Tests ===

(deftest test-intuition-card-exists
  ;; Bug caught: Card not added to all-cards vector, won't be loaded
  (testing "Intuition is in all-cards"
    (let [intuition-card (some #(when (= "Intuition" (:card/name %)) %)
                               cards/all-cards)]
      (is (= :intuition (:card/id intuition-card))
          "Card ID must be :intuition"))))


(deftest test-intuition-mana-cost
  ;; Bug caught: Wrong mana cost prevents casting
  (testing "Intuition has correct mana cost 2U"
    (let [intuition-card (some #(when (= :intuition (:card/id %)) %)
                               cards/all-cards)]
      (is (= {:colorless 2 :blue 1} (:card/mana-cost intuition-card))
          "Mana cost must be {2}{U}")
      (is (= 3 (:card/cmc intuition-card))
          "CMC must be 3")
      (is (= #{:blue} (:card/colors intuition-card))
          "Colors must be #{:blue}"))))


(deftest test-intuition-effect-structure
  ;; Bug caught: Missing parameters cause tutor/pile-choice to fail
  (testing "Intuition effect has required tutor parameters"
    (let [intuition-card (some #(when (= :intuition (:card/id %)) %)
                               cards/all-cards)
          effect (first (:card/effects intuition-card))]
      (is (= :tutor (:effect/type effect))
          "Effect type must be :tutor")
      (is (= 3 (:effect/select-count effect))
          "select-count must be 3")
      (is (= :hand (:effect/target-zone effect))
          "target-zone must be :hand")
      (is (= {:hand 1 :graveyard :rest} (:effect/pile-choice effect))
          "pile-choice must specify 1 to hand, rest to graveyard"))))


(deftest test-intuition-full-flow-integration
  ;; Bug caught: Card definition doesn't work with selection system
  (testing "Intuition triggers pile choice after tutor selection"
    (let [db (create-test-db)
          [db' _card-ids] (add-cards-to-library db
                                                [:dark-ritual :cabal-ritual :brain-freeze
                                                 :careful-study :mental-note]
                                                :player-1)
          intuition-card (some #(when (= :intuition (:card/id %)) %)
                               cards/all-cards)
          intuition-effect (first (:card/effects intuition-card))
          selection (selection/build-tutor-selection db' :player-1 (random-uuid)
                                                     intuition-effect [])]
      ;; Verify multi-select tutor
      (is (= 3 (:selection/select-count selection))
          "Intuition must require 3 cards")
      (is (= {:hand 1 :graveyard :rest} (:selection/pile-choice selection))
          "Selection must include pile-choice for second phase"))))


(defn add-card-to-zone
  "Add a card object to a zone for a player.
   Returns [db object-id] tuple."
  [db card-id zone player-id]
  (let [conn (d/conn-from-db db)
        player-eid (q/get-player-eid db player-id)
        card-eid (d/q '[:find ?e .
                        :in $ ?cid
                        :where [?e :card/id ?cid]]
                      db card-id)
        obj-id (random-uuid)]
    (d/transact! conn [{:object/id obj-id
                        :object/card card-eid
                        :object/zone zone
                        :object/owner player-eid
                        :object/controller player-eid
                        :object/tapped false}])
    [@conn obj-id]))


(deftest test-intuition-with-fewer-than-3-cards-in-library
  ;; Bug caught: crash or wrong behavior with insufficient cards
  (testing "Intuition with only 2 cards in library creates tutor with 2 candidates"
    (let [db (create-test-db)
          ;; Add only 2 cards to library (fewer than Intuition's select-count of 3)
          [db' _card-ids] (add-cards-to-library db [:dark-ritual :cabal-ritual] :player-1)
          ;; Add Intuition to hand
          [db'' int-id] (add-card-to-zone db' :intuition :hand :player-1)
          ;; Cast Intuition (already has 3 blue mana from create-test-db)
          db-cast (rules/cast-spell db'' :player-1 int-id)
          _ (is (= :stack (:object/zone (q/get-object db-cast int-id)))
                "Precondition: Intuition on stack")
          ;; Resolve - should create tutor selection with available cards
          result (selection/resolve-spell-with-selection db-cast :player-1 int-id)
          sel (:pending-selection result)]
      ;; Should have pending selection (tutor)
      (is (= :tutor (:selection/type sel))
          "Selection type should be :tutor")
      ;; With only 2 cards, can only find up to 2
      (is (<= (count (:selection/candidate-ids sel)) 2)
          "Should have at most 2 candidates (only 2 cards in library)"))))
