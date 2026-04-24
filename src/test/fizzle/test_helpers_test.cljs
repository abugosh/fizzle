(ns fizzle.test-helpers-test
  "Tests for shared test helpers."
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [datascript.core :as d]
    [fizzle.db.queries :as q]
    [fizzle.engine.zones :as zones]
    [fizzle.events.selection.core :as sel-core]
    [fizzle.test-helpers :as th]))


;; Register a no-op domain policy for confirm-selection validation tests.
;; Tests that verify validation pass/throw need :selection/mechanism and
;; :selection/domain so execute-confirmed-selection can dispatch to apply-domain-policy.
(defmethod sel-core/apply-domain-policy :test-helpers-noop
  [game-db _selection]
  {:db game-db})


(deftest create-test-db-returns-valid-db-test
  (testing "create-test-db returns db with player entity, game entity, card definitions"
    (let [db (th/create-test-db)]
      ;; Player entity exists
      (is (pos-int? (q/get-player-eid db :player-1))
          "Player :player-1 should exist")
      ;; Game entity exists
      (is (map? (q/get-game-state db))
          "Game state should exist")
      ;; Card definitions loaded
      (is (pos-int? (d/q '[:find ?e .
                           :where [?e :card/id :dark-ritual]]
                         db))
          "Dark Ritual card def should be loaded")
      ;; Default mana pool is all zeros
      (let [pool (q/get-mana-pool db :player-1)]
        (is (= 0 (:black pool)))
        (is (= 0 (:blue pool)))
        (is (= 0 (:red pool)))))))


(deftest create-test-db-with-mana-opts-test
  (testing "create-test-db with mana opts sets specified colors, others zero"
    (let [db (th/create-test-db {:mana {:blue 3}})]
      (is (= 3 (:blue (q/get-mana-pool db :player-1))))
      (is (= 0 (:black (q/get-mana-pool db :player-1))))
      (is (= 0 (:red (q/get-mana-pool db :player-1)))))))


(deftest create-test-db-with-life-opts-test
  (testing "create-test-db with life opts sets life total"
    (let [db (th/create-test-db {:life 10})]
      (is (= 10 (q/get-life-total db :player-1))))))


(deftest add-card-to-zone-hand-test
  (testing "add-card-to-zone adds card to hand"
    (let [db (th/create-test-db)
          [db' obj-id] (th/add-card-to-zone db :dark-ritual :hand :player-1)]
      (is (= 1 (th/get-zone-count db' :hand :player-1)))
      (is (uuid? obj-id)))))


(deftest add-card-to-zone-library-sets-position-test
  (testing "add-card-to-zone for library sets :object/position 0"
    (let [db (th/create-test-db)
          [db' obj-id] (th/add-card-to-zone db :dark-ritual :library :player-1)
          obj (q/get-object db' obj-id)]
      (is (= 0 (:object/position obj))))))


(deftest add-card-to-zone-invalid-card-id-test
  (testing "add-card-to-zone with invalid card-id throws"
    (let [db (th/create-test-db)]
      (is (thrown-with-msg? js/Error #"Unknown card-id"
            (th/add-card-to-zone db :nonexistent-card :hand :player-1))))))


(deftest add-cards-to-library-ordering-test
  (testing "add-cards-to-library sets positions 0, 1, 2"
    (let [db (th/create-test-db)
          [db' obj-ids] (th/add-cards-to-library db
                                                 [:dark-ritual :dark-ritual :dark-ritual]
                                                 :player-1)]
      (is (= 3 (count obj-ids)))
      (is (= 0 (:object/position (q/get-object db' (nth obj-ids 0)))))
      (is (= 1 (:object/position (q/get-object db' (nth obj-ids 1)))))
      (is (= 2 (:object/position (q/get-object db' (nth obj-ids 2))))))))


(deftest add-cards-to-library-empty-test
  (testing "add-cards-to-library with empty returns [db []] unchanged"
    (let [db (th/create-test-db)
          [db' obj-ids] (th/add-cards-to-library db [] :player-1)]
      (is (= [] obj-ids))
      (is (= db db')))))


(deftest add-cards-to-graveyard-multiple-test
  (testing "add-cards-to-graveyard adds multiple cards"
    (let [db (th/create-test-db)
          [db' obj-ids] (th/add-cards-to-graveyard db
                                                   [:dark-ritual :dark-ritual :dark-ritual]
                                                   :player-1)]
      (is (= 3 (count obj-ids)))
      (is (= 3 (th/get-zone-count db' :graveyard :player-1))))))


(deftest get-zone-count-empty-test
  (testing "get-zone-count returns 0 when no cards in zone"
    (let [db (th/create-test-db)]
      (is (= 0 (th/get-zone-count db :hand :player-1)))
      (is (= 0 (th/get-zone-count db :graveyard :player-1))))))


(deftest add-opponent-creates-player-2-test
  (testing "add-opponent creates :player-2 with is-opponent true"
    (let [db (th/create-test-db)
          db' (th/add-opponent db)]
      (is (pos-int? (q/get-player-eid db' :player-2))
          "Player :player-2 should exist")
      (let [player-eid (q/get-player-eid db' :player-2)
            player (d/pull db' '[:player/is-opponent :player/life] player-eid)]
        (is (true? (:player/is-opponent player)))
        (is (= 20 (:player/life player)))))))


(deftest add-opponent-with-bot-archetype-test
  (testing "add-opponent with :bot-archetype sets the archetype"
    (let [db (th/create-test-db)
          db' (th/add-opponent db {:bot-archetype :goldfish})]
      (let [player-eid (q/get-player-eid db' :player-2)
            player (d/pull db' '[:player/bot-archetype :player/is-opponent] player-eid)]
        (is (= :goldfish (:player/bot-archetype player)))
        (is (true? (:player/is-opponent player)))))))


(deftest add-opponent-with-stops-test
  (testing "add-opponent with :stops sets the stops"
    (let [db (th/create-test-db)
          db' (th/add-opponent db {:stops #{:main1 :end}})]
      (let [player-eid (q/get-player-eid db' :player-2)
            player (d/pull db' '[:player/stops] player-eid)]
        (is (= #{:main1 :end} (:player/stops player)))))))


(deftest add-opponent-zero-arity-backward-compat-test
  (testing "add-opponent 0-arity still works without opts"
    (let [db (th/create-test-db)
          db' (th/add-opponent db)]
      (let [player-eid (q/get-player-eid db' :player-2)
            player (d/pull db' '[:player/bot-archetype :player/stops] player-eid)]
        (is (nil? (:player/bot-archetype player)))
        (is (nil? (:player/stops player)))))))


(deftest create-test-db-with-stops-test
  (testing "create-test-db with :stops applies stops to player-1"
    (let [db (th/create-test-db {:stops #{:main1 :main2 :combat}})]
      (let [player-eid (q/get-player-eid db :player-1)
            player (d/pull db '[:player/stops] player-eid)]
        (is (= #{:main1 :main2 :combat} (:player/stops player)))))))


(deftest create-game-scenario-returns-app-db-test
  (testing "create-game-scenario returns app-db with :game/db and history keys"
    (let [app-db (th/create-game-scenario {})]
      (is (some? (:game/db app-db)) "should have :game/db key")
      (is (vector? (:history/main app-db)) "should have :history/main")
      (is (map? (:history/forks app-db)) "should have :history/forks")
      (is (= -1 (:history/position app-db)) "history position should be -1"))))


(deftest create-game-scenario-both-players-exist-test
  (testing "create-game-scenario creates both player-1 and player-2"
    (let [app-db (th/create-game-scenario {})
          db (:game/db app-db)]
      (is (pos-int? (q/get-player-eid db :player-1)) "player-1 should exist")
      (is (pos-int? (q/get-player-eid db :player-2)) "player-2 should exist"))))


(deftest create-game-scenario-both-players-have-library-test
  (testing "create-game-scenario gives both players default 10 library cards"
    (let [app-db (th/create-game-scenario {})
          db (:game/db app-db)]
      (is (= 10 (th/get-zone-count db :library :player-1))
          "player-1 should have 10 library cards")
      (is (= 10 (th/get-zone-count db :library :player-2))
          "player-2 should have 10 library cards"))))


(deftest create-game-scenario-default-stops-test
  (testing "create-game-scenario sets default stops #{:main1 :main2} for human"
    (let [app-db (th/create-game-scenario {})
          db (:game/db app-db)
          player-eid (q/get-player-eid db :player-1)
          player (d/pull db '[:player/stops] player-eid)]
      (is (= #{:main1 :main2} (:player/stops player))))))


(deftest create-game-scenario-custom-library-count-test
  (testing "create-game-scenario respects :human-library and :bot-library opts"
    (let [app-db (th/create-game-scenario {:human-library 5 :bot-library 3})
          db (:game/db app-db)]
      (is (= 5 (th/get-zone-count db :library :player-1)))
      (is (= 3 (th/get-zone-count db :library :player-2))))))


(deftest create-game-scenario-bot-archetype-test
  (testing "create-game-scenario sets bot-archetype on player-2 (default :goldfish)"
    (let [app-db (th/create-game-scenario {})
          db (:game/db app-db)
          player-eid (q/get-player-eid db :player-2)
          player (d/pull db '[:player/bot-archetype] player-eid)]
      (is (= :goldfish (:player/bot-archetype player)))))
  (testing "create-game-scenario sets explicit bot-archetype when given"
    (let [app-db (th/create-game-scenario {:bot-archetype :goldfish})
          db (:game/db app-db)
          player-eid (q/get-player-eid db :player-2)
          player (d/pull db '[:player/bot-archetype] player-eid)]
      (is (= :goldfish (:player/bot-archetype player))))))


(deftest create-game-scenario-passes-mana-and-life-test
  (testing "create-game-scenario passes mana and life opts to human player"
    (let [app-db (th/create-game-scenario {:mana {:blue 2} :life 15})
          db (:game/db app-db)]
      (is (= 2 (:blue (q/get-mana-pool db :player-1))))
      (is (= 15 (q/get-life-total db :player-1))))))


;; =====================================================
;; confirm-selection validation tests
;; =====================================================

(deftest confirm-selection-throws-on-wrong-count-test
  (testing "confirm-selection throws when selected count doesn't match :selection/select-count"
    (let [db (th/create-test-db)
          sel {:selection/validation :exact
               :selection/select-count 1
               :selection/candidates #{:a :b :c}}]
      ;; Pass 2 items when :exact 1 expected
      (is (thrown-with-msg? js/Error #"confirm-selection: validation failed"
            (th/confirm-selection db sel #{:a :b}))
          "Should throw when count(selected) != select-count"))))


(deftest confirm-selection-throws-on-items-not-in-candidates-test
  (testing "confirm-selection throws when selected items are not in :selection/candidates"
    (let [db (th/create-test-db)
          sel {:selection/validation :exact
               :selection/select-count 1
               :selection/candidates #{:a :b}}]
      ;; :x not in candidates
      (is (thrown-with-msg? js/Error #"confirm-selection: validation failed"
            (th/confirm-selection db sel #{:x}))
          "Should throw when items not in candidates"))))


(deftest confirm-selection-throws-on-nil-validation-type-test
  (testing "confirm-selection throws when :selection/validation is nil (builder bug)"
    (let [db (th/create-test-db)
          sel {:selection/candidates #{:a :b}}]
      ;; Missing :selection/validation — nil defaults to reject
      (is (thrown-with-msg? js/Error #"confirm-selection: validation failed"
            (th/confirm-selection db sel #{:a}))
          "Should throw when :selection/validation is nil"))))


(deftest confirm-selection-passes-valid-exact-selection-test
  (testing "confirm-selection works for valid :exact selection with correct count and candidates"
    (let [db (th/create-test-db)
          sel {:selection/mechanism :pick-from-zone
               :selection/domain :test-helpers-noop
               :selection/validation :exact
               :selection/select-count 1
               :selection/candidates #{:a :b}
               :selection/lifecycle :finalized}]
      ;; :a is in candidates and count matches — verify {:db ...} shape returned (no throw)
      (is (some? (:db (th/confirm-selection db sel #{:a})))
          "Should return map with :db key for valid selection"))))


(deftest confirm-selection-passes-always-validation-test
  (testing "confirm-selection works for :always validation type (no candidate/count check)"
    (let [db (th/create-test-db)
          sel {:selection/mechanism :pick-from-zone
               :selection/domain :test-helpers-noop
               :selection/validation :always
               :selection/lifecycle :finalized}]
      ;; :always validation never fails — verify {:db ...} shape returned (no throw)
      (is (some? (:db (th/confirm-selection db sel #{:a :b :c})))
          "Should return map with :db key for :always validation"))))


;; =====================================================
;; cast-with-target routing tests
;; =====================================================

(deftest cast-with-target-routes-through-pipeline-test
  (testing "cast-with-target puts targeted spell on stack with target assigned"
    (let [db (th/create-test-db {:mana {:red 1}})
          db (th/add-opponent db)
          [db bolt-id] (th/add-card-to-zone db :lightning-bolt :hand :player-1)
          ;; Cast via cast-with-target — should route through cast-spell-handler pipeline
          db-cast (th/cast-with-target db :player-1 bolt-id :player-2)
          top-item (q/get-top-stack-item db-cast)]
      (is (some? top-item)
          "Spell should be on stack after cast-with-target")
      (is (= :player-2 (get (:stack-item/targets top-item) :target))
          "Target :player-2 should be stored on stack-item"))))


(deftest cast-with-target-throws-on-can-cast-false-test
  (testing "cast-with-target throws assertion when can-cast? returns false"
    (let [db (th/create-test-db {:mana {:red 0}})
          db (th/add-opponent db)
          [db bolt-id] (th/add-card-to-zone db :lightning-bolt :hand :player-1)]
      ;; No mana — can-cast? false → should throw
      (is (thrown-with-msg? js/Error #"can-cast\? returned false"
            (th/cast-with-target db :player-1 bolt-id :player-2))
          "Should throw when can-cast? is false"))))


(deftest cast-with-target-throws-on-pre-cast-cost-selection-test
  (testing "cast-with-target throws when spell has pre-cast cost requiring selection (e.g. Fling sacrifice)"
    (let [db (th/create-test-db {:mana {:red 1 :colorless 1}})
          db (th/add-opponent db)
          [db _creature-id] (th/add-card-to-zone db :nimble-mongoose :battlefield :player-1)
          [db fling-id] (th/add-card-to-zone db :fling :hand :player-1)]
      ;; Fling has a mandatory sacrifice-permanent pre-cast cost
      ;; cast-spell-handler will return a pending-selection for sacrifice BEFORE targeting
      ;; cast-with-target should throw fail-fast on detecting this
      (is (thrown-with-msg? js/Error #"pre-cast costs"
            (th/cast-with-target db :player-1 fling-id :player-2))
          "Should throw fail-fast when spell has pre-cast cost selection"))))


(deftest cast-with-target-throws-on-invalid-target-test
  (testing "cast-with-target throws when given target is not a valid target"
    (let [db (th/create-test-db {:mana {:red 1}})
          db (th/add-opponent db)
          [db bolt-id] (th/add-card-to-zone db :lightning-bolt :hand :player-1)]
      ;; :not-a-real-player is not a valid target for Lightning Bolt
      (is (thrown-with-msg? js/Error #"not in valid targets"
            (th/cast-with-target db :player-1 bolt-id :not-a-real-player))
          "Should throw when target is not in valid targets"))))


(deftest cast-with-target-throws-on-modal-spell-test
  (testing "cast-with-target throws with 'mode selection' message when given a modal spell"
    (let [db (th/create-test-db {:mana {:blue 1}})
          ;; Vision Charm is modal (3 modes: mill, land-type change, phase-out artifact)
          ;; Mode 2 has no targeting so can-cast? returns true without any targets set up
          [db charm-id] (th/add-card-to-zone db :vision-charm :hand :player-1)]
      ;; Modal spells must use cast-mode-with-target, not cast-with-target
      (is (thrown-with-msg? js/Error #"mode selection"
            (th/cast-with-target db :player-1 charm-id :player-1))
          "Should throw 'mode selection' for modal spell"))))


;; =====================================================
;; Creature P/T in all zones tests (fizzle-pb06)
;; =====================================================

(deftest add-card-to-zone-creature-in-graveyard-has-pt-test
  (testing "add-card-to-zone sets :object/power and :object/toughness for creatures in graveyard"
    (let [db (th/create-test-db)
          [db' obj-id] (th/add-card-to-zone db :nimble-mongoose :graveyard :player-1)
          obj (q/get-object db' obj-id)]
      (is (= 1 (:object/power obj))
          "Creature in graveyard should have :object/power")
      (is (= 1 (:object/toughness obj))
          "Creature in graveyard should have :object/toughness"))))


(deftest add-card-to-zone-creature-in-hand-has-pt-test
  (testing "add-card-to-zone sets :object/power and :object/toughness for creatures in hand"
    (let [db (th/create-test-db)
          [db' obj-id] (th/add-card-to-zone db :nimble-mongoose :hand :player-1)
          obj (q/get-object db' obj-id)]
      (is (= 1 (:object/power obj))
          "Creature in hand should have :object/power")
      (is (= 1 (:object/toughness obj))
          "Creature in hand should have :object/toughness"))))


(deftest add-card-to-zone-non-creature-in-graveyard-no-pt-test
  (testing "add-card-to-zone does NOT set :object/power for non-creature cards"
    (let [db (th/create-test-db)
          [db' obj-id] (th/add-card-to-zone db :dark-ritual :graveyard :player-1)
          obj (q/get-object db' obj-id)]
      (is (nil? (:object/power obj))
          "Non-creature should NOT have :object/power")
      (is (nil? (:object/toughness obj))
          "Non-creature should NOT have :object/toughness"))))


(deftest add-cards-to-library-creature-has-pt-test
  (testing "add-cards-to-library sets :object/power and :object/toughness for creature cards"
    (let [db (th/create-test-db)
          [db' obj-ids] (th/add-cards-to-library db [:nimble-mongoose] :player-1)
          obj (q/get-object db' (first obj-ids))]
      (is (= 1 (:object/power obj))
          "Creature in library should have :object/power")
      (is (= 1 (:object/toughness obj))
          "Creature in library should have :object/toughness"))))


(deftest add-cards-to-graveyard-creature-has-pt-test
  (testing "add-cards-to-graveyard sets :object/power and :object/toughness for creature cards"
    (let [db (th/create-test-db)
          [db' obj-ids] (th/add-cards-to-graveyard db [:nimble-mongoose] :player-1)
          obj (q/get-object db' (first obj-ids))]
      (is (= 1 (:object/power obj))
          "Creature in graveyard (bulk) should have :object/power")
      (is (= 1 (:object/toughness obj))
          "Creature in graveyard (bulk) should have :object/toughness"))))


(deftest creature-leaving-battlefield-resets-pt-to-base-test
  (testing "creature leaving battlefield resets P/T to base values, not nil"
    (let [db (th/create-test-db)
          [db' obj-id] (th/add-card-to-zone db :nimble-mongoose :battlefield :player-1)
          ;; Move to graveyard (simulates creature dying)
          db-after (zones/move-to-zone* db' obj-id :graveyard)
          obj (q/get-object db-after obj-id)]
      (is (= 1 (:object/power obj))
          "Creature after leaving battlefield should have reset :object/power (not nil)")
      (is (= 1 (:object/toughness obj))
          "Creature after leaving battlefield should have reset :object/toughness (not nil)"))))


(deftest creature-entering-battlefield-keeps-existing-pt-test
  (testing "creature entering battlefield keeps pre-existing P/T, adds summoning-sick and damage-marked"
    (let [db (th/create-test-db)
          [db' obj-id] (th/add-card-to-zone db :nimble-mongoose :hand :player-1)
          ;; Move to battlefield from hand (ETB)
          db-after (zones/move-to-zone* db' obj-id :battlefield)
          obj (q/get-object db-after obj-id)]
      (is (= 1 (:object/power obj))
          "Creature on battlefield should have :object/power")
      (is (= 1 (:object/toughness obj))
          "Creature on battlefield should have :object/toughness")
      (is (true? (:object/summoning-sick obj))
          "Creature entering battlefield should have summoning-sick")
      (is (= 0 (:object/damage-marked obj))
          "Creature entering battlefield should have damage-marked 0"))))


;; =====================================================
;; cast-and-resolve defensive assert tests (fizzle-497a)
;; =====================================================

(deftest cast-and-resolve-throws-on-targeted-spell-test
  (testing "cast-and-resolve throws for a spell with targeting requirements (use cast-with-target instead)"
    (let [db (th/create-test-db {:mana {:red 1}})
          db (th/add-opponent db)
          [db bolt-id] (th/add-card-to-zone db :lightning-bolt :hand :player-1)]
      ;; Lightning Bolt requires a target — cast-and-resolve should reject it fail-fast
      (is (thrown-with-msg? js/Error #"targeting"
            (th/cast-and-resolve db :player-1 bolt-id))
          "Should throw for spell with targeting requirements"))))


(deftest cast-and-resolve-works-for-simple-spell-test
  (testing "cast-and-resolve succeeds for a spell with no pre-cast costs (e.g. Dark Ritual)"
    (let [db (th/create-test-db {:mana {:black 1}})
          [db ritual-id] (th/add-card-to-zone db :dark-ritual :hand :player-1)
          result-db (th/cast-and-resolve db :player-1 ritual-id)]
      ;; Dark Ritual adds 3 black mana — verify the mana pool changed
      (is (= 3 (:black (q/get-mana-pool result-db :player-1)))
          "Dark Ritual should add 3 black mana to mana pool"))))
