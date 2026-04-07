(ns fizzle.engine.objects-test
  "Tests for engine/objects.cljs build-object-tx chokepoint.

   These tests verify that build-object-tx produces structurally correct
   Datascript tx maps for game objects, and that the canary shapes match
   what init.cljs produces."
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [datascript.core :as d]
    [fizzle.db.queries :as q]
    [fizzle.db.schema :refer [schema]]
    [fizzle.engine.cards :as cards]
    [fizzle.engine.objects :as objects]
    [fizzle.events.init :as init]))


(defn- get-card-eid
  [db card-id]
  (d/q '[:find ?e . :in $ ?cid :where [?e :card/id ?cid]] db card-id))


;; === Unit tests for build-object-tx ===

(deftest test-non-creature-has-no-pt
  (testing "non-creature card (dark-ritual) tx has NO :object/power or :object/toughness"
    (let [conn (d/create-conn schema)
          _ (d/transact! conn cards/all-cards)
          db @conn
          card-eid (get-card-eid db :dark-ritual)
          card-data (d/pull db [:card/types :card/power :card/toughness] card-eid)
          owner-eid 99
          tx (objects/build-object-tx card-eid card-data :hand owner-eid 0)]
      (is (nil? (:object/power tx))
          "Non-creature should not have :object/power")
      (is (nil? (:object/toughness tx))
          "Non-creature should not have :object/toughness"))))


(deftest test-creature-has-pt
  (testing "creature card (xantid-swarm) tx has correct :object/power and :object/toughness"
    (let [conn (d/create-conn schema)
          _ (d/transact! conn cards/all-cards)
          db @conn
          card-eid (get-card-eid db :xantid-swarm)
          card-data (d/pull db [:card/types :card/power :card/toughness] card-eid)
          owner-eid 99
          tx (objects/build-object-tx card-eid card-data :hand owner-eid 0)]
      (is (= 0 (:object/power tx))
          "Xantid Swarm has power 0")
      (is (= 1 (:object/toughness tx))
          "Xantid Swarm has toughness 1"))))


(deftest test-required-fields-always-present
  (testing "all objects get required base fields"
    (let [conn (d/create-conn schema)
          _ (d/transact! conn cards/all-cards)
          db @conn
          card-eid (get-card-eid db :dark-ritual)
          card-data (d/pull db [:card/types :card/power :card/toughness] card-eid)
          owner-eid 99
          tx (objects/build-object-tx card-eid card-data :hand owner-eid 0)]
      (is (uuid? (:object/id tx))
          "Object must have a UUID :object/id")
      (is (= card-eid (:object/card tx))
          "Object must reference card-eid via :object/card")
      (is (= :hand (:object/zone tx))
          "Object must have :object/zone")
      (is (= owner-eid (:object/owner tx))
          "Object must have :object/owner")
      (is (= owner-eid (:object/controller tx))
          "Object must default :object/controller to owner-eid")
      (is (= false (:object/tapped tx))
          "Object must start untapped")
      (is (= 0 (:object/position tx))
          "Object must have :object/position"))))


(deftest test-library-zone-uses-provided-position
  (testing "position parameter is used as :object/position"
    (let [conn (d/create-conn schema)
          _ (d/transact! conn cards/all-cards)
          db @conn
          card-eid (get-card-eid db :dark-ritual)
          card-data (d/pull db [:card/types :card/power :card/toughness] card-eid)
          owner-eid 99
          tx (objects/build-object-tx card-eid card-data :library owner-eid 42)]
      (is (= 42 (:object/position tx))
          "Position 42 should be stored as :object/position"))))


(deftest test-non-library-position-zero
  (testing "non-library zones use position 0 when 0 passed"
    (let [conn (d/create-conn schema)
          _ (d/transact! conn cards/all-cards)
          db @conn
          card-eid (get-card-eid db :dark-ritual)
          card-data (d/pull db [:card/types :card/power :card/toughness] card-eid)
          owner-eid 99
          tx (objects/build-object-tx card-eid card-data :hand owner-eid 0)]
      (is (= 0 (:object/position tx))
          "Hand position should be 0"))))


(deftest test-battlefield-no-summoning-sick-or-damage
  (testing "battlefield zone does NOT add summoning-sick or damage-marked (zone-transition concern)"
    (let [conn (d/create-conn schema)
          _ (d/transact! conn cards/all-cards)
          db @conn
          card-eid (get-card-eid db :xantid-swarm)
          card-data (d/pull db [:card/types :card/power :card/toughness] card-eid)
          owner-eid 99
          tx (objects/build-object-tx card-eid card-data :battlefield owner-eid 0)]
      (is (nil? (:object/summoning-sick tx))
          "build-object-tx must NOT set summoning-sick (zones.cljs responsibility)")
      (is (nil? (:object/damage-marked tx))
          "build-object-tx must NOT set damage-marked (zones.cljs responsibility)"))))


(deftest test-creature-in-hand-has-pt
  (testing "creature card in :hand zone has P/T (P/T is zone-independent)"
    (let [conn (d/create-conn schema)
          _ (d/transact! conn cards/all-cards)
          db @conn
          card-eid (get-card-eid db :xantid-swarm)
          card-data (d/pull db [:card/types :card/power :card/toughness] card-eid)
          owner-eid 99
          tx (objects/build-object-tx card-eid card-data :hand owner-eid 0)]
      (is (= 0 (:object/power tx)))
      (is (= 1 (:object/toughness tx))))))


(deftest test-creature-in-graveyard-has-pt
  (testing "creature card in :graveyard zone has P/T"
    (let [conn (d/create-conn schema)
          _ (d/transact! conn cards/all-cards)
          db @conn
          card-eid (get-card-eid db :xantid-swarm)
          card-data (d/pull db [:card/types :card/power :card/toughness] card-eid)
          owner-eid 99
          tx (objects/build-object-tx card-eid card-data :graveyard owner-eid 0)]
      (is (= 0 (:object/power tx)))
      (is (= 1 (:object/toughness tx))))))


(deftest test-custom-uuid-used-when-provided
  (testing "passing :id option uses provided UUID"
    (let [conn (d/create-conn schema)
          _ (d/transact! conn cards/all-cards)
          db @conn
          card-eid (get-card-eid db :dark-ritual)
          card-data (d/pull db [:card/types :card/power :card/toughness] card-eid)
          owner-eid 99
          my-uuid (random-uuid)
          tx (objects/build-object-tx card-eid card-data :hand owner-eid 0 :id my-uuid)]
      (is (= my-uuid (:object/id tx))
          "Provided UUID must be used as :object/id"))))


(deftest test-default-uuid-generated-when-omitted
  (testing "omitting :id generates a fresh random-uuid"
    (let [conn (d/create-conn schema)
          _ (d/transact! conn cards/all-cards)
          db @conn
          card-eid (get-card-eid db :dark-ritual)
          card-data (d/pull db [:card/types :card/power :card/toughness] card-eid)
          owner-eid 99
          tx (objects/build-object-tx card-eid card-data :hand owner-eid 0)]
      (is (uuid? (:object/id tx))
          "Generated UUID should be a uuid"))))


(deftest test-controller-override
  (testing "passing :controller option overrides the default owner-eid controller"
    (let [conn (d/create-conn schema)
          _ (d/transact! conn cards/all-cards)
          db @conn
          card-eid (get-card-eid db :dark-ritual)
          card-data (d/pull db [:card/types :card/power :card/toughness] card-eid)
          owner-eid 99
          controller-eid 88
          tx (objects/build-object-tx card-eid card-data :hand owner-eid 0 :controller controller-eid)]
      (is (= owner-eid (:object/owner tx))
          "Owner should remain owner-eid")
      (is (= controller-eid (:object/controller tx))
          "Controller should be the override value"))))


;; === Shape canary tests ===
;; These are the most important tests in this file.
;; They verify that build-object-tx produces the same KEY SET as init.cljs objects-tx.
;; After refactoring init.cljs to call build-object-tx, these become tautologies for init.cljs,
;; but they will catch divergence when restorer/tokens/test-helpers are migrated in later tasks.

(def ^:private minimal-deck
  [{:card/id :dark-ritual :count 4}
   {:card/id :cabal-ritual :count 4}
   {:card/id :brain-freeze :count 4}
   {:card/id :lotus-petal :count 4}
   {:card/id :lions-eye-diamond :count 4}
   {:card/id :opt :count 4}
   {:card/id :careful-study :count 4}
   {:card/id :mental-note :count 4}
   {:card/id :deep-analysis :count 4}
   {:card/id :intuition :count 4}
   {:card/id :ill-gotten-gains :count 4}
   {:card/id :island :count 4}
   {:card/id :swamp :count 4}
   {:card/id :city-of-brass :count 4}
   {:card/id :polluted-delta :count 4}])


(deftest canary-non-creature-hand-shape-matches-init
  (testing "build-object-tx key set matches init.cljs for non-creature in hand"
    (let [;; Get key set from build-object-tx
          conn1 (d/create-conn schema)
          _ (d/transact! conn1 cards/all-cards)
          db1 @conn1
          card-eid (get-card-eid db1 :dark-ritual)
          card-data (d/pull db1 [:card/types :card/power :card/toughness] card-eid)
          build-keys (set (keys (objects/build-object-tx card-eid card-data :hand 99 0)))
          ;; Get key set from init.cljs objects-tx (via init-game-state)
          app-db (init/init-game-state {:main-deck minimal-deck})
          game-db (:game/db app-db)
          hand (q/get-hand game-db :player-1)
          ;; Find a non-creature object in hand (dark-ritual is non-creature)
          non-creature (some (fn [obj]
                               (let [card-types (set (:card/types (:object/card obj)))]
                                 (when-not (contains? card-types :creature) obj)))
                             hand)
          ;; Pull the actual stored fields of this object
          obj-eid (d/q '[:find ?e . :in $ ?id :where [?e :object/id ?id]]
                       game-db (:object/id non-creature))
          stored-keys (set (keys (d/pull game-db '[*] obj-eid)))
          ;; Remove :db/id which is a Datascript internal
          relevant-stored-keys (disj stored-keys :db/id)]
      (is (= build-keys relevant-stored-keys)))))


(deftest canary-creature-hand-shape-matches-init
  (testing "build-object-tx key set matches init.cljs for creature in hand"
    (let [;; Get key set from build-object-tx for a creature
          conn1 (d/create-conn schema)
          _ (d/transact! conn1 cards/all-cards)
          db1 @conn1
          card-eid (get-card-eid db1 :xantid-swarm)
          card-data (d/pull db1 [:card/types :card/power :card/toughness] card-eid)
          build-keys (set (keys (objects/build-object-tx card-eid card-data :hand 99 0)))
          ;; Use a deck of all xantid-swarm so every hand card is a creature
          app-db (init/init-game-state {:main-deck [{:card/id :xantid-swarm :count 60}]})
          game-db (:game/db app-db)
          hand (q/get-hand game-db :player-1)
          creature-obj (first hand)
          obj-eid (d/q '[:find ?e . :in $ ?id :where [?e :object/id ?id]]
                       game-db (:object/id creature-obj))
          stored-keys (set (keys (d/pull game-db '[*] obj-eid)))
          relevant-stored-keys (disj stored-keys :db/id)]
      (is (= build-keys relevant-stored-keys)))))


(deftest canary-library-shape-matches-init
  (testing "build-object-tx key set for :library zone matches init.cljs"
    (let [conn1 (d/create-conn schema)
          _ (d/transact! conn1 cards/all-cards)
          db1 @conn1
          card-eid (get-card-eid db1 :dark-ritual)
          card-data (d/pull db1 [:card/types :card/power :card/toughness] card-eid)
          ;; Library position is non-zero (e.g. index 5)
          build-keys (set (keys (objects/build-object-tx card-eid card-data :library 99 5)))
          app-db (init/init-game-state {:main-deck minimal-deck})
          game-db (:game/db app-db)
          library (q/get-objects-in-zone game-db :player-1 :library)
          lib-obj (first library)
          obj-eid (d/q '[:find ?e . :in $ ?id :where [?e :object/id ?id]]
                       game-db (:object/id lib-obj))
          stored-keys (set (keys (d/pull game-db '[*] obj-eid)))
          relevant-stored-keys (disj stored-keys :db/id)]
      (is (= build-keys relevant-stored-keys)))))
