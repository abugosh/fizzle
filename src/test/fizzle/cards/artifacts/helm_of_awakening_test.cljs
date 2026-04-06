(ns fizzle.cards.artifacts.helm-of-awakening-test
  "Tests for Helm of Awakening.

   Helm of Awakening: {2} - Artifact
   Spells cost {1} less to cast."
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [fizzle.cards.artifacts.helm-of-awakening :as helm]
    [fizzle.db.queries :as q]
    [fizzle.engine.card-spec :as card-spec]
    [fizzle.engine.mana :as mana]
    [fizzle.engine.rules :as rules]
    [fizzle.engine.static-abilities :as static-abilities]
    [fizzle.test-helpers :as th]))


;; =====================================================
;; A. Card Definition Tests
;; =====================================================

(deftest helm-of-awakening-card-definition-test
  (testing "Helm of Awakening card definition is complete and correct"
    (let [card helm/card]
      (is (= :helm-of-awakening (:card/id card)))
      (is (= "Helm of Awakening" (:card/name card)))
      (is (= 2 (:card/cmc card)))
      (is (= {:colorless 2} (:card/mana-cost card)))
      (is (= #{} (:card/colors card)))
      (is (= #{:artifact} (:card/types card)))
      (is (= "Spells cost {1} less to cast." (:card/text card)))))

  (testing "Helm has exactly one static ability with correct fields"
    (let [abilities (:card/static-abilities helm/card)]
      (is (= 1 (count abilities)))
      (let [ability (first abilities)]
        (is (= :cost-modifier (:static/type ability)))
        (is (= 1 (:modifier/amount ability)))
        (is (= :decrease (:modifier/direction ability)))
        (is (= :all (:modifier/applies-to ability)))
        (is (nil? (:modifier/criteria ability))
            "Helm has no criteria — applies to all spells")
        (is (nil? (:modifier/condition ability))
            "Helm has no condition")))))


(deftest helm-spec-validation-test
  (testing "Helm passes card spec validation"
    (is (card-spec/valid-card? helm/card))))


;; =====================================================
;; B. Cast-Resolve Happy Path
;; =====================================================

(deftest helm-cast-and-resolve-test
  (testing "Helm enters battlefield when cast and resolved"
    (let [db (th/create-test-db {:mana {:colorless 2}})
          [db obj-id] (th/add-card-to-zone db :helm-of-awakening :hand :player-1)
          db (th/cast-and-resolve db :player-1 obj-id)]
      (is (= :battlefield (th/get-object-zone db obj-id)))
      (is (= {:white 0 :blue 0 :black 0 :red 0 :green 0 :colorless 0}
             (q/get-mana-pool db :player-1))))))


;; =====================================================
;; C. Cannot-Cast Guards
;; =====================================================

(deftest helm-cannot-cast-without-mana-test
  (testing "Helm not castable without mana"
    (let [db (th/create-test-db)
          [db obj-id] (th/add-card-to-zone db :helm-of-awakening :hand :player-1)]
      (is (false? (rules/can-cast? db :player-1 obj-id))))))


(deftest helm-cannot-cast-with-insufficient-mana-test
  (testing "Helm not castable with only 1 mana"
    (let [db (th/create-test-db {:mana {:colorless 1}})
          [db obj-id] (th/add-card-to-zone db :helm-of-awakening :hand :player-1)]
      (is (false? (rules/can-cast? db :player-1 obj-id))))))


;; =====================================================
;; D. Storm Count
;; =====================================================

(deftest helm-cast-increments-storm-test
  (testing "Casting Helm increments storm count"
    (let [db (th/create-test-db {:mana {:colorless 2}})
          [db obj-id] (th/add-card-to-zone db :helm-of-awakening :hand :player-1)
          _ (is (= 0 (q/get-storm-count db :player-1)))
          db (th/cast-and-resolve db :player-1 obj-id)]
      (is (= 1 (q/get-storm-count db :player-1))))))


;; =====================================================
;; Cost Reduction: Helm reduces all spells
;; =====================================================

(deftest helm-reduces-spell-with-generic-cost-test
  (testing "Helm reduces :colorless by 1"
    (let [db (th/create-test-db)
          [db _] (th/add-card-to-zone db :helm-of-awakening :battlefield :player-1)
          [db obj-id] (th/add-card-to-zone db :accumulated-knowledge :hand :player-1)
          spell-card (:object/card (q/get-object db obj-id))
          result (static-abilities/apply-cost-modifiers db :player-1 spell-card {:colorless 1 :blue 1})]
      (is (= {:colorless 0 :blue 1} result)
          "Accumulated Knowledge {1}{U} becomes {U} with Helm"))))


(deftest helm-reduces-colorless-only-spell-test
  (testing "Helm reduces colorless-only cost"
    (let [db (th/create-test-db)
          [db _] (th/add-card-to-zone db :helm-of-awakening :battlefield :player-1)
          [db obj-id] (th/add-card-to-zone db :sphere-of-resistance :hand :player-1)
          spell-card (:object/card (q/get-object db obj-id))
          result (static-abilities/apply-cost-modifiers db :player-1 spell-card {:colorless 2})]
      (is (= {:colorless 1} result)
          "Sphere {2} becomes {1} with Helm"))))


(deftest helm-floors-at-zero-test
  (testing "Helm on pure-colored cost floors at 0"
    (let [db (th/create-test-db)
          [db _] (th/add-card-to-zone db :helm-of-awakening :battlefield :player-1)
          [db obj-id] (th/add-card-to-zone db :dark-ritual :hand :player-1)
          spell-card (:object/card (q/get-object db obj-id))
          result (static-abilities/apply-cost-modifiers db :player-1 spell-card {:black 1})]
      (is (= {:black 1 :colorless 0} result)
          "Dark Ritual {B} with Helm: no generic to reduce"))))


(deftest helm-reduces-any-color-spell-test
  (testing "Helm reduces spells of every color (no color restriction)"
    (let [db (th/create-test-db)
          [db _] (th/add-card-to-zone db :helm-of-awakening :battlefield :player-1)]
      (doseq [[spell-id cost description]
              [[:accumulated-knowledge {:colorless 1 :blue 1} "blue spell"]
               [:cabal-ritual {:colorless 1 :black 1} "black spell"]
               [:burning-wish {:colorless 1 :red 1} "red spell"]
               [:seal-of-cleansing {:colorless 1 :white 1} "white spell"]]]
        (let [[db' obj-id] (th/add-card-to-zone db spell-id :hand :player-1)
              spell-card (:object/card (q/get-object db' obj-id))
              result (static-abilities/apply-cost-modifiers db' :player-1 spell-card cost)]
          (is (= 0 (:colorless result))
              (str "Helm should reduce " description " generic by 1")))))))


;; =====================================================
;; Helm Affects Both Players (epic requirement)
;; =====================================================

(deftest helm-affects-both-players-test
  (testing "Helm on player-1's battlefield reduces player-2's spells too"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          [db _] (th/add-card-to-zone db :helm-of-awakening :battlefield :player-1)
          [db obj-id] (th/add-card-to-zone db :accumulated-knowledge :hand :player-2)
          spell-card (:object/card (q/get-object db obj-id))
          result (static-abilities/apply-cost-modifiers db :player-2 spell-card {:colorless 1 :blue 1})]
      (is (= {:colorless 0 :blue 1} result)
          "Opponent's spell should also be reduced by player's Helm")))

  (testing "Helm on player-1's battlefield: player-2 can cast cheaper"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          [db _] (th/add-card-to-zone db :helm-of-awakening :battlefield :player-1)
          [db obj-id] (th/add-card-to-zone db :accumulated-knowledge :hand :player-2)
          db (mana/add-mana db :player-2 {:blue 1})]
      (is (rules/can-cast? db :player-2 obj-id)
          "Opponent should cast {1}{U} spell for {U} with player's Helm"))))


(deftest helm-vs-medallion-ownership-contrast-test
  (testing "Helm (:all) vs Medallion (:controller) — contrasting ownership behavior"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          ;; Player-1 has both Helm and Sapphire Medallion
          [db _] (th/add-card-to-zone db :helm-of-awakening :battlefield :player-1)
          [db _] (th/add-card-to-zone db :sapphire-medallion :battlefield :player-1)
          [db obj-id] (th/add-card-to-zone db :accumulated-knowledge :hand :player-2)
          spell-card (:object/card (q/get-object db obj-id))
          result (static-abilities/apply-cost-modifiers db :player-2 spell-card {:colorless 1 :blue 1})]
      ;; Helm applies (affects all), Medallion does NOT (controller only)
      ;; So opponent gets -1 from Helm only
      (is (= {:colorless 0 :blue 1} result)
          "Opponent gets Helm reduction but NOT Medallion reduction"))))


;; =====================================================
;; Interaction: Helm + Sphere of Resistance
;; =====================================================

(deftest helm-and-sphere-cancel-test
  (testing "Helm + Sphere cancel out for all spells"
    (let [db (th/create-test-db)
          [db _] (th/add-card-to-zone db :helm-of-awakening :battlefield :player-1)
          [db _] (th/add-card-to-zone db :sphere-of-resistance :battlefield :player-1)
          [db obj-id] (th/add-card-to-zone db :dark-ritual :hand :player-1)
          spell-card (:object/card (q/get-object db obj-id))
          result (static-abilities/apply-cost-modifiers db :player-1 spell-card {:black 1})]
      ;; Sphere +1, Helm -1 → net 0
      (is (= {:black 1 :colorless 0} result)
          "Sphere +1 and Helm -1 should cancel"))))


(deftest helm-and-sphere-can-cast-test
  (testing "Dark Ritual castable for {B} when Helm and Sphere cancel"
    (let [db (th/create-test-db {:mana {:black 1}})
          [db _] (th/add-card-to-zone db :helm-of-awakening :battlefield :player-1)
          [db _] (th/add-card-to-zone db :sphere-of-resistance :battlefield :player-1)
          [db obj-id] (th/add-card-to-zone db :dark-ritual :hand :player-1)]
      (is (rules/can-cast? db :player-1 obj-id)
          "Helm and Sphere cancel — Dark Ritual castable for {B}"))))


;; =====================================================
;; Interaction: Helm + Medallion Stack
;; =====================================================

(deftest helm-and-medallion-stack-test
  (testing "Helm + Sapphire Medallion reduce blue spell by 2 total"
    (let [db (th/create-test-db)
          [db _] (th/add-card-to-zone db :helm-of-awakening :battlefield :player-1)
          [db _] (th/add-card-to-zone db :sapphire-medallion :battlefield :player-1)
          [db obj-id] (th/add-card-to-zone db :accumulated-knowledge :hand :player-1)
          spell-card (:object/card (q/get-object db obj-id))
          result (static-abilities/apply-cost-modifiers db :player-1 spell-card {:colorless 2 :blue 1})]
      (is (= {:colorless 0 :blue 1} result)
          "Helm -1 + Medallion -1 = -2 total on blue spell")))

  (testing "Helm + Medallion on non-matching color: only Helm applies"
    (let [db (th/create-test-db)
          [db _] (th/add-card-to-zone db :helm-of-awakening :battlefield :player-1)
          [db _] (th/add-card-to-zone db :sapphire-medallion :battlefield :player-1)
          [db obj-id] (th/add-card-to-zone db :dark-ritual :hand :player-1)
          spell-card (:object/card (q/get-object db obj-id))
          result (static-abilities/apply-cost-modifiers db :player-1 spell-card {:colorless 1 :black 1})]
      (is (= {:colorless 0 :black 1} result)
          "Non-blue spell gets only Helm reduction, not Medallion"))))


;; =====================================================
;; G. Edge Cases
;; =====================================================

(deftest helm-in-graveyard-no-effect-test
  (testing "Helm in graveyard does not modify costs"
    (let [db (th/create-test-db {:mana {:black 1}})
          [db _] (th/add-card-to-zone db :helm-of-awakening :graveyard :player-1)
          [db obj-id] (th/add-card-to-zone db :dark-ritual :hand :player-1)]
      (is (rules/can-cast? db :player-1 obj-id)
          "Dark Ritual castable for {B} when Helm is in graveyard"))))


(deftest helm-on-stack-no-effect-test
  (testing "Helm on stack (being cast) does not modify costs"
    (let [db (th/create-test-db {:mana {:colorless 2 :black 1}})
          [db helm-id] (th/add-card-to-zone db :helm-of-awakening :hand :player-1)
          [db dr-id] (th/add-card-to-zone db :dark-ritual :hand :player-1)
          ;; rules/cast-spell required — Helm must stay on stack to test it has no effect while being cast
          db (rules/cast-spell db :player-1 helm-id)
          _ (is (= :stack (th/get-object-zone db helm-id)))
          db (mana/add-mana db :player-1 {:black 1})]
      (is (rules/can-cast? db :player-1 dr-id)
          "Dark Ritual castable for {B} when Helm is on stack"))))


(deftest multiple-helms-stack-test
  (testing "Two Helms reduce by 2"
    (let [db (th/create-test-db)
          [db _] (th/add-card-to-zone db :helm-of-awakening :battlefield :player-1)
          [db _] (th/add-card-to-zone db :helm-of-awakening :battlefield :player-1)
          [db obj-id] (th/add-card-to-zone db :accumulated-knowledge :hand :player-1)
          spell-card (:object/card (q/get-object db obj-id))
          result (static-abilities/apply-cost-modifiers db :player-1 spell-card {:colorless 3 :blue 1})]
      (is (= {:colorless 1 :blue 1} result)
          "Two Helms should reduce :colorless by 2"))))
