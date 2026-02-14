(ns fizzle.cards.cabal-ritual-test
  "Tests for Cabal Ritual - 1B -> BBB, or BBBBB with threshold."
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [fizzle.cards.iggy-pop :as cards]
    [fizzle.db.queries :as q]
    [fizzle.engine.mana :as mana]
    [fizzle.engine.rules :as rules]
    [fizzle.test-helpers :as th]))


;; === Card definition test ===

(deftest cabal-ritual-card-definition-test
  (testing "Cabal Ritual card data is correct"
    (let [card cards/cabal-ritual]
      (is (= :cabal-ritual (:card/id card)))
      (is (= "Cabal Ritual" (:card/name card)))
      (is (= 2 (:card/cmc card)))
      (is (= {:colorless 1 :black 1} (:card/mana-cost card)))
      (is (= #{:instant} (:card/types card)))
      (is (= #{:black} (:card/colors card)))
      ;; Normal effects
      (is (= 1 (count (:card/effects card))))
      (let [effect (first (:card/effects card))]
        (is (= :add-mana (:effect/type effect)))
        (is (= {:black 3} (:effect/mana effect))))
      ;; Conditional threshold effects
      (let [threshold-effects (get-in card [:card/conditional-effects :threshold])]
        (is (= 1 (count threshold-effects)))
        (is (= :add-mana (:effect/type (first threshold-effects))))
        (is (= {:black 5} (:effect/mana (first threshold-effects))))))))


;; === Cast-resolve integration tests ===

(deftest cabal-ritual-produces-bbb-without-threshold-test
  (testing "Cabal Ritual produces BBB without threshold (< 7 graveyard cards)"
    (let [db (th/create-test-db)
          [db' obj-id] (th/add-card-to-zone db :cabal-ritual :hand :player-1)
          ;; Add 2B to pay for {1}{B} cost
          db-with-mana (mana/add-mana db' :player-1 {:black 2})
          db-cast (rules/cast-spell db-with-mana :player-1 obj-id)
          db-resolved (rules/resolve-spell db-cast :player-1 obj-id)
          pool (q/get-mana-pool db-resolved :player-1)]
      ;; Paid 2B, gained 3B = 3B in pool
      (is (= 3 (:black pool))
          "Should have 3 black mana (paid 2B, gained 3B)")
      (is (= :graveyard (:object/zone (q/get-object db-resolved obj-id)))
          "Cabal Ritual should be in graveyard after resolution"))))


(deftest cabal-ritual-produces-bbbbb-with-threshold-test
  (testing "Cabal Ritual produces BBBBB with threshold (7+ graveyard cards)"
    (let [db (th/create-test-db)
          ;; Add 7 cards to graveyard for threshold
          [db-with-gy _] (th/add-cards-to-graveyard db (vec (repeat 7 :dark-ritual)) :player-1)
          [db' obj-id] (th/add-card-to-zone db-with-gy :cabal-ritual :hand :player-1)
          db-with-mana (mana/add-mana db' :player-1 {:black 2})
          db-cast (rules/cast-spell db-with-mana :player-1 obj-id)
          db-resolved (rules/resolve-spell db-cast :player-1 obj-id)
          pool (q/get-mana-pool db-resolved :player-1)]
      ;; Paid 2B, gained 5B (threshold) = 5B in pool
      (is (= 5 (:black pool))
          "Should have 5 black mana with threshold (paid 2B, gained 5B)"))))


(deftest cabal-ritual-threshold-exactly-7-cards-test
  (testing "Cabal Ritual threshold fires at exactly 7 graveyard cards"
    (let [db (th/create-test-db)
          [db-with-gy _] (th/add-cards-to-graveyard db (vec (repeat 7 :dark-ritual)) :player-1)
          ;; Verify exactly 7 cards in graveyard
          gy-count (count (q/get-objects-in-zone db-with-gy :player-1 :graveyard))
          _ (is (= 7 gy-count) "Precondition: exactly 7 cards in graveyard")
          [db' obj-id] (th/add-card-to-zone db-with-gy :cabal-ritual :hand :player-1)
          db-with-mana (mana/add-mana db' :player-1 {:black 2})
          db-cast (rules/cast-spell db-with-mana :player-1 obj-id)
          db-resolved (rules/resolve-spell db-cast :player-1 obj-id)
          pool (q/get-mana-pool db-resolved :player-1)]
      (is (= 5 (:black pool))
          "Threshold should fire at exactly 7 cards (>= 7)"))))


(deftest cabal-ritual-threshold-6-cards-no-threshold-test
  (testing "Cabal Ritual does NOT get threshold at 6 graveyard cards"
    (let [db (th/create-test-db)
          [db-with-gy _] (th/add-cards-to-graveyard db (vec (repeat 6 :dark-ritual)) :player-1)
          ;; Verify exactly 6 cards in graveyard
          gy-count (count (q/get-objects-in-zone db-with-gy :player-1 :graveyard))
          _ (is (= 6 gy-count) "Precondition: exactly 6 cards in graveyard")
          [db' obj-id] (th/add-card-to-zone db-with-gy :cabal-ritual :hand :player-1)
          db-with-mana (mana/add-mana db' :player-1 {:black 2})
          db-cast (rules/cast-spell db-with-mana :player-1 obj-id)
          db-resolved (rules/resolve-spell db-cast :player-1 obj-id)
          pool (q/get-mana-pool db-resolved :player-1)]
      (is (= 3 (:black pool))
          "Should have 3B without threshold (only 6 graveyard cards, need 7)"))))
