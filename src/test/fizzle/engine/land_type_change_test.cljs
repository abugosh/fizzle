(ns fizzle.engine.land-type-change-test
  "Tests for land type change mechanic.

   Covers:
   - :change-land-types effect returns :needs-selection
   - Two-step chained selection (source → target land type)
   - Grant application to matching lands on battlefield
   - Mana ability override when grant present
   - Grant expiration restores original mana production
   - Edge cases: no matching lands, multiple matching lands, dual-subtype lands"
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [datascript.core :as d]
    [fizzle.db.queries :as q]
    [fizzle.engine.effects :as effects]
    [fizzle.engine.effects-registry]
    [fizzle.engine.grants :as grants]
    [fizzle.engine.land-types :as land-types]
    [fizzle.engine.mana-activation :as mana-activation]
    [fizzle.events.selection.core :as sel-core]
    [fizzle.events.selection.land-types]
    [fizzle.test-helpers :as th]))


;; =====================================================
;; Helpers
;; =====================================================

(defn add-basic-land
  "Add a basic land of the given card-id (e.g. :island) to player's battlefield.
   Returns [db object-id]."
  [db player-id card-id]
  (th/add-card-to-zone db card-id :battlefield player-id))


;; =====================================================
;; A. land-types module
;; =====================================================

(deftest land-types-module-test
  (testing "basic-land-types map has all 5 entries"
    (is (= 5 (count land-types/basic-land-types))
        "Should have exactly 5 basic land types"))

  (testing "basic-land-type-keys has all 5 keywords in order"
    (is (= [:plains :island :swamp :mountain :forest]
           land-types/basic-land-type-keys)
        "Should have all 5 basic land type keywords in standard order"))

  (testing "each land type has the correct produces map"
    (is (= {:white 1} (get-in land-types/basic-land-types [:plains :produces])))
    (is (= {:blue 1} (get-in land-types/basic-land-types [:island :produces])))
    (is (= {:black 1} (get-in land-types/basic-land-types [:swamp :produces])))
    (is (= {:red 1} (get-in land-types/basic-land-types [:mountain :produces])))
    (is (= {:green 1} (get-in land-types/basic-land-types [:forest :produces]))))

  (testing "each land type subtype matches its key"
    (doseq [k [:plains :island :swamp :mountain :forest]]
      (is (= k (get-in land-types/basic-land-types [k :subtype]))
          (str "Subtype for " k " should match key")))))


;; =====================================================
;; B. :change-land-types effect returns :needs-selection
;; =====================================================

(deftest change-land-types-effect-returns-needs-selection-test
  (testing ":change-land-types effect pauses execution and returns :needs-selection"
    (let [db (th/create-test-db)
          effect {:effect/type :change-land-types}
          result (effects/execute-effect-checked db :player-1 effect)]
      (is (map? result) "Effect should return a map")
      (is (= db (:db result)) "db should be unchanged")
      (is (some? (:needs-selection result)) "Should include :needs-selection"))))


;; =====================================================
;; C. Source land type selection
;; =====================================================

(deftest source-selection-has-five-options-test
  (testing "source land type selection presents all 5 basic land type options"
    (let [db (th/create-test-db)
          effect {:effect/type :change-land-types}
          sel (sel-core/build-selection-for-effect db :player-1 (random-uuid) effect [])]
      (is (= :land-type-source (:selection/domain sel))
          "Selection type should be :land-type-source")
      (is (= [:plains :island :swamp :mountain :forest] (:selection/options sel))
          "All 5 basic land types should be options")
      (is (= :chaining (:selection/lifecycle sel))
          "Lifecycle should be :chaining to trigger chain to target selection")
      (is (= :exact (:selection/validation sel))
          "Validation should be :exact")
      (is (= 1 (:selection/select-count sel))
          "Should select exactly 1 land type"))))


;; =====================================================
;; D. Target land type selection (chained from source)
;; =====================================================

(deftest target-selection-excludes-source-type-test
  (testing "target land type selection excludes the chosen source type"
    (let [db (th/create-test-db)
          spell-id (random-uuid)
          effect {:effect/type :change-land-types}
          source-sel (sel-core/build-selection-for-effect db :player-1 spell-id effect [])
          ;; Confirm source selection with :island
          source-sel-confirmed (assoc source-sel :selection/selected #{:island})
          app-db {:game/db db :game/pending-selection source-sel-confirmed}
          result (sel-core/confirm-selection-impl app-db)
          next-sel (:game/pending-selection result)]
      (is (some? next-sel) "Should chain to target selection")
      (is (= :land-type-target (:selection/domain next-sel))
          "Chained selection type should be :land-type-target")
      (is (= 4 (count (:selection/options next-sel)))
          "Target options should exclude the source type (4 remaining)")
      (is (not (contains? (set (:selection/options next-sel)) :island))
          "Target options should NOT include the source type :island")
      (is (= :island (:selection/source-type next-sel))
          "Source type should be stored on target selection"))))


;; =====================================================
;; E. Grant application: matching lands get grants
;; =====================================================

(deftest grants-applied-to-matching-lands-test
  (testing "grants applied to all islands when source=:island, target=:mountain"
    (let [db (th/create-test-db)
          [db island-1-id] (add-basic-land db :player-1 :island)
          [db island-2-id] (add-basic-land db :player-1 :island)
          [db swamp-id] (add-basic-land db :player-1 :swamp)
          spell-id (random-uuid)
          effect {:effect/type :change-land-types}
          ;; Step 1: Source selection
          source-sel (sel-core/build-selection-for-effect db :player-1 spell-id effect [])
          source-sel-confirmed (assoc source-sel :selection/selected #{:island})
          app-db {:game/db db :game/pending-selection source-sel-confirmed}
          result1 (sel-core/confirm-selection-impl app-db)
          target-sel (:game/pending-selection result1)
          ;; Step 2: Target selection
          target-sel-confirmed (assoc target-sel :selection/selected #{:mountain})
          app-db2 (assoc result1 :game/pending-selection target-sel-confirmed)
          result2 (sel-core/confirm-selection-impl app-db2)
          db-final (:game/db result2)]
      ;; Both islands should have land-type-override grants
      (let [island-1-grants (grants/get-grants-by-type db-final island-1-id :land-type-override)
            island-2-grants (grants/get-grants-by-type db-final island-2-id :land-type-override)
            swamp-grants (grants/get-grants-by-type db-final swamp-id :land-type-override)]
        (is (= 1 (count island-1-grants)) "First island should have 1 override grant")
        (is (= 1 (count island-2-grants)) "Second island should have 1 override grant")
        (is (= 0 (count swamp-grants)) "Swamp should have no override grants")
        ;; Verify grant data
        (let [grant (first island-1-grants)]
          (is (= :island (get-in grant [:grant/data :original-subtype]))
              "Grant data should record original subtype :island")
          (is (= :mountain (get-in grant [:grant/data :new-subtype]))
              "Grant data should record new subtype :mountain")
          (is (= {:red 1} (get-in grant [:grant/data :new-produces]))
              "Grant data should record new produces {:red 1}"))))))


(deftest no-matching-lands-no-crash-test
  (testing "no matching lands on battlefield → no grants applied, no crash"
    (let [db (th/create-test-db)
          [db forest-id] (add-basic-land db :player-1 :forest)
          spell-id (random-uuid)
          effect {:effect/type :change-land-types}
          ;; Source = :island (no islands on battlefield)
          source-sel (sel-core/build-selection-for-effect db :player-1 spell-id effect [])
          source-sel-confirmed (assoc source-sel :selection/selected #{:island})
          app-db {:game/db db :game/pending-selection source-sel-confirmed}
          result1 (sel-core/confirm-selection-impl app-db)
          target-sel (:game/pending-selection result1)
          target-sel-confirmed (assoc target-sel :selection/selected #{:swamp})
          app-db2 (assoc result1 :game/pending-selection target-sel-confirmed)
          result2 (sel-core/confirm-selection-impl app-db2)
          db-final (:game/db result2)]
      ;; Forest should be unaffected
      (let [forest-grants (grants/get-grants-by-type db-final forest-id :land-type-override)]
        (is (= 0 (count forest-grants))
            "Forest should have no override grants")))))


(deftest grants-apply-to-both-players-lands-test
  (testing "grants applied to matching lands on BOTH players' battlefields"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          [db p1-island-id] (add-basic-land db :player-1 :island)
          [db p2-island-id] (add-basic-land db :player-2 :island)
          spell-id (random-uuid)
          effect {:effect/type :change-land-types}
          source-sel (sel-core/build-selection-for-effect db :player-1 spell-id effect [])
          source-sel-confirmed (assoc source-sel :selection/selected #{:island})
          app-db {:game/db db :game/pending-selection source-sel-confirmed}
          result1 (sel-core/confirm-selection-impl app-db)
          target-sel (:game/pending-selection result1)
          target-sel-confirmed (assoc target-sel :selection/selected #{:mountain})
          app-db2 (assoc result1 :game/pending-selection target-sel-confirmed)
          result2 (sel-core/confirm-selection-impl app-db2)
          db-final (:game/db result2)]
      (let [p1-grants (grants/get-grants-by-type db-final p1-island-id :land-type-override)
            p2-grants (grants/get-grants-by-type db-final p2-island-id :land-type-override)]
        (is (= 1 (count p1-grants)) "Player 1's island should have override grant")
        (is (= 1 (count p2-grants)) "Player 2's island should have override grant")))))


;; =====================================================
;; F. Mana ability override
;; =====================================================

(deftest overridden-land-produces-new-mana-test
  (testing "island overridden to mountain produces red mana"
    (let [db (th/create-test-db)
          [db island-id] (add-basic-land db :player-1 :island)
          spell-id (random-uuid)
          effect {:effect/type :change-land-types}
          ;; Apply the override: island → mountain
          source-sel (sel-core/build-selection-for-effect db :player-1 spell-id effect [])
          source-sel-confirmed (assoc source-sel :selection/selected #{:island})
          app-db {:game/db db :game/pending-selection source-sel-confirmed}
          result1 (sel-core/confirm-selection-impl app-db)
          target-sel (:game/pending-selection result1)
          target-sel-confirmed (assoc target-sel :selection/selected #{:mountain})
          app-db2 (assoc result1 :game/pending-selection target-sel-confirmed)
          result2 (sel-core/confirm-selection-impl app-db2)
          db-with-override (:game/db result2)
          ;; Activate mana ability (tap for red)
          db-after-tap (mana-activation/activate-mana-ability
                         db-with-override :player-1 island-id :red)]
      (is (= 1 (get (q/get-mana-pool db-after-tap :player-1) :red 0))
          "Island with mountain override should produce 1 red mana")
      (is (= 0 (get (q/get-mana-pool db-after-tap :player-1) :blue 0))
          "Island with mountain override should NOT produce blue mana"))))


(deftest non-overridden-land-produces-original-mana-test
  (testing "island without override still produces blue mana"
    (let [db (th/create-test-db)
          [db island-id] (add-basic-land db :player-1 :island)
          db-after-tap (mana-activation/activate-mana-ability db :player-1 island-id :blue)]
      (is (= 1 (get (q/get-mana-pool db-after-tap :player-1) :blue 0))
          "Island without override should produce 1 blue mana"))))


;; =====================================================
;; G. Grant expiration restores original mana
;; =====================================================

(deftest grant-expires-at-cleanup-restores-mana-test
  (testing "after expire-grants at cleanup, island reverts to blue mana"
    (let [db (th/create-test-db)
          [db island-id] (add-basic-land db :player-1 :island)
          spell-id (random-uuid)
          effect {:effect/type :change-land-types}
          ;; Apply override: island → mountain
          source-sel (sel-core/build-selection-for-effect db :player-1 spell-id effect [])
          source-sel-confirmed (assoc source-sel :selection/selected #{:island})
          app-db {:game/db db :game/pending-selection source-sel-confirmed}
          result1 (sel-core/confirm-selection-impl app-db)
          target-sel (:game/pending-selection result1)
          target-sel-confirmed (assoc target-sel :selection/selected #{:mountain})
          app-db2 (assoc result1 :game/pending-selection target-sel-confirmed)
          result2 (sel-core/confirm-selection-impl app-db2)
          db-with-override (:game/db result2)
          ;; Expire grants at current turn cleanup
          game-state (q/get-game-state db-with-override)
          current-turn (:game/turn game-state)
          db-after-expire (grants/expire-grants db-with-override current-turn :cleanup)
          ;; Now try to tap for red (should fail, grant gone)
          db-after-red-tap (mana-activation/activate-mana-ability
                             db-after-expire :player-1 island-id :red)
          ;; Tap for blue (should succeed now)
          db-after-blue-tap (mana-activation/activate-mana-ability
                              db-after-expire :player-1 island-id :blue)]
      ;; After expire: island should produce blue, not red
      ;; Island after grant expiry rejects red tap request (mana unchanged)
      (is (= 0 (get (q/get-mana-pool db-after-red-tap :player-1) :red 0))
          "After expire, island should NOT produce red mana")
      (is (= 1 (get (q/get-mana-pool db-after-blue-tap :player-1) :blue 0))
          "After expire, island should produce blue mana again"))))


;; =====================================================
;; H. Grant has correct expiration
;; =====================================================

(deftest grant-expires-at-current-turn-cleanup-test
  (testing "grant expires at current turn's cleanup phase"
    (let [db (th/create-test-db)
          [db island-id] (add-basic-land db :player-1 :island)
          spell-id (random-uuid)
          effect {:effect/type :change-land-types}
          source-sel (sel-core/build-selection-for-effect db :player-1 spell-id effect [])
          source-sel-confirmed (assoc source-sel :selection/selected #{:island})
          app-db {:game/db db :game/pending-selection source-sel-confirmed}
          result1 (sel-core/confirm-selection-impl app-db)
          target-sel (:game/pending-selection result1)
          target-sel-confirmed (assoc target-sel :selection/selected #{:mountain})
          app-db2 (assoc result1 :game/pending-selection target-sel-confirmed)
          result2 (sel-core/confirm-selection-impl app-db2)
          db-final (:game/db result2)
          grant (first (grants/get-grants-by-type db-final island-id :land-type-override))
          game-state (q/get-game-state db-final)
          current-turn (:game/turn game-state)]
      (is (= current-turn (get-in grant [:grant/expires :expires/turn]))
          "Grant should expire on the current turn")
      (is (= :cleanup (get-in grant [:grant/expires :expires/phase]))
          "Grant should expire at :cleanup phase"))))


;; =====================================================
;; I. Dual-subtype land with source subtype also gets overridden
;; =====================================================

(defn- add-dual-subtype-land
  "Add a synthetic dual-subtype land with :island and :swamp subtypes.
   Returns [db object-id]."
  [db owner]
  (let [conn (d/conn-from-db db)
        player-eid (q/get-player-eid db owner)
        card-id (keyword (str "test-dual-land-" (random-uuid)))
        _ (d/transact! conn [{:card/id card-id
                              :card/name "Test Dual Land"
                              :card/cmc 0
                              :card/mana-cost {}
                              :card/colors #{}
                              :card/types #{:land}
                              :card/subtypes #{:island :swamp}
                              :card/text "{T}: Add {U} or {B}."
                              :card/abilities [{:ability/type :mana
                                                :ability/cost {:tap true}
                                                :ability/produces {:blue 1}}
                                               {:ability/type :mana
                                                :ability/cost {:tap true}
                                                :ability/produces {:black 1}}]}])
        card-eid (d/q '[:find ?e . :in $ ?cid :where [?e :card/id ?cid]] @conn card-id)
        obj-id (random-uuid)
        _ (d/transact! conn [{:object/id obj-id
                              :object/card card-eid
                              :object/zone :battlefield
                              :object/owner player-eid
                              :object/controller player-eid
                              :object/tapped false}])]
    [@conn obj-id]))


(deftest dual-land-with-source-subtype-gets-overridden-test
  (testing "dual-subtype land with island subtype gets overridden when source=:island"
    (let [db (th/create-test-db)
          [db dual-id] (add-dual-subtype-land db :player-1)
          spell-id (random-uuid)
          effect {:effect/type :change-land-types}
          source-sel (sel-core/build-selection-for-effect db :player-1 spell-id effect [])
          source-sel-confirmed (assoc source-sel :selection/selected #{:island})
          app-db {:game/db db :game/pending-selection source-sel-confirmed}
          result1 (sel-core/confirm-selection-impl app-db)
          target-sel (:game/pending-selection result1)
          target-sel-confirmed (assoc target-sel :selection/selected #{:mountain})
          app-db2 (assoc result1 :game/pending-selection target-sel-confirmed)
          result2 (sel-core/confirm-selection-impl app-db2)
          db-final (:game/db result2)
          grants-on-dual (grants/get-grants-by-type db-final dual-id :land-type-override)]
      (is (= 1 (count grants-on-dual))
          "Dual land with island subtype should receive override grant"))))
