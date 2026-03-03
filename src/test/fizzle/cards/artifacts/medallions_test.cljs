(ns fizzle.cards.artifacts.medallions-test
  "Tests for the Medallion cycle — all five medallions.

   Each Medallion: {2} - Artifact
   [Color] spells you cast cost {1} less to cast."
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [fizzle.cards.artifacts.medallions :as medallions]
    [fizzle.db.queries :as q]
    [fizzle.engine.card-spec :as card-spec]
    [fizzle.engine.rules :as rules]
    [fizzle.engine.static-abilities :as static-abilities]
    [fizzle.test-helpers :as th]))


;; Medallion cycle data: [def card-id name color test-spell test-spell-base-cost off-color-spell]
;; test-spell: a spell of the medallion's color with generic mana in cost
;; off-color-spell: a spell of a different color (should not be reduced)
(def medallion-cycle
  [[medallions/sapphire-medallion :sapphire-medallion "Sapphire Medallion" :blue
    :accumulated-knowledge {:colorless 1 :blue 1} :dark-ritual]
   [medallions/jet-medallion :jet-medallion "Jet Medallion" :black
    :cabal-ritual {:colorless 1 :black 1} :counterspell]
   [medallions/ruby-medallion :ruby-medallion "Ruby Medallion" :red
    :burning-wish {:colorless 1 :red 1} :dark-ritual]
   [medallions/emerald-medallion :emerald-medallion "Emerald Medallion" :green
    :crumble {:green 1} :dark-ritual]
   [medallions/pearl-medallion :pearl-medallion "Pearl Medallion" :white
    :seal-of-cleansing {:colorless 1 :white 1} :dark-ritual]])


;; =====================================================
;; A. Card Definition Tests (cycle doseq)
;; =====================================================

(deftest medallion-card-definitions-test
  (doseq [[card card-id card-name color _spell _spell-cost _off-color] medallion-cycle]
    (testing (str card-name " has correct card fields")
      (is (= card-id (:card/id card)))
      (is (= card-name (:card/name card)))
      (is (= 2 (:card/cmc card)))
      (is (= {:colorless 2} (:card/mana-cost card)))
      (is (= #{} (:card/colors card)))
      (is (= #{:artifact} (:card/types card)))
      (is (string? (:card/text card)))
      (is (.includes (:card/text card) "cost {1} less to cast")
          "Oracle text should mention cost reduction"))

    (testing (str card-name " has correct static ability")
      (let [abilities (:card/static-abilities card)]
        (is (= 1 (count abilities))
            "Should have exactly 1 static ability")
        (let [ability (first abilities)]
          (is (= :cost-modifier (:static/type ability)))
          (is (= 1 (:modifier/amount ability)))
          (is (= :decrease (:modifier/direction ability)))
          (is (= :controller (:modifier/applies-to ability)))
          (is (= {:criteria/type :spell-color
                  :criteria/colors #{color}}
                 (:modifier/criteria ability))))))))


(deftest medallion-cards-vector-test
  (testing "cards vector contains exactly 5 medallions"
    (is (= 5 (count medallions/cards)))
    (is (= #{:emerald-medallion :jet-medallion :pearl-medallion
             :ruby-medallion :sapphire-medallion}
           (set (map :card/id medallions/cards))))))


(deftest medallion-spec-validation-test
  (testing "All medallions pass card spec validation"
    (doseq [[card _card-id card-name] medallion-cycle]
      (is (card-spec/valid-card? card)
          (str card-name " should pass spec validation")))))


;; =====================================================
;; B. Cast-Resolve Happy Path
;; =====================================================

(deftest medallion-cast-and-resolve-test
  (doseq [[_card card-id card-name] medallion-cycle]
    (testing (str card-name " enters battlefield when cast and resolved")
      (let [db (th/create-test-db {:mana {:colorless 2}})
            [db obj-id] (th/add-card-to-zone db card-id :hand :player-1)
            db (th/cast-and-resolve db :player-1 obj-id)]
        (is (= :battlefield (th/get-object-zone db obj-id))
            (str card-name " should be on battlefield"))
        (is (= {:white 0 :blue 0 :black 0 :red 0 :green 0 :colorless 0}
               (q/get-mana-pool db :player-1))
            "Mana pool should be empty after paying {2}")))))


;; =====================================================
;; C. Cannot-Cast Guards
;; =====================================================

(deftest medallion-cannot-cast-without-mana-test
  (doseq [[_card card-id card-name] medallion-cycle]
    (testing (str card-name " not castable without mana")
      (let [db (th/create-test-db)
            [db obj-id] (th/add-card-to-zone db card-id :hand :player-1)]
        (is (false? (rules/can-cast? db :player-1 obj-id))
            (str card-name " should not be castable without mana"))))))


(deftest medallion-cannot-cast-with-insufficient-mana-test
  (doseq [[_card card-id card-name] medallion-cycle]
    (testing (str card-name " not castable with only 1 mana")
      (let [db (th/create-test-db {:mana {:colorless 1}})
            [db obj-id] (th/add-card-to-zone db card-id :hand :player-1)]
        (is (false? (rules/can-cast? db :player-1 obj-id))
            (str card-name " should not be castable with only 1 colorless"))))))


;; =====================================================
;; D. Storm Count
;; =====================================================

(deftest medallion-cast-increments-storm-test
  (doseq [[_card card-id card-name] medallion-cycle]
    (testing (str "Casting " card-name " increments storm count")
      (let [db (th/create-test-db {:mana {:colorless 2}})
            [db obj-id] (th/add-card-to-zone db card-id :hand :player-1)
            _ (is (= 0 (q/get-storm-count db :player-1)))
            db (rules/cast-spell db :player-1 obj-id)]
        (is (= 1 (q/get-storm-count db :player-1))
            "Storm count should be 1 after casting medallion")))))


;; =====================================================
;; Cost Reduction: apply-cost-modifiers
;; =====================================================

(deftest medallion-reduces-matching-color-spell-test
  (doseq [[_card card-id card-name _color test-spell test-spell-cost] medallion-cycle]
    (testing (str card-name " reduces cost of matching-color spell")
      (let [db (th/create-test-db)
            [db _] (th/add-card-to-zone db card-id :battlefield :player-1)
            [db obj-id] (th/add-card-to-zone db test-spell :hand :player-1)
            spell-card (:object/card (q/get-object db obj-id))
            result (static-abilities/apply-cost-modifiers db :player-1 spell-card test-spell-cost)
            expected-colorless (max 0 (- (get test-spell-cost :colorless 0) 1))]
        (is (= expected-colorless (:colorless result))
            (str card-name " should reduce :colorless by 1 (floored at 0)"))))))


(deftest medallion-does-not-reduce-off-color-spell-test
  (doseq [[_card card-id card-name _color _test-spell _test-cost off-color-spell] medallion-cycle]
    (testing (str card-name " does not reduce off-color spell")
      (let [db (th/create-test-db)
            [db _] (th/add-card-to-zone db card-id :battlefield :player-1)
            [db obj-id] (th/add-card-to-zone db off-color-spell :hand :player-1)
            spell-card (:object/card (q/get-object db obj-id))
            base-cost (:card/mana-cost spell-card)
            result (static-abilities/apply-cost-modifiers db :player-1 spell-card base-cost)]
        (is (= base-cost result)
            (str card-name " should not reduce off-color spell cost"))))))


;; =====================================================
;; Integration: can-cast? with Medallion
;; =====================================================

(deftest medallion-enables-cheaper-casting-test
  (testing "Sapphire Medallion lets Accumulated Knowledge be cast for just {U}"
    (let [db (th/create-test-db {:mana {:blue 1}})
          [db _] (th/add-card-to-zone db :sapphire-medallion :battlefield :player-1)
          [db obj-id] (th/add-card-to-zone db :accumulated-knowledge :hand :player-1)]
      (is (rules/can-cast? db :player-1 obj-id)
          "Accumulated Knowledge should be castable for {U} with Sapphire Medallion")))

  (testing "Accumulated Knowledge NOT castable for {U} without medallion"
    (let [db (th/create-test-db {:mana {:blue 1}})
          [db obj-id] (th/add-card-to-zone db :accumulated-knowledge :hand :player-1)]
      (is (false? (rules/can-cast? db :player-1 obj-id))
          "Accumulated Knowledge needs {1}{U} without medallion"))))


;; =====================================================
;; Ownership: Medallion only affects controller
;; =====================================================

(deftest medallion-does-not-affect-opponent-test
  (testing "Sapphire Medallion on player-1 does NOT reduce player-2's blue spells"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          [db _] (th/add-card-to-zone db :sapphire-medallion :battlefield :player-1)
          [db obj-id] (th/add-card-to-zone db :counterspell :hand :player-2)
          spell-card (:object/card (q/get-object db obj-id))
          result (static-abilities/apply-cost-modifiers db :player-2 spell-card {:blue 2})]
      (is (= {:blue 2} result)
          "Opponent's spell should not be reduced by player's medallion"))))


;; =====================================================
;; Interaction: Medallion + Sphere of Resistance
;; =====================================================

(deftest medallion-and-sphere-cancel-test
  (testing "Sapphire Medallion + Sphere of Resistance cancel out for blue spells"
    (let [db (th/create-test-db)
          [db _] (th/add-card-to-zone db :sapphire-medallion :battlefield :player-1)
          [db _] (th/add-card-to-zone db :sphere-of-resistance :battlefield :player-1)
          [db obj-id] (th/add-card-to-zone db :counterspell :hand :player-1)
          spell-card (:object/card (q/get-object db obj-id))
          result (static-abilities/apply-cost-modifiers db :player-1 spell-card {:blue 2})]
      ;; Sphere adds 1 colorless, Medallion removes 1 → net 0
      (is (= {:blue 2 :colorless 0} result)
          "Sphere +1 and Medallion -1 should cancel for blue spells")))

  (testing "Sapphire Medallion + Sphere: non-blue spell still pays Sphere tax"
    (let [db (th/create-test-db)
          [db _] (th/add-card-to-zone db :sapphire-medallion :battlefield :player-1)
          [db _] (th/add-card-to-zone db :sphere-of-resistance :battlefield :player-1)
          [db obj-id] (th/add-card-to-zone db :dark-ritual :hand :player-1)
          spell-card (:object/card (q/get-object db obj-id))
          result (static-abilities/apply-cost-modifiers db :player-1 spell-card {:black 1})]
      ;; Sphere adds 1, Medallion doesn't apply (black, not blue) → net +1
      (is (= {:black 1 :colorless 1} result)
          "Non-blue spell still pays Sphere tax with Sapphire Medallion"))))


(deftest medallion-and-sphere-can-cast-integration-test
  (testing "Medallion cancels Sphere for matching color — can-cast? check"
    (let [db (th/create-test-db {:mana {:blue 1 :colorless 1}})
          [db _] (th/add-card-to-zone db :sapphire-medallion :battlefield :player-1)
          [db _] (th/add-card-to-zone db :sphere-of-resistance :battlefield :player-1)
          [db obj-id] (th/add-card-to-zone db :accumulated-knowledge :hand :player-1)]
      ;; Base cost {1}{U}, Sphere +1, Medallion -1 → {1}{U} → needs {U}+{1}
      (is (rules/can-cast? db :player-1 obj-id)
          "Accumulated Knowledge castable with {U}{1} when Sphere+Medallion cancel"))))


;; =====================================================
;; Interaction: Multiple Medallions Stack
;; =====================================================

(deftest multiple-medallions-stack-test
  (testing "Two Sapphire Medallions reduce blue spell cost by 2"
    (let [db (th/create-test-db)
          [db _] (th/add-card-to-zone db :sapphire-medallion :battlefield :player-1)
          [db _] (th/add-card-to-zone db :sapphire-medallion :battlefield :player-1)
          [db obj-id] (th/add-card-to-zone db :counterspell :hand :player-1)
          spell-card (:object/card (q/get-object db obj-id))
          result (static-abilities/apply-cost-modifiers db :player-1 spell-card {:colorless 2 :blue 1})]
      (is (= {:colorless 0 :blue 1} result)
          "Two medallions should reduce :colorless by 2")))

  (testing "Two Sapphire Medallions floor at 0 for Counterspell {UU}"
    (let [db (th/create-test-db)
          [db _] (th/add-card-to-zone db :sapphire-medallion :battlefield :player-1)
          [db _] (th/add-card-to-zone db :sapphire-medallion :battlefield :player-1)
          [db obj-id] (th/add-card-to-zone db :counterspell :hand :player-1)
          spell-card (:object/card (q/get-object db obj-id))
          ;; Counterspell base cost is {:blue 2}, no :colorless
          result (static-abilities/apply-cost-modifiers db :player-1 spell-card {:blue 2})]
      (is (= {:blue 2 :colorless 0} result)
          "Cannot reduce below 0 colorless, even with 2 medallions"))))


(deftest multiple-medallions-can-cast-integration-test
  (testing "Two Sapphire Medallions make {1}{U} spell cost just {U}"
    (let [db (th/create-test-db {:mana {:blue 1}})
          [db _] (th/add-card-to-zone db :sapphire-medallion :battlefield :player-1)
          [db _] (th/add-card-to-zone db :sapphire-medallion :battlefield :player-1)
          [db obj-id] (th/add-card-to-zone db :accumulated-knowledge :hand :player-1)]
      ;; Accumulated Knowledge base cost {:colorless 1 :blue 1}
      ;; Two medallions reduce by 2, but base generic is only 1 → {0}{U}
      (is (rules/can-cast? db :player-1 obj-id)
          "Accumulated Knowledge castable with just {U} and two medallions"))))
