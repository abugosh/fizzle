(ns fizzle.engine.card-spec-test
  (:require
    [cljs.test :refer [deftest is testing]]
    [fizzle.engine.card-spec :as card-spec]
    [fizzle.engine.cards :as cards]))


(deftest test-valid-card-passes
  (testing "minimal valid card definition passes spec"
    (let [card {:card/id :test-spell
                :card/name "Test Spell"
                :card/cmc 1
                :card/mana-cost {:black 1}
                :card/colors #{:black}
                :card/types #{:instant}
                :card/text "Test."
                :card/effects [{:effect/type :add-mana
                                :effect/mana {:black 3}}]}]
      (is (= true (card-spec/valid-card? card))))))


(deftest test-missing-required-field-fails
  (testing "card without :card/name fails"
    (let [card {:card/id :bad-card
                :card/cmc 1
                :card/mana-cost {:black 1}
                :card/colors #{:black}
                :card/types #{:instant}
                :card/text "Test."}]
      (is (= false (card-spec/valid-card? card)))))

  (testing "card without :card/id fails"
    (let [card {:card/name "Bad Card"
                :card/cmc 1
                :card/mana-cost {:black 1}
                :card/colors #{:black}
                :card/types #{:instant}
                :card/text "Test."}]
      (is (= false (card-spec/valid-card? card)))))

  (testing "card without :card/types fails"
    (let [card {:card/id :bad-card
                :card/name "Bad Card"
                :card/cmc 1
                :card/mana-cost {:black 1}
                :card/colors #{:black}
                :card/text "Test."}]
      (is (= false (card-spec/valid-card? card))))))


(deftest test-invalid-effect-type-fails
  (testing "card with unknown effect type fails"
    (let [card {:card/id :bad-card
                :card/name "Bad Card"
                :card/cmc 1
                :card/mana-cost {:black 1}
                :card/colors #{:black}
                :card/types #{:instant}
                :card/text "Test."
                :card/effects [{:effect/type :nonexistent}]}]
      (is (= false (card-spec/valid-card? card))))))


(deftest test-invalid-mana-cost-fails
  (testing "card with string mana cost fails"
    (let [card {:card/id :bad-card
                :card/name "Bad Card"
                :card/cmc 1
                :card/mana-cost "bad"
                :card/colors #{:black}
                :card/types #{:instant}
                :card/text "Test."}]
      (is (= false (card-spec/valid-card? card))))))


(deftest test-empty-mana-cost-valid
  (testing "land with empty mana cost passes"
    (let [card {:card/id :test-land
                :card/name "Test Land"
                :card/cmc 0
                :card/mana-cost {}
                :card/colors #{}
                :card/types #{:land}
                :card/text "T: Add U."}]
      (is (= true (card-spec/valid-card? card))))))


(deftest test-x-mana-cost-valid
  (testing "card with :x true in mana cost passes"
    (let [card {:card/id :test-x
                :card/name "X Spell"
                :card/cmc 2
                :card/mana-cost {:colorless 1 :blue 1 :x true}
                :card/colors #{:blue}
                :card/types #{:instant}
                :card/text "X spell."}]
      (is (= true (card-spec/valid-card? card))))))


(deftest test-all-existing-cards-valid
  (testing "all 58 cards from all-cards pass validation"
    (doseq [card cards/all-cards]
      (is (= true (card-spec/valid-card? card))
          (str "Card failed validation: " (:card/name card))))))


(deftest test-validation-error-descriptive
  (testing "validate-cards! error includes card name in message"
    (let [bad-card {:card/id :bad-card
                    :card/name "Bad Card"
                    :card/cmc 1
                    :card/mana-cost "invalid"
                    :card/colors #{:black}
                    :card/types #{:instant}
                    :card/text "Test."}
          error (try
                  (card-spec/validate-cards! [bad-card])
                  nil
                  (catch :default e e))]
      (is (some? error) "validate-cards! should throw on invalid card")
      (is (re-find #"Bad Card" (ex-message error))
          "Error message should include card name"))))


(deftest test-validate-cards-passes-valid-cards
  (testing "validate-cards! succeeds with valid cards"
    (is (nil? (card-spec/validate-cards! cards/all-cards))
        "validate-cards! should return nil on success")))


(deftest test-card-with-abilities-valid
  (testing "card with abilities passes"
    (let [card {:card/id :test-land-ability
                :card/name "Test Land"
                :card/cmc 0
                :card/mana-cost {}
                :card/colors #{}
                :card/types #{:land}
                :card/text "T: Add U."
                :card/abilities [{:ability/type :mana
                                  :ability/cost {:tap true}
                                  :ability/produces {:blue 1}
                                  :ability/effects [{:effect/type :deal-damage
                                                     :effect/amount 1
                                                     :effect/target :controller}]}]}]
      (is (= true (card-spec/valid-card? card))))))


(deftest test-card-with-targeting-valid
  (testing "card with targeting passes"
    (let [card {:card/id :test-target
                :card/name "Target Spell"
                :card/cmc 2
                :card/mana-cost {:colorless 1 :blue 1}
                :card/colors #{:blue}
                :card/types #{:sorcery}
                :card/text "Target player draws two cards."
                :card/targeting [{:target/id :player
                                  :target/type :player
                                  :target/options [:self :opponent :any-player]
                                  :target/required true}]
                :card/effects [{:effect/type :draw
                                :effect/amount 2
                                :effect/target :any-player}]}]
      (is (= true (card-spec/valid-card? card))))))


(deftest test-card-with-alternate-costs-valid
  (testing "card with flashback alternate costs passes"
    (let [card {:card/id :test-flashback
                :card/name "Flashback Spell"
                :card/cmc 4
                :card/mana-cost {:colorless 3 :blue 1}
                :card/colors #{:blue}
                :card/types #{:sorcery}
                :card/text "Flashback."
                :card/effects [{:effect/type :draw :effect/amount 2 :effect/target :any-player}]
                :card/alternate-costs [{:alternate/id :flashback
                                        :alternate/zone :graveyard
                                        :alternate/mana-cost {:colorless 1 :blue 1}
                                        :alternate/additional-costs [{:cost/type :pay-life :cost/amount 3}]
                                        :alternate/on-resolve :exile}]}]
      (is (= true (card-spec/valid-card? card))))))


(deftest test-card-with-triggers-valid
  (testing "card with triggers passes"
    (let [card {:card/id :test-trigger
                :card/name "Trigger Land"
                :card/cmc 0
                :card/mana-cost {}
                :card/colors #{}
                :card/types #{:land}
                :card/text "T: Add U."
                :card/triggers [{:trigger/type :becomes-tapped
                                 :trigger/description "deals 1 damage"
                                 :trigger/effects [{:effect/type :deal-damage
                                                    :effect/amount 1
                                                    :effect/target :controller}]}]}]
      (is (= true (card-spec/valid-card? card))))))


(deftest test-card-with-conditional-effects-valid
  (testing "card with conditional effects passes"
    (let [card {:card/id :test-conditional
                :card/name "Conditional Spell"
                :card/cmc 2
                :card/mana-cost {:colorless 1 :black 1}
                :card/colors #{:black}
                :card/types #{:instant}
                :card/text "Add BBB. Threshold - Add BBBBB instead."
                :card/effects [{:effect/type :add-mana :effect/mana {:black 3}}]
                :card/conditional-effects {:threshold [{:effect/type :add-mana
                                                        :effect/mana {:black 5}}]}}]
      (is (= true (card-spec/valid-card? card))))))


(deftest test-card-with-kicker-valid
  (testing "card with kicker passes"
    (let [card {:card/id :test-kicker
                :card/name "Kicker Spell"
                :card/cmc 1
                :card/mana-cost {:white 1}
                :card/colors #{:white}
                :card/types #{:instant}
                :card/text "Kicker W."
                :card/kicker {:white 1}
                :card/effects [{:effect/type :add-restriction
                                :effect/target :opponent
                                :restriction/type :cannot-cast-spells}]
                :card/kicked-effects [{:effect/type :add-restriction
                                       :effect/target :opponent
                                       :restriction/type :cannot-attack}]}]
      (is (= true (card-spec/valid-card? card))))))
