(ns fizzle.cards.blue.stifle-test
  "Tests for Stifle card.

   Stifle (U): Instant
   Counter target activated or triggered ability. (Mana abilities can't be targeted.)"
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [datascript.core :as d]
    [fizzle.cards.blue.stifle :as stifle]
    [fizzle.db.queries :as q]
    [fizzle.engine.effects :as effects]
    [fizzle.engine.rules :as rules]
    [fizzle.engine.stack :as stack]
    [fizzle.events.selection.targeting :as sel-targeting]
    [fizzle.test-helpers :as th]))


;; === File-specific helpers ===

(defn create-fake-activated-ability-stack-item
  "Create a fake activated ability stack-item for testing.
   Returns [db stack-item-eid]."
  [db player-id ability-type]
  (let [conn (d/conn-from-db db)
        stack-item-id (random-uuid)
        ;; Get current highest position on stack to place this item above
        existing-positions (d/q '[:find [?p ...]
                                  :where [?e :stack-item/position ?p]]
                                @conn)
        next-position (if (empty? existing-positions)
                        0
                        (inc (apply max existing-positions)))
        stack-item {:stack-item/id stack-item-id
                    :stack-item/type :activated-ability
                    :stack-item/ability-type ability-type
                    :stack-item/controller player-id
                    :stack-item/position next-position
                    :stack-item/effects []}]
    (d/transact! conn [stack-item])
    (let [eid (d/q '[:find ?e . :in $ ?sid
                     :where [?e :stack-item/id ?sid]]
                   @conn stack-item-id)]
      [@conn eid])))


(defn create-fake-triggered-ability-stack-item
  "Create a fake triggered ability stack-item for testing.
   Returns [db stack-item-eid]."
  [db player-id]
  (let [conn (d/conn-from-db db)
        stack-item-id (random-uuid)
        ;; Get current highest position on stack to place this item above
        existing-positions (d/q '[:find [?p ...]
                                  :where [?e :stack-item/position ?p]]
                                @conn)
        next-position (if (empty? existing-positions)
                        0
                        (inc (apply max existing-positions)))
        stack-item {:stack-item/id stack-item-id
                    :stack-item/type :triggered-ability
                    :stack-item/controller player-id
                    :stack-item/position next-position
                    :stack-item/effects []}]
    (d/transact! conn [stack-item])
    (let [eid (d/q '[:find ?e . :in $ ?sid
                     :where [?e :stack-item/id ?sid]]
                   @conn stack-item-id)]
      [@conn eid])))


;; === A. Card Definition Tests ===

;; Oracle: "Stifle" / {U} / Instant
(deftest stifle-card-definition-test
  (testing "Stifle card fields match Scryfall data"
    (let [card stifle/card]
      (is (= :stifle (:card/id card)))
      (is (= "Stifle" (:card/name card)))
      (is (= 1 (:card/cmc card)))
      (is (= {:blue 1} (:card/mana-cost card)))
      (is (= #{:blue} (:card/colors card)))
      (is (= #{:instant} (:card/types card)))
      (is (= "Counter target activated or triggered ability. (Mana abilities can't be targeted.)"
             (:card/text card)))))


  ;; Oracle: "Counter target activated or triggered ability"
  (testing "Stifle uses counter-ability effect"
    (let [effects (:card/effects stifle/card)]
      (is (= 1 (count effects))
          "Should have exactly 1 effect")
      (let [effect (first effects)]
        (is (= :counter-ability (:effect/type effect)))
        (is (= :ability (:effect/target-ref effect))
            "Effect references ability target"))))


  ;; Oracle: "target activated or triggered ability"
  (testing "Stifle has ability targeting"
    (let [targeting (:card/targeting stifle/card)]
      (is (vector? targeting)
          "Targeting must be a vector")
      (is (= 1 (count targeting))
          "Should have exactly one target requirement")
      (let [req (first targeting)]
        (is (= :ability (:target/id req)))
        (is (= :ability (:target/type req)))
        (is (true? (:target/required req)))
        ;; Note: Targeting options for ability filtering not yet implemented
        ))))


;; === B. Cast-Resolve Happy Path ===

;; Regression: Stifle must be castable when there's a non-mana ability on stack
(deftest stifle-castable-with-ability-on-stack-test
  (testing "can-cast? returns true when non-mana activated ability is on stack"
    (let [db (-> (th/create-test-db {:mana {:blue 1}})
                 (th/add-opponent))
          [db _ability-eid] (create-fake-activated-ability-stack-item db :player-2 :other)
          [db stifle-id] (th/add-card-to-zone db :stifle :hand :player-1)]
      (is (true? (rules/can-cast? db :player-1 stifle-id))
          "Stifle should be castable with U and non-mana ability on stack")))

  (testing "can-cast? returns true when triggered ability is on stack"
    (let [db (-> (th/create-test-db {:mana {:blue 1}})
                 (th/add-opponent))
          [db _ability-eid] (create-fake-triggered-ability-stack-item db :player-2)
          [db stifle-id] (th/add-card-to-zone db :stifle :hand :player-1)]
      (is (true? (rules/can-cast? db :player-1 stifle-id))
          "Stifle should be castable with triggered ability on stack")))

  (testing "can-cast? returns false when no abilities on stack"
    (let [db (-> (th/create-test-db {:mana {:blue 1}})
                 (th/add-opponent))
          [db stifle-id] (th/add-card-to-zone db :stifle :hand :player-1)]
      (is (false? (rules/can-cast? db :player-1 stifle-id))
          "Stifle should not be castable with no abilities on stack")))

  (testing "can-cast? returns false when only mana ability on stack"
    (let [db (-> (th/create-test-db {:mana {:blue 1}})
                 (th/add-opponent))
          [db _ability-eid] (create-fake-activated-ability-stack-item db :player-2 :mana)
          [db stifle-id] (th/add-card-to-zone db :stifle :hand :player-1)]
      (is (false? (rules/can-cast? db :player-1 stifle-id))
          "Stifle should not be castable when only mana abilities exist"))))


;; Oracle: "Counter target activated or triggered ability"
(deftest stifle-counters-activated-ability-test
  (testing "Stifle effect counters an activated ability on the stack"
    (let [db (th/create-test-db)
          ;; Create a fake activated ability on stack
          [db ability-eid] (create-fake-activated-ability-stack-item db :player-1 :other)
          _ (is (= :activated-ability (:stack-item/type (d/pull db '[*] ability-eid)))
                "Precondition: activated ability on stack")
          _ (is (some? (d/pull db '[*] ability-eid))
                "Precondition: ability is on stack")
          ;; Execute counter-ability effect
          db-after (effects/execute-effect db :player-1
                                           {:effect/type :counter-ability
                                            :effect/target ability-eid})]
      ;; Ability should be removed from stack
      (is (nil? (:stack-item/id (d/pull db-after [:stack-item/id] ability-eid)))
          "Activated ability should be removed from stack"))))


(deftest stifle-counters-triggered-ability-test
  (testing "Stifle effect counters a triggered ability on the stack"
    (let [db (th/create-test-db)
          ;; Create a fake triggered ability on stack
          [db ability-eid] (create-fake-triggered-ability-stack-item db :player-1)
          _ (is (= :triggered-ability (:stack-item/type (d/pull db '[*] ability-eid)))
                "Precondition: triggered ability on stack")
          _ (is (some? (d/pull db '[*] ability-eid))
                "Precondition: ability is on stack")
          ;; Execute counter-ability effect
          db-after (effects/execute-effect db :player-1
                                           {:effect/type :counter-ability
                                            :effect/target ability-eid})]
      ;; Ability should be removed from stack
      (is (nil? (:stack-item/id (d/pull db-after [:stack-item/id] ability-eid)))
          "Triggered ability should be removed from stack"))))


;; === C. Cannot-Cast Guards ===

;; Oracle: mana cost {U} — cannot cast without U
(deftest stifle-cannot-cast-without-mana-test
  (testing "Cannot cast Stifle without blue mana"
    (let [db (th/create-test-db)
          [db stifle-id] (th/add-card-to-zone db :stifle :hand :player-1)]
      (is (false? (rules/can-cast? db :player-1 stifle-id))
          "Should not be castable without blue mana"))))


;; Instant must be cast from hand
(deftest stifle-cannot-cast-from-graveyard-test
  (testing "Cannot cast Stifle from graveyard"
    (let [db (th/create-test-db {:mana {:blue 1}})
          [db stifle-id] (th/add-card-to-zone db :stifle :graveyard :player-1)]
      (is (false? (rules/can-cast? db :player-1 stifle-id))
          "Should not be castable from graveyard"))))


;; === D. Storm Count ===

(deftest stifle-increments-storm-count-test
  (testing "Casting Stifle increments storm count"
    (let [db (th/create-test-db {:mana {:blue 1}})
          [db stifle-id] (th/add-card-to-zone db :stifle :hand :player-1)
          storm-before (q/get-storm-count db :player-1)
          db-cast (rules/cast-spell db :player-1 stifle-id)]
      (is (= (inc storm-before) (q/get-storm-count db-cast :player-1))
          "Storm count should increment by 1"))))


;; === E. Selection Tests ===

;; Oracle: "Counter target activated or triggered ability"
(deftest stifle-targeting-creates-ability-cast-selection-test
  (testing "Casting Stifle creates :ability-cast-targeting selection"
    (let [db (-> (th/create-test-db {:mana {:blue 1}})
                 (th/add-opponent))
          [db ability-eid] (create-fake-activated-ability-stack-item db :player-2 :other)
          [db stifle-id] (th/add-card-to-zone db :stifle :hand :player-1)
          result (sel-targeting/cast-spell-with-targeting db :player-1 stifle-id)
          sel (:pending-target-selection result)]
      (is (some? sel)
          "Should create a targeting selection")
      (is (= :ability-cast-targeting (:selection/type sel))
          "Selection type should be :ability-cast-targeting for ability targets")
      (is (= [ability-eid] (:selection/valid-targets sel))
          "Valid targets should include the ability on stack"))))


;; === F. Targeting Tests ===

;; Oracle: "(Mana abilities can't be targeted.)"
(deftest stifle-cannot-counter-mana-ability-test
  (testing "Stifle effect cannot counter mana abilities"
    (let [db (th/create-test-db)
          ;; Create a fake mana ability stack-item (hypothetical, since mana abilities don't use stack)
          [db mana-ability-eid] (create-fake-activated-ability-stack-item db :player-1 :mana)
          _ (is (= :mana (:stack-item/ability-type (d/pull db '[*] mana-ability-eid)))
                "Precondition: mana ability")
          ;; Try to counter the mana ability
          db-after (effects/execute-effect db :player-1
                                           {:effect/type :counter-ability
                                            :effect/target mana-ability-eid})]
      ;; Mana ability should still exist (counter-ability is a no-op for mana abilities)
      (is (some? (:stack-item/id (d/pull db-after [:stack-item/id] mana-ability-eid)))
          "Mana ability should not be countered"))))


;; === G. Edge Cases ===

;; Edge case: Target ability already resolved
(deftest stifle-target-already-resolved-test
  (testing "Stifle targeting an ability that's already gone is a no-op"
    (let [db (th/create-test-db {:mana {:blue 1}})
          ;; Create a fake stack-item EID that doesn't exist
          nonexistent-eid 99999
          db-after (effects/execute-effect db :player-1
                                           {:effect/type :counter-ability
                                            :effect/target nonexistent-eid})]
      ;; Should be a no-op (returns db unchanged)
      (is (= db db-after)
          "Should be no-op when target doesn't exist"))))


;; Edge case: Target is a spell, not an ability
(deftest stifle-cannot-counter-spells-test
  (testing "Stifle cannot counter spells (only abilities)"
    (let [db (th/create-test-db {:mana {:blue 2}})
          [db bolt-id] (th/add-card-to-zone db :lightning-bolt :hand :player-1)
          ;; Cast Lightning Bolt (it's a spell, not an ability)
          db-cast (rules/cast-spell db :player-1 bolt-id)
          bolt-obj-eid (q/get-object-eid db-cast bolt-id)
          bolt-stack-item (stack/get-stack-item-by-object-ref db-cast bolt-obj-eid)
          bolt-stack-eid (:db/id bolt-stack-item)
          _ (is (= :spell (:stack-item/type bolt-stack-item))
                "Precondition: spell on stack")
          ;; Try to counter it with counter-ability effect
          db-after (effects/execute-effect db-cast :player-1
                                           {:effect/type :counter-ability
                                            :effect/target bolt-stack-eid})]
      ;; Spell should still be on stack (counter-ability only works on abilities)
      (is (some? (d/pull db-after '[*] bolt-stack-eid))
          "Spell should not be countered by counter-ability effect"))))
