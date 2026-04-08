(ns fizzle.events.lands-test
  "Integration (dispatch-sync) + unit tests for events/lands.cljs.

   Integration tests dispatch through re-frame and verify state changes including
   ETB trigger registration and history entry creation.
   Unit tests call pure functions directly for guard conditions and edge cases.

   NOTE: play_land_test.cljs and tap_land_test.cljs already cover the pure function
   happy paths for play-land, tap-permanent, and mana activation. This file focuses
   on the dispatch-sync (re-frame event handler wiring) gap."
  (:require
    [cljs.test :refer-macros [deftest is testing]]
    [datascript.core :as d]
    [fizzle.db.queries :as q]
    [fizzle.events.lands :as lands]
    [fizzle.history.interceptor :as interceptor]
    [fizzle.test-helpers :as h]
    [re-frame.core :as rf]
    [re-frame.db :as rf-db]))


;; Register history interceptor for dispatch-sync tests
(interceptor/register!)


(defn- setup-app-db
  "Create a full app-db with human + goldfish bot, libraries populated,
   starting at :main1 phase. Mirrors priority_test.cljs setup exactly."
  ([]
   (setup-app-db {}))
  ([opts]
   (h/create-game-scenario (merge {:bot-archetype :goldfish} opts))))


(defn- dispatch-event
  "Dispatch a re-frame event synchronously, return resulting app-db."
  [app-db event]
  (reset! rf-db/app-db app-db)
  (rf/dispatch-sync event)
  @rf-db/app-db)


;; ============================================================
;; Integration Tests (dispatch-sync through re-frame)
;; ============================================================

;; Test 1: ::play-land moves land to battlefield
(deftest play-land-dispatch-moves-to-battlefield
  (testing "::play-land event moves land from hand to battlefield"
    (let [app-db (setup-app-db)
          game-db (:game/db app-db)
          [game-db' obj-id] (h/add-card-to-zone game-db :island :hand :player-1)
          app-db' (assoc app-db :game/db game-db')
          _ (is (= :hand (:object/zone (q/get-object game-db' obj-id)))
                "Precondition: island starts in hand")
          result (dispatch-event app-db' [::lands/play-land obj-id :player-1])
          result-obj (q/get-object (:game/db result) obj-id)]
      (is (= :battlefield (:object/zone result-obj))
          "Island should be on battlefield after ::play-land dispatch"))))


;; Test 2: ::play-land creates history entry
(deftest play-land-creates-history-entry
  (testing "::play-land dispatch creates a history entry with correct event type"
    (let [app-db (setup-app-db)
          game-db (:game/db app-db)
          [game-db' obj-id] (h/add-card-to-zone game-db :island :hand :player-1)
          app-db' (assoc app-db :game/db game-db')
          result (dispatch-event app-db' [::lands/play-land obj-id :player-1])
          ;; interceptor moves pending-entry to history/main — check history/main not pending-entry
          history-entries (:history/main result)]
      (is (seq history-entries)
          "history should have entries after ::play-land")
      (let [last-entry (last history-entries)]
        (is (= ::lands/play-land (:entry/event-type last-entry))
            "last history entry event-type should be ::lands/play-land")))))


;; Test 3: ::play-land decrements land-plays-left
(deftest play-land-dispatch-decrements-land-plays
  (testing "::play-land decrements :player/land-plays-left from 1 to 0"
    (let [app-db (setup-app-db)
          game-db (:game/db app-db)
          [game-db' obj-id] (h/add-card-to-zone game-db :island :hand :player-1)
          _ (is (= 1 (d/q '[:find ?plays .
                            :in $ ?pid
                            :where [?e :player/id ?pid]
                            [?e :player/land-plays-left ?plays]]
                          game-db' :player-1))
                "Precondition: land-plays-left = 1")
          app-db' (assoc app-db :game/db game-db')
          result (dispatch-event app-db' [::lands/play-land obj-id :player-1])
          plays-after (d/q '[:find ?plays .
                             :in $ ?pid
                             :where [?e :player/id ?pid]
                             [?e :player/land-plays-left ?plays]]
                           (:game/db result) :player-1)]
      (is (= 0 plays-after)
          "land-plays-left should be 0 after playing a land"))))


;; Test 4: ::tap-permanent taps land via dispatch
(deftest tap-permanent-dispatch-taps-land
  (testing "::tap-permanent dispatch sets :object/tapped to true"
    (let [app-db (setup-app-db)
          game-db (:game/db app-db)
          [game-db' obj-id] (h/add-card-to-zone game-db :island :battlefield :player-1)
          _ (is (false? (:object/tapped (q/get-object game-db' obj-id)))
                "Precondition: island starts untapped")
          app-db' (assoc app-db :game/db game-db')
          result (dispatch-event app-db' [::lands/tap-permanent obj-id])
          land-after (q/get-object (:game/db result) obj-id)]
      (is (true? (:object/tapped land-after))
          "Island should be tapped after ::tap-permanent dispatch"))))


;; Test 5: ::play-land registers triggers for City of Brass
(deftest play-land-dispatch-registers-triggers
  (testing "::play-land dispatch registers card triggers for City of Brass"
    (let [app-db (setup-app-db)
          game-db (:game/db app-db)
          [game-db' obj-id] (h/add-card-to-zone game-db :city-of-brass :hand :player-1)
          app-db' (assoc app-db :game/db game-db')
          result (dispatch-event app-db' [::lands/play-land obj-id :player-1])
          result-db (:game/db result)
          ;; City of Brass has a tap trigger — verify trigger entities created
          trigger-eids (d/q '[:find [?e ...]
                              :where [?e :trigger/type _]]
                            result-db)]
      (is (seq trigger-eids)
          "Trigger entities should exist in db after playing City of Brass"))))


;; Test 6: ::play-land fires ETB effects (Gemstone Mine enters with counters)
(deftest play-land-dispatch-fires-etb-effects
  (testing "::play-land dispatch fires ETB effects — Gemstone Mine enters with 3 counters"
    (let [app-db (setup-app-db)
          game-db (:game/db app-db)
          [game-db' obj-id] (h/add-card-to-zone game-db :gemstone-mine :hand :player-1)
          app-db' (assoc app-db :game/db game-db')
          result (dispatch-event app-db' [::lands/play-land obj-id :player-1])
          result-obj (q/get-object (:game/db result) obj-id)]
      (is (= :battlefield (:object/zone result-obj))
          "Gemstone Mine should be on battlefield")
      (is (= {:mining 3} (:object/counters result-obj))
          "Gemstone Mine should enter with 3 mining counters from ETB effect"))))


;; ============================================================
;; Unit Tests (direct function calls on pure functions)
;; ============================================================

;; Test 7: play-land with non-land card returns unchanged db
(deftest play-land-not-a-land
  (testing "play-land with a non-land card (instant) returns unchanged db"
    (let [db (h/create-test-db)
          [db' obj-id] (h/add-card-to-zone db :dark-ritual :hand :player-1)
          result (lands/play-land db' :player-1 obj-id)]
      (is (= :hand (:object/zone (q/get-object result obj-id)))
          "Dark Ritual should remain in hand — not a land")
      (is (= 1 (d/q '[:find ?plays .
                      :in $ ?pid
                      :where [?e :player/id ?pid]
                      [?e :player/land-plays-left ?plays]]
                    result :player-1))
          "land-plays-left should be unchanged"))))


;; Test 8: play-land with no plays left returns unchanged db
(deftest play-land-no-plays-left
  (testing "play-land returns unchanged db when land-plays-left = 0"
    (let [db (h/create-test-db)
          ;; Set land plays to 0
          player-eid (q/get-player-eid db :player-1)
          db-no-plays (d/db-with db [[:db/add player-eid :player/land-plays-left 0]])
          [db' obj-id] (h/add-card-to-zone db-no-plays :island :hand :player-1)
          result (lands/play-land db' :player-1 obj-id)]
      (is (= :hand (:object/zone (q/get-object result obj-id)))
          "Island should remain in hand when no land plays left")
      (is (= 0 (d/q '[:find ?plays .
                      :in $ ?pid
                      :where [?e :player/id ?pid]
                      [?e :player/land-plays-left ?plays]]
                    result :player-1))
          "land-plays-left should stay 0"))))


;; Test 9: play-land with land not in hand returns unchanged db
(deftest play-land-not-in-hand
  (testing "play-land returns unchanged db when land is on battlefield (not hand)"
    (let [db (h/create-test-db)
          [db' obj-id] (h/add-card-to-zone db :island :battlefield :player-1)
          result (lands/play-land db' :player-1 obj-id)]
      (is (= :battlefield (:object/zone (q/get-object result obj-id)))
          "Island already on battlefield should stay there")
      (is (= 1 (d/q '[:find ?plays .
                      :in $ ?pid
                      :where [?e :player/id ?pid]
                      [?e :player/land-plays-left ?plays]]
                    result :player-1))
          "land-plays-left should be unchanged — land was not in hand"))))


;; Test 10: tap-permanent with nonexistent object returns unchanged db
(deftest tap-permanent-nonexistent-object
  (testing "tap-permanent with a fake object-id returns unchanged db"
    (let [db (h/create-test-db)
          fake-id (random-uuid)
          result (lands/tap-permanent db fake-id)]
      (is (= db result)
          "db should be unchanged when object-id does not exist"))))


;; Test 11: untap-permanent sets :object/tapped to false
(deftest untap-permanent-sets-tapped-false
  (testing "untap-permanent sets :object/tapped to false on a tapped permanent"
    (let [db (h/create-test-db)
          [db' obj-id] (h/add-card-to-zone db :island :battlefield :player-1)
          ;; Tap it first
          db-tapped (lands/tap-permanent db' obj-id)
          _ (is (true? (:object/tapped (q/get-object db-tapped obj-id)))
                "Precondition: island is tapped")
          result (lands/untap-permanent db-tapped obj-id)]
      (is (false? (:object/tapped (q/get-object result obj-id)))
          "Island should be untapped after untap-permanent"))))


;; Test 12: untap-permanent with nonexistent object returns unchanged db
(deftest untap-permanent-nonexistent-object
  (testing "untap-permanent with a fake object-id returns unchanged db"
    (let [db (h/create-test-db)
          fake-id (random-uuid)
          result (lands/untap-permanent db fake-id)]
      (is (= db result)
          "db should be unchanged when object-id does not exist"))))
