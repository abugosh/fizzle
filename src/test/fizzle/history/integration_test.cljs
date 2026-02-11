(ns fizzle.history.integration-test
  "Integration test for the history system lifecycle.
   Uses real Datascript game-dbs as snapshots to verify the full
   init -> append -> step-back -> step-forward -> fork -> switch-branch flow."
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [datascript.core :as d]
    [fizzle.db.schema :refer [schema]]
    [fizzle.history.core :as history]))


(defn- make-game-db
  "Create a minimal real Datascript game-db with given turn and phase."
  [turn phase]
  (let [conn (d/create-conn schema)]
    (d/transact! conn [{:player/id :player-1
                        :player/name "Player"
                        :player/life 20
                        :player/mana-pool {:white 0 :blue 0 :black 0
                                           :red 0 :green 0 :colorless 0}
                        :player/storm-count 0
                        :player/land-plays-left 1}])
    (let [player-eid (d/q '[:find ?e . :where [?e :player/id :player-1]] @conn)]
      (d/transact! conn [{:game/id :game-1
                          :game/turn turn
                          :game/phase phase
                          :game/active-player player-eid
                          :game/priority player-eid}]))
    @conn))


(deftest history-full-lifecycle-test
  (testing "Full history lifecycle: init -> entries -> step-back -> step-forward -> fork -> switch"
    (let [;; Create real Datascript game dbs for snapshots
          db1 (make-game-db 1 :main1)
          db2 (make-game-db 1 :combat)
          db3 (make-game-db 2 :main1)
          ;; Init history
          app-db (merge (history/init-history) {:game/db db1})]
      ;; Verify initial state
      (is (= -1 (:history/position app-db)))
      (is (= 0 (history/entry-count app-db)))

      ;; Append 3 entries
      (let [app-db (-> app-db
                       (history/append-entry (history/make-entry db1 :evt-1 "Cast Dark Ritual" 1))
                       (history/append-entry (history/make-entry db2 :evt-2 "Advance to Combat" 1))
                       (history/append-entry (history/make-entry db3 :evt-3 "Start Turn 2" 2)))]
        (is (= 3 (history/entry-count app-db)))
        (is (= 2 (:history/position app-db)) "Position should be at tip (index 2)")
        (is (true? (history/at-tip? app-db)))

        ;; Step back to position 1
        (let [stepped-back (history/step-to app-db 1)]
          (is (= 1 (:history/position stepped-back)))
          (is (= db2 (:game/db stepped-back))
              "Should restore game-db snapshot at position 1")
          (is (true? (history/can-step-back? stepped-back))
              "Should be able to step back further")
          (is (true? (history/can-step-forward? stepped-back))
              "Should be able to step forward")

          ;; Step forward to position 2
          (let [stepped-forward (history/step-to stepped-back 2)]
            (is (= 2 (:history/position stepped-forward)))
            (is (= db3 (:game/db stepped-forward))
                "Should restore game-db snapshot at position 2")
            (is (true? (history/at-tip? stepped-forward))))

          ;; Fork from position 1 (auto-fork when taking action from non-tip)
          (let [db-fork (make-game-db 1 :main2)
                forked (history/auto-fork stepped-back
                                          (history/make-entry db-fork :evt-fork "Play land" 1))]
            (is (some? (:history/current-branch forked))
                "Should be on a fork branch")
            (is (= 2 (:history/position forked))
                "Position should advance on fork")
            ;; Fork effective entries: entries 0-1 from main + 1 fork entry = 3
            (is (= 3 (history/entry-count forked)))
            ;; Main timeline should be preserved
            (is (= 3 (count (:history/main forked)))
                "Main timeline should be unchanged")

            ;; Switch back to main
            (let [on-main (history/switch-branch forked nil)]
              (is (nil? (:history/current-branch on-main))
                  "Should be on main timeline")
              (is (= 2 (:history/position on-main))
                  "Should be at main tip")
              (is (= db3 (:game/db on-main))
                  "Should have main tip's game-db"))

            ;; Switch to fork
            (let [fork-id (:history/current-branch forked)
                  on-fork (history/switch-branch forked fork-id)]
              (is (= fork-id (:history/current-branch on-fork)))
              (is (= db-fork (:game/db on-fork))
                  "Should have fork tip's game-db"))))))))


(deftest history-pop-entry-test
  (testing "Pop entry removes last entry and restores previous snapshot"
    (let [db1 (make-game-db 1 :main1)
          db2 (make-game-db 1 :combat)
          app-db (-> (merge (history/init-history) {:game/db db1})
                     (history/append-entry (history/make-entry db1 :evt-1 "Entry 1" 1))
                     (history/append-entry (history/make-entry db2 :evt-2 "Entry 2" 1)))]
      (is (= 2 (history/entry-count app-db)))
      (is (true? (history/can-pop? app-db)))

      (let [popped (history/pop-entry app-db)]
        (is (= 1 (history/entry-count popped)))
        (is (= 0 (:history/position popped)))
        (is (= db1 (:game/db popped))
            "Should restore snapshot of previous entry")))))
