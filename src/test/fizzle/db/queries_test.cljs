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
      (d/transact! conn [{:player/id :opponent
                          :player/name "Opponent"
                          :player/life 20
                          :player/is-opponent true
                          :player/mana-pool {:white 0 :blue 0 :black 0
                                             :red 0 :green 0 :colorless 0}
                          :player/storm-count 0}])
      (is (= :opponent (q/get-opponent-id @conn :player-1))))))


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
          db' (stack/create-stack-item db {:stack-item/type :storm-copy
                                           :stack-item/controller :player-1
                                           :stack-item/description "Storm copy"})]
      (is (false? (q/stack-empty? db'))))))


(deftest stack-empty?-spells-only-test
  (testing "false when only spells on stack"
    (let [db (th/create-test-db)
          [db' _] (th/add-card-to-zone db :dark-ritual :stack :player-1)]
      (is (false? (q/stack-empty? db'))))))


(deftest stack-empty?-both-test
  (testing "false when both stack-items and spells on stack"
    (let [db (th/create-test-db)
          [db' _] (th/add-card-to-zone db :dark-ritual :stack :player-1)
          db'' (stack/create-stack-item db' {:stack-item/type :storm-copy
                                             :stack-item/controller :player-1
                                             :stack-item/description "Storm copy"})]
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
