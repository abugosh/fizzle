(ns fizzle.engine.targeting-test
  "Tests for the targeting system.

   Targeting system provides:
   - get-targeting-requirements: Extract targeting from cards/abilities
   - find-valid-targets: Find objects/players matching criteria
   - has-valid-targets?: Check if any valid target exists
   - target-still-legal?: Check if chosen target is still valid
   - all-targets-legal?: Check all targets on resolution"
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [datascript.core :as d]
    [fizzle.db.init :refer [init-game-state]]
    [fizzle.db.queries :as q]
    [fizzle.engine.targeting :as targeting]))


;; === Test Helpers ===

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


(defn add-card-to-zone
  "Add a card and create an object in specified zone.
   Returns [obj-id db] tuple."
  [db player-id card zone]
  (let [conn (d/conn-from-db db)
        player-eid (q/get-player-eid db player-id)]
    (when-not (d/q '[:find ?e .
                     :in $ ?cid
                     :where [?e :card/id ?cid]]
                   @conn (:card/id card))
      (d/transact! conn [card]))
    (let [card-eid (d/q '[:find ?e .
                          :in $ ?cid
                          :where [?e :card/id ?cid]]
                        @conn (:card/id card))
          obj-id (random-uuid)
          obj {:object/id obj-id
               :object/card card-eid
               :object/zone zone
               :object/owner player-eid
               :object/controller player-eid
               :object/tapped false}]
      (d/transact! conn [obj])
      [obj-id @conn])))


;; === Test Cards ===

(def test-sorcery
  {:card/id :test-sorcery
   :card/name "Test Sorcery"
   :card/cmc 2
   :card/mana-cost {:colorless 1 :black 1}
   :card/colors #{:black}
   :card/types #{:sorcery}
   :card/text "Test sorcery."
   :card/effects []})


(def test-instant
  {:card/id :test-instant
   :card/name "Test Instant"
   :card/cmc 1
   :card/mana-cost {:blue 1}
   :card/colors #{:blue}
   :card/types #{:instant}
   :card/text "Test instant."
   :card/effects []})


(def test-targeting-card
  "Card with targeting requirements."
  {:card/id :test-targeting-card
   :card/name "Recoup-like"
   :card/cmc 2
   :card/mana-cost {:colorless 1 :red 1}
   :card/colors #{:red}
   :card/types #{:sorcery}
   :card/text "Target sorcery card in your graveyard gains flashback."
   :card/targeting [{:target/id :graveyard-sorcery
                     :target/type :object
                     :target/zone :graveyard
                     :target/controller :self
                     :target/criteria {:card/types #{:sorcery}}
                     :target/required true}]
   :card/effects []})


(def test-player-targeting-card
  "Card with player targeting."
  {:card/id :test-player-targeting-card
   :card/name "Deep Analysis-like"
   :card/cmc 4
   :card/mana-cost {:colorless 3 :blue 1}
   :card/colors #{:blue}
   :card/types #{:sorcery}
   :card/text "Target player draws two cards."
   :card/targeting [{:target/id :player
                     :target/type :player
                     :target/options [:self :opponent :any-player]
                     :target/required true}]
   :card/effects []})


(def test-multi-target-card
  "Card with multiple targets."
  {:card/id :test-multi-target-card
   :card/name "Multi Target"
   :card/cmc 3
   :card/mana-cost {:colorless 2 :black 1}
   :card/colors #{:black}
   :card/types #{:sorcery}
   :card/text "Target creature and target player."
   :card/targeting [{:target/id :creature
                     :target/type :object
                     :target/zone :battlefield
                     :target/controller :any
                     :target/criteria {:card/types #{:creature}}
                     :target/required true}
                    {:target/id :player
                     :target/type :player
                     :target/options [:self :opponent :any-player]
                     :target/required true}]
   :card/effects []})


(def test-creature
  {:card/id :test-creature
   :card/name "Test Creature"
   :card/cmc 2
   :card/mana-cost {:green 2}
   :card/colors #{:green}
   :card/types #{:creature}
   :card/text "2/2"
   :card/effects []})


;; === get-targeting-requirements Tests ===

(deftest test_get_targeting_requirements_from_card
  (testing "get-targeting-requirements extracts :card/targeting"
    (let [reqs (targeting/get-targeting-requirements test-targeting-card)]
      (is (= 1 (count reqs))
          "Should return one targeting requirement")
      (is (= :graveyard-sorcery (:target/id (first reqs)))
          "Should have correct target id")
      (is (= :object (:target/type (first reqs)))
          "Should be object targeting"))))


(deftest test_get_targeting_requirements_from_ability
  (testing "get-targeting-requirements extracts :ability/targeting from ability"
    (let [ability {:ability/id :test-ability
                   :ability/targeting [{:target/id :player
                                        :target/type :player
                                        :target/options [:any-player]
                                        :target/required true}]}
          reqs (targeting/get-targeting-requirements ability)]
      (is (= 1 (count reqs))
          "Should return one targeting requirement")
      (is (= :player (:target/type (first reqs)))
          "Should be player targeting"))))


(deftest test_get_targeting_requirements_no_targeting
  (testing "get-targeting-requirements returns empty vector when no targeting"
    (let [reqs (targeting/get-targeting-requirements test-sorcery)]
      (is (= [] reqs)
          "Should return empty vector for card without targeting"))))


;; === find-valid-targets Tests ===

(deftest test_find_valid_targets_sorcery_in_graveyard
  (testing "find-valid-targets finds matching sorcery in graveyard"
    (let [db (init-game-state)
          [obj-id db] (add-card-to-zone db :player-1 test-sorcery :graveyard)
          target-req {:target/id :graveyard-sorcery
                      :target/type :object
                      :target/zone :graveyard
                      :target/controller :self
                      :target/criteria {:card/types #{:sorcery}}}
          targets (targeting/find-valid-targets db :player-1 target-req)]
      (is (= 1 (count targets))
          "Should find one valid target")
      (is (= obj-id (first targets))
          "Should return the sorcery's object ID"))))


(deftest test_find_valid_targets_empty_graveyard
  (testing "find-valid-targets returns empty vector for empty zone"
    (let [db (init-game-state)
          target-req {:target/id :graveyard-sorcery
                      :target/type :object
                      :target/zone :graveyard
                      :target/controller :self
                      :target/criteria {:card/types #{:sorcery}}}
          targets (targeting/find-valid-targets db :player-1 target-req)]
      (is (= [] targets)
          "Should return empty vector when graveyard is empty"))))


(deftest test_find_valid_targets_no_matching_type
  (testing "find-valid-targets returns empty when no cards match type"
    (let [db (init-game-state)
          ;; Add an instant to graveyard, but targeting requires sorcery
          [_obj-id db] (add-card-to-zone db :player-1 test-instant :graveyard)
          target-req {:target/id :graveyard-sorcery
                      :target/type :object
                      :target/zone :graveyard
                      :target/controller :self
                      :target/criteria {:card/types #{:sorcery}}}
          targets (targeting/find-valid-targets db :player-1 target-req)]
      (is (= [] targets)
          "Should return empty vector when no cards match criteria"))))


(deftest test_find_valid_targets_player_any
  (testing "find-valid-targets returns all players for :any-player"
    (let [db (-> (init-game-state)
                 (add-opponent))
          target-req {:target/id :player
                      :target/type :player
                      :target/options [:self :opponent :any-player]}
          targets (targeting/find-valid-targets db :player-1 target-req)]
      (is (= 2 (count targets))
          "Should return both players")
      (is (contains? (set targets) :player-1)
          "Should include self")
      (is (contains? (set targets) :player-2)
          "Should include opponent"))))


(deftest test_find_valid_targets_player_self
  (testing "find-valid-targets returns only self for :self option"
    (let [db (init-game-state)
          target-req {:target/id :player
                      :target/type :player
                      :target/options [:self]}
          targets (targeting/find-valid-targets db :player-1 target-req)]
      (is (= 1 (count targets))
          "Should return only one player")
      (is (= :player-1 (first targets))
          "Should return caster"))))


;; === has-valid-targets? Tests ===

(deftest test_has_valid_targets_true
  (testing "has-valid-targets? returns true when valid target exists"
    (let [db (init-game-state)
          [_obj-id db] (add-card-to-zone db :player-1 test-sorcery :graveyard)]
      (is (targeting/has-valid-targets? db :player-1 test-targeting-card)
          "Should return true when sorcery is in graveyard"))))


(deftest test_has_valid_targets_false_empty_zone
  (testing "has-valid-targets? returns false when zone is empty"
    (let [db (init-game-state)]
      (is (not (targeting/has-valid-targets? db :player-1 test-targeting-card))
          "Should return false when graveyard is empty"))))


(deftest test_has_valid_targets_false_no_match
  (testing "has-valid-targets? returns false when cards exist but don't match"
    (let [db (init-game-state)
          [_obj-id db] (add-card-to-zone db :player-1 test-instant :graveyard)]
      (is (not (targeting/has-valid-targets? db :player-1 test-targeting-card))
          "Should return false when only instant is in graveyard (requires sorcery)"))))


;; === target-still-legal? Tests ===

(deftest test_target_still_legal_in_zone
  (testing "target-still-legal? returns true when target still in correct zone"
    (let [db (init-game-state)
          [obj-id db] (add-card-to-zone db :player-1 test-sorcery :graveyard)
          target-req {:target/id :graveyard-sorcery
                      :target/type :object
                      :target/zone :graveyard
                      :target/controller :self
                      :target/criteria {:card/types #{:sorcery}}}]
      (is (targeting/target-still-legal? db obj-id target-req)
          "Target should still be legal when in graveyard"))))


(deftest test_target_still_legal_moved_zone
  (testing "target-still-legal? returns false when target moved to different zone"
    (let [db (init-game-state)
          [obj-id db] (add-card-to-zone db :player-1 test-sorcery :graveyard)
          ;; Move the object to exile
          obj-eid (d/q '[:find ?e . :in $ ?oid :where [?e :object/id ?oid]] db obj-id)
          db (d/db-with db [[:db/add obj-eid :object/zone :exile]])
          target-req {:target/id :graveyard-sorcery
                      :target/type :object
                      :target/zone :graveyard
                      :target/controller :self
                      :target/criteria {:card/types #{:sorcery}}}]
      (is (not (targeting/target-still-legal? db obj-id target-req))
          "Target should be illegal after moving to exile"))))


(deftest test_target_still_legal_object_not_found
  (testing "target-still-legal? returns false when target doesn't exist"
    (let [db (init-game-state)
          fake-id (random-uuid)
          target-req {:target/id :graveyard-sorcery
                      :target/type :object
                      :target/zone :graveyard
                      :target/controller :self
                      :target/criteria {:card/types #{:sorcery}}}]
      (is (not (targeting/target-still-legal? db fake-id target-req))
          "Target should be illegal when object doesn't exist"))))


(deftest test_target_still_legal_player
  (testing "target-still-legal? returns true for player targets"
    (let [db (init-game-state)
          target-req {:target/id :player
                      :target/type :player
                      :target/options [:any-player]}]
      (is (targeting/target-still-legal? db :player-1 target-req)
          "Player targets are always legal (players don't move zones)"))))


;; === all-targets-legal? Tests ===

(deftest test_all_targets_legal_all_valid
  (testing "all-targets-legal? returns true when all targets still legal"
    (let [db (init-game-state)
          [sorcery-id db] (add-card-to-zone db :player-1 test-sorcery :graveyard)
          targets {:graveyard-sorcery sorcery-id}
          requirements (targeting/get-targeting-requirements test-targeting-card)]
      (is (targeting/all-targets-legal? db targets requirements)
          "All targets should be legal when sorcery still in graveyard"))))


(deftest test_all_targets_legal_one_invalid
  (testing "all-targets-legal? returns false when one target invalid"
    (let [db (init-game-state)
          [sorcery-id db] (add-card-to-zone db :player-1 test-sorcery :graveyard)
          targets {:graveyard-sorcery sorcery-id}
          requirements (targeting/get-targeting-requirements test-targeting-card)
          ;; Move the target to exile
          sorcery-eid (d/q '[:find ?e . :in $ ?oid :where [?e :object/id ?oid]] db sorcery-id)
          db (d/db-with db [[:db/add sorcery-eid :object/zone :exile]])]
      (is (not (targeting/all-targets-legal? db targets requirements))
          "Should return false when target moved to exile"))))


(deftest test_all_targets_legal_no_targets
  (testing "all-targets-legal? returns true for spell without targets"
    (let [db (init-game-state)]
      (is (targeting/all-targets-legal? db nil [])
          "Spell without targets should return true"))))


(deftest test_all_targets_legal_player_target
  (testing "all-targets-legal? returns true for player-targeted spell"
    (let [db (init-game-state)
          targets {:player :player-1}
          requirements (targeting/get-targeting-requirements test-player-targeting-card)]
      (is (targeting/all-targets-legal? db targets requirements)
          "Player target should always be legal"))))


;; === Target Zone Validation Tests ===

(deftest test-target-wrong-zone-rejected
  ;; Bug caught: Accepting targets from any zone instead of specified zone
  (testing "Targeting rejects objects in wrong zone"
    (let [db (init-game-state)
          ;; Add sorcery to LIBRARY (not graveyard where targeting expects it)
          [obj-id db] (add-card-to-zone db :player-1 test-sorcery :library)
          ;; Target requirement expects graveyard
          target-req {:target/id :graveyard-sorcery
                      :target/type :object
                      :target/zone :graveyard  ; Expects graveyard
                      :target/controller :self
                      :target/criteria {:card/types #{:sorcery}}}]
      ;; Object should NOT be found as valid target (wrong zone)
      (is (= [] (targeting/find-valid-targets db :player-1 target-req))
          "Object in wrong zone should not be found as valid target")
      ;; target-still-legal? should return false for object in wrong zone
      (is (false? (targeting/target-still-legal? db obj-id target-req))
          "Object in wrong zone should not be legal target"))))


;; =====================================================
;; Corner Case Tests: :opponent player targeting
;; =====================================================

(deftest test-find-valid-targets-player-opponent-only
  (testing "find-valid-targets with :opponent option returns only opponent"
    (let [db (-> (init-game-state)
                 (add-opponent))
          target-req {:target/id :player
                      :target/type :player
                      :target/options [:opponent]}
          targets (targeting/find-valid-targets db :player-1 target-req)]
      (is (= 1 (count targets))
          "Should return only one player (opponent)")
      (is (= :player-2 (first targets))
          "Should return opponent, not self"))))


(deftest test-find-valid-targets-player-opponent-no-opponent
  (testing "find-valid-targets with :opponent returns nil when no opponent exists"
    (let [db (init-game-state)  ; No opponent added
          target-req {:target/id :player
                      :target/type :player
                      :target/options [:opponent]}
          targets (targeting/find-valid-targets db :player-1 target-req)]
      ;; When no opponent exists, get-opponent-id returns nil
      ;; find-valid-targets returns [nil] since the option matches
      (is (= [nil] targets)
          "Should return [nil] when no opponent exists"))))
