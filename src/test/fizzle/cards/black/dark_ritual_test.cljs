(ns fizzle.cards.black.dark-ritual-test
  "Tests for Dark Ritual - B -> BBB mana acceleration."
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [fizzle.cards.black.dark-ritual :as dark-ritual]
    [fizzle.db.queries :as q]
    [fizzle.engine.mana :as mana]
    [fizzle.engine.rules :as rules]
    [fizzle.test-helpers :as th]))


;; === Card definition test ===

(deftest dark-ritual-card-definition-test
  (testing "Dark Ritual card data is correct"
    (let [card dark-ritual/card]
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
    (let [db (th/create-test-db)
          [db' obj-id] (th/add-card-to-zone db :dark-ritual :hand :player-1)
          db-with-mana (mana/add-mana db' :player-1 {:black 1})
          db-resolved (th/cast-and-resolve db-with-mana :player-1 obj-id)
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
    (let [db (th/create-test-db)
          [db' obj-id] (th/add-card-to-zone db :dark-ritual :hand :player-1)]
      (is (false? (rules/can-cast? db' :player-1 obj-id))
          "Should not be able to cast without mana"))))


(deftest dark-ritual-cannot-cast-from-graveyard-test
  (testing "Dark Ritual cannot be cast from graveyard (no flashback)"
    (let [db (th/create-test-db)
          [db' obj-id] (th/add-card-to-zone db :dark-ritual :graveyard :player-1)
          db-with-mana (mana/add-mana db' :player-1 {:black 1})]
      (is (false? (rules/can-cast? db-with-mana :player-1 obj-id))
          "Should not be able to cast from graveyard"))))


(deftest dark-ritual-increments-storm-count-test
  (testing "casting two Dark Rituals increments storm count to 2"
    (let [db (th/create-test-db)
          [db' first-id] (th/add-card-to-zone db :dark-ritual :hand :player-1)
          [db'' second-id] (th/add-card-to-zone db' :dark-ritual :hand :player-1)
          ;; Add 1B to cast first ritual
          db-with-mana (mana/add-mana db'' :player-1 {:black 1})
          ;; Cast and resolve first ritual (produces BBB)
          db-resolved-1 (th/cast-and-resolve db-with-mana :player-1 first-id)
          ;; Cast and resolve second ritual (costs 1B from the 3B pool)
          db-resolved-2 (th/cast-and-resolve db-resolved-1 :player-1 second-id)
          pool (q/get-mana-pool db-resolved-2 :player-1)]
      ;; Storm count should be 2
      (is (= 2 (q/get-storm-count db-resolved-2 :player-1))
          "Storm count should be 2 after casting two spells")
      ;; Mana: started 1B, cast first (paid 1B, gained 3B = 3B), cast second (paid 1B, gained 3B = 5B)
      (is (= 5 (:black pool))
          "Should have 5 black mana after two rituals (3B - 1B + 3B = 5B)"))))
