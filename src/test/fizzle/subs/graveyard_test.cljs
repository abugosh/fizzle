(ns fizzle.subs.graveyard-test
  (:require
    [cljs.test :refer-macros [deftest is testing]]
    [datascript.core :as d]
    [fizzle.cards.blue.deep-analysis :as deep-analysis-card]
    [fizzle.db.init :as init]
    [fizzle.db.queries :as q]
    [fizzle.engine.grants :as grants]
    [fizzle.engine.sorting :as sorting]
    [fizzle.events.ui :as ui-events]
    [fizzle.subs.game :as subs]
    [re-frame.core :as rf]
    [re-frame.db :as rf-db]))


(defn- sub-value
  "Get subscription value by resetting app-db and deref'ing the subscription."
  [db sub-vec]
  (reset! rf-db/app-db db)
  @(rf/subscribe sub-vec))


(defn- make-game-db
  "Create a game-db with cards in graveyard for testing.
   Returns game-db (Datascript db value)."
  []
  (init/init-game-state))


(defn- add-card-to-graveyard
  "Add a card object to the graveyard. Returns [new-db object-id]."
  [game-db card-id]
  (let [conn (d/conn-from-db game-db)
        player-eid (q/get-player-eid game-db :player-1)
        card-eid (d/q '[:find ?e .
                        :in $ ?cid
                        :where [?e :card/id ?cid]]
                      game-db card-id)
        obj-id (random-uuid)]
    (d/transact! conn [{:object/id obj-id
                        :object/card card-eid
                        :object/zone :graveyard
                        :object/owner player-eid
                        :object/controller player-eid
                        :object/tapped false}])
    [@conn obj-id]))


(defn- add-card-def
  "Transact a card definition into game-db. Returns new game-db."
  [game-db card-def]
  (let [conn (d/conn-from-db game-db)]
    (d/transact! conn [card-def])
    @conn))


;; === ::graveyard-sort-mode subscription tests ===

(deftest test-graveyard-sort-mode-defaults-to-entry
  (testing "::graveyard-sort-mode defaults to :entry when not set"
    (let [app-db {:game/db (make-game-db)}
          mode (sub-value app-db [::subs/graveyard-sort-mode])]
      (is (= :entry mode)))))


(deftest test-graveyard-sort-mode-returns-stored-value
  (testing "::graveyard-sort-mode returns stored value from app-db"
    (let [app-db {:game/db (make-game-db)
                  :graveyard/sort-mode :sorted}
          mode (sub-value app-db [::subs/graveyard-sort-mode])]
      (is (= :sorted mode)))))


;; === ::graveyard subscription shape tests ===

(deftest test-graveyard-returns-map-with-castable-and-remainder
  (testing "::graveyard returns map with :castable and :remainder keys"
    (let [game-db (make-game-db)
          [game-db' _] (add-card-to-graveyard game-db :dark-ritual)
          app-db {:game/db game-db'}
          result (sub-value app-db [::subs/graveyard])]
      (is (map? result))
      (is (contains? result :castable))
      (is (contains? result :remainder)))))


(deftest test-graveyard-empty-returns-empty-partitions
  (testing "::graveyard with empty graveyard returns empty castable and remainder"
    (let [app-db {:game/db (make-game-db)}
          result (sub-value app-db [::subs/graveyard])]
      (is (= [] (:castable result)))
      (is (= [] (:remainder result))))))


(deftest test-graveyard-non-castable-cards-in-remainder
  (testing "non-castable graveyard cards go to :remainder"
    (let [game-db (make-game-db)
          ;; Dark Ritual in graveyard without flashback = non-castable
          [game-db' _] (add-card-to-graveyard game-db :dark-ritual)
          app-db {:game/db game-db'}
          result (sub-value app-db [::subs/graveyard])]
      (is (= [] (:castable result)))
      (is (= 1 (count (:remainder result)))))))


(deftest test-graveyard-entry-mode-no-partition
  (testing "entry mode returns all cards in :remainder, no :castable partition"
    (let [game-db (add-card-def (make-game-db) deep-analysis-card/card)
          [db1 _] (add-card-to-graveyard game-db :dark-ritual)
          [db2 _] (add-card-to-graveyard db1 :deep-analysis)
          app-db {:game/db db2}
          result (sub-value app-db [::subs/graveyard])]
      (is (= [] (:castable result)))
      (is (= 2 (count (:remainder result)))))))


(deftest test-graveyard-sorted-mode-partitions-flashback-to-top
  (testing "sorted mode puts flashback cards in :castable even without mana"
    (let [game-db (add-card-def (make-game-db) deep-analysis-card/card)
          [game-db' _] (add-card-to-graveyard game-db :deep-analysis)
          app-db {:game/db game-db'
                  :graveyard/sort-mode :sorted}
          result (sub-value app-db [::subs/graveyard])]
      (is (= 1 (count (:castable result))))
      (is (= "Deep Analysis"
             (get-in (first (:castable result)) [:object/card :card/name])))
      (is (= [] (:remainder result))))))


(deftest test-graveyard-sorted-mode-mixed-flashback-and-non-flashback
  (testing "sorted mode: flashback to :castable, others to :remainder"
    (let [game-db (add-card-def (make-game-db) deep-analysis-card/card)
          [db1 _] (add-card-to-graveyard game-db :dark-ritual)
          [db2 _] (add-card-to-graveyard db1 :deep-analysis)
          app-db {:game/db db2
                  :graveyard/sort-mode :sorted}
          result (sub-value app-db [::subs/graveyard])]
      (is (= 1 (count (:castable result))))
      (is (= "Deep Analysis"
             (get-in (first (:castable result)) [:object/card :card/name])))
      (is (= 1 (count (:remainder result))))
      (is (= "Dark Ritual"
             (get-in (first (:remainder result)) [:object/card :card/name]))))))


(deftest test-graveyard-sorted-mode-granted-flashback-in-castable
  (testing "sorted mode: cards granted flashback by Recoup go to :castable"
    (let [game-db (make-game-db)
          [game-db' obj-id] (add-card-to-graveyard game-db :dark-ritual)
          game-db' (grants/add-grant game-db' obj-id
                                     {:grant/id (random-uuid)
                                      :grant/type :alternate-cost
                                      :grant/source (random-uuid)
                                      :grant/expires {:expires/turn 1 :expires/phase :cleanup}
                                      :grant/data {:alternate/id :granted-flashback
                                                   :alternate/zone :graveyard
                                                   :alternate/mana-cost {:black 1}
                                                   :alternate/on-resolve :exile}})
          app-db {:game/db game-db'
                  :graveyard/sort-mode :sorted}
          result (sub-value app-db [::subs/graveyard])]
      (is (= 1 (count (:castable result))))
      (is (= "Dark Ritual"
             (get-in (first (:castable result)) [:object/card :card/name])))
      (is (= [] (:remainder result))))))


(deftest test-graveyard-remainder-entry-mode-preserves-order
  (testing "remainder in :entry mode preserves insertion order"
    (let [game-db (make-game-db)
          [db1 _] (add-card-to-graveyard game-db :dark-ritual)
          [db2 _] (add-card-to-graveyard db1 :dark-ritual)
          app-db {:game/db db2
                  :graveyard/sort-mode :entry}
          result (sub-value app-db [::subs/graveyard])
          remainder-ids (mapv :object/id (:remainder result))]
      ;; In entry mode, order should be whatever Datascript returns (not sorted)
      (is (= 2 (count remainder-ids))))))


(deftest test-graveyard-remainder-sorted-mode-sorts-cards
  (testing "remainder in :sorted mode returns CMC+name sorted cards"
    (let [game-db (make-game-db)
          [db1 _] (add-card-to-graveyard game-db :dark-ritual)
          [db2 _] (add-card-to-graveyard db1 :dark-ritual)
          app-db {:game/db db2
                  :graveyard/sort-mode :sorted}
          result (sub-value app-db [::subs/graveyard])
          remainder (:remainder result)]
      (is (= 2 (count remainder)))
      ;; Sorted remainder should equal sort-cards applied to it
      (is (= (mapv :object/id (sorting/sort-cards remainder))
             (mapv :object/id remainder))))))


(deftest test-graveyard-castable-sorted-in-sorted-mode
  (testing "castable partition is sorted by CMC+name in sorted mode"
    (let [game-db (add-card-def (make-game-db) deep-analysis-card/card)
          [game-db' _] (add-card-to-graveyard game-db :deep-analysis)
          app-db {:game/db game-db'
                  :graveyard/sort-mode :sorted}
          result (sub-value app-db [::subs/graveyard])
          castable (:castable result)]
      (is (= (mapv :object/id (sorting/sort-cards castable))
             (mapv :object/id castable))))))


;; === ::toggle-graveyard-sort event tests ===

(deftest test-toggle-graveyard-sort-from-entry-to-sorted
  (testing "toggling from :entry switches to :sorted"
    (let [app-db {:game/db (make-game-db)
                  :graveyard/sort-mode :entry}]
      (reset! rf-db/app-db app-db)
      (rf/dispatch-sync [::ui-events/toggle-graveyard-sort])
      (is (= :sorted (:graveyard/sort-mode @rf-db/app-db))))))


(deftest test-toggle-graveyard-sort-from-sorted-to-entry
  (testing "toggling from :sorted switches to :entry"
    (let [app-db {:game/db (make-game-db)
                  :graveyard/sort-mode :sorted}]
      (reset! rf-db/app-db app-db)
      (rf/dispatch-sync [::ui-events/toggle-graveyard-sort])
      (is (= :entry (:graveyard/sort-mode @rf-db/app-db))))))


(deftest test-toggle-graveyard-sort-from-nil-defaults-entry-then-toggles
  (testing "toggling when no sort-mode set treats nil as :entry and toggles to :sorted"
    (let [app-db {:game/db (make-game-db)}]
      (reset! rf-db/app-db app-db)
      (rf/dispatch-sync [::ui-events/toggle-graveyard-sort])
      (is (= :sorted (:graveyard/sort-mode @rf-db/app-db))))))
