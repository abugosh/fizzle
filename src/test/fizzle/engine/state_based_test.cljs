(ns fizzle.engine.state-based-test
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [datascript.core :as d]
    [fizzle.db.init :refer [init-game-state]]
    [fizzle.db.queries :as q]
    [fizzle.engine.state-based :as sba]))


;; === Test helpers ===

(defn add-permanent
  "Add a permanent to the battlefield for testing.
   Returns [db object-id] where object-id is the UUID of the created permanent."
  ([db player-id card-data]
   (add-permanent db player-id card-data nil))
  ([db player-id card-data initial-counters]
   (let [conn (d/conn-from-db db)
         player-eid (q/get-player-eid db player-id)
         ;; Create an inline card definition
         card-id (keyword (str "test-card-" (random-uuid)))
         _ (d/transact! conn [(merge {:card/id card-id
                                      :card/name "Test Card"
                                      :card/types #{:land}}
                                     card-data)])
         card-eid (d/q '[:find ?e .
                         :in $ ?cid
                         :where [?e :card/id ?cid]]
                       @conn card-id)
         object-id (random-uuid)
         base-entity {:object/id object-id
                      :object/card card-eid
                      :object/zone :battlefield
                      :object/owner player-eid
                      :object/controller player-eid
                      :object/tapped false}
         entity (if initial-counters
                  (assoc base-entity :object/counters initial-counters)
                  base-entity)]
     (d/transact! conn [entity])
     [@conn object-id])))


(defn get-object-zone
  "Get the zone of an object by its UUID."
  [db object-id]
  (d/q '[:find ?zone .
         :in $ ?oid
         :where [?e :object/id ?oid]
         [?e :object/zone ?zone]]
       db object-id))


;; === register-sba! tests ===

(deftest test-register-sba
  (testing "can register custom SBA check"
    (let [called (atom false)
          check-fn (fn [_db] (reset! called true) [])]
      (sba/register-sba! :test-custom-sba check-fn)
      (sba/check-all-sbas (init-game-state))
      (is @called "Custom SBA check should be called"))))


;; === check-all-sbas tests ===

(deftest test-check-all-sbas-empty
  (testing "returns empty when no SBAs apply"
    ;; Create db with permanent that has counters > 0
    (let [[db _object-id] (add-permanent
                            (init-game-state)
                            :player-1
                            {:card/abilities [{:ability/cost {:remove-counter {:mining 1}}}]}
                            {:mining 3})
          sbas (sba/check-all-sbas db)]
      (is (empty? (filter #(= :zero-counters (:sba/type %)) sbas))
          "No zero-counters SBA when counters > 0"))))


;; === zero-counters SBA tests ===
;; NOTE: These tests were removed in favor of ability-based sacrifice.
;; The zero-counters SBA was replaced with :sacrifice effect in mana abilities.
;; See test-gemstone-mine-sacrifices-on-last-counter in tap_land_test.cljs for new behavior.


(deftest test-non-zero-counters-ignored
  (testing "permanents with counters > 0 not sacrificed"
    (let [[db obj-id] (add-permanent
                        (init-game-state)
                        :player-1
                        {:card/abilities [{:ability/cost {:remove-counter {:mining 1}}}]}
                        {:mining 1})
          db-after (sba/check-and-execute-sbas db)
          zone-after (get-object-zone db-after obj-id)]
      (is (= :battlefield zone-after) "Object should still be on battlefield"))))


(deftest test-no-counter-ability-ignored
  (testing "permanents without remove-counter cost are not sacrificed even with 0 counters"
    (let [[db obj-id] (add-permanent
                        (init-game-state)
                        :player-1
                        {:card/abilities [{:ability/cost {:tap true}}]}  ; tap only, no counter cost
                        {:mining 0})  ; has 0 counters but ability doesn't use them
          db-after (sba/check-and-execute-sbas db)
          zone-after (get-object-zone db-after obj-id)]
      (is (= :battlefield zone-after) "Object should still be on battlefield"))))
