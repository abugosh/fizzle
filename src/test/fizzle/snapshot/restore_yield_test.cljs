(ns fizzle.snapshot.restore-yield-test
  "Reproduction test: restore from snapshot, then yield twice.
   Verifies that the game director can advance a restored game state."
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [fizzle.events.director :as director]
    [fizzle.sharing.decoder :as decoder]
    [fizzle.sharing.encoder :as encoder]
    [fizzle.sharing.extractor :as extractor]
    [fizzle.sharing.restorer :as restorer]
    [fizzle.test-helpers :as th]))


(defn- restore-round-trip
  "Extract game-db → encode → decode → restore.
   Returns restored app-db (the same shape as restorer/restore-game-state)."
  [game-db]
  (let [snapshot (extractor/extract game-db)
        encoded  (encoder/encode-snapshot snapshot)
        decoded  (decoder/decode-snapshot encoded)]
    (restorer/restore-game-state decoded)))


(deftest restored-game-yields-twice-test
  (testing "Director can advance a restored game through multiple yields"
    (let [;; Create a normal game state with goldfish opponent
          scenario (th/create-game-scenario {:bot-archetype :goldfish
                                             :stops #{:main1 :main2}})
          game-db  (:game/db scenario)

          ;; Round-trip through snapshot
          restored (restore-round-trip game-db)
          _ (is (some? (:game/db restored)) "Restored app-db should have game/db")

          ;; First yield
          result-1 (director/run-to-decision restored {:yield-all? false :human-yielded? true})
          reason-1 (:reason result-1)
          gdb-1    (:game/db (:app-db result-1))]

      (is (some? reason-1) (str "First yield should return a reason, got: " (pr-str result-1)))
      (is (not (identical? (:game/db restored) gdb-1))
          "First yield should change game-db")

      (testing "Second yield also advances"
        (let [app-db-2 (:app-db result-1)
              pending  (:game/pending-selection app-db-2)]

          (when pending
            (is (nil? pending)
                (str "First yield left pending-selection of type "
                     (:selection/type pending)
                     " — this blocks subsequent yields. Selection: "
                     (pr-str (select-keys pending
                                          [:selection/type :selection/lifecycle
                                           :selection/player-id :selection/cleanup?])))))

          (when-not pending
            (let [result-2 (director/run-to-decision app-db-2
                                                     {:yield-all? false :human-yielded? true})
                  reason-2 (:reason result-2)
                  gdb-2    (:game/db (:app-db result-2))]
              (is (some? reason-2)
                  (str "Second yield should return a reason, got: " (pr-str result-2)))
              (is (not (identical? gdb-1 gdb-2))
                  (str "Second yield should change game-db. "
                       "reason-1: " reason-1
                       ", reason-2: " reason-2)))))))))


(deftest restored-game-no-phantom-pending-selection-test
  (testing "Director yield-all on restored game completes without stuck selection"
    (let [scenario (th/create-game-scenario {:bot-archetype :goldfish
                                             :stops #{:main1 :main2}})
          game-db  (:game/db scenario)
          restored (restore-round-trip game-db)

          ;; yield-all should run through the entire turn
          result   (director/run-to-decision restored {:yield-all? true})
          reason   (:reason result)
          app-db   (:app-db result)
          pending  (:game/pending-selection app-db)]

      (is (some? reason) "yield-all should return a reason")
      ;; If it stopped for pending-selection, that's the bug
      (when (= :pending-selection reason)
        (is (nil? pending)
            (str "yield-all stopped with pending-selection of type "
                 (:selection/type pending)
                 " — likely the stuck-game bug"))))))


(deftest director-double-yield-without-restore-test
  (testing "Director can double-yield on a normal (non-restored) game scenario"
    (let [scenario (th/create-game-scenario {:bot-archetype :goldfish
                                             :stops #{:main1 :main2}})
          result-1 (director/run-to-decision scenario {:yield-all? false :human-yielded? true})
          gdb-1    (:game/db (:app-db result-1))
          pending-1 (:game/pending-selection (:app-db result-1))]

      (is (not (identical? (:game/db scenario) gdb-1))
          "First yield should change game-db on normal scenario")
      (is (nil? pending-1)
          (str "Normal scenario first yield should not leave pending-selection, got: "
               (:selection/type pending-1)))

      (when-not pending-1
        (let [result-2 (director/run-to-decision (:app-db result-1)
                                                 {:yield-all? false :human-yielded? true})
              gdb-2    (:game/db (:app-db result-2))]
          (is (not (identical? gdb-1 gdb-2))
              "Second yield should change game-db on normal scenario"))))))
