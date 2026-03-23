(ns fizzle.events.game-init-test
  "Tests for game initialization — sideboard zone loading."
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [fizzle.db.queries :as q]
    [fizzle.events.init :as game-init]
    [fizzle.events.setup :as setup]))


(def ^:private minimal-deck
  "A 60-card main deck for testing init-game-state."
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


;; === Sideboard zone at game init ===

(deftest test-sideboard-cards-created-in-game-state
  (testing "sideboard cards are created as objects in the :sideboard zone"
    (let [sideboard [{:card/id :merchant-scroll :count 2}
                     {:card/id :orims-chant :count 1}]
          app-db (game-init/init-game-state {:main-deck minimal-deck
                                             :sideboard sideboard})
          game-db (:game/db app-db)
          sb-objects (q/get-objects-in-zone game-db :player-1 :sideboard)]
      (is (= 3 (count sb-objects))
          "Should have 3 sideboard objects (2 Merchant Scroll + 1 Orim's Chant)"))))


(deftest test-sideboard-objects-have-correct-owner
  (testing "sideboard objects are owned by the player"
    (let [sideboard [{:card/id :merchant-scroll :count 1}]
          app-db (game-init/init-game-state {:main-deck minimal-deck
                                             :sideboard sideboard})
          game-db (:game/db app-db)
          sb-objects (q/get-objects-in-zone game-db :player-1 :sideboard)]
      (is (= 1 (count sb-objects))
          "Should have 1 sideboard object")
      (let [obj (first sb-objects)
            owner-eid (:db/id (:object/owner obj))
            player-eid (q/get-player-eid game-db :player-1)]
        (is (= player-eid owner-eid)
            "Sideboard object should be owned by player-1")))))


(deftest test-empty-sideboard-produces-no-objects
  (testing "empty sideboard produces no objects in :sideboard zone"
    (let [app-db (game-init/init-game-state {:main-deck minimal-deck
                                             :sideboard []})
          game-db (:game/db app-db)
          sb-objects (q/get-objects-in-zone game-db :player-1 :sideboard)]
      (is (= 0 (count sb-objects))
          "Should have no sideboard objects"))))


(deftest test-no-sideboard-key-produces-no-objects
  (testing "omitting :sideboard key entirely produces no objects"
    (let [app-db (game-init/init-game-state {:main-deck minimal-deck})
          game-db (:game/db app-db)
          sb-objects (q/get-objects-in-zone game-db :player-1 :sideboard)]
      (is (= 0 (count sb-objects))
          "Should have no sideboard objects when key is omitted"))))


(deftest test-game-works-normally-with-sideboard
  (testing "game state is functional with sideboard — hand + library + sideboard all populated"
    (let [sideboard [{:card/id :merchant-scroll :count 2}]
          app-db (game-init/init-game-state {:main-deck minimal-deck
                                             :sideboard sideboard})
          game-db (:game/db app-db)
          hand (q/get-objects-in-zone game-db :player-1 :hand)
          library (q/get-objects-in-zone game-db :player-1 :library)
          sb (q/get-objects-in-zone game-db :player-1 :sideboard)]
      (is (= 7 (count hand))
          "Should still draw 7 cards for opening hand")
      (is (= 53 (count library))
          "Should have 53 cards in library (60 - 7)")
      (is (= 2 (count sb))
          "Should have 2 sideboard cards")
      (is (= :opening-hand (:active-screen app-db))
          "Should be on opening-hand screen"))))


(deftest test-sideboard-objects-have-correct-card-refs
  (testing "sideboard objects reference the correct card definitions"
    (let [sideboard [{:card/id :merchant-scroll :count 1}
                     {:card/id :orims-chant :count 1}]
          app-db (game-init/init-game-state {:main-deck minimal-deck
                                             :sideboard sideboard})
          game-db (:game/db app-db)
          sb-objects (q/get-objects-in-zone game-db :player-1 :sideboard)
          card-ids (set (map #(get-in % [:object/card :card/id]) sb-objects))]
      (is (= #{:merchant-scroll :orims-chant} card-ids)
          "Sideboard objects should reference the correct cards"))))


(deftest test-start-game-passes-sideboard-to-init
  (testing "start-game-handler passes sideboard from setup to init-game-state"
    (let [setup-db {:setup/selected-deck :iggy-pop
                    :setup/main-deck minimal-deck
                    :setup/sideboard [{:card/id :merchant-scroll :count 2}]
                    :setup/bot-archetype :goldfish
                    :setup/must-contain {}
                    :setup/presets {}
                    :setup/last-preset nil
                    :setup/imported-decks {}
                    :active-screen :setup}
          result (setup/start-game-handler setup-db)]
      (is (= :opening-hand (:active-screen result))
          "Should start game")
      (let [game-db (:game/db result)
            sb-objects (q/get-objects-in-zone game-db :player-1 :sideboard)]
        (is (= 2 (count sb-objects))
            "Should have 2 sideboard objects from setup config")))))


(deftest test-bot-gets-empty-sideboard
  (testing "opponent/bot player has no sideboard objects"
    (let [sideboard [{:card/id :merchant-scroll :count 2}]
          app-db (game-init/init-game-state {:main-deck minimal-deck
                                             :sideboard sideboard
                                             :bot-archetype :goldfish})
          game-db (:game/db app-db)
          opp-sb (q/get-objects-in-zone game-db :opponent :sideboard)]
      (is (= 0 (count opp-sb))
          "Bot should have no sideboard objects"))))
