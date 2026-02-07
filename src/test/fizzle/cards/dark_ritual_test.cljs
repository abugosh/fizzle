(ns fizzle.cards.dark-ritual-test
  "Tests for Dark Ritual - B -> BBB mana acceleration."
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [datascript.core :as d]
    [fizzle.cards.iggy-pop :as cards]
    [fizzle.db.queries :as q]
    [fizzle.db.schema :refer [schema]]
    [fizzle.engine.mana :as mana]
    [fizzle.engine.rules :as rules]))


;; === Test helpers ===

(defn create-test-db
  "Create a game state with all card definitions loaded."
  []
  (let [conn (d/create-conn schema)]
    (d/transact! conn cards/all-cards)
    (d/transact! conn [{:player/id :player-1
                        :player/name "Player"
                        :player/life 20
                        :player/mana-pool {:white 0 :blue 0 :black 0
                                           :red 0 :green 0 :colorless 0}
                        :player/storm-count 0
                        :player/land-plays-left 1}])
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


;; === Card definition test ===

(deftest dark-ritual-card-definition-test
  (testing "Dark Ritual card data is correct"
    (let [card cards/dark-ritual]
      (is (= :dark-ritual (:card/id card)))
      (is (= "Dark Ritual" (:card/name card)))
      (is (= 1 (:card/cmc card)))
      (is (= {:black 1} (:card/mana-cost card)))
      (is (= #{:instant} (:card/types card)))
      (is (= #{:black} (:card/colors card)))
      (is (= 1 (count (:card/effects card))))
      (let [effect (first (:card/effects card))]
        (is (= :add-mana (:effect/type effect)))
        (is (= {:black 3} (:effect/mana effect)))))))


;; === Cast-resolve integration tests ===

(deftest dark-ritual-cast-adds-bbb-mana-test
  (testing "casting and resolving Dark Ritual adds BBB to mana pool"
    (let [db (create-test-db)
          [db' obj-id] (add-card-to-zone db :dark-ritual :hand :player-1)
          db-with-mana (mana/add-mana db' :player-1 {:black 1})
          db-cast (rules/cast-spell db-with-mana :player-1 obj-id)
          db-resolved (rules/resolve-spell db-cast :player-1 obj-id)
          pool (q/get-mana-pool db-resolved :player-1)]
      ;; Paid 1B to cast, gained 3B from effect = 3B in pool
      (is (= 3 (:black pool))
          "Should have 3 black mana after resolving (paid 1B, gained 3B)")
      ;; Instant goes to graveyard after resolution
      (is (= :graveyard (:object/zone (q/get-object db-resolved obj-id)))
          "Dark Ritual should be in graveyard after resolution")
      ;; Storm count should be 1
      (is (= 1 (q/get-storm-count db-resolved :player-1))
          "Storm count should be 1 after casting one spell"))))


(deftest dark-ritual-cannot-cast-without-mana-test
  (testing "Dark Ritual cannot be cast without B mana"
    (let [db (create-test-db)
          [db' obj-id] (add-card-to-zone db :dark-ritual :hand :player-1)]
      (is (false? (rules/can-cast? db' :player-1 obj-id))
          "Should not be able to cast without mana"))))


(deftest dark-ritual-increments-storm-count-test
  (testing "casting two Dark Rituals increments storm count to 2"
    (let [db (create-test-db)
          [db' first-id] (add-card-to-zone db :dark-ritual :hand :player-1)
          [db'' second-id] (add-card-to-zone db' :dark-ritual :hand :player-1)
          ;; Add 1B to cast first ritual
          db-with-mana (mana/add-mana db'' :player-1 {:black 1})
          ;; Cast and resolve first ritual (produces BBB)
          db-cast-1 (rules/cast-spell db-with-mana :player-1 first-id)
          db-resolved-1 (rules/resolve-spell db-cast-1 :player-1 first-id)
          ;; Cast and resolve second ritual (costs 1B from the 3B pool)
          db-cast-2 (rules/cast-spell db-resolved-1 :player-1 second-id)
          db-resolved-2 (rules/resolve-spell db-cast-2 :player-1 second-id)
          pool (q/get-mana-pool db-resolved-2 :player-1)]
      ;; Storm count should be 2
      (is (= 2 (q/get-storm-count db-resolved-2 :player-1))
          "Storm count should be 2 after casting two spells")
      ;; Mana: started 1B, cast first (paid 1B, gained 3B = 3B), cast second (paid 1B, gained 3B = 5B)
      (is (= 5 (:black pool))
          "Should have 5 black mana after two rituals (3B - 1B + 3B = 5B)"))))
