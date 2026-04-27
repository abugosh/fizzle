(ns fizzle.db.queries-test
  "Comprehensive tests for public query functions in db/queries.cljs.

   Tests use real Datascript databases (no mocks). Complements schema_test.cljs
   which covers: mana-pool nil, hand empty, get-card by id."
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [datascript.core :as d]
    [fizzle.db.init :refer [init-game-state]]
    [fizzle.db.queries :as q]
    [fizzle.db.schema :refer [schema]]
    [fizzle.engine.cards :as cards]
    [fizzle.engine.stack :as stack]
    [fizzle.test-helpers :as th]))


;; === Test helpers ===

(defn add-card-to-zone-with-position
  "Add a card object to a zone with a position. Returns [db object-id]."
  [db card-id zone player-id position]
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
                        :object/tapped false
                        :object/position position}])
    [@conn obj-id]))


;; === Player queries ===

(deftest get-player-eid-valid-player-test
  (testing "returns integer eid for existing player"
    (let [db (init-game-state)
          eid (q/get-player-eid db :player-1)]
      (is (integer? eid))
      (is (pos? eid)))))


(deftest get-player-eid-nonexistent-test
  (testing "returns nil for nonexistent player"
    (let [db (init-game-state)]
      (is (nil? (q/get-player-eid db :nonexistent))))))


(deftest get-mana-pool-valid-player-test
  (testing "returns mana pool map with all color keys for existing player"
    (let [db (init-game-state)
          pool (q/get-mana-pool db :player-1)]
      (is (map? pool))
      (is (= #{:white :blue :black :red :green :colorless} (set (keys pool))))
      (is (every? zero? (vals pool))))))


(deftest get-storm-count-initial-test
  (testing "returns updated storm count after transact"
    (let [db (init-game-state)
          conn (d/conn-from-db db)
          player-eid (q/get-player-eid db :player-1)]
      (d/transact! conn [{:db/id player-eid
                          :player/storm-count 5}])
      (is (= 5 (q/get-storm-count @conn :player-1))))))


(deftest get-storm-count-nonexistent-player-test
  (testing "returns nil for nonexistent player"
    (let [db (init-game-state)]
      (is (nil? (q/get-storm-count db :nonexistent))))))


(deftest get-life-total-initial-test
  (testing "returns 20 for initial player"
    (let [db (init-game-state)]
      (is (= 20 (q/get-life-total db :player-1))))))


(deftest get-life-total-nonexistent-player-test
  (testing "returns nil for nonexistent player"
    (let [db (init-game-state)]
      (is (nil? (q/get-life-total db :nonexistent))))))


(deftest get-max-hand-size-default-test
  (testing "returns 7 when max-hand-size attribute not set"
    (let [db (init-game-state)]
      (is (= 7 (q/get-max-hand-size db :player-1))))))


(deftest get-max-hand-size-custom-test
  (testing "returns custom value when set"
    (let [db (init-game-state)
          conn (d/conn-from-db db)
          player-eid (q/get-player-eid db :player-1)]
      (d/transact! conn [{:db/id player-eid
                          :player/max-hand-size 0}])
      (is (= 0 (q/get-max-hand-size @conn :player-1))))))


(deftest get-max-hand-size-nonexistent-player-test
  (testing "returns 7 (default) for nonexistent player"
    (let [db (init-game-state)]
      (is (= 7 (q/get-max-hand-size db :nonexistent))))))


(deftest get-opponent-id-with-opponent-test
  (testing "returns opponent player-id when opponent exists"
    (let [db (init-game-state)
          conn (d/conn-from-db db)]
      (d/transact! conn [{:player/id :player-2
                          :player/name "Opponent"
                          :player/life 20
                          :player/is-opponent true
                          :player/mana-pool {:white 0 :blue 0 :black 0
                                             :red 0 :green 0 :colorless 0}
                          :player/storm-count 0}])
      (is (= :player-2 (q/get-opponent-id @conn :player-1))))))


(deftest get-opponent-id-no-opponent-test
  (testing "returns nil when no opponent exists"
    (let [db (init-game-state)]
      (is (nil? (q/get-opponent-id db :player-1))))))


;; === Object queries ===

(deftest get-hand-with-cards-test
  (testing "returns objects with card data pulled in as maps"
    (let [db (init-game-state)
          hand (q/get-hand db :player-1)
          obj (first hand)]
      (is (= 1 (count hand)))
      (is (uuid? (:object/id obj)))
      (is (= :hand (:object/zone obj)))
      ;; Card should be pulled as a map, not integer ref
      (is (map? (:object/card obj)))
      (is (= "Dark Ritual" (get-in obj [:object/card :card/name]))))))


(deftest get-hand-multiple-cards-test
  (testing "returns all cards in hand"
    (let [db (th/create-test-db)
          [db' _] (th/add-card-to-zone db :dark-ritual :hand :player-1)
          [db'' _] (th/add-card-to-zone db' :brain-freeze :hand :player-1)
          hand (q/get-hand db'' :player-1)]
      (is (= 2 (count hand))))))


(deftest get-hand-nonexistent-player-test
  (testing "returns nil for nonexistent player"
    (let [db (init-game-state)]
      (is (nil? (q/get-hand db :nonexistent))))))


(deftest get-card-valid-object-test
  (testing "returns card definition map"
    (let [db (init-game-state)
          hand (q/get-hand db :player-1)
          obj-id (:object/id (first hand))
          card (q/get-card db obj-id)]
      (is (map? card))
      (is (= :dark-ritual (:card/id card)))
      (is (= 1 (:card/cmc card))))))


(deftest get-card-nonexistent-object-test
  (testing "returns nil for nonexistent object-id"
    (let [db (init-game-state)]
      (is (nil? (q/get-card db (random-uuid)))))))


(deftest get-object-valid-test
  (testing "returns full object with card data pulled in"
    (let [db (init-game-state)
          hand (q/get-hand db :player-1)
          obj-id (:object/id (first hand))
          obj (q/get-object db obj-id)]
      (is (map? obj))
      (is (= obj-id (:object/id obj)))
      (is (= :hand (:object/zone obj)))
      (is (false? (:object/tapped obj)))
      ;; Card should be pulled as map
      (is (map? (:object/card obj)))
      (is (= "Dark Ritual" (get-in obj [:object/card :card/name]))))))


(deftest get-object-nonexistent-test
  (testing "returns nil for nonexistent object-id"
    (let [db (init-game-state)]
      (is (nil? (q/get-object db (random-uuid)))))))


(deftest get-objects-in-zone-hand-test
  (testing "returns vector of objects in specified zone"
    (let [db (init-game-state)
          objs (q/get-objects-in-zone db :player-1 :hand)]
      (is (vector? objs))
      (is (= 1 (count objs)))
      (is (= "Dark Ritual" (get-in (first objs) [:object/card :card/name]))))))


(deftest get-objects-in-zone-empty-zone-test
  (testing "returns empty vector for zone with no objects"
    (let [db (init-game-state)]
      (is (= [] (q/get-objects-in-zone db :player-1 :graveyard))))))


(deftest get-objects-in-zone-nonexistent-player-test
  (testing "returns nil for nonexistent player"
    (let [db (init-game-state)]
      (is (nil? (q/get-objects-in-zone db :nonexistent :hand))))))


;; === Game state queries ===

(deftest get-game-state-test
  (testing "returns game state with turn, phase, and pulled active-player"
    (let [db (init-game-state)
          gs (q/get-game-state db)]
      (is (map? gs))
      (is (= :game-1 (:game/id gs)))
      (is (= 1 (:game/turn gs)))
      (is (= :main1 (:game/phase gs)))
      ;; active-player should be a pulled map, not an integer ref
      (let [ap (:game/active-player gs)]
        (is (map? ap) "active-player should be a pulled map, not integer ref")
        (is (= :player-1 (:player/id ap)))
        (is (= "Player" (:player/name ap)))))))


;; === Stack queries ===

(deftest stack-empty?-empty-test
  (testing "true when no triggers and no spells on stack"
    (let [db (init-game-state)]
      (is (true? (q/stack-empty? db))))))


(deftest stack-empty?-stack-items-only-test
  (testing "false when only stack-items on stack"
    (let [db (th/create-test-db)
          db' (stack/create-stack-item db {:stack-item/type :declare-attackers
                                           :stack-item/controller :player-1})]
      (is (false? (q/stack-empty? db'))))))


(deftest stack-empty?-both-test
  (testing "false when both stack-items and spells on stack"
    (let [db (th/create-test-db)
          [db' _] (th/add-card-to-zone db :dark-ritual :stack :player-1)
          db'' (stack/create-stack-item db' {:stack-item/type :declare-attackers
                                             :stack-item/controller :player-1})]
      (is (false? (q/stack-empty? db''))))))


;; === Library queries ===

(deftest get-top-n-library-basic-test
  (testing "returns top N object-ids sorted by position ascending"
    (let [db (th/create-test-db)
          [db' id0] (add-card-to-zone-with-position db :dark-ritual :library :player-1 0)
          [db'' id1] (add-card-to-zone-with-position db' :brain-freeze :library :player-1 1)
          [db''' _] (add-card-to-zone-with-position db'' :cabal-ritual :library :player-1 2)
          result (q/get-top-n-library db''' :player-1 2)]
      (is (= 2 (count result)))
      ;; position 0 should be first (top of library)
      (is (= id0 (first result)))
      (is (= id1 (second result))))))


(deftest get-top-n-library-n-zero-test
  (testing "returns empty vector when n=0"
    (let [db (th/create-test-db)
          [db' _] (add-card-to-zone-with-position db :dark-ritual :library :player-1 0)]
      (is (= [] (q/get-top-n-library db' :player-1 0))))))


(deftest get-top-n-library-n-exceeds-size-test
  (testing "returns all available when n > library size"
    (let [db (th/create-test-db)
          [db' _] (add-card-to-zone-with-position db :dark-ritual :library :player-1 0)
          [db'' _] (add-card-to-zone-with-position db' :brain-freeze :library :player-1 1)
          result (q/get-top-n-library db'' :player-1 100)]
      (is (= 2 (count result))))))


(deftest get-top-n-library-nonexistent-player-test
  (testing "returns nil for nonexistent player"
    (let [db (init-game-state)]
      (is (nil? (q/get-top-n-library db :nonexistent 5))))))


(deftest get-top-n-library-empty-library-test
  (testing "returns empty vector when library is empty"
    (let [db (init-game-state)]
      (is (= [] (q/get-top-n-library db :player-1 3))))))


;; === Criteria queries ===

(deftest query-zone-by-criteria-type-filter-test
  (testing "types filter objects - single type matches only that type"
    (let [db (th/create-test-db)
          ;; Add an instant (Dark Ritual) and a land (Island) to hand
          [db' _] (th/add-card-to-zone db :dark-ritual :hand :player-1)
          [db'' _] (th/add-card-to-zone db' :island :hand :player-1)
          ;; Query for instants only
          instants (q/query-zone-by-criteria db'' :player-1 :hand {:match/types #{:instant}})
          ;; Query for lands only
          lands (q/query-zone-by-criteria db'' :player-1 :hand {:match/types #{:land}})]
      (is (= 1 (count instants)))
      (is (= "Dark Ritual" (get-in (first instants) [:object/card :card/name])))
      (is (= 1 (count lands)))
      (is (= "Island" (get-in (first lands) [:object/card :card/name]))))))


(deftest query-zone-by-criteria-color-or-logic-test
  (testing "colors use OR logic - any match suffices"
    (let [db (th/create-test-db)
          ;; Dark Ritual is :black, Brain Freeze is :blue
          [db' _] (th/add-card-to-zone db :dark-ritual :hand :player-1)
          [db'' _] (th/add-card-to-zone db' :brain-freeze :hand :player-1)
          ;; Query for black OR blue - should get both
          result (q/query-zone-by-criteria db'' :player-1 :hand {:match/colors #{:black :blue}})]
      (is (= 2 (count result))))))


(deftest query-zone-by-criteria-subtype-or-logic-test
  (testing "subtypes use OR logic"
    (let [db (th/create-test-db)
          ;; Island has subtype :island, Swamp has subtype :swamp
          [db' _] (th/add-card-to-zone db :island :hand :player-1)
          [db'' _] (th/add-card-to-zone db' :swamp :hand :player-1)
          ;; Query for island or swamp subtypes - should get both
          result (q/query-zone-by-criteria db'' :player-1 :hand {:match/subtypes #{:island :swamp}})]
      (is (= 2 (count result))))))


(deftest query-zone-by-criteria-empty-criteria-test
  (testing "empty criteria matches all objects in zone"
    (let [db (th/create-test-db)
          [db' _] (th/add-card-to-zone db :dark-ritual :hand :player-1)
          [db'' _] (th/add-card-to-zone db' :island :hand :player-1)
          [db''' _] (th/add-card-to-zone db'' :lotus-petal :hand :player-1)
          result (q/query-zone-by-criteria db''' :player-1 :hand {})]
      (is (= 3 (count result))))))


(deftest query-zone-by-criteria-no-match-test
  (testing "returns empty vector when no objects match criteria"
    (let [db (th/create-test-db)
          [db' _] (th/add-card-to-zone db :dark-ritual :hand :player-1)
          result (q/query-zone-by-criteria db' :player-1 :hand {:match/types #{:land}})]
      (is (= [] result)))))


(deftest query-zone-by-criteria-nonexistent-player-test
  (testing "returns nil for nonexistent player"
    (let [db (th/create-test-db)]
      (is (nil? (q/query-zone-by-criteria db :nonexistent :hand {}))))))


(deftest query-library-by-criteria-delegates-test
  (testing "delegates to query-zone-by-criteria with :library zone"
    (let [db (th/create-test-db)
          [db' _] (th/add-card-to-zone db :dark-ritual :library :player-1)
          [db'' _] (th/add-card-to-zone db' :island :library :player-1)
          ;; Query library for instants
          instants (q/query-library-by-criteria db'' :player-1 {:match/types #{:instant}})
          ;; Query library for lands
          lands (q/query-library-by-criteria db'' :player-1 {:match/types #{:land}})]
      (is (= 1 (count instants)))
      (is (= "Dark Ritual" (get-in (first instants) [:object/card :card/name])))
      (is (= 1 (count lands)))
      (is (= "Island" (get-in (first lands) [:object/card :card/name]))))))


(deftest query-library-by-criteria-nonexistent-player-test
  (testing "returns nil for nonexistent player"
    (let [db (th/create-test-db)]
      (is (nil? (q/query-library-by-criteria db :nonexistent {}))))))


(deftest query-zone-by-criteria-multi-type-or-test
  (testing "multiple types use OR logic - matches objects with ANY specified type"
    (let [db (th/create-test-db)
          ;; Add an instant (Dark Ritual) and a land (Island) to hand
          [db' _] (th/add-card-to-zone db :dark-ritual :hand :player-1)
          [db'' _] (th/add-card-to-zone db' :island :hand :player-1)
          ;; Query for instants OR lands - should get both
          result (q/query-zone-by-criteria db'' :player-1 :hand {:match/types #{:instant :land}})]
      (is (= 2 (count result))
          "Both instant and land should match when querying for either type"))))


;; === :match/not-types tests ===

(deftest matches-criteria-not-types-excludes-test
  (testing ":match/not-types #{:land} excludes lands"
    (let [db (th/create-test-db)
          [db' _] (th/add-card-to-zone db :island :hand :player-1)
          [db'' _] (th/add-card-to-zone db' :dark-ritual :hand :player-1)
          result (q/query-zone-by-criteria db'' :player-1 :hand {:match/not-types #{:land}})]
      (is (= 1 (count result))
          "Should exclude Island (land)")
      (is (= "Dark Ritual" (get-in (first result) [:object/card :card/name]))
          "Should return only Dark Ritual (instant)"))))


(deftest matches-criteria-not-types-multi-type-object-test
  (testing "object with types #{:artifact :creature} excluded by :match/not-types #{:creature}"
    (let [db (th/create-test-db)
          ;; Lotus Petal is an artifact - should NOT be excluded
          [db' _] (th/add-card-to-zone db :lotus-petal :hand :player-1)
          ;; Island is a land - should NOT be excluded by :creature
          [db'' _] (th/add-card-to-zone db' :island :hand :player-1)
          result (q/query-zone-by-criteria db'' :player-1 :hand {:match/not-types #{:creature}})]
      (is (= 2 (count result))
          "Neither artifact nor land has :creature type, so both should match"))))


(deftest matches-criteria-not-types-empty-set-test
  (testing "empty :match/not-types #{} matches everything"
    (let [db (th/create-test-db)
          [db' _] (th/add-card-to-zone db :dark-ritual :hand :player-1)
          [db'' _] (th/add-card-to-zone db' :island :hand :player-1)
          result (q/query-zone-by-criteria db'' :player-1 :hand {:match/not-types #{}})]
      (is (= 2 (count result))
          "Empty not-types should match all objects"))))


(deftest matches-criteria-types-and-not-types-combined-test
  (testing ":match/types #{:artifact} :match/not-types #{:land} combined"
    (let [db (th/create-test-db)
          ;; Lotus Petal is artifact (not land) - should match
          [db' _] (th/add-card-to-zone db :lotus-petal :hand :player-1)
          ;; Island is land (not artifact) - should not match (wrong type)
          [db'' _] (th/add-card-to-zone db' :island :hand :player-1)
          ;; Dark Ritual is instant (not artifact) - should not match (wrong type)
          [db''' _] (th/add-card-to-zone db'' :dark-ritual :hand :player-1)
          result (q/query-zone-by-criteria db''' :player-1 :hand
                                           {:match/types #{:artifact}
                                            :match/not-types #{:land}})]
      (is (= 1 (count result))
          "Only artifact that is not a land should match")
      (is (= "Lotus Petal" (get-in (first result) [:object/card :card/name]))
          "Lotus Petal (artifact, not land) should match"))))


(deftest matches-criteria-not-types-no-overlap-test
  (testing ":match/not-types #{:creature} matches object with types #{:instant}"
    (let [db (th/create-test-db)
          [db' _] (th/add-card-to-zone db :dark-ritual :hand :player-1)
          result (q/query-zone-by-criteria db' :player-1 :hand {:match/not-types #{:creature}})]
      (is (= 1 (count result))
          "Instant has no overlap with :creature, should match"))))


(deftest matches-criteria-not-types-exact-type-excluded-test
  (testing ":match/not-types #{:instant} excludes instants"
    (let [db (th/create-test-db)
          [db' _] (th/add-card-to-zone db :dark-ritual :hand :player-1)
          [db'' _] (th/add-card-to-zone db' :island :hand :player-1)
          result (q/query-zone-by-criteria db'' :player-1 :hand {:match/not-types #{:instant}})]
      (is (= 1 (count result))
          "Should exclude Dark Ritual (instant)")
      (is (= "Island" (get-in (first result) [:object/card :card/name]))
          "Should return only Island (land)"))))


(deftest matches-criteria-conflicting-types-and-not-types-test
  (testing "{:match/types #{:land} :match/not-types #{:land}} matches nothing"
    (let [db (th/create-test-db)
          [db' _] (th/add-card-to-zone db :island :hand :player-1)
          [db'' _] (th/add-card-to-zone db' :dark-ritual :hand :player-1)
          result (q/query-zone-by-criteria db'' :player-1 :hand
                                           {:match/types #{:land}
                                            :match/not-types #{:land}})]
      (is (= 0 (count result))
          "Conflicting types/not-types should match nothing"))))


(deftest matches-criteria-not-types-multiple-exclusions-test
  (testing ":match/not-types #{:land :instant} excludes both"
    (let [db (th/create-test-db)
          [db' _] (th/add-card-to-zone db :dark-ritual :hand :player-1)
          [db'' _] (th/add-card-to-zone db' :island :hand :player-1)
          [db''' _] (th/add-card-to-zone db'' :lotus-petal :hand :player-1)
          result (q/query-zone-by-criteria db''' :player-1 :hand
                                           {:match/not-types #{:land :instant}})]
      (is (= 1 (count result))
          "Should exclude both land and instant")
      (is (= "Lotus Petal" (get-in (first result) [:object/card :card/name]))
          "Only artifact should remain"))))


(deftest matches-criteria-not-types-with-colors-test
  (testing ":match/not-types combined with :match/colors"
    (let [db (th/create-test-db)
          ;; Dark Ritual is black instant
          [db' _] (th/add-card-to-zone db :dark-ritual :hand :player-1)
          ;; Brain Freeze is blue instant
          [db'' _] (th/add-card-to-zone db' :brain-freeze :hand :player-1)
          ;; Island is colorless land
          [db''' _] (th/add-card-to-zone db'' :island :hand :player-1)
          result (q/query-zone-by-criteria db''' :player-1 :hand
                                           {:match/colors #{:black}
                                            :match/not-types #{:land}})]
      (is (= 1 (count result))
          "Only black non-land should match")
      (is (= "Dark Ritual" (get-in (first result) [:object/card :card/name]))
          "Dark Ritual (black instant) should match"))))


;; === Corner case tests ===

(deftest stack-empty?-shared-stack-test
  ;; MTG Rules 117, 405: The stack is shared between all players.
  ;; stack-empty? must return false when ANY player has spells on the stack.
  (testing "stack-empty? treats stack as shared (MTG rules)"
    (let [conn (d/create-conn schema)
          _ (d/transact! conn cards/all-cards)
          _ (d/transact! conn [{:player/id :player-1
                                :player/name "Player"
                                :player/life 20
                                :player/mana-pool {:white 0 :blue 0 :black 0
                                                   :red 0 :green 0 :colorless 0}
                                :player/storm-count 0
                                :player/land-plays-left 1}
                               {:player/id :player-2
                                :player/name "Opponent"
                                :player/life 20
                                :player/mana-pool {:white 0 :blue 0 :black 0
                                                   :red 0 :green 0 :colorless 0}
                                :player/storm-count 0
                                :player/land-plays-left 0}])
          db @conn
          p2-eid (q/get-player-eid db :player-2)
          card-eid (d/q '[:find ?e .
                          :in $ ?cid
                          :where [?e :card/id ?cid]]
                        db :dark-ritual)
          ;; Put a spell on player-2's stack zone
          _ (d/transact! conn [{:object/id (random-uuid)
                                :object/card card-eid
                                :object/zone :stack
                                :object/owner p2-eid
                                :object/controller p2-eid
                                :object/tapped false}])
          db-after @conn]
      ;; Player-1's stack zone is empty, but player-2 has a spell on stack
      (is (empty? (q/get-objects-in-zone db-after :player-1 :stack))
          "Player-1 should have no spells on stack")
      (is (= 1 (count (q/get-objects-in-zone db-after :player-2 :stack)))
          "Player-2 should have a spell on stack")
      ;; The stack is SHARED — opponent's spell means stack is not empty
      (is (false? (q/stack-empty? db-after))
          "stack-empty? should be false when opponent has spells on stack"))))


(deftest get-objects-in-zone-battlefield-test
  ;; Bug caught: battlefield zone query broken
  (testing "get-objects-in-zone returns objects on battlefield"
    (let [db (th/create-test-db)
          [db' obj-id] (th/add-card-to-zone db :dark-ritual :battlefield :player-1)
          objs (q/get-objects-in-zone db' :player-1 :battlefield)]
      (is (= 1 (count objs))
          "Should find 1 object on battlefield")
      (is (= obj-id (:object/id (first objs)))
          "Object on battlefield should match the one we added")
      (is (= "Dark Ritual" (get-in (first objs) [:object/card :card/name]))
          "Card data should be pulled in for battlefield objects"))))


;; === get-active-player-id ===

(deftest get-active-player-id-returns-active-player-test
  (testing "returns :player-1 when player-1 is active"
    (let [db (th/create-test-db)]
      (is (= :player-1 (q/get-active-player-id db))))))


(deftest get-active-player-id-after-switching-test
  (testing "returns :player-2 after switching active player to opponent"
    (let [db (th/create-test-db)
          db' (th/add-opponent db)
          ;; Switch active player to :player-2
          game-eid (d/q '[:find ?g . :where [?g :game/id _]] db')
          p2-eid (q/get-player-eid db' :player-2)
          db'' (d/db-with db' [[:db/add game-eid :game/active-player p2-eid]])]
      (is (= :player-2 (q/get-active-player-id db''))))))


(deftest get-active-player-id-no-game-state-test
  (testing "returns nil when no game state exists"
    (let [conn (d/create-conn schema)]
      (is (nil? (q/get-active-player-id @conn))))))


;; === get-human-player-id ===

(deftest get-human-player-id-returns-stored-value-test
  (testing "returns :player-1 from game/human-player-id"
    (let [db (th/create-test-db)]
      (is (= :player-1 (q/get-human-player-id db))))))


(deftest get-human-player-id-fallback-test
  (testing "returns :player-1 as fallback when game/human-player-id not set"
    (let [conn (d/create-conn schema)
          _ (d/transact! conn [{:player/id :player-1
                                :player/name "Player"
                                :player/life 20
                                :player/mana-pool {:white 0 :blue 0 :black 0
                                                   :red 0 :green 0 :colorless 0}
                                :player/storm-count 0
                                :player/land-plays-left 1}])
          player-eid (d/q '[:find ?e . :where [?e :player/id :player-1]] @conn)
          ;; Create game state WITHOUT :game/human-player-id
          _ (d/transact! conn [{:game/id :game-1
                                :game/turn 1
                                :game/phase :main1
                                :game/active-player player-eid
                                :game/priority player-eid}])]
      (is (= :player-1 (q/get-human-player-id @conn))))))


(deftest get-human-player-id-no-game-state-test
  (testing "returns :player-1 fallback when no game state exists"
    (let [conn (d/create-conn schema)]
      (is (= :player-1 (q/get-human-player-id @conn))))))


;; === get-all-objects-in-zone ===

(deftest get-all-objects-in-zone-single-player-test
  (testing "returns objects from a single player's zone"
    (let [db (th/create-test-db)
          [db' obj-id] (th/add-card-to-zone db :dark-ritual :battlefield :player-1)
          objs (q/get-all-objects-in-zone db' :battlefield)]
      (is (vector? objs))
      (is (= 1 (count objs)))
      (is (= obj-id (:object/id (first objs))))
      (is (= "Dark Ritual" (get-in (first objs) [:object/card :card/name]))))))


(deftest get-all-objects-in-zone-multiple-players-test
  (testing "returns objects from ALL players' zones"
    (let [db (th/create-test-db)
          db' (th/add-opponent db)
          [db'' p1-obj] (th/add-card-to-zone db' :dark-ritual :battlefield :player-1)
          [db''' p2-obj] (th/add-card-to-zone db'' :island :battlefield :player-2)
          objs (q/get-all-objects-in-zone db''' :battlefield)
          obj-ids (set (map :object/id objs))]
      (is (= 2 (count objs)))
      (is (contains? obj-ids p1-obj))
      (is (contains? obj-ids p2-obj)))))


(deftest get-all-objects-in-zone-empty-zone-test
  (testing "returns empty vector when no objects in zone"
    (let [db (th/create-test-db)
          objs (q/get-all-objects-in-zone db :battlefield)]
      (is (vector? objs))
      (is (= 0 (count objs))))))


(deftest get-all-objects-in-zone-does-not-cross-zones-test
  (testing "only returns objects from requested zone, not other zones"
    (let [db (th/create-test-db)
          [db' _] (th/add-card-to-zone db :dark-ritual :hand :player-1)
          [db'' bf-obj] (th/add-card-to-zone db' :island :battlefield :player-1)
          objs (q/get-all-objects-in-zone db'' :battlefield)]
      (is (= 1 (count objs)))
      (is (= bf-obj (:object/id (first objs)))))))


;; === count-cards-named-in-zone ===

(deftest count-cards-named-in-zone-single-copy-test
  (testing "returns 1 when one copy exists in zone"
    (let [db (th/create-test-db)
          [db' _] (th/add-card-to-zone db :dark-ritual :graveyard :player-1)]
      (is (= 1 (q/count-cards-named-in-zone db' "Dark Ritual" :graveyard))))))


(deftest count-cards-named-in-zone-multiple-copies-test
  (testing "returns correct count for multiple copies"
    (let [db (th/create-test-db)
          [db' _] (th/add-card-to-zone db :dark-ritual :graveyard :player-1)
          [db'' _] (th/add-card-to-zone db' :dark-ritual :graveyard :player-1)
          [db''' _] (th/add-card-to-zone db'' :dark-ritual :graveyard :player-1)]
      (is (= 3 (q/count-cards-named-in-zone db''' "Dark Ritual" :graveyard))))))


(deftest count-cards-named-in-zone-across-players-test
  (testing "counts cards across all players"
    (let [db (th/create-test-db)
          db' (th/add-opponent db)
          [db'' _] (th/add-card-to-zone db' :dark-ritual :graveyard :player-1)
          [db''' _] (th/add-card-to-zone db'' :dark-ritual :graveyard :player-2)]
      (is (= 2 (q/count-cards-named-in-zone db''' "Dark Ritual" :graveyard))))))


(deftest count-cards-named-in-zone-zero-test
  (testing "returns 0 when no copies in zone"
    (let [db (th/create-test-db)]
      (is (= 0 (q/count-cards-named-in-zone db "Dark Ritual" :graveyard))))))


(deftest count-cards-named-in-zone-wrong-zone-test
  (testing "returns 0 when card exists in different zone"
    (let [db (th/create-test-db)
          [db' _] (th/add-card-to-zone db :dark-ritual :hand :player-1)]
      (is (= 0 (q/count-cards-named-in-zone db' "Dark Ritual" :graveyard))))))


(deftest count-cards-named-in-zone-wrong-name-test
  (testing "returns 0 when zone has cards but not the requested name"
    (let [db (th/create-test-db)
          [db' _] (th/add-card-to-zone db :dark-ritual :graveyard :player-1)]
      (is (= 0 (q/count-cards-named-in-zone db' "Brain Freeze" :graveyard))))))


;; === get-object-eid ===

(deftest get-object-eid-valid-object-test
  (testing "returns positive integer eid for existing object"
    (let [db (th/create-test-db)
          [db' obj-id] (th/add-card-to-zone db :dark-ritual :hand :player-1)
          eid (q/get-object-eid db' obj-id)]
      (is (integer? eid))
      (is (pos? eid)))))


(deftest get-object-eid-different-objects-unique-test
  (testing "returns different eids for different objects"
    (let [db (th/create-test-db)
          [db' obj-id-1] (th/add-card-to-zone db :dark-ritual :hand :player-1)
          [db'' obj-id-2] (th/add-card-to-zone db' :brain-freeze :hand :player-1)
          eid-1 (q/get-object-eid db'' obj-id-1)
          eid-2 (q/get-object-eid db'' obj-id-2)]
      (is (integer? eid-1))
      (is (integer? eid-2))
      (is (not= eid-1 eid-2)))))


(deftest get-object-eid-nonexistent-test
  (testing "returns nil for nonexistent object-id"
    (let [db (th/create-test-db)]
      (is (nil? (q/get-object-eid db (random-uuid)))))))


;; === get-grants ===

(deftest get-grants-no-grants-test
  (testing "returns empty vector when object has no grants"
    (let [db (th/create-test-db)
          [db' obj-id] (th/add-card-to-zone db :dark-ritual :hand :player-1)]
      (is (= [] (q/get-grants db' obj-id))))))


(deftest get-grants-with-grants-test
  (testing "returns grant vector when object has grants"
    (let [db (th/create-test-db)
          [db' obj-id] (th/add-card-to-zone db :dark-ritual :graveyard :player-1)
          grant {:grant/id (random-uuid)
                 :grant/type :keyword
                 :grant/source (random-uuid)
                 :grant/data {:keyword :flashback
                              :flashback-cost {:black 1}}}
          obj-eid (q/get-object-eid db' obj-id)
          db'' (d/db-with db' [[:db/add obj-eid :object/grants [grant]]])
          grants (q/get-grants db'' obj-id)]
      (is (= 1 (count grants)))
      (is (= :keyword (:grant/type (first grants))))
      (is (= :flashback (get-in (first grants) [:grant/data :keyword]))))))


(deftest get-grants-multiple-grants-test
  (testing "returns all grants when object has multiple"
    (let [db (th/create-test-db)
          [db' obj-id] (th/add-card-to-zone db :dark-ritual :graveyard :player-1)
          grant-1 {:grant/id (random-uuid)
                   :grant/type :keyword
                   :grant/source (random-uuid)
                   :grant/data {:keyword :flashback}}
          grant-2 {:grant/id (random-uuid)
                   :grant/type :ability
                   :grant/source (random-uuid)
                   :grant/data {:ability/type :activated}}
          obj-eid (q/get-object-eid db' obj-id)
          db'' (d/db-with db' [[:db/add obj-eid :object/grants [grant-1 grant-2]]])
          grants (q/get-grants db'' obj-id)]
      (is (= 2 (count grants)))
      (is (= #{:keyword :ability} (set (map :grant/type grants)))))))


(deftest get-grants-nonexistent-object-test
  (testing "returns empty vector for nonexistent object"
    (let [db (th/create-test-db)]
      (is (= [] (q/get-grants db (random-uuid)))))))


;; === :match/not-colors tests ===

(deftest matches-criteria-not-colors-excludes-black-test
  (testing ":match/not-colors #{:black} excludes black card"
    ;; Catches: missing exclusion logic for not-colors
    (let [db (th/create-test-db)
          ;; Dark Ritual is black instant
          [db' _] (th/add-card-to-zone db :dark-ritual :hand :player-1)
          result (q/query-zone-by-criteria db' :player-1 :hand {:match/not-colors #{:black}})]
      (is (= 0 (count result))
          "Black card should be excluded by :match/not-colors #{:black}"))))


(deftest matches-criteria-not-colors-allows-blue-test
  (testing ":match/not-colors #{:black} allows blue card"
    ;; Catches: over-filtering (excluding too many cards)
    (let [db (th/create-test-db)
          ;; Brain Freeze is blue instant
          [db' _] (th/add-card-to-zone db :brain-freeze :hand :player-1)
          result (q/query-zone-by-criteria db' :player-1 :hand {:match/not-colors #{:black}})]
      (is (= 1 (count result))
          "Blue card should NOT be excluded by :match/not-colors #{:black}")
      (is (= "Brain Freeze" (get-in (first result) [:object/card :card/name]))
          "Brain Freeze (blue) should match"))))


(deftest matches-criteria-not-colors-allows-colorless-test
  (testing ":match/not-colors #{:black} allows colorless artifact"
    ;; Catches: colorless treated as having colors — colorless objects have empty
    ;; colors set (#{}) so set/intersection with #{:black} = #{} → should NOT be excluded
    (let [db (th/create-test-db)
          ;; Lotus Petal is a colorless artifact
          [db' _] (th/add-card-to-zone db :lotus-petal :hand :player-1)
          result (q/query-zone-by-criteria db' :player-1 :hand {:match/not-colors #{:black}})]
      (is (= 1 (count result))
          "Colorless artifact should NOT be excluded by :match/not-colors #{:black}")
      (is (= "Lotus Petal" (get-in (first result) [:object/card :card/name]))
          "Lotus Petal (colorless) should match"))))


(deftest matches-criteria-not-colors-empty-set-matches-all-test
  (testing ":match/not-colors #{} matches everything"
    ;; Catches: empty set mishandled (excluding everything or causing error)
    (let [db (th/create-test-db)
          [db' _] (th/add-card-to-zone db :dark-ritual :hand :player-1)
          [db'' _] (th/add-card-to-zone db' :brain-freeze :hand :player-1)
          [db''' _] (th/add-card-to-zone db'' :lotus-petal :hand :player-1)
          result (q/query-zone-by-criteria db''' :player-1 :hand {:match/not-colors #{}})]
      (is (= 3 (count result))
          "Empty not-colors should match all objects"))))


(deftest matches-criteria-not-colors-multi-color-set-test
  (testing ":match/not-colors #{:black :blue} excludes object with either color"
    ;; Catches: OR vs AND logic bug — should exclude if object has ANY excluded color
    (let [db (th/create-test-db)
          ;; Dark Ritual is black - should be excluded
          [db' _] (th/add-card-to-zone db :dark-ritual :hand :player-1)
          ;; Brain Freeze is blue - should be excluded
          [db'' _] (th/add-card-to-zone db' :brain-freeze :hand :player-1)
          ;; Lotus Petal is colorless - should match
          [db''' _] (th/add-card-to-zone db'' :lotus-petal :hand :player-1)
          result (q/query-zone-by-criteria db''' :player-1 :hand {:match/not-colors #{:black :blue}})]
      (is (= 1 (count result))
          "Both black and blue cards should be excluded")
      (is (= "Lotus Petal" (get-in (first result) [:object/card :card/name]))
          "Only colorless artifact should remain"))))


(deftest matches-criteria-types-and-not-colors-combined-test
  (testing ":match/types #{:instant} + :match/not-colors #{:black} — Vendetta pattern"
    ;; Catches: combined criteria not working together correctly
    ;; Vendetta targets nonblack creatures — here we test the not-colors half
    (let [db (th/create-test-db)
          ;; Brain Freeze is blue instant - should match (instant, not black)
          [db' _] (th/add-card-to-zone db :brain-freeze :hand :player-1)
          ;; Dark Ritual is black instant - should not match (instant but black)
          [db'' _] (th/add-card-to-zone db' :dark-ritual :hand :player-1)
          ;; Lotus Petal is colorless artifact - should not match (not instant)
          [db''' _] (th/add-card-to-zone db'' :lotus-petal :hand :player-1)
          result (q/query-zone-by-criteria db''' :player-1 :hand
                                           {:match/types #{:instant}
                                            :match/not-colors #{:black}})]
      (is (= 1 (count result))
          "Only nonblack instant should match")
      (is (= "Brain Freeze" (get-in (first result) [:object/card :card/name]))
          "Brain Freeze (blue instant) should match"))))


(deftest matches-criteria-not-colors-partial-overlap-test
  (testing "object with multiple colors #{:black :blue} excluded by :match/not-colors #{:black}"
    ;; Catches: partial color match bug — Diabolic Vision has #{:blue :black}
    ;; even though it's also blue, it should still be excluded
    (let [db (th/create-test-db)
          ;; Diabolic Vision is blue+black multicolor
          [db' _] (th/add-card-to-zone db :diabolic-vision :hand :player-1)
          result (q/query-zone-by-criteria db' :player-1 :hand {:match/not-colors #{:black}})]
      (is (= 0 (count result))
          "Multicolor card with black should be excluded by :match/not-colors #{:black}"))))


(deftest matches-criteria-not-colors-with-colors-combined-test
  (testing ":match/colors #{:blue} combined with :match/not-colors #{:black}"
    ;; Catches: interaction between positive and negative color filters
    ;; {:match/colors #{:blue} :match/not-colors #{:black}} should match blue-only
    ;; but exclude blue-black multicolor (Diabolic Vision)
    (let [db (th/create-test-db)
          ;; Brain Freeze is blue-only instant - should match
          [db' _] (th/add-card-to-zone db :brain-freeze :hand :player-1)
          ;; Diabolic Vision is blue+black - should NOT match (excluded by not-colors)
          [db'' _] (th/add-card-to-zone db' :diabolic-vision :hand :player-1)
          ;; Dark Ritual is black-only - should NOT match (missing required blue color)
          [db''' _] (th/add-card-to-zone db'' :dark-ritual :hand :player-1)
          result (q/query-zone-by-criteria db''' :player-1 :hand
                                           {:match/colors #{:blue}
                                            :match/not-colors #{:black}})]
      (is (= 1 (count result))
          "Only pure blue card should match")
      (is (= "Brain Freeze" (get-in (first result) [:object/card :card/name]))
          "Brain Freeze (blue, not black) should match"))))


;; === :match/has-ability-type tests ===

(deftest matches-criteria-has-ability-type-activated-matches-land-with-activated-ability-test
  (testing ":match/has-ability-type :activated matches object whose card has :ability/type :activated"
    ;; Catches: absent conjunct — without the new axis both Wasteland and Island match,
    ;; but only Wasteland has :activated ability; Island has only :mana.
    (let [db (th/create-test-db)
          ;; Wasteland has both :mana and :activated abilities
          [db' _] (th/add-card-to-zone db :wasteland :hand :player-1)
          ;; Island has only :mana ability — must NOT match :activated criterion
          [db'' _] (th/add-card-to-zone db' :island :hand :player-1)
          result (q/query-zone-by-criteria db'' :player-1 :hand {:match/has-ability-type :activated})]
      (is (= 1 (count result))
          "Only Wasteland (has :activated ability) should match; Island (mana-only) should not")
      (is (= "Wasteland" (get-in (first result) [:object/card :card/name]))
          "Wasteland should be returned"))))


(deftest matches-criteria-has-ability-type-activated-does-not-match-mana-only-land-test
  (testing ":match/has-ability-type :activated returns false for object with only :mana ability"
    ;; Catches: wrong-direction logic — Island has only :mana, not :activated
    (let [db (th/create-test-db)
          [db' _] (th/add-card-to-zone db :island :hand :player-1)
          result (q/query-zone-by-criteria db' :player-1 :hand {:match/has-ability-type :activated})]
      (is (= 0 (count result))
          "Island (mana-only) should NOT match :activated criterion"))))


(deftest matches-criteria-has-ability-type-nil-card-abilities-returns-false-test
  (testing "object with no :card/abilities returns false, does not throw"
    ;; Catches: nil-path bugs — Dark Ritual has no :card/abilities key
    (let [db (th/create-test-db)
          [db' _] (th/add-card-to-zone db :dark-ritual :hand :player-1)
          result (q/query-zone-by-criteria db' :player-1 :hand {:match/has-ability-type :activated})]
      (is (= 0 (count result))
          "Dark Ritual (no :card/abilities) should NOT match and not throw"))))


(deftest matches-criteria-has-ability-type-and-types-both-applied-test
  (testing "combined :match/types and :match/has-ability-type requires BOTH (AND semantics)"
    ;; Catches: accidental OR semantics — Island is a land but has no :activated ability
    ;; Wasteland is a land WITH :activated ability — only Wasteland should match
    (let [db (th/create-test-db)
          [db' _] (th/add-card-to-zone db :island :hand :player-1)
          [db'' _] (th/add-card-to-zone db' :wasteland :hand :player-1)
          [db''' _] (th/add-card-to-zone db'' :dark-ritual :hand :player-1)
          result (q/query-zone-by-criteria db''' :player-1 :hand
                                           {:match/types #{:land}
                                            :match/has-ability-type :activated})]
      (is (= 1 (count result))
          "Only Wasteland (land + :activated ability) should match both criteria")
      (is (= "Wasteland" (get-in (first result) [:object/card :card/name]))
          "Wasteland should be returned"))))


(deftest matches-criteria-existing-types-axis-unaffected-regression-test
  (testing ":match/types #{:land} alone on a land still matches (regression for additive change)"
    ;; Catches: the new conjunct breaking existing callers that don't pass :match/has-ability-type
    (let [db (th/create-test-db)
          [db' _] (th/add-card-to-zone db :island :hand :player-1)
          [db'' _] (th/add-card-to-zone db' :dark-ritual :hand :player-1)
          result (q/query-zone-by-criteria db'' :player-1 :hand {:match/types #{:land}})]
      (is (= 1 (count result))
          "Island (land) should match :match/types #{:land} as before")
      (is (= "Island" (get-in (first result) [:object/card :card/name]))
          "Island should be returned"))))


(deftest matches-criteria-absent-has-ability-type-criterion-does-not-restrict-test
  (testing "criteria without :match/has-ability-type does not spuriously filter"
    ;; Catches: nil-criterion branch missing — all objects match when criterion absent
    (let [db (th/create-test-db)
          [db' _] (th/add-card-to-zone db :island :hand :player-1)
          [db'' _] (th/add-card-to-zone db' :dark-ritual :hand :player-1)
          result (q/query-zone-by-criteria db'' :player-1 :hand {})]
      (is (= 2 (count result))
          "Empty criteria (no :match/has-ability-type) should match all objects"))))
