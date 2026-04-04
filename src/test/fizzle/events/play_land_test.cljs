(ns fizzle.events.play-land-test
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [datascript.core :as d]
    [fizzle.db.queries :as q]
    [fizzle.engine.stack :as stack]
    [fizzle.events.lands :as lands]
    [fizzle.test-helpers :as th]))


;; === Test helpers ===

(defn set-phase
  "Set the game phase in the database."
  [db phase]
  (let [conn (d/conn-from-db db)
        game-eid (d/q '[:find ?e . :where [?e :game/id _]] @conn)]
    (d/transact! conn [[:db/add game-eid :game/phase phase]])
    @conn))


(defn set-land-plays
  "Set the land plays remaining for a player."
  [db player-id plays]
  (let [conn (d/conn-from-db db)
        player-eid (q/get-player-eid db player-id)]
    (d/transact! conn [[:db/add player-eid :player/land-plays-left plays]])
    @conn))


(defn get-land-plays
  "Get the land plays remaining for a player."
  [db player-id]
  (d/q '[:find ?plays .
         :in $ ?pid
         :where [?e :player/id ?pid]
         [?e :player/land-plays-left ?plays]]
       db player-id))


;; === play-land tests ===

(deftest test-play-land-moves-to-battlefield
  (testing "play-land moves land card from hand to battlefield"
    (let [db (th/create-test-db)
          [db' obj-id] (th/add-card-to-zone db :city-of-brass :hand :player-1)
          _ (is (= :hand (th/get-object-zone db' obj-id)) "Precondition: land starts in hand")
          db'' (lands/play-land db' :player-1 obj-id)]
      (is (= :battlefield (th/get-object-zone db'' obj-id))
          "Land should be on battlefield after play-land"))))


(deftest test-play-land-decrements-land-plays
  (testing "play-land decrements :player/land-plays-left"
    (let [db (th/create-test-db)
          [db' obj-id] (th/add-card-to-zone db :city-of-brass :hand :player-1)
          _ (is (= 1 (get-land-plays db' :player-1)) "Precondition: land-plays-left = 1")
          db'' (lands/play-land db' :player-1 obj-id)]
      (is (= 0 (get-land-plays db'' :player-1))
          "Land plays should be decremented to 0"))))


(deftest test-play-land-fails-when-no-plays-left
  (testing "play-land returns unchanged db when land-plays-left = 0"
    (let [db (-> (th/create-test-db)
                 (set-land-plays :player-1 0))
          [db' obj-id] (th/add-card-to-zone db :city-of-brass :hand :player-1)
          db'' (lands/play-land db' :player-1 obj-id)]
      (is (= :hand (th/get-object-zone db'' obj-id))
          "Land should remain in hand when no land plays left")
      (is (= 0 (get-land-plays db'' :player-1))
          "Land plays should remain 0"))))


(deftest test-play-land-fails-for-non-land
  (testing "play-land returns unchanged db for non-land card (instant)"
    (let [db (th/create-test-db)
          [db' obj-id] (th/add-card-to-zone db :dark-ritual :hand :player-1)
          db'' (lands/play-land db' :player-1 obj-id)]
      (is (= :hand (th/get-object-zone db'' obj-id))
          "Dark Ritual (instant) should remain in hand")
      (is (= 1 (get-land-plays db'' :player-1))
          "Land plays should not be decremented for non-land"))))


(deftest test-play-land-fails-for-card-not-in-hand
  (testing "play-land returns unchanged db for card in graveyard"
    (let [db (th/create-test-db)
          [db' obj-id] (th/add-card-to-zone db :city-of-brass :graveyard :player-1)
          db'' (lands/play-land db' :player-1 obj-id)]
      (is (= :graveyard (th/get-object-zone db'' obj-id))
          "Land in graveyard should stay in graveyard")
      (is (= 1 (get-land-plays db'' :player-1))
          "Land plays should not be decremented"))))


(deftest test-play-land-fails-outside-main-phase
  (testing "play-land returns unchanged db during combat phase"
    (let [db (-> (th/create-test-db)
                 (set-phase :combat))
          [db' obj-id] (th/add-card-to-zone db :city-of-brass :hand :player-1)
          db'' (lands/play-land db' :player-1 obj-id)]
      (is (= :hand (th/get-object-zone db'' obj-id))
          "Land should remain in hand during combat")
      (is (= 1 (get-land-plays db'' :player-1))
          "Land plays should not be decremented outside main phase"))))


(deftest test-play-land-works-in-main2
  (testing "play-land succeeds during main phase 2"
    (let [db (-> (th/create-test-db)
                 (set-phase :main2))
          [db' obj-id] (th/add-card-to-zone db :city-of-brass :hand :player-1)
          db'' (lands/play-land db' :player-1 obj-id)]
      (is (= :battlefield (th/get-object-zone db'' obj-id))
          "Land should be on battlefield after playing in main2"))))


;; === can-play-land? tests ===

(deftest test-can-play-land-returns-true-when-valid
  (testing "can-play-land? returns true when all conditions met"
    (let [db (th/create-test-db)
          [db' obj-id] (th/add-card-to-zone db :city-of-brass :hand :player-1)]
      (is (true? (lands/can-play-land? db' :player-1 obj-id))
          "Should be able to play land in hand during main phase with plays left"))))


(deftest test-can-play-land-false-when-no-plays-left
  (testing "can-play-land? returns false when land-plays-left = 0"
    (let [db (-> (th/create-test-db)
                 (set-land-plays :player-1 0))
          [db' obj-id] (th/add-card-to-zone db :city-of-brass :hand :player-1)]
      (is (false? (lands/can-play-land? db' :player-1 obj-id))
          "Should not be able to play land when no plays left"))))


(deftest test-can-play-land-false-for-non-land
  (testing "can-play-land? returns false for non-land card"
    (let [db (th/create-test-db)
          [db' obj-id] (th/add-card-to-zone db :dark-ritual :hand :player-1)]
      (is (false? (lands/can-play-land? db' :player-1 obj-id))
          "Should not be able to play-land a spell"))))


(deftest test-can-play-land-false-for-card-not-in-hand
  (testing "can-play-land? returns false for card in graveyard"
    (let [db (th/create-test-db)
          [db' obj-id] (th/add-card-to-zone db :city-of-brass :graveyard :player-1)]
      (is (false? (lands/can-play-land? db' :player-1 obj-id))
          "Should not be able to play land from graveyard"))))


(deftest test-can-play-land-false-outside-main-phase
  (testing "can-play-land? returns false during combat phase"
    (let [db (-> (th/create-test-db)
                 (set-phase :combat))
          [db' obj-id] (th/add-card-to-zone db :city-of-brass :hand :player-1)]
      (is (false? (lands/can-play-land? db' :player-1 obj-id))
          "Should not be able to play land during combat"))))


(deftest test-can-play-land-true-in-main2
  (testing "can-play-land? returns true during main phase 2"
    (let [db (-> (th/create-test-db)
                 (set-phase :main2))
          [db' obj-id] (th/add-card-to-zone db :city-of-brass :hand :player-1)]
      (is (true? (lands/can-play-land? db' :player-1 obj-id))
          "Should be able to play land during main2"))))


(deftest test-play-land-fails-during-upkeep
  ;; Bug caught: play-land only checked :combat, missed other non-main phases
  (testing "play-land returns unchanged db during upkeep phase"
    (let [db (-> (th/create-test-db)
                 (set-phase :upkeep))
          [db' obj-id] (th/add-card-to-zone db :city-of-brass :hand :player-1)
          db'' (lands/play-land db' :player-1 obj-id)]
      (is (= :hand (th/get-object-zone db'' obj-id))
          "Land should remain in hand during upkeep")
      (is (= 1 (get-land-plays db'' :player-1))
          "Land plays should not be decremented during upkeep"))))


(deftest test-play-land-fails-during-end-step
  ;; Bug caught: land playable during end step
  (testing "play-land returns unchanged db during end phase"
    (let [db (-> (th/create-test-db)
                 (set-phase :end))
          [db' obj-id] (th/add-card-to-zone db :city-of-brass :hand :player-1)
          db'' (lands/play-land db' :player-1 obj-id)]
      (is (= :hand (th/get-object-zone db'' obj-id))
          "Land should remain in hand during end step")
      (is (= 1 (get-land-plays db'' :player-1))
          "Land plays should not be decremented during end step"))))


(defn- add-stack-item
  "Add a dummy stack-item to the game state so the stack is non-empty."
  [db player-id]
  (let [conn (d/conn-from-db db)]
    (d/transact! conn [{:stack-item/type :spell
                        :stack-item/controller player-id
                        :stack-item/position (stack/get-next-stack-order db)}])
    @conn))


(deftest test-can-play-land-false-when-stack-non-empty
  (testing "can-play-land? returns false when stack has items"
    (let [db (th/create-test-db)
          [db' obj-id] (th/add-card-to-zone db :city-of-brass :hand :player-1)
          db'' (add-stack-item db' :player-1)]
      (is (false? (lands/can-play-land? db'' :player-1 obj-id))
          "Should not be able to play land with items on the stack"))))


(deftest test-play-land-fails-when-stack-non-empty
  (testing "play-land returns unchanged db when stack has items"
    (let [db (th/create-test-db)
          [db' obj-id] (th/add-card-to-zone db :city-of-brass :hand :player-1)
          db'' (add-stack-item db' :player-1)
          db''' (lands/play-land db'' :player-1 obj-id)]
      (is (= :hand (th/get-object-zone db''' obj-id))
          "Land should remain in hand when stack is non-empty")
      (is (= 1 (get-land-plays db''' :player-1))
          "Land plays should not be decremented when stack is non-empty"))))
