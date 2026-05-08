(ns fizzle.cards.black.songs-of-the-damned-test
  "Tests for Songs of the Damned — {B} Instant: Add {B} for each creature card in your graveyard."
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [fizzle.cards.black.songs-of-the-damned :as songs-of-the-damned]
    [fizzle.db.queries :as q]
    [fizzle.engine.mana :as mana]
    [fizzle.engine.rules :as rules]
    [fizzle.test-helpers :as th]))


;; === A. Card definition ===

(deftest songs-of-the-damned-card-definition-test
  (testing "Songs of the Damned card data is correct"
    (let [card songs-of-the-damned/card]
      (is (= :songs-of-the-damned (:card/id card)))
      (is (= "Songs of the Damned" (:card/name card)))
      (is (= 1 (:card/cmc card)))
      (is (= {:black 1} (:card/mana-cost card)))
      (is (= #{:instant} (:card/types card)))
      (is (= #{:black} (:card/colors card)))
      (is (= "Add {B} for each creature card in your graveyard." (:card/text card)))
      (is (= 1 (count (:card/effects card))))
      (let [effect (first (:card/effects card))]
        (is (= :add-mana (:effect/type effect)))
        (is (= {:black {:dynamic/type :count-type-in-zone
                        :dynamic/zone :graveyard
                        :dynamic/card-type :creature}}
               (:effect/mana effect)))))))


;; === B. Cast-resolve happy path ===

;; Oracle: "Add {B} for each creature card in your graveyard."
(deftest songs-of-the-damned-adds-mana-per-creature-test
  (testing "Songs of the Damned adds {B} per creature card in graveyard"
    (let [db (th/create-test-db)
          [db _] (th/add-cards-to-graveyard db [:nimble-mongoose :xantid-swarm :cloud-of-faeries] :player-1)
          [db obj-id] (th/add-card-to-zone db :songs-of-the-damned :hand :player-1)
          db (mana/add-mana db :player-1 {:black 1})
          db-resolved (th/cast-and-resolve db :player-1 obj-id)
          pool (q/get-mana-pool db-resolved :player-1)]
      (is (= 3 (:black pool))
          "Should have 3 black mana (paid 1B, gained 3B from 3 creatures)"))))


;; Oracle: "Add {B} for each creature card in your graveyard."
(deftest songs-of-the-damned-single-creature-test
  (testing "Songs of the Damned adds 1B with one creature in graveyard"
    (let [db (th/create-test-db)
          [db _] (th/add-cards-to-graveyard db [:nimble-mongoose] :player-1)
          [db obj-id] (th/add-card-to-zone db :songs-of-the-damned :hand :player-1)
          db (mana/add-mana db :player-1 {:black 1})
          db-resolved (th/cast-and-resolve db :player-1 obj-id)
          pool (q/get-mana-pool db-resolved :player-1)]
      (is (= 1 (:black pool))
          "Should have 1 black mana (paid 1B, gained 1B from 1 creature)"))))


;; === C. Cannot-cast guards ===

(deftest songs-of-the-damned-cannot-cast-without-mana-test
  (testing "Cannot cast Songs of the Damned without mana"
    (let [db (th/create-test-db)
          [db obj-id] (th/add-card-to-zone db :songs-of-the-damned :hand :player-1)]
      (is (false? (rules/can-cast? db :player-1 obj-id))))))


(deftest songs-of-the-damned-cannot-cast-from-graveyard-test
  (testing "Cannot cast Songs of the Damned from graveyard"
    (let [db (th/create-test-db {:mana {:black 1}})
          [db obj-id] (th/add-card-to-zone db :songs-of-the-damned :graveyard :player-1)]
      (is (false? (rules/can-cast? db :player-1 obj-id))))))


;; === D. Storm count ===

(deftest songs-of-the-damned-increments-storm-count-test
  (testing "Casting Songs of the Damned increments storm count"
    (let [db (th/create-test-db)
          [db obj-id] (th/add-card-to-zone db :songs-of-the-damned :hand :player-1)
          db (mana/add-mana db :player-1 {:black 1})
          _ (is (= 0 (q/get-storm-count db :player-1)))
          db-resolved (th/cast-and-resolve db :player-1 obj-id)]
      (is (= 1 (q/get-storm-count db-resolved :player-1))))))


;; === G. Edge cases ===

;; Oracle: "Add {B} for each creature card in your graveyard."
;; Edge: zero creatures = zero mana added
(deftest songs-of-the-damned-zero-creatures-test
  (testing "Songs of the Damned adds 0 mana with no creatures in graveyard"
    (let [db (th/create-test-db)
          [db obj-id] (th/add-card-to-zone db :songs-of-the-damned :hand :player-1)
          db (mana/add-mana db :player-1 {:black 1})
          db-resolved (th/cast-and-resolve db :player-1 obj-id)
          pool (q/get-mana-pool db-resolved :player-1)]
      (is (= 0 (:black pool))
          "Should have 0 black mana (paid 1B, gained 0B from 0 creatures)"))))


;; Oracle: "creature card" — only creature type cards count, not non-creature cards
(deftest songs-of-the-damned-ignores-non-creature-cards-test
  (testing "Non-creature cards in graveyard don't count"
    (let [db (th/create-test-db)
          [db _] (th/add-cards-to-graveyard db [:dark-ritual :cabal-ritual :lotus-petal] :player-1)
          [db obj-id] (th/add-card-to-zone db :songs-of-the-damned :hand :player-1)
          db (mana/add-mana db :player-1 {:black 1})
          db-resolved (th/cast-and-resolve db :player-1 obj-id)
          pool (q/get-mana-pool db-resolved :player-1)]
      (is (= 0 (:black pool))
          "Should have 0 black mana (3 non-creature cards don't count)"))))


;; Oracle: "creature card" — mixed graveyard, only creatures count
(deftest songs-of-the-damned-mixed-graveyard-test
  (testing "Only creature cards count in mixed graveyard"
    (let [db (th/create-test-db)
          [db _] (th/add-cards-to-graveyard
                   db [:nimble-mongoose :dark-ritual :xantid-swarm :cabal-ritual] :player-1)
          [db obj-id] (th/add-card-to-zone db :songs-of-the-damned :hand :player-1)
          db (mana/add-mana db :player-1 {:black 1})
          db-resolved (th/cast-and-resolve db :player-1 obj-id)
          pool (q/get-mana-pool db-resolved :player-1)]
      (is (= 2 (:black pool))
          "Should have 2 black mana (2 creatures out of 4 cards)"))))


;; Oracle: "creature card" — artifact creatures should count
(deftest songs-of-the-damned-counts-artifact-creatures-test
  (testing "Artifact creatures count as creature cards"
    (let [db (th/create-test-db)
          [db _] (th/add-cards-to-graveyard db [:phyrexian-devourer] :player-1)
          [db obj-id] (th/add-card-to-zone db :songs-of-the-damned :hand :player-1)
          db (mana/add-mana db :player-1 {:black 1})
          db-resolved (th/cast-and-resolve db :player-1 obj-id)
          pool (q/get-mana-pool db-resolved :player-1)]
      (is (= 1 (:black pool))
          "Should count artifact creature (Phyrexian Devourer)"))))


;; Edge: Songs of the Damned itself goes to graveyard after resolving,
;; but it's an instant (not a creature) so it doesn't count
(deftest songs-of-the-damned-in-graveyard-after-resolve-test
  (testing "Songs of the Damned goes to graveyard but doesn't count itself"
    (let [db (th/create-test-db)
          [db obj-id] (th/add-card-to-zone db :songs-of-the-damned :hand :player-1)
          db (mana/add-mana db :player-1 {:black 1})
          db-resolved (th/cast-and-resolve db :player-1 obj-id)]
      (is (= :graveyard (:object/zone (q/get-object db-resolved obj-id)))
          "Songs of the Damned should be in graveyard after resolution"))))
