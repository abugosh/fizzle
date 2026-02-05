(ns fizzle.cards.flash-of-insight-test
  "Tests for Flash of Insight card definition.

   Verifies card structure matches oracle text and uses correct
   codebase patterns for X costs, flashback, and exile costs."
  (:require
    [cljs.test :refer-macros [deftest is testing]]
    [fizzle.cards.flash-of-insight :as flash-of-insight]))


(deftest flash-of-insight-card-structure-test
  ;; Bug caught: card not exported or malformed
  (testing "Flash of Insight card has required fields"
    (let [card flash-of-insight/flash-of-insight]
      (is (= :flash-of-insight (:card/id card))
          "Card ID must be :flash-of-insight")
      (is (= "Flash of Insight" (:card/name card))
          "Card name must match oracle")
      (is (= #{:instant} (:card/types card))
          "Must be instant")
      (is (= #{:blue} (:card/colors card))
          "Must be blue")
      (is (true? (:x (:card/mana-cost card)))
          "Mana cost must have :x true for X cost")
      (is (= 1 (:colorless (:card/mana-cost card)))
          "Mana cost must have 1 colorless")
      (is (= 1 (:blue (:card/mana-cost card)))
          "Mana cost must have 1 blue"))))


(deftest flash-of-insight-normal-cast-effect-test
  ;; Bug caught: effect misconfigured, wrong effect type
  (testing "Normal cast uses :peek-and-select effect"
    (let [effect (first (:card/effects flash-of-insight/flash-of-insight))]
      (is (= :peek-and-select (:effect/type effect))
          "Effect must be :peek-and-select")
      (is (= :x (:effect/count effect))
          "Effect count must be :x to use X value")
      (is (= 1 (:effect/select-count effect))
          "Must select exactly 1 card")
      (is (= :hand (:effect/selected-zone effect))
          "Selected card goes to hand")
      (is (= :bottom-of-library (:effect/remainder-zone effect))
          "Non-selected go to bottom")
      (is (true? (:effect/shuffle-remainder? effect))
          "Remainder should be shuffled (random order)"))))


(deftest flash-of-insight-flashback-cost-test
  ;; Bug caught: missing flashback, wrong mana cost, missing exile cost
  (testing "Flashback has correct costs"
    (let [alternate (first (:card/alternate-costs flash-of-insight/flash-of-insight))]
      (is (= :flashback (:alternate/id alternate))
          "Must have :alternate/id :flashback")
      (is (= :graveyard (:alternate/zone alternate))
          "Flashback casts from graveyard")
      (is (= {:colorless 1 :blue 1} (:alternate/mana-cost alternate))
          "Flashback mana cost is {1}{U}")
      (is (= :exile (:alternate/on-resolve alternate))
          "Spell exiles after flashback resolution"))))


(deftest flash-of-insight-exile-additional-cost-test
  ;; Bug caught: exile cost missing or misconfigured
  (testing "Flashback has exile blue cards additional cost"
    (let [alternate (first (:card/alternate-costs flash-of-insight/flash-of-insight))
          exile-cost (first (:alternate/additional-costs alternate))]
      (is (= :exile-cards (:cost/type exile-cost))
          "Additional cost type must be :exile-cards")
      (is (= :graveyard (:cost/zone exile-cost))
          "Exile from graveyard")
      (is (= #{:blue} (:card/colors (:cost/criteria exile-cost)))
          "Must exile blue cards")
      (is (= :x (:cost/count exile-cost))
          "Exile count is X (player chooses)"))))
