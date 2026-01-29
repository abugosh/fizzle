(ns fizzle.events.play-land-test
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [datascript.core :as d]
    [fizzle.cards.iggy-pop :as cards]
    [fizzle.db.queries :as q]
    [fizzle.db.schema :refer [schema]]
    [fizzle.events.game :as game]))


;; === Test helpers ===

(defn create-test-db
  "Create a game state with land cards available.
   Includes city-of-brass and dark-ritual definitions."
  []
  (let [conn (d/create-conn schema)
        ;; Land card definition
        city-of-brass {:card/id :city-of-brass
                       :card/name "City of Brass"
                       :card/cmc 0
                       :card/mana-cost {}
                       :card/colors #{}
                       :card/types #{:land}
                       :card/text "T: Add one mana of any color."}]
    ;; Transact card definitions
    (d/transact! conn [city-of-brass cards/dark-ritual])
    ;; Transact player
    (d/transact! conn [{:player/id :player-1
                        :player/name "Player"
                        :player/life 20
                        :player/mana-pool {:white 0 :blue 0 :black 0
                                           :red 0 :green 0 :colorless 0}
                        :player/storm-count 0
                        :player/land-plays-left 1}])
    ;; Transact game state
    (let [player-eid (d/q '[:find ?e . :where [?e :player/id :player-1]] @conn)]
      (d/transact! conn [{:game/id :game-1
                          :game/turn 1
                          :game/phase :main1
                          :game/active-player player-eid
                          :game/priority player-eid}]))
    @conn))


(defn add-card-to-zone
  "Add a card object to a zone for a player.
   Returns [db object-id] tuple."
  [db card-id zone player-id]
  (let [conn (d/conn-from-db db)
        player-eid (q/get-player-eid db player-id)
        card-eid (d/q '[:find ?e .
                        :in $ ?cid
                        :where [?e :card/id ?cid]]
                      db card-id)
        obj-id (random-uuid)]
    (d/transact! conn [{:object/id obj-id
                        :object/card card-eid
                        :object/zone zone
                        :object/owner player-eid
                        :object/controller player-eid
                        :object/tapped false}])
    [@conn obj-id]))


(defn get-object-zone
  "Get the current zone of an object by its ID."
  [db object-id]
  (:object/zone (q/get-object db object-id)))


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
    (let [db (create-test-db)
          [db' obj-id] (add-card-to-zone db :city-of-brass :hand :player-1)
          _ (is (= :hand (get-object-zone db' obj-id)) "Precondition: land starts in hand")
          db'' (game/play-land db' :player-1 obj-id)]
      (is (= :battlefield (get-object-zone db'' obj-id))
          "Land should be on battlefield after play-land"))))


(deftest test-play-land-decrements-land-plays
  (testing "play-land decrements :player/land-plays-left"
    (let [db (create-test-db)
          [db' obj-id] (add-card-to-zone db :city-of-brass :hand :player-1)
          _ (is (= 1 (get-land-plays db' :player-1)) "Precondition: land-plays-left = 1")
          db'' (game/play-land db' :player-1 obj-id)]
      (is (= 0 (get-land-plays db'' :player-1))
          "Land plays should be decremented to 0"))))


(deftest test-play-land-fails-when-no-plays-left
  (testing "play-land returns unchanged db when land-plays-left = 0"
    (let [db (-> (create-test-db)
                 (set-land-plays :player-1 0))
          [db' obj-id] (add-card-to-zone db :city-of-brass :hand :player-1)
          db'' (game/play-land db' :player-1 obj-id)]
      (is (= :hand (get-object-zone db'' obj-id))
          "Land should remain in hand when no land plays left")
      (is (= 0 (get-land-plays db'' :player-1))
          "Land plays should remain 0"))))


(deftest test-play-land-fails-for-non-land
  (testing "play-land returns unchanged db for non-land card (instant)"
    (let [db (create-test-db)
          [db' obj-id] (add-card-to-zone db :dark-ritual :hand :player-1)
          db'' (game/play-land db' :player-1 obj-id)]
      (is (= :hand (get-object-zone db'' obj-id))
          "Dark Ritual (instant) should remain in hand")
      (is (= 1 (get-land-plays db'' :player-1))
          "Land plays should not be decremented for non-land"))))


(deftest test-play-land-fails-for-card-not-in-hand
  (testing "play-land returns unchanged db for card in graveyard"
    (let [db (create-test-db)
          [db' obj-id] (add-card-to-zone db :city-of-brass :graveyard :player-1)
          db'' (game/play-land db' :player-1 obj-id)]
      (is (= :graveyard (get-object-zone db'' obj-id))
          "Land in graveyard should stay in graveyard")
      (is (= 1 (get-land-plays db'' :player-1))
          "Land plays should not be decremented"))))


(deftest test-play-land-fails-outside-main-phase
  (testing "play-land returns unchanged db during combat phase"
    (let [db (-> (create-test-db)
                 (set-phase :combat))
          [db' obj-id] (add-card-to-zone db :city-of-brass :hand :player-1)
          db'' (game/play-land db' :player-1 obj-id)]
      (is (= :hand (get-object-zone db'' obj-id))
          "Land should remain in hand during combat")
      (is (= 1 (get-land-plays db'' :player-1))
          "Land plays should not be decremented outside main phase"))))


(deftest test-play-land-works-in-main2
  (testing "play-land succeeds during main phase 2"
    (let [db (-> (create-test-db)
                 (set-phase :main2))
          [db' obj-id] (add-card-to-zone db :city-of-brass :hand :player-1)
          db'' (game/play-land db' :player-1 obj-id)]
      (is (= :battlefield (get-object-zone db'' obj-id))
          "Land should be on battlefield after playing in main2"))))
