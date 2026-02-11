(ns fizzle.cards.opt-test
  "Tests for Opt with scry effect.

   Opt: U - Instant
   Scry 1, then draw a card.

   Tests verify:
   - Card definition (type, cost, effects)
   - Scry selection state created
   - Remaining draw effect preserved
   - Full resolution (scry confirm triggers draw)"
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [datascript.core :as d]
    [fizzle.cards.iggy-pop :as cards]
    [fizzle.db.queries :as q]
    [fizzle.db.schema :refer [schema]]
    [fizzle.engine.rules :as rules]
    [fizzle.events.selection :as selection]
    [fizzle.events.selection.resolution :as resolution]))


;; === Test helpers ===

(defn create-test-db
  "Create a game state with all card definitions loaded and blue mana available."
  []
  (let [conn (d/create-conn schema)]
    ;; Transact all card definitions
    (d/transact! conn cards/all-cards)
    ;; Transact player with blue mana for Opt
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


(defn get-object-zone
  "Get the current zone of an object by its ID."
  [db object-id]
  (:object/zone (q/get-object db object-id)))


;; === Card Definition Tests ===

(deftest test-opt-card-definition
  (testing "Opt type and cost"
    (is (= #{:instant} (:card/types cards/opt))
        "Opt should be an instant")
    (is (= {:blue 1} (:card/mana-cost cards/opt))
        "Opt should cost {U}"))

  (testing "Opt has scry 1 first, then draw 1"
    (let [effects (:card/effects cards/opt)]
      (is (= 2 (count effects))
          "Opt should have 2 effects")
      (let [scry-effect (first effects)
            draw-effect (second effects)]
        (is (= :scry (:effect/type scry-effect))
            "First effect should be :scry")
        (is (= 1 (:effect/amount scry-effect))
            "Scry effect should scry 1")
        (is (= :draw (:effect/type draw-effect))
            "Second effect should be :draw")
        (is (= 1 (:effect/amount draw-effect))
            "Draw effect should draw 1")))))


;; === Scry Selection Tests ===

(deftest test-opt-creates-scry-selection-state
  ;; Bug caught: Scry effect not triggering selection
  (testing "Casting and resolving Opt creates scry selection state"
    (let [db (create-test-db)
          ;; Add cards to library for scrying
          [db' _lib-ids] (add-cards-to-library db
                                               [:dark-ritual :cabal-ritual :brain-freeze]
                                               :player-1)
          ;; Add Opt to hand
          [db'' opt-id] (add-card-to-zone db' :opt :hand :player-1)
          _ (is (= 1 (get-hand-count db'' :player-1)) "Precondition: hand has 1 card (Opt)")
          ;; Cast Opt
          db-after-cast (rules/cast-spell db'' :player-1 opt-id)
          _ (is (= :stack (get-object-zone db-after-cast opt-id))
                "Opt should be on stack after casting")
          ;; Resolve spell - should create scry selection
          result (resolution/resolve-spell-with-selection db-after-cast :player-1 opt-id)]
      ;; Selection type should be :scry
      (is (= :scry (get-in result [:pending-selection :selection/type]))
          "Selection type should be :scry")
      ;; Should have 1 card to scry
      (is (= 1 (count (get-in result [:pending-selection :selection/cards])))
          "Scry selection should have 1 card"))))


(deftest test-opt-scry-preserves-draw-effect
  ;; Bug caught: Draw effect lost during selection chaining
  (testing "Scry selection state preserves draw as remaining effect"
    (let [db (create-test-db)
          ;; Add cards to library for scrying
          [db' _lib-ids] (add-cards-to-library db
                                               [:dark-ritual :cabal-ritual :brain-freeze]
                                               :player-1)
          ;; Add Opt to hand
          [db'' opt-id] (add-card-to-zone db' :opt :hand :player-1)
          ;; Cast and resolve Opt
          db-after-cast (rules/cast-spell db'' :player-1 opt-id)
          result (resolution/resolve-spell-with-selection db-after-cast :player-1 opt-id)
          remaining-effects (get-in result [:pending-selection :selection/remaining-effects])]
      ;; Should have 1 remaining effect (draw)
      (is (= 1 (count remaining-effects))
          "Should have 1 remaining effect")
      ;; Remaining effect should be draw 1
      (is (= :draw (:effect/type (first remaining-effects)))
          "Remaining effect should be :draw")
      (is (= 1 (:effect/amount (first remaining-effects)))
          "Remaining draw should draw 1"))))


(deftest test-opt-full-resolution-draws-card
  ;; Bug caught: Draw not executing after scry confirm
  (testing "After scry confirm, draw effect executes"
    (let [db (create-test-db)
          ;; Add 3 cards to library
          [db' _lib-ids] (add-cards-to-library db
                                               [:dark-ritual :cabal-ritual :brain-freeze]
                                               :player-1)
          ;; Add Opt to hand
          [db'' opt-id] (add-card-to-zone db' :opt :hand :player-1)
          initial-hand (get-hand-count db'' :player-1)
          _ (is (= 1 initial-hand) "Precondition: hand has 1 card (Opt)")
          ;; Cast Opt
          db-after-cast (rules/cast-spell db'' :player-1 opt-id)
          ;; Resolve spell - creates scry selection
          result (resolution/resolve-spell-with-selection db-after-cast :player-1 opt-id)
          scry-selection (:pending-selection result)
          top-card (first (:selection/cards scry-selection))
          ;; Simulate scry choice: put card on bottom
          db-before-confirm (:db result)
          scry-state (assoc scry-selection
                            :selection/top-pile []
                            :selection/bottom-pile [top-card])
          ;; Create app-db with pending selection for confirm-scry-selection
          app-db {:game/db db-before-confirm
                  :game/pending-selection scry-state}
          ;; Confirm scry selection - should trigger draw
          result-app-db (selection/confirm-scry-selection app-db)
          result-db (:game/db result-app-db)]
      ;; After scry + draw, hand should have 1 card (drew the top card after reorder)
      (is (= 1 (get-hand-count result-db :player-1))
          "After scry confirm and draw, hand should have 1 card")
      ;; Should NOT have pending selection (draw is automatic, not selection-based)
      (is (nil? (:game/pending-selection result-app-db))
          "Should not have pending selection after draw"))))


(deftest test-opt-empty-library-scry-still-draws-attempt
  ;; Bug caught: Empty library crash or skip draw
  (testing "Empty library: scry returns nil, draw still attempts"
    (let [db (create-test-db)
          ;; NO library cards - empty library
          ;; Add Opt to hand
          [db' opt-id] (add-card-to-zone db :opt :hand :player-1)
          library-count (count (q/get-objects-in-zone db' :player-1 :library))
          _ (is (= 0 library-count) "Precondition: library is empty")
          ;; Cast Opt
          db-after-cast (rules/cast-spell db' :player-1 opt-id)
          ;; Resolve spell - scry with empty library
          result (resolution/resolve-spell-with-selection db-after-cast :player-1 opt-id)]
      ;; With empty library, scry selection is nil (no cards to scry)
      ;; But the effect should handle this gracefully
      ;; If no selection needed, spell resolves directly including draw
      (if (nil? (:pending-selection result))
        ;; Direct resolution path - draw from empty library = 0 cards
        (is (= 0 (get-hand-count (:db result) :player-1))
            "With empty library, no cards drawn")
        ;; If somehow selection was created, it should be empty
        (is (= 0 (count (get-in result [:pending-selection :selection/cards])))
            "Scry selection should have 0 cards for empty library")))))
