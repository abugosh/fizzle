(ns fizzle.cards.lands.crystal-vein-test
  "Tests for Crystal Vein land card.

   Crystal Vein: Land
   {T}: Add {C}.
   {T}, Sacrifice this land: Add {C}{C}.

   Exercises multi-mana-ability dispatch where both abilities produce the
   same color (colorless). Reaching ability 1 requires passing ability-index
   to engine-mana/activate-mana-ability."
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [datascript.core :as d]
    [fizzle.cards.lands.crystal-vein :as crystal-vein]
    [fizzle.db.queries :as q]
    [fizzle.engine.mana-activation :as engine-mana]
    [fizzle.test-helpers :as th]))


;; === A. Card Definition ===

(deftest test-crystal-vein-card-definition
  (testing "Crystal Vein identity and core fields"
    (is (= :crystal-vein (:card/id crystal-vein/card))
        "Card id should be :crystal-vein")
    (is (= "Crystal Vein" (:card/name crystal-vein/card))
        "Card name should be 'Crystal Vein'")
    (is (= 0 (:card/cmc crystal-vein/card))
        "CMC should be 0")
    (is (= {} (:card/mana-cost crystal-vein/card))
        "Mana cost should be empty map (land)")
    (is (= #{} (:card/colors crystal-vein/card))
        "Colors should be empty set (colorless land)")
    (is (= #{:land} (:card/types crystal-vein/card))
        "Crystal Vein should be a land")
    (is (= "{T}: Add {C}.\n{T}, Sacrifice this land: Add {C}{C}."
           (:card/text crystal-vein/card))
        "Card text should match oracle")
    (is (= 2 (count (:card/abilities crystal-vein/card)))
        "Crystal Vein should have exactly 2 abilities"))

  (testing "Ability 0: {T}: Add {C}."
    (let [a0 (first (:card/abilities crystal-vein/card))]
      (is (= :mana (:ability/type a0))
          "Ability 0 should be a mana ability")
      (is (= {:tap true} (:ability/cost a0))
          "Ability 0 cost should be exactly {:tap true}")
      (is (= {:colorless 1} (:ability/produces a0))
          "Ability 0 should produce exactly 1 colorless")))

  (testing "Ability 1: {T}, Sacrifice Crystal Vein: Add {C}{C}."
    (let [a1 (second (:card/abilities crystal-vein/card))]
      (is (= :mana (:ability/type a1))
          "Ability 1 should be a mana ability (not :activated)")
      (is (= {:tap true :sacrifice-self true} (:ability/cost a1))
          "Ability 1 cost should be tap + sacrifice-self")
      (is (= {:colorless 2} (:ability/produces a1))
          "Ability 1 should produce exactly 2 colorless"))))


;; === B. Ability 0 Activation (Tap for {C}) ===

(deftest test-crystal-vein-tap-produces-one-colorless
  ;; Oracle: "{T}: Add {C}."
  (testing "Tapping Crystal Vein adds 1 colorless mana"
    (let [db (th/create-test-db)
          [db' obj-id] (th/add-card-to-zone db :crystal-vein :battlefield :player-1)
          _ (is (= 0 (:colorless (q/get-mana-pool db' :player-1)))
                "Precondition: colorless mana is 0")
          db'' (engine-mana/activate-mana-ability db' :player-1 obj-id :colorless)]
      (is (= 1 (:colorless (q/get-mana-pool db'' :player-1)))
          "Colorless mana should be 1 after tap")
      (is (= :battlefield (th/get-object-zone db'' obj-id))
          "Crystal Vein should still be on battlefield after tap")
      (let [obj (q/get-object db'' obj-id)]
        (is (true? (:object/tapped obj))
            "Crystal Vein should be tapped")))))


(deftest test-crystal-vein-tap-ability-0-by-explicit-index
  ;; Engine contract: passing ability-index 0 is equivalent to color-based dispatch.
  (testing "Activating ability 0 explicitly by index matches color-based dispatch"
    (let [db (th/create-test-db)
          [db' obj-id] (th/add-card-to-zone db :crystal-vein :battlefield :player-1)
          db'' (engine-mana/activate-mana-ability db' :player-1 obj-id :colorless 0)]
      (is (= 1 (:colorless (q/get-mana-pool db'' :player-1)))
          "Ability 0 should produce 1 colorless")
      (is (= :battlefield (th/get-object-zone db'' obj-id))
          "Ability 0 does NOT sacrifice the land"))))


;; === C. Ability 1 Activation (Tap + Sacrifice for {C}{C}) ===

(deftest test-crystal-vein-sacrifice-produces-two-colorless
  ;; Oracle: "{T}, Sacrifice this land: Add {C}{C}."
  (testing "Tap + sacrifice adds 2 colorless mana and sends land to graveyard"
    (let [db (th/create-test-db)
          [db' obj-id] (th/add-card-to-zone db :crystal-vein :battlefield :player-1)
          _ (is (= 0 (:colorless (q/get-mana-pool db' :player-1)))
                "Precondition: colorless mana is 0")
          db'' (engine-mana/activate-mana-ability db' :player-1 obj-id :colorless 1)]
      (is (= 2 (:colorless (q/get-mana-pool db'' :player-1)))
          "Colorless mana should be 2 after tap+sacrifice")
      (is (= :graveyard (th/get-object-zone db'' obj-id))
          "Crystal Vein should be in graveyard after sacrifice"))))


;; === D. Cannot Activate When Already Tapped ===

(deftest test-crystal-vein-ability-0-cannot-activate-when-tapped
  (testing "Already-tapped Crystal Vein cannot activate ability 0"
    (let [db (th/create-test-db)
          [db' obj-id] (th/add-card-to-zone db :crystal-vein :battlefield :player-1)
          obj-eid (d/q '[:find ?e . :in $ ?oid :where [?e :object/id ?oid]] db' obj-id)
          db-tapped (d/db-with db' [[:db/add obj-eid :object/tapped true]])
          db-after (engine-mana/activate-mana-ability db-tapped :player-1 obj-id :colorless)]
      (is (= 0 (:colorless (q/get-mana-pool db-after :player-1)))
          "No mana should be added when already tapped"))))


(deftest test-crystal-vein-ability-1-cannot-activate-when-tapped
  (testing "Already-tapped Crystal Vein cannot activate ability 1 either"
    (let [db (th/create-test-db)
          [db' obj-id] (th/add-card-to-zone db :crystal-vein :battlefield :player-1)
          obj-eid (d/q '[:find ?e . :in $ ?oid :where [?e :object/id ?oid]] db' obj-id)
          db-tapped (d/db-with db' [[:db/add obj-eid :object/tapped true]])
          db-after (engine-mana/activate-mana-ability db-tapped :player-1 obj-id :colorless 1)]
      (is (= 0 (:colorless (q/get-mana-pool db-after :player-1)))
          "No mana should be added when already tapped")
      (is (= :battlefield (th/get-object-zone db-after obj-id))
          "Land should NOT be sacrificed when tap cost cannot be paid"))))


;; === E. Wrong Zone Guards ===

(deftest test-crystal-vein-cannot-activate-from-graveyard
  (testing "Crystal Vein in graveyard cannot produce mana"
    (let [db (th/create-test-db)
          [db' obj-id] (th/add-card-to-zone db :crystal-vein :graveyard :player-1)
          db-a0 (engine-mana/activate-mana-ability db' :player-1 obj-id :colorless)
          db-a1 (engine-mana/activate-mana-ability db' :player-1 obj-id :colorless 1)]
      (is (= 0 (:colorless (q/get-mana-pool db-a0 :player-1)))
          "Ability 0 should not fire from graveyard")
      (is (= 0 (:colorless (q/get-mana-pool db-a1 :player-1)))
          "Ability 1 should not fire from graveyard"))))


(deftest test-crystal-vein-cannot-activate-from-hand
  (testing "Crystal Vein in hand cannot produce mana"
    (let [db (th/create-test-db)
          [db' obj-id] (th/add-card-to-zone db :crystal-vein :hand :player-1)
          db-after (engine-mana/activate-mana-ability db' :player-1 obj-id :colorless)]
      (is (= 0 (:colorless (q/get-mana-pool db-after :player-1)))
          "No mana should be added from hand")
      (is (= :hand (th/get-object-zone db-after obj-id))
          "Card should remain in hand"))))


;; === F. Out-of-Range ability-index ===

(deftest test-crystal-vein-out-of-range-ability-index-is-noop
  ;; Engine contract: passing an ability-index that doesn't resolve to a mana
  ;; ability leaves the db unchanged rather than silently firing the wrong one.
  (testing "Passing out-of-range ability-index produces no mana and does not tap"
    (let [db (th/create-test-db)
          [db' obj-id] (th/add-card-to-zone db :crystal-vein :battlefield :player-1)
          db-after (engine-mana/activate-mana-ability db' :player-1 obj-id :colorless 99)]
      (is (= 0 (:colorless (q/get-mana-pool db-after :player-1)))
          "No mana should be added for out-of-range index")
      (is (false? (boolean (:object/tapped (q/get-object db-after obj-id))))
          "Land should not be tapped")
      (is (= :battlefield (th/get-object-zone db-after obj-id))
          "Land should still be on battlefield"))))
