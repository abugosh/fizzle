(ns fizzle.cards.mental-note-test
  "Tests for Mental Note instant card.

   Mental Note: U - Instant
   Put the top two cards of your library into your graveyard, then draw a card.

   This tests:
   - Card definition (type, cost, effects)
   - Mill 2 from own library
   - Draw 1 card after milling
   - Effect ordering (mill first, then draw)"
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [datascript.core :as d]
    [fizzle.cards.iggy-pop :as cards]
    [fizzle.db.queries :as q]
    [fizzle.db.schema :refer [schema]]
    [fizzle.engine.effects :as effects]
    [fizzle.engine.rules :as rules]))


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
                        :player/mana-pool {:white 0 :blue 1 :black 0
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


(defn add-cards-to-library
  "Add multiple cards to the top of a player's library.
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


(defn get-hand-count
  "Get the number of cards in a player's hand."
  [db player-id]
  (count (q/get-hand db player-id)))


(defn get-library-count
  "Get the number of cards in a player's library."
  [db player-id]
  (count (q/get-objects-in-zone db player-id :library)))


(defn get-graveyard-count
  "Get the number of cards in a player's graveyard."
  [db player-id]
  (count (q/get-objects-in-zone db player-id :graveyard)))


(defn get-object-zone
  "Get the current zone of an object by its ID."
  [db object-id]
  (:object/zone (q/get-object db object-id)))


;; === Card Definition Tests ===

(deftest test-mental-note-is-instant-type
  (testing "Mental Note has :instant in types"
    (is (contains? (:card/types cards/mental-note) :instant)
        "Mental Note should be an instant")))


(deftest test-mental-note-costs-one-blue
  (testing "Mental Note costs one blue mana"
    (is (= {:blue 1} (:card/mana-cost cards/mental-note))
        "Mental Note should cost {U}")))


(deftest test-mental-note-has-mill-and-draw-effects
  (testing "Mental Note has mill 2 and draw 1 effects in correct order"
    (let [card-effects (:card/effects cards/mental-note)]
      (is (= 2 (count card-effects))
          "Mental Note should have 2 effects")
      (let [mill-effect (first card-effects)
            draw-effect (second card-effects)]
        (is (= :mill (:effect/type mill-effect))
            "First effect should be :mill")
        (is (= 2 (:effect/amount mill-effect))
            "Mill effect should mill 2")
        (is (= :draw (:effect/type draw-effect))
            "Second effect should be :draw")
        (is (= 1 (:effect/amount draw-effect))
            "Draw effect should draw 1")))))


;; === Mill Effect Tests ===

(deftest test-mental-note-mills-from-own-library
  (testing "Mental Note mills 2 cards from caster's own library"
    (let [db (create-test-db)
          [db' _lib-ids] (add-cards-to-library db
                                               [:dark-ritual :cabal-ritual :brain-freeze :island]
                                               :player-1)
          _ (is (= 4 (get-library-count db' :player-1)) "Precondition: 4 cards in library")
          _ (is (= 0 (get-graveyard-count db' :player-1)) "Precondition: graveyard empty")
          ;; Execute mill effect directly
          mill-effect {:effect/type :mill
                       :effect/amount 2}
          db-after-mill (effects/execute-effect db' :player-1 mill-effect)]
      (is (= 2 (get-library-count db-after-mill :player-1))
          "Library should have 2 cards remaining")
      (is (= 2 (get-graveyard-count db-after-mill :player-1))
          "Graveyard should have 2 cards"))))


(deftest test-mental-note-mills-top-cards
  (testing "Mental Note mills from top of library (lowest position)"
    (let [db (create-test-db)
          [db' lib-ids] (add-cards-to-library db
                                              [:dark-ritual :cabal-ritual :brain-freeze]
                                              :player-1)
          top-card-id (first lib-ids)
          second-card-id (second lib-ids)
          bottom-card-id (nth lib-ids 2)
          ;; Execute mill effect
          mill-effect {:effect/type :mill
                       :effect/amount 2}
          db-after-mill (effects/execute-effect db' :player-1 mill-effect)]
      ;; Top 2 cards should be in graveyard
      (is (= :graveyard (get-object-zone db-after-mill top-card-id))
          "Top card should be milled to graveyard")
      (is (= :graveyard (get-object-zone db-after-mill second-card-id))
          "Second card should be milled to graveyard")
      ;; Bottom card should still be in library
      (is (= :library (get-object-zone db-after-mill bottom-card-id))
          "Bottom card should remain in library"))))


;; === Draw Effect Tests ===

(deftest test-mental-note-draws-one-card
  (testing "Mental Note draws 1 card after milling"
    (let [db (create-test-db)
          [db' _lib-ids] (add-cards-to-library db
                                               [:dark-ritual :cabal-ritual :brain-freeze]
                                               :player-1)
          initial-hand-count (get-hand-count db' :player-1)
          _ (is (= 0 initial-hand-count) "Precondition: hand empty")
          ;; Execute draw effect directly
          draw-effect {:effect/type :draw
                       :effect/amount 1}
          db-after-draw (effects/execute-effect db' :player-1 draw-effect)]
      (is (= 1 (get-hand-count db-after-draw :player-1))
          "Hand should have 1 card after drawing"))))


;; === Combined Effect Tests ===

(deftest test-mental-note-full-resolution
  (testing "Mental Note mills 2, then draws 1 when cast and resolved"
    (let [db (create-test-db)
          ;; Add cards to library
          [db' _lib-ids] (add-cards-to-library db
                                               [:dark-ritual :cabal-ritual :brain-freeze :island :swamp]
                                               :player-1)
          ;; Add Mental Note to hand
          [db'' mn-id] (add-card-to-zone db' :mental-note :hand :player-1)
          _ (is (= 5 (get-library-count db'' :player-1)) "Precondition: 5 cards in library")
          _ (is (= 1 (get-hand-count db'' :player-1)) "Precondition: 1 card in hand (Mental Note)")
          _ (is (= 0 (get-graveyard-count db'' :player-1)) "Precondition: graveyard empty")
          ;; Cast Mental Note
          db-after-cast (rules/cast-spell db'' :player-1 mn-id)
          _ (is (= :stack (get-object-zone db-after-cast mn-id))
                "Mental Note should be on stack after casting")
          ;; Resolve spell
          db-after-resolve (rules/resolve-spell db-after-cast :player-1 mn-id)]
      ;; Mental Note goes to graveyard after resolution
      (is (= :graveyard (get-object-zone db-after-resolve mn-id))
          "Mental Note should be in graveyard after resolution")
      ;; 2 cards milled from library
      (is (= 2 (get-library-count db-after-resolve :player-1))
          "Library should have 2 cards (5 - 2 milled - 1 drawn)")
      ;; 1 card drawn to hand
      (is (= 1 (get-hand-count db-after-resolve :player-1))
          "Hand should have 1 card (drew 1 after Mental Note left)")
      ;; 3 cards in graveyard (2 milled + Mental Note itself)
      (is (= 3 (get-graveyard-count db-after-resolve :player-1))
          "Graveyard should have 3 cards (2 milled + Mental Note)"))))


;; === Edge Case Tests ===

(deftest test-mental-note-with-small-library
  (testing "Mental Note with only 1 card in library mills what's available, draws what remains"
    (let [db (create-test-db)
          ;; Only 2 cards in library (will mill both, then fail to draw)
          [db' _lib-ids] (add-cards-to-library db
                                               [:dark-ritual :cabal-ritual]
                                               :player-1)
          ;; Add Mental Note to hand
          [db'' mn-id] (add-card-to-zone db' :mental-note :hand :player-1)
          _ (is (= 2 (get-library-count db'' :player-1)) "Precondition: 2 cards in library")
          ;; Cast and resolve
          db-after-cast (rules/cast-spell db'' :player-1 mn-id)
          db-after-resolve (rules/resolve-spell db-after-cast :player-1 mn-id)]
      ;; Both cards milled
      (is (= 0 (get-library-count db-after-resolve :player-1))
          "Library should be empty after milling 2")
      ;; Graveyard has 2 milled + Mental Note
      (is (= 3 (get-graveyard-count db-after-resolve :player-1))
          "Graveyard should have 3 cards (2 milled + Mental Note)")
      ;; Draw from empty library should set loss condition
      (is (= :empty-library (:game/loss-condition (q/get-game-state db-after-resolve)))
          "Should set loss condition when drawing from empty library"))))


(deftest test-mental-note-contributes-to-threshold
  (testing "Mental Note adds 3 cards to graveyard (2 milled + itself)"
    ;; This is important for Iggy Pop: Mental Note helps reach threshold
    (let [db (create-test-db)
          [db' _lib-ids] (add-cards-to-library db
                                               [:dark-ritual :cabal-ritual :brain-freeze :island]
                                               :player-1)
          [db'' mn-id] (add-card-to-zone db' :mental-note :hand :player-1)
          _ (is (= 0 (get-graveyard-count db'' :player-1)) "Precondition: graveyard empty")
          db-after-cast (rules/cast-spell db'' :player-1 mn-id)
          db-after-resolve (rules/resolve-spell db-after-cast :player-1 mn-id)]
      (is (= 3 (get-graveyard-count db-after-resolve :player-1))
          "Mental Note should contribute 3 cards to threshold count"))))
