(ns fizzle.cards.black.rain-of-filth-test
  "Tests for Rain of Filth card.

   Rain of Filth: B - Instant
   Until end of turn, lands you control gain
   'Sacrifice this land: Add {B}.'

   Tests:
   - Card definition (type, cost, effects)
   - Cast-resolve grants sacrifice-for-B to all controlled lands
   - Cannot cast guards
   - Storm count
   - Edge cases: no lands, tapped lands, grant expiry, activation"
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [datascript.core :as d]
    [fizzle.cards.black.rain-of-filth :as rain-of-filth]
    [fizzle.db.queries :as q]
    [fizzle.engine.grants :as grants]
    [fizzle.engine.rules :as rules]
    [fizzle.events.abilities :as ability-events]
    [fizzle.test-helpers :as th]))


;; === A. Card Definition Tests ===

(deftest card-definition-test
  (testing "Rain of Filth card fields"
    (let [card rain-of-filth/card]
      (is (= :rain-of-filth (:card/id card)))
      (is (= "Rain of Filth" (:card/name card)))
      (is (= 1 (:card/cmc card)))
      (is (= {:black 1} (:card/mana-cost card)))
      (is (= #{:black} (:card/colors card)))
      (is (= #{:instant} (:card/types card)))))

  (testing "Rain of Filth effect is grant-mana-ability"
    (let [effects (:card/effects rain-of-filth/card)]
      (is (= 1 (count effects)))
      (let [effect (first effects)]
        (is (= :grant-mana-ability (:effect/type effect)))
        (is (= :controlled-lands (:effect/target effect)))
        (let [ability (:effect/ability effect)]
          (is (= :mana (:ability/type ability)))
          (is (= {:sacrifice-self true} (:ability/cost ability)))
          (is (= {:black 1} (:ability/produces ability))))))))


;; === B. Cast-Resolve Happy Path ===

(deftest cast-resolve-grants-to-all-lands-test
  (testing "Casting Rain of Filth grants sacrifice-for-B to all controlled lands"
    (let [db (th/create-test-db {:mana {:black 1}})
          [db land1-id] (th/add-card-to-zone db :island :battlefield :player-1)
          [db land2-id] (th/add-card-to-zone db :swamp :battlefield :player-1)
          [db rof-id] (th/add-card-to-zone db :rain-of-filth :hand :player-1)
          db' (th/cast-and-resolve db :player-1 rof-id)]
      ;; Both lands should have grants
      (let [land1-grants (q/get-grants db' land1-id)
            land2-grants (q/get-grants db' land2-id)]
        (is (= 1 (count land1-grants)))
        (is (= 1 (count land2-grants)))
        (is (= :ability (:grant/type (first land1-grants))))
        (is (= :mana (:ability/type (:grant/data (first land1-grants)))))
        (is (= {:black 1} (:ability/produces (:grant/data (first land1-grants)))))))))


;; === C. Cannot-Cast Guards ===

(deftest cannot-cast-without-mana-test
  (testing "Cannot cast Rain of Filth without black mana"
    (let [db (th/create-test-db)
          [db rof-id] (th/add-card-to-zone db :rain-of-filth :hand :player-1)]
      (is (false? (rules/can-cast? db :player-1 rof-id))))))


(deftest cannot-cast-from-graveyard-test
  (testing "Cannot cast Rain of Filth from graveyard"
    (let [db (th/create-test-db {:mana {:black 1}})
          [db rof-id] (th/add-card-to-zone db :rain-of-filth :graveyard :player-1)]
      (is (false? (rules/can-cast? db :player-1 rof-id))))))


;; === D. Storm Count ===

(deftest storm-count-increments-test
  (testing "Casting Rain of Filth increments storm count"
    (let [db (th/create-test-db {:mana {:black 1}})
          [db _] (th/add-card-to-zone db :island :battlefield :player-1)
          [db rof-id] (th/add-card-to-zone db :rain-of-filth :hand :player-1)
          _ (is (= 0 (q/get-storm-count db :player-1)))
          db' (th/cast-and-resolve db :player-1 rof-id)]
      (is (= 1 (q/get-storm-count db' :player-1))))))


;; === G. Edge Cases ===

(deftest no-lands-is-noop-test
  (testing "Rain of Filth with no lands is a no-op (still resolves)"
    (let [db (th/create-test-db {:mana {:black 1}})
          [db rof-id] (th/add-card-to-zone db :rain-of-filth :hand :player-1)
          db' (th/cast-and-resolve db :player-1 rof-id)]
      ;; Rain of Filth should be in graveyard
      (is (= :graveyard (th/get-object-zone db' rof-id))))))


(deftest tapped-lands-get-grant-test
  (testing "Tapped lands get the grant and can activate it (no :tap cost)"
    (let [db (th/create-test-db {:mana {:black 1}})
          [db land-id] (th/add-card-to-zone db :island :battlefield :player-1)
          ;; Tap the land
          obj-eid (q/get-object-eid db land-id)
          db (d/db-with db [[:db/add obj-eid :object/tapped true]])
          [db rof-id] (th/add-card-to-zone db :rain-of-filth :hand :player-1)
          db' (th/cast-and-resolve db :player-1 rof-id)]
      ;; Tapped land should have the grant
      (is (= 1 (count (q/get-grants db' land-id))))
      ;; Activate the granted ability (should work despite being tapped)
      (let [grant-id (:grant/id (first (q/get-grants db' land-id)))
            db'' (ability-events/activate-granted-mana-ability db' :player-1 land-id grant-id)]
        ;; Land should be sacrificed (in graveyard)
        (is (= :graveyard (th/get-object-zone db'' land-id)))
        ;; Should have produced black mana
        (is (= 1 (:black (q/get-mana-pool db'' :player-1))))))))


(deftest activate-granted-ability-test
  (testing "Activating granted ability: sacrifice land, get {B}"
    (let [db (th/create-test-db {:mana {:black 1}})
          [db land-id] (th/add-card-to-zone db :island :battlefield :player-1)
          [db rof-id] (th/add-card-to-zone db :rain-of-filth :hand :player-1)
          db' (th/cast-and-resolve db :player-1 rof-id)
          grant-id (:grant/id (first (q/get-grants db' land-id)))
          db'' (ability-events/activate-granted-mana-ability db' :player-1 land-id grant-id)]
      (is (= :graveyard (th/get-object-zone db'' land-id)))
      (is (= 1 (:black (q/get-mana-pool db'' :player-1)))))))


(deftest multiple-lands-independent-grants-test
  (testing "Each land gets independent grant, activating one doesn't affect others"
    (let [db (th/create-test-db {:mana {:black 1}})
          [db land1-id] (th/add-card-to-zone db :island :battlefield :player-1)
          [db land2-id] (th/add-card-to-zone db :swamp :battlefield :player-1)
          [db land3-id] (th/add-card-to-zone db :island :battlefield :player-1)
          [db rof-id] (th/add-card-to-zone db :rain-of-filth :hand :player-1)
          db' (th/cast-and-resolve db :player-1 rof-id)
          ;; Activate on first land
          grant1-id (:grant/id (first (q/get-grants db' land1-id)))
          db'' (ability-events/activate-granted-mana-ability db' :player-1 land1-id grant1-id)]
      ;; First land sacrificed
      (is (= :graveyard (th/get-object-zone db'' land1-id)))
      ;; Other lands still on battlefield with grants
      (is (= :battlefield (th/get-object-zone db'' land2-id)))
      (is (= :battlefield (th/get-object-zone db'' land3-id)))
      (is (= 1 (count (q/get-grants db'' land2-id))))
      (is (= 1 (count (q/get-grants db'' land3-id)))))))


(deftest grants-expire-at-cleanup-test
  (testing "Granted abilities expire at cleanup phase"
    (let [db (th/create-test-db {:mana {:black 1}})
          [db land-id] (th/add-card-to-zone db :island :battlefield :player-1)
          [db rof-id] (th/add-card-to-zone db :rain-of-filth :hand :player-1)
          db' (th/cast-and-resolve db :player-1 rof-id)]
      ;; Grant exists
      (is (= 1 (count (q/get-grants db' land-id))))
      ;; After cleanup, grant expired
      (let [db-expired (grants/expire-grants db' 1 :cleanup)]
        (is (= 0 (count (q/get-grants db-expired land-id))))))))


;; === I. Integration Test ===

(deftest integration-cast-rain-sacrifice-for-mana-test
  (testing "Full flow: cast Rain of Filth, sacrifice 2 lands for 2 black mana"
    (let [db (th/create-test-db {:mana {:black 2}})
          [db land1-id] (th/add-card-to-zone db :swamp :battlefield :player-1)
          [db land2-id] (th/add-card-to-zone db :island :battlefield :player-1)
          [db rof-id] (th/add-card-to-zone db :rain-of-filth :hand :player-1)
          ;; Cast Rain of Filth (costs B, leaving 1B)
          db' (th/cast-and-resolve db :player-1 rof-id)
          ;; Mana pool after casting: paid 1B from 2B = 1B remaining
          _ (is (= 1 (:black (q/get-mana-pool db' :player-1))))
          ;; Sacrifice land 1
          grant1-id (:grant/id (first (q/get-grants db' land1-id)))
          db'' (ability-events/activate-granted-mana-ability db' :player-1 land1-id grant1-id)
          _ (is (= 2 (:black (q/get-mana-pool db'' :player-1))))
          ;; Sacrifice land 2
          grant2-id (:grant/id (first (q/get-grants db'' land2-id)))
          db''' (ability-events/activate-granted-mana-ability db'' :player-1 land2-id grant2-id)]
      ;; Should have 3 black mana total (1 remaining + 2 from sacrifices)
      (is (= 3 (:black (q/get-mana-pool db''' :player-1))))
      ;; Both lands in graveyard
      (is (= :graveyard (th/get-object-zone db''' land1-id)))
      (is (= :graveyard (th/get-object-zone db''' land2-id))))))
