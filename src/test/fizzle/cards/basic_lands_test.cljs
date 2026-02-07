(ns fizzle.cards.basic-lands-test
  "Tests for basic land cards (Island, Swamp)."
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [datascript.core :as d]
    [fizzle.cards.iggy-pop :as cards]
    [fizzle.db.queries :as q]
    [fizzle.db.schema :refer [schema]]
    [fizzle.events.abilities :as ability-events]
    [fizzle.events.game :as game]))


;; === Test helpers ===

(defn create-test-db
  "Create a game state with all card definitions loaded."
  []
  (let [conn (d/create-conn schema)]
    ;; Transact all card definitions
    (d/transact! conn cards/all-cards)
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


(defn add-land-to-battlefield
  "Add a land card to the battlefield for a player.
   Returns [db object-id] tuple."
  [db card-id player-id]
  (let [conn (d/conn-from-db db)
        player-eid (q/get-player-eid db player-id)
        card-eid (d/q '[:find ?e .
                        :in $ ?cid
                        :where [?e :card/id ?cid]]
                      db card-id)
        obj-id (random-uuid)]
    (d/transact! conn [{:object/id obj-id
                        :object/card card-eid
                        :object/zone :battlefield
                        :object/owner player-eid
                        :object/controller player-eid
                        :object/tapped false}])
    [@conn obj-id]))


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


(defn get-object-tapped
  "Get the tapped state of an object by its ID."
  [db object-id]
  (:object/tapped (q/get-object db object-id)))


(defn get-object-zone
  "Get the current zone of an object by its ID."
  [db object-id]
  (:object/zone (q/get-object db object-id)))


(defn get-land-plays
  "Get the land plays remaining for a player."
  [db player-id]
  (d/q '[:find ?plays .
         :in $ ?pid
         :where [?e :player/id ?pid]
         [?e :player/land-plays-left ?plays]]
       db player-id))


;; === Island tests ===

(deftest test-island-taps-for-blue-mana
  (testing "Island taps for blue mana when activated"
    (let [db (create-test-db)
          [db' obj-id] (add-land-to-battlefield db :island :player-1)
          _ (is (false? (get-object-tapped db' obj-id))
                "Precondition: Island starts untapped")
          initial-pool (q/get-mana-pool db' :player-1)
          _ (is (= 0 (:blue initial-pool)) "Precondition: blue mana is 0")
          db'' (ability-events/activate-mana-ability db' :player-1 obj-id :blue)]
      (is (true? (get-object-tapped db'' obj-id))
          "Island should be tapped after activating mana ability")
      (is (= 1 (:blue (q/get-mana-pool db'' :player-1)))
          "Blue mana should be added to pool"))))


;; === Swamp tests ===

(deftest test-swamp-taps-for-black-mana
  (testing "Swamp taps for black mana when activated"
    (let [db (create-test-db)
          [db' obj-id] (add-land-to-battlefield db :swamp :player-1)
          _ (is (false? (get-object-tapped db' obj-id))
                "Precondition: Swamp starts untapped")
          initial-pool (q/get-mana-pool db' :player-1)
          _ (is (= 0 (:black initial-pool)) "Precondition: black mana is 0")
          db'' (ability-events/activate-mana-ability db' :player-1 obj-id :black)]
      (is (true? (get-object-tapped db'' obj-id))
          "Swamp should be tapped after activating mana ability")
      (is (= 1 (:black (q/get-mana-pool db'' :player-1)))
          "Black mana should be added to pool"))))


;; === Playing from hand tests ===

(deftest test-basic-land-played-from-hand
  (testing "Island can be played from hand and enters battlefield untapped"
    (let [db (create-test-db)
          [db' obj-id] (add-card-to-zone db :island :hand :player-1)
          _ (is (= :hand (get-object-zone db' obj-id)) "Precondition: Island starts in hand")
          _ (is (= 1 (get-land-plays db' :player-1)) "Precondition: land-plays-left = 1")
          db'' (game/play-land db' :player-1 obj-id)]
      (is (= :battlefield (get-object-zone db'' obj-id))
          "Island should be on battlefield after play-land")
      (is (= 0 (get-land-plays db'' :player-1))
          "Land plays should be decremented to 0"))))


;; === Edge case: tapped land cannot tap again ===

(deftest test-tapped-land-cannot-tap-again
  (testing "Already tapped Island cannot be tapped again for mana"
    (let [db (create-test-db)
          [db' obj-id] (add-land-to-battlefield db :island :player-1)
          ;; First tap produces mana
          db-after-first-tap (ability-events/activate-mana-ability db' :player-1 obj-id :blue)
          _ (is (= 1 (:blue (q/get-mana-pool db-after-first-tap :player-1)))
                "Precondition: first tap adds mana")
          _ (is (true? (get-object-tapped db-after-first-tap obj-id))
                "Precondition: land is tapped")
          ;; Second tap should fail (no additional mana)
          db-after-second-tap (ability-events/activate-mana-ability db-after-first-tap :player-1 obj-id :blue)]
      (is (= 1 (:blue (q/get-mana-pool db-after-second-tap :player-1)))
          "Second tap should NOT add more mana")
      (is (true? (get-object-tapped db-after-second-tap obj-id))
          "Land should still be tapped"))))


;; === Edge case: land in hand cannot activate mana ===

(deftest test-land-in-hand-cannot-activate-mana
  (testing "Island in hand cannot activate mana ability"
    (let [db (create-test-db)
          [db' obj-id] (add-card-to-zone db :island :hand :player-1)
          _ (is (= :hand (get-object-zone db' obj-id)) "Precondition: Island is in hand")
          initial-pool (q/get-mana-pool db' :player-1)
          _ (is (= 0 (:blue initial-pool)) "Precondition: blue mana is 0")
          ;; Attempt to activate mana ability from hand
          db'' (ability-events/activate-mana-ability db' :player-1 obj-id :blue)]
      (is (= 0 (:blue (q/get-mana-pool db'' :player-1)))
          "Mana should NOT be added (land not on battlefield)")
      (is (= :hand (get-object-zone db'' obj-id))
          "Island should remain in hand"))))
