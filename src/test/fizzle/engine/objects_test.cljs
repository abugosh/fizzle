(ns fizzle.engine.objects-test
  "Tests for engine/objects.cljs build-object-tx chokepoint.

   These tests verify that build-object-tx produces structurally correct
   Datascript tx maps for game objects, and that the canary shapes match
   what init.cljs, restorer.cljs, tokens.cljs, and test_helpers.cljs produce.

   === Audit Results (fizzle-2ywh + fizzle-vg0k) ===
   Total helper calls (add-card-to-zone / add-cards-to-library / add-cards-to-graveyard):
     2191 grep matches across 192 test files

   Battlefield placements (add-card-to-zone :battlefield): 502 calls

   MIGRATED (local add-test-creature helpers → th/add-test-creature, fizzle-2ywh):
     - cards/black/crippling_fatigue_test.cljs  — 13 call sites, local helper removed
     - cards/black/vendetta_test.cljs           — 8 call sites, local helper removed
     - engine/phase_mechanic_test.cljs          — 3 call sites, local helper removed
     - engine/effects_test.cljs (add-creature-with-toughness) — 5 call sites, removed

   MIGRATED (reviewer gap sites → build-object-tx, fizzle-vg0k):
     - engine/effects_test.cljs:add-library-cards — was missing build-object-tx, now migrated
     - engine/effects_test.cljs:add-permanent — was missing :object/position + build-object-tx
     - subs/calculator_test.cljs:add-card-to-library — inline construction, now migrated
     - subs/game_test.cljs:add-battlefield-permanent — missing :object/position, now migrated
     - subs/game_test.cljs:add-battlefield-creature — missing :object/position, now migrated
     - subs/game_test.cljs:storm-split test inline — missing :object/position, now migrated

   KEPT (justified, cannot use build-object-tx):
     - All 502 :battlefield placements via th/add-card-to-zone — appropriate test setup
     - subs/game_test.cljs:add-library-cards — NO :object/card ref (phantom counting objects);
       zone-count subs don't need real card entities, forcing card refs adds false coupling
     - subs/game_test.cljs:add-zone-cards — same as add-library-cards; counts only
     - tap_land_test.cljs:add-land-to-battlefield — tests land tap mechanics
     - sphere_of_resistance_test.cljs:add-decrease-modifier — custom artifact cards
       with static abilities for cost-reduction testing

   New shared helper added: th/add-test-creature in test_helpers.cljs
     Signature: [db owner power toughness & {:keys [colors] :or {colors #{}}}]
     Uses build-object-tx for object construction.
     Returns [db obj-id]."
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [clojure.set :as cset]
    [datascript.core :as d]
    [fizzle.db.queries :as q]
    [fizzle.db.schema :refer [schema]]
    [fizzle.engine.cards :as cards]
    [fizzle.engine.effects :as effects]
    [fizzle.engine.effects.tokens]
    [fizzle.engine.objects :as objects]
    [fizzle.engine.zones :as zones]
    [fizzle.events.init :as init]
    [fizzle.sharing.decoder :as decoder]
    [fizzle.sharing.encoder :as encoder]
    [fizzle.sharing.extractor :as extractor]
    [fizzle.sharing.restorer :as restorer]
    [fizzle.test-helpers :as th]))


;; Mock localStorage for restorer tests (restorer reads stops from localStorage)
(def ^:private mock-storage (atom {}))


(set! js/localStorage
      #js {:getItem    (fn [key] (get @mock-storage key nil))
           :setItem    (fn [key value] (swap! mock-storage assoc key value) nil)
           :removeItem (fn [key] (swap! mock-storage dissoc key) nil)
           :clear      (fn [] (reset! mock-storage {}) nil)
           :length     0
           :key        (fn [_] nil)})


(defn- get-card-eid
  [db card-id]
  (d/q '[:find ?e . :in $ ?cid :where [?e :card/id ?cid]] db card-id))


(defn- pull-stored-keys
  "Pull all keys stored for an object in db, stripping :db/id."
  [db obj-id]
  (let [obj-eid (d/q '[:find ?e . :in $ ?id :where [?e :object/id ?id]] db obj-id)]
    (disj (set (keys (d/pull db '[*] obj-eid))) :db/id)))


(defn- make-snapshot
  "Extract + encode + decode a live DB into a portable snapshot."
  [db]
  (-> db extractor/extract encoder/encode-snapshot decoder/decode-snapshot))


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


;; === Canary: restorer.cljs shape parity ===

(deftest canary-restorer-non-creature-hand-shape-matches-build-object-tx
  (testing "restorer.cljs hand object key set matches build-object-tx for non-creature"
    (let [;; Create a db with a dark-ritual in hand, take snapshot, restore
          db (th/create-test-db)
          [db _] (th/add-card-to-zone db :dark-ritual :hand :player-1)
          db (th/add-opponent db)
          snapshot (make-snapshot db)
          restored-db (-> snapshot restorer/restore-game-state :game/db)
          ;; Find the dark-ritual object in hand
          hand (q/get-hand restored-db :player-1)
          dr-obj (some (fn [o]
                         (when (= :dark-ritual (get-in o [:object/card :card/id])) o))
                       hand)
          _ (assert dr-obj "dark-ritual should be in restored hand")
          restored-keys (pull-stored-keys restored-db (:object/id dr-obj))
          ;; Expected: same as build-object-tx for non-creature in hand
          conn (d/create-conn schema)
          _ (d/transact! conn cards/all-cards)
          base-db @conn
          card-eid (get-card-eid base-db :dark-ritual)
          card-data (d/pull base-db [:card/types :card/power :card/toughness] card-eid)
          build-keys (set (keys (objects/build-object-tx card-eid card-data :hand 99 0)))]
      (is (= build-keys restored-keys)
          (str "restorer hand keys should match build-object-tx. "
               "Extra in restorer: " (cset/difference restored-keys build-keys)
               " Missing from restorer: " (cset/difference build-keys restored-keys))))))


(deftest canary-restorer-creature-battlefield-shape-is-superset
  (testing "restorer.cljs battlefield creature key set is superset of build-object-tx"
    (let [;; Create a db with a xantid-swarm on battlefield, take snapshot, restore
          db (th/create-test-db)
          [db _] (th/add-card-to-zone db :xantid-swarm :battlefield :player-1)
          db (th/add-opponent db)
          snapshot (make-snapshot db)
          restored-db (-> snapshot restorer/restore-game-state :game/db)
          bf (q/get-objects-in-zone restored-db :player-1 :battlefield)
          xantid-obj (some (fn [o]
                             (when (= :xantid-swarm (get-in o [:object/card :card/id])) o))
                           bf)
          _ (assert xantid-obj "xantid-swarm should be on restored battlefield")
          restored-keys (pull-stored-keys restored-db (:object/id xantid-obj))
          ;; build-object-tx keys (no summoning-sick/damage-marked)
          conn (d/create-conn schema)
          _ (d/transact! conn cards/all-cards)
          base-db @conn
          card-eid (get-card-eid base-db :xantid-swarm)
          card-data (d/pull base-db [:card/types :card/power :card/toughness] card-eid)
          build-keys (set (keys (objects/build-object-tx card-eid card-data :battlefield 99 0)))]
      ;; Restorer battlefield creatures must have summoning-sick and damage-marked on top of base
      (is (cset/subset? build-keys restored-keys)
          "Restored battlefield creature should have all build-object-tx keys plus more")
      (is (contains? restored-keys :object/summoning-sick)
          "Restored battlefield creature should have :object/summoning-sick")
      (is (contains? restored-keys :object/damage-marked)
          "Restored battlefield creature should have :object/damage-marked"))))


;; === Canary: tokens.cljs shape parity ===

(deftest canary-token-shape-is-superset-of-build-object-tx
  (testing "token-created object key set is superset of build-object-tx (has is-token/summoning-sick/damage-marked)"
    (let [beast-token-effect {:effect/type :create-token
                              :effect/token {:token/name "Beast"
                                             :token/power 4
                                             :token/toughness 4
                                             :token/types #{:creature}
                                             :token/subtypes #{:beast}
                                             :token/colors #{:green}}}
          db (th/create-test-db)
          db (effects/execute-effect db :player-1 beast-token-effect)
          bf (q/get-objects-in-zone db :player-1 :battlefield)
          token-obj (first (filter :object/is-token bf))
          _ (assert token-obj "beast token should be on battlefield")
          token-obj-id (:object/id token-obj)
          token-keys (pull-stored-keys db token-obj-id)
          ;; Expected: superset of build-object-tx for a creature
          ;; Token's card entity uses :card/types, :card/power, :card/toughness
          token-card-eid (d/q '[:find ?e . :in $ ?oid
                                :where [?o :object/id ?oid]
                                [?o :object/card ?e]]
                              db token-obj-id)
          card-data (d/pull db [:card/types :card/power :card/toughness] token-card-eid)
          build-keys (set (keys (objects/build-object-tx token-card-eid card-data :battlefield 99 0)))]
      (is (cset/subset? build-keys token-keys)
          "Token should have all build-object-tx keys")
      (is (contains? token-keys :object/is-token)
          "Token should have :object/is-token")
      (is (contains? token-keys :object/summoning-sick)
          "Token should have :object/summoning-sick")
      (is (contains? token-keys :object/damage-marked)
          "Token should have :object/damage-marked"))))


;; === Canary: test_helpers.cljs shape parity ===

(deftest canary-test-helper-non-creature-hand-shape-matches-build-object-tx
  (testing "add-card-to-zone hand object key set matches build-object-tx for non-creature"
    (let [db (th/create-test-db)
          [db obj-id] (th/add-card-to-zone db :dark-ritual :hand :player-1)
          helper-keys (pull-stored-keys db obj-id)
          ;; Expected: same as build-object-tx for non-creature in hand
          conn (d/create-conn schema)
          _ (d/transact! conn cards/all-cards)
          base-db @conn
          card-eid (get-card-eid base-db :dark-ritual)
          card-data (d/pull base-db [:card/types :card/power :card/toughness] card-eid)
          build-keys (set (keys (objects/build-object-tx card-eid card-data :hand 99 0)))]
      (is (= build-keys helper-keys)
          (str "test-helper hand keys should match build-object-tx. "
               "Extra in helper: " (cset/difference helper-keys build-keys)
               " Missing from helper: " (cset/difference build-keys helper-keys))))))


(deftest canary-test-helper-creature-battlefield-shape-is-superset
  (testing "add-card-to-zone battlefield creature key set is superset of build-object-tx"
    (let [db (th/create-test-db)
          [db obj-id] (th/add-card-to-zone db :xantid-swarm :battlefield :player-1)
          helper-keys (pull-stored-keys db obj-id)
          ;; Expected: superset with summoning-sick + damage-marked
          conn (d/create-conn schema)
          _ (d/transact! conn cards/all-cards)
          base-db @conn
          card-eid (get-card-eid base-db :xantid-swarm)
          card-data (d/pull base-db [:card/types :card/power :card/toughness] card-eid)
          build-keys (set (keys (objects/build-object-tx card-eid card-data :battlefield 99 0)))]
      (is (cset/subset? build-keys helper-keys)
          "Helper battlefield creature should have all build-object-tx keys")
      (is (contains? helper-keys :object/summoning-sick)
          "Helper battlefield creature should have :object/summoning-sick")
      (is (contains? helper-keys :object/damage-marked)
          "Helper battlefield creature should have :object/damage-marked"))))


;; === Canary: move-to-zone adds exactly the expected battlefield fields ===

(deftest canary-move-to-zone-adds-summoning-sick-and-damage-marked
  (testing "move-to-zone entering battlefield adds :object/summoning-sick and :object/damage-marked to creature"
    (let [;; Start with creature in hand (no bf fields)
          db (th/create-test-db)
          [db obj-id] (th/add-card-to-zone db :xantid-swarm :hand :player-1)
          hand-keys (pull-stored-keys db obj-id)
          ;; Move to battlefield
          db-bf (zones/move-to-zone db obj-id :battlefield)
          bf-keys (pull-stored-keys db-bf obj-id)
          ;; Keys added by move-to-zone
          added-keys (cset/difference bf-keys hand-keys)]
      (is (= #{:object/summoning-sick :object/damage-marked} added-keys)
          (str "move-to-zone should add exactly summoning-sick + damage-marked. "
               "Got: " added-keys)))))


(deftest canary-move-to-zone-removes-summoning-sick-and-damage-marked-on-exit
  (testing "move-to-zone leaving battlefield removes :object/summoning-sick and :object/damage-marked"
    (let [db (th/create-test-db)
          [db obj-id] (th/add-card-to-zone db :xantid-swarm :battlefield :player-1)
          bf-keys (pull-stored-keys db obj-id)
          ;; Move out of battlefield to graveyard
          db-gy (zones/move-to-zone db obj-id :graveyard)
          gy-keys (pull-stored-keys db-gy obj-id)
          removed-keys (cset/difference bf-keys gy-keys)]
      (is (contains? removed-keys :object/summoning-sick)
          "Leaving battlefield should remove :object/summoning-sick")
      (is (contains? removed-keys :object/damage-marked)
          "Leaving battlefield should remove :object/damage-marked"))))
